"""Validated public five-minute OHLC fallback, rendered without browser automation."""
import datetime as dt
import json
import math
from pathlib import Path
import time
import urllib.request
from urllib.parse import urlparse

TZ=dt.timezone(dt.timedelta(hours=8))

def parse_bars(payload,symbol):
    chart=payload['chart']
    if chart.get('error'):raise ValueError('行情來源回傳錯誤')
    result=chart['result'][0];meta=result['meta']
    if meta.get('symbol')!=symbol or meta.get('dataGranularity')!='5m' or meta.get('currency')!='TWD':
        raise ValueError('股號、幣別或五分鐘週期不符')
    q=result['indicators']['quote'][0];bars={}
    for i,stamp in enumerate(result.get('timestamp',[])):
        try:
            stamp=int(stamp);date=dt.datetime.fromtimestamp(stamp,TZ)
            values=[float(q[k][i]) for k in ('open','high','low','close','volume')]
            o,h,l,c,v=values
            if not all(math.isfinite(n) for n in values) or min(o,h,l,c)<=0 or v<0:continue
            if not l<=min(o,c)<=max(o,c)<=h:continue
            if stamp>time.time()+60 or date.hour*60+date.minute not in range(540,811) or date.minute%5:continue
            bars[stamp]=dict(ts=stamp,open=o,high=h,low=l,close=c,volume=v)
        except (ValueError,TypeError,IndexError,OverflowError):continue
    if not bars:raise ValueError('沒有有效五分鐘行情')
    latest=dt.datetime.fromtimestamp(max(bars),TZ).date()
    rows=[bars[t] for t in sorted(bars) if dt.datetime.fromtimestamp(t,TZ).date()==latest]
    if len(rows)<2:raise ValueError('五分鐘行情不足兩根K線')
    return rows

def render_bars(rows,code,directory):
    from PIL import Image,ImageDraw
    im=Image.new('RGB',(1000,570),'#102a40');d=ImageDraw.Draw(im)
    left,right,top,bottom=80,975,55,410
    lo=min(r['low'] for r in rows);hi=max(r['high'] for r in rows);pad=max((hi-lo)*.08,.01);lo-=pad;hi+=pad
    y=lambda p:top+(hi-p)/(hi-lo)*(bottom-top)
    # Use clock positions, leaving missing bars empty instead of compressing time.
    def x(r):
        t=dt.datetime.fromtimestamp(r['ts'],TZ)
        return left+(t.hour*60+t.minute-540)/270*(right-left)
    d.text((left,15),f'{code} | Yahoo Finance | 5 min | TWD',fill='white')
    for i in range(5):
        p=lo+(hi-lo)*i/4;yy=y(p);d.line((left,yy,right,yy),fill='#29465c');d.text((8,yy-5),f'{p:.2f}',fill='white')
    vmax=max(1,max(r['volume'] for r in rows))
    for r in rows:
        xx=x(r);color='#ff5269' if r['close']>=r['open'] else '#37d6a5'
        d.line((xx,y(r['high']),xx,y(r['low'])),fill=color,width=2)
        a,b=sorted((y(r['open']),y(r['close'])));d.rectangle((xx-4,a,xx+4,max(a+1,b)),fill=color)
        d.rectangle((xx-4,515-r['volume']/vmax*65,xx+4,515),fill=color)
    for minute in (540,600,660,720,780,810):
        xx=left+(minute-540)/270*(right-left);d.text((xx-15,420),f'{minute//60:02}:{minute%60:02}',fill='white')
    stamp=dt.datetime.fromtimestamp(rows[-1]['ts'],TZ).strftime('%Y-%m-%d %H:%M')
    d.text((left,540),f'{stamp} Taipei | May be delayed | Volume: shares',fill='white')
    directory=Path(directory);directory.mkdir(parents=True,exist_ok=True)
    for name in ('full.png','thumb.png'):im.save(directory/name)
    return stamp

def fallback_chart(code,directory):
    if len(code)!=4 or not code.isascii() or not code.isdigit():raise ValueError('股號必須為四位數字')
    errors=[]
    for suffix in ('.TW','.TWO'):
        symbol=code+suffix
        try:
            url=f'https://query1.finance.yahoo.com/v8/finance/chart/{symbol}?interval=5m&range=1d'
            req=urllib.request.Request(url,headers={'User-Agent':'Mozilla/5.0'})
            with urllib.request.urlopen(req,timeout=6) as response:
                if urlparse(response.geturl()).hostname!='query1.finance.yahoo.com':raise ValueError('來源異常')
                raw=response.read(1048577)
            if len(raw)>1048576:raise ValueError('回應過大')
            rows=parse_bars(json.loads(raw),symbol);stamp=render_bars(rows,code,directory)
            return dict(code=code,error='',source='Yahoo Finance 備援・可能延遲',period='5m',range_version=2,
                        day=dt.datetime.now(TZ).date().isoformat(),data_time=stamp,fetched_at=time.time(),directory=str(directory))
        except Exception as exc:errors.append(str(exc)[:120])
    raise RuntimeError('備援行情未取得：'+'；'.join(errors))
