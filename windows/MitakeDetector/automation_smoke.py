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
    panel=tk.Frame(win,bg='white',bd=1,relief='solid')
    combo=tk.Button(win,textvariable=label,font=('Microsoft JhengHei',16),bg='#102c40',fg='white',command=lambda:panel.place(x=25,y=65,width=260,height=150))
    combo.place(x=25,y=25,width=260,height=40)
    def select(name):
        label.set(name);panel.place_forget()
    for i,name in enumerate(['漲幅排行','預估量增','大單流入','大單匯集']):
        tk.Button(panel,text=name,font=('Microsoft JhengHei',16),bg='#102c40',fg='white',anchor='w',bd=0,command=lambda n=name:select(n)).place(x=1,y=i*36,width=255,height=36)
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
    done=queue.Queue();ocr_log=[]
    def read(image):
        tokens=app.read_auto_ocr(image)
        ocr_log.append([str(t[1]) for t in tokens])
        return tokens
    def worker():
        try:
            controller.activate()
            switcher=RankingSwitcher(controller,read,combo_roi,menu_roi)
            switcher.switch('大單流入')
            switcher.switch('漲幅排行')
            stop.set()
            try:controller.click((rect[0]+50,rect[1]+50))
            except AutomationStopped:pass
            else:raise AssertionError('Stop did not block mouse input')
            done.put((True,None))
        except Exception as exc:done.put((False,str(exc)+'; OCR='+repr(ocr_log)))
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
