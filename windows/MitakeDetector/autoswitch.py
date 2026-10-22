"""Local Windows ranking-menu automation. All input is bounded to calibrated ROIs."""
import ctypes
import os
import re
import threading
import time
import unicodedata
from ctypes import wintypes as W

# Verified top-to-bottom against the complete 2026-10-21 menu recording.
RANKINGS = [
    '漲停鎖住','跌停鎖住','即將漲停','即將跌停','瞬間拉抬','瞬間殺盤',
    '漲幅排行','跌幅排行','成量排行','成值排行','估量排行','預估量增',
    '大單流入','大單流出','大單匯集','大單渙散','分時買力','分時賣力',
    '累計買力','累計賣力','價漲動能','價跌動能','單量排行','量增排行',
    '高價排行','低價排行','開盤強勢','開盤弱勢','開高走低','開低走高',
    '週轉排行','大幅震盪','連續買單','連續賣單','買氣強大','賣壓沉重',
    '現股當沖','注意股','處置股','異常推介','特殊異常','暫停交易',
    '延後開盤','延後收盤',
]
TRANSLATE = str.maketrans('漲預單匯買賣盤轉強連續鎖處暫開壓氣異後價縮當沖震盪實時總計將間殺渙動勢週現','涨预单汇买卖盘转强连续锁处暂开压气异后价缩当冲震荡实时总计将间杀涣动势周现')

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


def find_buy_power_between_neighbors(tokens):
    """Infer only the documented single row between two verified neighbors.

    A third consecutive known row establishes actual row pitch; text height alone
    cannot distinguish one missing row from several missing rows.
    """
    top=find_menu_item(tokens,'大單渙散')
    bottom=find_menu_item(tokens,'分時賣力')
    if not top or not bottom or bottom[1]<=top[1]:return None
    pitch=(bottom[1]-top[1])/2
    if pitch<6 or abs(top[0]-bottom[0])>pitch*.5:return None
    for box,text,score in tokens or []:
        if score<.65:continue
        y=sum(p[1] for p in box)/4
        if top[1]+pitch*.3<y<bottom[1]-pitch*.3:
            if normalized(text) in {normalized(n) for n in RANKINGS if n!='分時買力'}:return None
    # Confirm at least one adjacent row outside the ambiguous middle row.
    for label,y in [('大單匯集',top[1]-pitch),('大單流出',top[1]-2*pitch),
                    ('累計買力',bottom[1]+pitch),('累計賣力',bottom[1]+2*pitch)]:
        point=find_menu_item(tokens,label)
        if point and abs(point[1]-y)<=pitch*.2 and abs(point[0]-top[0])<=pitch*.5:
            return ((top[0]+bottom[0])/2,(top[1]+bottom[1])/2)
    return None


def cumulative_buy_suffix(tokens):
    # Permit only an unreadable first character, never buy/sell ambiguity.
    for box,text,score in tokens or []:
        text=normalized(text)
        if score>=.85 and text not in {normalized(n) for n in RANKINGS if n!='累計買力'} and len(text) in (3,4) and text.endswith(normalized('計買力')):
            return (sum(p[0] for p in box)/4,sum(p[1] for p in box)/4)
    # The distinctive suffix can be split into adjacent high-confidence pieces.
    # Reuse the same-row/gap checks; never accept bare 買力 or join different rows.
    return find_menu_item([t for t in tokens or [] if t[2]>=.85],'計買力')


def find_cumulative_buy_below_sell(tokens,height):
    above=find_menu_item(tokens,'分時買力');anchor=find_menu_item(tokens,'分時賣力')
    if not above or not anchor:return None
    pitch=anchor[1]-above[1]
    if pitch<6 or abs(above[0]-anchor[0])>pitch*.5:return None
    y=anchor[1]+pitch
    if y+pitch*.4>=height:return None  # Scroll before clicking a clipped row.
    for box,text,score in tokens or []:
        cy=sum(p[1] for p in box)/4
        if score>=.65 and abs(cy-y)<pitch*.4 and normalized(text) in {normalized(n) for n in RANKINGS if n!='累計買力'}:return None
    return (anchor[0],y)


def find_opening_strong_below_low(tokens,height):
    """Use verified row pitch and the user's low-price -> opening-strong relation."""
    anchor=find_menu_item(tokens,'低價排行')
    if not anchor:return None
    high=find_menu_item(tokens,'高價排行');weak=find_menu_item(tokens,'開盤弱勢')
    pitches=[]
    if high and abs(high[0]-anchor[0])<20:pitches.append(anchor[1]-high[1])
    if weak and abs(weak[0]-anchor[0])<20:pitches.append((weak[1]-anchor[1])/2)
    if not pitches or any(p<6 for p in pitches):return None
    pitch=pitches[0]
    if any(abs(p-pitch)>pitch*.2 for p in pitches):return None
    y=anchor[1]+pitch
    if y+pitch*.4>=height:return None
    for box,text,score in tokens or []:
        cy=sum(p[1] for p in box)/4
        if score>=.65 and abs(cy-y)<pitch*.4 and normalized(text) in {normalized(n) for n in RANKINGS if n!='開盤強勢'}:return None
    return anchor[0],y


class WindowsController:
    def __init__(self, hwnd, stop):
        if os.name!='nt':raise RuntimeError('自動操作僅支援Windows')
        self.u=ctypes.WinDLL('user32',use_last_error=True)
        self.hwnd=int(hwnd);self.stop=stop
        self.highlight=None
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
            raise AutomationStopped('目標視窗已縮放，進度保留；按F8自動重新定位並接續')
        fg=self.u.GetForegroundWindow()
        if not self.belongs(fg) and not self.dashboard_foreground(fg):
            raise AutomationStopped('焦點已切換到其他軟體，已暫停；三竹與探測器主頁之間切換不會停止')

    def dashboard_foreground(self,hwnd=None):
        hwnd=self.u.GetForegroundWindow() if hwnd is None else hwnd
        return bool(hwnd) and self.process_id(hwnd)==os.getpid()

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
        # A wheel event goes to the foreground app on some Windows settings.
        # Activate only immediately before input; reading/OCR never steals focus.
        if not self.belongs(self.u.GetForegroundWindow()):
            self.activate()
            if not self.belongs(self.u.GetForegroundWindow()):raise AutomationStopped('無法啟用三竹操作視窗')
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
        if self.highlight:
            self.highlight(dict(region))
            # Finish moving the old borders before taking the next screenshot.
            ctypes.windll.dwmapi.DwmFlush()
        self.check()
        # Center check catches overlays, including the detector itself.
        owner=self.u.WindowFromPoint(W.POINT(region['left']+region['width']//2,region['top']+region['height']//2))
        if not self.belongs(owner):raise AutomationStopped('讀取區域被遮住，已停止')
        with mss.mss() as cap:
            captured_at=time.time()
            shot=cap.grab(region)
            image=Image.frombytes('RGB',shot.size,shot.rgb)
            image.info['captured_at']=captured_at
            return image,region


class RankingSwitcher:
    def __init__(self,controller,ocr,combo_roi,menu_roi,ocr_retry=None,status=None,experience=None,numbering=False,menu_numbers=None,show_numbers=None):
        self.experience=experience
        self.c=controller;self.ocr=ocr;self.ocr_retry=ocr_retry;self.status=status or (lambda text:None)
        self.combo=validate_roi(combo_roi);self.menu=validate_roi(menu_roi)
        from menu_numbers import NumberNavigator
        self.numbers=NumberNavigator(self,menu_numbers,show_numbers) if numbering else None

    @staticmethod
    def menu_signature(im):
        if not hasattr(im,'tobytes'):return None
        import hashlib
        return [list(im.size),hashlib.sha256(im.tobytes()).hexdigest()]

    def enlarged_reads(self,im,name):
        if not hasattr(im,'resize'):return
        from PIL import Image
        for scale in (2,3):
            self.c.check()
            self.status(f'正在放大{scale}倍辨識「{name}」')
            enlarged=im.resize((im.width*scale,im.height*scale),Image.Resampling.LANCZOS)
            for reader in (self.ocr,self.ocr_retry):
                if reader is None:continue
                self.c.check()
                yield [([[x/scale,y/scale] for x,y in box],text,score)
                       for box,text,score in reader(enlarged) or []]

    def menu_point(self,im,tokens,name):
        point=find_menu_item(tokens,name)
        if point:return point
        if name=='開盤強勢':
            point=find_opening_strong_below_low(tokens,getattr(im,'height',0))
            if point:
                self.status('依低價排行下一列定位開盤強勢；驗證標題後儲存位置')
                return point
        if name=='累計買力':
            point=cumulative_buy_suffix(tokens) or find_cumulative_buy_below_sell(tokens,getattr(im,'height',0))
            if point:
                self.status('依分時賣力下一列定位累計買力，切換後核對計買力')
                return point
        # A clearly read ordered page outside the target's catalog range needs
        # no repeated/enlarged Chinese OCR. Ambiguous or intervening rows retry.
        visible=[(find_menu_item(tokens,label),index) for index,label in enumerate(RANKINGS)]
        visible=sorted((point[1],index) for point,index in visible if point)
        indices=[index for _,index in visible]
        target=RANKINGS.index(name)
        if len(indices)>=3 and indices==sorted(set(indices)) and not indices[0]<=target<=indices[-1]:
            return None
        candidates=[tokens]
        if self.ocr_retry:
            self.c.check()
            retry=self.ocr_retry(im);candidates.append(retry)
            point=find_menu_item(retry,name)
            if point:return point
        for enlarged in self.enlarged_reads(im,name):
            candidates.append(enlarged)
            point=find_menu_item(enlarged,name)
            if point:return point
        if name=='開盤強勢':
            for candidate in candidates:
                point=find_opening_strong_below_low(candidate,getattr(im,'height',0))
                if point:return point
        if name=='分時買力':
            for candidate in candidates:
                point=find_buy_power_between_neighbors(candidate)
                if point:
                    self.status('已核對大單渙散與分時賣力：定位中間的分時買力，準備驗證標題')
                    return point
        return None

    def title_tokens_match(self,tokens,name):
        observed='、'.join(str(t[1]) for t in tokens or [])[:120]
        if observed:self.last_title_text=observed
        # Ignore arrow/border OCR and unrelated toolbar text, but never accept a
        # different recognized ranking alongside the requested label.
        if any(find_menu_item(tokens,other) for other in RANKINGS if other!=name):return False
        return find_menu_item(tokens,name) is not None or (name=='累計買力' and cumulative_buy_suffix(tokens) is not None)

    def label_matches(self,name):
        im,_=self.c.image(self.combo)
        signature=None
        if hasattr(im,'tobytes'):
            import hashlib
            signature=(name,im.size,hashlib.sha256(im.tobytes()).digest())
            if signature==getattr(self,'verified_title_pixels',None):return True
            if signature in getattr(self,'verified_titles',set()):return True
        def accepted(tokens):
            if not self.title_tokens_match(tokens,name):return False
            if signature is not None:
                self.verified_title_pixels=signature
                if not hasattr(self,'verified_titles'):self.verified_titles=set()
                if len(self.verified_titles)>128:self.verified_titles.clear()
                self.verified_titles.add(signature)
            return True
        for reader in [self.ocr,self.ocr_retry]:
            if reader is None:continue
            self.c.check()
            tokens=reader(im)
            if accepted(tokens):return True
            # A clearly recognized different title needs no enlargement retries.
            if any(find_menu_item(tokens,other) for other in RANKINGS if other!=name):return False
        if hasattr(im,'crop'):
            from PIL import ImageOps
            # The combo arrow and cyan frame can merge into the four-character
            # label. Re-read the inner text strip with a quiet margin for OCR.
            w,h=im.size
            inner=im.crop((max(1,round(w*.02)),max(1,round(h*.08)),
                           max(2,round(w*.84)),max(2,round(h*.92))))
            padded=ImageOps.expand(inner,border=max(8,h//3),fill=inner.getpixel((0,0)))
            for tokens in self.enlarged_reads(padded,name):
                if accepted(tokens):return True
        for tokens in self.enlarged_reads(im,name):
            if accepted(tokens):return True
        return False

    def title_failure(self):
        return '切換後標題仍無法確認；預期：'+getattr(self,'requested_title','未知')+'；實際OCR：'+getattr(self,'last_title_text','無文字')+'；進度已保留，沒有記入該排行資料'

    def verify_label(self,name):
        # Fast first check, bounded backoff for a slow application. F8 remains active.
        for delay in (.15,.25,.4,.6,.8):
            self.c.pause(delay)
            if self.label_matches(name):return True
        return False

    def recalibrate_menu(self):
        """Recover only menu geometry after a failed round, never table columns."""
        from auto_detect import combo_frame,detect_menu
        from menu_numbers import NumberNavigator
        self.c.check()
        if self.numbers:self.numbers.show(None)
        image,_=self.c.image([0,0,1,1])
        readers=[reader for reader in (self.ocr,self.ocr_retry) if reader is not None]
        frame=None;errors=[];started=time.monotonic()
        w,h=image.size
        x,y,cw,ch=self.combo
        # Translate local OCR boxes back to full-image pixels for validation.
        pad=max(12,round(ch*h*.6))
        nearby=(max(0,int(x*w)-pad),max(0,int(y*h)-pad),
                min(w,int((x+cw)*w)+pad),min(h,int((y+ch)*h)+pad))
        areas=[nearby,(0,0,max(1,round(w*.60)),max(1,round(h*.50)))]
        for index,box in enumerate(areas):
            if frame is not None:break
            self.status('選單快速校正｜'+('辨識原下拉框附近' if index==0 else '擴大搜尋左上區域'))
            for reader in readers:
                self.c.check()
                tokens=reader(image.crop(box))
                shifted=[([[px+box[0],py+box[1]] for px,py in points],text,score)
                         for points,text,score in tokens or []]
                try:
                    frame=combo_frame(image,shifted);break
                except ValueError as exc:errors.append(str(exc))
        if frame is None:raise ValueError('排行下拉框重新定位失敗：'+'；'.join(errors))
        l,t,r,b=frame;w,h=image.size
        combo=validate_roi([max(0,l)/w,max(0,t)/h,(min(w,r)-max(0,l))/w,(min(h,b)-max(0,t))/h])
        def read_menu(source):
            failures=[]
            for reader in readers:
                self.c.check()
                try:return detect_menu(source,combo,reader)
                except ValueError as exc:failures.append(str(exc))
            raise ValueError('；'.join(failures))
        self.status(f'下拉框已定位｜{time.monotonic()-started:.1f}秒｜核對展開選單')
        try:menu=read_menu(image) # A previous failed search may leave the popup open.
        except ValueError:
            rect=absolute_roi(combo,self.c.rect())
            self.c.click((rect['left']+rect['width']//2,rect['top']+rect['height']//2))
            self.menu_is_open=True;self.c.pause(.4)
            opened,_=self.c.image([0,0,1,1]);menu=read_menu(opened)
        self.c.check()
        self.combo=combo;self.menu=menu;self.menu_is_open=True
        if self.experience:
            for key in ('ranks','numbers','number_routes'):self.experience.data[key]={}
            for key in ('menu_geometry','menu_wheel'):self.experience.data.pop(key,None)
            self.experience.save()
        if self.numbers:self.numbers=NumberNavigator(self,self.numbers.manual,self.numbers.show)
        self.verified_titles=set();self.verified_title_pixels=None
        self.status(f'排行選單已重新定位｜耗時{time.monotonic()-started:.1f}秒｜清除失效記憶，保留表頭與已掃描資料')
        return dict(combo=combo,menu=menu)

    def switch(self,name,menu_open=False):
        try:return self._switch(name,menu_open)
        finally:
            if self.numbers:self.numbers.show(None)

    def _switch(self,name,menu_open=False,allow_numbers=True):
        menu_open=menu_open or getattr(self,'menu_is_open',False)
        self.menu_is_open=menu_open
        if name not in RANKINGS:raise ValueError('未知排行名稱')
        self.requested_title=name;self.last_title_text='無文字'
        self.c.check()
        # A verified current label needs no mouse input. An already-open popup must
        # instead be selected directly so setup never guesses its toggle state.
        if not menu_open and self.label_matches(name):return
        if not menu_open:
            _,combo=self.c.image(self.combo)
            self.c.click((combo['left']+combo['width']//2,combo['top']+combo['height']//2))
            self.menu_is_open=True
            self.c.pause(.4)
        menu=absolute_roi(self.menu,self.c.rect())
        center=(menu['left']+menu['width']//2,menu['top']+menu['height']//2)
        self.c.scroll(center,120*60);self.c.pause(.35)
        if self.numbers and allow_numbers:
            point=self.numbers.locate(name,menu)
            if point is not None:
                self.c.click((menu['left']+point[0],menu['top']+point[1]));self.menu_is_open=False
                if self.verify_label(name):
                    self.numbers.verified(name)
                    self.status(f'數字定位完成：{name} 第 {self.numbers.number(name)} 項｜標題核對通過')
                    return
                self.numbers.rejected(name)
                self.status(f'第 {self.numbers.number(name)} 項不是「{name}」，本次停用該編號並改用文字搜尋；請核對手動編號')
                return self._switch(name,False,False)
            self.numbers.show(None)
            self.c.scroll(center,120*60);self.c.pause(.15)
        # Reproduce a learned scroll path, but OCR the target again before clicking.
        hint=self.experience.hint(name) if self.experience else None
        if hint is not None:
            self.status(f'經驗加速：正在驗證「{name}」的已學習位置')
            for _ in range(hint):
                self.c.scroll(center,-120);self.c.pause(.12)
            im,region=self.c.image(self.menu)
            cached=self.experience.position(name,self.menu_signature(im))
            point=cached or self.menu_point(im,self.ocr(im),name)
            if cached:self.status(f'位置快取命中：直接選取「{name}」，核對切換結果')
            if point is not None:
                self.c.click((region['left']+point[0],region['top']+point[1]))
                self.menu_is_open=False
                if self.verify_label(name):
                    self.experience.record(name,hint,point,self.menu_signature(im))
                    return
                self.experience.forget(name)
                self.status('已清除錯誤位置記憶，重新搜尋此排行')
                return self._switch(name,False,False)
            self.experience.forget(name)
            self.status('選單內容有變，恢復完整搜尋')
            self.c.scroll(center,120*60);self.c.pause(.35)
        previous=None;unchanged=0;previous_pixels=None;still=0
        for page in range(60):
            self.c.check();im,region=self.c.image(self.menu)
            pixels=self.menu_signature(im)
            if pixels is not None and pixels==previous_pixels:
                still+=1
                if still>=2:break
                self.c.scroll(center,-120);self.c.pause(.45)
                continue
            previous_pixels=pixels;still=0
            if self.numbers:self.numbers.observe(im,page)
            tokens=self.ocr(im)
            self.status(f'正在尋找「{name}」｜選單第{page+1}段')
            point=self.menu_point(im,tokens,name)
            if point:
                self.c.click((region['left']+point[0],region['top']+point[1]))
                self.menu_is_open=False
                if self.verify_label(name):
                    if self.experience:self.experience.record(name,page,point,self.menu_signature(im))
                    if self.numbers:self.numbers.text_verified(name,point)
                    return
                if self.experience:self.experience.forget(name)
                raise AutomationStopped(self.title_failure())
            fingerprint=tuple(normalized(str(t[1])) for t in tokens or [])
            unchanged=unchanged+1 if fingerprint and fingerprint==previous else 0
            if unchanged>=2:break
            previous=fingerprint
            # One wheel notch preserves overlapping rows on narrow native lists.
            self.c.scroll(center,-120);self.c.pause(.45)
        observed='、'.join(str(t[1]) for t in tokens or [])[:100]
        raise AutomationStopped('選單中找不到「'+name+'」；本次讀到：'+(observed or '無文字')+'；已停止')

