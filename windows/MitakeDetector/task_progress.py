"""Passive corner status panel; it never activates or accepts mouse input on Windows."""
import ctypes
import sys
import time
import re

def duration_text(seconds):
    seconds=max(0,int(seconds))
    return f'{seconds//3600:02d}:{seconds//60%60:02d}:{seconds%60:02d}'


def task_stage(text):
    text=str(text)
    if '準備自動定位' in text:return '設定1/4：尋找視窗與欄位'
    if '辨識排行下拉框' in text:return '設定1/4：辨識表頭'
    if '定位展開選單' in text:return '設定2/4：定位類目選單'
    if '驗證欄位' in text:return '設定3/4：驗證股票資料'
    if '自動設定完成' in text or '準備開始自動輪切' in text:return '設定4/4：啟動輪切掃描'
    if '回找' in text or '找回重疊' in text:return '自動回找重疊股票列'
    if '接續' in text or '前次' in text:return '接續斷點，核對已讀位置'
    if '核對股票表頭' in text:return '複查股票欄位位置'
    if '正在尋找' in text:return '搜尋選單類目'
    if '正在切換' in text:return '正在切換排行榜'
    if '已確認排行' in text:return '確認類目，準備擷取'
    if '複查' in text:return '複查股票資料'
    if '擷取股票' in text or '段｜' in text:return '逐段捲動與讀取股票'
    if '辨識中' in text:return '正在讀取股票資料'
    if '後繼續' in text:return '本次擷取完成，等待繼續'
    return '正在執行任務'


def status_bounds(rect,combo=None,menu=None):
    """Yellow reference strip: right of combo, within top 5% of target."""
    x,y,right,bottom=rect;w=right-x;h=bottom-y
    left=x+round(w*.078)+2
    if combo and len(combo)==4:left=max(left,x+round((combo[0]+combo[2])*w)+3)
    right=max(left+1,right-max(4,round(w*.008)))
    top=y+2;end=y+max(3,round(h*.049))
    return (min(left,right-1),top,right,max(top+1,end))


def status_top(bounds,panel_height):
    return bounds[1]


class TaskProgress:
    def __init__(self,root,tk,ttk,rect,message,combo=None,menu=None):
        self.root=root;self.tk=tk;self.started=time.monotonic();self.alive=True;self.after_id=None
        self.window=tk.Toplevel(root);self.window.withdraw();self.window.overrideredirect(True)
        self.window.configure(bg='#101a2b');self.window.attributes('-topmost',True)
        rect=status_bounds(rect,combo,menu)
        width=max(1,rect[2]-rect[0]);height=max(1,rect[3]-rect[1])
        left=rect[0]
        self.allowed_bounds=rect
        self.window.geometry(f'{width}x{height}{left:+d}{rect[1]:+d}')
        pixels=max(5,min(13,(height-16)//2))
        label_pixels=max(5,min(10,(height-16)//2))
        self.title=tk.StringVar();self.detail=tk.StringVar(value=message);self.stage=task_stage(message)
        table_width=max(1,round(width*.56));text_width=max(1,width-table_width-12)
        self.title_label=tk.Label(self.window,textvariable=self.title,bg='#101a2b',fg='#68f5d1',font=('Microsoft JhengHei',-pixels,'bold'),anchor='w')
        self.title_label.place(x=4,y=1,width=text_width,height=max(1,(height-4)//2))
        self.detail_label=tk.Label(self.window,textvariable=self.detail,bg='#101a2b',fg='#edf2ff',font=('Microsoft JhengHei',-label_pixels),anchor='w')
        self.detail_label.place(x=4,y=max(1,(height-4)//2),width=text_width,height=max(1,height//2-2))
        self.rate=tk.StringVar(value='讀取速率：等待首段')
        self.rate_label=tk.Label(self.window,textvariable=self.rate)
        self.counts=tk.StringVar(value='已掃描 0 股／總股數待確認｜累計完成 0 遍')
        self.count_label=tk.Label(self.window,textvariable=self.counts)
        self.metrics=tk.Frame(self.window,bg='#30415d')
        self.metrics.place(x=width-table_width-3,y=1,width=table_width,height=max(1,height-5))
        self.values={};self.metric_cells=[]
        for i,(key,label,value) in enumerate([
            ('elapsed','掃描總時長','00:00:00'),('scanned','已掃描股數','0 股'),('total','排行總股數','待確認'),
            ('passes','累計完成遍數','0 遍'),('rate','讀取速率','等待首段'),('freshness','最近成功讀取','尚未讀取')]):
            cell=tk.Frame(self.metrics,bg='#15243a',highlightbackground='#30415d',highlightthickness=1)
            self.metric_cells.append(cell)
            cell.grid(row=0,column=i,sticky='nsew')
            self.metrics.columnconfigure(i,weight=1,uniform='metric')
            self.metrics.rowconfigure(0,weight=1)
            if height>=34:
                tk.Label(cell,text=label,bg='#15243a',fg='#9baecb',font=('Microsoft JhengHei',-label_pixels),pady=0,bd=0).pack()
            var=tk.StringVar(value=value);self.values[key]=var
            tk.Label(cell,textvariable=var,bg='#15243a',fg='#ffd36a',font=('Microsoft JhengHei',-pixels,'bold'),pady=0,bd=0).pack()
        self.last_success=None
        self.counts.trace_add('write',self.refresh_counts)
        self.rate.trace_add('write',self.refresh_rate)
        self.bar=ttk.Progressbar(self.window,mode='indeterminate',length=370)
        self.bar.place(x=2,y=max(0,height-3),width=max(1,width-4),height=min(2,height))
        self.bar.start(90)
        self.window.update_idletasks()
        self.native_hwnd=None
        if sys.platform=='win32':
            from ctypes import wintypes as W
            u=ctypes.windll.user32
            u.GetAncestor.argtypes=[W.HWND,W.UINT];u.GetAncestor.restype=W.HWND
            hwnd=u.GetAncestor(self.window.winfo_id(),2);self.native_hwnd=hwnd
            get_style=u.GetWindowLongPtrW if ctypes.sizeof(ctypes.c_void_p)==8 else u.GetWindowLongW
            set_style=u.SetWindowLongPtrW if ctypes.sizeof(ctypes.c_void_p)==8 else u.SetWindowLongW
            get_style.argtypes=[W.HWND,ctypes.c_int];get_style.restype=ctypes.c_ssize_t
            set_style.argtypes=[W.HWND,ctypes.c_int,ctypes.c_ssize_t];set_style.restype=ctypes.c_ssize_t
            # WS_EX_NOACTIVATE | TRANSPARENT | LAYERED | TOOLWINDOW.
            set_style(hwnd,-20,get_style(hwnd,-20)|0x08000000|0x20|0x80000|0x80)
            u.SetLayeredWindowAttributes.argtypes=[W.HWND,W.DWORD,ctypes.c_ubyte,W.DWORD]
            u.SetLayeredWindowAttributes(hwnd,0,245,2)
            # Keep the wider overlay out of OCR screenshots of the target window.
            u.SetWindowDisplayAffinity.argtypes=[W.HWND,W.DWORD]
            u.SetWindowDisplayAffinity.restype=W.BOOL
            u.SetWindowDisplayAffinity(hwnd,0x11)
            self.window.deiconify()
            u.ShowWindow.argtypes=[W.HWND,ctypes.c_int];u.ShowWindow(hwnd,4)
            u.SetWindowPos.argtypes=[W.HWND,W.HWND,ctypes.c_int,ctypes.c_int,ctypes.c_int,ctypes.c_int,W.UINT]
            u.SetWindowPos(hwnd,W.HWND(-1),0,0,0,0,0x0010|0x0001|0x0002|0x0040)
        else:self.window.deiconify()
        self.tick()

    def refresh_counts(self,*args):
        text=self.counts.get()
        for key,pattern,suffix in [('scanned',r'已掃描 (\d+) 股',' 股'),('total',r'總股數 ([^｜]+)',''),('passes',r'累計完成 (\d+) 遍',' 遍')]:
            match=re.search(pattern,text)
            if match:
                value=match.group(1).strip().replace('待完整掃描確認','待確認').replace('（上輪參考）',' (上輪)')
                self.values[key].set(value+suffix)

    def refresh_rate(self,*args):
        text=self.rate.get().split('｜本次讀取')[0]
        match=re.search(r'([\d.]+) 檔／秒',text)
        self.values['rate'].set(match.group(1)+' 股/秒' if match else '等待首段')

    def mark_success(self):
        self.last_success=time.monotonic()

    def tick(self):
        if not self.alive:return
        seconds=int(time.monotonic()-self.started)
        pulse=['●','◉','●','○'][seconds%4]
        self.title.set(f'{pulse} 機器人工作中｜{self.stage}｜F8 停止')
        self.values['elapsed'].set(duration_text(seconds))
        if self.last_success is not None:
            age=int(time.monotonic()-self.last_success)
            self.values['freshness'].set(f'{age} 秒前')
        self.after_id=self.root.after(500,self.tick)

    def update(self,text,completed=None,total=None):
        if not self.alive:return
        self.stage=task_stage(text)
        self.detail.set(str(text))
        seconds=int(time.monotonic()-self.started)
        self.title.set(f'● 機器人工作中｜{self.stage}｜F8 停止')
        if total is not None and total>0:
            self.bar.stop();self.bar.configure(mode='determinate',maximum=total,value=max(0,min(total,completed or 0)))
        else:
            if self.bar.cget('mode')!='indeterminate':
                self.bar.configure(mode='indeterminate');self.bar.start(90)

    def close(self):
        if not self.alive:return
        self.alive=False
        if self.after_id is not None:self.root.after_cancel(self.after_id)
        self.bar.stop();self.window.destroy()


class CandidateNotice(TaskProgress):
    """Topmost, non-activating, click-through notice; scanning keeps its focus."""
    def tick(self):pass

    def __init__(self,root,tk,ttk,text):
        w=root.winfo_screenwidth();h=root.winfo_screenheight()
        super().__init__(root,tk,ttk,(0,0,w,h),text)
        width=min(680,max(280,w-32));height=min(220,h-80)
        self.window.geometry(f'{width}x{height}+{max(0,w-width-16)}+{max(0,h-height-60)}')
        self.title.set('符合條件的轉強候選｜掃描持續進行')
        self.title_label.configure(fg='#ffd36a')
        self.title_label.place(x=12,y=8,width=width-24,height=24)
        self.metrics.place_forget();self.count_label.place_forget();self.rate_label.place_forget();self.bar.stop();self.bar.place_forget()
        self.detail_label.configure(wraplength=width-24,justify='left',anchor='nw',font=('Microsoft JhengHei',10))
        self.detail_label.place(x=12,y=40,width=width-24,height=height-48)
        self.after_id=root.after(30000,self.close)


def fresh_candidate_alerts(alerts,last,cooldown):
    """Share cooldown across rankings; only genuine engine hits enter here."""
    accepted=[]
    for q,reason in alerts:
        if q.ts-last.get(q.code,-1e20)<cooldown:continue
        last[q.code]=q.ts;accepted.append((q,reason))
    return accepted
