"""Forward observation of alerts; sampled returns are not execution/backtest results."""
import json
import sqlite3
from contextlib import closing


class SignalReview:
    def __init__(self,path):self.path=path

    def connect(self):
        conn=sqlite3.connect(self.path)
        conn.execute('CREATE TABLE IF NOT EXISTS signal_checks (id INTEGER PRIMARY KEY,ts REAL,code TEXT,price REAL,kind TEXT,config TEXT,reason TEXT)')
        conn.execute('CREATE TABLE IF NOT EXISTS signal_returns (signal_id INTEGER,horizon INTEGER,ts REAL,price REAL,change REAL,PRIMARY KEY(signal_id,horizon))')
        return conn

    def observe(self,quotes,signals,cfg):
        with closing(self.connect()) as conn,conn:
            for q in quotes:
                pending=conn.execute('SELECT id,ts,price FROM signal_checks WHERE code=? AND ts<? AND ts>=?',(q.code,q.ts,q.ts-360)).fetchall()
                for signal_id,stamp,price in pending:
                    elapsed=q.ts-stamp
                    for horizon in (60,180,300):
                        # Missing quotes are left missing; no synthetic carry-forward.
                        if horizon<=elapsed<=horizon+30:
                            conn.execute('INSERT OR IGNORE INTO signal_returns VALUES (?,?,?,?,?)',(signal_id,horizon,q.ts,q.price,(q.price/price-1)*100))
            for q,kind,reason in signals:
                recent=conn.execute('SELECT ts FROM signal_checks WHERE code=? AND kind=? ORDER BY ts DESC LIMIT 1',(q.code,kind)).fetchone()
                if recent and q.ts-recent[0]<cfg['cooldown']:continue
                conn.execute('INSERT INTO signal_checks(ts,code,price,kind,config,reason) VALUES (?,?,?,?,?,?)',(q.ts,q.code,q.price,kind,json.dumps(cfg,ensure_ascii=False),reason))

    def rows(self):
        with closing(self.connect()) as conn,conn:
            return conn.execute('SELECT s.ts,s.code,s.kind,s.price,r.horizon,r.ts,r.price,r.change,s.reason FROM signal_checks s LEFT JOIN signal_returns r ON r.signal_id=s.id ORDER BY s.ts DESC,r.horizon LIMIT 500').fetchall()
