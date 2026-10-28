"""Offline release notes plus first successful launch of each installed version."""
from contextlib import closing
import datetime as dt
import json
import sqlite3
from pathlib import Path


def record_launch(base,version):
    with closing(sqlite3.connect(Path(base)/'version-history.sqlite3')) as db:
        db.execute('CREATE TABLE IF NOT EXISTS launches (version TEXT PRIMARY KEY,first_seen TEXT NOT NULL)')
        db.execute('INSERT OR IGNORE INTO launches VALUES (?,?)',(version,dt.datetime.now(dt.timezone.utc).isoformat()))
        db.commit()


def history_text(base):
    try:entries=json.loads((Path(__file__).parent/'version_history.json').read_text(encoding='utf-8'))
    except (ValueError,OSError):entries=[]
    try:
        with closing(sqlite3.connect(Path(base)/'version-history.sqlite3')) as db:installed=dict(db.execute('SELECT version,first_seen FROM launches'))
    except sqlite3.Error:installed={}
    def local(value):
        return dt.datetime.fromisoformat(value.replace('Z','+00:00')).astimezone(dt.timezone(dt.timedelta(hours=8))).strftime('%Y-%m-%d %H:%M:%S')+'（台北）'
    out=['版本迭代日誌','本機首次啟用時間從加入此功能的版本開始記錄。','']
    for item in sorted(entries,key=lambda x:tuple(int(n) for n in x['version'].split('.')),reverse=True):
        version=item['version'];out.append('v'+version)
        if item.get('published_at'):out.append('發布時間：'+local(item['published_at']))
        elif item.get('built_at'):out.append('建置時間：'+local(item['built_at'])+'｜發布時間尚未收錄')
        if version in installed:out.append('本機首次啟用：'+local(installed[version]))
        out.extend([item.get('notes',''),'', '─'*35,''])
    return '\n'.join(out)
