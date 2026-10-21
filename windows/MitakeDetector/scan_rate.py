"""Independent, measured scan-rate display which survives task text changes."""
import time


class ScanRate:
    def __init__(self):self.started=None;self.last_seconds=None;self.last='讀取速率：等待首段'
    def update(self,state):
        if state.get('start') is not None:self.started=state['start']
        else:
            elapsed=max(.001,state['elapsed']);count=state['count'];self.last_seconds=elapsed
            self.last=f'{elapsed:.1f} 秒／段｜{count/elapsed:.2f} 檔／秒';self.started=None
    def text(self,now=None):
        if self.started is None:return self.last
        elapsed=max(0,(time.monotonic() if now is None else now)-self.started)
        return self.last+f'｜本次讀取 {elapsed:.0f} 秒'
    def stop(self):self.started=None
