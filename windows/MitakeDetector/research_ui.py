"""Research view keeps dated evidence separate from live six-condition counts."""
import datetime as dt
import json
import sys


class ResearchUI:
    def init_research(self):
        from MitakeDetector import BASE
        from research_service import ResearchService
        self.research_reports={}
        self.research_status=self.tk.StringVar(value='籌碼研究待取得；不是盤中買入確認')
        self.research_service=ResearchService(BASE,lambda kind,payload:self.events.put((None,kind,payload)))
    def research_codes(self):
        return list(dict.fromkeys(q.code for q,_,_,_ in getattr(self,'display_rows',[])))
    def schedule_research(self):
        if '--smoke-test' in sys.argv:return
        self.research_service.request(self.research_codes())
    def research_event(self,kind,payload):
        if kind=='research_status':self.research_status.set(payload)
        elif kind=='research_results':
            self.research_reports.update(payload)
            if hasattr(self,'tree'):self.draw_results()
            self.refresh_research_view()
    def refresh_research_view(self):
        tree=getattr(self,'research_tree',None)
        if tree is None or not tree.winfo_exists():return
        selected=tree.selection();chosen=tree.item(selected[0],'values')[0] if selected else None
        for item in tree.get_children():tree.delete(item)
        order={'日線轉強確認（非買入指令）':0,'放量突破／等待回測':1,'疑似吸籌觀察':2,'一般觀察／證據未齊':3,'籌碼轉弱':4,'失效／跌破日線支撐':5,'資料不足／持續蒐集':6}
        reports=sorted(self.research_reports.values(),key=lambda r:(order.get(r['stage'],9),r['code']))
        for r in reports:
            item=tree.insert('','end',values=[r['code'],r['name'],r['stage'],r['asof'],r['weekly_asof'],r['insti_asof'],'—' if r['large_change'] is None else f"{r['large_change']:+.2f}",'—' if r['foreign'] is None else f"{r['foreign']/1000:+,.0f}",'—' if r['trust'] is None else f"{r['trust']/1000:+,.0f}",';'.join(r['missing'])])
            if r['code']==chosen:tree.selection_set(item)
        if chosen:self.show_research_details(chosen)
    def show_research_details(self,code):
        r=self.research_reports.get(code)
        if not r:return
        text=getattr(self,'research_details',None)
        if text is None or not text.winfo_exists():return
        lines=[f"{code} {r['name']}｜{r['stage']}",f"收盤日期 {r['asof']}｜集保 {r['weekly_asof']}｜法人 {r['insti_asof']}",
               '缺資料：'+('；'.join(r['missing']) or '核心期數已齊；仍須核對企業行動與公告'),
               *r['evidence'],*r['warnings'],
               '最近交易日均價：'+('—' if r['day_vwap'] is None else f"{r['day_vwap']:.2f}（全日成交金額／股數，非主力成本、非即時VWAP）"),
               '相對大盤近5交易日變化：'+('—' if r['relative'] is None else f"{r['relative']:+.2f}百分點"),
               '初始實驗規則：3期大額占比連增且小額占比下降；外資或投信5日淨買且至少3日買超。日線放量突破後，另待量縮回測及重新走高。未驗證勝率。',
               '週資料未滿3期時持續累積；可匯入官方歷史CSV。重大訊息沒有列出，不代表已排除全部風險。']
        for notice in r['news']:lines.append('公告 '+notice['day']+' '+notice.get('title',''))
        for kind in ['weeks','insti']:
            lines.append(('集保期數明細' if kind=='weeks' else '法人日資料明細')+'：')
            for fact in r[kind]:lines.append(json.dumps(fact,ensure_ascii=False))
        lines.extend(['來源：集保／證交所／櫃買官方公開資料；來源日期與擷取時間分開保存。'])
        text.configure(state='normal');text.delete('1.0','end');text.insert('1.0','\n'.join(lines));text.configure(state='disabled')
    def show_research(self):
        top=self.tk.Toplevel(self.root);top.title('籌碼觀察與日線轉強研究');top.geometry('1250x800')
        self.ttk.Label(top,text='盤後／週資料研究；不能辨認特定大戶，亦非安全買點。EPS、現金流及產業比較尚未納入。',wraplength=1150).pack(fill='x',padx=12,pady=8)
        enabled=self.tk.BooleanVar(value=self.config.get('research_enabled',True))
        def toggle():
            self.config['research_enabled']=enabled.get();self.save()
        self.ttk.Checkbutton(top,text='掃描時自動背景追蹤（網路工作不阻塞OCR）',variable=enabled,command=toggle).pack(anchor='w',padx=12)
        bar=self.ttk.Frame(top);bar.pack(fill='x',padx=12)
        code_var=self.tk.StringVar()
        self.ttk.Label(bar,text='股號').pack(side='left');self.ttk.Entry(bar,textvariable=code_var,width=8).pack(side='left',padx=5)
        def request_one():
            code=code_var.get().strip()
            if len(code)==4 and code.isdigit():self.research_service.request([code],force=True)
        self.ttk.Button(bar,text='更新此股',command=request_one).pack(side='left')
        self.ttk.Button(bar,text='更新目前清單',command=lambda:self.research_service.request(self.research_codes(),force=True)).pack(side='left',padx=6)
        self.ttk.Button(bar,text='匯入歷史集保CSV',command=self.import_weekly_research).pack(side='left',padx=6)
        self.ttk.Button(bar,text='來源更新狀態',command=self.show_research_sources).pack(side='left',padx=6)
        self.ttk.Label(top,textvariable=self.research_status,wraplength=1150).pack(fill='x',padx=12,pady=5)
        frame=self.ttk.Frame(top);frame.pack(fill='both',expand=True,padx=12)
        columns=('code','name','stage','date','weekly','insti','large','foreign','trust','missing')
        tree=self.ttk.Treeview(frame,columns=columns,show='headings',height=9);self.research_tree=tree
        for key,title in zip(columns,['股號','股名','研究階段','收盤日期','集保日期','法人日期','大額占比變化百分點','外資5日張','投信5日張','缺資料／阻擋原因']):
            tree.heading(key,text=title);tree.column(key,width=120 if key not in ('stage','missing') else 250,stretch=False)
        v=self.ttk.Scrollbar(frame,orient='vertical',command=tree.yview);v.pack(side='right',fill='y')
        h=self.ttk.Scrollbar(frame,orient='horizontal',command=tree.xview);h.pack(side='bottom',fill='x')
        tree.configure(yscrollcommand=v.set,xscrollcommand=h.set);tree.pack(fill='both',expand=True)
        from tkinter.scrolledtext import ScrolledText
        self.research_details=ScrolledText(top,wrap='word',height=13,bg='#101a2c',fg='#e4eaf5',state='disabled');self.research_details.pack(fill='both',expand=True,padx=12,pady=8)
        def select(event=None):
            items=tree.selection()
            if items:self.show_research_details(tree.item(items[0],'values')[0])
        tree.bind('<<TreeviewSelect>>',select)
        self.refresh_research_view()
        if '--smoke-test' not in sys.argv:self.research_service.request(self.research_codes())
        return top
    def show_research_sources(self):
        top=self.tk.Toplevel(self.root);top.title('研究來源更新狀態');top.geometry('900x600')
        from tkinter.scrolledtext import ScrolledText
        text=ScrolledText(top,wrap='word');text.pack(fill='both',expand=True,padx=12,pady=10)
        for source,stamp,status in self.research_service.store.statuses():text.insert('end',f'{source}｜擷取 {dt.datetime.fromtimestamp(stamp):%Y-%m-%d %H:%M:%S}｜{status}\n')
        text.configure(state='disabled');return top
    def import_weekly_research(self):
        from tkinter import filedialog
        paths=filedialog.askopenfilenames(filetypes=[('集保官方CSV','*.csv')])
        if not paths:return
        import threading
        codes=self.research_codes() or list(self.research_reports)
        def job():
            from pathlib import Path
            from research_sources import weekly
            try:
                for path in paths:self.research_service.store.put(weekly(Path(path).read_text('utf-8-sig')),'使用者匯入官方格式CSV：'+Path(path).name)
                self.events.put((None,'research_results',{code:self.research_service.store.report(code) for code in codes}))
                self.events.put((None,'research_status',f'已匯入{len(paths)}份集保資料；原始日期保留'))
            except Exception as exc:self.events.put((None,'research_status','匯入失敗：'+str(exc)))
        threading.Thread(target=job,daemon=True).start()
