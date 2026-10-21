"""Bounded public MIS quote refresh; exchange timestamps are preserved."""
import datetime as dt
import json
import math
import re
import time
import urllib.request
from urllib.parse import urlencode,urlparse
from candidate_diary import TAIPEI

ENDPOINT='https://mis.twse.com.tw/stock/api/getStockInfo.jsp'

def parse_quotes(data,codes,now=None):
    if data.get('rtcode')!='0000':raise ValueError('行情來源回傳錯誤')
    now=time.time() if now is None else now
    found={};ambiguous=set()
    for row in data.get('msgArray',[]):
        code=row.get('c')
        if code not in codes or row.get('ex') not in ('tse','otc'):continue
        try:
            price=float(row['z'])
            stamp=dt.datetime.strptime(row['d']+' '+row['t'],'%Y%m%d %H:%M:%S').replace(tzinfo=TAIPEI).timestamp()
            if not math.isfinite(price) or price<=0 or stamp>now+60:continue
        except (KeyError,ValueError,TypeError):continue
        if code in found:ambiguous.add(code)
        found[code]=dict(code=code,price=price,ts=stamp,source='證交所MIS／'+row['ex'])
    return [q for code,q in found.items() if code not in ambiguous]

def fetch_quotes(codes,stop):
    codes=sorted({c for c in codes if re.fullmatch(r'[0-9]{4}',c)})
    result=[];errors=[]
    for offset in range(0,len(codes),20):
        if stop.is_set():break
        batch=codes[offset:offset+20]
        params=dict(ex_ch='|'.join(f'{market}_{c}.tw' for c in batch for market in ('tse','otc')),json=1,delay=0,_=int(time.time()*1000))
        request=urllib.request.Request(ENDPOINT+'?'+urlencode(params),headers={'User-Agent':'Mozilla/5.0','Referer':'https://mis.twse.com.tw/stock/index.jsp','Cache-Control':'no-cache'})
        try:
            with urllib.request.urlopen(request,timeout=8) as response:
                if urlparse(response.geturl()).hostname!='mis.twse.com.tw':raise ValueError('行情來源重新導向異常')
                raw=response.read(1024*1024+1)
            if len(raw)>1024*1024:raise ValueError('行情回應過大')
            result.extend(parse_quotes(json.loads(raw.decode('utf-8-sig')),batch))
        except Exception as exc:errors.append(str(exc)[:120])
        if offset+20<len(codes) and stop.wait(.2):break
    missing=len(set(codes)-{q['code'] for q in result})
    return result,(f'{missing}檔未取得成交價，保留前值／時間'+('：'+errors[0] if errors else '') if missing else '')
