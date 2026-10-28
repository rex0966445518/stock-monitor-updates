"""User-selectable screening profiles and independently editable rules."""
from scan_profiles import DEFAULTS, MODES, RULES, ShrinkTracker, validate


class ProfileUI:
    def build_profile_controls(self, parent):
        self.shrink_tracker=ShrinkTracker()
        self.profile_vars={k:(self.tk.BooleanVar(value=self.config.get(k,v)) if isinstance(v,bool)
                             else self.tk.StringVar(value=str(self.config.get(k,v)))) for k,v in DEFAULTS.items()}
        self.profile_label=self.tk.StringVar(value=MODES.get(self.config.get('scan_profile','original'),MODES['original']))
        self.ttk.Button(parent,text='掃描股特性／條件勾選',command=self.show_profile_settings).pack(side='left',padx=5)
        self.ttk.Label(parent,textvariable=self.profile_label).pack(side='left',padx=5)

    def read_profile_settings(self, cfg):
        for key, default in DEFAULTS.items():
            v=self.profile_vars[key].get()
            cfg[key]=bool(v) if isinstance(default,bool) else v if isinstance(default,str) else float(v)
        validate(cfg)

    def show_profile_settings(self):
        if not self.idle():return
        top=self.tk.Toplevel(self.root);top.title('掃描股特性｜獨立條件與門檻');top.geometry('900x760')
        outer=self.ttk.Frame(top);outer.pack(fill='both',expand=True)
        canvas=self.tk.Canvas(outer,highlightthickness=0,bg='#111a2e')
        scroll=self.ttk.Scrollbar(outer,orient='vertical',command=canvas.yview)
        canvas.configure(yscrollcommand=scroll.set);scroll.pack(side='right',fill='y');canvas.pack(side='left',fill='both',expand=True)
        body=self.ttk.Frame(canvas);item=canvas.create_window((0,0),window=body,anchor='nw')
        body.bind('<Configure>',lambda e:canvas.configure(scrollregion=canvas.bbox('all')))
        canvas.bind('<Configure>',lambda e:canvas.itemconfigure(item,width=e.width))
        self.ttk.Label(body,text='選擇要找的股票特性',font=('Microsoft JhengHei',14,'bold')).pack(anchor='w',padx=15,pady=10)
        for key,label in MODES.items():
            self.ttk.Radiobutton(body,text=label,variable=self.profile_vars['scan_profile'],value=key).pack(anchor='w',padx=20,pady=3)
        self.ttk.Label(body,text='縮量模式獨立判斷以下勾選條件；同時符合模式另須通過原有條件、進場及盤尾檢核（如已啟用）。\n原有六項評分保留在原有／同時符合模式；縮量模式顯示自己的符合數。',wraplength=820).pack(anchor='w',padx=15,pady=8)
        settings=self.ttk.LabelFrame(body,text='觀察週期與門檻（初版待回測，可自行調整）');settings.pack(fill='x',padx=15,pady=5)
        fields=[('shrink_minutes','每段分鐘（1／3／5／10）'),('shrink_segments','觀察段數（3～6）'),('shrink_tolerance','邊界提前容許秒數（0～15）'),
                ('shrink_percent','最新一段縮量至少%'),('shrink_mean_percent','低於前段均量至少%'),('shrink_rise','首末段末價上升至少%'),
                ('shrink_near_high','距當日最高價最多%'),('shrink_gain_min','當日漲幅下限%'),('shrink_gain_max','當日漲幅上限%'),
                ('shrink_total_volume','當日總量至少張'),('shrink_segment_volume','每段至少張'),('shrink_price','股價至少元')]
        for i,(key,label) in enumerate(fields):
            row,col=divmod(i,2)
            self.ttk.Label(settings,text=label).grid(row=row,column=col*2,sticky='w',padx=8,pady=4)
            self.ttk.Entry(settings,textvariable=self.profile_vars[key],width=8).grid(row=row,column=col*2+1,padx=8,pady=4)
        checks=self.ttk.LabelFrame(body,text='獨立勾選：全部已勾選條件成立才提醒');checks.pack(fill='x',padx=15,pady=8)
        for i,(key,label,_) in enumerate(RULES):
            self.ttk.Checkbutton(checks,text=label,variable=self.profile_vars['use_shrink_'+key]).grid(row=i//2,column=i%2,sticky='w',padx=8,pady=4)
        self.ttk.Label(body,text='均價／開盤／當日最高由三竹同列讀取；欄位遮擋或辨識不清時不通過。MACD仍需K線資料。\n三段量需四個邊界讀值；漏掃不補造，零成交不算縮量。非精確邊界會顯示各段實際秒數。\n新模式使用電腦端提醒，不送入手機六項評分／5/6日誌。\n重新開啟程式需重新累積；同次執行跨排行榜依股號合併。更換條件後觸發次數重新計算。',wraplength=820).pack(anchor='w',padx=15,pady=8)
        def save():
            if not self.idle():return
            cfg=self.settings()
            if cfg:
                self.profile_label.set(MODES[cfg['scan_profile']])
                self.rank_rows={};self.display_rows=[]
                from retained_candidates import RetainedCandidates
                self.retained_candidates=RetainedCandidates()
                self.display_cfg=dict(cfg);self.draw_results()
                self.say('掃描股特性已儲存：'+MODES[cfg['scan_profile']]+'；請按重新開始套用');top.destroy()
        self.ttk.Button(body,text='儲存條件',command=save).pack(pady=12)
        return top
