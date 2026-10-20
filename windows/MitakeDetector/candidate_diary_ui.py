"""Calendar diary, independent of the OCR worker and its focus lifecycle."""
import calendar
import datetime as dt
import json
import time
from candidate_diary import CandidateDiary,TAIPEI

class DiaryView:
    def __init__(self,app,path):
        self.app=app;self.store=CandidateDiary(path);self.importing=False;self.last_refresh=0;self.current_code=None;self.before=None
        self.window=app.tk.Toplevel(app.root);self.window.title('5/6候選歷史日誌');self.window.geometry('1300x850')
        today=dt.datetime.now(TAIPEI).date();self.month=today.replace(day=1)
        self.day=app.tk.StringVar(value=today.isoformat());self.status=app.tk.StringVar()
        app.ttk.Label(self.window,text='收集六項條件中符合5項或6項的有效觀測，並保留入選後的轉弱／待複查紀錄；不是實際買賣紀錄。舊批次資料按原設定重新計數，無原始資料的日期不補造。',wraplength=1220).pack(fill='x',padx=12,pady=8)
        bar=app.ttk.Frame(self.window);bar.pack(fill='x',padx=12)
        app.ttk.Button(bar,text='上個月',command=lambda:self.move(-1)).pack(side='left')
        self.month_label=app.ttk.Label(bar);self.month_label.pack(side='left',padx=12)
        app.ttk.Button(bar,text='下個月',command=lambda:self.move(1)).pack(side='left')
        app.ttk.Entry(bar,textvariable=self.day,width=13).pack(side='left',padx=12)
        app.ttk.Button(bar,text='查詢日期',command=self.choose_date).pack(side='left')
        app.ttk.Button(bar,text='今天',command=lambda:self.choose(today.isoformat())).pack(side='left',padx=8)
        app.ttk.Button(bar,text='整理既有掃描紀錄',command=self.import_old).pack(side='left')
        body=app.ttk.Frame(self.window);body.pack(fill='both',expand=True,padx=12,pady=10)
        self.calendar=app.ttk.Frame(body);self.calendar.pack(side='left',anchor='n',padx=(0,12))
        table=app.ttk.Frame(body);table.pack(side='left',fill='both',expand=True)
        columns=('code','name','first','price','last','latest','peak','current','n')
        self.tree=app.ttk.Treeview(table,columns=columns,show='headings',height=12)
        for key,title,width in zip(columns,['股號','股名','首次發現','首次價','最近有效讀取','最近價','最高符合','最近符合','觀測筆數'],[65,105,100,80,115,80,85,85,85]):
            self.tree.heading(key,text=title);self.tree.column(key,width=width,stretch=False)
        v=app.ttk.Scrollbar(table,orient='vertical',command=self.tree.yview);v.pack(side='right',fill='y')
        h=app.ttk.Scrollbar(table,orient='horizontal',command=self.tree.xview);h.pack(side='bottom',fill='x')
        self.tree.configure(yscrollcommand=v.set,xscrollcommand=h.set);self.tree.pack(fill='both',expand=True)
        self.tree.bind('<<TreeviewSelect>>',self.select)
        app.ttk.Label(self.window,textvariable=self.status,wraplength=1240).pack(fill='x',padx=12)
        app.ttk.Button(self.window,text='載入較早的200筆',command=lambda:self.details(older=True)).pack(anchor='w',padx=12)
        from tkinter.scrolledtext import ScrolledText
        self.text=ScrolledText(self.window,wrap='word',height=16);self.text.pack(fill='both',expand=True,padx=12,pady=8)
        self.choose(today.isoformat())
    def move(self,direction):
        index=self.month.year*12+self.month.month-1+direction;y,m=divmod(index,12)
        self.month=dt.date(y,m+1,1);self.draw_calendar()
    def draw_calendar(self):
        for child in self.calendar.winfo_children():child.destroy()
        app=self.app;counts=self.store.days(self.month.strftime('%Y-%m'));self.month_label.configure(text=f'{self.month.year:04d}年{self.month.month:02d}月')
        for i,label in enumerate('一二三四五六日'):app.ttk.Label(self.calendar,text=label,anchor='center').grid(row=0,column=i,sticky='ew')
        for r,week in enumerate(calendar.monthcalendar(self.month.year,self.month.month),1):
            for col,number in enumerate(week):
                if not number:continue
                day=self.month.replace(day=number).isoformat();count=counts.get(day,0)
                button=app.tk.Button(self.calendar,text=f'{number}\n{count}檔' if count else str(number),width=5,height=2,command=lambda d=day:self.choose(d),
                                     bg='#1e6d55' if count else '#172944',fg='#ffffff',relief='solid',bd=2 if day==self.day.get() else 1)
                button.grid(row=r,column=col,padx=1,pady=1,sticky='nsew')
    def choose_date(self):
        try:self.choose(dt.date.fromisoformat(self.day.get()).isoformat())
        except ValueError:self.status.set('日期格式請填YYYY-MM-DD')
    def choose(self,day):
        self.day.set(day);self.month=dt.date.fromisoformat(day).replace(day=1);self.current_code=None;self.before=None
        self.text.delete('1.0','end');self.refresh(force=True)
    def refresh(self,force=False):
        if not self.window.winfo_exists() or (not force and time.monotonic()-self.last_refresh<3):return
        self.last_refresh=time.monotonic();self.draw_calendar();selected=self.current_code
        self.tree.delete(*self.tree.get_children())
        rows=self.store.stocks(self.day.get())
        def clock(ts):return dt.datetime.fromtimestamp(ts,TAIPEI).strftime('%H:%M:%S') if ts is not None else '—'
        for code,name,first,price,last,latest,matched,total,reason,peak,n in rows:
            item=self.tree.insert('','end',values=[code,name or '股名待補',clock(first),f'{price:.2f}',clock(last),'—' if latest is None else f'{latest:.2f}',f'{peak}/6',f'{matched}/{total}' if total else '—',n])
            if code==selected:self.tree.selection_set(item)
        self.status.set(f'{self.day.get()}｜曾達5/6或6/6：{len(rows)}檔｜日曆綠色日期有紀錄；需啟用全部六項條件才會新收集5/6。' + ('｜正在整理舊紀錄' if self.importing else ''))
    def select(self,event=None):
        items=self.tree.selection()
        if not items:return
        code=str(self.tree.item(items[0],'values')[0])
        if code==self.current_code:return
        self.current_code=code;self.before=None;self.details()
    def details(self,older=False):
        if not self.current_code:return
        if not older:self.before=None;self.text.delete('1.0','end')
        rows=self.store.events(self.day.get(),self.current_code,self.before)
        for id,ts,price,gain,volume,turnover,ratio,matched,total,hit,blocked,reason,source,version,body in rows:
            self.text.insert('end',f'{dt.datetime.fromtimestamp(ts,TAIPEI):%H:%M:%S}｜{self.current_code}｜{matched}/{total}｜價格{price:.2f}｜漲幅{gain:+.2f}%｜總量{volume:,.0f}張｜換手{turnover}｜量速{ratio}\n{source}｜v{version}｜'+('待複查' if blocked else '檢核通過' if hit else '觀察／未通過全部檢核')+'\n'+reason+'\n當時設定：'+body+'\n\n')
        if rows:self.before=rows[-1][0]
        else:self.text.insert('end','沒有更早的紀錄。\n')
    def import_old(self):
        if self.importing or getattr(self.app,'candidate_import_running',False):return
        import threading
        self.app.candidate_import_running=True
        self.importing=True;self.status.set('正在背景整理既有批次紀錄；掃描照常執行…')
        def job():
            try:
                n=self.store.import_archive();message=f'已檢查{n}筆既有讀值，舊紀錄按原設定重新計數'
            except Exception as exc:message='整理失敗，可重試：'+str(exc)
            self.app.events.put((None,'research_diary',message))
        threading.Thread(target=job,daemon=True).start()
