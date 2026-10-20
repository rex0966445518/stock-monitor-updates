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
        self.research_service.enabled=self.config.get('research_enabled',True)
        if '--smoke-test' not in sys.argv and self.research_service.enabled:
            self.root.after(3000,lambda:self.research_service.request(self.research_service.watch))
    def research_codes(self):
        tree=getattr(self,'tree',None)
        if tree is not None and tree.winfo_exists():
            return [str(tree.item(item,'values')[0]) for item in tree.get_children()]
        return list(dict.fromkeys(q.code for q,_,_,_ in getattr(self,'display_rows',[])))
    def schedule_research(self):
        if '--smoke-test' in sys.argv:return
        self.research_service.request(self.research_codes())
    def research_event(self,kind,payload):
        if kind=='research_status':self.research_status.set(payload)
        elif kind=='research_diary':
            self.candidate_import_running=False
            diary=getattr(self,'candidate_diary_view',None)
            if diary and diary.window.winfo_exists():
                diary.importing=False;diary.refresh(force=True);diary.status.set(payload)
        elif kind=='research_replay':
            self.research_status.set('本地重播：'+str(payload))
            widget=getattr(self,'archive_output',None)
            if widget is not None and widget.winfo_exists():widget.insert('end',json.dumps(payload,ensure_ascii=False,indent=2)+'\n');widget.see('end')
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
               '歷史週資料自動依官網日期補抓；不足時不推定連續增持。重大訊息沒有列出，不代表排除全部風險。',
               '自動驗證：'+r.get('validation',{}).get('status','尚未開始'),
               '目前日線突破量倍數：'+str(r.get('validation',{}).get('active',1.5))+'；自動優化僅適用日線研究量倍數，不修改盤中六項條件或進場檢核。']
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
            self.research_service.enabled=enabled.get()
            if enabled.get():self.research_service.request(self.research_service.watch or self.research_codes())
        self.ttk.Checkbutton(top,text='啟用自動研究（程式開啟時每6小時更新，最多保留100股；單次加入前20股）',variable=enabled,command=toggle).pack(anchor='w',padx=12)
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
        self.ttk.Button(bar,text='驗證／參數日誌',command=self.show_validation_audit).pack(side='left',padx=6)
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

    def show_validation_audit(self):
        from research_validation import Validator
        top=self.tk.Toplevel(self.root);top.title('自動驗證／參數版本紀錄');top.geometry('1000x700')
        from tkinter.scrolledtext import ScrolledText
        text=ScrolledText(top,wrap='word');text.pack(fill='both',expand=True)
        text.insert('end','僅驗證日線突破量倍數1.5／2／2.5。時間分開的訓練、驗證、60日未重用測試；每期至少30訊號、10股、15日期。扣0.8百分點成本預留，非實際成交損益。未還原及追蹤股票選樣偏差仍存在；不是完整吸籌策略回測。\n')
        for at,body in Validator(self.research_service.store.path).audit():text.insert('end',at+'\n'+json.dumps(json.loads(body),ensure_ascii=False,indent=2)+'\n')
        text.configure(state='disabled');return top
    def show_scan_archive(self):
        from MitakeDetector import BASE
        from scan_archive import ScanArchive,replay
        from tkinter.scrolledtext import ScrolledText
        import threading
        top=self.tk.Toplevel(self.root);top.title('每日掃描資料庫／回放驗證');top.geometry('1000x740')
        archive=ScanArchive(BASE/'observations.sqlite3')
        self.ttk.Label(top,text='每次正式掃描自動保存全部成功解析的讀值（含不符條件及引擎略過值）、拒絕列數、排行、設定、時間與程式版本。未保留原圖或無法解析列的文字。更新／重新開始不刪歷史。',wraplength=950).pack(fill='x',padx=12,pady=8)
        bar=self.ttk.Frame(top);bar.pack(fill='x',padx=12)
        day=self.tk.StringVar(value=dt.datetime.now(dt.timezone(dt.timedelta(hours=8))).date().isoformat())
        self.ttk.Entry(bar,textvariable=day,width=14).pack(side='left')
        def run():
            selected=day.get().strip()
            def job():
                try:
                    dt.date.fromisoformat(selected);batches=archive.batches(selected)
                    result=replay(batches);result['day']=selected;result['batches']=len(batches)
                    self.events.put((None,'research_replay',result))
                except Exception as exc:self.events.put((None,'research_replay',{'error':str(exc)}))
            threading.Thread(target=job,daemon=True).start()
        self.ttk.Button(bar,text='重播指定日期',command=run).pack(side='left',padx=8)
        def backup():
            from tkinter import filedialog
            target=filedialog.asksaveasfilename(defaultextension='.sqlite3',initialfile='Mitake-scan-backup.sqlite3')
            if not target:return
            if __import__('pathlib').Path(target).resolve()==archive.path.resolve():return
            def job():
                try:archive.backup(target);self.events.put((None,'research_replay',{'backup':'資料庫備份完成'}))
                except Exception as exc:self.events.put((None,'research_replay',{'error':str(exc)}))
            threading.Thread(target=job,daemon=True).start()
        self.ttk.Button(bar,text='備份完整資料庫',command=backup).pack(side='left')
        output=ScrolledText(top,wrap='word');self.archive_output=output;output.pack(fill='both',expand=True,padx=12,pady=8)
        output.insert('end','本地位置：'+str(archive.path)+'\n日期｜掃描批次｜讀值筆數｜股票數｜符合觀測次數\n')
        for row in archive.summary():output.insert('end','｜'.join(map(str,row))+'\n')
        output.insert('end','重播以原批次到達順序、當時設定及當前引擎重建判定；使用300～330秒首筆後續觀測價，沒有資料就留缺。不是實際成交回測，亦不自動調整盤中參數。既有舊版observations及訊號紀錄保留；完整批次紀錄從本版開始。\n')
        return top

    def show_candidate_diary(self):
        from MitakeDetector import BASE
        from candidate_diary_ui import DiaryView
        view=getattr(self,'candidate_diary_view',None)
        if view and view.window.winfo_exists():view.window.lift();view.refresh(force=True);return view.window
        view=DiaryView(self,BASE/'observations.sqlite3');self.candidate_diary_view=view
        import sys
        if '--smoke-test' not in sys.argv:self.root.after(200,view.import_old)
        return view.window
