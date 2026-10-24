"""Atomic scan cursor; prior-day progress is archived before starting a new day."""
import datetime as dt
import json
import os
import uuid
import threading
from pathlib import Path


def scan_context(cfg):
    auto=cfg.get('auto_config') or {}
    return {'auto':{k:auto.get(k) for k in ('title','window_size','rankings','table','pages','full_table')},
            'rules':{k:cfg.get(k) for k in ('min_price','min_volume','min_gain','max_gain','use_turnover','min_turnover','use_accel','accel','use_breakout')},
            'profile':{k:v for k,v in cfg.items() if k=='scan_profile' or k.startswith(('shrink_','use_shrink_'))}}


class ScanCheckpoint:
    def __init__(self,path):
        self.path=Path(path);self.lock=threading.RLock();self.data={};self.archived_path=None
        if self.path.exists():
            try:
                self.data=json.loads(self.path.read_text(encoding='utf-8'))
                if not isinstance(self.data,dict) or self.data.get('version')!=1:raise ValueError()
            except (ValueError,OSError):raise ValueError('進度檔無法讀取，原檔已保留；請按重新開始建立新進度')

    def resume(self,context):
        if not self.data:return None
        if self.data.get('day')!=dt.date.today().isoformat():
            self.archive_previous_day()
            return None
        if self.data.get('context')!=context:raise ValueError('掃描設定已變更，前次進度仍保留；請還原設定續掃，或按重新開始')
        cursor=self.data.get('cursor',{})
        if type(cursor.get('cycle')) is not int or cursor['cycle']<1 or type(cursor.get('rank')) is not int or not 0<=cursor['rank']<len(context['auto']['rankings']):
            raise ValueError('進度位置無效，已保留；請按重新開始')
        return json.loads(json.dumps(cursor))

    def archive_previous_day(self):
        """Archive exact bytes, durably, before retiring the old active cursor."""
        with self.lock:
            try:day=dt.date.fromisoformat(str(self.data.get('day'))).isoformat()
            except ValueError:day='unknown-day'
            folder=self.path.parent/'scan-history'
            archive=folder/f'scan-{day}-{dt.datetime.now():%Y%m%d-%H%M%S}-{uuid.uuid4().hex}.json'
            created=False
            try:
                folder.mkdir(parents=True,exist_ok=True)
                raw=self.path.read_bytes()
                with archive.open('xb') as output:
                    created=True;output.write(raw);output.flush();os.fsync(output.fileno())
                self.path.unlink()
            except OSError as error:
                if created:
                    try:archive.unlink(missing_ok=True)
                    except OSError:pass
                raise OSError('無法封存前次進度，原進度仍保留；請檢查儲存空間或檔案權限：'+str(error)) from error
            self.archived_path=archive
            self.data={}
            return archive

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


def rebase_viewport_cursor(table):
    """Old wheel offsets are invalid after relayout; retain every observed quote."""
    table=json.loads(json.dumps(table))
    table.update(segment=0,codes=[],offset=0,notches=1,cap=1,relayout=True)
    return table
