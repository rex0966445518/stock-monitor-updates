"""Background TWSE MIS index display. Source time is never replaced with fetch time."""
import datetime as dt
import json
import math
import threading
import time
import urllib.request
from urllib.parse import urlparse
TAIPEI=dt.timezone(dt.timedelta(hours=8))
URL='https://mis.twse.com.tw/stock/api/getStockInfo.jsp?ex_ch=tse_t00.tw&json=1&delay=0'

def parse(data):
    if data.get('rtcode')!='0000':raise ValueError('證交所回傳錯誤狀態')
    items=[r for r in data.get('msgArray',[]) if r.get('c')=='t00' and r.get('ex')=='tse']
    if len(items)!=1:raise ValueError('未取得加權指數')
    row=items[0]
    price=float(row['z']);previous=float(row['y'])
    if not all(math.isfinite(v) and v>0 for v in (price,previous)):raise ValueError('指數數值無效')
    stamp=dt.datetime.strptime(row['d']+' '+row['t'],'%Y%m%d %H:%M:%S').replace(tzinfo=TAIPEI).timestamp()
    return dict(price=price,change=price-previous,percent=(price/previous-1)*100,ts=stamp,source=URL)

def fetch():
    request=urllib.request.Request(URL+'&_='+str(int(time.time()*1000)),headers={'User-Agent':'Mozilla/5.0','Referer':'https://mis.twse.com.tw/stock/index.jsp','Cache-Control':'no-cache'})
    with urllib.request.urlopen(request,timeout=8) as response:
        if urlparse(response.geturl()).hostname!='mis.twse.com.tw':raise ValueError('指數來源重新導向異常')
        raw=response.read(1024*1024+1)
    if len(raw)>1024*1024:raise ValueError('指數回應過大')
    return parse(json.loads(raw.decode('utf-8-sig')))

def presentation(quote,error='',now=None):
    if not quote:return ('台股加權指數\n行情待取得'+('／更新失敗' if error else ''),'#a6b8d2')
    now=time.time() if now is None else now
    change=quote['change'];color='#ff697f' if change>0 else '#59d99d' if change<0 else '#d7dfef'
    stamp=dt.datetime.fromtimestamp(quote['ts'],TAIPEI)
    fresh=-10<=now-quote['ts']<=90
    state='最新來源行情' if fresh and not error else '更新失敗／保留前值' if error else '非即時／最近行情'
    text=f"台股加權 {quote['price']:,.2f}\n{change:+,.2f}點（{quote['percent']:+.2f}%）\n{stamp:%m/%d %H:%M:%S}｜{state}"
    return text,color

class MarketIndex:
    def __init__(self,emit,fetcher=fetch):
        self.emit=emit;self.fetcher=fetcher;self.stop=threading.Event()
    def start(self):
        self.thread=threading.Thread(target=self.run,daemon=True);self.thread.start()
    def run(self):
        while not self.stop.is_set():
            try:self.emit(dict(quote=self.fetcher(),error=''))
            except Exception as exc:self.emit(dict(error=str(exc)))
            if self.stop.wait(15):return
    def close(self):self.stop.set()
