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
    foreground=u.GetForegroundWindow()
    region=dict(left=combo.winfo_rootx(),top=combo.winfo_rooty(),width=260,height=40)
    app.show_scan_outline(region);root.update()
    assert app.scan_outline.visible and app.scan_outline.region==region
    assert all(win.winfo_ismapped() for win in app.scan_outline.windows)
    assert u.GetForegroundWindow()==foreground,'Scan outline stole focus'
    from PIL import ImageGrab
    baseline=ImageGrab.grab(bbox=(region['left'],region['top'],region['left']+260,region['top']+40))
    app.scan_outline.hide();root.update()
    clean=ImageGrab.grab(bbox=(region['left'],region['top'],region['left']+260,region['top']+40))
    assert baseline.tobytes()==clean.tobytes(),'Scan border changed pixels inside the OCR area'
    app.show_scan_outline([dict(region,width=50),dict(region,left=region['left']+100,width=50)])
    root.update()
    assert app.scan_outline.region['width']==50
    assert len(app.scan_outline_extras)==1 and app.scan_outline_extras[0].visible
    assert u.GetForegroundWindow()==foreground
    app.show_scan_outline(None);root.update()
    assert not app.scan_outline.visible and not app.scan_outline_extras[0].visible
    app.show_scan_outline(dict(region,left=region['left']+20))
    root.update()
    assert app.scan_outline.region['left']==region['left']+20
    app.scan_outline.hide()
    app.show_task_progress(hwnd,'正在測試進度窗｜第1/3頁')
    root.update()
    assert u.GetForegroundWindow()==foreground,'Progress panel stole keyboard focus'
    progress_panel=app.task_panel
    assert progress_panel.alive and progress_panel.window.winfo_ismapped(),'Progress panel is not visible'
    progress_panel.update('排行1/5｜已讀26檔｜等待3秒',1,5)
    root.update()
    assert '26檔' in progress_panel.detail.get()
    from PIL import ImageGrab
    from pathlib import Path
    root.update_idletasks()
    ImageGrab.grab(bbox=(progress_panel.window.winfo_rootx(),progress_panel.window.winfo_rooty(),progress_panel.window.winfo_rootx()+progress_panel.window.winfo_width(),progress_panel.window.winfo_rooty()+48)).save(Path('release-output')/'progress-panel.png')
    get_style=u.GetWindowLongPtrW if ctypes.sizeof(ctypes.c_void_p)==8 else u.GetWindowLongW
    get_style.argtypes=[W.HWND,ctypes.c_int];get_style.restype=ctypes.c_ssize_t
    style=get_style(progress_panel.native_hwnd,-20)
    assert style&0x08000000 and style&0x20,'Panel must be passive and click-through'
    done=queue.Queue();ocr_log=[]
    def read(image):
        from pathlib import Path
        image.save(Path('release-output')/('auto-fixture-'+str(len(ocr_log))+'.png'))
        tokens=app.read_auto_ocr(image)
        ocr_log.append([str(t[1]) for t in tokens])
        return tokens
    def worker():
        try:
            controller.activate()
            from auto_detect import detect_menu
            switcher=RankingSwitcher(controller,read,combo_roi,menu_roi)
            # Starting on the requested ranking must not open its dropdown.
            clicks=[];original_click=controller.click
            def tracked_click(point):clicks.append(point);original_click(point)
            controller.click=tracked_click
            switcher.switch('漲幅排行')
            assert not clicks,'Already selected ranking caused a mouse click'
            controller.click((rect[0]+x+100,rect[1]+y+20));controller.pause(.4)
            opened,_=controller.image([0,0,1,1])
            discovered_menu=detect_menu(opened,combo_roi,read)
            switcher=RankingSwitcher(controller,read,combo_roi,discovered_menu)
            before=len(clicks)
            switcher.switch('漲幅排行',menu_open=True)
            assert len(clicks)==before+1,'Open popup was toggled rather than selected'
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
        return {'menu_switches':3,'selected_label':label.get(),'stop_blocks_input':True,'menu_auto_detected':True,'current_rank_no_click':True,'open_popup_one_click':True,'progress_visible':True,'progress_no_focus':True}
    finally:
        stop.set();thread.join(timeout=1);app.close_task_progress();win.destroy();root.update()
