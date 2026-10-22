"""Thread-scoped Windows idle sleep/display inhibition; never edits power plans."""
import sys
import time
import threading

CONTINUOUS = 0x80000000
AWAKE = CONTINUOUS | 0x00000001 | 0x00000002

class PowerGuard:
    def __init__(self, setter=None):
        self.owner = threading.get_ident()
        if setter is None and sys.platform == 'win32':
            import ctypes
            setter = ctypes.windll.kernel32.SetThreadExecutionState
            setter.argtypes = [ctypes.c_uint]
            setter.restype = ctypes.c_uint
        self.setter = setter
        self.active = False
        self.retry_at = 0

    def sync(self, wanted):
        if threading.get_ident() != self.owner:
            raise RuntimeError('PowerGuard must run on its creating UI thread')
        wanted = bool(wanted)
        if self.setter is None or wanted == self.active:
            return False
        if time.monotonic() < self.retry_at:
            return False
        if not self.setter(AWAKE if wanted else CONTINUOUS):
            self.retry_at = time.monotonic() + 30
            raise OSError('Windows 未接受保持喚醒要求')
        self.active = wanted
        self.retry_at = 0
        return True

    def close(self):
        self.retry_at = 0
        return self.sync(False)


def scanning(app):
    return bool(getattr(app, 'scan_retry_timer', None) or
                (not app.stop_event.is_set() and
                 (app.auto_active or (app.worker_thread and app.worker_thread.is_alive()))))
