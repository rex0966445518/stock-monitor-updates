"""Public TDCC dated query, with server form token and strict response identity checks."""
import datetime as dt
import html
import http.cookiejar
import re
import urllib.parse
import urllib.request
from research_sources import date,number,code
URL='https://www.tdcc.com.tw/portal/zh/smWeb/qryStock'

def text(value):return html.unescape(re.sub('<[^>]*>',' ',value)).strip()

def available_dates(page):
    match=re.search(r'<select[^>]*name="scaDate"[^>]*>(.*?)</select>',page,re.S)
    if not match:raise ValueError('集保歷史日期選單格式改變')
    return [v for v in re.findall(r'<option[^>]*value="(\d{8})"',match[1]) if date(v)<=dt.date.today().isoformat()]

def parse(page,ticker,stamp):
    plain=text(page)
    if not re.search(r'證券代號：\s*'+re.escape(ticker)+r'\b',plain):raise ValueError('集保回傳股號不符')
    match=re.search(r'資料日期：\s*(\d{3,4})年(\d{1,2})月(\d{1,2})日',plain)
    if not match:raise ValueError('集保回傳日期缺漏')
    y,m,d=map(int,match.groups());y=y+1911 if y<1911 else y
    if dt.date(y,m,d).isoformat()!=date(stamp):raise ValueError('集保回傳日期不符')
    tiers={}
    for row in re.findall(r'<tr[^>]*>(.*?)</tr>',page,re.S):
        cells=[text(v) for v in re.findall(r'<td[^>]*>(.*?)</td>',row,re.S)]
        if len(cells)!=5 or not cells[0].isdigit():continue
        tier=int(cells[0])
        if 1<=tier<=17:tiers[tier]=(number(cells[2] or 0),number(cells[3]))
    if set(tiers)!=set(range(1,18)):raise ValueError('集保級距不完整')
    total=tiers[17][1]
    if total<=0:raise ValueError('集保總股數無效')
    return [('weekly',ticker,date(stamp),dict(large=sum(tiers[i][1] for i in range(12,16))/total*100,small=sum(tiers[i][1] for i in range(1,4))/total*100,total=total,holders=tiers[17][0]))]

class TDCC:
    def __init__(self):self.opener=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
    def get(self,data=None):
        req=urllib.request.Request(URL,data=urllib.parse.urlencode(data).encode() if data else None,headers={'User-Agent':'MitakeDetector public research/0.3.56'})
        with self.opener.open(req,timeout=10) as r:
            if urllib.parse.urlparse(r.geturl()).hostname!='www.tdcc.com.tw':raise ValueError('集保重新導向異常')
            raw=r.read(1024*1024+1)
        if len(raw)>1024*1024:raise ValueError('集保回應過大')
        return raw.decode('utf-8-sig')
    def history(self,ticker,existing,stop):
        if not code(ticker):return
        page=self.get();dates=available_dates(page)[:3]
        for stamp in dates:
            if stop.is_set():return
            if date(stamp) in existing:continue
            fields=dict(re.findall(r'<input[^>]*name="([^"]+)"[^>]*value="([^"]*)"',page))
            if 'SYNCHRONIZER_TOKEN' not in fields:raise ValueError('集保表單權杖未取得')
            fields.update(scaDate=stamp,stockNo=ticker,stockName='',sqlMethod='StockNo',method='submit')
            page=self.get(fields)
            yield parse(page,ticker,stamp),URL+'?stockNo='+ticker+'&scaDate='+stamp
            if stop.wait(.5):return
