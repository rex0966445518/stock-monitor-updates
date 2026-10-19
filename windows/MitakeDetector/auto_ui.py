"""Setup and execution UI for the calibrated ranking rotation."""
import json
import threading
import time
from autoswitch import WindowsController, RankingSwitcher, RANKINGS, absolute_roi, validate_roi, F8Toggle, AutomationStopped


class AutoUI:
    def hide_scan_cursor(self,token):
        from cursor_visibility import CursorVisibility
        self.restore_scan_cursor()
        guard=CursorVisibility();self.cursor_visibility=guard
        if not guard.start() and guard.error:self.events.put((token,'cursor_note','游標隱藏未啟用：'+guard.error))

    def restore_scan_cursor(self):
        guard=getattr(self,'cursor_visibility',None)
        if guard:guard.close()

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
        regions=region if isinstance(region,list) else ([region] if region else [])
        extras=getattr(self,'scan_outline_extras',[])
        while len(extras)<max(0,len(regions)-1):extras.append(ScanOutline(self.root,self.tk))
        self.scan_outline_extras=extras
        for index,item in enumerate([outline]+extras):
            if index<len(regions):item.show(regions[index])
            else:item.hide()

    def prepare_scan_capture(self,token,region,stop):
        self.scan_capture_region=dict(region)
        self.scan_focus_context=(token,stop)
        # Remove all inner column borders before the next full-table screenshot.
        self.highlight_scan(token,None,stop)

    def focus_columns(self,image,bounds,fields,stage,row=None):
        source=getattr(self,'scan_capture_region',None);context=getattr(self,'scan_focus_context',None)
        if not source or not context or (source['width'],source['height'])!=image.size:return
        regions=[]
        labels={'code':'股號','name':'股名','price':'成交價','gain':'漲幅','volume':'成交量','turnover':'換手率'}
        for field in fields:
            left,right=bounds[field]
            x=round(left*image.width);end=round(right*image.width)
            top,bottom=row if row else (0,image.height)
            regions.append(dict(left=source['left']+x,top=source['top']+top,width=end-x,height=bottom-top))
        token,stop=context
        self.events.put((token,'auto_status',stage+'：'+'、'.join(labels.get(f,f) for f in fields)))
        self.highlight_scan(token,regions,stop)

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
        auto=self.config.get('auto_config') or {}
        self.task_panel=TaskProgress(self.root,self.tk,self.ttk,rect,message,combo=auto.get('combo'),menu=auto.get('menu'))
        self.task_panel.started-=getattr(self,'scan_total_elapsed',0)

    def update_task_progress(self,message,completed=None,total=None):
        if '後繼續' in message:
            outline=getattr(self,'scan_outline',None)
            if outline:outline.hide()
            for extra in getattr(self,'scan_outline_extras',[]):extra.hide()
        panel=getattr(self,'task_panel',None)
        if panel and panel.alive:panel.update(message,completed,total)

    def close_task_progress(self):
        outline=getattr(self,'scan_outline',None)
        if outline:outline.close()
        self.scan_outline=None
        for extra in getattr(self,"scan_outline_extras",[]):extra.close()
        self.scan_outline_extras=[]
        panel=getattr(self,'task_panel',None)
        if panel:
            self.scan_total_elapsed=max(0,time.monotonic()-panel.started)
            panel.close()
        self.task_panel=None

    def auto_setup(self,start_after=False):
        self.scan_resume_active=False
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
        self.events.put((token,'auto_phase','設定中｜正在辨識視窗與欄位'))
        from task_progress import SCAN_GOAL,goal_conditions
        self.say(SCAN_GOAL+'｜'+goal_conditions(cfg))
        self.numeric_priority=True
        self.auto_active=True
        stop=self.stop_event
        # Keep the main dashboard visible on the user's secondary display.
        self.show_task_progress(target,'準備自動定位選單與欄位…')
        self.announce_task('開始執行掃描' if start_after else '開始自動設定')
        def job():
            try:
                from auto_detect import detect_layout,detect_menu
                c=WindowsController(target,stop);c.activate()
                self.hide_scan_cursor(token)
                c.highlight=lambda region:self.prepare_scan_capture(token,region,stop)
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
            finally:
                self.restore_scan_cursor();stop.set()
        def launch():
            if token!=self.generation:return
            self.worker_thread=threading.Thread(target=job,daemon=True);self.worker_thread.start()
        self.root.after(700,launch)

    def remember_fast_rows(self,image,bounds,anchors,scores):
        import hashlib
        self.fast_row_source=(image,bounds,anchors,scores)
        self.fast_code_cache=(hashlib.sha256(image.tobytes()).digest(),bounds,
                              [code for code,y,h in anchors if scores.get(code,0)>=.90])
        pending=getattr(self,'deferred_names',{})
        tried=getattr(self,'deferred_name_tried',{})
        names=getattr(self,'stock_names',None)
        left,right=bounds['name']
        for code,y,h in anchors:
            if scores.get(code,0)<.92 or not names or code in names.names:continue
            top=max(0,int(y-h*.7));bottom=min(image.height,int(y+h*.7)+1)
            if bottom<=top:continue
            crop=image.crop((round(left*image.width),top,round(right*image.width),bottom))
            proof=hashlib.sha256(crop.tobytes()).hexdigest()
            if tried.get(code)==proof:continue
            pending[code]=(crop,proof)
        while len(pending)>1000:pending.pop(next(iter(pending)))
        self.deferred_names=pending

    def learn_deferred_names(self,check=lambda:None):
        """One bounded name sheet in the existing ranking wait, never per row."""
        from PIL import Image
        pending=getattr(self,'deferred_names',{})
        names=getattr(self,'stock_names',None)
        if not pending or not names:return 0
        sections=[];height=8;width=1
        eligible=getattr(self,'name_eligible_codes',set())
        for code in [c for c in list(pending) if c in eligible][:8]:
            crop,proof=pending.pop(code)
            if code in names.names:continue
            sections.append((code,height,height+crop.height,crop,proof))
            height+=crop.height+16;width=max(width,crop.width)
        if not sections:return 0
        sheet=Image.new('RGB',(width+16,height),'black')
        for code,top,bottom,crop,proof in sections:sheet.paste(crop,(8,top))
        check();tokens=self.read_auto_ocr(sheet);check()
        if not hasattr(self,'deferred_name_tried'):self.deferred_name_tried={}
        count=0
        for code,top,bottom,crop,proof in sections:
            self.deferred_name_tried[code]=proof
            near=[t for t in tokens if top<=sum(p[1] for p in t[0])/4<bottom]
            if len(near)==1:
                _,text,confidence=near[0]
                count+=bool(names.consider(code,str(text).replace(' ',''),confidence,'idle:'+proof))
        # One enlarged batch for uncertain names, instead of 2 OCR calls per row.
        unresolved=[part for part in sections if part[0] not in names.names]
        if unresolved:
            check();enlarged=sheet.resize((sheet.width*2,sheet.height*2),Image.Resampling.LANCZOS)
            zoomed=self.read_auto_ocr(enlarged);check()
            for code,top,bottom,crop,proof in unresolved:
                near=[t for t in zoomed if top*2<=sum(p[1] for p in t[0])/4<bottom*2]
                if len(near)==1:
                    _,text,confidence=near[0]
                    count+=bool(names.consider(code,str(text).replace(' ',''),confidence,'idle-zoom2:'+proof))
        while len(self.deferred_name_tried)>2000:self.deferred_name_tried.pop(next(iter(self.deferred_name_tried)))
        if names.dirty:names.save()
        return count

    def read_stock_anchors(self,image,bounds):
        import re,unicodedata
        import hashlib
        cached=getattr(self,'fast_code_cache',None)
        if cached and cached[0]==hashlib.sha256(image.tobytes()).digest() and cached[1]==bounds:return list(cached[2])
        left,right=bounds['code']
        self.focus_columns(image,bounds,['code'],'核對捲動重疊股號')
        crop=image.crop((round(left*image.width),0,round(right*image.width),image.height))
        import hashlib
        memory=self.get_ocr_learning()
        key=f'numeric-column:code:{crop.width}x{crop.height}:'+hashlib.sha256(crop.convert('RGB').tobytes()).hexdigest()
        tokens=memory.lookup(key) if memory else None
        if tokens is None:tokens=self.read_auto_ocr(crop)
        codes=[]
        for box,text,confidence in tokens:
            text=unicodedata.normalize('NFKC',str(text)).replace(' ','').strip()
            if confidence>=.90 and re.fullmatch(r'[0-9]{4,6}',text):codes.append(text)
        return codes

    def scan_mapped_table(self,image,bounds):
        from MitakeDetector import FIELDS,parse_ocr
        import re,unicodedata
        tokens=[]
        for i,field in enumerate(FIELDS):
            left,right=bounds[field]
            if not 0<=left<right<=1:raise ValueError('自動欄位範圍無效')
            crop=image.crop((round(left*image.width),0,round(right*image.width),image.height))
            self.focus_columns(image,bounds,[field],'逐欄複查')
            for box,text,confidence in self.read_auto_ocr(crop):
                if field=='code':
                    text=unicodedata.normalize('NFKC',text).strip()
                    text=re.sub(r'^[^0-9]+','',text)
                y=sum(p[1] for p in box)/4
                height=max(p[1] for p in box)-min(p[1] for p in box)
                x=50+i*100
                tokens.append(([[x-10,y-height/2],[x+10,y-height/2],[x+10,y+height/2],[x-10,y+height/2]],text,confidence))
        names=getattr(self,'stock_names',None)
        if names is not None:
            from fast_quotes import learn_mapped_names
            import hashlib
            learn_mapped_names(tokens,names,'mapped:'+hashlib.sha256(image.tobytes()).hexdigest())
        return parse_ocr(tokens,600,[100*(50+i*100)/600 for i in range(6)],time.time())

    def scan_recovering_table(self,image,bounds):
        started=time.perf_counter()
        captured_at=image.info.get('captured_at',time.time())
        try:
            result=self._scan_recovering_table(image,bounds)
            for q in result[0]:q.ts=captured_at
            return result
        finally:self.last_table_seconds=time.perf_counter()-started

    def _scan_recovering_table(self,image,bounds):
        """Retry whole-table OCR when narrow strips yield no complete rows."""
        from MitakeDetector import FIELDS,parse_ocr
        import re,unicodedata
        started=time.perf_counter()
        from fast_quotes import StockNames,scan_numeric_batch
        from MitakeDetector import BASE
        names=getattr(self,'stock_names',None)
        if names is None:self.stock_names=names=StockNames(BASE/'stock-names.json')
        quotes,bad=scan_numeric_batch(image,bounds,self.read_auto_ocr,names,memory=self.get_ocr_learning(),focus=lambda fields,stage,row=None:self.focus_columns(image,bounds,fields,stage,row),
                                      defer_names=getattr(self,'numeric_priority',False),
                                      anchors_ready=lambda anchors,scores:self.remember_fast_rows(image,bounds,anchors,scores))
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
            from fast_quotes import learn_mapped_names
            import hashlib
            learn_mapped_names(mapped,names,'whole:'+hashlib.sha256(image.tobytes()).hexdigest())
            return parse_ocr(mapped,600,[100*(50+i*100)/600 for i in range(6)],time.time())
        quotes,rejected=parse(self.read_auto_ocr(image))
        if not quotes:
            from PIL import ImageOps
            high=ImageOps.autocontrast(ImageOps.grayscale(image)).convert('RGB')
            quotes,rejected=parse(self.read_auto_ocr(high))
        self.last_scan_diagnostic=(f'整表複查：有效{len(quotes)}列｜股號文字{counts["code"]}、成交價{counts["price"]}、漲幅{counts["gain"]}、總量{counts["volume"]}；請核對欄位是否完整可見')
        return quotes,max(bad,rejected) if not quotes else rejected

    def show_scan_diagnostics(self):
        from MitakeDetector import BASE
        from tkinter.scrolledtext import ScrolledText
        top=self.tk.Toplevel(self.root);top.title('掃描中斷診斷');top.geometry('1000x620')
        text=ScrolledText(top,wrap='word');text.pack(fill='both',expand=True)
        try:
            path=BASE/'scan-interruptions.jsonl'
            records=path.read_text(encoding='utf-8').splitlines() if path.exists() else []
            value='\n\n'.join(json.dumps(json.loads(line),ensure_ascii=False,indent=2) for line in reversed(records)) or '尚無掃描中斷紀錄'
        except (OSError,ValueError) as exc:value='無法讀取中斷紀錄：'+str(exc)
        text.insert('1.0',value);text.configure(state='disabled')
        self.ttk.Button(top,text='複製全部診斷',command=lambda:(top.clipboard_clear(),top.clipboard_append(value))).pack(pady=6)

    def clear_stock_names(self):
        if not self.idle():return
        from MitakeDetector import BASE
        try:
            (BASE/'stock-names.json').unlink(missing_ok=True)
            (BASE/'stock-names-learning.json').unlink(missing_ok=True)
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
        windows=[w for w in WindowsController.windows() if '漲停板探測器-雷允澤製' not in w[1] and '三竹盤中轉強偵測器' not in w[1] and '三竹自動輪切設定' not in w[1]]
        choices=[f'{title}  [視窗 {hwnd}]' for hwnd,title in windows]
        self.ttk.Button(top,text='一鍵自動設定（免框選）',command=lambda:(top.destroy(),self.auto_setup())).pack(anchor='w',padx=15,pady=5)
        self.ttk.Label(top,text='1. 選擇三竹視窗（啟動後會使用滑鼠；F8開始／停止，可操作探測器主頁，三竹畫面保持可見）').pack(anchor='w',padx=15,pady=10)
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

    def get_ocr_learning(self):
        value=getattr(self,'ocr_learning',None)
        if value is False:return None
        if value is None:
            from ocr_learning import OCRLearning
            from sqlite3 import Error
            from MitakeDetector import BASE
            try:self.ocr_learning=value=OCRLearning(BASE/'ocr-learning.sqlite3')
            except (OSError,Error):self.ocr_learning=False;return None
        return value

    def show_ocr_learning(self):
        from tkinter import messagebox
        learning=self.get_ocr_learning()
        if not learning:messagebox.showinfo('OCR學習紀錄','本機學習紀錄暫時無法開啟');return
        top=self.tk.Toplevel(self.root);top.title('OCR持續學習紀錄');top.geometry('950x680')
        cached,rows=learning.recent()
        self.ttk.Label(top,text=f'保存最近2000次OCR讀取，以下顯示最近200次｜可重用畫面 {cached} 筆｜完全相同像素才重用').pack(anchor='w',padx=15,pady=10)
        tree=self.ttk.Treeview(top,columns=('time','source','cost','count'),show='headings',height=12)
        for key,title,width in [('time','讀取時間',260),('source','處理方式',180),('cost','耗時秒',100),('count','文字數',100)]:tree.heading(key,text=title);tree.column(key,width=width)
        tree.pack(fill='both',expand=True,padx=15)
        text=self.tk.Text(top,height=9,bg='#101a2c',fg='#edf2ff',wrap='word');text.pack(fill='both',expand=True,padx=15,pady=10)
        contents={}
        import datetime as dt
        for timestamp,source,elapsed,count,payload in rows:
            when=dt.datetime.fromisoformat(timestamp).astimezone().strftime('%Y-%m-%d %H:%M:%S')
            item=tree.insert('','end',values=(when,source,f'{elapsed:.3f}',count));contents[item]=payload
        def select(event=None):
            chosen=tree.selection()
            if chosen:
                tokens=json.loads(contents[chosen[0]])
                text.delete('1.0','end');text.insert('1.0','\n'.join(f'{word}  信心 {score:.1%}' for _,word,score in tokens))
        tree.bind('<<TreeviewSelect>>',select)
        def clear():
            if not self.idle():return
            learning.clear_cache()
            cache=getattr(self,'ocr_image_cache',None)
            if cache is not None:cache.clear()
            self.say('OCR辨識快取已清除，讀取紀錄保留。');top.destroy()
        self.ttk.Button(top,text='清除辨識快取（保留日誌）',command=clear).pack(anchor='w',padx=15,pady=(0,10))
        return top

    def read_auto_ocr(self,image):
        from rapidocr_onnxruntime import RapidOCR
        import numpy as np
        import hashlib
        from collections import OrderedDict
        source=getattr(self,'scan_capture_region',None);context=getattr(self,'scan_focus_context',None)
        if source and context and image.width==source['width'] and image.height<=source['height']:
            token,stop=context
            self.highlight_scan(token,dict(source,height=image.height),stop)
        started=time.perf_counter()
        raw=image.convert('RGB')
        key=(raw.size,hashlib.sha256(raw.tobytes()).digest())
        persistent_key=f'{raw.width}x{raw.height}:'+key[1].hex()
        learning=self.get_ocr_learning()
        cache=getattr(self,'ocr_image_cache',None)
        if cache is None:self.ocr_image_cache=cache=OrderedDict()
        if key in cache:
            cache.move_to_end(key)
            self.ocr_cache_hits=getattr(self,'ocr_cache_hits',0)+1
            if learning:learning.record(persistent_key,cache[key],time.perf_counter()-started,'記憶體重用')
            return cache[key]
        if learning:
            remembered=learning.lookup(persistent_key)
            if remembered is not None:
                cache[key]=remembered
                while len(cache)>24:cache.popitem(last=False)
                self.ocr_cache_hits=getattr(self,'ocr_cache_hits',0)+1
                learning.record(persistent_key,remembered,time.perf_counter()-started,'跨次重用')
                return remembered
        if self.ocr is None:self.ocr=RapidOCR(det_limit_type="max",det_limit_side_len=1600,intra_op_num_threads=2,inter_op_num_threads=1,use_cls=False)
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
        if learning:learning.record(persistent_key,tokens,time.perf_counter()-started,'重新辨識')
        if tokens:
            cache[key]=tokens
            while len(cache)>24:cache.popitem(last=False)
        return tokens

    def start_auto(self,test_only=False,announce=True):
        self.numeric_priority=True
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
        from resume_scan import ScanCheckpoint,scan_context
        from MitakeDetector import BASE
        context=scan_context(cfg);saved=None;store=None
        self.scan_resume_active=not test_only
        if not test_only:
            try:
                store=ScanCheckpoint(BASE/'scan-checkpoint.json');saved=store.resume(context)
                self.scan_checkpoint=store
            except (ValueError,OSError) as exc:messagebox.showerror('保留前次進度',str(exc));return
        new_day=bool(store and store.archived_path)
        if saved:
            self.generation+=1;token=self.generation;self.stop_event=threading.Event()
            self.mode='接續自動掃描'
            self.restore_scan_snapshot(store.data.get('ui'))
            self.say(f'接續第{saved["cycle"]}輪、排行{saved["rank"]+1}；保留已讀資料')
        else:
            self.scan_total_elapsed=0
            token=self.reset('自動輪切測試' if test_only else '自動輪切')
            self.auto_engines={}
        if new_day:self.say('前次日期進度已完整封存，開始今日第1輪；歷史檔：'+str(store.archived_path))
        from task_progress import SCAN_GOAL,goal_conditions
        self.say(SCAN_GOAL+'｜'+goal_conditions(cfg))
        self.auto_active=True
        self.events.put((token,'auto_phase','掃描中｜前次進度已封存，正在啟動今日新掃描' if new_day else '掃描中｜正在啟動或接續前次進度'))
        stop=self.stop_event;target=self.auto_hwnd
        # Keep the main dashboard visible on the user's secondary display.
        self.show_task_progress(target,'接續前次斷點，正在核對畫面…' if saved else '前次進度已封存，開始今日第1輪…' if new_day else '準備開始自動輪切…')
        if announce:self.announce_task('開始測試輪切' if test_only else ('接續前次掃描' if saved else '開始執行掃描'))
        def job():
            nonlocal saved
            diagnostic=dict(cycle=saved['cycle'] if saved else 1,ranking='啟動',segment=0,count=0,stage='檢查視窗與表頭')
            try:
                c=WindowsController(target,stop)
                c.highlight=lambda region:self.prepare_scan_capture(token,region,stop)
                if list((c.initial_rect[2]-c.initial_rect[0],c.initial_rect[3]-c.initial_rect[1]))!=auto.get('window_size'):
                    raise ValueError('三竹視窗尺寸與框選時不同，請重新設定位置')
                c.activate()
                self.hide_scan_cursor(token)
                threading.Thread(target=c.monitor_hotkey,daemon=True).start()
                from adaptive import Experience
                from MitakeDetector import BASE
                experience=Experience(BASE/'automation-experience.json',
                    {k:auto.get(k) for k in ('title','window_size','combo','menu','table')},auto.get('adaptive',True))
                switcher=RankingSwitcher(c,self.read_auto_ocr,auto['combo'],auto['menu'],experience=experience,ocr_retry=self.read_menu_ocr_retry,status=lambda detail:self.events.put((token,'auto_progress',(detail,None,None))))
                if auto.get('discovered'):
                    from auto_detect import cached_layout
                    self.events.put((token,'auto_status','快速核對表頭頭尾位置，沿用已確認欄位…'))
                    full,_=c.image([0,0,1,1])
                    layout,reused=cached_layout(full,self.read_auto_ocr,BASE/'header-layout.json',cfg.get('column_bounds'),auto.get('table'));c.check()
                    self.events.put((token,'auto_status','表頭頭尾一致，已略過逐欄辨識' if reused else '表頭配置已重新確認並記住'))
                    cfg['column_bounds']=layout['column_bounds'];auto['table']=layout['table']
                priority_last={}
                cycle=saved['cycle'] if saved else 1
                known_totals={}
                start_index=saved['rank'] if saved else 0
                while not stop.is_set():
                    for rank_index in range(start_index,len(auto['rankings'])):
                        name=auto['rankings'][rank_index]
                        diagnostic.update(cycle=cycle,ranking=name,segment=0,count=0,stage='切換排行')
                        self.events.put((token,'auto_phase',f'掃描中｜第{cycle}輪｜{name}'))
                        table_resume=saved.get('table') if saved and rank_index==saved['rank'] else None
                        if store:store.update(context,dict(cycle=cycle,rank=rank_index,table=table_resume))
                        c.check();self.events.put((token,'auto_status',f'第{cycle}輪｜排行{rank_index+1}/{len(auto["rankings"])}：正在切換{name}'))
                        switcher.switch(name)
                        self.events.put((token,'auto_status','已確認排行：'+name))
                        if test_only:continue
                        collected={};rejected=0;begin=time.monotonic()
                        counted=set(q['code'] for q in (table_resume or {}).get('quotes',[]))
                        def counts(done=False):
                            if done and auto.get('full_table',True):known_totals[name]=len(counted)
                            total=known_totals.get(name)
                            total_text=str(total) if total is not None and total>=len(counted) else '待完整掃描確認'
                            text=f'{name}：已掃描 {len(counted)} 股／總股數 {total_text}'+('（上輪參考）' if total is not None and not done else '')
                            text+=f'｜目前第 {cycle} 輪｜累計完成 {cycle-1} 遍'
                            self.events.put((token,'auto_counts',text))
                        counts()
                        table=absolute_roi(auto['table'],c.rect());center=(table['left']+table['width']//2,table['top']+table['height']//2)
                        from table_scan import scan_table
                        def progress(segment,count,stage):
                            diagnostic.update(segment=segment,count=count,stage=stage,seconds_per_segment=getattr(self,"last_table_seconds",0))
                            reused=getattr(getattr(self,'stock_names',None),'reused',0)
                            seconds=getattr(self,'last_table_seconds',0)
                            detail=f'第{cycle}輪｜類目{rank_index+1}/{len(auto["rankings"])} {name}｜第{segment}段｜已讀{count}檔｜股名重用{reused}次｜{stage}｜{seconds:.1f}秒/段'
                            self.events.put((token,'auto_progress',(detail,rank_index,len(auto['rankings']))))
                        def read(image):
                            started=time.monotonic();self.events.put((token,'auto_rate',dict(start=started)))
                            try:
                                result=self.scan_recovering_table(image,cfg['column_bounds']) if auto.get('discovered') else self.scan(image,cfg['centers'])
                                self.events.put((token,'auto_rate',dict(elapsed=time.monotonic()-started,count=len(result[0]))))
                                return result
                            except Exception:
                                self.events.put((token,'auto_rate',dict(elapsed=time.monotonic()-started,count=0)))
                                raise
                        def recover_layout():
                            from auto_detect import detect_layout
                            diagnostic.update(stage='掃描中重新定位表頭')
                            self.events.put((token,'auto_status','讀不到股票資料：重新定位表頭與欄位，保留目前進度…'))
                            c.check();full,_=c.image([0,0,1,1])
                            try:
                                layout=detect_layout(full,self.read_auto_ocr,require_combo=False)
                            except ValueError as error:
                                self.events.put((token,'auto_status','表頭重定位尚未成功：'+str(error)))
                                diagnostic.update(layout_recovery_error=str(error))
                                return False
                            c.check()
                            if not switcher.label_matches(name):
                                raise RuntimeError('表頭重定位時排行標題無法確認，進度已保留')
                            cfg['column_bounds']=layout['column_bounds']
                            # scan_table holds this list; mutate it so fresh captures
                            # and overlap checks both use the new table geometry.
                            auto['table'][:]=layout['table']
                            auto['discovered']=True
                            diagnostic.update(stage='表頭已重定位，重讀當前區段',column_bounds=layout['column_bounds'])
                            self.events.put((token,'auto_status','已更新欄位位置，繼續讀取當前區段'))
                            return True
                        priority_budget=[3]
                        first_batch=[not bool(table_resume)]
                        def observed(quotes,bad):
                            if not switcher.label_matches(name):raise RuntimeError('本段股票類目改變，未接收本段資料')
                            c.check()
                            current=dict(cfg,source_rank=name,source_partial=True,source_reset=first_batch[0],scan_cycle=cycle)
                            first_batch[0]=False
                            counted.update(q.code for q in quotes);counts()
                            self.events.put((token,'auto_quotes',(name,quotes,bad,time.monotonic()-begin,current)))
                            try:return prioritized_recheck(quotes,current)
                            except AutomationStopped:raise
                            except Exception as error:
                                priority_budget[0]=0
                                diagnostic.update(stage='優先複查失敗，主掃描繼續',optional_error=str(error))
                                from scan_diagnostics import record_interruption
                                from MitakeDetector import VERSION
                                location=record_interruption(BASE/'scan-interruptions.jsonl',VERSION,diagnostic,error)
                                self.events.put((token,'auto_status','優先複查暫停，主掃描繼續：'+str(error)+'｜'+location))
                                return []
                        def prioritized_recheck(quotes,current):
                            # Recheck only a few near-target rows before leaving
                            # this viewport. Full-market traversal is never skipped.
                            if not auto.get('discovered') or priority_budget[0]<=0:return []
                            source=getattr(self,'fast_row_source',None)
                            if not source:return []
                            from candidate_ranking import assess
                            selected=[]
                            for q in quotes:
                                match=assess((q,None,'等待觀測突破守穩',False),cfg)
                                if (q.price>=cfg['min_price'] and q.volume>=cfg['min_volume']
                                        and cfg['min_gain']<=q.gain<=cfg['max_gain']
                                        and match['count']>=3+int(bool(cfg['use_turnover']))
                                        and time.monotonic()-priority_last.get(q.code,-1e20)>=20):selected.append(q.code)
                            selected=set(selected[:3])
                            if not selected:return []
                            image,bounds,anchors,scores=source
                            sections=[(code,max(0,int(y-h*.8)),min(image.height,int(y+h*.8)+1))
                                      for code,y,h in anchors if code in selected and scores.get(code,0)>=.92]
                            if not sections:return []
                            priority_budget[0]-=1
                            for code in selected:priority_last[code]=time.monotonic()
                            self.events.put((token,'auto_status',f'候選優先複查：{len(sections)}檔｜數字快掃，股名稍後補讀'))
                            c.check();fresh,_=c.image(auto['table'])
                            from PIL import Image
                            height=8+sum(bottom-top+16 for _,top,bottom in sections)
                            sheet=Image.new('RGB',(fresh.width,height),'black');y=8
                            for code,top,bottom in sections:
                                sheet.paste(fresh.crop((0,top,fresh.width,bottom)),(0,y));y+=bottom-top+16
                            self.focus_columns(fresh,bounds,['code','price','gain','volume','turnover'],'候選小區塊優先複查',
                                               (min(top for _,top,bottom in sections),max(bottom for _,top,bottom in sections)))
                            from fast_quotes import scan_numeric_batch
                            extra,extra_bad=scan_numeric_batch(sheet,cfg['column_bounds'],self.read_auto_ocr,self.stock_names,
                                                              memory=self.get_ocr_learning(),defer_names=True)
                            extra=[q for q in extra if q.code in selected]
                            for q in extra:q.ts=fresh.info.get('captured_at',q.ts)
                            c.check()
                            if extra and switcher.label_matches(name):
                                self.events.put((token,'auto_quotes',(name,extra,extra_bad,time.monotonic()-begin,dict(current,source_reset=False))))
                                return extra
                            return []
                        table_state=[table_resume]
                        def checkpoint(state):
                            table_state[0]=state
                            diagnostic.update(last_codes=state.get("codes",[]),wheel_offset=state.get("offset",0),scroll_notches=state.get("notches",1))
                            if store:store.update(context,dict(cycle=cycle,rank=rank_index,table=state))
                        from table_scan import scan_with_recovery
                        def attempt_scan(resume):
                            return scan_table(c,auto['table'],read,progress,observed=observed,resume=resume,checkpoint=checkpoint,
                                                   limit_pages=None if auto.get('full_table',True) else auto['pages'],scroll_cap=experience.scroll_cap(name),
                                                   learn_scroll=lambda cap,recovered:experience.record_scroll(name,cap,recovered),
                                                   read_anchors=(lambda image:self.read_stock_anchors(image,cfg['column_bounds'])) if auto.get('discovered') else None,
                                                   recover_layout=recover_layout)
                        def recovered_error(error):
                            from scan_diagnostics import record_interruption
                            from MitakeDetector import VERSION
                            diagnostic.update(stage='暫時中斷，正在自動恢復')
                            record_interruption(BASE/'scan-interruptions.jsonl',VERSION,diagnostic,error)
                        quotes,rejected=scan_with_recovery(attempt_scan,table_state,c.check,
                            lambda detail:self.events.put((token,'auto_status',detail)),c.pause,recovered_error)
                        saved=None
                        collected={q.code:q for q in quotes}
                        counted.update(collected);counts(done=True)
                        # Verify label again after scanning, before any observations are accepted.
                        if not switcher.label_matches(name):raise RuntimeError('讀取後排行標題改變，已停止且捨棄本次資料')
                        c.check()
                        current=dict(cfg,source_rank=name)
                        if store:
                            next_rank=(rank_index+1)%len(auto['rankings'])
                            store.update(context,dict(cycle=cycle+(1 if next_rank==0 else 0),rank=next_rank,table=None))
                        experience.observed(name,bool(collected) and rejected==0)
                        dwell=experience.wait(name,auto['seconds'])
                        mode='自適應加速' if experience.enabled else '固定間隔'
                        diagnostic.update(stage='排行讀取完成，等待下次切換',count=len(collected))
                        wait_until=time.monotonic()+dwell
                        if getattr(self,'deferred_names',None):
                            self.events.put((token,'auto_status','數字篩選已完成：利用輪切等待時間批次補讀股名（最多8檔）'))
                            try:self.learn_deferred_names(c.check)
                            except AutomationStopped:raise
                            except Exception as error:
                                diagnostic.update(stage='股名補讀失敗，主掃描繼續',optional_error=str(error))
                                from scan_diagnostics import record_interruption
                                from MitakeDetector import VERSION
                                location=record_interruption(BASE/'scan-interruptions.jsonl',VERSION,diagnostic,error)
                                self.events.put((token,'auto_status','股名補讀失敗，已略過並繼續數字掃描：'+str(error)+'｜'+location))
                        while time.monotonic()<wait_until:
                            remaining=max(1,int(wait_until-time.monotonic()+.999))
                            self.events.put((token,'auto_progress',(f'{name}｜已讀{len(collected)}檔｜略過{rejected}列｜{mode}｜{remaining}秒後繼續｜OCR重用{getattr(self,"ocr_cache_hits",0)}次',rank_index+1,len(auto['rankings']))))
                            c.pause(min(1,max(0,wait_until-time.monotonic())))
                    if test_only:break
                    self.events.put((token,'auto_counts',f'本輪排行掃描完成｜累計完成 {cycle} 遍｜準備第 {cycle+1} 輪'))
                    self.events.put((token,'auto_phase',f'第{cycle}輪已完成｜自動開始第{cycle+1}輪'))
                    cycle+=1;start_index=0
                self.events.put((token,'auto_end','輪切測試完成' if test_only else '自動輪切已停止'))
            except Exception as exc:
                from scan_diagnostics import record_interruption
                from MitakeDetector import BASE,VERSION
                location=record_interruption(BASE/'scan-interruptions.jsonl',VERSION,diagnostic,exc)
                self.events.put((token,'auto_end','掃描已暫停，進度保留：'+str(exc)+'｜'+location))
            finally:
                self.restore_scan_cursor();stop.set()
        def launch():
            if token!=self.generation:return
            self.worker_thread=threading.Thread(target=job,daemon=True);self.worker_thread.start()
        self.root.after(700,launch)
