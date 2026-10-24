"""Causal, cross-ranking sampled volume contraction profiles (no synthetic bars)."""
import datetime as dt
import math
import re
from collections import deque

TZ = dt.timezone(dt.timedelta(hours=8))
MODES = {'original': '原有轉強條件', 'shrink': '盤中縮量上推', 'combined': '原有＋縮量同時符合'}
RULES = [
    ('price_up', '成交價逐段墊高', True),
    ('volume_down', '成交量逐段縮小', True),
    ('last_shrink', '最新一段縮量達門檻', True),
    ('mean_shrink', '最新量低於前段均量', False),
    ('rise', '首末段末價格漲幅達門檻', True),
    ('tick', '每次上推至少一檔（一般股票）', False),
    ('above_average', '成交價站上當日均價〔目前未讀取〕', False),
    ('average_up', '當日均價逐段上升〔目前未讀取〕', False),
    ('above_open', '成交價高於開盤價〔目前未讀取〕', False),
    ('near_high', '接近當日最高價〔目前未讀取〕', False),
    ('gain', '當日漲幅介於指定範圍', False),
    ('total_volume', '當日累計成交量達門檻', False),
    ('segment_volume', '每段成交量達門檻', False),
    ('price', '股價達最低門檻', False),
    ('macd', 'MACD DIF向上〔目前未接K線〕', False),
]
DEFAULTS = dict(scan_profile='original', shrink_minutes=5., shrink_segments=3.,
    shrink_tolerance=15., shrink_percent=20., shrink_mean_percent=20., shrink_rise=.3,
    shrink_near_high=1., shrink_gain_min=3., shrink_gain_max=6.,
    shrink_total_volume=1000., shrink_segment_volume=20., shrink_price=50.)
DEFAULTS.update({'use_shrink_'+key: enabled for key, _, enabled in RULES})
BAD = ('讀值跳動', '量倒退', '掃描讀值已過期', '等待重新複查', '引擎略過', '60秒讀值未變', '等待接續複查')


def validate(cfg):
    c = dict(DEFAULTS, **cfg)
    if c['scan_profile'] not in MODES: raise ValueError('請選擇有效的掃描特性')
    for key, default in DEFAULTS.items():
        if isinstance(default, float) and (not math.isfinite(c[key]) or c[key] < 0):
            raise ValueError('縮量上推門檻須為非負有限數字')
    if c['shrink_minutes'] not in (1, 3, 5, 10) or c['shrink_segments'] not in (3, 4, 5, 6):
        raise ValueError('週期限1／3／5／10分鐘；段數限3～6')
    if not 0 <= c['shrink_tolerance'] <= 15 or max(c['shrink_percent'], c['shrink_mean_percent'], c['shrink_near_high']) > 100 or c['shrink_gain_min'] > c['shrink_gain_max']:
        raise ValueError('邊界誤差限0～15秒；比例限0～100%；漲幅上下限順序須正確')
    if c['scan_profile'] != 'original' and not any(c['use_shrink_'+k] for k, _, _ in RULES):
        raise ValueError('至少勾選一個縮量上推條件')


def tick(price):
    for ceiling, step in ((10,.01),(50,.05),(100,.1),(500,.5),(1000,1)):
        if price < ceiling:return step
    return 5.


def window(now):
    d = dt.datetime.fromtimestamp(now, TZ)
    start = d.replace(hour=9, minute=0, second=0, microsecond=0).timestamp()
    return d.date().isoformat(), start, start + 4.5*3600


class ShrinkTracker:
    def __init__(self):
        self.day = None
        self.points = {}; self.states = {}; self.counts = {}; self.last_alert = {}
        self.config_key = None

    def configure(self, cfg, now):
        day, _, _ = window(now)
        key = tuple((k, cfg.get(k, v)) for k, v in DEFAULTS.items())
        if day != self.day:
            self.points = {}; self.day = day; self.states = {}; self.counts = {}; self.last_alert = {}
        if key != self.config_key:
            self.states = {}; self.counts = {}; self.last_alert = {}; self.config_key = key

    def add(self, q, now, reason=''):
        day, start, end = window(now)
        if any(s in reason for s in BAD):return False
        if not all(math.isfinite(v) for v in (q.ts, q.price, q.volume)) or q.price <= 0 or q.volume < 0:return False
        if day != self.day or not start <= q.ts <= min(now, end) or now-q.ts > 30:return False
        series = self.points.setdefault(q.code, deque())
        if series:
            last = series[-1]
            if q.ts <= last.ts or q.volume < last.volume or abs(q.price/last.price-1) > .03:return False
        series.append(q)
        while series and q.ts-series[0].ts > 3700:series.popleft()
        return True

    def evaluate(self, q, cfg, now):
        c = dict(DEFAULTS, **cfg)
        enabled = [(k, label) for k, label, _ in RULES if c['use_shrink_'+k]]
        r = dict(passed=False, count=0, total=len(enabled), status='資料累積中', detail='', prices=[], volumes=[], durations=[], missing=[])
        _, start, end = window(now)
        if not start <= now <= end:
            r.update(status='非盤中時段', detail='僅09:00～13:30產生新訊號');return r
        period = c['shrink_minutes']*60; n = int(c['shrink_segments'])
        boundary = start + math.floor((now-start)/period)*period
        series = list(self.points.get(q.code, ()))
        samples = []
        for i in range(n, -1, -1):
            target = boundary-i*period
            choices = [p for p in series if target-c['shrink_tolerance'] <= p.ts <= target]
            if not choices:
                r['detail'] = f'缺少完整{n}段邊界讀值（每段{period/60:g}分、允許提前{c["shrink_tolerance"]:g}秒）；不可補造漏段'
                return r
            samples.append(choices[-1])
        volumes = [b.volume-a.volume for a,b in zip(samples,samples[1:])]
        prices = [p.price for p in samples[1:]]
        durations = [b.ts-a.ts for a,b in zip(samples,samples[1:])]
        r.update(prices=prices, volumes=volumes, durations=durations)
        if any(v <= 0 for v in volumes):
            r.update(status='資料無效', detail='有零成交或累計量倒退區間，不視為有效縮量');return r
        rise = (prices[-1]/prices[0]-1)*100
        shrink = (1-volumes[-1]/volumes[-2])*100
        average = getattr(q, 'average', None)
        opening = getattr(q, 'open_price', None)
        high = getattr(q, 'day_high', None)
        avgs = [getattr(p, 'average', None) for p in samples[1:]]
        dif = getattr(q, 'macd_dif', None); prior_dif = getattr(q, 'macd_previous_dif', None)
        tests = dict(
            price_up=all(b>a for a,b in zip(prices,prices[1:])),
            volume_down=all(b<a for a,b in zip(volumes,volumes[1:])),
            last_shrink=shrink+1e-9 >= c['shrink_percent'],
            mean_shrink=volumes[-1] <= sum(volumes[:-1])/(n-1)*(1-c['shrink_mean_percent']/100)+1e-9,
            rise=rise+1e-9 >= c['shrink_rise'],
            tick=all(b-a+1e-9 >= tick(a) for a,b in zip(prices,prices[1:])),
            above_average=None if average is None else q.price>average,
            average_up=None if any(v is None for v in avgs) else all(b>a for a,b in zip(avgs,avgs[1:])),
            above_open=None if opening is None else q.price>opening,
            near_high=None if high is None or high<=0 else 0 <= (high-q.price)/high*100 <= c['shrink_near_high'],
            gain=c['shrink_gain_min'] <= q.gain <= c['shrink_gain_max'],
            total_volume=q.volume>=c['shrink_total_volume'],
            segment_volume=min(volumes)>=c['shrink_segment_volume'],
            price=q.price>=c['shrink_price'],
            macd=None if dif is None or prior_dif is None else dif>prior_dif)
        missing = [label+('：缺資料' if tests[k] is None else '：未符合') for k,label in enabled if tests[k] is not True]
        r.update(passed=bool(enabled) and not missing, count=sum(tests[k] is True for k,_ in enabled), missing=missing,
                 status='符合' if enabled and not missing else '待確認' if any(tests[k] is None for k,_ in enabled) else '未符合')
        r['detail'] = (f'段末價 {"→".join(f"{p:g}" for p in prices)}；新增量 {"→".join(f"{v:g}" for v in volumes)}張；'
            f'採樣段長 {"/".join(f"{v:g}" for v in durations)}秒；最新縮量{shrink:.1f}%；首末段末漲幅{rise:.2f}%；'+('、'.join(missing) or '已勾選條件全部符合'))
        return r

    def apply(self, rows, cfg, now):
        self.configure(cfg, now)
        output=[]; alerts=[]; reports={}
        for q,ratio,reason,base_hit in rows:
            valid=self.add(q, now, reason)
            r=self.evaluate(q, cfg, now)
            if not valid:r.update(passed=False,status='待複查',detail='讀值無效、重複或過期；'+r['detail'])
            hit=r['passed'] and (base_hit if cfg['scan_profile']=='combined' else True)
            was=self.states.get(q.code, False)
            if valid:
                self.states[q.code]=hit
                if hit and not was:self.counts[q.code]=self.counts.get(q.code,0)+1
            r['triggers']=self.counts.get(q.code,0); reports[q.code]=r
            text=f'縮量上推[{r["count"]}/{r["total"]}] {r["status"]}｜觸發{r["triggers"]}次｜'+r['detail']
            if cfg['scan_profile']=='combined':text+='｜原有條件：'+reason
            output.append((q,ratio,text,hit))
            if hit and not was and now-self.last_alert.get(q.code,-1e20)>=cfg['cooldown']:
                self.last_alert[q.code]=now; alerts.append((q,text))
        return output,alerts,reports


def profile_assess(row):
    q,ratio,reason,hit=row
    match=re.search(r'縮量上推\[(\d+)/(\d+)\]', reason)
    count,total=map(int,match.groups()) if match else (0,0)
    blocked=not match or any(s in reason for s in ('待複查','等待重新複查','等待接續複查','非盤中時段'))
    passed=hit and not blocked
    label='★ 完全符合' if passed else '待複查' if blocked else '縮量待確認'
    return dict(count=count,total=total,hit=passed,label=label,text=f'{label} {count}/{total}',
        missing=[] if passed else ['縮量條件／資料待確認'],key=(not passed,-count,blocked,q.code))
