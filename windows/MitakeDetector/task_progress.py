"""Passive corner status panel; it never activates or accepts mouse input on Windows."""
import ctypes
import sys
import time


def task_stage(text):
    text=str(text)
    if '準備自動定位' in text:return '設定1/4：尋找視窗與欄位'
    if '辨識排行下拉框' in text:return '設定1/4：辨識表頭'
    if '定位展開選單' in text:return '設定2/4：定位類目選單'
    if '驗證欄位' in text:return '設定3/4：驗證股票資料'
    if '自動設定完成' in text or '準備開始自動輪切' in text:return '設定4/4：啟動輪切掃描'
    if '核對股票表頭' in text:return '複查股票欄位位置'
    if '正在尋找' in text:return '搜尋選單類目'
    if '正在切換' in text:return '正在切換排行榜'
    if '已確認排行' in text:return '確認類目，準備擷取'
    if '複查' in text:return '複查股票資料'
    if '擷取股票' in text or '段｜' in text:return '逐段捲動與讀取股票'
    if '辨識中' in text:return '正在讀取股票資料'
    if '後繼續' in text:return '本次擷取完成，等待繼續'
    return '正在執行任務'


class TaskProgress:
    def __init__(self,root,tk,ttk,rect,message):
        self.root=root;self.tk=tk;self.started=time.monotonic();self.alive=True;self.after_id=None
        self.window=tk.Toplevel(root);self.window.withdraw();self.window.overrideredirect(True)
        self.window.configure(bg='#101a2b');self.window.attributes('-topmost',True)
        width=min(900,max(280,rect[2]-rect[0]-240))
        left=max(rect[0]+8,min(rect[2]-width-8,rect[0]+(rect[2]-rect[0]-width)//2))
        self.window.geometry(f'{width}x48{left:+d}{rect[1]+2:+d}')
        self.title=tk.StringVar();self.detail=tk.StringVar(value=message);self.stage=task_stage(message)
        tk.Label(self.window,textvariable=self.title,bg='#101a2b',fg='#68f5d1',font=('Microsoft JhengHei',10,'bold'),anchor='w').place(x=8,y=0,width=width-16,height=20)
        tk.Label(self.window,textvariable=self.detail,bg='#101a2b',fg='#edf2ff',font=('Microsoft JhengHei',8),anchor='w').place(x=8,y=20,width=width-16,height=20)
        self.bar=ttk.Progressbar(self.window,mode='indeterminate',length=370);self.bar.place(x=8,y=42,width=width-16,height=4)
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
            self.window.deiconify()
            u.ShowWindow.argtypes=[W.HWND,ctypes.c_int];u.ShowWindow(hwnd,4)
            u.SetWindowPos.argtypes=[W.HWND,W.HWND,ctypes.c_int,ctypes.c_int,ctypes.c_int,ctypes.c_int,W.UINT]
            u.SetWindowPos(hwnd,W.HWND(-1),0,0,0,0,0x0010|0x0001|0x0002|0x0040)
        else:self.window.deiconify()
        self.tick()

    def tick(self):
        if not self.alive:return
        seconds=int(time.monotonic()-self.started)
        pulse=['●','◉','●','○'][seconds%4]
        self.title.set(f'{pulse} 機器人工作中｜{self.stage}｜{seconds//60:02d}:{seconds%60:02d}｜F8 停止')
        self.after_id=self.root.after(500,self.tick)

    def update(self,text,completed=None,total=None):
        if not self.alive:return
        self.stage=task_stage(text)
        self.detail.set(str(text)[:120])
        seconds=int(time.monotonic()-self.started)
        self.title.set(f'● 機器人工作中｜{self.stage}｜{seconds//60:02d}:{seconds%60:02d}｜F8 停止')
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
