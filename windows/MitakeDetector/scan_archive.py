"""Append-only capture batches for causal replay; includes non-candidates and rejected counts."""
from contextlib import closing
import dataclasses
import datetime as dt
import hashlib
import json
import sqlite3
import time
import uuid

TAIPEI=dt.timezone(dt.timedelta(hours=8))

class ScanArchive:
    def __init__(self,path):self.path=path
    def connect(self):
        c=sqlite3.connect(self.path,timeout=5)
        c.execute('PRAGMA journal_mode=WAL')
        c.execute('CREATE TABLE IF NOT EXISTS scan_configs(hash TEXT PRIMARY KEY,body TEXT)')
        c.execute('CREATE TABLE IF NOT EXISTS scan_batches(id TEXT PRIMARY KEY,day TEXT,received REAL,version TEXT,rank TEXT,config_hash TEXT,rejected INTEGER,elapsed REAL,count INTEGER)')
        c.execute('CREATE TABLE IF NOT EXISTS scan_quotes(batch TEXT,ordinal INTEGER,ts REAL,code TEXT,body TEXT,ratio REAL,reason TEXT,hit INTEGER,PRIMARY KEY(batch,ordinal))')
        c.execute('CREATE INDEX IF NOT EXISTS scan_day ON scan_batches(day)')
        c.execute('CREATE INDEX IF NOT EXISTS scan_code_time ON scan_quotes(code,ts)')
        return c
    def append(self,quotes,rows,cfg,version,rejected,elapsed,received=None):
        received=time.time() if received is None else received
        day=dt.datetime.fromtimestamp(received,TAIPEI).date().isoformat()
        config=json.dumps(cfg,ensure_ascii=False,sort_keys=True,allow_nan=False)
        digest=hashlib.sha256(config.encode()).hexdigest();batch=uuid.uuid4().hex
        decisions={(q.code,q.ts):(ratio,reason,hit) for q,ratio,reason,hit in rows}
        with closing(self.connect()) as c,c:
            c.execute('INSERT OR IGNORE INTO scan_configs VALUES (?,?)',(digest,config))
            c.execute('INSERT INTO scan_batches VALUES (?,?,?,?,?,?,?,?,?)',(batch,day,received,version,cfg.get('source_rank','手動掃描'),digest,rejected,elapsed,len(quotes)))
            for i,q in enumerate(quotes):
                ratio,reason,hit=decisions.get((q.code,q.ts),(None,'引擎略過：重複或非遞增擷取時間',False))
                c.execute('INSERT INTO scan_quotes VALUES (?,?,?,?,?,?,?,?)',(batch,i,q.ts,q.code,json.dumps(dataclasses.asdict(q),ensure_ascii=False,allow_nan=False),ratio,reason,int(hit)))
        return batch
    def summary(self):
        with closing(self.connect()) as c:
            return c.execute('SELECT b.day,COUNT(DISTINCT b.id),COUNT(q.ordinal),COUNT(DISTINCT q.code),SUM(CASE WHEN q.hit=1 THEN 1 ELSE 0 END) FROM scan_batches b LEFT JOIN scan_quotes q ON q.batch=b.id GROUP BY b.day ORDER BY b.day DESC').fetchall()
    def batches(self,day):
        with closing(self.connect()) as c:
            result=[]
            for bid,received,rank,body,version in c.execute('SELECT b.id,b.received,b.rank,k.body,b.version FROM scan_batches b JOIN scan_configs k ON k.hash=b.config_hash WHERE b.day=? ORDER BY b.received,b.rowid',(day,)):
                quotes=[json.loads(r[0]) for r in c.execute('SELECT body FROM scan_quotes WHERE batch=? ORDER BY ordinal',(bid,))]
                result.append(dict(id=bid,received=received,rank=rank,config=json.loads(body),version=version,quotes=quotes))
            return result
    def backup(self,destination):
        with closing(self.connect()) as c,closing(sqlite3.connect(destination)) as target:c.backup(target)


def replay(batches):
    """Reconstruct engine state using capture timestamps; returns are observations, not fills."""
    from MitakeDetector import Engine,Quote
    engines={};signals=[];seen=set();observed={};versions=set()
    for batch in batches:
        versions.add(batch['version']);engine=engines.setdefault(batch['rank'],Engine())
        quotes=[Quote(**q) for q in batch['quotes']]
        for q in quotes:
            key=(q.code,q.ts)
            if key not in seen:observed.setdefault(q.code,[]).append((q,batch['received']));seen.add(key)
        # Each batch is processed only when it was available, never with later quotes.
        _,alerts=engine.process(quotes,batch['config'],now=batch['received'])
        signals.extend((q,batch['rank'],batch['received']) for q,_ in alerts)
    changes=[];missing=0
    for q,rank,available in signals:
        later=sorted((v for v,received in observed.get(q.code,[]) if received>available and q.ts+300<=v.ts<=q.ts+330),key=lambda v:v.ts)
        if later:changes.append((later[0].price/q.price-1)*100)
        else:missing+=1
    return dict(signals=len(signals),covered=len(changes),missing=missing,
                mean_5m=sum(changes)/len(changes) if changes else None,
                versions=sorted(versions),rules='目前程式引擎重播歷史設定；擷取後300～330秒首筆觀測價，非成交回測；舊版本判定可能不同')
