"""Atomic local scan cursor. Existing progress is discarded only on explicit reset."""
import datetime as dt
import json
import threading
from pathlib import Path


def scan_context(cfg):
    auto=cfg.get('auto_config') or {}
    return {'auto':{k:auto.get(k) for k in ('title','window_size','rankings','table','pages','full_table')},
            'rules':{k:cfg.get(k) for k in ('min_price','min_volume','min_gain','max_gain','use_turnover','min_turnover','use_accel','accel','use_breakout')}}


class ScanCheckpoint:
    def __init__(self,path):
        self.path=Path(path);self.lock=threading.RLock();self.data={}
        if self.path.exists():
            try:
                self.data=json.loads(self.path.read_text(encoding='utf-8'))
                if not isinstance(self.data,dict) or self.data.get('version')!=1:raise ValueError()
            except (ValueError,OSError):raise ValueError('進度檔無法讀取，原檔已保留；請按重新開始建立新進度')

    def resume(self,context):
        if not self.data:return None
        if self.data.get('day')!=dt.date.today().isoformat():raise ValueError('前次進度日期不同，已保留；請按重新開始進行今日觀察')
        if self.data.get('context')!=context:raise ValueError('掃描設定已變更，前次進度仍保留；請還原設定續掃，或按重新開始')
        cursor=self.data.get('cursor',{})
        if type(cursor.get('cycle')) is not int or cursor['cycle']<1 or type(cursor.get('rank')) is not int or not 0<=cursor['rank']<len(context['auto']['rankings']):
            raise ValueError('進度位置無效，已保留；請按重新開始')
        return json.loads(json.dumps(cursor))

    def update(self,context,cursor,**extra):
        with self.lock:
            self.data.update(version=1,day=dt.date.today().isoformat(),context=context,cursor=cursor,**extra)
            self.path.parent.mkdir(parents=True,exist_ok=True)
            temporary=self.path.with_suffix('.tmp')
            temporary.write_text(json.dumps(self.data,ensure_ascii=False),encoding='utf-8');temporary.replace(self.path)

    def snapshot(self,ui):
        with self.lock:
            if self.data:self.update(self.data['context'],self.data['cursor'],ui=ui)

    def clear(self):
        with self.lock:
            self.path.unlink(missing_ok=True);self.data={}
