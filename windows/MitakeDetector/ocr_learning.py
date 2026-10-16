"""Bounded local OCR records and exact-pixel memoization across restarts."""
from contextlib import closing
import datetime as dt
import json
import math
from pathlib import Path
import sqlite3

PROFILE='rapidocr1.4.4-pad24-scale2-unclip2'


def clean_tokens(tokens):
    result=[]
    for box,text,confidence in tokens:
        confidence=float(confidence)
        points=[[float(x),float(y)] for x,y in box]
        if len(points)!=4 or not math.isfinite(confidence) or not 0<=confidence<=1 or any(not math.isfinite(n) for point in points for n in point):raise ValueError('OCR格式無效')
        result.append((points,str(text),confidence))
    return result


class OCRLearning:
    def __init__(self,path):
        self.path=Path(path);self.last_error=''
        self.path.parent.mkdir(parents=True,exist_ok=True)
        with closing(sqlite3.connect(self.path)) as db:
            db.execute('CREATE TABLE IF NOT EXISTS cache (key TEXT PRIMARY KEY,tokens TEXT NOT NULL,touched REAL NOT NULL)')
            db.execute('CREATE TABLE IF NOT EXISTS reads (id INTEGER PRIMARY KEY,ts TEXT,key TEXT,source TEXT,elapsed REAL,count INTEGER,tokens TEXT)')
            db.commit()

    def lookup(self,key):
        try:
            with closing(sqlite3.connect(self.path)) as db:
                row=db.execute('SELECT tokens FROM cache WHERE key=?',(PROFILE+key,)).fetchone()
            return clean_tokens(json.loads(row[0])) if row else None
        except (sqlite3.Error,OSError,ValueError,TypeError) as exc:self.last_error=str(exc);return None

    def record(self,key,tokens,elapsed,source):
        try:
            tokens=clean_tokens(tokens);payload=json.dumps(tokens,ensure_ascii=False)
            confidence=sum(t[2] for t in tokens)/len(tokens) if tokens else 0
            cacheable=bool(tokens) and confidence>=.93 and min(t[2] for t in tokens)>=.80
            now=dt.datetime.now(dt.timezone.utc)
            with closing(sqlite3.connect(self.path)) as db:
                db.execute('INSERT INTO reads(ts,key,source,elapsed,count,tokens) VALUES (?,?,?,?,?,?)',(now.isoformat(),PROFILE+key,source,max(0,float(elapsed)),len(tokens),payload))
                if cacheable:db.execute('INSERT OR REPLACE INTO cache VALUES (?,?,?)',(PROFILE+key,payload,now.timestamp()))
                db.execute('DELETE FROM reads WHERE id NOT IN (SELECT id FROM reads ORDER BY id DESC LIMIT 2000)')
                db.execute('DELETE FROM cache WHERE key NOT IN (SELECT key FROM cache ORDER BY touched DESC LIMIT 512)')
                db.commit()
        except (sqlite3.Error,OSError,ValueError,TypeError) as exc:self.last_error=str(exc)

    def recent(self):
        with closing(sqlite3.connect(self.path)) as db:
            rows=db.execute('SELECT ts,source,elapsed,count,tokens FROM reads ORDER BY id DESC LIMIT 200').fetchall()
            cached=db.execute('SELECT COUNT(*) FROM cache').fetchone()[0]
        return cached,rows

    def clear_cache(self):
        with closing(sqlite3.connect(self.path)) as db:db.execute('DELETE FROM cache');db.commit()
