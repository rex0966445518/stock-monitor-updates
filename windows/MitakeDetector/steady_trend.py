"""Causal 09:00–12:50 sampled-price screen, not an inference of investor identity."""
import datetime as dt
import math
import statistics
from contextlib import closing

TAIPEI=dt.timezone(dt.timedelta(hours=8))
DEFAULTS=dict(steady_enabled=True,steady_min_rise=1.,steady_max_rise=6.,
              steady_max_drawdown=1.5,steady_up_share=55.,steady_r2=.65,
              steady_max_jump=1.,steady_coverage=80.,steady_max_gap=10.,steady_up_volume=55.)
BLOCK='盤尾緩漲未通過'
PASS='盤尾緩漲通過'
BAD=('讀值跳動','量倒退','等待第二次讀值','掃描讀值已過期','等待重新複查','引擎略過','60秒讀值未變','等待接續複查')


def session(now):
    date=dt.datetime.fromtimestamp(now,TAIPEI).date()
    start=dt.datetime.combine(date,dt.time(9),TAIPEI).timestamp()
    end=dt.datetime.combine(date,dt.time(12,50),TAIPEI).timestamp()
    return date.isoformat(),start,end


def valid_quote(q):
    return all(math.isfinite(float(v)) for v in (q.ts,q.price,q.volume)) and q.price>0 and q.volume>=0


def volume_evidence(points,start,max_gap):
    """Sample-end direction attribution, NOT aggressor flow, VWAP or true OHLC bars."""
    bins={}
    for point in points:bins[min(45,int((point[0]-start)//300))]=point
    samples=sorted(bins.values())
    pairs=[];invalid=any(b[2]<a[2] for a,b in zip(points,points[1:]))
    for a,b in zip(samples,samples[1:]):
        seconds=b[0]-a[0];volume=b[2]-a[2]
        if 0<seconds<=max_gap*60 and volume>=0:pairs.append((b[1]-a[1],volume,seconds))
    total=sum(v for _,v,_ in pairs)
    up=[(v,t) for change,v,t in pairs if change>0]
    down=[(v,t) for change,v,t in pairs if change<0]
    up_volume=sum(v for v,_ in up)
    up_rate=up_volume/sum(t for _,t in up) if up else None
    down_rate=sum(v for v,_ in down)/sum(t for _,t in down) if down else None
    ratio=down_rate/up_rate if up_rate and len(down)>=3 else None
    blocks={}
    for ts,price,volume in points:blocks.setdefault(int((ts-start)//1800),[]).append(price)
    lows=[min(prices) for key,prices in sorted(blocks.items()) if len(prices)>=4]
    rising_lows=sum(b>a for a,b in zip(lows,lows[1:]))/max(1,len(lows)-1)*100 if len(lows)>=3 else None
    return dict(up_volume_share=up_volume/total*100 if total>0 and not invalid else None,
                pullback_volume_ratio=ratio if not invalid else None,rising_lows=rising_lows,
                volume_intervals=len(pairs),volume_invalid=invalid,volume_total=total)


def evidence_text(e):
    share=e['up_volume_share'];ratio=e['pullback_volume_ratio'];lows=e['rising_lows']
    return ('上漲採樣區間量占比 '+(f'{share:.1f}%' if share is not None else '資料不足')+
            '；回落/上漲量速 '+(f'{ratio:.2f}倍' if ratio is not None else '回落樣本不足')+
            '；30分採樣低點墊高比例 '+(f'{lows:.0f}%' if lows is not None else '待累積')+
            '（採樣推估，非主動買量或大戶身分）')


class SteadyTracker:
    def __init__(self):self.day=None;self.points={};self.first={}
    def reset_day(self,now):
        day,_,_=session(now)
        if day!=self.day:self.day=day;self.points={};self.first={}
    def add(self,q,reason='',now=None):
        now=q.ts if now is None else now
        if not valid_quote(q) or any(word in reason for word in BAD):return
        self.reset_day(now)
        day,start,end=session(now)
        if not start<=q.ts<=min(now,end) or session(q.ts)[0]!=day:return
        point=(q.ts,q.price,q.volume)
        code=q.code
        if code not in self.first or q.ts<self.first[code][0]:self.first[code]=point
        minute=int((q.ts-start)//60);series=self.points.setdefault(code,{})
        if minute not in series or q.ts>series[minute][0]:series[minute]=point
    def merge(self,other):
        if self.day and self.day!=other.day:return
        if self.day is None:self.day=other.day
        for code,series in other.points.items():
            target=self.points.setdefault(code,{})
            for minute,p in series.items():
                if minute not in target or p[0]>target[minute][0]:target[minute]=p
            if code not in self.first or other.first[code][0]<self.first[code][0]:self.first[code]=other.first[code]
    def evaluate(self,code,cfg,now,current_price=None):
        day,start,end=session(now)
        opts=dict(DEFAULTS,**{k:v for k,v in cfg.items() if k in DEFAULTS})
        result=dict(passed=False,status='等待12:50',detail='',coverage=0.,rise=None,drawdown=None,up_share=None,r2=None,jump=None,first=None,last=None)
        points=sorted(p for p in self.points.get(code,{}).values() if start<=p[0]<=min(end,now)) if self.day==day else []
        first=self.first.get(code) if self.day==day else None
        if first and first[0]<=now and (not points or points[0]!=first):points.insert(0,first)
        if not points:
            result.update(status='資料不足',detail='未取得09:00～12:50有效讀值');return result
        bins={}
        for point in points:bins[min(45,int((point[0]-start)//300))]=point
        result.update(coverage=len(bins)/46*100,first=points[0][0],last=points[-1][0])
        evidence=volume_evidence(points,start,opts['steady_max_gap']);result.update(evidence)
        elapsed_bins=max(1,min(46,int((min(now,end)-start)//300)+1))
        result['elapsed_coverage']=min(100.,len(bins)/elapsed_bins*100)
        result['samples']=len(points)
        info=evidence_text(evidence)
        if now<end:
            result['detail']=f'累積{len(points)}筆；已經過時段覆蓋 {result["elapsed_coverage"]:.0f}%；全段 {result["coverage"]:.0f}%；12:50才判定｜'+info;return result
        gaps=[points[0][0]-start,end-points[-1][0]]+[b[0]-a[0] for a,b in zip(points,points[1:])]
        missing=[]
        if points[0][0]>start+300:missing.append('缺少09:00～09:05首筆')
        if points[-1][0]<end-300:missing.append('缺少12:45～12:50末筆')
        if result['coverage']<opts['steady_coverage']:missing.append(f'5分區間覆蓋{result["coverage"]:.0f}%不足')
        if max(gaps)>opts['steady_max_gap']*60:missing.append(f'最大缺口{max(gaps)/60:.1f}分鐘')
        if missing:
            result.update(status='資料不足',detail='；'.join(missing));return result
        values=[p[1] for p in points];peak=values[0];drawdown=0.
        for value in values:peak=max(peak,value);drawdown=max(drawdown,(peak-value)/peak*100)
        samples=[v for _,v in sorted(bins.items())]
        changes=[(b[1]/a[1]-1)*100 for a,b in zip(samples,samples[1:])]
        rise=(values[-1]/values[0]-1)*100
        up=sum(v>0 for v in changes)/max(1,len(changes))*100
        jump=max(changes,default=0)
        xs=[(p[0]-start)/60 for p in samples];ys=[p[1] for p in samples]
        mx=statistics.mean(xs);my=statistics.mean(ys)
        xx=sum((v-mx)**2 for v in xs);yy=sum((v-my)**2 for v in ys)
        xy=sum((x-mx)*(y-my) for x,y in zip(xs,ys));slope=xy/xx if xx else 0
        r2=xy*xy/(xx*yy) if xx and yy else 0
        earlier=[p for p in points if p[0]<=end-1800]
        tail=(values[-1]/earlier[-1][1]-1)*100 if earlier else rise
        failed=[]
        if evidence['up_volume_share'] is None:failed.append('成交量資料不足或累計量倒退')
        elif evidence['up_volume_share']<opts['steady_up_volume']:failed.append('上漲採樣區間量占比不足')
        if evidence['pullback_volume_ratio'] is not None and evidence['pullback_volume_ratio']>1:failed.append('回落量速大於上漲量速')
        if not opts['steady_min_rise']<=rise<=opts['steady_max_rise']:failed.append('首末採樣漲幅不符')
        if up<opts['steady_up_share']:failed.append('上漲區間比例不足')
        if slope<=0 or r2<opts['steady_r2']:failed.append('趨勢不夠平順向上')
        if drawdown>opts['steady_max_drawdown']:failed.append('採樣回撤過大')
        if jump>opts['steady_max_jump'] or rise>0 and jump>rise*.35:failed.append('漲幅集中急拉')
        if rise>0 and tail>rise*.40:failed.append('漲幅過度集中最後30分鐘')
        if current_price is not None and current_price<values[-1]*(1-opts['steady_max_drawdown']/100):failed.append('12:50後已轉弱')
        result.update(passed=not failed,status='緩漲觀察候選' if not failed else '未符合',rise=rise,drawdown=drawdown,up_share=up,r2=r2,jump=jump,
                      detail=('；'.join(failed) if failed else '全段價量規則通過；仍須籌碼佐證')+'｜'+info)
        return result


def gate_rows(rows,cfg,tracker,now,ready=True):
    """Preserve base 6 checks; this is an additional mandatory observation gate."""
    output=[];reports={}
    for q,ratio,reason,hit in rows:
        tracker.add(q,reason,now)
        report=tracker.evaluate(q.code,cfg,now,q.price) if ready else dict(passed=False,status='歷史載入中',detail='正在還原當日資料',coverage=0.)
        reports[q.code]=report
        if cfg.get('steady_enabled',False):
            prefix=PASS if report['passed'] else BLOCK
            reason=f'{prefix}：{report["status"]}｜{report["detail"]}｜'+reason
            hit=hit and report['passed']
        output.append((q,ratio,reason,hit))
    return output,reports


def load_archive(path,now):
    """Only actual captures available by now; never fill absent opening data."""
    import sqlite3,json
    from types import SimpleNamespace
    tracker=SteadyTracker();tracker.reset_day(now)
    if not path.exists():return tracker
    _,start,end=session(now)
    with closing(sqlite3.connect(path,timeout=5)) as c:
        if not c.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name='scan_quotes'").fetchone():return tracker
        for body,reason in c.execute('SELECT q.body,q.reason FROM scan_quotes q JOIN scan_batches b ON b.id=q.batch WHERE q.ts>=? AND q.ts<=? AND b.received<=? ORDER BY q.ts',(start,min(now,end),now)):
            try:tracker.add(SimpleNamespace(**json.loads(body)),reason or '',now)
            except (ValueError,TypeError,AttributeError):continue
    return tracker


def process(engine,quotes,cfg,tracker,now,ready=True):
    """Gate engine alerts without consuming their cooldown on rejected observations."""
    tracker.reset_day(now)
    previous=dict(engine.last_alert)
    rows,alerts=engine.process(quotes,cfg,now=now)
    rows,reports=gate_rows(rows,cfg,tracker,now,ready)
    if cfg.get('steady_enabled',False):
        allowed={q.code:reason for q,ratio,reason,hit in rows if hit}
        for q,reason in alerts:
            if q.code not in allowed:
                if q.code in previous and session(previous[q.code])[0]==session(now)[0]:engine.last_alert[q.code]=previous[q.code]
                else:engine.last_alert.pop(q.code,None)
        alerts=[(q,allowed[q.code]) for q,_ in alerts if q.code in allowed]
    return rows,alerts,reports


def validate_config(cfg):
    o=dict(DEFAULTS,**{k:v for k,v in cfg.items() if k in DEFAULTS})
    numeric=[v for k,v in o.items() if k!='steady_enabled']
    if not all(math.isfinite(v) and v>=0 for v in numeric):raise ValueError('緩漲門檻須為非負有限數字')
    if not (0<o['steady_min_rise']<=o['steady_max_rise'] and 0<o['steady_max_drawdown']<100 and 0<=o['steady_up_share']<=100 and 0<=o['steady_r2']<=1 and 50<=o['steady_coverage']<=100 and 0<o['steady_max_gap']<=30 and o['steady_max_jump']>0 and 0<=o['steady_up_volume']<=100):
        raise ValueError('緩漲設定：漲幅上下限須大於0且順序正確；回撤0～100%之間；比例0～100%、R² 0～1、覆蓋率50～100%、缺口0～30分鐘之間')
