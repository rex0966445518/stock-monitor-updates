"""Narrow daily-volume experiment. No intraday/chip-strategy profitability claims."""
from contextlib import closing
import datetime as dt
import hashlib
import json
import sqlite3

BASELINE=1.5
COST=0.8  # Explicit round-trip friction reserve in percentage points; not a fee quote.

def experiments(histories,multiple):
    trades=[];excluded=0
    for code,prices in histories.items():
        next_free=0
        for i in range(20,len(prices)-5):
            if i<next_free:continue
            before=prices[i-20:i];signal=prices[i];entry=prices[i+1];exit=prices[i+5]
            window=prices[i-20:i+6]
            if any(r.get('volume',0)<=0 or not 0<r['low']<=min(r['open'],r['close'])<=max(r['open'],r['close'])<=r['high'] for r in window):excluded+=1;continue
            if any(abs(b['close']/a['close']-1)>.15 for a,b in zip(window,window[1:])):excluded+=1;continue
            if any((dt.date.fromisoformat(b['day'])-dt.date.fromisoformat(a['day'])).days>10 for a,b in zip(window,window[1:])):excluded+=1;continue
            if signal['close']<=max(r['high'] for r in before) or signal['volume']<sum(r['volume'] for r in before)/20*multiple:continue
            # Limit-locked execution and corporate-action gaps cannot be assumed filled.
            if entry['high']==entry['low'] or exit['high']==exit['low'] or abs(entry['open']/signal['close']-1)>.09:excluded+=1;continue
            trades.append(dict(code=code,day=signal['day'],end=exit['day'],net=(exit['close']/entry['open']-1)*100-COST))
            next_free=i+6
    return trades,excluded

def metrics(trades,start,end):
    rows=[r for r in trades if start<=r['day'] and r['end']<=end]
    returns=sorted(r['net'] for r in rows)
    return dict(n=len(rows),codes=len({r['code'] for r in rows}),days=len({r['day'] for r in rows}),
                mean=sum(returns)/len(returns) if returns else None,
                p10=returns[int((len(returns)-1)*.1)] if returns else None,
                positive=sum(v>0 for v in returns)/len(returns) if returns else None,start=start,end=end)

def adequate(m):return m['n']>=30 and m['codes']>=10 and m['days']>=15

def better(candidate,baseline):
    return adequate(candidate) and adequate(baseline) and candidate['mean']>0 and candidate['mean']>=baseline['mean']+.5 and candidate['p10']>=baseline['p10']

class Validator:
    def __init__(self,path):self.path=path
    def connect(self):
        c=sqlite3.connect(self.path,timeout=5)
        c.execute('CREATE TABLE IF NOT EXISTS validation_state(key TEXT PRIMARY KEY,body TEXT)')
        c.execute('CREATE TABLE IF NOT EXISTS validation_audit(id INTEGER PRIMARY KEY,at TEXT,body TEXT)')
        return c
    def state(self):
        with closing(self.connect()) as c:
            row=c.execute("SELECT body FROM validation_state WHERE key='daily'").fetchone()
            return json.loads(row[0]) if row else dict(active=BASELINE,last_test_end='',status='待累積：至少250個交易日及足夠跨股訊號')
    def save(self,state,report):
        with closing(self.connect()) as c,c:
            c.execute('INSERT OR REPLACE INTO validation_state VALUES (?,?)',('daily',json.dumps(state,ensure_ascii=False)))
            c.execute('INSERT INTO validation_audit(at,body) VALUES (?,?)',(dt.datetime.now(dt.timezone.utc).isoformat(),json.dumps(report,ensure_ascii=False)))
    def audit(self):
        with closing(self.connect()) as c:return c.execute('SELECT at,body FROM validation_audit ORDER BY id DESC LIMIT 50').fetchall()
    def run(self,histories):
        today=dt.datetime.now(dt.timezone(dt.timedelta(hours=8))).date().isoformat()
        histories={c:[r for r in prices if r['day']<today] for c,prices in histories.items()}
        state=self.state();dates=sorted({r['day'] for prices in histories.values() for r in prices})
        if len(dates)<250:
            status=f'資料不足：日線{len(dates)}/250個交易日；不變更參數'
            if state.get('status')!=status:
                state['status']=status;self.save(state,dict(status=status))
            return state
        cutoff=dates[-1];base,excluded=experiments(histories,BASELINE)
        if state['active']!=BASELINE:
            active,_=experiments(histories,state['active'])
            forward=metrics(active,state['activated_after'],cutoff);ref=metrics(base,state['activated_after'],cutoff)
            if adequate(forward) and adequate(ref) and (forward['mean']<0 or forward['mean']<ref['mean']-.5):
                old=state['active'];state.update(active=BASELINE,status='新資料表現退化，自動回復1.5倍基準')
                self.save(state,dict(action='rollback',previous=old,active=BASELINE,forward=forward,baseline=ref));return state
        # The final test period is never reused to select another model.
        test_start=dates[-60]
        if test_start<=state.get('last_test_end',''):return state
        train_end=dates[-131];valid_start=dates[-125];valid_end=dates[-66];train_start=dates[0]
        candidates=[]
        for value in (1.5,2.0,2.5):
            trades,_=experiments(histories,value);m=metrics(trades,train_start,train_end)
            if adequate(m):candidates.append((m['mean'],value,trades,m))
        report=dict(scope='日線20日高點突破量倍數；非吸籌或盤中完整策略回測',cost_reserve_pct=COST,
                    current_universe='僅追蹤過的股票；有選樣及存活偏差',corporate_actions='未還原；排除大跳空但無完整企業行動校正',
                    excluded_windows=excluded,train=[train_start,train_end],validation=[valid_start,valid_end],test=[test_start,cutoff],
                    digest=hashlib.sha256(json.dumps(histories,sort_keys=True).encode()).hexdigest())
        if not candidates:state['status']='訓練樣本不足（每期30訊號、10股、15日期）；維持參數'
        else:
            state['last_test_end']=cutoff
            _,value,trades,training=max(candidates,key=lambda v:(v[0],-v[1]))
            valid=metrics(trades,valid_start,valid_end);test=metrics(trades,test_start,cutoff)
            bv=metrics(base,valid_start,valid_end);bt=metrics(base,test_start,cutoff)
            report.update(candidate=value,training=training,validation_metrics=valid,test_metrics=test,baseline_validation=bv,baseline_test=bt)
            if value>BASELINE and better(valid,bv) and better(test,bt):
                state.update(active=value,activated_after=(dt.date.fromisoformat(cutoff)+dt.timedelta(days=1)).isoformat(),status=f'已套用日線研究量倍數{value}；持續以新資料監測')
                report['action']='promote'
            else:state['status']='獨立驗證未達門檻，維持現行參數；等待全新60交易日測試區間'
        report['state']=dict(state);self.save(state,report)
        return state
