"""Temporary cursor hiding with raw-mouse reveal and an independent restore guard.
Win32 contracts: SetSystemCursor consumes each replacement; originals are copied.
Raw input is mouse-only, INPUTSINK without NOLEGACY: no input is blocked.
"""
import ctypes
import os
import queue
import subprocess
import sys
import threading
from pathlib import Path

CURSOR_IDS=(32512,32513,32514,32515,32516,32642,32643,32644,32645,32646,32648,32649,32650,32651)


def physical_motion(device,dx,dy):
    # Synthetic messages without a hardware device and button-only events do not reveal.
    return bool(device) and bool(dx or dy)


class CursorIdleState:
    def __init__(self):self.hidden=True;self.last_motion=None
    def motion(self,now):
        self.last_motion=now
        if self.hidden:self.hidden=False;return 'show'
    def tick(self,now):
        if not self.hidden and self.last_motion is not None and now-self.last_motion>=2:
            self.hidden=True;return 'hide'


class CursorVisibility:
    def __init__(self):self.process=None;self.error='';self.handles=None
    def start(self):
        if sys.platform!='win32':return False
        from ctypes import wintypes as W
        from updater import installer_environment
        import time,uuid
        k=ctypes.WinDLL('kernel32',use_last_error=True)
        k.CreateEventW.argtypes=[ctypes.c_void_p,W.BOOL,W.BOOL,W.LPCWSTR];k.CreateEventW.restype=W.HANDLE
        k.WaitForSingleObject.argtypes=[W.HANDLE,W.DWORD];k.SetEvent.argtypes=[W.HANDLE];k.CloseHandle.argtypes=[W.HANDLE]
        name='Local\\MitakeCursor-'+uuid.uuid4().hex
        stop=k.CreateEventW(None,True,False,name+'-stop');ready=k.CreateEventW(None,True,False,name+'-ready')
        self.handles=(k,stop,ready)
        args=[sys.executable]
        if not getattr(sys,'frozen',False):args.append(str(Path(__file__).with_name('MitakeDetector.py')))
        args+=['--cursor-guard',str(os.getpid()),name]
        try:
            if not stop or not ready:raise OSError('無法建立游標保護事件')
            self.process=subprocess.Popen(args,stdin=subprocess.DEVNULL,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,
                env=installer_environment(),creationflags=0x08000000)
            deadline=time.monotonic()+4
            while time.monotonic()<deadline:
                if k.WaitForSingleObject(ready,50)==0:return True
                if self.process.poll() is not None:raise RuntimeError('游標保護程序無法初始化')
            raise RuntimeError('游標保護程序逾時')
        except (OSError,RuntimeError) as exc:
            self.error=str(exc);self.close();return False
    def close(self):
        handles=self.handles;self.handles=None
        if handles:
            k,stop,ready=handles
            if stop:k.SetEvent(stop);k.CloseHandle(stop)
            if ready:k.CloseHandle(ready)
        # Finish restoring before another guard can copy the cursor images.
        if self.process:
            try:self.process.wait(timeout=1)
            except subprocess.TimeoutExpired:pass
        # Never terminate a guard holding original cursor handles.


def run_guard(parent_pid,event_name):
    import time
    from ctypes import wintypes as W
    u=ctypes.WinDLL('user32',use_last_error=True);k=ctypes.WinDLL('kernel32',use_last_error=True)
    u.LoadCursorW.argtypes=[W.HINSTANCE,ctypes.c_void_p];u.LoadCursorW.restype=W.HANDLE
    u.CopyImage.argtypes=[W.HANDLE,W.UINT,ctypes.c_int,ctypes.c_int,W.UINT];u.CopyImage.restype=W.HANDLE
    u.CreateCursor.argtypes=[W.HINSTANCE,ctypes.c_int,ctypes.c_int,ctypes.c_int,ctypes.c_int,ctypes.c_void_p,ctypes.c_void_p];u.CreateCursor.restype=W.HANDLE
    u.SetSystemCursor.argtypes=[W.HANDLE,W.DWORD];u.SetSystemCursor.restype=W.BOOL
    u.DestroyCursor.argtypes=[W.HANDLE];u.DestroyCursor.restype=W.BOOL
    k.OpenProcess.argtypes=[W.DWORD,W.BOOL,W.DWORD];k.OpenProcess.restype=W.HANDLE
    k.WaitForSingleObject.argtypes=[W.HANDLE,W.DWORD];k.CloseHandle.argtypes=[W.HANDLE]
    k.GetModuleHandleW.argtypes=[W.LPCWSTR];k.GetModuleHandleW.restype=W.HINSTANCE
    parent=k.OpenProcess(0x00100000,False,int(parent_pid))
    if not parent:raise OSError('無法監測掃描主程序')
    k.OpenEventW.argtypes=[W.DWORD,W.BOOL,W.LPCWSTR];k.OpenEventW.restype=W.HANDLE
    k.SetEvent.argtypes=[W.HANDLE]
    stop_handle=k.OpenEventW(0x00100000,False,event_name+'-stop')
    ready_handle=k.OpenEventW(0x0002,False,event_name+'-ready')
    if not stop_handle or not ready_handle:
        if stop_handle:k.CloseHandle(stop_handle)
        if ready_handle:k.CloseHandle(ready_handle)
        k.CloseHandle(parent);raise OSError('游標保護事件已關閉')
    stop=threading.Event();backups={};changed=set();hwnd=None;registered=False
    idle=CursorIdleState()
    proc_type=ctypes.WINFUNCTYPE(ctypes.c_ssize_t,W.HWND,W.UINT,W.WPARAM,W.LPARAM)
    class Header(ctypes.Structure):
        _fields_=[('kind',W.DWORD),('size',W.DWORD),('device',W.HANDLE),('wparam',W.WPARAM)]
    class Mouse(ctypes.Structure):
        _fields_=[('flags',W.USHORT),('buttons',W.ULONG),('raw_buttons',W.ULONG),('dx',W.LONG),('dy',W.LONG),('extra',W.ULONG)]
    class Device(ctypes.Structure):
        _fields_=[('page',W.USHORT),('usage',W.USHORT),('flags',W.DWORD),('target',W.HWND)]
    class WindowClass(ctypes.Structure):
        _fields_=[('style',W.UINT),('proc',proc_type),('class_extra',ctypes.c_int),('window_extra',ctypes.c_int),
                  ('instance',W.HINSTANCE),('icon',W.HANDLE),('cursor',W.HANDLE),('brush',W.HANDLE),('menu',W.LPCWSTR),('name',W.LPCWSTR)]
    u.DefWindowProcW.argtypes=[W.HWND,W.UINT,W.WPARAM,W.LPARAM];u.DefWindowProcW.restype=ctypes.c_ssize_t
    u.GetRawInputData.argtypes=[W.HANDLE,W.UINT,ctypes.c_void_p,ctypes.POINTER(W.UINT),W.UINT];u.GetRawInputData.restype=W.UINT
    @proc_type
    def handler(window,message,wparam,lparam):
        if message==0x00FF:
            size=W.UINT()
            if u.GetRawInputData(W.HANDLE(lparam),0x10000003,None,ctypes.byref(size),ctypes.sizeof(Header))==0 and size.value>=ctypes.sizeof(Header)+ctypes.sizeof(Mouse):
                data=ctypes.create_string_buffer(size.value)
                if u.GetRawInputData(W.HANDLE(lparam),0x10000003,data,ctypes.byref(size),ctypes.sizeof(Header))!=0xFFFFFFFF:
                    head=Header.from_buffer_copy(data);mouse=Mouse.from_buffer_copy(data,ctypes.sizeof(Header))
                    if head.kind==0 and physical_motion(head.device,mouse.dx,mouse.dy):
                        if idle.motion(time.monotonic())=='show':restore_visible()
        return u.DefWindowProcW(window,message,wparam,lparam)
    instance=k.GetModuleHandleW(None);name='MitakeCursorRestoreGuard'+str(os.getpid())
    wc=WindowClass();wc.proc=handler;wc.instance=instance;wc.name=name
    u.RegisterClassW.argtypes=[ctypes.POINTER(WindowClass)];u.RegisterClassW.restype=W.ATOM
    u.CreateWindowExW.argtypes=[W.DWORD,W.LPCWSTR,W.LPCWSTR,W.DWORD,ctypes.c_int,ctypes.c_int,ctypes.c_int,ctypes.c_int,W.HWND,W.HMENU,W.HINSTANCE,ctypes.c_void_p];u.CreateWindowExW.restype=W.HWND
    u.RegisterRawInputDevices.argtypes=[ctypes.POINTER(Device),W.UINT,W.UINT];u.RegisterRawInputDevices.restype=W.BOOL
    u.PeekMessageW.argtypes=[ctypes.POINTER(W.MSG),W.HWND,W.UINT,W.UINT,W.UINT]
    u.DispatchMessageW.argtypes=[ctypes.POINTER(W.MSG)];u.DispatchMessageW.restype=ctypes.c_ssize_t
    u.DestroyWindow.argtypes=[W.HWND];u.UnregisterClassW.argtypes=[W.LPCWSTR,W.HINSTANCE]
    def restore_visible():
        failed=False
        for cursor_id in tuple(changed):
            # Retain the master originals for subsequent hide/show cycles.
            copied=u.CopyImage(backups[cursor_id],2,0,0,0)
            if copied and u.SetSystemCursor(copied,cursor_id):changed.discard(cursor_id)
            else:
                if copied:u.DestroyCursor(copied)
                failed=True
        if failed:
            u.SystemParametersInfoW(0x0057,0,None,0)
            stop.set()
    def hide_all():
        for cursor_id in CURSOR_IDS:
            blank=u.CreateCursor(instance,0,0,32,32,and_mask,xor_mask)
            if not blank:raise OSError('無法建立透明游標')
            if not u.SetSystemCursor(blank,cursor_id):
                u.DestroyCursor(blank);raise OSError('無法暫時隱藏游標')
            changed.add(cursor_id)
        # A target application may retain the active handle until the next
        # WM_SETCURSOR. Force a normal cursor refresh without changing position.
        position=W.POINT()
        if u.GetCursorPos(ctypes.byref(position)):u.SetCursorPos(position.x,position.y)
    try:
        if not u.RegisterClassW(ctypes.byref(wc)):raise OSError('游標保護視窗無法建立')
        registered=True
        hwnd=u.CreateWindowExW(0,name,name,0,0,0,0,0,W.HWND(-3),None,instance,None)
        if not hwnd:raise OSError('游標保護視窗無法建立')
        device=Device(1,2,0x100,hwnd)
        if not u.RegisterRawInputDevices(ctypes.byref(device),1,ctypes.sizeof(Device)):raise OSError('無法監測使用者滑鼠移動')
        and_mask=(ctypes.c_ubyte*128)(*([255]*128));xor_mask=(ctypes.c_ubyte*128)()
        # Copy all originals before making any visible changes.
        for cursor_id in CURSOR_IDS:
            original=u.LoadCursorW(None,ctypes.c_void_p(cursor_id))
            copied=u.CopyImage(original,2,0,0,0) if original else None
            if not copied:raise OSError('無法備份原游標')
            backups[cursor_id]=copied
        if stop.is_set() or k.WaitForSingleObject(stop_handle,0)==0:return
        hide_all()
        k.SetEvent(ready_handle)
        message=W.MSG()
        while not stop.is_set() and k.WaitForSingleObject(stop_handle,0)==0x102 and k.WaitForSingleObject(parent,0)==0x102:
            while u.PeekMessageW(ctypes.byref(message),None,0,0,1):
                u.TranslateMessage(ctypes.byref(message));u.DispatchMessageW(ctypes.byref(message))
            if idle.tick(time.monotonic())=='hide':hide_all()
            stop.wait(.01)
    finally:
        restore_visible()
        for original in backups.values():u.DestroyCursor(original)
        if hwnd:
            device=Device(1,2,1,None);u.RegisterRawInputDevices(ctypes.byref(device),1,ctypes.sizeof(Device));u.DestroyWindow(hwnd)
        if registered:u.UnregisterClassW(name,instance)
        k.CloseHandle(parent);k.CloseHandle(stop_handle);k.CloseHandle(ready_handle)


def native_smoke(controller,point):
    """Verify actual Windows cursor images change, robot moves don't reveal, and stop restores."""
    from ctypes import wintypes as W
    u=ctypes.WinDLL('user32',use_last_error=True);g=ctypes.WinDLL('gdi32',use_last_error=True)
    u.LoadCursorW.argtypes=[W.HINSTANCE,ctypes.c_void_p];u.LoadCursorW.restype=W.HANDLE
    class Icon(ctypes.Structure):
        _fields_=[('is_icon',W.BOOL),('x',W.DWORD),('y',W.DWORD),('mask',W.HBITMAP),('color',W.HBITMAP)]
    class Bitmap(ctypes.Structure):
        _fields_=[('kind',W.LONG),('width',W.LONG),('height',W.LONG),('row_bytes',W.LONG),('planes',W.WORD),('bits_per_pixel',W.WORD),('bits',ctypes.c_void_p)]
    u.GetIconInfo.argtypes=[W.HANDLE,ctypes.POINTER(Icon)]
    g.GetObjectW.argtypes=[W.HANDLE,ctypes.c_int,ctypes.c_void_p]
    g.GetBitmapBits.argtypes=[W.HBITMAP,W.LONG,ctypes.c_void_p];g.GetBitmapBits.restype=W.LONG
    g.DeleteObject.argtypes=[W.HANDLE]
    def signature():
        info=Icon();handle=u.LoadCursorW(None,ctypes.c_void_p(32512))
        assert u.GetIconInfo(handle,ctypes.byref(info))
        values=[]
        try:
            for bitmap in (info.mask,info.color):
                if not bitmap:continue
                shape=Bitmap();assert g.GetObjectW(bitmap,ctypes.sizeof(shape),ctypes.byref(shape))
                size=shape.row_bytes*shape.height;data=ctypes.create_string_buffer(size)
                assert g.GetBitmapBits(bitmap,size,data)==size
                values.append((shape.width,shape.height,data.raw))
            return values
        finally:
            for bitmap in (info.mask,info.color):
                if bitmap:g.DeleteObject(bitmap)
    original=signature();guard=CursorVisibility()
    try:
        assert guard.start(),guard.error
        assert signature()!=original,'Cursor was not hidden'
        controller.point(point)
        threading.Event().wait(.15)
        assert guard.process.poll() is None,'Automatic movement incorrectly revealed cursor'
    finally:
        guard.close()
        if guard.process:guard.process.wait(timeout=5)
    assert signature()==original,'Original cursor was not restored'
    return True
