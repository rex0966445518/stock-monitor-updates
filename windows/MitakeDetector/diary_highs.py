"""Forward daily highs from published monthly exchange OHLC, never OCR samples."""
from contextlib import closing
import datetime as dt
import json
import math
import re
import sqlite3
import time
from candidate_diary import TAIPEI
from research_sources import fetch,monthly

def summarize(rows,start,today=None):
    today=today or dt.datetime.now(TAIPEI).date()
    valid={}
    for row in rows:
        try:
            day=dt.date.fromisoformat(row['day'])
            o,h,l,c=[float(row[k]) for k in ('open','high','low','close')]
            if not start<=day<=today or not all(math.isfinite(v) for v in (o,h,l,c)):continue
            if not 0<l<=min(o,c)<=max(o,c)<=h:continue
            valid[day]=h
        except (ValueError,TypeError,KeyError):continue
    ordered=sorted(valid.items())[:10]
    result={}
    for n in (5,10):
        sample=ordered[:n]
        result[n]=dict(high=max((h for _,h in sample),default=None),count=len(sample),
                       complete=len(sample)==n,end=sample[-1][0].isoformat() if sample else '')
    return result

class DiaryHighs:
    def __init__(self,path):self.path=path
    def connect(self):
        c=sqlite3.connect(self.path,timeout=5)
        c.execute('CREATE TABLE IF NOT EXISTS diary_high_months(code TEXT,month TEXT,body TEXT,source TEXT,fetched REAL,PRIMARY KEY(code,month))')
        return c
    def read_month(self,code,month):
        with closing(self.connect()) as c:
            row=c.execute('SELECT body,source,fetched FROM diary_high_months WHERE code=? AND month=?',(code,month)).fetchone()
        return (json.loads(row[0]),row[1],row[2]) if row else None
    def save_month(self,code,month,rows,source):
        with closing(self.connect()) as c,c:
            c.execute('INSERT OR REPLACE INTO diary_high_months VALUES (?,?,?,?,?)',
                      (code,month,json.dumps(rows,ensure_ascii=False,allow_nan=False),source,time.time()))
    def months(self,start,today):
        index=start.year*12+start.month-1
        for offset in range(3):
            year,month=divmod(index+offset,12);day=dt.date(year,month+1,1)
            if day>today:break
            yield day
    def result(self,code,start):
        today=dt.datetime.now(TAIPEI).date();rows=[]
        for month in self.months(start,today):
            saved=self.read_month(code,month.strftime('%Y-%m'))
            if not saved:break # A missing earlier month must not shift the window.
            rows.extend(saved[0])
            if summarize(rows,start,today)[10]['complete']:break
        return summarize(rows,start,today)
    def refresh(self,codes,start,stop,fetcher=fetch):
        today=dt.datetime.now(TAIPEI).date();errors=0
        for code in codes:
            if stop.is_set():break
            if not re.fullmatch(r'[0-9]{4}',code):continue
            rows=[];market=None
            for month in self.months(start,today):
                if stop.is_set():break
                key=month.strftime('%Y-%m');saved=self.read_month(code,key)
                finalized=saved and dt.datetime.fromtimestamp(saved[2],TAIPEI).strftime('%Y-%m')>key
                if saved and (finalized or time.time()-saved[2]<900):
                    rows.extend(saved[0]);market=saved[1]
                else:
                    success=False
                    for candidate in ([market] if market else ['TWSE','TPEx']):
                        if stop.is_set():break
                        url=(f'https://www.twse.com.tw/rwd/zh/afterTrading/STOCK_DAY?date={month:%Y%m}01&stockNo={code}&response=json'
                             if candidate=='TWSE' else
                             f'https://www.tpex.org.tw/www/zh-tw/afterTrading/tradingStock?code={code}&date={month:%Y/%m}/01&response=json')
                        try:
                            facts=monthly(json.loads(fetcher(url)),code,candidate)
                            records=[dict(body,day=day) for kind,ticker,day,body in facts
                                     if ticker==code and day[:7]==key and day<=today.isoformat()]
                            if not records:raise ValueError('尚無日線')
                            if stop.is_set():break
                            self.save_month(code,key,records,candidate)
                            rows.extend(records);market=candidate;success=True;break
                        except Exception:continue
                    if not success:
                        errors+=1
                        break
                    if stop.wait(.2):break
                if summarize(rows,start,today)[10]['complete']:break
        return f'{errors}檔／月份日線待補' if errors else '日線最高價已更新'

def display(value,n):
    if value['high'] is None:return '待補日線'
    text=f"{value['high']:.2f}"
    return text if value['complete'] else text+f"（{value['count']}/{n}日）"
