"""Public Investing.com rendered chart capture; no private data endpoints."""
import datetime as dt
import io
import json
from pathlib import Path
import re
import time
from urllib.parse import urlparse
from wantgoo_chart import WantGooCharts, taipei_day

HOME='https://www.investing.com/'
KNOWN={'5202':HOME+'equities/newsoft-technology-chart',
       '2330':HOME+'equities/taiwan-semicon-chart'}

def chart_url(code):
    if not re.fullmatch(r'[0-9]{4}',str(code)):raise ValueError('股號必須為四位數字')
    return KNOWN.get(code,HOME+'search/?q='+code)

def safe_chart_url(url):
    parsed=urlparse(url)
    return parsed.scheme=='https' and parsed.netloc=='www.investing.com' and bool(re.fullmatch(r'/equities/[a-z0-9-]+-chart',parsed.path))

def validate_chart(state,code,name):
    if not re.search(r'\('+re.escape(code)+r'\)',state.get('heading','')):raise ValueError('Investing.com 股號不符')
    if not state.get('selected') or not re.search(r',\s*5\s*,',state.get('legend','')):
        raise ValueError('Investing.com 尚未提供或完成切換五分 K；請開啟原站確認')
    if not name or not state.get('legend','').startswith(name+','):raise ValueError('圖表股票名稱不符')
    if not state.get('candles'):raise ValueError('尚未切換蠟燭 K 線')
    values=state.get('values',[])
    if len(values)!=4:raise ValueError('五分 K 行情未載入')
    try:o,h,l,c=[float(str(x).replace(',','')) for x in values]
    except (ValueError,TypeError):raise ValueError('五分 K 行情未載入')
    if not 0<l<=min(o,c)<=max(o,c)<=h:raise ValueError('五分 K 行情不完整')
    if not state.get('canvas'):raise ValueError('圖表尚未繪製')

STATE_JS="""() => ({
 selected:!!document.querySelector('.quick [title="5 minute"].selected'),
 candles:!!document.querySelector('.quick [title="Candles"].selected'),
 legend:document.querySelector('span.pane-legend-line.main')?.textContent.trim()||'',
 values:[...document.querySelectorAll('.pane-legend-item-value__main')].map(e=>e.textContent.trim()),
 canvas:[...document.querySelectorAll('canvas')].some(e=>e.width>200&&e.height>100)
})"""

def check_page(page,response=None):
    if response and response.status>=400:raise RuntimeError(f'Investing.com 回應 HTTP {response.status}；請開啟原站確認')
    text=page.locator('body').inner_text(timeout=10000)[:5000].lower()
    if any(t in text for t in ('verify you are human','checking your browser','access denied','just a moment')):
        raise RuntimeError('Investing.com 要求驗證或限制存取；請開啟原站，掃描仍會繼續')

def resolve_chart(page,code):
    if code in KNOWN:
        check_page(page,page.goto(KNOWN[code],wait_until='domcontentloaded',timeout=25000))
        return
    check_page(page,page.goto(HOME,wait_until='domcontentloaded',timeout=25000))
    page.get_by_role('button',name='Open search',exact=True).click()
    page.get_by_role('combobox',name='Search Investing.com',exact=True).fill(code)
    # Match the exact symbol AND Taiwan exchange; do not choose a foreign ticker.
    matches=page.get_by_role('option').filter(has_text=re.compile(r'^'+code+r'\s')).filter(
        has_text=re.compile(r'(Taiwan|Taipei|TPEX).*\(TW\)'))
    matches.first.wait_for(state='visible',timeout=15000)
    if matches.count()!=1:raise ValueError('Investing.com 台股搜尋結果不唯一，請開啟原站確認')
    matches.click()
    page.get_by_role('heading',level=1).filter(has_text=re.compile(r'\('+code+r'\)')).wait_for(timeout=15000)
    link=page.get_by_role('link',name='Chart',exact=True)
    url=link.get_attribute('href')
    if not safe_chart_url(url or ''):raise ValueError('未找到可確認的 Investing.com 圖表連結')
    check_page(page,page.goto(url,wait_until='domcontentloaded',timeout=25000))

def capture(page,code,fixture=False):
    heading=page.get_by_role('heading',level=1).inner_text()
    if not re.search(r'\('+code+r'\)',heading):raise ValueError('Investing.com 股號不符')
    name=heading.rsplit('(',1)[0].strip()
    deadline=time.monotonic()+15;frame=None
    while time.monotonic()<deadline:
        frame=next((f for f in page.frames if f.name.startswith('tradingview_')),None)
        if frame:break
        page.wait_for_timeout(200)
    if frame is None:raise RuntimeError('Investing.com 圖表尚未載入；請開啟原站確認')
    five=frame.locator('.quick span').filter(has_text=re.compile(r'^5$'))
    five.wait_for(state='visible',timeout=12000)
    if 'disabled' in (five.get_attribute('class') or ''):
        raise ValueError('Investing.com 此股票的五分鐘選項停用，未提供五分 K；可開啟原站查看其他週期')
    five.click(timeout=5000)
    frame.locator('[title="Candles"]').click(timeout=5000)
    try:
        frame.wait_for_function(r"""() => {
            const s=SELECTOR();return s.selected&&s.candles&&/,\s*5\s*,/.test(s.legend)&&
            s.values.length===4&&s.values.every(v=>Number.isFinite(Number(v.replaceAll(',',''))));
        }""".replace('SELECTOR', '('+STATE_JS+')'),timeout=15000)
    except Exception as exc:raise RuntimeError('Investing.com 五分 K 尚未就緒或不提供此週期；請開啟原站確認') from exc
    state=frame.evaluate(STATE_JS);state['heading']=heading;validate_chart(state,code,name)
    # Keep the original time axis, watermark and legend; no fabricated data time.
    raw=frame.locator('table.chart-markup-table').first.screenshot(animations='disabled',timeout=10000)
    state=frame.evaluate(STATE_JS);state['heading']=page.get_by_role('heading',level=1).inner_text()
    validate_chart(state,code,name)
    from PIL import Image
    image=Image.open(io.BytesIO(raw));image.load()
    if image.width<200 or image.height<100:raise ValueError('圖表尺寸異常')
    return raw

def run_capture(code,directory,fixture=False):
    chart_url(code);directory=Path(directory);directory.mkdir(parents=True,exist_ok=True)
    result=dict(code=code,url=chart_url(code),source='Investing.com',period='5m',range_version=2,
                day=taipei_day(),fetched_at=time.time(),error='',fixture=fixture)
    try:
        from playwright.sync_api import sync_playwright
        with sync_playwright() as p:
            browser=p.chromium.launch(channel='msedge',headless=True,chromium_sandbox=True)
            try:
                page=browser.new_page(viewport={'width':1280,'height':1000},locale='en-US',timezone_id='Asia/Taipei')
                page.set_default_timeout(15000)
                if fixture:
                    page.set_content('<h1>Test (2330)</h1><iframe name="tradingview_fixture" style="width:700px;height:400px"></iframe>')
                    frame=page.frames[1]
                    frame.set_content("""<div class="quick"><span title="5 minute" onclick="this.classList.add('selected');document.querySelector('.main').textContent='Test, Taiwan, 5, Taiwan'">5</span><span title="Candles" onclick="this.classList.add('selected')">Candles</span></div><table class="chart-markup-table"><tr><td><span class="pane-legend-line main">Test, Taiwan, D, Taiwan</span><span class="pane-legend-item-value__main">100</span><span class="pane-legend-item-value__main">102</span><span class="pane-legend-item-value__main">99</span><span class="pane-legend-item-value__main">101</span><canvas width="600" height="250"></canvas></td></tr></table><script>let c=document.querySelector('canvas').getContext('2d');c.fillStyle='white';c.fillRect(0,0,600,250);c.fillStyle='red';c.fillRect(80,60,15,100);c.fillStyle='green';c.fillRect(150,40,15,90);</script>""")
                else:
                    resolve_chart(page,code)
                    result['url']=page.url
                raw=capture(page,code,fixture)
                (directory/'full.png').write_bytes(raw);(directory/'thumb.png').write_bytes(raw)
                result['data_time']='依原圖時間軸'
                result['capture_time']=dt.datetime.now(dt.timezone(dt.timedelta(hours=8))).strftime('%Y-%m-%d %H:%M:%S')
            finally:browser.close()
    except Exception as exc:result['error']=str(exc)[:700]
    result['fetched_at']=time.time()
    (directory/'result.json').write_text(json.dumps(result,ensure_ascii=False),encoding='utf-8')
    return result

class InvestingCharts(WantGooCharts):
    source='Investing.com'
    capture_flag='--investing-capture'
    fallback_enabled=True
    capture_timeout=35
    def __init__(self,base,emit):
        super().__init__(base,emit)
        self.base=Path(base)/'investing-charts'

