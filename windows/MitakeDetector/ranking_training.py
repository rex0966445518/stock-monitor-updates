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


def replay_menu(switcher,page):
    """Reopen after teaching UI took focus; never trust popup-open bookkeeping."""
    c=switcher.c
    c.activate();c.check()
    c.u.keybd_event(0x1B,0,0,0);c.u.keybd_event(0x1B,0,2,0)
    c.pause(.15)
    _,combo=c.image(switcher.combo)
    c.click((combo['left']+combo['width']//2,combo['top']+combo['height']//2))
    switcher.menu_is_open=True;c.pause(.4)
    from autoswitch import absolute_roi
    region=absolute_roi(switcher.menu,c.rect())
    center=(region['left']+region['width']//2,region['top']+region['height']//2)
    c.scroll(center,120*60);c.pause(.35)
    for _ in range(page):c.scroll(center,-120);c.pause(.45)
    return c.image(switcher.menu)


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
    top=app.tk.Toplevel(app.root);top.title('學習排行榜｜F8停止');top.geometry('680x740');top.attributes('-topmost',True)
    # Place beside the captured menu rather than hide the learning window.
    rect=WindowsController(target,stop).rect();menu=absolute_roi(auto['menu'],rect)
    right=menu['left']+menu['width']+16
    x=right if right+680<=top.winfo_screenwidth() else max(0,menu['left']-696)
    top.geometry(f'680x740+{x}+0')
    status=app.tk.StringVar(value='每輪隨機測試全部排行；每項連續三次自動核對成功才完成。')
    app.ttk.Label(top,textvariable=status,wraplength=650).pack(fill='x',padx=10,pady=8)
    table=app.ttk.Treeview(top,columns=('streak','tries','fail'),height=6)
    table.heading('#0',text='排行');table.column('#0',width=220)
    for key,label in [('streak','連續通過 / 3'),('tries','自動測試次數'),('fail','失敗次數')]:
        table.heading(key,text=label);table.column(key,width=115)
    for name in RANKINGS:table.insert('', 'end',iid=name,text=name,values=(0,0,0))
    table.pack(fill='x',padx=10)
    app.ttk.Label(top,text='需要協助時：在下方選單截圖點選指定排行。可往下捲動尋找；\n人工示範不算通過，接著必須由系統自行選取並核對。',wraplength=650).pack(pady=8)
    raw_text=app.tk.StringVar();mapped=app.tk.StringVar(value=RANKINGS[0])
    app.ttk.Label(top,text='即時 OCR 原文（選一項設定對應；保留辨識信心門檻）').pack(anchor='w',padx=10)
    raw_picker=app.ttk.Combobox(top,textvariable=raw_text,state='readonly');raw_picker.pack(fill='x',padx=10)
    correction=app.ttk.Frame(top);correction.pack(fill='x',padx=10,pady=4)
    app.ttk.Combobox(correction,textvariable=mapped,values=RANKINGS,state='readonly',width=16).pack(side='left')
    app.ttk.Button(correction,text='儲存對應並重試',command=lambda:send('alias',(raw_text.get(),mapped.get()))).pack(side='left',padx=6)
    app.ttk.Button(correction,text='刪除此對應',command=lambda:send('remove',raw_text.get())).pack(side='left')
    ocr_detail=app.tk.StringVar(value='等待辨識…')
    app.ttk.Label(top,textvariable=ocr_detail,wraplength=650).pack(fill='x',padx=10)
    canvas=app.tk.Canvas(top,height=300,bg='#102c40');canvas.pack(fill='both',expand=True,padx=10)
    state={'image':None,'scale':1,'assisting':False,'done':False}
    buttons=app.ttk.Frame(top);buttons.pack(fill='x',pady=8)
    def send(action,value=None):
        if stop.is_set():return
        if not state['assisting']:
            status.set('正在自動定位，請等待下方選單截圖出現後，再點選截圖中的排行。');return
        state['assisting']=False
        status.set('已收到操作，正在還原選單並核對，請稍候…')
        commands.put((action,value))
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
                elif kind=='ocr':
                    values=list(dict.fromkeys(str(t[1]) for t in data))
                    raw_picker['values']=values
                    if raw_text.get() not in values:raw_text.set(values[0] if values else '')
                    ocr_detail.set('辨識原文：'+'、'.join(f'{t[1]} ({float(t[2]):.0%})' for t in data)[:180])
                elif kind=='target':mapped.set(data)
                elif kind=='results':
                    for n,values in data.items():table.item(n,values=values)
                elif kind=='image':
                    from PIL import ImageTk
                    im=data;scale=min(max(1,canvas.winfo_width()-4)/im.width,max(1,canvas.winfo_height()-4)/im.height,1)
                    state['scale']=scale;state['image']=ImageTk.PhotoImage(im.resize((max(1,round(im.width*scale)),max(1,round(im.height*scale)))))
                    canvas.delete('all');canvas.create_image(0,0,anchor='nw',image=state['image'])
                    state['assisting']=True;top.lift()
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
            switcher=RankingSwitcher(c,app.read_auto_ocr,auto['combo'],auto['menu'],experience=experience,ocr_retry=app.read_menu_ocr_retry,numbering=auto.get('menu_numbering',True),menu_numbers=auto.get('menu_numbers',{}),observe_ocr=lambda tokens:updates.put(('ocr',tokens)))
            def assist(name):
                updates.put(('target',name))
                page=0
                im,region=replay_menu(switcher,page)
                while not stop.is_set():
                    switcher.ocr(im)
                    updates.put(('image',im))
                    while not stop.is_set():
                        try:action,value=commands.get(timeout=.1);break
                        except queue.Empty:continue
                    if stop.is_set():return
                    if action in ('alias','remove'):
                        try:
                            if action=='alias':switcher.aliases.save(*value)
                            else:switcher.aliases.remove(value)
                            switcher.verified_title_pixels=None;switcher.verified_titles=set()
                            updates.put(('status','對應已更新；正在重新自動定位「'+name+'」'))
                            switcher.switch(name)
                            updates.put(('status','重新定位成功；人工對應不計入通過次數，繼續自動測試'))
                            return
                        except Exception as exc:
                            updates.put(('status','對應重試未通過：'+str(exc)))
                            im,region=replay_menu(switcher,page);continue
                    if action=='next':page=min(59,page+1)
                    elif action=='home':page=0
                    now,region=replay_menu(switcher,page)
                    if action in ('next','home'):im=now;continue
                    if now.size!=im.size:
                        updates.put(('status','選單尺寸變動，請重新點選「'+name+'」'))
                        im=now;continue
                    # The page was replayed from the top. Final title verification
                    # gates persistence; focus/hover pixels need not be identical.
                    x,y=value
                    if not (0<=x<im.width and 0<=y<im.height):continue
                    c.click((region['left']+x,region['top']+y));switcher.menu_is_open=False
                    if not switcher.verify_label(name):
                        updates.put(('status','人工點選後標題未通過核對，未保存；請再次示範「'+name+'」'))
                        im,region=replay_menu(switcher,page);continue
                    experience.record(name,page,(x,y),switcher.menu_signature(now))
                    updates.put(('status','已核對並保存「'+name+'」，繼續自動測試'))
                    if switcher.numbers:switcher.numbers.disabled.add(name)
                    return
            rounds=0
            while not stop.is_set() and not progress.complete:
                rounds+=1
                for name in progress.round():
                    if stop.is_set():break
                    updates.put(('status',f'第 {rounds} 輪｜自動測試「{name}」｜F8停止'))
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
                        if any(word in reason for word in ('綠框','焦點','遮住','縮放','視窗已更換','滑鼠')):
                            raise RuntimeError('環境問題，學習已暫停（不算定位失敗）：'+reason)
                        progress.result(name,False)
                        updates.put(('status',f'請協助定位「{name}」：{reason}'))
                        # The teaching panel stays visible beside the menu.
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
    app.worker_thread.start();top.after(50,pump)

