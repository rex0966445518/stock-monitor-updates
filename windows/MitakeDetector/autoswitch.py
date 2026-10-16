"""Local Windows ranking-menu automation. All input is bounded to calibrated ROIs."""
import ctypes
import difflib
import os
import re
import threading
import time
import unicodedata
from ctypes import wintypes as W

RANKINGS = ['漲幅排行','跌幅排行','成量排行','成值排行','估量排行','預估量增','大單流入','大單流出','大單匯集','大單渙散','分時買力','分時賣力','累計買力','累計賣力','價漲動能','價跌動能','量增排行','量縮排行','高價排行','低價排行','開盤強勢','開盤弱勢','開高走低','開低走高','週轉排行','大幅震盪','連續買單','連續賣單','買氣強大','賣壓沉重','現股當沖','注意股','處置股','異常推介','特殊異常','暫停交易','延後開盤','延後收盤','漲停鎖住']
TRANSLATE = str.maketrans('漲預單匯買賣盤轉強連續鎖處暫開壓氣異後價縮當沖震盪實時總','涨预单汇买卖盘转强连续锁处暂开压气异后价缩当冲震荡实时总')

class AutomationStopped(RuntimeError): pass


class F8Toggle:
    """Start on release; stop on press; holding never toggles repeatedly."""
    def __init__(self):
        self.down=False
        self.pending_start=False

    def update(self, down, busy):
        if down and not self.down:
            self.down=True
            self.pending_start=not busy
            return 'stop' if busy else None
        if not down and self.down:
            self.down=False
            start=self.pending_start
            self.pending_start=False
            return 'start' if start and not busy else None
        return None


def normalized(text):
    return ''.join(re.findall(r'[\u3400-\u9fff0-9]+',unicodedata.normalize('NFKC',text))).translate(TRANSLATE)


def validate_roi(roi):
    if not isinstance(roi,(tuple,list)) or len(roi)!=4 or any(type(n) not in (int,float) for n in roi):
        raise ValueError('請先設定選單與表格位置')
    x,y,w,h=roi
    if not (0<=x<1 and 0<=y<1 and .002<=w<=1 and .002<=h<=1 and x+w<=1.001 and y+h<=1.001):
        raise ValueError('框選區域超出目標視窗')
    return roi


def absolute_roi(roi,rect):
    x,y,w,h=validate_roi(roi);l,t,r,b=rect
    return dict(left=round(l+x*(r-l)),top=round(t+y*(b-t)),width=max(1,round(w*(r-l))),height=max(1,round(h*(b-t))))


def find_menu_item(ocr, name):
    target=normalized(name)
    tokens=[t for t in ocr or [] if t[2]>=.65]
    for box,text,score in tokens:
        if normalized(text)==target:
            return (sum(p[0] for p in box)/4,sum(p[1] for p in box)/4)
    # OCR may split one menu label into adjacent words; never join different rows.
    for first in tokens:
        box=first[0];y=sum(p[1] for p in box)/4
        height=max(p[1] for p in box)-min(p[1] for p in box)
        row=sorted([t for t in tokens if abs(sum(p[1] for p in t[0])/4-y)<max(4,height*.45)],key=lambda t:min(p[0] for p in t[0]))
        for start in range(len(row)):
            text='';pieces=[];right=None
            for token in row[start:]:
                left=min(p[0] for p in token[0])
                if right is not None and left-right>height*2:break
                right=max(p[0] for p in token[0]);pieces.append(token);text+=normalized(token[1])
                if len(pieces)>1 and text==target:
                    return ((min(p[0] for t in pieces for p in t[0])+max(p[0] for t in pieces for p in t[0]))/2,y)
                if len(text)>=len(target):break
    return None


class WindowsController:
    def __init__(self, hwnd, stop):
        if os.name!='nt':raise RuntimeError('自動操作僅支援Windows')
        self.u=ctypes.WinDLL('user32',use_last_error=True)
        self.hwnd=int(hwnd);self.stop=stop
        self.u.GetForegroundWindow.restype=W.HWND
        self.u.GetAncestor.argtypes=[W.HWND,W.UINT];self.u.GetAncestor.restype=W.HWND
        self.u.WindowFromPoint.argtypes=[W.POINT];self.u.WindowFromPoint.restype=W.HWND
        self.u.GetWindowThreadProcessId.argtypes=[W.HWND,ctypes.POINTER(W.DWORD)]
        self.u.GetWindowRect.argtypes=[W.HWND,ctypes.POINTER(W.RECT)]
        self.u.SetForegroundWindow.argtypes=[W.HWND]
        self.u.IsWindow.argtypes=[W.HWND];self.u.IsIconic.argtypes=[W.HWND]
        self.u.SetCursorPos.argtypes=[ctypes.c_int,ctypes.c_int]
        self.u.mouse_event.argtypes=[W.DWORD,W.DWORD,W.DWORD,W.DWORD,ctypes.c_size_t]
        self.pid=self.process_id(hwnd)
        self.initial_rect=self.rect()

    @staticmethod
    def windows():
        if os.name!='nt':return []
        u=ctypes.WinDLL('user32',use_last_error=True)
        u.IsWindowVisible.argtypes=[W.HWND]
        u.GetWindowTextW.argtypes=[W.HWND,W.LPWSTR,ctypes.c_int]
        callback=ctypes.WINFUNCTYPE(W.BOOL,W.HWND,W.LPARAM)
        output=[]
        def each(hwnd,_):
            if u.IsWindowVisible(hwnd):
                buf=ctypes.create_unicode_buffer(512);u.GetWindowTextW(hwnd,buf,512)
                if buf.value:output.append((int(hwnd),buf.value))
            return True
        u.EnumWindows(callback(each),0)
        return output

    def process_id(self,hwnd):
        pid=W.DWORD();self.u.GetWindowThreadProcessId(hwnd,ctypes.byref(pid));return pid.value

    def belongs(self,hwnd):
        return bool(hwnd) and self.process_id(hwnd)==self.pid and self.u.GetAncestor(hwnd,3)==self.u.GetAncestor(self.hwnd,3)

    def rect(self):
        r=W.RECT()
        if not self.u.IsWindow(self.hwnd) or self.u.IsIconic(self.hwnd) or not self.u.GetWindowRect(self.hwnd,ctypes.byref(r)):
            raise AutomationStopped('目標視窗已關閉或最小化，已停止')
        if r.right-r.left<100 or r.bottom-r.top<100:raise AutomationStopped('目標視窗大小無效')
        return (r.left,r.top,r.right,r.bottom)

    def check(self):
        if self.stop.is_set():raise AutomationStopped('已停止自動操作')
        if self.u.GetAsyncKeyState(0x77)&0x8000:
            self.stop.set();raise AutomationStopped('F8緊急停止')
        rect=self.rect()
        if self.process_id(self.hwnd)!=self.pid:raise AutomationStopped('目標視窗已更換')
        # Refuse to continue after resizing; calibrated proportions can change column layout.
        a=self.initial_rect
        if abs((rect[2]-rect[0])-(a[2]-a[0]))>3 or abs((rect[3]-rect[1])-(a[3]-a[1]))>3:
            raise AutomationStopped('目標視窗已縮放，請重新設定位置')
        fg=self.u.GetForegroundWindow()
        if not self.belongs(fg):
            raise AutomationStopped('焦點已離開三竹目標視窗，已停止；請手動重新開始')

    def activate(self):
        self.rect();self.u.SetForegroundWindow(self.hwnd);self.pause(.4,check=False);self.check()

    def monitor_hotkey(self):
        while not self.stop.wait(.02):
            if self.u.GetAsyncKeyState(0x77)&0x8000:self.stop.set()

    def pause(self,seconds,check=True):
        end=time.monotonic()+seconds
        while time.monotonic()<end:
            if self.stop.wait(min(.05,max(0,end-time.monotonic()))):raise AutomationStopped('已停止自動操作')
            if check:self.check()

    def point(self,point):
        self.check();x,y=map(round,point)
        owner=self.u.WindowFromPoint(W.POINT(x,y))
        if not self.belongs(owner):raise AutomationStopped('操作區域被其他視窗遮住，已停止')
        if not self.u.SetCursorPos(x,y):raise AutomationStopped('無法移動游標')
        self.check()
        return x,y

    def click(self,point):
        self.point(point)
        # One complete click; no keyboard shortcuts or order-entry commands are sent.
        self.u.mouse_event(0x0002,0,0,0,0);self.u.mouse_event(0x0004,0,0,0,0)

    def scroll(self,point,delta):
        self.point(point)
        self.u.mouse_event(0x0800,0,0,int(delta)&0xffffffff,0)

    def image(self,roi):
        self.check()
        from PIL import Image
        import mss
        region=absolute_roi(roi,self.rect())
        # Center check catches overlays, including the detector itself.
        owner=self.u.WindowFromPoint(W.POINT(region['left']+region['width']//2,region['top']+region['height']//2))
        if not self.belongs(owner):raise AutomationStopped('讀取區域被遮住，已停止')
        with mss.mss() as cap:
            shot=cap.grab(region)
            return Image.frombytes('RGB',shot.size,shot.rgb),region


class RankingSwitcher:
    def __init__(self,controller,ocr,combo_roi,menu_roi,ocr_retry=None,status=None):
        self.c=controller;self.ocr=ocr;self.ocr_retry=ocr_retry;self.status=status or (lambda text:None)
        self.combo=validate_roi(combo_roi);self.menu=validate_roi(menu_roi)

    def label_matches(self,name):
        im,_=self.c.image(self.combo)
        for reader in [self.ocr,self.ocr_retry]:
            if reader is None:continue
            tokens=reader(im)
            text=''.join(str(item[1]) for item in sorted(tokens or [],key=lambda item:min(p[0] for p in item[0])) if item[2]>=.65)
            if normalized(text)==normalized(name):return True
        return False

    def switch(self,name,menu_open=False):
        if name not in RANKINGS:raise ValueError('未知排行名稱')
        self.c.check()
        # A verified current label needs no mouse input. An already-open popup must
        # instead be selected directly so setup never guesses its toggle state.
        if not menu_open and self.label_matches(name):return
        if not menu_open:
            _,combo=self.c.image(self.combo)
            self.c.click((combo['left']+combo['width']//2,combo['top']+combo['height']//2))
            self.c.pause(.4)
        menu=absolute_roi(self.menu,self.c.rect())
        center=(menu['left']+menu['width']//2,menu['top']+menu['height']//2)
        self.c.scroll(center,120*60);self.c.pause(.35)
        previous=None;unchanged=0
        for page in range(60):
            self.c.check();im,region=self.c.image(self.menu);tokens=self.ocr(im)
            self.status(f'正在尋找「{name}」｜選單第{page+1}段')
            point=find_menu_item(tokens,name)
            if point is None and self.ocr_retry and any(difflib.SequenceMatcher(None,normalized(t[1]),normalized(name)).ratio()>=.55 for t in tokens or []):
                alternative=self.ocr_retry(im)
                point=find_menu_item(alternative,name)
                if point:tokens=alternative
            if point:
                self.c.click((region['left']+point[0],region['top']+point[1]));self.c.pause(.5)
                for _ in range(3):
                    if self.label_matches(name):return
                    self.c.pause(.4)
                raise AutomationStopped('切換後標題不符，已停止，沒有記入該排行資料')
            fingerprint=tuple(normalized(str(t[1])) for t in tokens or [])
            unchanged=unchanged+1 if fingerprint and fingerprint==previous else 0
            if unchanged>=2:break
            previous=fingerprint
            # One wheel notch preserves overlapping rows on narrow native lists.
            self.c.scroll(center,-120);self.c.pause(.45)
        observed='、'.join(str(t[1]) for t in tokens or [])[:100]
        raise AutomationStopped('選單中找不到「'+name+'」；本次讀到：'+(observed or '無文字')+'；已停止')
