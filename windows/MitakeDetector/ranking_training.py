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


def same_teaching_layout(a,b):
    if a==b:return True
    try:
        left,right=json.loads(a),json.loads(b)
        if not isinstance(left,dict) or not isinstance(right,dict):return False
        # Table columns can adapt independently of the ranking dropdown.
        left.pop('table',None);right.pop('table',None)
        return left==right
    except (TypeError,ValueError):return False


class TeachingMemory:
    """Explicitly confirmed candidates; never counted as verified experience."""
    def __init__(self,path,layout):
        self.path=path;self.layout=layout;self.items={}
        try:
            saved=json.loads(path.read_text(encoding='utf-8'))
            if same_teaching_layout(saved.get('layout'),layout) and isinstance(saved.get('items'),dict):self.items=saved['items']
        except (OSError,ValueError,AttributeError):pass
    def get(self,name):
        import math
        value=self.items.get(name)
        if not isinstance(value,dict):return None
        number=value.get('number');page=value.get('page');point=value.get('point');size=value.get('size')
        if number is not None and (type(number) is not int or not 1<=number<=500):return None
        if type(page) is not int or not 0<=page<60:return None
        if not isinstance(size,list) or len(size)!=2 or not all(type(v) is int and 0<v<32768 for v in size):return None
        if not isinstance(point,list) or len(point)!=2 or not all(type(v) in (int,float) and math.isfinite(v) and 0<=v<size[i] for i,v in enumerate(point)):return None
        return value
    def save(self):
        self.path.parent.mkdir(parents=True,exist_ok=True)
        temp=self.path.with_suffix('.tmp')
        temp.write_text(json.dumps(dict(layout=self.layout,items=self.items),ensure_ascii=False),encoding='utf-8');temp.replace(self.path)
    def mark(self,name,result,detail=''):
        value=self.get(name)
        if value is None:return
        if result not in ('pending','passed','failed'):raise ValueError('未知驗證結果')
        value.update(verification=result,detail=str(detail)[:300],checked_at=time.time());self.save()
    def remember(self,name,number,page,point,size):
        self.items[name]=dict(number=number,page=page,point=list(point),size=list(size))
        if self.get(name) is None:
            self.items.pop(name,None);raise ValueError('位置無效，未儲存')
        self.save()


def learned_positions(names,teaching,experience):
    result={}
    for name in names:
        saved=teaching.get(name)
        if saved:
            result[name]=f"第 {saved['number']} 項" if saved['number'] is not None else f"捲動 {saved['page']} 次／座標 {round(saved['point'][0])},{round(saved['point'][1])}"
        elif experience.number(name):result[name]=f"第 {experience.number(name)} 項"
        else:
            entry=experience.data.get('ranks',{}).get(name,{})
            if entry.get('point') and entry.get('signature'):result[name]='已保存點選位置'
    return result


def learned_checks(names,teaching,experience):
    result={}
    for name in learned_positions(names,teaching,experience):
        saved=teaching.get(name)
        check=saved.get('verification','pending') if saved else ('passed' if experience.data.get('ranks',{}).get(name,{}).get('wins',0)>0 else 'pending')
        result[name]={'pending':'已記住待驗證','passed':'實測通過','failed':'需重新校正'}.get(check,'已記住待驗證')
    return result


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


def open_training(app,mode="manual"):
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
    height=max(420,min(740,top.winfo_screenheight()-90))
    top.geometry(f'680x{height}+{x}+0')
    top.minsize(680,420)
    state={'image':None,'source':None,'scale':1,'assisting':False,'done':False,'raw_recent':[],'point':None,'number':None,'learned':{},'current':None,'overview':None,'overview_tables':[],'checks':{}}
    counter=app.tk.StringVar(value=f'手動教學｜第 1 / {len(RANKINGS)} 項｜本次已記住 0 項')
    target_text=app.tk.StringVar(value='現在請指定：'+RANKINGS[0])
    instruction=app.tk.StringVar(value='① 點選下方截圖中的「'+RANKINGS[0]+'」\n② 黃色框位置正確後，按「記住並下一項」。')
    app.ttk.Label(top,textvariable=counter).pack(fill='x',padx=12,pady=(10,4))
    app.ttk.Label(top,textvariable=target_text,font=('Microsoft JhengHei',22,'bold'),wraplength=650).pack(fill='x',padx=12)
    app.ttk.Label(top,textvariable=instruction,font=('Microsoft JhengHei',12),wraplength=650).pack(fill='x',padx=12,pady=6)
    status=app.tk.StringVar(value='正在準備第一項的選單截圖，請稍候…')
    def send(action,value=None):
        if stop.is_set():return
        if not state['assisting']:
            status.set('正在準備選單截圖，完成後即可點選。');return
        state['assisting']=False
        confirm_button.configure(state='disabled')
        status.set('正在儲存你確認的位置…' if action=='confirm' else '正在更新標記，請稍候…')
        commands.put((action,value))
    # The primary action is packed before expandable screenshots/details.
    buttons=app.ttk.Frame(top);buttons.pack(fill='x',padx=10,pady=4)
    confirm_button=app.ttk.Button(buttons,text='記住並下一項',command=lambda:send('confirm'),state='disabled')
    confirm_button.pack(side='left',padx=(0,12),ipady=5)
    app.ttk.Button(buttons,text='標記上移一列',command=lambda:send('step',-1)).pack(side='left',padx=3)
    app.ttk.Button(buttons,text='標記下移一列',command=lambda:send('step',1)).pack(side='left',padx=3)
    app.ttk.Button(buttons,text='停止',command=stop.set).pack(side='right')
    app.ttk.Label(top,textvariable=status,wraplength=650).pack(fill='x',padx=12,pady=4)
    browse=app.ttk.Frame(top);browse.pack(fill='x',padx=10)
    app.ttk.Label(browse,text='找不到時：在下方截圖滾動滑鼠，或按').pack(side='left')
    app.ttk.Button(browse,text='往上找',command=lambda:send('scroll',-1)).pack(side='left')
    app.ttk.Button(browse,text='往下找',command=lambda:send('scroll',1)).pack(side='left',padx=4)
    details=app.ttk.Frame(top)
    def toggle_details():
        if details.winfo_manager():details.pack_forget()
        else:details.pack(fill='x',before=body,padx=10)
    app.ttk.Button(browse,text='辨識細節',command=toggle_details).pack(side='right')
    raw_text=app.tk.StringVar();mapped=app.tk.StringVar(value=RANKINGS[0])
    app.ttk.Label(details,text='OCR 原文 → 正確排行（選填，不影響手動記憶）').pack(anchor='w')
    raw_picker=app.ttk.Combobox(details,textvariable=raw_text,state='readonly');raw_picker.pack(fill='x')
    correction=app.ttk.Frame(details);correction.pack(fill='x',pady=4)
    app.ttk.Combobox(correction,textvariable=mapped,values=RANKINGS,state='readonly',width=16).pack(side='left')
    app.ttk.Button(correction,text='儲存文字對應',command=lambda:send('alias',(raw_text.get(),mapped.get()))).pack(side='left',padx=6)
    app.ttk.Button(correction,text='刪除此對應',command=lambda:send('remove',raw_text.get())).pack(side='left')
    ocr_detail=app.tk.StringVar(value='等待辨識…')
    app.ttk.Label(details,textvariable=ocr_detail,wraplength=650).pack(fill='x')
    table=app.ttk.Treeview(details,columns=('streak','tries','fail'),height=3)
    table.heading('#0',text='自動測試');table.column('#0',width=180)
    for key,label in [('streak','連續通過 / 3'),('tries','測試次數'),('fail','失敗次數')]:
        table.heading(key,text=label);table.column(key,width=100)
    for name in RANKINGS:table.insert('', 'end',iid=name,text=name,values=(0,0,0))
    table.pack(fill='x')
    def start_test():
        if not state['done']:
            status.set('請先完成手動教學，或按停止後再進行自動測試。');return
        top.destroy();open_training(app,mode='test')
    app.ttk.Button(details,text='自動測試已記住的位置',command=start_test).pack(anchor='w',pady=4)
    body=app.ttk.Frame(top);body.pack(fill='both',expand=True,padx=10,pady=(4,10))
    catalog_box=app.ttk.Frame(body);catalog_box.pack(side='left',fill='y',padx=(0,8))
    catalog_count=app.tk.StringVar(value=f'全部 {len(RANKINGS)} 項｜已記住 0 項')
    app.ttk.Label(catalog_box,textvariable=catalog_count).pack(anchor='w')
    catalog_frame=app.ttk.Frame(catalog_box);catalog_frame.pack(fill='both',expand=True)
    catalog=app.ttk.Treeview(catalog_frame,columns=('state','position'),height=10,selectmode='browse')
    catalog.heading('#0',text='完整排行清單');catalog.column('#0',width=120,minwidth=100,stretch=False)
    catalog.heading('state',text='學習狀態');catalog.column('state',width=100,minwidth=90,stretch=False)
    catalog.heading('position',text='對應位置');catalog.column('position',width=95,minwidth=70,stretch=False)
    for i,n in enumerate(RANKINGS,1):catalog.insert('','end',iid=n,text=f'{i}. {n}',values=('未學習','—'))
    catalog.tag_configure('learned',foreground='#138045');catalog.tag_configure('current',foreground='#ad6200')
    catalog.tag_configure('pending',foreground='#2166a5');catalog.tag_configure('failed',foreground='#c93838')
    bar=app.ttk.Scrollbar(catalog_frame,orient='vertical',command=catalog.yview);catalog.configure(yscrollcommand=bar.set)
    bar.pack(side='right',fill='y');catalog.pack(side='left',fill='both',expand=True)
    def refresh_catalog():
        learned=state['learned'];current=state['current']
        catalog_count.set(f'全部 {len(RANKINGS)} 項｜已記住 {len(learned)} 項')
        for n in RANKINGS:
            label=('複習中' if n in learned else '正在學習') if n==current and not state['done'] else (state['checks'].get(n,'已記住待驗證') if n in learned else '未學習')
            tag='current' if n==current and not state['done'] else ('learned' if label=='實測通過' else 'failed' if label=='需重新校正' else 'pending' if n in learned else '')
            catalog.item(n,values=(label,learned.get(n,'—')),tags=(tag,))
            for tree in state['overview_tables']:
                if tree.winfo_exists() and tree.exists(n):tree.item(n,values=(label,),tags=(tag,))
    def overview():
        window=state['overview']
        if window is not None and window.winfo_exists():window.lift();return
        window=app.tk.Toplevel(top);state['overview']=window;state['overview_tables']=[]
        window.title(f'全部 {len(RANKINGS)} 項排行與學習狀態')
        window.geometry(f'{min(1120,top.winfo_screenwidth()-40)}x{min(500,top.winfo_screenheight()-90)}')
        app.ttk.Label(window,text='藍色＝已記住待驗證；綠色＝實測通過；紅色＝需重新校正。').pack(fill='x',padx=10,pady=8)
        grids=app.ttk.Frame(window);grids.pack(fill='both',expand=True,padx=8,pady=8)
        per_group=(len(RANKINGS)+3)//4
        for group in range(4):
            frame=app.ttk.Frame(grids);frame.pack(side='left',fill='both',expand=True,padx=3)
            tree=app.ttk.Treeview(frame,columns=('state',),height=per_group)
            tree.heading('#0',text='排行');tree.column('#0',width=140,minwidth=110)
            tree.heading('state',text='狀態');tree.column('state',width=100,minwidth=90)
            tree.tag_configure('learned',foreground='#138045');tree.tag_configure('current',foreground='#ad6200')
            tree.tag_configure('pending',foreground='#2166a5');tree.tag_configure('failed',foreground='#c93838')
            for i in range(group*per_group,min(len(RANKINGS),(group+1)*per_group)):
                tree.insert('','end',iid=RANKINGS[i],text=f'{i+1}. {RANKINGS[i]}')
            tree.pack(fill='both',expand=True);state['overview_tables'].append(tree)
        refresh_catalog()
    app.ttk.Button(catalog_box,text=f'展開全部 {len(RANKINGS)} 項',command=overview).pack(fill='x',pady=4)
    canvas=app.tk.Canvas(body,width=280,height=300,bg='#102c40');canvas.pack(side='left',fill='both',expand=True)

    def render():
        if state['source'] is None:return
        from PIL import ImageTk
        im=state['source'];point=state['point'];number=state['number']
        scale=min(max(1,canvas.winfo_width()-4)/im.width,max(1,canvas.winfo_height()-4)/im.height,1)
        state['scale']=scale;state['image']=ImageTk.PhotoImage(im.resize((max(1,round(im.width*scale)),max(1,round(im.height*scale)))))
        canvas.delete('all');canvas.create_image(0,0,anchor='nw',image=state['image'])
        if point:
            py=point[1]*scale
            canvas.create_rectangle(1,max(1,py-10),max(2,im.width*scale-2),py+10,outline='#ffd54f',width=3)
            canvas.create_text(max(50,im.width*scale-8),max(10,py-12),text='你選的位置'+(f'（第 {number} 項）' if number else ''),anchor='e',fill='#ffd54f')
    canvas.bind('<Configure>',lambda e:render())
    canvas.bind('<Button-1>',lambda e:send('select',(round(e.x/state['scale']),round(e.y/state['scale']))))
    canvas.bind('<MouseWheel>',lambda e:send('scroll',-1 if e.delta>0 else 1))
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
                    state['raw_recent']=list(dict.fromkeys(values+state['raw_recent']))[:100]
                    values=state['raw_recent']
                    raw_picker['values']=values
                    if raw_text.get() not in values:raw_text.set(values[0] if values else '')
                    ocr_detail.set('辨識原文：'+'、'.join(f'{t[1]} ({float(t[2]):.0%})' for t in data)[:180])
                elif kind=='target':
                    mapped.set(data);state['raw_recent']=[];state['current']=data
                    refresh_catalog();catalog.see(data)
                    target_text.set('現在請指定：'+data)
                    instruction.set('① 點選下方截圖中的「'+data+'」\n② 黃色框位置正確後，按「記住並下一項」。')
                elif kind=='checks':
                    state['checks']=data;refresh_catalog()
                elif kind=='learned':
                    state['learned']=data;refresh_catalog()
                elif kind=='step':
                    index,learned=data
                    counter.set(f'手動教學｜第 {index} / {len(RANKINGS)} 項｜本次已記住 {learned} 項')
                elif kind=='number':
                    n,value=data
                    numbers=app.config.setdefault('auto_config',{}).setdefault('menu_numbers',{})
                    for old in list(numbers):
                        if old!=n and numbers[old]==value:del numbers[old]
                    numbers[n]=value
                    try:app.save()
                    except OSError:status.set('位置已核對；設定檔寫入失敗，請檢查磁碟權限。')
                elif kind=='results':
                    for n,values in data.items():table.item(n,values=values)
                elif kind=='image':
                    state['source'],state['point'],state['number']=data
                    render();state['assisting']=True
                    confirm_button.configure(state='normal' if state['point'] is not None else 'disabled')
                    top.lift()
                elif kind=='done':
                    state['done']=True;state['assisting']=False;status.set(data);app.status.set(data);top.deiconify()
                    confirm_button.configure(state='disabled');refresh_catalog()
                    target_text.set('手動教學已完成' if mode=='manual' and data.startswith('全部') else '學習已停止')
                    instruction.set('已確認的對應已保存。可關閉此視窗開始掃描，或展開「辨識細節」執行自動測試。')
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
            teaching=TeachingMemory(BASE/'ranking-teaching-positions.json',experience.key)
            switcher.teaching=teaching
            updates.put(('checks',learned_checks(RANKINGS,teaching,experience)))
            updates.put(('learned',learned_positions(RANKINGS,teaching,experience)))
            def assist(name):
                from menu_numbers import NumberNavigator
                updates.put(('target',name))
                number=experience.number(name) or RANKINGS.index(name)+1
                page=0;point=None;rows=[]
                def show_rows(data):
                    nonlocal rows
                    rows=list(data.get('rows',[])) if data else []
                navigator=NumberNavigator(switcher,show=show_rows)
                def numbered(value):
                    nonlocal rows,page
                    page=0;rows=[]
                    image,area=replay_menu(switcher,0)
                    navigator.manual[name]=value
                    found=navigator.locate(name,area)
                    image,area=c.image(switcher.menu)
                    return image,area,found
                saved=teaching.get(name)
                if saved and saved['number'] is not None:number=saved['number']
                im,region,point=numbered(number)
                if saved and saved['number'] is None:
                    page=saved['page'];im,region=replay_menu(switcher,page);rows=[];number=None
                    point=tuple(saved['point']) if list(im.size)==saved['size'] else None
                if point is None:
                    number=None;page=0;rows=[]
                    im,region=replay_menu(switcher,0)
                    updates.put(('status','無法確認項目編號；請在截圖點選目標位置，可用滾輪捲動，再按記住並下一項。'))
                last_image=None
                while not stop.is_set():
                    if im is not last_image:switcher.ocr(im);last_image=im
                    updates.put(('image',(im,point,number)))
                    while not stop.is_set():
                        try:action,value=commands.get(timeout=.1);break
                        except queue.Empty:continue
                    if stop.is_set():return
                    if action in ('alias','remove'):
                        try:
                            if action=='alias':switcher.aliases.save(*value)
                            else:switcher.aliases.remove(value)
                            switcher.verified_title_pixels=None;switcher.verified_titles=set()
                            updates.put(('status','文字對應已更新；請確認黃色標記後按「記住並下一項」儲存位置。'))
                        except Exception as exc:updates.put(('status','對應未保存：'+str(exc)))
                        continue
                    if action=='select':
                        x,y=value
                        if not (0<=x<im.width and 0<=y<im.height):continue
                        old_point,old_number=point,number
                        point=(x,y);number=None
                        if rows:
                            candidate,cy=min(rows,key=lambda row:abs(row[1]-y))
                            pitch=min((abs(a[1]-b[1]) for a,b in zip(rows,rows[1:]) if a[1]!=b[1]),default=30)
                            if abs(cy-y)<=pitch*.5:number=candidate;point=(x,cy)
                            else:
                                point,number=old_point,old_number
                                updates.put(('status','請點選文字所在的一列；空白區域不會被記住。'));continue
                        updates.put(('status','已標記位置；可按上一個／下一個微調，確認後按「記住並下一項」。'))
                        continue
                    if action=='step':
                        if number is None:
                            updates.put(('status','目前無法確認列編號，請先在截圖點選可辨識的一列；不會任意跳動。'));continue
                        wanted=max(1,min(500,number+value))
                        if wanted==number:
                            updates.put(('status','已是第一個項目。'));continue
                        visible=next((cy for item,cy in rows if item==wanted),None)
                        if visible is not None:
                            number=wanted;point=(im.width*.45,visible)
                            updates.put(('status',f'已移至第 {number} 項；確認黃色標記後按記住並下一項。'))
                            continue
                        old=number
                        im,region,candidate=numbered(wanted)
                        if candidate is None:
                            im,region,point=numbered(old)
                            updates.put(('status','無法確認下一個位置，保留原選擇；請直接點選截圖。'))
                        else:
                            number=wanted;point=candidate
                            updates.put(('status',f'已移至第 {number} 項；確認黃色標記是「{name}」後按記住並下一項。'))
                        continue
                    if action=='scroll':
                        page=max(0,min(59,page+value));number=None;point=None;rows=[]
                        im,region=replay_menu(switcher,page)
                        updates.put(('status','選單畫面已捲動；請重新點選位置，再按記住並下一項。'));continue
                    if action!='confirm':continue
                    if point is None:
                        updates.put(('status','請先在截圖點選要學習的排行位置。'));continue
                    if number is not None:
                        now,region,confirmed=numbered(number)
                        if confirmed is None:
                            updates.put(('status','目前位置無法重現，尚未保存，請重新選擇。'));point=None;im=now;continue
                        point=confirmed
                    else:now,region=replay_menu(switcher,page)
                    if now.size!=im.size:
                        updates.put(('status','選單尺寸變動，請重新選擇位置再確定。'))
                        im=now;point=None;continue
                    try:teaching.remember(name,number,page,point,now.size)
                    except (OSError,ValueError) as exc:
                        updates.put(('status','位置未保存：'+str(exc)));im=now;continue
                    # The user has explicitly confirmed this correspondence. Keep
                    # it for scanning even when OCR cannot read the label yet.
                    if number is not None:
                        navigator.verified(name)
                        if switcher.numbers:
                            switcher.numbers.manual[name]=number;switcher.numbers.disabled.discard(name)
                        updates.put(('number',(name,number)))
                    else:
                        experience.record(name,page,point,switcher.menu_signature(now))
                        if switcher.numbers:switcher.numbers.disabled.add(name)
                    updates.put(('checks',learned_checks(RANKINGS,teaching,experience)))
                    updates.put(('learned',learned_positions(RANKINGS,teaching,experience)))
                    updates.put(('status','已記住「'+name+'」的人工對應位置，準備下一項。'))
                    return True
            if mode=='manual':
                learned=0
                for index,name in enumerate(RANKINGS,1):
                    if stop.is_set():break
                    updates.put(('step',(index,learned)))
                    updates.put(('status',f'請在下方截圖指出「{name}」的位置；選好後按記住並下一項。'))
                    if assist(name):
                        learned+=1;updates.put(('step',(min(index+1,len(RANKINGS)),learned)))
                updates.put(('done',f'全部 {learned} 項已由你確認並記住；尚未計為自動測試通過。' if learned==len(RANKINGS) else f'已保存本次確認的 {learned} 項，可稍後繼續教學。'))
            else:
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
                            updates.put(('checks',learned_checks(RANKINGS,teaching,experience)))
                            updates.put(('results',{n:(progress.streak[n],progress.attempts[n],progress.failures[n]) for n in RANKINGS}))
                            assist(name)
                        updates.put(('checks',learned_checks(RANKINGS,teaching,experience)))
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

