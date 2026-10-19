"""Persistent dated evidence; hypothesis labels never imply investor identity."""
from contextlib import closing
import datetime as dt
import json
import sqlite3


class ResearchStore:
    def __init__(self,path):self.path=path
    def connect(self):
        c=sqlite3.connect(self.path,timeout=5)
        c.execute('CREATE TABLE IF NOT EXISTS research_facts(kind TEXT,code TEXT,day TEXT,body TEXT,source TEXT,PRIMARY KEY(kind,code,day))')
        c.execute('CREATE TABLE IF NOT EXISTS research_sources(source TEXT PRIMARY KEY,updated REAL,status TEXT)')
        return c
    def put(self,rows,source):
        with closing(self.connect()) as c,c:
            for kind,code,day,body in rows:
                if day[:10]>dt.date.today().isoformat():continue
                # Monthly backfills must not erase the known security name.
                previous=c.execute('SELECT body FROM research_facts WHERE kind=? AND code=? AND day=?',(kind,code,day)).fetchone()
                if previous:body=dict(json.loads(previous[0]),**body)
                c.execute('INSERT OR REPLACE INTO research_facts VALUES (?,?,?,?,?)',(kind,code,day,json.dumps(body,ensure_ascii=False),source))
    def source_status(self,source,updated,status):
        with closing(self.connect()) as c,c:c.execute('INSERT OR REPLACE INTO research_sources VALUES (?,?,?)',(source,updated,status))
    def statuses(self):
        with closing(self.connect()) as c:return c.execute('SELECT source,updated,status FROM research_sources ORDER BY source').fetchall()
    def facts(self,kind,code):
        with closing(self.connect()) as c:
            return [dict(json.loads(body),day=day,source=source) for day,body,source in c.execute('SELECT day,body,source FROM research_facts WHERE kind=? AND code=? ORDER BY day',(kind,code))]
    def known_codes(self):
        with closing(self.connect()) as c:return [r[0] for r in c.execute("SELECT DISTINCT code FROM research_facts WHERE kind='price'")]
    def report(self,code,today=None):
        return evaluate(code,self.facts('weekly',code),self.facts('insti',code),self.facts('price',code),self.facts('revenue',code),self.facts('news',code),self.facts('index','TWSE'),self.facts('index','TPEx'),today)


def evaluate(code,weeks,inst,prices,revenues,news,tw_index=(),otc_index=(),today=None):
    today=today or dt.date.today()
    weeks=weeks[-3:];inst=inst[-5:];prices=prices[-60:]
    missing=[];warnings=[];evidence=[]
    if len(weeks)<3:missing.append(f'集保週資料{len(weeks)}/3期')
    if len(inst)<5:missing.append(f'法人日資料{len(inst)}/5日')
    if len(prices)<21:missing.append(f'日線資料{len(prices)}/21日')
    def old(rows,days):return bool(rows) and (today-dt.date.fromisoformat(rows[-1]['day'][:10])).days>days
    if old(weeks,14):missing.append('集保來源過期')
    if old(prices,7):missing.append('收盤來源過期')
    if prices and inst and inst[-1]['day']!=prices[-1]['day']:missing.append('法人與收盤日期未對齊')
    if len(prices)>=5 and len(inst)==5 and [r['day'] for r in inst]!=[r['day'] for r in prices[-5:]]:missing.append('法人5日未與日線連續對齊')
    large=None;concentrated=False
    if len(weeks)==3:
        gaps=[(dt.date.fromisoformat(b['day'])-dt.date.fromisoformat(a['day'])).days for a,b in zip(weeks,weeks[1:])]
        if any(g<4 or g>12 for g in gaps):missing.append('集保週期間隔不連續')
        large=weeks[-1]['large']-weeks[0]['large']
        capital_changed=any(abs(b['total']/a['total']-1)>.005 for a,b in zip(weeks,weeks[1:]))
        if capital_changed:missing.append('集保總股數變動，須核對配股增資或股本事件')
        concentrated=all(b['large']>a['large'] for a,b in zip(weeks,weeks[1:])) and weeks[-1]['small']<weeks[0]['small']
        evidence.append(f'超過400張級距占比3期變化 {large:+.2f}百分點；非特定主力身分')
    foreign=sum(r['foreign'] for r in inst) if inst else None
    trust=sum(r['trust'] for r in inst) if inst else None
    buying=len(inst)==5 and ((foreign>0 and sum(r['foreign']>0 for r in inst)>=3) or (trust>0 and sum(r['trust']>0 for r in inst)>=3))
    if inst:evidence.append(f'近{len(inst)}日外資 {foreign/1000:+,.0f}張、投信 {trust/1000:+,.0f}張；自營避險不當看多票')
    weak=False;breakout=False;confirmed=False;support=None;vwap=None;relative=None
    if len(prices)>=21:
        history=prices[-21:-1];last=prices[-1]
        support=min(r['low'] for r in history[-10:])
        ceiling=max(r['high'] for r in history)
        average=sum(r['volume'] for r in history)/20
        weak=last['close']<support
        breakout=last['close']>ceiling and average>0 and last['volume']>=average*1.5
        abnormal=any(abs(b['close']/a['close']-1)>.15 for a,b in zip(prices[-21:],prices[-20:]))
        if abnormal:missing.append('未還原日線有大幅跳空，須核對除權息等事件')
        # Chronological breakout -> smaller-volume retest -> renewed advance.
        for i in range(max(20,len(prices)-5),len(prices)-2):
            before=prices[i-20:i];b=prices[i];level=max(r['high'] for r in before)
            if b['close']<=level or b['volume']<sum(r['volume'] for r in before)/20*1.5:continue
            for j in range(i+1,len(prices)-1):
                retest=prices[j]
                if any(r['close']<level for r in prices[i+1:]):continue
                if level*.99<=retest['low']<=level*1.01 and retest['close']>=level and retest['volume']<b['volume']:
                    confirmed=last['close']>retest['high'] and last['volume']>retest['volume']
        if last['volume']>0:vwap=last['amount']/last['volume']
        benchmark=otc_index if last.get('market')=='TPEx' else tw_index
        by_day={r['day']:r['close'] for r in benchmark}
        if len(prices)>=6 and prices[-6]['day'] in by_day and last['day'] in by_day:
            relative=((last['close']/prices[-6]['close']-1)-(by_day[last['day']]/by_day[prices[-6]['day']]-1))*100
        else:warnings.append('大盤比較資料不足')
        evidence.append(f'最近10個已完成交易日低點參考 {support:.2f}；非保證成交停損價')
        evidence.append('日線未還原，企業行動須人工核對；不是盤中高點或即時VWAP')
    revenue=revenues[-1] if revenues else None
    if not revenue:warnings.append('月營收未取得')
    elif old(revenues,70):warnings.append('月營收資料過期')
    else:evidence.append(f"營收 {revenue['day'][:7]} 年增 {revenue['yoy']:+.2f}%，不是完整財報健診")
    if revenue and revenue['yoy']<=0:warnings.append('最新月營收年增非正，基本面需再確認')
    warnings.append('產業相對強弱及EPS／現金流尚未納入；重大訊息需人工核對')
    if weak:stage='失效／跌破日線支撐'
    elif missing:stage='資料不足／持續蒐集'
    elif large is not None and large<0:stage='籌碼轉弱'
    elif concentrated and buying and (not revenue or revenue['yoy']<=0 or old(revenues,70)):stage='籌碼觀察／營收待確認'
    elif concentrated and buying and confirmed:stage='日線轉強確認（非買入指令）'
    elif concentrated and buying and breakout:stage='放量突破／等待回測'
    elif concentrated and buying:stage='疑似吸籌觀察'
    else:stage='一般觀察／證據未齊'
    last=prices[-1] if prices else {}
    return dict(code=code,name=last.get('name') or (revenue.get('name','') if revenue else ''),stage=stage,asof=last.get('day','—'),weekly_asof=weeks[-1]['day'] if weeks else '—',insti_asof=inst[-1]['day'] if inst else '—',large_change=large,foreign=foreign,trust=trust,support=support,day_vwap=vwap,relative=relative,missing=missing,warnings=warnings,evidence=evidence,news=news[-10:],revenue=revenue,prices=prices,weeks=weeks,insti=inst)
