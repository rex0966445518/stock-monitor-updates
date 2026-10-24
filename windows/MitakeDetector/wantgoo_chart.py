"""Capture the public, rendered WantGoo five-minute chart in an isolated Edge session.
No private API, login cookies, fingerprints or access-control workarounds.
"""
import datetime as dt
import io
import json
import os
from pathlib import Path
import queue
import re
import subprocess
import sys
import threading
import time


def chart_url(code):
    if not re.fullmatch(r'[0-9]{4}',str(code)):raise ValueError('股號必須為四位數字')
    return f'https://www.wantgoo.com/stock/{code}/technical-chart'


def taipei_day():return dt.datetime.now(dt.timezone(dt.timedelta(hours=8))).date().isoformat()


def validate_state(state,code):
    if not state.get('selected'):raise ValueError('尚未確認五分週期，拒絕顯示其他週期')
    if code not in state.get('title',''):raise ValueError('圖表股號不一致')
    stamp=state.get('stamp','').strip()
    try:dt.datetime.strptime(stamp,'%Y-%m-%d %H:%M')
    except ValueError:raise ValueError('圖表資料時間未載入')
    if state.get('candles',0)<2:raise ValueError('K線未載入完成')
    if state.get('loading'):raise ValueError('圖表仍在載入')
    return stamp


def validate_day_range(state,stamp):
    labels=state.get('axis_labels',[])
    day=dt.datetime.strptime(stamp,'%Y-%m-%d %H:%M').strftime('%m/%d')
    if not state.get('one_day'):raise ValueError('尚未確認當日範圍')
    if not labels or any(not str(label).startswith(day+' ') for label in labels):
        raise ValueError('圖表仍包含其他交易日，暫不顯示')
    if not any(str(label).endswith('09:00') for label in labels):
        raise ValueError('尚未確認開盤時間軸，暫不顯示')
    return day


STATE_JS="""() => {
const root=document.querySelector('#technical-chart svg.highcharts-root');
return {selected:!!document.querySelector('button[type="five-minutes"].active'),
one_day:[...document.querySelectorAll('#technical-chart g.highcharts-button-pressed')].some(e=>e.textContent.trim()==='1 天'),
axis_labels:[...document.querySelectorAll('#technical-chart g.highcharts-xaxis-labels:not(.highcharts-navigator-xaxis) text')].map(e=>e.textContent.trim()),
title:document.querySelector('h1')?.textContent||'',
stamp:document.querySelector('#ohlc [c-model="time"]')?.textContent||'',
candles:root?.querySelectorAll('g.highcharts-candlestick-series path').length||0,
loading:[...document.querySelectorAll('#technical-chart .highcharts-loading')].some(e=>{const s=getComputedStyle(e),r=e.getBoundingClientRect();return s.display!=='none' && s.visibility!=='hidden' && Number(s.opacity)>0.01 && r.width>0 && r.height>0})};
}"""


def wait_chart(page, predicate, code, stage, timeout):
    from playwright.sync_api import TimeoutError as PlaywrightTimeout
    try:
        page.wait_for_function(predicate, timeout=timeout)
    except PlaywrightTimeout as exc:
        state=page.evaluate(STATE_JS)
        reason=[]
        if not state.get('selected'):reason.append('五分週期未切換')
        if not re.fullmatch(r'\d{4}-\d{2}-\d{2} \d{2}:\d{2}',state.get('stamp','').strip()):reason.append('分K時間未載入')
        if state.get('candles',0)<2:reason.append('K線不足')
        if state.get('loading'):reason.append('圖表仍有載入遮罩')
        if stage=='單日範圍':
            try:validate_day_range(state,validate_state(state,code))
            except ValueError as error:reason.append(str(error))
        raise RuntimeError(stage+'等待逾時：'+('、'.join(reason) or '圖表尚未就緒')+'；可按「重試五分 K」或開啟原站確認。') from exc


def capture(page,code):
    from PIL import Image
    button=page.get_by_role('button',name='5分',exact=True)
    button.wait_for(state='visible',timeout=20000)
    button.click(timeout=10000)
    # A date-only daily title must become intraday; active button alone is not sufficient.
    wait_chart(page,"() => {const s=("+STATE_JS+")();return s.selected && /\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}/.test(s.stamp) && s.candles>=2 && !s.loading}",code,'五分K資料',timeout=25000)
    state=page.evaluate(STATE_JS);stamp=validate_state(state,code)
    page.get_by_text('1 天',exact=True).click(timeout=10000)
    wait_chart(page,"() => {const s=("+STATE_JS+")();const d=s.stamp.slice(5,10).replace('-','/');return s.one_day && s.axis_labels.length && s.axis_labels.every(t=>t.startsWith(d+' ')) && s.axis_labels.some(t=>t.endsWith('09:00')) && !s.loading}",code,'單日範圍',timeout=15000)
    validate_day_range(page.evaluate(STATE_JS),stamp)
    svg=page.locator('#technical-chart svg.highcharts-root')
    # Use rendered DOM geometry, never Highcharts' hidden runtime data.
    crop=svg.evaluate("e=>{const r=e.getBoundingClientRect(),g=e.querySelector('g.highcharts-yaxis-grid')?.getBoundingClientRect(),a=e.querySelector('g.highcharts-xaxis-labels:not(.highcharts-navigator-xaxis)')?.getBoundingClientRect();return g?{bottom:g.bottom-r.top+15,height:r.height,axis:a?{top:a.top-r.top,bottom:a.bottom-r.top}:null}:null}")
    raw=svg.screenshot(timeout=10000,animations='disabled')
    final=page.evaluate(STATE_JS)
    validate_day_range(final,validate_state(final,code))
    image=Image.open(io.BytesIO(raw));image.load()
    if image.width<100 or image.height<80:raise ValueError('圖表尺寸異常')
    if crop and 30<crop['bottom']<crop['height']:
        scale=image.height/crop['height']
        panel=image.crop((0,0,image.width,round(scale*crop['bottom'])))
        if crop.get('axis'):
            a=crop['axis'];axis=image.crop((0,max(0,round(scale*a['top'])-3),image.width,min(image.height,round(scale*a['bottom'])+3)))
            joined=Image.new('RGB',(image.width,panel.height+axis.height),'white');joined.paste(panel,(0,0));joined.paste(axis,(0,panel.height));image=joined
        else:image=panel
    thumb=io.BytesIO();image.save(thumb,format='PNG')
    return raw,thumb.getvalue(),stamp


def run_capture(code,directory,fixture=False):
    chart_url(code);directory=Path(directory);directory.mkdir(parents=True,exist_ok=True)
    report=dict(code=code,url=chart_url(code),period='5m',range_version=2,fetched_at=time.time(),day=taipei_day(),error='')
    try:
        from playwright.sync_api import sync_playwright
        with sync_playwright() as p:
            try:browser=p.chromium.launch(channel='msedge',headless=True,chromium_sandbox=True)
            except Exception as exc:raise RuntimeError('無法啟動 Microsoft Edge，請確認已安裝並可正常開啟。'+str(exc)[:160])
            try:
                context=browser.new_context(viewport={'width':1280,'height':1000},locale='zh-TW',timezone_id='Asia/Taipei')
                page=context.new_page();page.set_default_timeout(15000)
                if fixture:
                    page.set_content('''<div class="highcharts-loading" style="width:50px;height:50px">Unrelated chart</div><h1>測試股(2330) 技術分析</h1><button type="five-minutes" onclick="this.classList.add('active');document.querySelector('[c-model]').textContent='2026-01-02 13:30'">5分</button><ul id="ohlc"><span c-model="time">2026-01-02</span></ul><div id="technical-chart"><div class="highcharts-loading" style="opacity:0;display:block;width:100px;height:100px;position:absolute">Finished loading</div><svg class="highcharts-root" width="600" height="300"><rect width="600" height="300" fill="white"/><g class="highcharts-yaxis-grid"><path d="M0 30 L600 30 M0 200 L600 200" stroke="gray"/></g><g class="highcharts-candlestick-series"><path d="M100 50 L100 150" stroke="red"/><path d="M200 60 L200 180" stroke="green"/></g><g class="highcharts-xaxis-labels"><text x="20" y="220">01/01 09:00</text></g><g class="highcharts-button" onclick="this.classList.add('highcharts-button-pressed');document.querySelector('.highcharts-xaxis-labels text').textContent='01/02 09:00'"><text x="20" y="280">1 天</text></g></svg></div>''')
                else:
                    response=page.goto(chart_url(code),wait_until='domcontentloaded',timeout=30000)
                    if response and response.status>=400:raise RuntimeError(f'玩股網回應 HTTP {response.status}；未重試或繞過限制')
                    text=page.locator('body').inner_text(timeout=10000)[:3000].lower()
                    if any(s in text for s in ('verify you are human','checking your browser','access denied','驗證您是人類')):raise RuntimeError('網站要求驗證或限制存取；請使用原站檢視')
                full,thumb,stamp=capture(page,code)
                (directory/'full.png').write_bytes(full);(directory/'thumb.png').write_bytes(thumb)
                report['data_time']=stamp;report['fixture']=fixture
            finally:browser.close()
    except Exception as exc:report['error']=str(exc)[:700]
    report['fetched_at']=time.time()
    (directory/'result.json').write_text(json.dumps(report,ensure_ascii=False),encoding='utf-8')
    return report


class WantGooCharts:
    """One bounded worker, five-minute cache, no UI-thread browser calls."""
    source='玩股網'
    capture_flag='--wantgoo-capture'
    fallback_enabled=True
    capture_timeout=40
    def __init__(self,base,emit):
        self.base=Path(base)/'wantgoo-charts';self.emit=emit;self.queue=queue.Queue(maxsize=8)
        self.pending=set();self.lock=threading.Lock();self.stop=threading.Event();self.proc=None;self.thread=None
    def cached(self,code):
        chart_url(code);directory=self.base/code
        try:
            result=json.loads((directory/'result.json').read_text(encoding='utf-8'))
            if result.get('code')!=code or result.get('period')!='5m' or result.get('day')!=taipei_day():return None
            if result.get('fixture'):return None
            if not result.get('error') and result.get('range_version')!=2:return None
            if not result.get('error') and not all((directory/n).is_file() for n in ('full.png','thumb.png')):return None
            return dict(result,directory=str(directory))
        except (OSError,ValueError,TypeError):return None
    def request(self,code,force=False):
        chart_url(code)
        if self.stop.is_set():return
        cached=self.cached(code)
        if not force and cached and 0<=time.time()-cached.get('fetched_at',0)<(30 if cached.get('error') else 300):
            self.emit(cached);return
        with self.lock:
            if code in self.pending:return
            self.pending.add(code)
            self.emit(dict(code=code,loading=True,error=f'排隊載入{self.source}五分 K…'))
            try:self.queue.put_nowait(code)
            except queue.Full:
                self.pending.discard(code)
                self.emit(dict(code=code,error='圖表佇列已滿，請按重試；掃描繼續'))
                return
            if not self.thread:
                self.thread=threading.Thread(target=self.work,daemon=True,name='wantgoo-chart-worker');self.thread.start()
    def work(self):
        while not self.stop.is_set():
            try:code=self.queue.get(timeout=.5)
            except queue.Empty:continue
            try:
                if self.stop.is_set():break
                self.emit(dict(code=code,loading=True,error='載入並確認五分 K 圖表…'))
                directory=self.base/code;directory.mkdir(parents=True,exist_ok=True)
                (directory/'result.json').unlink(missing_ok=True)
                command=[sys.executable]+([] if getattr(sys,'frozen',False) else [str(Path(__file__).with_name('MitakeDetector.py'))])+[self.capture_flag,code,str(directory)]
                env=os.environ.copy();env['PYINSTALLER_RESET_ENVIRONMENT']='1'
                with self.lock:
                    if self.stop.is_set():break
                    self.proc=subprocess.Popen(command,env=env,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,creationflags=getattr(subprocess,'CREATE_NO_WINDOW',0))
                try:self.proc.wait(timeout=self.capture_timeout)
                except subprocess.TimeoutExpired:
                    self.kill();raise RuntimeError(f'圖表載入超過 {self.capture_timeout} 秒，稍後再試；掃描繼續')
                result=self.cached(code)
                if not result:raise RuntimeError('圖表程序未回傳有效結果，請檢查 Edge 或改開原站')
                if result.get('error') and self.fallback_enabled:raise RuntimeError(result['error'])
                if not self.stop.is_set():self.emit(result)
            except Exception as exc:
                result=dict(code=code,error=str(exc),day=taipei_day(),period='5m',range_version=2,fetched_at=time.time())
                if self.stop.is_set():break
                try:
                    if not self.fallback_enabled:raise RuntimeError('保留指定來源，請開啟原站確認')
                    from intraday_chart import fallback_chart
                    self.emit(dict(code=code,loading=True,error='原站未就緒，改用五分鐘行情繪圖…'))
                    result=fallback_chart(code,self.base/code)
                except Exception as fallback_error:
                    result['error']+='；'+str(fallback_error)
                try:(self.base/code/'result.json').write_text(json.dumps(result,ensure_ascii=False),encoding='utf-8')
                except OSError:pass
                if not self.stop.is_set():self.emit(result)
            finally:
                with self.lock:self.pending.discard(code);self.proc=None
                self.queue.task_done()
    def kill(self):
        proc=self.proc
        if proc and proc.poll() is None:
            if sys.platform=='win32':subprocess.run(['taskkill','/PID',str(proc.pid),'/T','/F'],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,creationflags=subprocess.CREATE_NO_WINDOW,timeout=10)
            else:proc.kill()
    def close(self):
        self.stop.set()
        with self.lock:
            try:self.kill()
            except (OSError,subprocess.SubprocessError):pass
