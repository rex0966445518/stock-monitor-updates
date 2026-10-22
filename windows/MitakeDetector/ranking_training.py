"""Interactive ranking practice; only independently verified switches pass."""
import json
import queue
import random
import threading
import time


class Progress:
    def __init__(self,names):
        self.names=list(names);self.streak={n:0 for n in names}
        self.attempts={n:0 for n in names};self.failures={n:0 for n in names}
    def round(self):
        names=list(self.names);random.shuffle(names);return names
    def result(self,name,ok):
        self.attempts[name]+=1
        self.streak[name]=self.streak[name]+1 if ok else 0
        if not ok:self.failures[name]+=1
    @property
    def complete(self):return all(v>=3 for v in self.streak.values())


def open_training(app):
    from tkinter import messagebox
    from autoswitch import WindowsController,RankingSwitcher,RANKINGS,validate_roi,absolute_roi
    from adaptive import Experience
    from MitakeDetector import BASE
    if not app.idle() or not app.dependencies():return
    auto=json.loads(json.dumps(app.config.get('auto_config') or {}))
    try:
        for key in ('combo','menu'):validate_roi(auto.get(key))
        target=app.resolve_auto_window(auto)
    except Exception as exc:messagebox.showerror('學習排行榜',str(exc));return
    app.cancel_scan_retry()
    app.generation+=1;token=app.generation
    app.stop_event=stop=threading.Event()
    updates=queue.Queue();commands=queue.Queue();progress=Progress(RANKINGS)
    top=app.tk.Toplevel(app.root);top.title('學習排行榜｜F1停止');top.geometry('680x740')
    status=app.tk.StringVar(value='每輪隨機測試全部排行；每項連續三次自動核對成功才完成。')
    app.ttk.Label(top,textvariable=status,wraplength=650).pack(fill='x',padx=10,pady=8)
    table=app.ttk.Treeview(top,columns=('streak','tries','fail'),height=10)
    table.heading('#0',text='排行');table.column('#0',width=220)
    for key,label in [('streak','連續通過 / 3'),('tries','自動測試次數'),('fail','失敗次數')]:
        table.heading(key,text=label);table.column(key,width=115)
    for name in RANKINGS:table.insert('', 'end',iid=name,text=name,values=(0,0,0))
    table.pack(fill='x',padx=10)
    app.ttk.Label(top,text='需要協助時：在下方選單截圖點選指定排行。可往下捲動尋找；\n人工示範不算通過，接著必須由系統自行選取並核對。',wraplength=650).pack(pady=8)
    canvas=app.tk.Canvas(top,height=300,bg='#102c40');canvas.pack(fill='both',expand=True,padx=10)
    state={'image':None,'scale':1,'assisting':False,'done':False}
    buttons=app.ttk.Frame(top);buttons.pack(fill='x',pady=8)
    def send(action,value=None):
        if not state['assisting'] or stop.is_set():return
        state['assisting']=False;top.withdraw();commands.put((action,value))
    app.ttk.Button(buttons,text='往下一段',command=lambda:send('next')).pack(side='left',padx=8)
    app.ttk.Button(buttons,text='回到頂部',command=lambda:send('home')).pack(side='left')
    app.ttk.Button(buttons,text='停止學習',command=stop.set).pack(side='right',padx=8)
    canvas.bind('<Button-1>',lambda e:send('point',(round(e.x/state['scale']),round(e.y/state['scale']))))
    def close():stop.set();top.destroy()
    top.protocol('WM_DELETE_WINDOW',close)
    def pump():
        if not top.winfo_exists():return
        try:
            while True:
                kind,data=updates.get_nowait()
                if kind=='status':status.set(data);app.status.set(data)
                elif kind=='results':
                    for n,values in data.items():table.item(n,values=values)
                elif kind=='image':
                    from PIL import ImageTk
                    im=data;scale=min(640/im.width,300/im.height,1)
                    state['scale']=scale;state['image']=ImageTk.PhotoImage(im.resize((max(1,round(im.width*scale)),max(1,round(im.height*scale)))))
                    canvas.delete('all');canvas.create_image(0,0,anchor='nw',image=state['image'])
                    state['assisting']=True;top.deiconify();top.lift()
                elif kind=='done':state['done']=True;status.set(data);top.deiconify()
        except queue.Empty:pass
        if not state['done']:top.after(50,pump)
    def job():
        switcher=None
        try:
            c=WindowsController(target,stop)
            c.highlight=lambda region:app.prepare_scan_capture(token,region,stop)
            app.outline_disabled_token=token
            c.activate()
            app.highlight_scan(token,None,stop)
            experience=Experience(BASE/'automation-experience.json',dict({k:auto.get(k) for k in ('title','window_size','combo','menu','table')},menu_catalog=RANKINGS),True)
            switcher=RankingSwitcher(c,app.read_auto_ocr,auto['combo'],auto['menu'],experience=experience,ocr_retry=app.read_menu_ocr_retry,numbering=auto.get('menu_numbering',True),menu_numbers=auto.get('menu_numbers',{}))
            def assist(name):
                # All demonstration screenshots use a reproducible path from the top.
                if not switcher.menu_is_open:
                    _,combo=c.image(auto['combo']);c.click((combo['left']+combo['width']//2,combo['top']+combo['height']//2));c.pause(.4)
                    switcher.menu_is_open=True
                region=absolute_roi(auto['menu'],c.rect());center=(region['left']+region['width']//2,region['top']+region['height']//2)
                c.scroll(center,120*60);c.pause(.35);page=0
                while not stop.is_set():
                    im,region=c.image(auto['menu']);updates.put(('image',im))
                    while not stop.is_set():
                        try:action,value=commands.get(timeout=.1);break
                        except queue.Empty:continue
                    if stop.is_set():return
                    c.activate()
                    # Recheck exact pixels before using a click from an old screenshot.
                    now,_=c.image(auto['menu'])
                    if switcher.menu_signature(now)!=switcher.menu_signature(im):
                        updates.put(('status','選單畫面已變動，重新擷取；請再點選「'+name+'」'));continue
                    if action=='next':
                        if page<59:c.scroll(center,-120);page+=1
                        c.pause(.45);continue
                    if action=='home':c.scroll(center,120*60);page=0;c.pause(.35);continue
                    x,y=value
                    if not (0<=x<im.width and 0<=y<im.height):continue
                    c.click((region['left']+x,region['top']+y));switcher.menu_is_open=False
                    if not switcher.verify_label(name):
                        updates.put(('status','人工點選後標題未通過核對，未保存；請再次示範「'+name+'」'))
                        _,combo=c.image(auto['combo']);c.click((combo['left']+combo['width']//2,combo['top']+combo['height']//2));switcher.menu_is_open=True
                        c.pause(.4);c.scroll(center,120*60);page=0;c.pause(.35);continue
                    experience.record(name,page,(x,y),switcher.menu_signature(im))
                    if switcher.numbers:switcher.numbers.disabled.add(name)
                    return
            rounds=0
            while not stop.is_set() and not progress.complete:
                rounds+=1
                for name in progress.round():
                    if stop.is_set():break
                    updates.put(('status',f'第 {rounds} 輪｜自動測試「{name}」｜F1停止'))
                    try:
                        c.check()
                        # Prevent the current label fast path from counting as navigation.
                        if switcher.label_matches(name):
                            switcher.switch(next(n for n in RANKINGS if n!=name))
                        switcher.switch(name)
                        progress.result(name,True)
                    except Exception as exc:
                        if stop.is_set():break
                        reason=str(exc)
                        if any(word in reason for word in ('綠框','焦點','遮住','縮放','視窗已更換')):
                            raise RuntimeError('環境問題，學習已暫停（不算定位失敗）：'+reason)
                        progress.result(name,False)
                        updates.put(('status',f'請協助定位「{name}」：{reason}'))
                        # Hide the teaching panel before taking a clean menu screenshot.
                        updates.put(('results',{n:(progress.streak[n],progress.attempts[n],progress.failures[n]) for n in RANKINGS}))
                        assist(name)
                    updates.put(('results',{n:(progress.streak[n],progress.attempts[n],progress.failures[n]) for n in RANKINGS}))
                    report={'time':time.time(),'layout':experience.key,'rounds':rounds,'streak':progress.streak,'attempts':progress.attempts,'failures':progress.failures,'complete':progress.complete}
                    path=BASE/'ranking-training-report.json';temp=path.with_suffix('.tmp')
                    temp.write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8');temp.replace(path)
            updates.put(('done','全部排行均連續三次自動核對成功（本次測試通過率100%，不代表所有環境永久保證）。' if progress.complete else '學習已停止；已驗證的位置及測試紀錄已保存。'))
        except Exception as exc:updates.put(('done','學習暫停：'+str(exc)))
        finally:
            stop.set()
            if switcher and switcher.numbers:switcher.numbers.show(None)
    app.worker_thread=threading.Thread(target=job,daemon=True)
    # Keep teaching UI off the target screenshot during automatic navigation.
    top.withdraw();app.worker_thread.start();top.after(50,pump)

