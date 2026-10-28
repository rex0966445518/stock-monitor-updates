"""Independent, measured scan-rate display which survives task text changes."""
import time
from collections import deque
import unicodedata

def digit_count(texts):
    return sum(c in "0123456789" for text in texts for c in unicodedata.normalize("NFKC",str(text)))


class ScanRate:
    def __init__(self):self.samples=deque();self.started=None;self.last_seconds=None;self.last='讀取速率：等待首段'
    def update(self,state):
        if 'digits' in state:
            self.samples.append((state['at'],state['digits']));return
        if state.get('start') is not None:self.started=state['start']
        else:
            elapsed=max(.001,state['elapsed']);count=state['count'];self.last_seconds=elapsed
            self.last=f'{elapsed:.1f} 秒／段｜{count/elapsed:.2f} 檔／秒';self.started=None
    def text(self,now=None):
        now=time.monotonic() if now is None else now
        while self.samples and self.samples[0][0]<=now-1:self.samples.popleft()
        prefix=f'{sum(n for at,n in self.samples if at<=now)} 字／秒｜'
        if self.started is None:return prefix+self.last
        elapsed=max(0,now-self.started)
        return prefix+self.last+f'｜本次讀取 {elapsed:.0f} 秒'
    def stop(self):self.started=None;self.samples.clear()

