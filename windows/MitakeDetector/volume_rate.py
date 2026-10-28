"""Shared observed volume rates. No interpolation or invented volume samples."""
from bisect import bisect_left,bisect_right
from collections import deque
from dataclasses import dataclass
import datetime as dt
import math

TAIPEI=dt.timezone(dt.timedelta(hours=8))

@dataclass
class Rate:
    value: object=None
    status: str='待首筆資料'
    approximate: bool=False
    previous_seconds: float=0
    recent_seconds: float=0

    def cell(self):
        if self.value is None:return self.status
        return ('約' if self.approximate else '')+f'{self.value:.2f}'

class VolumeRates:
    def __init__(self):self.history={};self.day=None
    def ingest(self,code,stamp,volume):
        if not math.isfinite(stamp) or not math.isfinite(volume) or volume<0:return False
        day=dt.datetime.fromtimestamp(stamp,TAIPEI).date()
        if self.day and day<self.day:return False
        if day!=self.day:self.history={};self.day=day
        samples=self.history.setdefault(code,deque())
        if samples and (stamp<=samples[-1][0] or volume<samples[-1][1]):return False
        samples.append((stamp,volume))
        # Keep one extra boundary sample rather than truncating the only anchor.
        while len(samples)>2 and samples[1][0]<stamp-1200:samples.popleft()
        return True
    def result(self,code,stamp):
        rows=[r for r in self.history.get(code,()) if r[0]<=stamp]
        if not rows:return Rate()
        latest=rows[-1]
        if stamp-latest[0]>30:return Rate(status='量讀值未更新')
        span=latest[0]-rows[0][0]
        if span<600:return Rate(status=f'累積{int(span//60):02d}:{int(span%60):02d}/10:00')
        # Choose actual samples nearest 10 and 5 minutes ago. Each *actual*
        # interval must remain 3–6 minutes, and combined coverage 8–12 minutes.
        # An offset is disclosed as approximate; cumulative volumes stay untouched.
        timestamps=[r[0] for r in rows];best=None;score=float('inf')
        for b in rows:
            if not 180<=latest[0]-b[0]<=360:continue
            low=max(b[0]-360,latest[0]-720)
            high=min(b[0]-180,latest[0]-480)
            left=bisect_left(timestamps,low);right=bisect_right(timestamps,high)
            if left>=right:continue
            target=bisect_left(timestamps,latest[0]-600,left,right)
            for index in {min(target,right-1),max(left,target-1)}:
                a=rows[index]
                distance=abs(a[0]-(latest[0]-600))+abs(b[0]-(latest[0]-300))
                if distance<score:best=(a,b);score=distance
        if best is None:return Rate(status='採樣缺口／需回訪')
        a,b=best
        old=b[1]-a[1];new=latest[1]-b[1]
        if old<=0:return Rate(status='前窗無成交量')
        if new<0:return Rate(status='成交量倒退待複查')
        before=b[0]-a[0];recent=latest[0]-b[0]
        value=(new/recent)/(old/before)
        approximate=abs(before-300)>15 or abs(recent-300)>15
        return Rate(value=value,status=f'量速實測窗：前{before:.0f}秒／近{recent:.0f}秒',approximate=approximate,previous_seconds=before,recent_seconds=recent)
    def dump(self):return dict(day=self.day.isoformat() if self.day else None,history={c:list(rows) for c,rows in self.history.items()})
    @classmethod
    def restore(cls,state,legacy=()):
        result=cls();rows=[]
        for code,items in (state or {}).get('history',{}).items():
            rows.extend((float(ts),code,float(volume)) for ts,volume in items)
        for code,items in legacy:
            rows.extend((q.ts,code,q.volume) for q in items)
        for stamp,code,volume in sorted(rows):result.ingest(code,stamp,volume)
        return result
