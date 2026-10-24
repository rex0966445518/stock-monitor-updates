"""Navy/gold terminal presentation. Reads existing scan state, never runs OCR."""
import datetime as dt
import time

BG='#071827'
PANEL='#0c2236'
GOLD='#e5bf73'
MUTED='#9fb6ca'
RED='#ff5269'
GREEN='#37d6a5'


def theme(style):
    style.configure('.',background=BG,foreground='#e4edf6')
    style.configure('TFrame',background=BG)
    style.configure('TButton',background='#173650',foreground='#e4edf6',bordercolor='#33536b',lightcolor='#33536b',darkcolor='#33536b',borderwidth=1,padding=(14,9))
    style.map('TButton',background=[('active','#244b68'),('pressed','#315b73')])
    style.configure('Gold.TButton',background=GOLD,foreground='#172536',font=('Microsoft JhengHei',11,'bold'))
    style.map('Gold.TButton',background=[('active','#f3d698'),('pressed','#cda65c')],foreground=[('active','#172536')])
    style.configure('TMenubutton',background='#173650',padding=(10,8))
    style.configure('TLabelframe',background=PANEL,bordercolor='#29465e',relief='solid',borderwidth=1)
    style.configure('TLabelframe.Label',background=BG,foreground='#e4edf6',font=('Microsoft JhengHei',11,'bold'))
    style.configure('TNotebook',background=BG,borderwidth=0)
    style.configure('TNotebook.Tab',background=BG,foreground=MUTED,padding=(14,7))
    style.map('TNotebook.Tab',background=[('selected','#19394f')],foreground=[('selected',GOLD)])
    style.configure('Treeview',background=PANEL,fieldbackground=PANEL,foreground='#e4edf6',borderwidth=0)
    style.configure('Treeview.Heading',background='#112c43',foreground='#bfd0df',relief='flat',padding=(6,8))
    style.map('Treeview',background=[('selected','#4c4939')],foreground=[('selected','#ffe4a4')])
    style.configure('Horizontal.TProgressbar',background=GREEN,troughcolor='#1a3851',borderwidth=0,lightcolor=GREEN,darkcolor=GREEN)


def header(app):
    from MitakeDetector import VERSION
    tk,ttk=app.tk,app.ttk
    app.header=tk.Frame(app.ui_root,bg=BG);app.header.pack(fill='x',padx=20,pady=(14,10))
    app.header.columnconfigure(1,weight=1)
    app.product_title=tk.Label(app.header,text='漲停板探測器',fg=GOLD,bg=BG,font=('Microsoft JhengHei',24,'bold'),anchor='w')
    app.product_title.grid(row=0,column=0,sticky='w')
    tk.Label(app.header,text='雷允澤製 · v'+VERSION,fg=MUTED,bg=BG,font=('Microsoft JhengHei',10)).grid(row=1,column=0,sticky='w')
    nav=ttk.Frame(app.header);app.header_navigation=nav;nav.grid(row=1,column=1,columnspan=2,padx=8,pady=(8,0),sticky='e')
    for label,command in [('候選歷史',app.show_candidate_diary),('條件設定',app.show_guard_settings),('一鍵更新',app.check_update)]:
        ttk.Button(nav,text=label,command=command).pack(side='left',padx=4)
    app.taiyo_var=tk.StringVar(value='太陽誘電 6976\n行情待取得')
    app.taiyo_label=tk.Label(app.header,textvariable=app.taiyo_var,bg=BG,fg=MUTED,font=('Microsoft JhengHei',11,'bold'),justify='right',anchor='e',wraplength=245)
    app.taiyo_label.grid(row=0,column=1,sticky='e',padx=(12,18))
    qr_box=tk.Frame(app.header,bg=BG);qr_box.grid(row=0,column=3,rowspan=2,sticky='e',padx=(16,0))
    app.header_qr=tk.Label(qr_box,text='正在準備配對碼',bg=BG,fg=MUTED,cursor='hand2');app.header_qr.pack()
    app.header_qr.bind('<Button-1>',lambda event:app.show_mobile_pairing())
    app.header_qr_status=tk.StringVar(value='手機掃碼連線')
    tk.Label(qr_box,textvariable=app.header_qr_status,bg=BG,fg=MUTED,font=('Microsoft JhengHei',9)).pack()
    app.market_var=tk.StringVar(value='台股加權指數｜行情待取得')
    app.market_label=tk.Label(app.header,textvariable=app.market_var,bg=BG,fg=MUTED,font=('Microsoft JhengHei',11,'bold'),justify='right',anchor='e',wraplength=280)
    app.market_label.grid(row=0,column=2,sticky='e')
    app.header_status=tk.Frame(app.ui_root,bg=PANEL)
    app.index_quote=None;app.index_error=''


def metrics(app,bar):
    tk=app.tk
    app.metric_vars={}
    for key,title in [('scanned','已掃描股數'),('near','5/6 候選'),('hit','6/6 候選'),('seconds','讀取耗時')]:
        card=tk.Frame(bar,bg=PANEL,highlightthickness=1,highlightbackground='#29465e')
        card.pack(side='left',fill='both',expand=True,padx=5)
        tk.Label(card,text=title,bg=PANEL,fg=MUTED,font=('Microsoft JhengHei',10)).pack(padx=12,pady=(6,0))
        var=tk.StringVar(value='—' if key=='seconds' else '0');app.metric_vars[key]=var
        tk.Label(card,textvariable=var,bg=PANEL,fg=GOLD if key in ('near','hit') else '#edf4ff',font=('Microsoft JhengHei',21,'bold')).pack(padx=12,pady=(0,5))


def refresh(app):
    if time.monotonic()-getattr(app,'_terminal_refresh',0)<1:return
    app._terminal_refresh=time.monotonic()
    from candidate_ranking import assess
    rows=getattr(app,'display_rows',[]);cfg=getattr(app,'display_cfg',app.config)
    scores=[assess(row,cfg) for row in rows]
    app.metric_vars['scanned'].set(str(len({r[0].code for r in rows})))
    for key,n in [('near',5),('hit',6)]:app.metric_vars[key].set(str(sum(s['count']==n and s['total']==6 for s in scores)))
    rate=app.scan_rate
    seconds=getattr(rate,'last_seconds',None)
    app.metric_vars['seconds'].set(f'{seconds:.1f} 秒' if seconds is not None else '—')
    active=app.auto_active or bool(app.worker_thread and app.worker_thread.is_alive())
    if active!=getattr(app,'_progress_active',False):
        app._progress_active=active
        if active:app.scan_progress.start(18)
        else:app.scan_progress.stop()
    config=app.config.get('auto_config') or {}
    coords='欄位座標已記憶' if config.get('column_bounds') else '欄位座標待設定'
    numbering='數字定位已啟用' if config.get('menu_numbering',True) else '數字定位未啟用'
    app.terminal_status.set(coords+'   ｜   '+numbering+'   ｜   '+dt.datetime.now().strftime('%H:%M:%S'))
    app.focus_chart.refresh()


class FocusChart:
    """Selected-stock panel reusing the existing asynchronous chart cache/service."""
    def __init__(self,app,parent):
        self.app=app;self.code=None;self.key=None;self.photo=None
        tk,ttk=app.tk,app.ttk
        self.frame=parent
        self.title=tk.StringVar(value='選取股票查看圖表')
        self.price=tk.StringVar(value='—')
        heading=tk.Frame(parent,bg=PANEL);heading.pack(fill='x',padx=12,pady=(12,6))
        tk.Label(heading,textvariable=self.title,bg=PANEL,fg=GOLD,font=('Microsoft JhengHei',16,'bold'),anchor='w').pack(side='left')
        self.price_label=tk.Label(heading,textvariable=self.price,bg=PANEL,fg=RED,font=('Microsoft JhengHei',16,'bold'));self.price_label.pack(side='right')
        self.mode=tk.StringVar(value='5 分 K')
        bar=ttk.Frame(parent);bar.pack(fill='x',padx=12,pady=5)
        for name in ('5 分 K','日 K','日 MACD'):
            ttk.Radiobutton(bar,text=name,variable=self.mode,value=name,command=self.invalidate).pack(side='left',padx=4)
        ttk.Button(bar,text='放大',command=self.enlarge).pack(side='right')
        ttk.Button(bar,text='重試',command=self.retry).pack(side='right')
        ttk.Button(bar,text='原站',command=self.open_source).pack(side='right')
        self.note=tk.StringVar(value='等待掃描資料；圖表在背景載入')
        tk.Label(parent,textvariable=self.note,bg=PANEL,fg=MUTED,wraplength=400,anchor='w',justify='left',font=('Microsoft JhengHei',9)).pack(side='bottom',fill='x',padx=12,pady=8)
        self.volume=tk.Canvas(parent,height=80,bg=PANEL,highlightthickness=0);self.volume.pack(side='bottom',fill='x',padx=10,pady=(0,4))
        self.canvas=tk.Canvas(parent,width=400,height=240,bg=PANEL,highlightthickness=0);self.canvas.pack(fill='both',expand=True,padx=10,pady=4)
        self.canvas.bind('<Configure>',lambda e:self.invalidate())
        self.canvas.bind('<Button-1>',lambda e:self.enlarge())
        self.volume.bind('<Configure>',lambda e:self.invalidate())
    def open_source(self):
        if not self.code:return
        from candidate_charts import source_links
        import webbrowser,threading
        data=self.app.candidate_charts.web_cache.get(self.code,{})
        from investing_chart import safe_chart_url
        url=data.get('url','')
        if not safe_chart_url(url):url=source_links(self.code)[0]
        threading.Thread(target=webbrowser.open,args=(url,),daemon=True).start()
    def retry(self):
        if self.code:self.app.candidate_charts.retry_web(self.code)
        self.invalidate()
    def invalidate(self):self.key=None;self.refresh()
    def select(self,event=None):
        selection=self.app.tree.selection()
        if selection:self.code=str(self.app.tree.item(selection[0],'values')[0])
        self.invalidate()
    def enlarge(self):
        if not self.code:return
        if self.mode.get()=='5 分 K':
            self.app.candidate_charts.enlarge(self.code);return
        top=self.app.tk.Toplevel(self.app.root);top.title(self.title.get()+' · '+self.mode.get())
        top.geometry('1000x700')
        panel=FocusChart(self.app,top);panel.code=self.code;panel.mode.set(self.mode.get());panel.refresh()
    def refresh(self):
        rows=getattr(self.app,'display_rows',[])
        by_code={r[0].code:r[0] for r in rows}
        if self.code not in by_code:self.code=next(iter(by_code),None)
        service=self.app.candidate_charts
        if not self.code:
            self.title.set('選取股票查看圖表');self.price.set('—');self.canvas.delete('all');self.volume.delete('all')
            self.canvas.create_text(max(120,self.canvas.winfo_width()/2),100,text='等待掃描結果',fill=MUTED,font=('Microsoft JhengHei',13));return
        q=by_code[self.code];self.title.set(f'{q.code}  {q.name or "股名待補"}');self.price.set(f'{q.price:,.2f}')
        self.price_label.configure(fg=RED if q.gain>=0 else GREEN)
        service.request(self.code)
        if self.mode.get()=='5 分 K' and '--smoke-test' not in __import__('sys').argv:
            now=time.monotonic()
            if now-service.last_requests.get(self.code,-1000)>=300:
                service.last_requests[self.code]=now;service.web_service.request(self.code)
        data=service.cache.get(self.code,{})
        web=service.web_cache.get(self.code,{})
        if web.get('loading') and time.monotonic()-web.get('loading_since',time.monotonic())>120:
            web=dict(web,loading=False,error='圖表等待逾時，請按重試；股票掃描繼續執行')
            service.web_cache[self.code]=web
        key=(self.code,self.mode.get(),data.get('read_at'),str(web),self.canvas.winfo_width(),self.canvas.winfo_height())
        if key==self.key:return
        self.key=key;self.canvas.delete('all');self.volume.delete('all')
        if self.mode.get()=='5 分 K':
            self.volume.pack_forget()
            self.note.set('來源：Investing.com｜五分 K 快照；點圖可放大')
            if web.get('error'):self.message(web['error']);return
            if not web.get('directory'):self.message('五分 K 背景載入中…');return
            try:
                from PIL import Image,ImageTk
                from pathlib import Path
                with Image.open(Path(web['directory'])/'full.png') as source:
                    im=source.copy();im.thumbnail((max(100,self.canvas.winfo_width()-8),max(100,self.canvas.winfo_height()-8)))
                self.photo=ImageTk.PhotoImage(im,master=self.canvas)
                self.canvas.create_image(self.canvas.winfo_width()/2,self.canvas.winfo_height()/2,image=self.photo)
                self.note.set(web.get('source','Investing.com')+'｜五分 K｜'+web.get('data_time','時間待核對')+'｜點圖放大')
            except (OSError,ValueError) as error:self.message('圖表暫時無法讀取：'+str(error))
            return
        self.volume.pack(side='bottom',fill='x',padx=10,pady=(0,4),before=self.canvas)
        rows=data.get('rows',[])
        if not rows:self.message(data.get('error') or '日線快取不足；請啟用籌碼研究等待背景更新');return
        self.note.set('官方日線快取截至 '+rows[-1]['day']+'｜非即時行情')
        recent=rows[-60:]
        c=self.canvas;w=max(180,c.winfo_width());h=max(140,c.winfo_height());left=48;right=w-16
        def axes(lo,hi):
            span=max(hi-lo,.01)
            for i in range(5):
                yy=20+i*(h-44)/4;c.create_line(left,yy,right,yy,fill='#1d3b51')
                c.create_text(left-5,yy,text=f'{hi-i*span/4:.2f}',anchor='e',fill=MUTED,font=('Arial',8))
            return lambda v:20+(hi-v)/span*(h-44)
        step=(right-left)/len(recent);x=lambda i:left+(i+.5)*step
        if self.mode.get()=='日 K':
            y=axes(min(r['low'] for r in recent),max(r['high'] for r in recent))
            for i,r in enumerate(recent):
                color=RED if r['close']>=r['open'] else GREEN
                c.create_line(x(i),y(r['low']),x(i),y(r['high']),fill=color)
                a,b=sorted((y(r['open']),y(r['close'])))
                c.create_rectangle(x(i)-step*.32,a,x(i)+step*.32,max(a+1,b),fill=color,outline='')
        else:
            from candidate_charts import macd
            if len(rows)<34:self.message('MACD 至少需要 34 筆日線');return
            values=macd(rows)[-60:];nums=[v for triple in values for v in triple if v is not None]+[0];y=axes(min(nums),max(nums))
            for i,(_,_,v) in enumerate(values):
                if v is not None:c.create_rectangle(x(i)-step*.3,y(v),x(i)+step*.3,y(0),fill=RED if v>=0 else GREEN,outline='')
            for col,color in [(0,GOLD),(1,'#6bb7ec')]:
                points=[a for i,v in enumerate(values) if v[col] is not None for a in (x(i),y(v[col]))]
                if len(points)>=4:c.create_line(*points,fill=color,width=2)
        c.create_text(left,h-8,text=recent[0]['day'],anchor='w',fill=MUTED,font=('Arial',8));c.create_text(right,h-8,text=recent[-1]['day'],anchor='e',fill=MUTED,font=('Arial',8))
        vc=self.volume;vw=max(180,vc.winfo_width());maxv=max(1,max(r['volume'] for r in recent));step=(vw-60)/len(recent)
        vc.create_text(4,8,text='日成交量',anchor='w',fill=MUTED,font=('Microsoft JhengHei',9))
        for i,r in enumerate(recent):
            px=48+(i+.5)*step;vc.create_rectangle(px-step*.32,72-r['volume']/maxv*50,px+step*.32,74,fill=RED if r['close']>=r['open'] else GREEN,outline='')
    def message(self,text):
        self.canvas.create_text(max(100,self.canvas.winfo_width()/2),max(70,self.canvas.winfo_height()/2),text=text,fill=MUTED,width=max(150,self.canvas.winfo_width()-40),justify='center')


def shell(app):
    """Scroll the workspace at high zoom instead of clipping controls or text."""
    tk,ttk=app.tk,app.ttk
    host=ttk.Frame(app.root);host.pack(fill='both',expand=True)
    host.rowconfigure(0,weight=1);host.columnconfigure(0,weight=1)
    canvas=tk.Canvas(host,bg=BG,highlightthickness=0)
    canvas.grid(row=0,column=0,sticky='nsew')
    vertical=ttk.Scrollbar(host,orient='vertical',command=canvas.yview);vertical.grid(row=0,column=1,sticky='ns')
    horizontal=ttk.Scrollbar(host,orient='horizontal',command=canvas.xview);horizontal.grid(row=1,column=0,sticky='ew')
    canvas.configure(yscrollcommand=vertical.set,xscrollcommand=horizontal.set)
    app.ui_root=tk.Frame(canvas,bg=BG)
    window=canvas.create_window(0,0,window=app.ui_root,anchor='nw')
    def resize(event=None):
        scale=getattr(getattr(app,'display_scale',None),'percent',100)/100
        width=max(canvas.winfo_width(),round(1380*scale));height=max(canvas.winfo_height(),round(850*scale))
        canvas.itemconfigure(window,width=width,height=height)
        canvas.configure(scrollregion=(0,0,width,height))
    app.fit_terminal=resize
    canvas.bind('<Configure>',resize)
    return app.ui_root
