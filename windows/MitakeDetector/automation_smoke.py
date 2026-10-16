"""Windows integration test using an isolated local ranking-menu fixture."""
import ctypes
import queue
import threading
import time
from autoswitch import WindowsController, RankingSwitcher, AutomationStopped


def run_automation_smoke(root,app):
    import tkinter as tk
    from tkinter import ttk
    win=tk.Toplevel(root);win.title('Mitake automation test fixture');win.geometry('520x450+100+100')
    label=tk.StringVar(value='漲幅排行')
    combo=ttk.Combobox(win,textvariable=label,values=['漲幅排行','預估量增','大單流入','大單匯集'],state='readonly',font=('Microsoft JhengHei',16),height=4)
    combo.place(x=25,y=25,width=260,height=40)
    root.update();win.lift();win.focus_force();root.update()
    u=ctypes.windll.user32
    from ctypes import wintypes as W
    u.GetAncestor.argtypes=[W.HWND,W.UINT];u.GetAncestor.restype=W.HWND
    hwnd=u.GetAncestor(win.winfo_id(),2)
    stop=threading.Event();controller=WindowsController(hwnd,stop)
    rect=controller.rect();width,height=rect[2]-rect[0],rect[3]-rect[1]
    x,y=combo.winfo_rootx()-rect[0],combo.winfo_rooty()-rect[1]
    combo_roi=[x/width,y/height,260/width,40/height]
    menu_roi=[x/width,(y+40)/height,260/width,170/height]
    done=queue.Queue()
    def worker():
        try:
            controller.activate()
            switcher=RankingSwitcher(controller,app.read_auto_ocr,combo_roi,menu_roi)
            switcher.switch('大單流入')
            switcher.switch('漲幅排行')
            stop.set()
            try:controller.click((rect[0]+50,rect[1]+50))
            except AutomationStopped:pass
            else:raise AssertionError('Stop did not block mouse input')
            done.put((True,None))
        except Exception as exc:done.put((False,str(exc)))
    thread=threading.Thread(target=worker,daemon=True);thread.start()
    deadline=time.monotonic()+100
    try:
        while time.monotonic()<deadline:
            root.update()
            try:ok,error=done.get_nowait();break
            except queue.Empty:time.sleep(.02)
        else:stop.set();raise RuntimeError('Automation fixture timed out')
        if not ok:raise RuntimeError('Automation fixture: '+error)
        assert label.get()=='漲幅排行'
        return {'menu_switches':2,'selected_label':label.get(),'stop_blocks_input':True}
    finally:
        stop.set();thread.join(timeout=1);win.destroy();root.update()
