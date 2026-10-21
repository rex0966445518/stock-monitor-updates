"""Main header index and persistent daily candidate panel."""
import datetime as dt
import sys
import time
import sqlite3
from candidate_diary import CandidateDiary,TAIPEI
from market_index import MarketIndex,presentation

class DashboardUI:
    def build_market_header(self):
        from terminal_ui import header
        header(self)
    def build_daily_bottom(self):
        from MitakeDetector import BASE
        ttk=self.ttk;tk=self.tk
        bottom=ttk.Frame(self.ui_root);bottom.pack(fill='x',padx=18,pady=8)
        bottom.rowconfigure(0,weight=1)
        bottom.columnconfigure(0,weight=3,uniform='bottom');bottom.columnconfigure(1,weight=2,uniform='bottom')
        self.today_frame=ttk.LabelFrame(bottom,text='今日曾達5/6（轉弱仍保留）');self.today_frame.grid(row=0,column=0,sticky='nsew',padx=(0,8))
        columns=('code','name','first','price','latest','state')
        self.today_tree=ttk.Treeview(self.today_frame,columns=columns,show='headings',height=5)
        for key,label,width in zip(columns,['股號','股名','首次發現','首次價','最近有效價','後續狀態'],[65,100,90,80,100,160]):
            self.today_tree.heading(key,text=label);self.today_tree.column(key,width=width,stretch=key=='state')
        v=ttk.Scrollbar(self.today_frame,orient='vertical',command=self.today_tree.yview);v.pack(side='right',fill='y')
        h=ttk.Scrollbar(self.today_frame,orient='horizontal',command=self.today_tree.xview);h.pack(side='bottom',fill='x')
        self.today_tree.configure(yscrollcommand=v.set,xscrollcommand=h.set);self.today_tree.pack(fill='both',expand=True)
        self.today_tree.tag_configure('weak',foreground='#aebed3');self.today_tree.tag_configure('near',foreground='#ffd36a')
        self.today_tree.bind('<Double-1>',self.open_today_candidate)
        diagnostic=ttk.LabelFrame(bottom,text='掃描紀錄');diagnostic.grid(row=0,column=1,sticky='nsew')
        self.rate_label=tk.Label(diagnostic,textvariable=self.scan_rate_var,bg='#0a1322',fg='#68f5d1',anchor='w')
        self.rate_label.pack(side='bottom',fill='x')
        self.log=tk.Text(diagnostic,width=35,height=9,wrap='word',bg='#0a1322',fg='#a6b8d2',font=('Microsoft JhengHei',10),state='disabled')
        scroll=ttk.Scrollbar(diagnostic,orient='vertical',command=self.log.yview);self.log.configure(yscrollcommand=scroll.set)
        self.log.pack(side='left',fill='both',expand=True);scroll.pack(side='right',fill='y')
        # Keep detailed progress in the bounded diagnostic pane, never above the table.
        self.status.trace_add('write',lambda *_:self.say(self.status.get()))
        self.observation_summary.trace_add('write',lambda *_:self.say(self.observation_summary.get()))
        self.say(self.status.get())
        self.diary_store=CandidateDiary(BASE/'observations.sqlite3');self.daily_refresh=0
        return bottom
    def init_dashboard(self):
        self.index_service=MarketIndex(lambda payload:self.events.put((None,'market_index',payload)))
        if '--smoke-test' not in sys.argv:
            self.index_service.start()
            self.app_import_diary()
        self.refresh_today_candidates(force=True)
    def app_import_diary(self):
        import threading
        self.candidate_import_running=True
        def job():
            try:
                count=self.diary_store.import_archive()
                message=f'候選日誌已整理既有{count}筆讀值'
            except Exception as exc:message='候選舊紀錄整理暫停，可從日曆重試：'+str(exc)
            self.events.put((None,'research_diary',message))
        threading.Thread(target=job,daemon=True).start()
    def update_market_index(self,payload):
        if payload.get('quote'):
            q=payload['quote']
            if self.index_quote is None or q['ts']>=self.index_quote['ts']:self.index_quote=q
        self.index_error=payload.get('error','')
        self.refresh_index_label()
    def refresh_index_label(self):
        text,color=presentation(self.index_quote,self.index_error)
        if text!=self.market_var.get():self.market_var.set(text)
        if color!=self.market_label.cget('fg'):self.market_label.configure(fg=color)
    def refresh_today_candidates(self,force=False):
        if not force and time.monotonic()-getattr(self,'daily_refresh',0)<3:return
        if not hasattr(self,'today_tree'):return
        self.daily_refresh=time.monotonic();today=dt.datetime.now(TAIPEI).date().isoformat()
        try:rows=self.diary_store.stocks(today);flags=self.diary_store.latest_flags(today)
        except (sqlite3.Error,OSError):
            self.today_frame.configure(text='今日曾達5/6｜讀取暫時失敗，稍後自動重試')
            return
        selected=self.today_tree.selection();selected=self.today_tree.item(selected[0],'values')[0] if selected else None
        self.today_tree.delete(*self.today_tree.get_children())
        for code,name,first,price,last,latest,matched,total,reason,peak,n in rows:
            stamp,blocked,last_reason,_,_=flags.get(code,(last,False,reason,matched,total))
            stale=last is None or time.time()-last>60
            weak='已轉弱' in (reason or '') or (matched is not None and total and matched<5)
            state=('待複查｜' if blocked or stale else '已轉弱｜' if weak else '')+f'曾{peak}/6 → '+(f'{matched}/{total}' if total else '待複查')
            item=self.today_tree.insert('','end',values=[code,name or '股名待補',dt.datetime.fromtimestamp(first,TAIPEI).strftime('%H:%M:%S'),f'{price:.2f}','—' if latest is None else f'{latest:.2f}',state],tags=('weak',) if weak or blocked or stale else ('near',))
            if code==selected:self.today_tree.selection_set(item)
        if hasattr(self,'display_scale'):self.display_scale.fit_tables()
        self.today_frame.configure(text=f'今日曾達5/6｜{today[5:]}｜{len(rows)}檔（轉弱保留，雙擊明細）')
    def open_today_candidate(self,event=None):
        items=self.today_tree.selection()
        if not items:return
        code=str(self.today_tree.item(items[0],'values')[0]);self.show_candidate_diary()
        view=self.candidate_diary_view;view.choose(dt.datetime.now(TAIPEI).date().isoformat())
        for item in view.tree.get_children():
            if str(view.tree.item(item,'values')[0])==code:view.tree.selection_set(item);view.select();break
