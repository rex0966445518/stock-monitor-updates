"""Four passive neon borders outside the captured area; no pixels cover data."""
import ctypes
import sys


def border_rects(region,thickness=3):
    x,y,w,h=(int(region[k]) for k in ('left','top','width','height'))
    if w<=0 or h<=0:raise ValueError('掃描區域無效')
    t=thickness
    return [(x-t,y-t,w+2*t,t),(x-t,y+h,w+2*t,t),(x-t,y,t,h),(x+w,y,t,h)]


class ScanOutline:
    def __init__(self,root,tk):
        self.root=root;self.windows=[];self.handles=[];self.region=None;self.visible=False
        for _ in range(4):
            win=tk.Toplevel(root);win.withdraw();win.overrideredirect(True)
            win.configure(bg='#39ff14');win.attributes('-topmost',True)
            win.update_idletasks();self.windows.append(win)
            if sys.platform=='win32':
                from ctypes import wintypes as W
                u=ctypes.windll.user32
                u.GetAncestor.argtypes=[W.HWND,W.UINT];u.GetAncestor.restype=W.HWND
                hwnd=u.GetAncestor(win.winfo_id(),2);self.handles.append(hwnd)
                get=u.GetWindowLongPtrW if ctypes.sizeof(ctypes.c_void_p)==8 else u.GetWindowLongW
                put=u.SetWindowLongPtrW if ctypes.sizeof(ctypes.c_void_p)==8 else u.SetWindowLongW
                get.argtypes=[W.HWND,ctypes.c_int];get.restype=ctypes.c_ssize_t
                put.argtypes=[W.HWND,ctypes.c_int,ctypes.c_ssize_t];put.restype=ctypes.c_ssize_t
                put(hwnd,-20,get(hwnd,-20)|0x08000000|0x20|0x80000|0x80)
                u.SetLayeredWindowAttributes.argtypes=[W.HWND,W.DWORD,ctypes.c_ubyte,W.DWORD]
                u.SetLayeredWindowAttributes(hwnd,0,255,2)

    def show(self,region):
        self.region=dict(region)
        for index,(win,(x,y,w,h)) in enumerate(zip(self.windows,border_rects(region))):
            win.geometry(f'{w}x{h}{x:+d}{y:+d}')
            win.update_idletasks();win.deiconify()
            if sys.platform=='win32':
                from ctypes import wintypes as W
                u=ctypes.windll.user32
                u.ShowWindow.argtypes=[W.HWND,ctypes.c_int];u.ShowWindow(self.handles[index],4)
                u.SetWindowPos.argtypes=[W.HWND,W.HWND,ctypes.c_int,ctypes.c_int,ctypes.c_int,ctypes.c_int,W.UINT]
                u.SetWindowPos(self.handles[index],W.HWND(-1),x,y,w,h,0x0010|0x0040)
        self.visible=True

    def hide(self):
        for win in self.windows:win.withdraw()
        self.visible=False

    def close(self):
        for win in self.windows:win.destroy()
        self.windows=[];self.visible=False
