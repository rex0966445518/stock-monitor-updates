"""Bounded local interruption reports, without screen images or quote payloads."""
import datetime as dt
import json
import traceback
from pathlib import Path


def record_interruption(path,version,state,exc):
    frames=traceback.extract_tb(exc.__traceback__)
    origin=f'{Path(frames[-1].filename).name}:{frames[-1].lineno}' if frames else '無堆疊位置'
    entry=dict(time=dt.datetime.now(dt.timezone.utc).astimezone().isoformat(timespec='seconds'),version=version,
               **state,error_type=type(exc).__name__,error=str(exc),origin=origin,traceback=''.join(traceback.format_exception(type(exc),exc,exc.__traceback__)))
    path=Path(path)
    try:
        previous=path.read_text(encoding='utf-8').splitlines() if path.exists() else []
        path.parent.mkdir(parents=True,exist_ok=True)
        temporary=path.with_suffix('.tmp')
        temporary.write_text('\n'.join(previous[-199:]+[json.dumps(entry,ensure_ascii=False)])+'\n',encoding='utf-8');temporary.replace(path)
        saved=True
    except OSError:saved=False
    detail=f'第{state.get("cycle","?")}輪｜{state.get("ranking","啟動")}｜第{state.get("segment",0)}段｜已讀{state.get("count",0)}檔｜{state.get("stage","啟動")}｜{origin}'
    return detail+('｜診斷已保存' if saved else '｜診斷檔無法寫入')
