"""Gradual-rise controls; archive loading never blocks the OCR/UI thread."""
import threading
import time
import datetime as dt
from collections import Counter
from steady_trend import DEFAULTS,SteadyTracker,load_archive,session,TAIPEI

class SteadyUI:
    def build_steady_controls(self,parent):
        self.vars['steady_enabled']=self.tk.BooleanVar(value=self.config['steady_enabled'])
        for key,value in DEFAULTS.items():
            if key!='steady_enabled':self.vars[key]=self.tk.StringVar(value=str(self.config.get(key,value)))
        self.ttk.Checkbutton(parent,text='09:00–12:50 盤尾緩漲檢核',variable=self.vars['steady_enabled']).pack(side='left',padx=8)
        self.ttk.Button(self.main_actions,text='緩漲明細／設定',command=self.show_steady_settings).pack(side='left',padx=4)
    def init_steady(self):
        self.steady_tracker=SteadyTracker();self.steady_tracker.reset_day(time.time())
        self.steady_report_day=self.steady_tracker.day
        self.steady_reports={};self.steady_ready=False;self.steady_error=''
        self.load_steady_history()
    def load_steady_history(self):
        if getattr(self,'steady_loading',False):return
        from MitakeDetector import BASE
        self.steady_loading=True;self.steady_ready=False;self.steady_error='';now=time.time()
        self.steady_load_status='正在讀取本機當日歷史…'
        self.refresh_steady_views()
        def job():
            try:payload=(load_archive(BASE/'observations.sqlite3',now),'')
            except Exception as exc:payload=(None,str(exc))
            self.events.put((None,'steady_loaded',payload))
        threading.Thread(target=job,daemon=True).start()
    def steady_loaded(self,payload):
        history,error=payload;self.steady_loading=False;self.steady_error=error
        self.steady_tracker.reset_day(time.time())
        if history:self.steady_tracker.merge(history)
        self.steady_reports={}
        points=[p for series in (history.points.values() if history else []) for p in series.values()]
        clock=lambda ts:dt.datetime.fromtimestamp(ts,TAIPEI).strftime('%H:%M:%S')
        self.steady_load_status=(f'載入成功：{len(history.points)}檔／{len(points)}筆有效分鐘採樣｜{clock(min(p[0] for p in points))}～{clock(max(p[0] for p in points))}' if points else '載入完成：本機當日沒有可用的09:00～12:50採樣') if not error else '載入失敗：'+error
        # A failed restore is visible; retry is explicit and must not yield a pass.
        self.steady_ready=not bool(error)
        self.say(self.steady_load_status+'；此功能只讀本機紀錄，不會下載或補齊缺少行情')
        self.refresh_steady_views()
    def refresh_steady_views(self):
        for view in list(getattr(self,'steady_views',[])):
            view()
    def steady_summary(self):
        if not self.steady_ready:return '盤尾緩漲：'+('歷史載入失敗，請開明細重試' if self.steady_error else '當日歷史載入中')
        counts=Counter(r['status'] for r in self.steady_reports.values())
        return '目標：持續記錄價量、觀察承接線索（非大戶確認）｜'+('、'.join(f'{s} {n}檔' for s,n in counts.items()) or '等待累積實際讀值')
    def show_steady_settings(self):
        top=self.tk.Toplevel(self.root);top.title('09:00–12:50 緩漲觀察｜明細與設定');top.geometry('1080x720')
        self.ttk.Label(top,text='持續記錄價格與累計量，觀察緩漲、回落量速及低點變化。12:50前累積；早盤缺資料不補造。',wraplength=1000).pack(anchor='w',padx=15,pady=8)
        self.ttk.Label(top,text='以下為待回測初始規則，55%不是已驗證最佳門檻；採樣區間成交量不是主動買量，不能確認大戶吸籌或保證上漲。保留原基礎條件；啟用時須額外通過本檢核才提醒。',wraplength=1000).pack(anchor='w',padx=15)
        fields=[('steady_min_rise','首末採樣漲幅下限%'),('steady_max_rise','首末採樣漲幅上限%'),('steady_max_drawdown','採樣最大回撤%'),('steady_up_share','上漲5分區間比例%'),('steady_r2','趨勢平順度 R²（0–1）'),('steady_max_jump','相鄰採樣區間漲幅上限%'),('steady_coverage','5分區間覆蓋率%'),('steady_max_gap','最長資料缺口（分鐘）'),('steady_up_volume','上漲採樣區間量占比下限%')]
        form=self.ttk.Frame(top);form.pack(fill='x',padx=15,pady=8)
        for i,(key,label) in enumerate(fields):
            row,col=divmod(i,2)
            self.ttk.Label(form,text=label).grid(row=row,column=col*2,sticky='w',padx=5,pady=5)
            self.ttk.Entry(form,textvariable=self.vars[key],width=10).grid(row=row,column=col*2+1,sticky='w',padx=10)
        self.ttk.Label(top,text='價量：有至少3個回落區間時，回落量速不可高於上漲量速；低點變化僅作線索。\n固定完整性：09:05前、12:45後須有有效讀值。單區間漲幅不超過總漲幅35%；最後30分鐘不超過40%。\n變更後暫停並重新開始套用；「重新開始」不刪除當天已保存的觀測資料。',wraplength=1000).pack(anchor='w',padx=15)
        self.ttk.Label(top,text='載入本機紀錄不等於補齊歷史行情；未掃描的時段無法由此按鈕找回。點選資料列可看完整缺漏原因。',wraplength=1000).pack(anchor='w',padx=15)
        state=self.tk.StringVar();self.ttk.Label(top,textvariable=state,wraplength=1000).pack(fill='x',padx=15,pady=8)
        cols=('code','status','coverage','rise','drawdown','up','r2','reason')
        frame=self.ttk.Frame(top);frame.pack(fill='both',expand=True,padx=15)
        tree=self.ttk.Treeview(frame,columns=cols,show='headings')
        for key,label,width in zip(cols,['股號','狀態','覆蓋%','採樣漲幅%','回撤%','上漲區間%','R²','說明'],[70,120,70,90,75,90,65,350]):
            tree.heading(key,text=label);tree.column(key,width=width,stretch=key=='reason')
        scroll=self.ttk.Scrollbar(frame,command=tree.yview);tree.configure(yscrollcommand=scroll.set)
        tree.pack(side='left',fill='both',expand=True);scroll.pack(side='right',fill='y')
        detail=self.tk.StringVar(value='選取一檔股票查看完整說明')
        self.ttk.Label(top,textvariable=detail,wraplength=1000).pack(fill='x',padx=15,pady=5)
        def selected(event=None):
            items=tree.selection()
            if items:
                values=tree.item(items[0],'values');detail.set(str(values[0])+'：'+str(values[-1]))
        tree.bind('<<TreeviewSelect>>',selected)
        def refresh():
            if not top.winfo_exists():return
            now=time.time();self.steady_tracker.reset_day(now)
            state.set(getattr(self,'steady_load_status','')+'\n'+self.steady_summary())
            for iid in tree.get_children():tree.delete(iid)
            cfg=dict(self.config)
            for code in sorted(self.steady_tracker.points):
                r=self.steady_reports.get(code) or self.steady_tracker.evaluate(code,cfg,now)
                fmt=lambda k:'—' if r.get(k) is None else f'{r[k]:.2f}'
                tree.insert('','end',values=(code,r['status'],fmt('coverage'),fmt('rise'),fmt('drawdown'),fmt('up_share'),fmt('r2'),r['detail']))
        actions=self.ttk.Frame(top);actions.pack(pady=10)
        self.ttk.Button(actions,text='重新整理明細',command=refresh).pack(side='left',padx=5)
        retry=self.ttk.Button(actions,text='重新載入本機當日歷史',command=self.load_steady_history);retry.pack(side='left',padx=5)
        self.ttk.Button(actions,text='關閉',command=top.destroy).pack(side='left',padx=5)
        if not hasattr(self,'steady_views'):self.steady_views=[]
        def update_view():
            if top.winfo_exists():
                refresh();retry.configure(state='disabled' if getattr(self,'steady_loading',False) else 'normal')
        self.steady_views.append(update_view)
        def destroyed(event):
            if event.widget is top and update_view in self.steady_views:self.steady_views.remove(update_view)
        top.bind('<Destroy>',destroyed,add='+')
        update_view();return top
