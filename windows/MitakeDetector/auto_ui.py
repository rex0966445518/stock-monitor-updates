"""Setup and execution UI for the calibrated ranking rotation."""
import json
import threading
import time
from autoswitch import WindowsController, RankingSwitcher, RANKINGS, absolute_roi, validate_roi, F8Toggle


class AutoUI:
    def highlight_scan(self,token,region,stop):
        from autoswitch import AutomationStopped
        ready=threading.Event()
        self.events.put((token,'scan_region',(region,ready)))
        deadline=time.monotonic()+5
        while not ready.wait(.02):
            if stop.is_set():raise AutomationStopped('已停止自動操作')
            if time.monotonic()>deadline:raise AutomationStopped('掃描區域提示未就緒，已停止')
        if stop.is_set():raise AutomationStopped('已停止自動操作')

    def show_scan_outline(self,region):
        from scan_outline import ScanOutline
        outline=getattr(self,'scan_outline',None)
        if outline is None:self.scan_outline=outline=ScanOutline(self.root,self.tk)
        outline.show(region)

    def announce_task(self,message):
        if __import__('os').name!='nt' or not self.config.get('voice_enabled',True):return
        from windows_voice import WindowsVoice
        voice=getattr(self,'task_voice',None)
        if voice is None:
            voice=WindowsVoice(lambda error:self.events.put((None,'voice_error',error)))
            self.task_voice=voice
        voice.say(message)

    def toggle_task_voice(self):
        self.config['voice_enabled']=bool(self.voice_var.get());self.save()
        if not self.config['voice_enabled']:
            voice=getattr(self,'task_voice',None)
            if voice:voice.close()
            self.task_voice=None
        else:self.announce_task('語音提醒已開啟')

    def show_task_progress(self,target,message):
        from task_progress import TaskProgress
        panel=getattr(self,'task_panel',None)
        if panel and panel.alive:
            panel.update(message);return
        rect=WindowsController(target,threading.Event()).rect()
        self.task_panel=TaskProgress(self.root,self.tk,self.ttk,rect,message)

    def update_task_progress(self,message,completed=None,total=None):
        if '後繼續' in message:
            outline=getattr(self,'scan_outline',None)
            if outline:outline.hide()
        panel=getattr(self,'task_panel',None)
        if panel and panel.alive:panel.update(message,completed,total)

    def close_task_progress(self):
        outline=getattr(self,'scan_outline',None)
        if outline:outline.close()
        self.scan_outline=None
        panel=getattr(self,'task_panel',None)
        if panel:panel.close()
        self.task_panel=None

    def auto_setup(self,start_after=False):
        from tkinter import messagebox
        if not self.idle() or not self.dependencies():return
        if __import__('os').name!='nt':
            messagebox.showinfo('Windows功能','自動設定需要Windows。');return
        candidates=[w for w in WindowsController.windows() if '熱門排行' in w[1] or '热门排行' in w[1]]
        if len(candidates)!=1:
            messagebox.showerror('自動設定','請只開啟一個「熱門排行」視窗並保持完整顯示，再按一鍵自動設定。');return
        cfg=self.settings()
        if cfg is None:return
        target,title=candidates[0]
        token=self.reset('自動定位與欄位校正')
        self.auto_active=True
        stop=self.stop_event
        self.root.iconify()
        self.show_task_progress(target,'準備自動定位選單與欄位…')
        self.announce_task('開始執行掃描' if start_after else '開始自動設定')
        def job():
            try:
                from auto_detect import detect_layout,detect_menu
                c=WindowsController(target,stop);c.activate()
                c.highlight=lambda region:self.highlight_scan(token,region,stop)
                threading.Thread(target=c.monitor_hotkey,daemon=True).start()
                im,_=c.image([0,0,1,1])
                self.events.put((token,'auto_status','正在辨識排行下拉框與六個表頭…'))
                discovered=detect_layout(im,self.read_auto_ocr);c.check()
                combo=absolute_roi(discovered['combo'],c.rect())
                c.click((combo['left']+combo['width']//2,combo['top']+combo['height']//2));c.pause(.6)
                self.events.put((token,'auto_status','正在定位展開選單…'))
                opened,_=c.image([0,0,1,1])
                menu=detect_menu(opened,discovered['combo'],self.read_auto_ocr);c.check()
                auto=dict(title=title,window_size=[im.width,im.height],combo=discovered['combo'],menu=menu,table=discovered['table'],
                          rankings=['漲幅排行','預估量增','分時買力','大單流入','連續買單'],seconds=15,pages=1,full_table=True,discovered=1)
                # The popup is known to be open after detect_menu: select directly.
                RankingSwitcher(c,self.read_auto_ocr,auto['combo'],auto['menu'],ocr_retry=self.read_menu_ocr_retry,status=lambda detail:self.events.put((token,'auto_progress',(detail,None,None)))).switch('漲幅排行',menu_open=True)
                self.events.put((token,'auto_status','正在讀取股票資料並驗證欄位…'))
                table,_=c.image(auto['table'])
                quotes,bad=self.scan_recovering_table(table,discovered['column_bounds'])
                c.check()
                if not quotes:raise ValueError('自動定位完成，但尚未讀到完整股票資料，未儲存；請保持資料列可見。')
                cfg.update(auto_config=auto,column_bounds=discovered['column_bounds'],region=absolute_roi(auto['table'],c.rect()))
                self.events.put((token,'auto_setup_done',(cfg,target,len(quotes),start_after)))
            except Exception as exc:self.events.put((token,'auto_end','自動設定未完成：'+str(exc)+'；可使用手動設定。'))
            finally:stop.set()
        def launch():
            if token!=self.generation:return
            self.worker_thread=threading.Thread(target=job,daemon=True);self.worker_thread.start()
        self.root.after(700,launch)

    def scan_mapped_table(self,image,bounds):
        from MitakeDetector import FIELDS,parse_ocr
        import re,unicodedata
        tokens=[]
        for i,field in enumerate(FIELDS):
            left,right=bounds[field]
            if not 0<=left<right<=1:raise ValueError('自動欄位範圍無效')
            crop=image.crop((round(left*image.width),0,round(right*image.width),image.height))
            for box,text,confidence in self.read_auto_ocr(crop):
                if field=='code':
                    text=unicodedata.normalize('NFKC',text).strip()
                    text=re.sub(r'^[^0-9]+','',text)
                y=sum(p[1] for p in box)/4
                height=max(p[1] for p in box)-min(p[1] for p in box)
                x=50+i*100
                tokens.append(([[x-10,y-height/2],[x+10,y-height/2],[x+10,y+height/2],[x-10,y+height/2]],text,confidence))
        return parse_ocr(tokens,600,[100*(50+i*100)/600 for i in range(6)],time.time())

    def scan_recovering_table(self,image,bounds):
        """Retry whole-table OCR when narrow strips yield no complete rows."""
        from MitakeDetector import FIELDS,parse_ocr
        import re,unicodedata
        started=time.perf_counter()
        from fast_quotes import StockNames,scan_numeric_batch
        from MitakeDetector import BASE
        names=getattr(self,'stock_names',None)
        if names is None:self.stock_names=names=StockNames(BASE/'stock-names.json')
        quotes,bad=scan_numeric_batch(image,bounds,self.read_auto_ocr,names)
        if not quotes:quotes,bad=self.scan_mapped_table(image,bounds)
        self.last_table_seconds=time.perf_counter()-started
        self.last_scan_diagnostic=f'欄位辨識：有效{len(quotes)}列，略過{bad}列'
        if quotes:return quotes,bad
        counts={field:0 for field in FIELDS}
        def parse(tokens):
            mapped=[]
            for box,text,confidence in tokens:
                cx=sum(p[0] for p in box)/4/image.width
                fields=[field for field in FIELDS if bounds[field][0]<=cx<bounds[field][1]]
                if len(fields)!=1:continue
                field=fields[0];i=FIELDS.index(field)
                text=unicodedata.normalize('NFKC',str(text)).strip()
                if field=='code':
                    text=re.sub(r'^[^0-9]+','',text)
                    if re.fullmatch(r'[0-9 ]{4,9}',text):text=text.replace(' ','')
                if confidence>=.70:counts[field]+=1
                y=sum(p[1] for p in box)/4
                height=max(p[1] for p in box)-min(p[1] for p in box)
                x=50+i*100
                mapped.append(([[x-10,y-height/2],[x+10,y-height/2],[x+10,y+height/2],[x-10,y+height/2]],text,confidence))
            return parse_ocr(mapped,600,[100*(50+i*100)/600 for i in range(6)],time.time())
        quotes,rejected=parse(self.read_auto_ocr(image))
        if not quotes:
            from PIL import ImageOps
            high=ImageOps.autocontrast(ImageOps.grayscale(image)).convert('RGB')
            quotes,rejected=parse(self.read_auto_ocr(high))
        self.last_scan_diagnostic=(f'整表複查：有效{len(quotes)}列｜股號文字{counts["code"]}、成交價{counts["price"]}、漲幅{counts["gain"]}、總量{counts["volume"]}；請核對欄位是否完整可見')
        return quotes,max(bad,rejected) if not quotes else rejected

    def clear_stock_names(self):
        if not self.idle():return
        from MitakeDetector import BASE
        try:
            (BASE/'stock-names.json').unlink(missing_ok=True)
            self.stock_names=None
            self.say('股名記憶已清除，下次讀取時重新學習。')
        except OSError as exc:self.say('無法清除股名記憶：'+str(exc))

    def init_f8(self):
        self.f8_toggle=F8Toggle()
        if __import__('os').name=='nt':self.root.after(20,self.poll_f8)

    def poll_f8(self):
        import ctypes
        busy=self.auto_active or bool(self.worker_thread and self.worker_thread.is_alive())
        action=self.f8_toggle.update(bool(ctypes.windll.user32.GetAsyncKeyState(0x77)&0x8000),busy)
        if action=='stop':self.stop()
        elif action=='start':
            auto=self.config.get('auto_config') or {}
            matches=[w for w in WindowsController.windows() if w[1]==auto.get('title')]
            u=ctypes.windll.user32
            from ctypes import wintypes
            u.GetForegroundWindow.restype=wintypes.HWND
            fg=u.GetForegroundWindow()
            if len(matches)==1 and matches[0][0]==fg:
                self.auto_hwnd=matches[0][0]
                if auto.get('discovered'):self.start_auto()
                elif '熱門排行' in matches[0][1] or '热门排行' in matches[0][1]:self.auto_setup(start_after=True)
                else:self.start_auto()
            else:
                candidates=[w for w in WindowsController.windows() if ('熱門排行' in w[1] or '热门排行' in w[1]) and w[0]==fg]
                if len(candidates)==1:self.auto_setup(start_after=True)
                else:self.say('F8未啟動：請將熱門排行視窗放在最前方。')
        self.root.after(20,self.poll_f8)

    def configure_auto(self):
        from tkinter import messagebox
        if not self.idle():return
        if __import__('os').name!='nt':
            messagebox.showinfo('Windows功能','自動輪切需要在Windows安裝版使用。');return
        top=self.tk.Toplevel(self.root);top.title('三竹自動輪切設定');top.geometry('860x780')
        cfg=json.loads(json.dumps(self.config.get('auto_config') or {}))
        windows=[w for w in WindowsController.windows() if '三竹盤中轉強偵測器' not in w[1] and '三竹自動輪切設定' not in w[1]]
        choices=[f'{title}  [視窗 {hwnd}]' for hwnd,title in windows]
        self.ttk.Button(top,text='一鍵自動設定（免框選）',command=lambda:(top.destroy(),self.auto_setup())).pack(anchor='w',padx=15,pady=5)
        self.ttk.Label(top,text='1. 選擇三竹視窗（啟動後會使用滑鼠；F8開始／停止，離開目標視窗會停止）').pack(anchor='w',padx=15,pady=10)
        choice=self.tk.StringVar()
        combo=self.ttk.Combobox(top,textvariable=choice,values=choices,state='readonly',width=90);combo.pack(padx=15,fill='x')
        selected=next((i for i,w in enumerate(windows) if w[1]==cfg.get('title')),None)
        if selected is None:selected=next((i for i,w in enumerate(windows) if '三竹' in w[1]),None)
        if selected is not None:combo.current(selected)
        labels={}
        def hwnd():
            index=combo.current()
            if index<0:raise ValueError('請選擇三竹目標視窗；若未出現，先開啟三竹再重開設定。')
            self.auto_hwnd=windows[index][0];cfg['title']=windows[index][1]
            return self.auto_hwnd
        def region(kind):
            try:
                target=hwnd()
                if kind=='menu':validate_roi(cfg.get('combo'))
            except Exception as exc:messagebox.showerror('設定位置',str(exc));return
            top.withdraw();self.root.withdraw()
            def capture():
                try:
                    from PIL import ImageTk
                    c=WindowsController(target,threading.Event());c.activate()
                    current_size=[c.initial_rect[2]-c.initial_rect[0],c.initial_rect[3]-c.initial_rect[1]]
                    if cfg.get('window_size') and cfg['window_size']!=current_size:
                        for k in ('combo','menu','table'):
                            cfg.pop(k,None);labels[k].set('未設定')
                        if kind=='menu':raise ValueError('視窗尺寸已改變，請先重新框選下拉框。')
                    if kind=='menu':
                        a=absolute_roi(cfg['combo'],c.rect());c.click((a['left']+a['width']//2,a['top']+a['height']//2));c.pause(.5)
                    pic,_=c.image([0,0,1,1]);rect=c.rect()
                    overlay=self.tk.Toplevel(self.root);overlay.overrideredirect(True);overlay.attributes('-topmost',True)
                    overlay.geometry(f'{pic.width}x{pic.height}{rect[0]:+d}{rect[1]:+d}')
                    canvas=self.tk.Canvas(overlay,highlightthickness=0,cursor='cross');canvas.pack(fill='both',expand=True)
                    overlay.photo=ImageTk.PhotoImage(pic);canvas.create_image(0,0,image=overlay.photo,anchor='nw')
                    title={'combo':'只框排行名稱下拉框（含箭頭）','menu':'只框已展開的選單清單（不要含下拉框）','table':'只框排行榜表頭與資料列（不要包含下拉選單）'}[kind]
                    canvas.create_text(20,20,text=title+'；拖曳框選，Esc取消',fill='#ff5577',anchor='nw',font=('Microsoft JhengHei',18,'bold'))
                    start=[None];box=[None]
                    def finish():
                        overlay.destroy();self.root.deiconify();top.deiconify();top.lift()
                    def down(e):
                        start[0]=(e.x,e.y)
                        if box[0]:canvas.delete(box[0])
                        box[0]=canvas.create_rectangle(e.x,e.y,e.x,e.y,outline='#ff335e',width=3)
                    def move(e):
                        if start[0]:canvas.coords(box[0],*start[0],e.x,e.y)
                    def up(e):
                        if not start[0]:return
                        x,y=start[0];w,h=abs(e.x-x),abs(e.y-y)
                        if w<12 or h<12:return
                        cfg[kind]=[min(x,e.x)/pic.width,min(y,e.y)/pic.height,w/pic.width,h/pic.height]
                        validate_roi(cfg[kind]);cfg['window_size']=[pic.width,pic.height]
                        labels[kind].set('已設定')
                        finish()
                    canvas.bind('<Button-1>',down);canvas.bind('<B1-Motion>',move);canvas.bind('<ButtonRelease-1>',up)
                    overlay.bind('<Escape>',lambda e:finish());overlay.focus_force()
                except Exception as exc:
                    self.root.deiconify();top.deiconify();messagebox.showerror('框選失敗',str(exc))
            self.root.after(500,capture)
        row=self.ttk.Frame(top);row.pack(fill='x',padx=15,pady=10)
        for key,title in [('combo','框選排行下拉框'),('menu','框選展開選單'),('table','框選資料表格')]:
            self.ttk.Button(row,text=title,command=lambda k=key:region(k)).pack(side='left',padx=4)
            labels[key]=self.tk.StringVar(value='已設定' if cfg.get(key) else '未設定')
            self.ttk.Label(row,textvariable=labels[key]).pack(side='left',padx=3)
        self.ttk.Label(top,text='2. 勾選要輪切的排行。表格需沿用主畫面的六欄位置校正；各排行欄位須一致。').pack(anchor='w',padx=15,pady=5)
        ranks=self.ttk.Frame(top);ranks.pack(fill='x',padx=15)
        enabled=set(cfg.get('rankings',['漲幅排行','預估量增','大單流入','大單匯集']))
        rank_vars={}
        for i,name in enumerate(RANKINGS):
            var=self.tk.BooleanVar(value=name in enabled);rank_vars[name]=var
            self.ttk.Checkbutton(ranks,text=name,variable=var).grid(row=i//5,column=i%5,sticky='w',padx=5,pady=2)
        fields=self.ttk.Frame(top);fields.pack(fill='x',padx=15,pady=10)
        delay=self.tk.StringVar(value=str(cfg.get('seconds',15)))
        pages=self.tk.StringVar(value=str(cfg.get('pages',1)))
        self.ttk.Label(fields,text='每個排行讀取後停留秒數 ≥3').pack(side='left');self.ttk.Entry(fields,textvariable=delay,width=6).pack(side='left',padx=5)
        self.ttk.Label(fields,text='每個排行讀取頁數 1～20').pack(side='left',padx=10);self.ttk.Entry(fields,textvariable=pages,width=6).pack(side='left')
        full_table=self.tk.BooleanVar(value=cfg.get('full_table',True))
        self.ttk.Checkbutton(top,text='自動逐段讀取清單（建議）：重疊捲動，連續三次未移動才結束；關閉則使用指定頁數',variable=full_table).pack(anchor='w',padx=15)
        adaptive=self.tk.BooleanVar(value=cfg.get('adaptive',True))
        self.ttk.Checkbutton(top,text='自適應加速：記住已驗證搜尋路徑，成功讀取後縮短停留（最低1秒）',variable=adaptive).pack(anchor='w',padx=15)
        def clear_experience():
            from MitakeDetector import BASE
            try:(BASE/'automation-experience.json').unlink(missing_ok=True)
            except OSError as exc:messagebox.showerror('清除失敗',str(exc));return
            self.say('操作經驗已清除，下次重新學習')
        self.ttk.Button(top,text='清除操作經驗',command=clear_experience).pack(anchor='w',padx=15)
        self.ttk.Label(top,text='優先重用已驗證搜尋路徑，失敗時回頂端找文字，再確認切換後標題；找不到或視窗縮放時停止。\n多頁使用滑鼠滾輪向下讀取並依股號合併；不是官方全市場資料。\n首次先按「測試輪切一次」，確認切換正確後再開始持續輪切。',wraplength=810).pack(anchor='w',padx=15,pady=7)
        def save(close=True):
            try:
                hwnd();validate_roi(cfg.get('combo'));validate_roi(cfg.get('menu'))
                cfg['adaptive']=bool(adaptive.get());cfg['full_table']=bool(full_table.get())
                cfg['seconds']=float(delay.get());cfg['pages']=int(pages.get())
                if not 3<=cfg['seconds']<=3600 or not 1<=cfg['pages']<=20:raise ValueError('秒數需3～3600，頁數需1～20')
                cfg['rankings']=[n for n,v in rank_vars.items() if v.get()]
                if not cfg['rankings']:raise ValueError('請至少勾選一個排行')
                self.config['auto_config']=cfg
                if cfg.get('table'):
                    self.config['region']=absolute_roi(cfg['table'],WindowsController(self.auto_hwnd,threading.Event()).rect())
                self.save()
                self.say('自動輪切設定已儲存，共'+str(len(cfg['rankings']))+'個排行')
                if close:top.destroy()
                return True
            except Exception as exc:messagebox.showerror('設定錯誤',str(exc));return False
        def test():
            if save():self.start_auto(test_only=True)
        buttons=self.ttk.Frame(top);buttons.pack(fill='x',padx=15,pady=8)
        self.ttk.Button(buttons,text='儲存設定',command=save).pack(side='left')
        self.ttk.Button(buttons,text='測試輪切一次（不讀取行情）',command=test).pack(side='left',padx=10)

    def read_menu_ocr_retry(self,image):
        from PIL import ImageOps
        gray=ImageOps.autocontrast(ImageOps.grayscale(image))
        high=gray.point(lambda value:255 if value>140 else 0).convert('RGB')
        return self.read_auto_ocr(high)

    def read_auto_ocr(self,image):
        from rapidocr_onnxruntime import RapidOCR
        import numpy as np
        import hashlib
        from collections import OrderedDict
        raw=image.convert('RGB')
        key=(raw.size,hashlib.sha256(raw.tobytes()).digest())
        cache=getattr(self,'ocr_image_cache',None)
        if cache is None:self.ocr_image_cache=cache=OrderedDict()
        if key in cache:
            cache.move_to_end(key)
            self.ocr_cache_hits=getattr(self,'ocr_cache_hits',0)+1
            return cache[key]
        if self.ocr is None:self.ocr=RapidOCR()
        # Preserve pixel coordinates for clicking; upscale OCR output is scaled back.
        from PIL import ImageOps
        raw=image.convert('RGB')
        margin=24
        background=raw.getpixel((min(5,raw.width-1),raw.height//2))
        padded=ImageOps.expand(raw,border=margin,fill=background)
        if sum(background)/3<128:padded=ImageOps.invert(padded)
        scale=2
        result,_=self.ocr(np.asarray(padded.resize((padded.width*scale,padded.height*scale)))[:,:,::-1].copy(),unclip_ratio=2.0)
        tokens=[([[(x/scale)-margin,(y/scale)-margin] for x,y in box],text,score) for box,text,score in result or []]
        if tokens:
            cache[key]=tokens
            while len(cache)>24:cache.popitem(last=False)
        return tokens

    def start_auto(self,test_only=False,announce=True):
        from tkinter import messagebox
        if not self.idle() or not self.dependencies():return
        cfg=self.settings()
        if cfg is None:return
        auto=json.loads(json.dumps(cfg.get('auto_config') or {}))
        try:
            validate_roi(auto.get('combo'));validate_roi(auto.get('menu'))
            if not test_only:validate_roi(auto.get('table'))
            matches=[w for w in WindowsController.windows() if w[1]==auto.get('title')]
            if getattr(self,'auto_hwnd',None) not in [w[0] for w in matches]:
                if len(matches)!=1:raise ValueError('請在自動輪切設定重新選擇三竹視窗')
                self.auto_hwnd=matches[0][0]
            if not auto.get('rankings') or any(n not in RANKINGS for n in auto['rankings']):raise ValueError('請設定要輪切的排行')
            if not 3<=float(auto.get('seconds',0))<=3600 or not 1<=int(auto.get('pages',0))<=20:raise ValueError('輪切間隔或頁數無效')
        except Exception as exc:messagebox.showerror('需要設定',str(exc));return
        token=self.reset('自動輪切測試' if test_only else '自動輪切')
        self.auto_active=True;self.auto_engines={}
        stop=self.stop_event;target=self.auto_hwnd
        self.root.iconify()
        self.show_task_progress(target,'準備開始自動輪切…')
        if announce:self.announce_task('開始測試輪切' if test_only else '開始執行掃描')
        def job():
            try:
                c=WindowsController(target,stop)
                c.highlight=lambda region:self.highlight_scan(token,region,stop)
                if list((c.initial_rect[2]-c.initial_rect[0],c.initial_rect[3]-c.initial_rect[1]))!=auto.get('window_size'):
                    raise ValueError('三竹視窗尺寸與框選時不同，請重新設定位置')
                c.activate()
                threading.Thread(target=c.monitor_hotkey,daemon=True).start()
                from adaptive import Experience
                from MitakeDetector import BASE
                experience=Experience(BASE/'automation-experience.json',
                    {k:auto.get(k) for k in ('title','window_size','combo','menu','table')},auto.get('adaptive',True))
                switcher=RankingSwitcher(c,self.read_auto_ocr,auto['combo'],auto['menu'],experience=experience,ocr_retry=self.read_menu_ocr_retry,status=lambda detail:self.events.put((token,'auto_progress',(detail,None,None))))
                if auto.get('discovered'):
                    from auto_detect import detect_layout
                    self.events.put((token,'auto_status','正在重新核對股票表頭與欄位位置…'))
                    full,_=c.image([0,0,1,1])
                    layout=detect_layout(full,self.read_auto_ocr);c.check()
                    cfg['column_bounds']=layout['column_bounds'];auto['table']=layout['table']
                cycle=0
                while not stop.is_set():
                    cycle+=1
                    for rank_index,name in enumerate(auto['rankings']):
                        c.check();self.events.put((token,'auto_status',f'第{cycle}輪｜排行{rank_index+1}/{len(auto["rankings"])}：正在切換{name}'))
                        switcher.switch(name)
                        self.events.put((token,'auto_status','已確認排行：'+name))
                        if test_only:continue
                        collected={};rejected=0;begin=time.monotonic()
                        table=absolute_roi(auto['table'],c.rect());center=(table['left']+table['width']//2,table['top']+table['height']//2)
                        if auto.get('full_table',True):
                            from table_scan import scan_table
                            def progress(segment,count,stage):
                                reused=getattr(getattr(self,'stock_names',None),'reused',0)
                                seconds=getattr(self,'last_table_seconds',0)
                                detail=f'第{cycle}輪｜類目{rank_index+1}/{len(auto["rankings"])} {name}｜第{segment}段｜已讀{count}檔｜股名重用{reused}次｜{stage}｜{seconds:.1f}秒/段'
                                self.events.put((token,'auto_progress',(detail,rank_index,len(auto['rankings']))))
                            read=lambda image:self.scan_recovering_table(image,cfg['column_bounds']) if auto.get('discovered') else self.scan(image,cfg['centers'])
                            first_batch=[True]
                            def observed(quotes,bad):
                                if not switcher.label_matches(name):raise RuntimeError('本段股票類目改變，未接收本段資料')
                                c.check()
                                current=dict(cfg,source_rank=name,source_partial=True,source_reset=first_batch[0])
                                first_batch[0]=False
                                self.events.put((token,'auto_quotes',(name,quotes,bad,time.monotonic()-begin,current)))
                            quotes,rejected=scan_table(c,auto['table'],read,progress,observed=observed)
                            collected={q.code:q for q in quotes}
                        else:
                            c.scroll(center,120*60);c.pause(.6)
                            for page in range(auto['pages']):
                                self.events.put((token,'auto_progress',(f'第{cycle}輪｜{name}｜第{page+1}/{auto["pages"]}頁辨識中',rank_index*auto['pages']+page,len(auto['rankings'])*auto['pages'])))
                                c.check();im,_=c.image(auto['table'])
                                quotes,bad=self.scan_recovering_table(im,cfg['column_bounds']) if auto.get('discovered') else self.scan(im,cfg['centers']);rejected+=bad
                                for q in quotes:collected[q.code]=q
                                if page+1<auto['pages']:c.scroll(center,-120*6);c.pause(.6)
                        # Verify label again after scanning, before any observations are accepted.
                        if not switcher.label_matches(name):raise RuntimeError('讀取後排行標題改變，已停止且捨棄本次資料')
                        c.check()
                        current=dict(cfg,source_rank=name)
                        if not auto.get('full_table',True):
                            self.events.put((token,'auto_quotes',(name,list(collected.values()),rejected,time.monotonic()-begin,current)))
                        experience.observed(name,bool(collected) and rejected==0)
                        dwell=experience.wait(name,auto['seconds'])
                        mode='自適應加速' if experience.enabled else '固定間隔'
                        wait_until=time.monotonic()+dwell
                        while time.monotonic()<wait_until:
                            remaining=max(1,int(wait_until-time.monotonic()+.999))
                            self.events.put((token,'auto_progress',(f'{name}｜已讀{len(collected)}檔｜略過{rejected}列｜{mode}｜{remaining}秒後繼續｜OCR重用{getattr(self,"ocr_cache_hits",0)}次',rank_index+1,len(auto['rankings']))))
                            c.pause(min(1,max(0,wait_until-time.monotonic())))
                    if test_only:break
                self.events.put((token,'auto_end','輪切測試完成' if test_only else '自動輪切已停止'))
            except Exception as exc:self.events.put((token,'auto_end','自動輪切已停止：'+str(exc)+('｜'+getattr(self,'last_scan_diagnostic','') if '辨識' in str(exc) else '')))
            finally:stop.set()
        def launch():
            if token!=self.generation:return
            self.worker_thread=threading.Thread(target=job,daemon=True);self.worker_thread.start()
        self.root.after(700,launch)
