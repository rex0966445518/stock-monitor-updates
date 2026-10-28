"""Visible update stages and measured download progress, driven by the UI thread."""
class UpdateProgress:
    def __init__(self,root,tk,ttk):
        self.window=tk.Toplevel(root);self.window.title('漲停板探測器-雷允澤製｜更新進度')
        self.window.geometry('540x230');self.window.resizable(False,False)
        self.window.transient(root)
        self.window.protocol('WM_DELETE_WINDOW',self.window.withdraw)
        self.stage=tk.StringVar(value='正在檢查更新…')
        self.detail=tk.StringVar(value='連線至更新伺服器')
        self.amount=tk.StringVar(value='')
        frame=ttk.Frame(self.window,padding=22);frame.pack(fill='both',expand=True)
        ttk.Label(frame,textvariable=self.stage,font=('Microsoft JhengHei',14,'bold')).pack(anchor='w',pady=(0,12))
        self.bar=ttk.Progressbar(frame,mode='indeterminate',maximum=100)
        self.bar.pack(fill='x',pady=(0,10));self.bar.start(70)
        ttk.Label(frame,textvariable=self.amount,wraplength=490).pack(anchor='w')
        ttk.Label(frame,textvariable=self.detail,wraplength=490).pack(anchor='w',pady=(10,0))
        self.window.update_idletasks()

    def show(self):
        self.window.deiconify();self.window.lift()

    def update(self,state):
        stage=state['stage'];version=state.get('version','')
        titles={'checking':'正在檢查更新…','connecting':'正在連線下載…','downloading':f'正在下載 v{version}',
                'verifying':'下載完成，正在驗證安裝檔…','ready':'驗證通過，準備安裝…',
                'latest':'目前已是最新版','error':'更新未完成'}
        self.stage.set(titles.get(stage,stage))
        self.detail.set(state.get('message',''))
        if stage in ('checking','connecting','verifying'):
            if str(self.bar.cget('mode'))!='indeterminate':
                self.bar.stop();self.bar.configure(mode='indeterminate');self.bar.start(70)
        else:
            self.bar.stop();self.bar.configure(mode='determinate')
            if stage in ('ready','latest'):self.bar['value']=100
            elif stage=='downloading':self.bar['value']=state['percent']
        if stage=='downloading':
            done=state['downloaded']/1048576;total=state['total']/1048576
            if state.get('elapsed',0)==0:self.resume_bytes=state['downloaded']
            speed=max(0,state['downloaded']-getattr(self,'resume_bytes',0))/max(.01,state.get('elapsed',0))/1048576
            rate=f'｜{speed:.1f} MiB/s' if state['downloaded'] else ''
            eta=''
            if speed>0:
                remaining=max(0,int((total-done)/speed))
                eta=f'｜預估剩餘 {remaining//60:02d}:{remaining%60:02d}'
            self.amount.set(f'{state["percent"]}%｜{done:.1f} / {total:.1f} MiB {rate}{eta}')
        elif stage in ('checking','connecting','latest'):self.amount.set('')

    def close(self):
        self.bar.stop();self.window.destroy()
