"""Gradual-rise controls; archive loading never blocks the OCR/UI thread."""
import threading
import time
from collections import Counter
from steady_trend import DEFAULTS,SteadyTracker,load_archive,session

class SteadyUI:
    def build_steady_controls(self,parent):
        self.vars['steady_enabled']=self.tk.BooleanVar(value=self.config['steady_enabled'])
        for key,value in DEFAULTS.items():
            if key!='steady_enabled':self.vars[key]=self.tk.StringVar(value=str(self.config.get(key,value)))
        self.ttk.Checkbutton(parent,text='09:00–12:50 盤尾緩漲檢核',variable=self.vars['steady_enabled']).grid(row=2,column=0,columnspan=5,sticky='w',padx=5,pady=5)
        self.ttk.Button(parent,text='緩漲明細／設定',command=self.show_steady_settings).grid(row=2,column=5,columnspan=3,sticky='w')
        self.ttk.Label(parent,text='全天累積，12:50後判定；非大戶身分判定').grid(row=2,column=8,columnspan=4,sticky='w')
    def init_steady(self):
        self.steady_tracker=SteadyTracker();self.steady_tracker.reset_day(time.time())
        self.steady_report_day=self.steady_tracker.day
        self.steady_reports={};self.steady_ready=False;self.steady_error=''
        self.load_steady_history()
    def load_steady_history(self):
        if getattr(self,'steady_loading',False):return
        from MitakeDetector import BASE
        self.steady_loading=True;self.steady_ready=False;now=time.time()
        def job():
            try:payload=(load_archive(BASE/'observations.sqlite3',now),'')
            except Exception as exc:payload=(None,str(exc))
            self.events.put((None,'steady_loaded',payload))
        threading.Thread(target=job,daemon=True).start()
    def steady_loaded(self,payload):
        history,error=payload;self.steady_loading=False;self.steady_error=error
        self.steady_tracker.reset_day(time.time())
        if history:self.steady_tracker.merge(history)
        # A failed restore is visible; retry is explicit and must not yield a pass.
        self.steady_ready=not bool(error)
        self.say('盤尾緩漲：當日歷史還原完成；缺少的時段不補造' if not error else '盤尾緩漲歷史載入失敗，檢核暫不通過：'+error)
    def steady_summary(self):
        if not self.steady_ready:return '盤尾緩漲：'+('歷史載入失敗，請開明細重試' if self.steady_error else '當日歷史載入中')
        counts=Counter(r['status'] for r in self.steady_reports.values())
        return '目標：09:00–12:50持續緩漲｜'+('、'.join(f'{s} {n}檔' for s,n in counts.items()) or '等待累積實際讀值')
    def show_steady_settings(self):
        top=self.tk.Toplevel(self.root);top.title('09:00–12:50 緩漲觀察｜明細與設定');top.geometry('1080x720')
        self.ttk.Label(top,text='觀察整段緩漲，不要求每筆都上漲。12:50前持續累積；早盤缺資料則不能判定。',wraplength=1000).pack(anchor='w',padx=15,pady=8)
        self.ttk.Label(top,text='以下為待回測的初始規則，不能確認大戶吸籌或保證上漲。保留原基礎條件；啟用時須額外通過本檢核才提醒。',wraplength=1000).pack(anchor='w',padx=15)
        fields=[('steady_min_rise','首末採樣漲幅下限%'),('steady_max_rise','首末採樣漲幅上限%'),('steady_max_drawdown','採樣最大回撤%'),('steady_up_share','上漲5分區間比例%'),('steady_r2','趨勢平順度 R²（0–1）'),('steady_max_jump','相鄰採樣區間漲幅上限%'),('steady_coverage','5分區間覆蓋率%'),('steady_max_gap','最長資料缺口（分鐘）')]
        form=self.ttk.Frame(top);form.pack(fill='x',padx=15,pady=8)
        for i,(key,label) in enumerate(fields):
            row,col=divmod(i,2)
            self.ttk.Label(form,text=label).grid(row=row,column=col*2,sticky='w',padx=5,pady=5)
            self.ttk.Entry(form,textvariable=self.vars[key],width=10).grid(row=row,column=col*2+1,sticky='w',padx=10)
        self.ttk.Label(top,text='固定完整性：09:05前、12:45後須有有效讀值。單區間漲幅不超過總漲幅35%；最後30分鐘不超過40%。\n變更後暫停並重新開始套用；「重新開始」不刪除當天已保存的觀測資料。',wraplength=1000).pack(anchor='w',padx=15)
        state=self.tk.StringVar();self.ttk.Label(top,textvariable=state,wraplength=1000).pack(fill='x',padx=15,pady=8)
        cols=('code','status','coverage','rise','drawdown','up','r2','reason')
        frame=self.ttk.Frame(top);frame.pack(fill='both',expand=True,padx=15)
        tree=self.ttk.Treeview(frame,columns=cols,show='headings')
        for key,label,width in zip(cols,['股號','狀態','覆蓋%','採樣漲幅%','回撤%','上漲區間%','R²','說明'],[70,120,70,90,75,90,65,350]):
            tree.heading(key,text=label);tree.column(key,width=width,stretch=key=='reason')
        scroll=self.ttk.Scrollbar(frame,command=tree.yview);tree.configure(yscrollcommand=scroll.set)
        tree.pack(side='left',fill='both',expand=True);scroll.pack(side='right',fill='y')
        def refresh():
            if not top.winfo_exists():return
            now=time.time();self.steady_tracker.reset_day(now)
            state.set(self.steady_summary()+('｜'+self.steady_error if self.steady_error else ''))
            for iid in tree.get_children():tree.delete(iid)
            cfg=dict(self.config)
            for code in sorted(self.steady_tracker.points):
                r=self.steady_reports.get(code) or self.steady_tracker.evaluate(code,cfg,now)
                fmt=lambda k:'—' if r.get(k) is None else f'{r[k]:.2f}'
                tree.insert('','end',values=(code,r['status'],fmt('coverage'),fmt('rise'),fmt('drawdown'),fmt('up_share'),fmt('r2'),r['detail']))
        actions=self.ttk.Frame(top);actions.pack(pady=10)
        self.ttk.Button(actions,text='重新整理明細',command=refresh).pack(side='left',padx=5)
        self.ttk.Button(actions,text='重試載入當日歷史',command=self.load_steady_history).pack(side='left',padx=5)
        self.ttk.Button(actions,text='關閉',command=top.destroy).pack(side='left',padx=5)
        refresh();return top
