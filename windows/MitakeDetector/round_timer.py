"""Persist active work seconds; exclude time between process runs."""
import time
import math
class RoundTimer:
    def __init__(self,elapsed=0,clock=time.monotonic):
        try:self.saved=max(0,float(elapsed))
        except (TypeError,ValueError):self.saved=0
        if not math.isfinite(self.saved):self.saved=0
        self.clock=clock;self.started=clock()
    def elapsed(self):return self.saved+max(0,self.clock()-self.started)
