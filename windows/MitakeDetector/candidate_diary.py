"""Dated 5/6 candidate collection with immutable observations and original settings."""
from contextlib import closing
import datetime as dt
import hashlib
import json
import math
import time
import sqlite3
from candidate_ranking import assess

TAIPEI=dt.timezone(dt.timedelta(hours=8))

class CandidateDiary:
    def __init__(self,path):self.path=path
    def connect(self):
        c=sqlite3.connect(self.path,timeout=5)
        c.execute('CREATE TABLE IF NOT EXISTS candidate_diary(day TEXT,code TEXT,name TEXT,first_ts REAL,first_price REAL,PRIMARY KEY(day,code))')
        c.execute('CREATE INDEX IF NOT EXISTS diary_code ON candidate_diary(code)')
        c.execute('CREATE TABLE IF NOT EXISTS candidate_diary_configs(hash TEXT PRIMARY KEY,body TEXT)')
        c.execute('CREATE TABLE IF NOT EXISTS candidate_diary_events(id INTEGER PRIMARY KEY,day TEXT,code TEXT,ts REAL,price REAL,gain REAL,volume REAL,turnover REAL,ratio REAL,matched INTEGER,total INTEGER,hit INTEGER,blocked INTEGER,reason TEXT,source TEXT,version TEXT,config_hash TEXT,UNIQUE(day,code,ts,source,config_hash))')
        c.execute('CREATE INDEX IF NOT EXISTS diary_event_time ON candidate_diary_events(day,code,ts DESC)')
        c.execute('CREATE TABLE IF NOT EXISTS candidate_diary_meta(key TEXT PRIMARY KEY,value TEXT)')
        c.execute("CREATE TABLE IF NOT EXISTS candidate_latest_quotes(code TEXT PRIMARY KEY,ts REAL,price REAL,source TEXT)")
        return c
    def observe(self,rows,cfg,version):
        encoded=json.dumps(cfg,sort_keys=True,ensure_ascii=False,allow_nan=False)
        digest=hashlib.sha256(encoded.encode()).hexdigest();changed=0
        with closing(self.connect()) as c,c:
            for row in rows:
                q,ratio,reason,hit=row;match=assess(row,cfg)
                day=dt.datetime.fromtimestamp(q.ts,TAIPEI).date().isoformat()
                blocked=match['label']=='待複查' or reason.startswith('引擎略過')
                qualifies=match['total']==6 and match['count']>=5 and not blocked
                if qualifies:
                    c.execute('INSERT OR IGNORE INTO candidate_diary VALUES (?,?,?,?,?)',(day,q.code,q.name,q.ts,q.price))
                    c.execute('UPDATE candidate_diary SET first_price=CASE WHEN ?<first_ts THEN ? ELSE first_price END,first_ts=MIN(first_ts,?) WHERE day=? AND code=?',(q.ts,q.price,q.ts,day,q.code))
                if not blocked and c.execute('SELECT 1 FROM candidate_diary WHERE code=? LIMIT 1',(q.code,)).fetchone():
                    self._save_quote(c,dict(code=q.code,ts=q.ts,price=q.price,source='三竹有效掃描'))
                if not c.execute('SELECT 1 FROM candidate_diary WHERE day=? AND code=?',(day,q.code)).fetchone():continue
                if q.name:c.execute('UPDATE candidate_diary SET name=? WHERE day=? AND code=?',(q.name,day,q.code))
                c.execute('INSERT OR IGNORE INTO candidate_diary_configs VALUES (?,?)',(digest,encoded))
                cur=c.execute('INSERT OR IGNORE INTO candidate_diary_events(day,code,ts,price,gain,volume,turnover,ratio,matched,total,hit,blocked,reason,source,version,config_hash) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)',
                          (day,q.code,q.ts,q.price,q.gain,q.volume,q.turnover,ratio,match['count'],match['total'],int(bool(hit)),int(blocked),reason,cfg.get('source_rank') or '手動掃描',version,digest))
                changed+=cur.rowcount
        return changed
    @staticmethod
    def _save_quote(c,q):
        if not all(math.isfinite(float(q[k])) for k in ('ts','price')) or q['price']<=0 or q['ts']>time.time()+60:return
        c.execute("INSERT INTO candidate_latest_quotes VALUES (?,?,?,?) ON CONFLICT(code) DO UPDATE SET ts=excluded.ts,price=excluded.price,source=excluded.source WHERE excluded.ts>candidate_latest_quotes.ts",(q['code'],q['ts'],q['price'],q['source']))
    def update_quotes(self,quotes):
        with closing(self.connect()) as c,c:
            for q in quotes:
                if c.execute('SELECT 1 FROM candidate_diary WHERE code=? LIMIT 1',(q['code'],)).fetchone():self._save_quote(c,q)
    def latest_quotes(self,day):
        with closing(self.connect()) as c:
            rows=c.execute("SELECT q.code,q.ts,q.price,q.source FROM candidate_latest_quotes q JOIN candidate_diary d ON d.code=q.code WHERE d.day=?",(day,)).fetchall()
            return {code:dict(ts=ts,price=price,source=source) for code,ts,price,source in rows}
    def days(self,month):
        with closing(self.connect()) as c:return dict(c.execute('SELECT day,COUNT(*) FROM candidate_diary WHERE day LIKE ? GROUP BY day',(month+'-%',)))
    def stocks(self,day):
        with closing(self.connect()) as c:
            return c.execute('''SELECT d.code,d.name,d.first_ts,d.first_price,e.ts,e.price,e.matched,e.total,e.reason,
                       (SELECT MAX(matched) FROM candidate_diary_events WHERE day=d.day AND code=d.code AND blocked=0 AND total=6),
                       (SELECT COUNT(*) FROM candidate_diary_events WHERE day=d.day AND code=d.code)
                       FROM candidate_diary d LEFT JOIN candidate_diary_events e ON e.id=(SELECT id FROM candidate_diary_events WHERE day=d.day AND code=d.code AND blocked=0 ORDER BY ts DESC,id DESC LIMIT 1)
                       WHERE d.day=? ORDER BY 10 DESC,d.first_ts,d.code''',(day,)).fetchall()
    def qualification_counts(self,day):
        """Count valid below-five -> five-or-six transitions in timestamp order.

        Reconstruct from persisted events so restart and archive imports retain
        counts. Unknown/blocked observations neither qualify nor reset a run.
        """
        with closing(self.connect()) as c:
            return dict(c.execute("""
                WITH dedup AS (
                    SELECT code,ts,matched,
                           ROW_NUMBER() OVER(PARTITION BY code,ts ORDER BY id DESC) AS rn
                    FROM candidate_diary_events WHERE day=? AND blocked=0 AND total=6
                ), transitions AS (
                    SELECT code,matched,
                           LAG(matched,1,0) OVER(PARTITION BY code ORDER BY ts) AS previous
                    FROM dedup WHERE rn=1
                )
                SELECT code,SUM(CASE WHEN matched>=5 AND previous<5 THEN 1 ELSE 0 END)
                FROM transitions GROUP BY code
            """,(day,)))

    def latest_flags(self,day):
        with closing(self.connect()) as c:
            rows=c.execute('SELECT d.code,e.ts,e.blocked,e.reason,e.matched,e.total FROM candidate_diary d JOIN candidate_diary_events e ON e.id=(SELECT id FROM candidate_diary_events WHERE day=d.day AND code=d.code ORDER BY ts DESC,id DESC LIMIT 1) WHERE d.day=?',(day,)).fetchall()
            return {row[0]:row[1:] for row in rows}
    def events(self,day,code,before=None):
        with closing(self.connect()) as c:
            cutoff=1e20
            if before is not None:
                row=c.execute('SELECT ts FROM candidate_diary_events WHERE id=? AND day=? AND code=?',(before,day,code)).fetchone()
                if not row:return []
                cutoff=row[0]
            return c.execute('''SELECT e.id,e.ts,e.price,e.gain,e.volume,e.turnover,e.ratio,e.matched,e.total,e.hit,e.blocked,e.reason,e.source,e.version,k.body
                       FROM candidate_diary_events e JOIN candidate_diary_configs k ON k.hash=e.config_hash WHERE day=? AND code=? AND (e.ts<? OR (e.ts=? AND e.id<?)) ORDER BY e.ts DESC,e.id DESC LIMIT 200''',(day,code,cutoff,cutoff,before or 9223372036854775807)).fetchall()
    def import_archive(self,progress=lambda n:None):
        from MitakeDetector import Quote
        from itertools import groupby
        with closing(self.connect()) as c:
            if not c.execute("SELECT 1 FROM sqlite_master WHERE name='scan_quotes'").fetchone():return 0
            row=c.execute("SELECT value FROM candidate_diary_meta WHERE key='archive_cursor'").fetchone();cursor=int(row[0]) if row else 0
            ceiling=c.execute('SELECT COALESCE(MAX(rowid),0) FROM scan_quotes').fetchone()[0]
        total=0
        while True:
            with closing(self.connect()) as c:
                batch=c.execute('''SELECT q.rowid,q.body,q.ratio,q.reason,q.hit,b.version,k.body,b.id FROM scan_quotes q JOIN scan_batches b ON b.id=q.batch JOIN scan_configs k ON k.hash=b.config_hash WHERE q.rowid>? AND q.rowid<=? ORDER BY q.rowid LIMIT 500''',(cursor,ceiling)).fetchall()
            if not batch:break
            for _,items in groupby(batch,key=lambda r:r[7]):
                items=list(items);cfg=json.loads(items[0][6]);rows=[]
                for _,body,ratio,reason,hit,version,_,bid in items:
                    rows.append((Quote(**json.loads(body)),ratio,reason,bool(hit)))
                self.observe(rows,cfg,items[0][5])
            cursor=batch[-1][0];total+=len(batch)
            with closing(self.connect()) as c,c:c.execute('INSERT OR REPLACE INTO candidate_diary_meta VALUES (?,?)',('archive_cursor',str(cursor)))
            progress(total)
        return total
