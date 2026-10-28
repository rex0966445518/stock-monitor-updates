"""Background quote and pairing display; never blocks the OCR/UI loop."""
import datetime as dt
import json
import math
import threading
import time
import urllib.request
from urllib.parse import urlencode,urlparse
from market_index import TAIPEI

URL='https://query1.finance.yahoo.com/v8/finance/chart/6976.T?interval=1m&range=1d'

def parse_quote(data):
    chart=data.get('chart',{})
    if chart.get('error'):raise ValueError('行情來源回傳錯誤')
    meta=chart['result'][0]['meta']
    if meta.get('symbol')!='6976.T' or meta.get('currency')!='JPY':raise ValueError('行情代號或幣別不符')
    price=float(meta['regularMarketPrice']);previous=float(meta['previousClose']);stamp=float(meta['regularMarketTime'])
    if not all(math.isfinite(v) and v>0 for v in (price,previous,stamp)):raise ValueError('行情數值無效')
    return dict(price=price,percent=(price/previous-1)*100,ts=stamp)

def fetch_quote():
    req=urllib.request.Request(URL,headers={'User-Agent':'Mozilla/5.0','Cache-Control':'no-cache'})
    with urllib.request.urlopen(req,timeout=8) as response:
        if urlparse(response.geturl()).hostname!='query1.finance.yahoo.com':raise ValueError('行情來源異常')
        raw=response.read(1048577)
    if len(raw)>1048576:raise ValueError('行情回應過大')
    return parse_quote(json.loads(raw))

def quote_text(quote,error=''):
    if not quote:return '太陽誘電 6976\n行情待取得'+('／更新失敗' if error else ''),'#9fb6ca'
    stamp=dt.datetime.fromtimestamp(quote['ts'],TAIPEI)
    pct=quote['percent']
    return (f'太陽誘電 {pct:+.2f}%（較昨收）\n{stamp:%m/%d %H:%M:%S} 台灣時間\nYahoo行情・可能延遲'+('／更新失敗，保留前值' if error else ''),
            '#ff5269' if pct>0 else '#37d6a5' if pct<0 else '#e4edf6')

def pairing_image(bridge,addresses,preferred=None):
    import qrcode
    if not bridge.server:return None,'手機連線未啟用｜點此設定'
    address=preferred if preferred in addresses else next((ip for ip in addresses if not ip.startswith('127.')),None)
    if not address or address.startswith('127.'):return None,'未取得區網位址｜點此設定'
    url='mitake://pair?'+urlencode(dict(address=f'http://{address}:{bridge.port}',token=bridge.token))
    # Preserve modules and quiet zone; never interpolate QR pixels.
    qr=qrcode.QRCode(box_size=3,border=4);qr.add_data(url);qr.make(fit=True)
    return qr.make_image(fill_color='black',back_color='white').convert('RGB'),f'手機掃碼｜每分鐘更新\n{address}｜{dt.datetime.now(TAIPEI):%H:%M:%S}'

class HeaderExtras:
    def __init__(self,app,fetcher=fetch_quote):
        self.app=app;self.fetcher=fetcher;self.stop=threading.Event();self.quote=None
    def start(self):
        self.bridge=self.app.get_mobile_bridge()
        try:self.bridge.start()
        except OSError as exc:self.app.say('手機連線服務啟用失敗：'+str(exc))
        self.thread=threading.Thread(target=self.run,daemon=True,name='header-quotes-pairing');self.thread.start()
    def run(self):
        from mobile_bridge import local_addresses
        while not self.stop.is_set():
            started=time.monotonic()
            try:
                im,text=pairing_image(self.bridge,local_addresses(),self.app.config.get('mobile_address'))
                self.app.events.put((None,'header_qr',(im,text)))
            except Exception:self.app.events.put((None,'header_qr',(None,'配對碼更新失敗｜點此設定')))
            try:
                quote=self.fetcher();self.app.events.put((None,'header_quote',dict(quote=quote,error='')))
            except Exception:self.app.events.put((None,'header_quote',dict(error='更新失敗')))
            if self.stop.wait(max(0,60-(time.monotonic()-started))):return
    def receive_quote(self,payload):
        q=payload.get('quote')
        if q and (self.quote is None or q['ts']>=self.quote['ts']):self.quote=q
        text,color=quote_text(self.quote,payload.get('error',''))
        self.app.taiyo_var.set(text);self.app.taiyo_label.configure(fg=color)
    def close(self):self.stop.set()

def receive_qr(app,payload):
    from PIL import ImageTk
    image,text=payload
    app.header_qr_photo=ImageTk.PhotoImage(image) if image is not None else None
    app.header_qr.configure(image=app.header_qr_photo or '',text='' if image is not None else 'QR Code 暫不可用')
    app.header_qr_status.set(text)
