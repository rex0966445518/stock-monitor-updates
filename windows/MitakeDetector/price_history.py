"""Eight dated price snapshots; no carry-forward for unobserved stocks."""
import datetime as dt
import math


class PriceHistory:
    def __init__(self,data=None):
        self.slots=list(data or [])[:8]

    def update(self,quotes,cycle=None,interval=None):
        valid=[q for q in quotes if math.isfinite(q.ts) and math.isfinite(q.price) and q.price>0]
        if not valid:return
        if interval is not None:
            if not math.isfinite(interval) or interval<=0:raise ValueError('價格紀錄間隔無效')
            zone=dt.timezone(dt.timedelta(hours=8))
            for q in sorted(valid,key=lambda q:q.ts):
                day=dt.datetime.fromtimestamp(q.ts,zone).date().isoformat()
                if self.slots and day<self.slots[0]['day']:continue
                if self.slots and day!=self.slots[0]['day']:self.slots=[]
                key=f'{day}:time:{int(q.ts//interval)}'
                slot=next((s for s in self.slots if s['key']==key),None)
                if slot is None:
                    slot=dict(key=key,day=day,time=q.ts,prices={})
                    self.slots.append(slot);self.slots.sort(key=lambda s:s['time'],reverse=True)
                    del self.slots[8:]
                    if slot not in self.slots:continue
                previous=slot['prices'].get(q.code)
                if previous is None or q.ts>previous[0]:slot['prices'][q.code]=[q.ts,q.price]
            return
        timestamp=max(q.ts for q in valid)
        day=dt.datetime.fromtimestamp(timestamp).date().isoformat()
        if self.slots and self.slots[0]['day']!=day:self.slots=[]
        key=f'{day}:round:{cycle}' if cycle is not None else f'{day}:batch:{timestamp}'
        slot=next((s for s in self.slots if s['key']==key),None)
        if slot is None:
            slot=dict(key=key,day=day,time=min(q.ts for q in valid),prices={})
            self.slots.insert(0,slot);del self.slots[8:]
        for q in valid:
            previous=slot['prices'].get(q.code)
            if previous is None or q.ts>previous[0]:slot['prices'][q.code]=[q.ts,q.price]

    def headings(self):
        labels=[dt.datetime.fromtimestamp(s['time']).strftime('%H:%M:%S') for s in self.slots]
        return labels+[f'待掃描 {i+1}' for i in range(len(labels),8)]

    def values(self,code):
        values=[f"{s['prices'][code][1]:.2f}" if code in s['prices'] else '—' for s in self.slots]
        return values+['—']*(8-len(values))
