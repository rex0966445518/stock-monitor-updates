"""JSON observation snapshot; restoring it never emits fresh alerts."""
from collections import deque
from dataclasses import asdict
import datetime as dt


def snapshot(app):
    engines={}
    for rank,engine in app.auto_engines.items():
        engines[rank]={'history':{code:[asdict(q) for q in rows] for code,rows in engine.history.items()},
                       **{key:getattr(engine,key) for key in ('last_alert','highs','pending','last_change','guard_holds')},
                       'session_day':engine.session_day.isoformat() if engine.session_day else None}
    return dict(volume_rates=getattr(getattr(app,'volume_rates',None),'dump',lambda:None)(),near_alert_times=getattr(app,'near_alert_times',{}),display_rows=[[asdict(q),ratio,reason,hit] for q,ratio,reason,hit in getattr(app,'display_rows',[])],display_cfg=getattr(app,'display_cfg',{}),retained={code:[asdict(row[0]),*row[1:]] for code,row in getattr(getattr(app,'retained_candidates',None),'rows',{}).items()},retained_peak=getattr(getattr(app,'retained_candidates',None),'peak',{}),price_history=getattr(getattr(app,'price_history',None),'slots',[]),engines=engines,rows={rank:[[asdict(q),ratio,reason,hit] for q,ratio,reason,hit in rows] for rank,rows in app.rank_rows.items()},records=app.records)


def restore(app,data):
    from MitakeDetector import Engine,Quote
    from price_history import PriceHistory
    app.price_history=PriceHistory(data.get('price_history',[]))
    from retained_candidates import RetainedCandidates
    app.retained_candidates=RetainedCandidates()
    app.retained_candidates.rows={code:(Quote(**row[0]),row[1],'等待重新複查｜'+row[2],False) for code,row in data.get('retained',{}).items()}
    app.retained_candidates.peak=data.get('retained_peak',{})
    app.near_alert_times=data.get('near_alert_times',{})
    engines={}
    for rank,state in data.get('engines',{}).items():
        engine=Engine()
        engine.history={code:deque(Quote(**q) for q in rows) for code,rows in state['history'].items()}
        for key in ('last_alert','highs','pending','last_change','guard_holds'):setattr(engine,key,state.get(key,{}))
        engine.session_day=dt.date.fromisoformat(state['session_day']) if state.get('session_day') else None
        engines[rank]=engine
    from volume_rate import VolumeRates
    shared=VolumeRates.restore(data.get('volume_rates'),[(code,items) for e in engines.values() for code,items in e.history.items()])
    app.volume_rates=shared
    for engine in engines.values():engine.volume_rates=shared
    if hasattr(app,'engine'):app.engine.volume_rates=shared
    rows={rank:[(Quote(**q),ratio,'等待接續複查｜'+reason,False) for q,ratio,reason,hit in items] for rank,items in data.get('rows',{}).items()}
    app.auto_engines=engines;app.rank_rows=rows;app.records=data.get('records',[])
    latest={}
    for items in rows.values():
        for item in items:
            if item[0].code not in latest or item[0].ts>=latest[item[0].code][0].ts:latest[item[0].code]=item
    for q,ratio,reason,hit in data.get('display_rows',[]):
        quote=Quote(**q)
        if quote.code not in latest:latest[quote.code]=(quote,ratio,'等待重新複查｜'+reason,False)
    for code,row in app.retained_candidates.rows.items():
        if code not in latest:latest[code]=row
    for item in app.tree.get_children():app.tree.delete(item)
    for i,label in enumerate(app.price_history.headings()):
        if hasattr(app.tree,'heading'):app.tree.heading(f'history{i}',text=label)
    for q,ratio,reason,hit in latest.values():
        app.tree.insert('','end',values=[q.code,q.name,'待複查',f'{q.price:.2f}',f'{q.gain:+.2f}',f'{q.volume:,.0f}','—' if q.turnover is None else f'{q.turnover:.2f}','待累積' if ratio is None else f'{ratio:.2f}',reason]+app.price_history.values(q.code))
    app.display_rows=list(latest.values())
    app.display_cfg=data.get('display_cfg') or getattr(app,'config',{})
    if hasattr(app,'draw_results'):app.draw_results()
    app.observation_summary.set(f'已還原 {len(latest)} 檔觀察資料｜等待接續複查，不將舊讀值當成新訊號')


def save_daily(path,data,day=None):
    import json
    day=day or dt.date.today().isoformat()
    temp=path.with_suffix('.tmp')
    temp.write_text(json.dumps(dict(day=day,ui=data),ensure_ascii=False),'utf-8')
    temp.replace(path)


def load_daily(path,day=None):
    import json
    day=day or dt.date.today().isoformat()
    if not path.exists():return None
    data=json.loads(path.read_text('utf-8'))
    return data.get('ui') if data.get('day')==day else None
