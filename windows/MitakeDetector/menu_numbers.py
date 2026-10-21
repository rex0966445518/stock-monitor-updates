"""1-based menu navigation: native indices, otherwise calibrated pixel row tracking."""
import ctypes
from ctypes import wintypes as W


def validate_numbers(values):
    from autoswitch import RANKINGS
    if not isinstance(values,dict):raise ValueError('排行編號格式錯誤')
    result={}
    for name,value in values.items():
        if name not in RANKINGS:continue
        if value in ('',None):continue
        if isinstance(value,bool) or str(value).strip()!=str(int(value)) or not 1<=int(value)<=500:
            raise ValueError(name+'：請填1～500的整數，留空為自動')
        result[name]=int(value)
    if len(set(result.values()))!=len(result):raise ValueError('不同排行不可填入相同編號')
    return result


class NativeMenu:
    """Only standard ListBox controls; bounded system messages, never keys/orders."""
    def __init__(self,c,region):
        self.c=c;self.region=region;u=c.u
        point=W.POINT(region['left']+region['width']//2,region['top']+region['height']//2)
        self.hwnd=u.WindowFromPoint(point)
        if not c.belongs(self.hwnd):raise ValueError('不是目標清單')
        u.GetClassNameW.argtypes=[W.HWND,W.LPWSTR,ctypes.c_int]
        name=ctypes.create_unicode_buffer(128);u.GetClassNameW(self.hwnd,name,128)
        if name.value.lower() not in ('combolbox','listbox'):raise ValueError('自繪選單')
        u.SendMessageTimeoutW.argtypes=[W.HWND,W.UINT,W.WPARAM,W.LPARAM,W.UINT,W.UINT,ctypes.POINTER(ctypes.c_size_t)]
        u.SendMessageTimeoutW.restype=W.LPARAM
        u.ClientToScreen.argtypes=[W.HWND,ctypes.POINTER(W.POINT)]
        u.IsWindowVisible.argtypes=[W.HWND]
        self.count=self.send(0x018B)
        if not 1<=self.count<=500:raise ValueError('清單筆數無效')
    def send(self,message,wparam=0,lparam=0):
        self.c.check()
        if not self.c.u.IsWindowVisible(self.hwnd):raise ValueError('選單已收起')
        result=ctypes.c_size_t()
        if not self.c.u.SendMessageTimeoutW(self.hwnd,message,wparam,lparam,2,200,ctypes.byref(result)):
            raise ValueError('清單回應逾時')
        return ctypes.c_ssize_t(result.value).value
    def rect(self,index):
        r=W.RECT()
        if self.send(0x0198,index,ctypes.addressof(r))<0:raise ValueError('項目不可見')
        origin=W.POINT(0,0)
        if not self.c.u.ClientToScreen(self.hwnd,ctypes.byref(origin)):raise ValueError('清單位置無效')
        return origin.x+r.left,origin.y+r.top,origin.x+r.right,origin.y+r.bottom
    def rows(self):
        first=self.send(0x018E);result=[];region=self.region
        if not 0<=first<self.count:raise ValueError('清單起點無效')
        for i in range(first,min(self.count,first+100)):
            l,t,r,b=self.rect(i)
            if t>=region['top']+region['height']:break
            if t>=region['top'] and b<=region['top']+region['height']:result.append((i+1,(t+b)/2-region['top']))
        return result
    def point(self,number):
        if not 1<=number<=self.count:return None
        if self.send(0x0197,number-1)<0:return None
        self.c.pause(.08)
        l,t,r,b=self.rect(number-1);region=self.region
        if not (region['top']<=t<b<=region['top']+region['height']):return None
        x=max(l,region['left'])+min(r-l,region['width'])*.45
        if not region['left']<=x<region['left']+region['width']:return None
        return x-region['left'],(t+b)/2-region['top']


def features(image,centers,pitch):
    """Horizontal edge masks avoid selected-row background/highlight changes."""
    from PIL import Image,ImageChops
    result=[]
    for y in centers:
        crop=image.crop((int(image.width*.08),round(y-pitch*.36),int(image.width*.85),round(y+pitch*.36))).convert('L')
        crop=crop.resize((97,20),Image.Resampling.BILINEAR)
        edge=ImageChops.difference(crop.crop((1,0,97,20)),crop.crop((0,0,96,20)))
        result.append(bytes(1 if v>20 else 0 for v in edge.tobytes()))
    return result


def distance(a,b):return sum(x!=y for x,y in zip(a,b))/len(a)


class PixelMenu:
    def __init__(self,image,tokens):
        from autoswitch import find_menu_item,RANKINGS
        first,second,third=(find_menu_item(tokens,name) for name in RANKINGS[:3])
        if not first or not second or not third:raise ValueError('第一頁前三列尚未校準')
        self.pitch=second[1]-first[1];self.first=first[1]
        if not 8<=self.pitch<=100 or abs(third[1]-second[1]-self.pitch)>self.pitch*.15:
            raise ValueError('列距不一致')
        if not self.pitch*.35<=self.first<=self.pitch*1.5:raise ValueError('清單第一列不完整')
        self.size=image.size;self.centers=[];y=self.first
        while y+self.pitch*.48<image.height:
            self.centers.append(y);y+=self.pitch
        if len(self.centers)<3:raise ValueError('可見列不足')
        self.initial=features(image,self.centers,self.pitch)
        if any(sum(row)<8 for row in self.initial[:3]):raise ValueError('列影像不足')
        self.previous=self.initial;self.offset=0
    def reset(self,image):
        if image.size!=self.size:return False
        current=features(image,self.centers,self.pitch)
        if max(distance(a,b) for a,b in zip(self.initial[:3],current[:3]))>.045:return False
        self.previous=current;self.offset=0;return True
    def update(self,image):
        if image.size!=self.size:return False
        current=features(image,self.centers,self.pitch);n=len(current);matches=[]
        for shift in range(-(n-2),n-1):
            if self.offset+shift<0:continue
            pairs=[(self.previous[j+shift],current[j]) for j in range(n) if 0<=j+shift<n]
            # At least two non-identical labels must agree; blank rows are not evidence.
            pairs=[(a,b) for a,b in pairs if sum(a)>=8 and sum(b)>=8]
            if len(pairs)<2 or len({a for a,b in pairs})<2:continue
            errors=sorted(distance(a,b) for a,b in pairs)
            score=sum(errors)/len(errors)
            if score<=.035:matches.append((score,shift))
        matches.sort()
        if not matches or len(matches)>1 and matches[1][0]-matches[0][0]<.008:return False
        self.offset+=matches[0][1];self.previous=current
        return True
    def rows(self):return [(self.offset+i+1,y) for i,y in enumerate(self.centers) if sum(self.previous[i])>=8]
    def point(self,number):
        i=number-self.offset-1
        return (self.size[0]*.45,self.centers[i]) if 0<=i<len(self.centers) and sum(self.previous[i])>=8 else None


class NumberNavigator:
    def __init__(self,switcher,manual=None,show=None):
        self.s=switcher;self.manual=validate_numbers(manual or {});self.show=show or (lambda value:None)
        self.geometry=None;self.disabled=set()
    def number(self,name):
        from autoswitch import RANKINGS
        learned=self.s.experience.number(name) if self.s.experience else None
        return self.manual.get(name) or learned or RANKINGS.index(name)+1
    def display(self,region,rows):self.show(dict(region=region,rows=rows))
    def locate(self,name,region):
        if name in self.disabled:return None
        number=self.number(name);s=self.s
        s.status(f'數字定位：{name} → 第 {number} 項（選取後核對標題）')
        try:
            native=NativeMenu(s.c,region)
            point=native.point(number);self.display(region,native.rows())
            return point
        except (AttributeError,ValueError):pass
        image,_=s.c.image(s.menu)
        if not hasattr(image,'size'):return None
        if self.geometry is None or not self.geometry.reset(image):
            try:self.geometry=PixelMenu(image,s.ocr(image))
            except ValueError:
                s.status('暫時無法確認編號列距，改用文字搜尋；可在排行編號設定中校正')
                return None
        geometry=self.geometry;unchanged=0
        for page in range(60):
            self.display(region,geometry.rows());point=geometry.point(number)
            if point:return point
            old=geometry.offset
            s.c.scroll((region['left']+region['width']//2,region['top']+region['height']//2),-120)
            s.c.pause(.12);image,_=s.c.image(s.menu)
            if not geometry.update(image):
                s.status('捲動編號無法可靠銜接，退回文字核對');self.show(None);return None
            unchanged=unchanged+1 if geometry.offset==old else 0
            if unchanged>=2:return None
        return None
    def observe(self,image,page):
        self.text_tracking=False;self.native_rows=[]
        try:
            from autoswitch import absolute_roi
            self.native_rows=NativeMenu(self.s.c,absolute_roi(self.s.menu,self.s.c.rect())).rows()
        except (ValueError,AttributeError):pass
        if self.geometry is not None and hasattr(image,'size'):
            self.text_tracking=self.geometry.reset(image) if page==0 else self.geometry.update(image)
    def text_verified(self,name,point):
        if not self.s.experience:return
        if getattr(self,'native_rows',None):
            number,y=min(self.native_rows,key=lambda row:abs(row[1]-point[1]))
            if abs(y-point[1])<8:self.s.experience.record_number(name,number)
            return
        if not getattr(self,'text_tracking',False):return
        rows=self.geometry.rows()
        if rows:
            number,y=min(rows,key=lambda row:abs(row[1]-point[1]))
            if abs(y-point[1])<self.geometry.pitch*.25:self.s.experience.record_number(name,number)
    def verified(self,name):
        if self.s.experience:self.s.experience.record_number(name,self.number(name))
    def rejected(self,name):
        self.disabled.add(name)
        if self.s.experience:self.s.experience.forget_number(name)
        self.show(None)


def preview_numbers(c,menu_roi,combo,region,native,geometry,show,clock=None,seconds=120):
    """Walk overlapping views to the bottom; a timeout is never completion."""
    import time
    from autoswitch import RANKINGS
    clock=clock or time.monotonic
    deadline=clock()+seconds;seen=set();previous=None;unchanged=0
    expected=native.count if native else len(RANKINGS)
    center=(region['left']+region['width']//2,region['top']+region['height']//2)
    for page in range(160):
        c.check()
        if native:rows=native.rows()
        else:
            image,_=c.image(menu_roi)
            if not geometry.update(image):
                raise ValueError(f'預覽未完成：已預覽 {len(seen)} 項；選單已關閉或捲動無法銜接，請重新預覽')
            rows=geometry.rows()
        numbers=tuple(number for number,_ in rows)
        if not numbers:raise ValueError(f'預覽未完成：已預覽 {len(seen)} 項；目前沒有可確認的選單列')
        seen.update(numbers)
        unchanged=unchanged+1 if numbers==previous else 0
        previous=numbers
        label=f'已預覽 {len(seen)}/{expected} 項'
        show(dict(region=region,rows=rows,preview_label=label))
        if unchanged>=3:
            if seen!=set(range(1,max(seen)+1)) or len(seen)!=expected:
                raise ValueError(f'預覽未完成：已預覽 {len(seen)}/{expected} 項，捲動沒有進展；請檢查選單範圍或項目數，未將缺漏項目標示完成')
            c.click((combo['left']+combo['width']//2,combo['top']+combo['height']//2))
            return len(seen)
        if clock()>=deadline:
            raise ValueError(f'預覽逾時：已預覽 {len(seen)}/{expected} 項，尚未確認到底；F8可重新啟動掃描')
        c.pause(1)
        c.scroll(center,-120);c.pause(.2)
    raise ValueError(f'預覽未完成：已預覽 {len(seen)}/{expected} 項，已達捲動次數上限')


class NumberOverlay:
    """Side ruler; never covers menu pixels or accepts input/focus."""
    def __init__(self,root):
        import tkinter as tk
        self.window=tk.Toplevel(root);self.window.withdraw();self.window.overrideredirect(True)
        self.canvas=tk.Canvas(self.window,width=42,highlightthickness=0,bg='#102638');self.canvas.pack(fill='both',expand=True)
    def update(self,payload):
        if not payload:self.window.withdraw();return
        region=payload['region'];preview=payload.get('preview_label')
        width=118 if preview is not None else 42;header=56 if preview is not None else 0
        x=region['left']-width-2;y=region['top']-header;height=region['height']+header
        # Preserve negative monitor coordinates; desktop geometry is not primary-screen-only.
        self.window.geometry(f'{width}x{height}{x:+d}{y:+d}');self.canvas.delete('all')
        if preview is not None:
            self.canvas.create_text(width//2,28,text=f'自動完整預覽\n{preview}\nF8 結束',fill='#ffe08a',font=('Segoe UI',9,'bold'))
        for number,center in payload['rows']:
            self.canvas.create_text(width-6,center+header,text=str(number),anchor='e',fill='#68f5d1',font=('Segoe UI',10,'bold'))
        from task_progress import TaskProgress
        handle=TaskProgress.show_passive(self,self.window,True)
        if handle:
            u=ctypes.windll.user32
            if x<u.GetSystemMetrics(76):x=region['left']+region['width']+2
            u.SetWindowPos(handle,W.HWND(-1),x,y,width,height,0x0010|0x0040)
    def close(self):self.window.destroy()
