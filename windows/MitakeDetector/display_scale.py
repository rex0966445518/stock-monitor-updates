"""Main-window display scaling; never resizes automation target or overlays."""
import json
from pathlib import Path

SCALES=(100,125,150,175,200,250,300)


def fit_window(window,work,ratio):
    x,y,w,h=window;l,t,r,b=work
    nw=min(max(240,round(w*ratio)),max(240,r-l-16))
    nh=min(max(200,round(h*ratio)),max(200,b-t-16))
    return (max(l,min(x,r-nw)),max(t,min(y,b-nh)),nw,nh)


def current_work_area(root):
    import sys
    if sys.platform=='win32':
        import ctypes
        from ctypes import wintypes as W
        class Info(ctypes.Structure):
            _fields_=[('size',W.DWORD),('monitor',W.RECT),('work',W.RECT),('flags',W.DWORD)]
        u=ctypes.windll.user32
        u.MonitorFromWindow.argtypes=[W.HWND,W.DWORD];u.MonitorFromWindow.restype=W.HANDLE
        u.GetMonitorInfoW.argtypes=[W.HANDLE,ctypes.POINTER(Info)]
        info=Info();info.size=ctypes.sizeof(info)
        monitor=u.MonitorFromWindow(root.winfo_id(),2)
        if u.GetMonitorInfoW(monitor,ctypes.byref(info)):
            return info.work.left,info.work.top,info.work.right,info.work.bottom
    return 0,0,root.winfo_screenwidth(),root.winfo_screenheight()


class DisplayScale:
    def __init__(self,app,path):
        self.app=app;self.root=app.root;self.path=Path(path);self.percent=100
        self.fonts={};self.padding={}
        self.widths={c:int(app.tree.column(c,'width')) for c in app.tree['columns']}
        default=150 if self.root.winfo_screenheight()>=1440 else 100
        try:value=json.loads(self.path.read_text('utf-8')).get('scale',default)
        except (OSError,ValueError,AttributeError):value=default
        self.initial=value if type(value) is int and value in SCALES else default

    def widgets(self,parent):
        for widget in parent.winfo_children():
            if widget.winfo_class()=='Toplevel':continue
            yield widget
            yield from self.widgets(widget)

    def apply(self,value,save=True,resize=True):
        import tkinter as tk
        import tkinter.font as font
        from tkinter import ttk
        value=int(str(value).replace('%',''))
        if value not in SCALES:raise ValueError('介面縮放需為100%～300%的選項')
        factor=value/100;old=self.percent
        self.root.update_idletasks()
        window=(self.root.winfo_x(),self.root.winfo_y(),self.root.winfo_width(),self.root.winfo_height())
        for widget in self.widgets(self.root):
            try:
                if widget not in self.fonts:
                    spec=widget.cget('font')
                    if spec:self.fonts[widget]=font.Font(root=self.root,font=spec).actual()
                if widget in self.fonts:
                    spec=dict(self.fonts[widget]);size=spec['size'];spec['size']=max(5,round(abs(size)*factor))*(1 if size>=0 else -1)
                    widget.configure(font=tuple([spec['family'],spec['size']]+[s for s in ('bold' if spec['weight']=='bold' else '', 'italic' if spec['slant']=='italic' else '') if s]))
            except tk.TclError:pass
            try:
                if widget not in self.padding:
                    info=widget.pack_info()
                    base={}
                    for key in ('padx','pady'):
                        raw=info[key]
                        numbers=tuple(int(v) for v in (raw if isinstance(raw,(tuple,list)) else self.root.tk.splitlist(str(raw))))
                        base[key]=numbers
                    self.padding[widget]=base
                widget.pack_configure(**{k:tuple(round(v*factor) for v in values) for k,values in self.padding[widget].items()})
            except (tk.TclError,ValueError,KeyError):pass
        style=ttk.Style(self.root)
        style.configure('.',font=('Microsoft JhengHei',round(10*factor)))
        style.configure('TButton',padding=round(8*factor))
        style.configure('TEntry',padding=round(5*factor))
        style.configure('Treeview',font=('Microsoft JhengHei',round(10*factor)),rowheight=round(30*factor))
        style.configure('Treeview.Heading',font=('Microsoft JhengHei',round(10*factor),'bold'))
        self.app.tree.tag_configure('hit',font=('Microsoft JhengHei',round(10*factor),'bold'))
        for column,width in self.widths.items():self.app.tree.column(column,width=round(width*factor),minwidth=round(min(width,60)*factor))
        self.app.log.configure(height=6 if value>100 else 9)
        self.percent=value
        if hasattr(self.app,'display_scale_var'):self.app.display_scale_var.set(f'{value}%')
        if resize and self.root.state() not in ('zoomed','iconic','withdrawn'):
            work=current_work_area(self.root);x,y,w,h=fit_window(window,work,value/old)
            self.root.minsize(min(1100,work[2]-work[0]-16),min(800,work[3]-work[1]-16))
            self.root.geometry(f'{w}x{h}')
            import sys
            if sys.platform=='win32':
                import ctypes
                from ctypes import wintypes as W
                u=ctypes.windll.user32
                u.GetAncestor.argtypes=[W.HWND,W.UINT];u.GetAncestor.restype=W.HWND
                u.SetWindowPos.argtypes=[W.HWND,W.HWND,ctypes.c_int,ctypes.c_int,ctypes.c_int,ctypes.c_int,W.UINT]
                u.SetWindowPos(u.GetAncestor(self.root.winfo_id(),2),None,x,y,w,h,0x0010|0x0004)
            else:self.root.geometry(f'{w}x{h}{x:+d}{y:+d}')
        if save:
            try:
                self.path.parent.mkdir(parents=True,exist_ok=True)
                temporary=self.path.with_suffix('.tmp');temporary.write_text(json.dumps({'scale':value}),'utf-8');temporary.replace(self.path)
            except OSError as error:self.app.say('介面縮放已套用，設定無法儲存：'+str(error))
        return value
