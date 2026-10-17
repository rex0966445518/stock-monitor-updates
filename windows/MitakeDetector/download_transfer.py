"""Bounded ranged downloads, preserving segments across transient failures."""
import concurrent.futures
import os
import threading
import time
from pathlib import Path


class RangeUnavailable(Exception):pass


def valid_range(response,start,end,total):
    status=getattr(response,'status',None)
    headers=getattr(response,'headers',{})
    return status==206 and headers.get('Content-Range')==f'bytes {start}-{end}/{total}'


def parallel_download(manifest,partial,opener,make_request,report):
    size=manifest['size'];partial=Path(partial);started=time.monotonic()
    # Only enable multiple connections after an actual, exact range response.
    request=make_request(manifest['url']);request.add_header('Range','bytes=0-0')
    with opener(request,timeout=40) as probe:
        if not valid_range(probe,0,0,size):return False
        if probe.read(2)!=b'M':raise ValueError('下載來源不是有效的安裝檔')
    paths=[partial.with_name(partial.name+'.'+manifest['sha256'][:16]+f'.segment{i}') for i in range(4)]
    edges=[size*i//4 for i in range(5)]
    counts=[p.stat().st_size if p.exists() else 0 for p in paths]
    for i,p in enumerate(paths):
        if counts[i]>edges[i+1]-edges[i]:p.unlink(missing_ok=True);counts[i]=0
    resumed=sum(counts);lock=threading.Lock();cancel=threading.Event()
    def update():
        report(sum(counts),size,time.monotonic()-started,resumed)
    report(resumed,size,0,resumed)
    def worker(i):
        end=edges[i+1]-1
        for attempt in range(3):
            start=edges[i]+counts[i]
            if start>end:return
            req=make_request(manifest['url']);req.add_header('Range',f'bytes={start}-{end}')
            try:
                with opener(req,timeout=25) as response:
                    if not valid_range(response,start,end,size):raise RangeUnavailable()
                    with paths[i].open('ab') as out:
                        while not cancel.is_set():
                            remaining=end-edges[i]+1-counts[i]
                            if not remaining:return
                            chunk=response.read(min(32768,remaining))
                            if not chunk:raise OSError('分段連線提前中斷')
                            out.write(chunk)
                            with lock:counts[i]+=len(chunk);update()
                        out.flush();os.fsync(out.fileno())
                return
            except OSError:
                if attempt==2:raise
                if cancel.wait(.25):return
    try:
        with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
            futures=[pool.submit(worker,i) for i in range(4)]
            try:
                for future in concurrent.futures.as_completed(futures):future.result()
            except BaseException:
                cancel.set();raise
    except RangeUnavailable:
        for p in paths:p.unlink(missing_ok=True)
        return False
    if sum(counts)!=size:raise OSError('分段下載未完成，已保留進度')
    with partial.open('wb') as out:
        for p in paths:
            with p.open('rb') as source:
                while True:
                    chunk=source.read(256*1024)
                    if not chunk:break
                    out.write(chunk)
        out.flush();os.fsync(out.fileno())
    # Keep completed segments until the caller verifies the complete file.
    return True


def clear_segments(partial):
    partial=Path(partial)
    for p in partial.parent.glob(partial.name+'.*.segment*'):p.unlink(missing_ok=True)
