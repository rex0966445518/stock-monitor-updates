from stock_codes import excluded_only
"""Setup and execution UI for the calibrated ranking rotation."""
import json
import datetime as dt
import threading
import time
from autoswitch import WindowsController, RankingSwitcher, RANKINGS, absolute_roi, validate_roi, F8Toggle, AutomationStopped


def migrate_read_interval(config):
    """One-time upgrade of the old 15-second default; retain later user edits."""
    auto=config.get('auto_config')
    if not isinstance(auto,dict) or auto.get('read_interval_v2'):return False
    if auto.get('seconds',15) in (15,'15','15.0'):auto['seconds']=2
    auto['read_interval_v2']=True
    return True


def calibrated_auto(previous, **geometry):
    """Replace device geometry without replacing the user's scan preferences."""
    auto=dict(rankings=['漲幅排行','預估量增','分時買力','大單流入','連續買單'],
              seconds=2,pages=1,full_table=True,adaptive=True,read_interval_v2=True)
    auto.update(json.loads(json.dumps(previous or {})))
    auto.update(geometry)
    return auto


class AutoUI:
    def persist_auto_preferences(self, auto):
        previous=self.config
        self.config=dict(previous,auto_config=json.loads(json.dumps(auto)))
        try:self.save()
        except Exception:
            self.config=previous
            raise

    def hide_scan_cursor(self,token):
        from cursor_visibility import CursorVisibility
        self.restore_scan_cursor()
        guard=CursorVisibility();self.cursor_visibility=guard
        if not guard.start() and guard.error:self.events.put((token,'cursor_note','游標隱藏未啟用：'+guard.error))

    def restore_scan_cursor(self):
        guard=getattr(self,'cursor_visibility',None)
        if guard:guard.close()

    def poll_capture_clear(self):
        """Service capture barriers independently of expensive quote rendering."""
        try:
            pending=getattr(self,'outline_clear_pending',None)
            if pending and not pending[1].is_set():
                token,ready=pending
                if token==self.generation:
                    notice=getattr(self,'candidate_notice',None)
                    if notice and notice.alive:notice.window.withdraw()
                    self.show_scan_outline(None)
                    self.root.update_idletasks()
                ready.set()
            display=getattr(self,'outline_show_pending',None)
            if display and not display[2].is_set():
                token,region,ready=display
                if token==self.generation and getattr(self,'outline_disabled_token',None)!=token:
                    self.show_scan_outline(region);self.root.update_idletasks()
                ready.set()
        except Exception:
            # Keep the same barrier pending: a failed withdraw must be retried.
            pass
        finally:
            self.root.after(25,self.poll_capture_clear)

    def highlight_scan(self,token,region,stop):
        from autoswitch import AutomationStopped
        if stop.is_set():raise AutomationStopped('已停止自動操作')
        # Decorative borders may be disabled, but clearing old borders before
        # capture must still be acknowledged to avoid OCR of stale overlays.
        if region and getattr(self,'outline_disabled_token',None)==token:return
        pending=getattr(self,'outline_clear_pending',None)
        if region is None and pending and pending[0]==token:
            ready=pending[1]
        else:
            ready=threading.Event()
            self.outline_clear_pending=(token,ready) if region is None else None
            if region is None:
                previous=getattr(self,'outline_show_pending',None)
                if previous:previous[2].set()
                self.outline_show_pending=None
            else:self.outline_show_pending=(token,region,ready)
            self.events.put((token,'scan_region',(region,ready)))
        deadline=time.monotonic()+(3.0 if region is None else .5)
        while not ready.wait(.02):
            if stop.is_set():raise AutomationStopped('已停止自動操作')
            if time.monotonic()>deadline:
                already_disabled=getattr(self,'outline_disabled_token',None)==token
                self.outline_disabled_token=token
                if not already_disabled:self.events.put((token,'ranking_skipped',dict(ranking='掃描綠框提示',cycle=0,reason='綠框回應逾時，已停用本次掃描綠框；等待原清除確認後繼續，未確認的畫面略過。')))
                if region is None:raise RuntimeError('綠框尚未清除，略過本次擷取，繼續下一排行')
                return
        if stop.is_set():raise AutomationStopped('已停止自動操作')

    def show_scan_outline(self,region):
        from scan_outline import ScanOutline
        outline=getattr(self,'scan_outline',None)
        if outline is None and not region:return
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

    def focus_numeric_cells(self,image,bounds,cells,stage):
        source=getattr(self,'scan_capture_region',None);context=getattr(self,'scan_focus_context',None)
        if not source or not context or (source['width'],source['height'])!=image.size:return
        regions=[]
        for cell in cells[:16]:
            left,right=bounds[cell['field']]
            x=round(left*image.width);end=round(right*image.width)
            regions.append(dict(left=source['left']+x,top=source['top']+cell['top'],
                                width=end-x,height=cell['bottom']-cell['top']))
        token,stop=context
        self.events.put((token,'auto_status',f'{stage}｜本批 {len(cells)} 格'+('｜框示前16格' if len(cells)>16 else '')))
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
        self.page_review_config=None
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
                discovered=self.detect_layout_recovering(im);c.check()
                combo=absolute_roi(discovered['combo'],c.rect())
                c.click((combo['left']+combo['width']//2,combo['top']+combo['height']//2));c.pause(.6)
                self.events.put((token,'auto_status','正在定位展開選單…'))
                opened,_=c.image([0,0,1,1])
                menu=detect_menu(opened,discovered['combo'],self.read_auto_ocr);c.check()
                auto=calibrated_auto(cfg.get('auto_config'),title=title,window_size=[im.width,im.height],
                                     combo=discovered['combo'],menu=menu,table=discovered['table'],discovered=1)
                # The popup is known to be open after detect_menu: select directly.
                RankingSwitcher(c,self.read_auto_ocr,auto['combo'],auto['menu'],ocr_retry=self.read_menu_ocr_retry,status=lambda detail:self.events.put((token,'auto_progress',(detail,None,None)))).switch('漲幅排行',menu_open=True)
                self.events.put((token,'auto_status','正在讀取股票資料並驗證欄位…'))
                quotes=self.validate_setup_table(c,auto,discovered['column_bounds'],token)
                cfg.update(auto_config=auto,column_bounds=discovered['column_bounds'],region=absolute_roi(auto['table'],c.rect()))
                self.events.put((token,'auto_setup_done',(cfg,target,len(quotes),start_after)))
            except Exception as exc:self.events.put((token,'auto_end','自動設定未完成：'+str(exc)+'；可使用手動設定。'))
            finally:
                self.events.put((token,'menu_numbers',None));self.restore_scan_cursor();stop.set()
        def launch():
            if token!=self.generation:return
            self.worker_thread=threading.Thread(target=job,daemon=True);self.worker_thread.start()
        self.root.after(700,launch)

    def validate_setup_table(self,c,auto,bounds,token):
        """Retry fresh, small samples before rejecting a newly calibrated device."""
        from ocr_recovery import fresh_ocr
        diagnostics=[]
        for attempt in range(3):
            c.check()
            if attempt:c.pause(.5)
            image,_=c.image(auto['table'])
            if attempt:
                # Keep X coordinates/column bounds intact. Short bands avoid
                # shrinking tall columns on a new screen or DPI configuration.
                top=0 if attempt==1 else min(256,max(0,image.height-384))
                image=image.crop((0,top,image.width,min(image.height,top+384)))
                self.events.put((token,'auto_status',f'表頭定位已完成｜資料列驗證重試 {attempt}/2：重新擷取並分區辨識'))
                with fresh_ocr(self):quotes,bad=self.scan_recovering_table(image,bounds)
            else:quotes,bad=self.scan_recovering_table(image,bounds)
            c.check()
            nav=(getattr(self,'fast_code_cache',None) or (None,None,[]))[2]
            if quotes or excluded_only(nav):return quotes
            diagnostics.append(getattr(self,'last_scan_diagnostic','未辨識到完整資料列'))
        raise ValueError('表頭定位已完成；資料列驗證3次仍未通過，尚未儲存新設定。'+
                         diagnostics[-1]+'。請確認股號、成交價、漲幅、成交量、換手率均完整可見，且清單已有行情資料。')

    def remember_fast_rows(self,image,bounds,anchors,scores):
        import hashlib
        self.fast_row_source=(image,bounds,anchors,scores)
        self.fast_code_cache=(hashlib.sha256(image.tobytes()).digest(),bounds,
                              [code for code,y,h in anchors if scores.get(code,0)>=.90])
        if getattr(self,'numeric_priority',False):return
        pending=getattr(self,'deferred_names',{})
        tried=getattr(self,'deferred_name_tried',{})
        names=getattr(self,'stock_names',None)
        left,right=bounds['name']
        for code,y,h in anchors:
            if len(code)!=4 or not code.isascii() or not code.isdigit() or scores.get(code,0)<.92 or not names or code in names.names:continue
            top=max(0,int(y-h*.7));bottom=min(image.height,int(y+h*.7)+1)
            if bottom<=top:continue
            crop=image.crop((round(left*image.width),top,round(right*image.width),bottom))
            proof=hashlib.sha256(crop.tobytes()).hexdigest()
            if tried.get(code)==proof:continue
            pending[code]=(crop,proof)
        while len(pending)>1000:pending.pop(next(iter(pending)))
        self.deferred_names=pending

    def learn_deferred_names(self,check=lambda:None,codes=None):
        """One bounded name sheet in the existing ranking wait, never per row."""
        from PIL import Image
        pending=getattr(self,'deferred_names',{})
        names=getattr(self,'stock_names',None)
        if not pending or not names:return 0
        sections=[];height=8;width=1
        selected=set(codes) if codes is not None else None
        for code in [c for c in list(pending) if selected is None or c in selected][:32]:
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
            if confidence>=.90 and re.fullmatch(r'[0-9]{4,12}[A-Za-z]{0,2}',text):codes.append(text)
        return codes

    def scan_mapped_table(self,image,bounds):
        from MitakeDetector import FIELDS,parse_ocr
        import re,unicodedata
        tokens=[]
        for i,field in enumerate(FIELDS):
            if field=='name' and getattr(self,'numeric_priority',False):continue
            if field=='name':
                names=getattr(self,'stock_names',None)
                codes=[(box,str(text),score) for box,text,score in tokens if sum(p[0] for p in box)/4==50 and re.fullmatch(r'[0-9]{4}',str(text))]
                if names and codes and all(code in names.names and score>=.92 for box,code,score in codes):
                    tokens.extend(([[x+100,y] for x,y in box],names.names[code],1.) for box,code,score in codes)
                    continue
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
        self.fast_code_cache=None
        try:
            result=self._scan_recovering_table(image,bounds)
            names=getattr(self,'stock_names',None)
            unknown=[q.code for q in result[0] if names and q.code not in names.names]
            if unknown and not getattr(self,'numeric_priority',False):
                def check():
                    if getattr(self,'stop_event',None) and self.stop_event.is_set():
                        from autoswitch import AutomationStopped
                        raise AutomationStopped('股名補讀已停止')
                try:self.learn_deferred_names(check,codes=unknown)
                except Exception as exc:
                    from autoswitch import AutomationStopped
                    if isinstance(exc,AutomationStopped):raise
                    self.last_name_error=str(exc)
            for q in result[0]:
                q.ts=captured_at
                if names:q.name=names.names.get(q.code,q.name)
            if names and getattr(names,'last_error',''):self.last_scan_diagnostic+='｜股名資料庫寫入未完成：'+names.last_error
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
        memory=self.get_ocr_learning()
        if getattr(self,'numeric_priority',False):
            from numeric_cells import scan_cells
            from collections import OrderedDict
            if not hasattr(self,'numeric_cell_cache'):self.numeric_cell_cache=OrderedDict()
            cells=scan_cells(image,bounds,self.read_auto_ocr,names,self.numeric_cell_cache,memory=memory,
                force=getattr(self,'force_fresh_ocr',False),focus=lambda fields,stage:self.focus_columns(image,bounds,fields,stage),
                anchors_ready=lambda anchors,scores:self.remember_fast_rows(image,bounds,anchors,scores),
                recognize_cells=self.read_numeric_cells,
                focus_cells=lambda cells,stage:self.focus_numeric_cells(image,bounds,cells,stage),
                review_if=self.review_candidate_cells if getattr(self,'page_review_config',None) else None,
                budget_ok=lambda:time.monotonic()<getattr(self,'page_deadline',float('inf')),
                metrics_ready=lambda metrics:setattr(self,'cell_metrics',metrics))
            if cells is not None and (getattr(self,'page_review_config',None) or cells[0] or excluded_only((getattr(self,'fast_code_cache',None) or (None,None,[]))[2])):
                quotes,bad,metrics=cells
                self.last_scan_diagnostic=f'變動格快掃｜重用{metrics["reused"]}格｜重讀{metrics["changed"]}格｜有效{len(quotes)}列｜跳過長股號{metrics.get("skipped",0)}列'
                self.cell_metrics=metrics
                return quotes,bad
        if memory and getattr(self,'force_fresh_ocr',False):
            from ocr_recovery import FreshMemory
            memory=FreshMemory(memory)
        quotes,bad=scan_numeric_batch(image,bounds,self.read_auto_ocr,names,memory=memory,focus=lambda fields,stage,row=None:self.focus_columns(image,bounds,fields,stage,row),
                                      defer_names=getattr(self,'numeric_priority',False),
                                      anchors_ready=lambda anchors,scores:self.remember_fast_rows(image,bounds,anchors,scores))
        nav=(getattr(self,'fast_code_cache',None) or (None,None,[]))[2]
        if not quotes and excluded_only(nav):
            self.last_scan_diagnostic=f'跳過超過四位股號 {len(nav)} 列';return [],0
        if not quotes and (getattr(self,'force_fresh_ocr',False) or max(image.size)>1600):
            from ocr_recovery import tiled_numeric,fresh_ocr,FreshMemory
            memory=self.get_ocr_learning()
            with fresh_ocr(self):
                quotes,bad,anchors,scores=tiled_numeric(image,bounds,self.read_auto_ocr,names,
                    memory=FreshMemory(memory) if memory else None,status=lambda box:self.recovery_region(image,box,'分區放大複查數字'))
            self.remember_fast_rows(image,bounds,anchors,scores)
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

    def recovery_region(self,image,box,stage):
        source=getattr(self,'scan_capture_region',None);context=getattr(self,'scan_focus_context',None)
        if not source or not context:return
        token,stop=context
        left,top,right,bottom=box
        self.events.put((token,'auto_status',f'{stage}｜x={left},y={top},w={right-left},h={bottom-top}'))
        self.highlight_scan(token,dict(left=source['left']+left,top=source['top']+top,width=right-left,height=bottom-top),stop)

    def detect_layout_recovering(self,image,require_combo=True):
        from auto_detect import detect_layout
        from ocr_recovery import fresh_ocr,tiled_tokens,zoom_tokens
        try:return detect_layout(image,self.read_auto_ocr,require_combo=require_combo)
        except AutomationStopped:raise
        except (ValueError,RuntimeError):pass
        with fresh_ocr(self):
            try:return detect_layout(image,lambda crop:tiled_tokens(crop,self.read_auto_ocr),require_combo=require_combo)
            except AutomationStopped:raise
            except (ValueError,RuntimeError):pass
            failures=[]
            for scale in (2,3,.75):
                context=getattr(self,'scan_focus_context',None)
                if context:
                    token,stop=context
                    if stop.is_set():raise AutomationStopped('使用者已停止')
                    self.events.put((token,'auto_status',f'表頭重新定位｜{image.width}×{image.height}｜文字縮放 {scale:g} 倍確認中'))
                try:
                    return detect_layout(image,lambda crop:tiled_tokens(crop,
                        lambda tile:zoom_tokens(tile,self.read_auto_ocr,scale),width=384,height=192,overlap=32),require_combo=require_combo)
                except AutomationStopped:raise
                except (ValueError,RuntimeError) as error:failures.append(str(error))
            raise ValueError(f'表頭定位失敗｜視窗 {image.width}×{image.height}｜已完成原圖、分區、2倍、3倍及縮小複查。'+failures[-1])

    def adapt_scan_layout(self,c,auto,cfg,token,ranking):
        """Reuse saved coordinates on unchanged windows; recalibrate after resize."""
        self._startup_rank_error=None;self._scan_menu_open=False
        from auto_detect import cached_layout,detect_menu
        from MitakeDetector import BASE
        size=[c.initial_rect[2]-c.initial_rect[0],c.initial_rect[3]-c.initial_rect[1]]
        resized=size!=auto.get('window_size')
        if not resized:
            bounds=cfg.get('column_bounds',{})
            valid=isinstance(bounds,dict) and {'code','name','price','gain','volume','turnover'}.issubset(bounds) and all(isinstance(pair,(list,tuple)) and len(pair)==2 and all(type(v) in (int,float) for v in pair) and 0<=pair[0]<pair[1]<=1 for pair in bounds.values())
            if not auto.get('discovered') or valid:
                self.layout_coordinates_locked=True
                cfg['region']=absolute_roi(auto['table'],c.rect())
                self.events.put((token,'auto_status','沿用已儲存欄位座標｜略過表頭 OCR；需要變更請按「重新自動定位」'))
                return False
        self.layout_coordinates_locked=False
        self.events.put((token,'auto_status',f'目前視窗 {size[0]}×{size[1]}｜'+('解析度或視窗大小已變更，自動重新定位表頭與選單' if resized else '核對表頭頭尾位置，必要時自動重新定位')))
        full,_=c.image([0,0,1,1])
        layout,reused=cached_layout(full,self.read_auto_ocr,BASE/'header-layout.json',
            None if resized else cfg.get('column_bounds'),None if resized else auto.get('table'),detector=self.detect_layout_recovering)
        c.check()
        menu_changed=resized or layout['combo']!=auto.get('combo')
        menu=auto['menu']
        if menu_changed:
            self.events.put((token,'auto_status','已找到新表頭｜正在重新定位排行下拉選單'))
            combo=absolute_roi(layout['combo'],c.rect())
            c.click((combo['left']+combo['width']//2,combo['top']+combo['height']//2));c.pause(.6)
            opened,_=c.image([0,0,1,1])
            menu=detect_menu(opened,layout['combo'],self.read_auto_ocr);c.check()
            picker=RankingSwitcher(c,self.read_auto_ocr,layout['combo'],menu,ocr_retry=self.read_menu_ocr_retry,
                status=lambda detail:self.events.put((token,'auto_progress',(detail,None,None))),
                numbering=auto.get('menu_numbering',True),menu_numbers=auto.get('menu_numbers',{}),
                show_numbers=lambda data:self.events.put((token,'menu_numbers',data)))
            try:picker.switch(ranking,menu_open=True)
            except AutomationStopped as exc:
                if not str(exc).startswith(('選單中找不到','切換後標題')):raise
                c.check()
                self._startup_rank_error=(ranking,exc);self._scan_menu_open=picker.menu_is_open
        geometry_changed=resized or layout['column_bounds']!=cfg.get('column_bounds') or layout['table']!=auto.get('table') or menu_changed
        auto.update(window_size=size,combo=layout['combo'],menu=menu,table=layout['table'],discovered=1)
        cfg.update(auto_config=auto,column_bounds=layout['column_bounds'],region=absolute_roi(layout['table'],c.rect()))
        self.events.put((token,'auto_layout_done',dict(auto_config=auto.copy(),column_bounds=layout['column_bounds'],region=cfg['region'])))
        self.events.put((token,'auto_status','表頭頭尾一致，已略過逐欄辨識' if reused else f'已適應 {size[0]}×{size[1]} 視窗｜表頭與操作位置已確認'))
        self.layout_coordinates_locked=True
        return geometry_changed

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
            (BASE/'stock-names.sqlite3').unlink(missing_ok=True)
            self.deferred_names={};self.deferred_name_tried={}
            self.stock_names=None
            self.say('股名記憶已清除，下次讀取時重新學習。')
        except OSError as exc:self.say('無法清除股名記憶：'+str(exc))

    def init_f8(self):
        self.f8_toggle=F8Toggle()
        if __import__('os').name=='nt':self.root.after(20,self.poll_f8)

    def poll_f8(self):
        import ctypes
        busy=bool(getattr(self,'scan_retry_timer',None)) or self.auto_active or bool(self.worker_thread and self.worker_thread.is_alive())
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

    def build_filter_settings(self,parent):
        self.ttk.Label(parent,text='變更後請儲存，並重新開始掃描以套用。',wraplength=800).pack(anchor='w',padx=18,pady=15)
        form=self.ttk.Frame(parent);form.pack(fill='x',padx=18)
        fields=[('min_price','股價 ≥'),('min_volume','總量張 ≥'),('min_gain','漲幅 ≥%'),('max_gain','漲幅 ≤%'),('interval','間隔秒'),('cooldown','提醒間隔秒'),('min_turnover','換手率 ≥%'),('accel','近5分量速比 ≥')]
        for row,(key,label) in enumerate(fields):
            self.ttk.Label(form,text=label).grid(row=row,column=0,sticky='w',pady=8)
            self.ttk.Entry(form,textvariable=self.vars[key],width=12).grid(row=row,column=1,sticky='w',padx=12)
        for key,label in [('use_turnover','啟用換手率條件'),('use_accel','啟用近5分量速比'),('use_breakout','觀測突破後連續兩次守穩')]:
            self.ttk.Checkbutton(parent,text=label,variable=self.vars[key]).pack(anchor='w',padx=18,pady=6)
        def save_conditions():
            if self.settings():self.say('篩選條件已儲存，重新開始掃描後套用')
        self.ttk.Button(parent,text='儲存篩選條件',command=save_conditions).pack(anchor='w',padx=18,pady=15)

    def configure_auto(self):
        from tkinter import messagebox
        if not self.idle():return
        if __import__('os').name!='nt':
            messagebox.showinfo('Windows功能','自動輪切需要在Windows安裝版使用。');return
        top=self.tk.Toplevel(self.root);top.title('三竹自動輪切設定');top.geometry(f'{min(960,self.root.winfo_screenwidth()-40)}x{min(850,self.root.winfo_screenheight()-90)}')
        buttons=self.ttk.Frame(top);buttons.pack(side='top',fill='x',padx=15,pady=8)
        tabs=self.ttk.Notebook(top);tabs.pack(fill='both',expand=True)
        conditions=self.ttk.Frame(tabs);rotation=self.ttk.Frame(tabs)
        tabs.add(conditions,text='篩選條件');tabs.add(rotation,text='輪切排行與定位')
        scroller=self.tk.Canvas(rotation,highlightthickness=0)
        bar=self.ttk.Scrollbar(rotation,orient='vertical',command=scroller.yview)
        scroller.configure(yscrollcommand=bar.set);bar.pack(side='right',fill='y');scroller.pack(fill='both',expand=True)
        body=self.ttk.Frame(scroller);body_id=scroller.create_window((0,0),window=body,anchor='nw')
        body.bind('<Configure>',lambda e:scroller.configure(scrollregion=scroller.bbox('all')))
        scroller.bind('<Configure>',lambda e:scroller.itemconfigure(body_id,width=e.width))
        self.build_filter_settings(conditions)
        cfg=json.loads(json.dumps(self.config.get('auto_config') or {}))
        windows=[w for w in WindowsController.windows() if '漲停板探測器-雷允澤製' not in w[1] and '三竹盤中轉強偵測器' not in w[1] and '三竹自動輪切設定' not in w[1]]
        choices=[f'{title}  [視窗 {hwnd}]' for hwnd,title in windows]
        self.ttk.Button(body,text='一鍵自動設定（免框選）',command=lambda:(top.destroy(),self.auto_setup())).pack(anchor='w',padx=15,pady=5)
        self.ttk.Label(body,text='1. 選擇三竹視窗（啟動後會使用滑鼠；F8開始／停止，可操作探測器主頁，三竹畫面保持可見）').pack(anchor='w',padx=15,pady=10)
        choice=self.tk.StringVar()
        combo=self.ttk.Combobox(body,textvariable=choice,values=choices,state='readonly',width=90);combo.pack(padx=15,fill='x')
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
        row=self.ttk.Frame(body);row.pack(fill='x',padx=15,pady=10)
        for key,title in [('combo','框選排行下拉框'),('menu','框選展開選單'),('table','框選資料表格')]:
            self.ttk.Button(row,text=title,command=lambda k=key:region(k)).pack(side='left',padx=4)
            labels[key]=self.tk.StringVar(value='已設定' if cfg.get(key) else '未設定')
            self.ttk.Label(row,textvariable=labels[key]).pack(side='left',padx=3)
        self.ttk.Label(body,text='2. 勾選要輪切的排行。表格需沿用主畫面的六欄位置校正；各排行欄位須一致。').pack(anchor='w',padx=15,pady=5)
        ranks=self.ttk.Frame(body);ranks.pack(fill='x',padx=15)
        enabled=set(cfg.get('rankings',['漲幅排行','預估量增','大單流入','大單匯集']))
        if enabled-set(RANKINGS):
            self.ttk.Label(body,text='舊設定含未列於錄影選單的排行：'+ '、'.join(sorted(enabled-set(RANKINGS)))+'；儲存後依目前勾選更新，單量排行請另行勾選。',wraplength=810).pack(anchor='w',padx=15)
        rank_vars={}
        for i,name in enumerate(RANKINGS):
            var=self.tk.BooleanVar(value=name in enabled);rank_vars[name]=var
            self.ttk.Checkbutton(ranks,text=name,variable=var).grid(row=i//5,column=i%5,sticky='w',padx=5,pady=2)
        numbered=self.ttk.Frame(tabs);tabs.add(numbered,text='排行編號（手動校正）')
        number_vars=self.build_menu_number_settings(numbered,cfg)
        fields=self.ttk.Frame(body);fields.pack(fill='x',padx=15,pady=10)
        delay=self.tk.StringVar(value=str(cfg.get('seconds',2)))
        pages=self.tk.StringVar(value=str(cfg.get('pages',1)))
        self.ttk.Label(fields,text='每個排行讀取後停留秒數 ≥2').pack(side='left');self.ttk.Entry(fields,textvariable=delay,width=6).pack(side='left',padx=5)
        self.ttk.Label(fields,text='每個排行讀取頁數 1～20').pack(side='left',padx=10);self.ttk.Entry(fields,textvariable=pages,width=6).pack(side='left')
        full_table=self.tk.BooleanVar(value=cfg.get('full_table',True))
        self.ttk.Checkbutton(body,text='自動逐段讀取清單（建議）：重疊捲動，連續三次未移動才結束；關閉則使用指定頁數',variable=full_table).pack(anchor='w',padx=15)
        adaptive=self.tk.BooleanVar(value=cfg.get('adaptive',True))
        self.ttk.Checkbutton(body,text='自適應加速：記住已驗證搜尋路徑，成功讀取後縮短停留（最低1秒）',variable=adaptive).pack(anchor='w',padx=15)
        def clear_experience():
            from MitakeDetector import BASE
            try:(BASE/'automation-experience.json').unlink(missing_ok=True)
            except OSError as exc:messagebox.showerror('清除失敗',str(exc));return
            self.say('操作經驗已清除，下次重新學習')
        self.ttk.Button(body,text='清除操作經驗',command=clear_experience).pack(anchor='w',padx=15)
        self.ttk.Label(body,text='優先依編號定位並核對標題；無法可靠定位時回到文字搜尋，單一排行失敗會跳過並通知手機。\n多頁使用滑鼠滾輪向下讀取並依股號合併；不是官方全市場資料。\n首次先按「測試輪切一次」，確認切換正確後再開始持續輪切。',wraplength=810).pack(anchor='w',padx=15,pady=7)
        def save(close=True):
            try:
                if combo.current()>=0:hwnd()
                from menu_numbers import validate_numbers
                cfg['menu_numbers']=validate_numbers({name:var.get().strip() for name,var in number_vars.items()})
                cfg['adaptive']=bool(adaptive.get());cfg['full_table']=bool(full_table.get())
                cfg['seconds']=float(delay.get());cfg['pages']=int(pages.get());cfg['read_interval_v2']=True
                if not 2<=cfg['seconds']<=3600 or not 1<=cfg['pages']<=20:raise ValueError('秒數需2～3600，頁數需1～20')
                cfg['rankings']=[n for n,v in rank_vars.items() if v.get()]
                if not cfg['rankings']:raise ValueError('請至少勾選一個排行')
                if not self.settings():return False
                self.persist_auto_preferences(cfg)
                self.say('自動輪切設定已儲存，共'+str(len(cfg['rankings']))+'個排行；讀取後停留 '+str(cfg['seconds'])+' 秒，重開後保留')
                if close:top.destroy()
                return True
            except Exception as exc:messagebox.showerror('設定錯誤',str(exc));return False
        def test():
            if save():self.start_auto(test_only=True)
        self.ttk.Button(buttons,text='儲存設定',command=save).pack(side='left')
        self.ttk.Button(buttons,text='測試輪切一次（不讀取行情）',command=test).pack(side='left',padx=10)
        def preview():
            if save():self.preview_menu_numbers()
        self.ttk.Button(buttons,text='完整預覽編號（自動捲動）',command=preview).pack(side='left',padx=10)

    def build_menu_number_settings(self,parent,cfg):
        from menu_numbers import validate_numbers
        self.ttk.Label(parent,text='編號從選單最上方第1項開始，往下連續計數，捲動後不重新從1算。\n欄位已填入預設編號，可直接修改；已儲存的自訂編號優先保留。\n找不到排行時，填入實際編號並儲存；選取後仍核對標題。',wraplength=860).pack(anchor='w',padx=15,pady=12)
        enabled=self.tk.BooleanVar(value=cfg.get('menu_numbering',True))
        def toggle():cfg['menu_numbering']=bool(enabled.get())
        self.ttk.Checkbutton(parent,text='啟用數字定位與選單旁編號（減少中文OCR）',variable=enabled,command=toggle).pack(anchor='w',padx=15)
        frame=self.ttk.Frame(parent);frame.pack(fill='both',expand=True,padx=15,pady=10)
        canvas=self.tk.Canvas(frame,highlightthickness=0);scroll=self.ttk.Scrollbar(frame,orient='vertical',command=canvas.yview)
        canvas.configure(yscrollcommand=scroll.set);scroll.pack(side='right',fill='y');canvas.pack(side='left',fill='both',expand=True)
        content=self.ttk.Frame(canvas);canvas.create_window((0,0),window=content,anchor='nw')
        content.bind('<Configure>',lambda event:canvas.configure(scrollregion=canvas.bbox('all')))
        variables={}
        for i,name in enumerate(RANKINGS):
            rows_per_group=(len(RANKINGS)+1)//2
            group=i//rows_per_group;row=i%rows_per_group
            self.ttk.Label(content,text=f'{name}（預設{i+1}）',width=26).grid(row=row,column=group*2,sticky='w',pady=5)
            value=self.tk.StringVar(value=str(cfg.get('menu_numbers',{}).get(name,i+1)));variables[name]=value
            self.ttk.Entry(content,textvariable=value,width=7).grid(row=row,column=group*2+1,padx=(0,22))
        return variables

    def resolve_auto_window(self,auto):
        """Reacquire a live window after restart; never trust a persisted handle."""
        title=auto.get('title')
        matches=[w for w in WindowsController.windows() if title and w[1]==title]
        current=getattr(self,'auto_hwnd',None)
        if current not in [w[0] for w in matches]:
            if len(matches)!=1:
                self.auto_hwnd=None
                raise ValueError('找不到唯一的三竹目標視窗。請先開啟三竹，再到「輪切排行與定位」選擇視窗並儲存。')
            current=matches[0][0]
        self.auto_hwnd=current
        return current

    def learn_rankings(self):
        from ranking_training import open_training
        open_training(self)

    def preview_menu_numbers(self):
        if not self.idle() or not self.dependencies():return
        from menu_numbers import NativeMenu,PixelMenu,preview_numbers
        from tkinter import messagebox
        cfg=self.config.get('auto_config',{})
        try:
            for key in ('combo','menu'):validate_roi(cfg.get(key))
            target=self.resolve_auto_window(cfg)
        except Exception as exc:messagebox.showerror('需要設定',str(exc));return
        self.generation+=1;token=self.generation;self.stop_event=threading.Event();stop=self.stop_event
        self.status.set('完整編號預覽（非行情掃描）｜自動向下捲動至底部；F8可提前結束')
        def job():
            try:
                c=WindowsController(target,stop);c.activate()
                threading.Thread(target=c.monitor_hotkey,daemon=True).start()
                _,combo=c.image(cfg['combo']);c.click((combo['left']+combo['width']//2,combo['top']+combo['height']//2));c.pause(.4)
                region=absolute_roi(cfg['menu'],c.rect());center=(region['left']+region['width']//2,region['top']+region['height']//2)
                c.scroll(center,120*60);c.pause(.3)
                native=None;geometry=None
                try:native=NativeMenu(c,region)
                except (ValueError,AttributeError):
                    im,_=c.image(cfg['menu']);geometry=PixelMenu(im,self.read_auto_ocr(im))
                def show_preview(data):
                    self.events.put((token,'menu_numbers',data))
                    self.events.put((token,'auto_status','完整編號預覽｜'+data['preview_label']+'｜自動向下捲動，F8結束'))
                count=preview_numbers(c,cfg['menu'],combo,region,native,geometry,show_preview)
                self.events.put((token,'message',f'編號預覽完成，共 {count} 項，已收起選單；要掃描請按「開始／繼續」或F8'))
            except Exception as exc:self.events.put((token,'message','編號預覽結束：'+str(exc)))
            finally:stop.set();self.events.put((token,'menu_numbers',None))
        self.worker_thread=threading.Thread(target=job,daemon=True);self.worker_thread.start()

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

    def review_candidate_cells(self,code,values):
        from MitakeDetector import number,Quote
        from candidate_ranking import assess
        import re
        if not re.fullmatch(r'[0-9]{4}',str(code)):return False
        cfg=self.page_review_config
        # Previously verified >=5 candidates retain priority even if a new cell
        # is missing. This does not promote or write provisional quote values.
        for row in list(getattr(self,'display_rows',[]) or []):
            if row[0].code==code and assess(row,cfg)['count']>=5:return True
        try:
            q=Quote(code,'',number(values['price']),number(values['gain']),number(values['volume']),number(values['turnover']),time.time())
        except (KeyError,TypeError,ValueError):return False
        rates=getattr(self,'volume_rates',None)
        ratio=rates.result(code,q.ts).value if rates else None
        return assess((q,ratio,'等待觀測突破守穩',False),cfg)['count']>=5

    def record_cell_failures(self,elapsed):
        metrics=getattr(self,'cell_metrics',None)
        if not metrics:return
        from collections import Counter
        counts=getattr(self,'ocr_failed_fields',Counter())
        counts.update(metrics.get('failed',{}));self.ocr_failed_fields=counts
        self.ocr_reviewed=getattr(self,'ocr_reviewed',0)+metrics.get('reviewed',0)
        self.ocr_unresolved=getattr(self,'ocr_unresolved',0)+metrics.get('unresolved',0)
        self.page_pending=bool(metrics.get('pending_codes') or metrics.get('unknown_codes'))
        self.last_scan_diagnostic+=f'｜失敗欄位累計{sum(counts.values())}次｜複查{self.ocr_reviewed}格｜未解決{self.ocr_unresolved}格｜本頁待補{len(metrics.get("pending_codes",[]))}檔｜重用{metrics.get("reused",0)}/{metrics.get("total",0)}格｜略過{metrics.get("skipped",0)}列｜目標10秒'
        if metrics.get('failed') or self.page_pending:
            from MitakeDetector import BASE
            record=dict(time=dt.datetime.now().isoformat(),elapsed=round(elapsed,2),failed=metrics.get('failed',{}),reviewed=metrics.get('reviewed',0),unresolved=metrics.get('unresolved',0),pending_codes=metrics.get('pending_codes',[]),cumulative=dict(counts))
            try:
                with (BASE/'ocr-field-failures.jsonl').open('a',encoding='utf-8') as f:f.write(json.dumps(record,ensure_ascii=False)+'\n')
            except OSError as exc:self.last_scan_diagnostic+='｜失敗紀錄寫入失敗：'+str(exc)

    def read_numeric_cells(self,images,on_batch=None):
        """Recognize already segmented one-line cells without text detection."""
        from rapidocr_onnxruntime import RapidOCR
        import numpy as np
        from PIL import ImageOps
        if self.ocr is None:self.ocr=RapidOCR(det_limit_type="max",det_limit_side_len=1600,intra_op_num_threads=2,inter_op_num_threads=1,use_cls=False)
        inputs=[]
        for image in images:
            raw=image.convert('RGB')
            background=raw.getpixel((min(2,raw.width-1),min(2,raw.height-1)))
            if sum(background)<384:raw=ImageOps.invert(raw)
            inputs.append(np.asarray(raw)[:,:,::-1].copy())
        results=[]
        # Bound each inference batch so F8 is checked between batches.
        for start in range(0,len(inputs),16):
            context=getattr(self,'scan_focus_context',None)
            if context and context[1].is_set():raise AutomationStopped('掃描已暫停')
            if on_batch:on_batch(start,min(start+16,len(inputs)))
            rows,_=self.ocr.text_rec(inputs[start:start+16])
            results.extend(rows)
            self.report_read_digits([row[0] for row in rows])
        return results

    def report_read_digits(self,texts):
        report=getattr(self,"digit_report",None)
        if report:
            from scan_rate import digit_count
            report(digit_count(texts))

    def read_auto_ocr(self,image):
        from rapidocr_onnxruntime import RapidOCR
        import numpy as np
        import hashlib
        from collections import OrderedDict
        source=getattr(self,'scan_capture_region',None);context=getattr(self,'scan_focus_context',None)
        if source and context and image.width==source['width'] and image.height<=source['height']:
            token,stop=context
            self.highlight_scan(token,None,stop)
        started=time.perf_counter()
        raw=image.convert('RGB')
        key=(raw.size,hashlib.sha256(raw.tobytes()).digest())
        persistent_key=f'{raw.width}x{raw.height}:'+key[1].hex()
        learning=self.get_ocr_learning()
        cache=getattr(self,'ocr_image_cache',None)
        if cache is None:self.ocr_image_cache=cache=OrderedDict()
        if key in cache and not getattr(self,'force_fresh_ocr',False):
            cache.move_to_end(key)
            self.ocr_cache_hits=getattr(self,'ocr_cache_hits',0)+1
            if learning:learning.record(persistent_key,cache[key],time.perf_counter()-started,'記憶體重用')
            return cache[key]
        if learning and not getattr(self,'force_fresh_ocr',False):
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
        self.report_read_digits([t[1] for t in tokens])
        if learning:learning.record(persistent_key,tokens,time.perf_counter()-started,'重新辨識')
        if tokens:
            cache[key]=tokens
            while len(cache)>24:cache.popitem(last=False)
        return tokens

    def cancel_scan_retry(self):
        timer=getattr(self,'scan_retry_timer',None)
        if timer:
            self.root.after_cancel(timer)
        self.scan_retry_timer=None

    def schedule_scan_retry(self,token):
        self.cancel_scan_retry()
        count=getattr(self,'scan_retry_count',0)
        self.scan_retry_count=count+1
        delay=(5,15,30,60)[min(count,3)]
        self.scan_phase.set(f'掃描失敗｜{delay}秒後自動恢復（第{count+1}次）｜F8可取消')
        self.say(self.scan_phase.get())
        def resume():
            self.scan_retry_timer=None
            if token!=self.generation:return
            worker=getattr(self,'worker_thread',None)
            if worker and worker.is_alive():
                self.scan_retry_timer=self.root.after(500,resume)
                return
            try:
                # Never activate another application's window during a retry.
                WindowsController(self.auto_hwnd,threading.Event()).check()
            except Exception as exc:
                self.say('等待三竹視窗可操作後自動接續：'+str(exc))
                self.events.put((token,'ranking_skipped',dict(ranking='自動恢復',cycle=0,reason='視窗暫時無法操作，稍後自動重試：'+str(exc))))
                self.schedule_scan_retry(token)
                return
            self.say('自動恢復：沿用已存欄位座標並接續進度')
            try:
                self.start_auto(announce=False,recovering=True)
            except Exception as exc:
                self.say('自動恢復未完成，稍後再試：'+str(exc))
                self.events.put((token,'ranking_skipped',dict(ranking='自動恢復',cycle=0,reason=str(exc))))
            if not getattr(self,'auto_active',False) and token==self.generation:
                self.schedule_scan_retry(token)
        self.scan_retry_timer=self.root.after(delay*1000,resume)

    def start_auto(self,test_only=False,announce=True,recovering=False):
        self.cancel_scan_retry()
        if not recovering:self.scan_retry_count=0
        self.scan_run_started=time.monotonic()
        self.numeric_priority=True
        from tkinter import messagebox
        if not self.idle() or not self.dependencies():return
        cfg=self.settings()
        if cfg is None:return
        auto=json.loads(json.dumps(cfg.get('auto_config') or {}))
        try:
            validate_roi(auto.get('combo'));validate_roi(auto.get('menu'))
            if not test_only:validate_roi(auto.get('table'))
            self.resolve_auto_window(auto)
            if not auto.get('rankings'):raise ValueError('請設定要輪切的排行')
            unknown=[n for n in auto['rankings'] if n not in RANKINGS]
            if unknown:raise ValueError('舊設定含未列於目前選單的排行：'+'、'.join(unknown)+'。請到自動輪切設定重新勾選並儲存；不會自動改選其他排行。')
            if not 2<=float(auto.get('seconds',0))<=3600 or not 1<=int(auto.get('pages',0))<=20:raise ValueError('輪切間隔或頁數無效')
        except Exception as exc:
            if recovering:self.say('自動恢復等待設定或視窗可用：'+str(exc))
            else:messagebox.showerror('需要設定',str(exc))
            return
        from resume_scan import ScanCheckpoint,scan_context
        from MitakeDetector import BASE
        context=scan_context(cfg);saved=None;store=None
        self.scan_resume_active=not test_only
        if not test_only:
            try:
                store=ScanCheckpoint(BASE/'scan-checkpoint.json');saved=store.resume(context)
                self.scan_checkpoint=store
            except (ValueError,OSError) as exc:
                if recovering:self.say('中斷紀錄暫時無法讀取，稍後再試：'+str(exc))
                else:messagebox.showerror('保留前次進度',str(exc))
                return
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
                c.activate()
                self.hide_scan_cursor(token)
                threading.Thread(target=c.monitor_hotkey,daemon=True).start()
                ranking=auto['rankings'][saved['rank'] if saved else 0]
                changed=self.adapt_scan_layout(c,auto,cfg,token,ranking)
                if changed:
                    from resume_scan import rebase_viewport_cursor
                    if saved and saved.get('table'):
                        saved['table']=rebase_viewport_cursor(saved['table'])
                        self.events.put((token,'auto_status','已保留前次股票與輪次｜新設備重新核對目前排行位置'))
                    context.clear();context.update(scan_context(cfg))
                    if store and saved:store.update(context,saved)
                from adaptive import Experience
                from MitakeDetector import BASE
                experience=Experience(BASE/'automation-experience.json',
                    dict({k:auto.get(k) for k in ('title','window_size','combo','menu','table')},menu_catalog=RANKINGS),auto.get('adaptive',True))
                switcher=RankingSwitcher(c,self.read_auto_ocr,auto['combo'],auto['menu'],experience=experience,ocr_retry=self.read_menu_ocr_retry,status=lambda detail:self.events.put((token,'auto_progress',(detail,None,None))),
                    numbering=auto.get('menu_numbering',True),menu_numbers=auto.get('menu_numbers',{}),show_numbers=lambda data:self.events.put((token,'menu_numbers',data)))
                switcher.menu_is_open=getattr(self,'_scan_menu_open',False)
                priority_last={}
                failed_reads=[0];saved_failure=[False]
                cycle=saved['cycle'] if saved else 1
                from round_timer import RoundTimer
                round_timer=RoundTimer((saved or {}).get('round_elapsed',0))
                cycle_started=time.time()
                def round_clock():
                    from task_progress import duration_text
                    started=dt.datetime.fromtimestamp(cycle_started).strftime('%H:%M:%S')
                    return f'起始{started}｜本輪耗時{duration_text(round_timer.elapsed())}'
                known_totals={}
                start_index=saved['rank'] if saved else 0
                while not stop.is_set():
                    skipped=0;switch_failures=[];round_errors=[]
                    attempted=len(auto['rankings'])-start_index
                    for rank_index in range(start_index,len(auto['rankings'])):
                        name=auto['rankings'][rank_index]
                        diagnostic.update(cycle=cycle,ranking=name,segment=0,count=0,stage='切換排行')
                        self.events.put((token,'auto_phase',f'掃描中｜第{cycle}輪｜{round_clock()}｜{name}'))
                        table_resume=saved.get('table') if saved and rank_index==saved['rank'] else None
                        if store:store.update(context,dict(cycle=cycle,rank=rank_index,table=table_resume,round_started=cycle_started,round_elapsed=round_timer.elapsed()))
                        c.check();self.events.put((token,'auto_status',f'第{cycle}輪｜排行{rank_index+1}/{len(auto["rankings"])}：正在切換{name}'))
                        try:
                            startup_error=getattr(self,'_startup_rank_error',None)
                            self._startup_rank_error=None
                            if startup_error and startup_error[0]==name:raise startup_error[1]
                            switcher.switch(name)
                            diagnostic.update(stage='讀取排行資料')
                            self.events.put((token,'auto_status','已切換 '+name+'｜沿用欄位座標，直接掃描數據'))
                            if test_only:continue
                            collected={};rejected=0;begin=time.monotonic();failed_reads[0]=0;saved_failure[0]=False
                            counted=set(q['code'] for q in (table_resume or {}).get('quotes',[]))
                            def counts(done=False):
                                if done and auto.get('full_table',True):known_totals[name]=len(counted)
                                total=known_totals.get(name)
                                total_text=str(total) if total is not None and total>=len(counted) else '待完整掃描確認'
                                text=f'{name}：已掃描 {len(counted)} 股／總股數 {total_text}'+('（上輪參考）' if total is not None and not done else '')
                                text+=f'｜目前第 {cycle} 輪｜累計完成 {cycle-1} 遍｜{round_clock()}'
                                self.events.put((token,'auto_counts',text))
                            counts()
                            table=absolute_roi(auto['table'],c.rect());center=(table['left']+table['width']//2,table['top']+table['height']//2)
                            from table_scan import scan_table
                            def progress(segment,count,stage):
                                diagnostic.update(segment=segment,count=count,stage=stage,seconds_per_segment=getattr(self,"last_table_seconds",0))
                                reused=getattr(getattr(self,'stock_names',None),'reused',0)
                                seconds=getattr(self,'last_table_seconds',0)
                                detail=f'第{cycle}輪｜{round_clock()}｜{getattr(self,"last_scan_diagnostic","準備讀取")}｜類目{rank_index+1}/{len(auto["rankings"])} {name}｜第{segment}段｜已讀{count}檔｜股名重用{reused}次｜{stage}｜{seconds:.1f}秒/段'
                                self.events.put((token,'auto_progress',(detail,rank_index,len(auto['rankings']))))
                            def read(image):
                                started=time.monotonic();self.events.put((token,'auto_rate',dict(start=started)))
                                self.page_deadline=started+10;self.page_review_config=cfg;self.cell_metrics=None;self.page_pending=False
                                from ocr_recovery import fresh_ocr
                                from contextlib import nullcontext
                                self.digit_report=lambda count:self.events.put((token,'auto_rate',dict(digits=count,at=time.monotonic())))
                                try:
                                    with fresh_ocr(self) if failed_reads[0] else nullcontext():
                                        result=self.scan_recovering_table(image,cfg['column_bounds']) if auto.get('discovered') else self.scan(image,cfg['centers'])
                                except AutomationStopped:raise
                                except (ValueError,RuntimeError) as error:
                                    diagnostic.update(ocr_error_type=type(error).__name__,ocr_error=str(error))
                                    self.last_scan_diagnostic=f'{type(error).__name__}: {error}'
                                    result=([],1)
                                finally:self.digit_report=None
                                self.record_cell_failures(time.monotonic()-started)
                                self.events.put((token,'auto_rate',dict(elapsed=time.monotonic()-started,count=len(result[0]))))
                                anchors=(getattr(self,'fast_code_cache',None) or (None,None,[]))[2]
                                excluded_page=not result[0] and auto.get('discovered') and excluded_only(anchors)
                                if result[0] or excluded_page or self.page_pending:failed_reads[0]=0
                                else:
                                    failed_reads[0]+=1
                                    detail=getattr(self,'last_scan_diagnostic','未取得完整數字列')
                                    diagnostic.update(ocr_detail=detail,image_size=getattr(image,'size',None),column_bounds=cfg.get('column_bounds'),table_roi=list(auto['table']),read_failures=failed_reads[0])
                                    self.events.put((token,'auto_status',f'第{diagnostic.get("segment",1)}段尚未讀到股票｜第{failed_reads[0]}次｜{detail}｜正在重新擷取資料區與複查'))
                                    if not saved_failure[0] and hasattr(image,'save'):
                                        from scan_diagnostics import save_failed_capture
                                        saved_failure[0]=True
                                        diagnostic['failed_capture']=save_failed_capture(BASE/'scan-failures',image,diagnostic)
                                return result
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
                                    self.events.put((token,'ranking_skipped',dict(ranking=name+'／候選複查',cycle=cycle,reason='複查失敗，主掃描繼續：'+str(error))))
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
                                if getattr(self,'page_review_config',None):return []
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
                                if store:store.update(context,dict(cycle=cycle,rank=rank_index,table=state,round_started=cycle_started,round_elapsed=round_timer.elapsed()))
                            from table_scan import scan_with_recovery
                            def attempt_scan(resume):
                                return scan_table(c,auto['table'],read,progress,observed=observed,resume=resume,checkpoint=checkpoint,
                                                       limit_pages=None if auto.get('full_table',True) else auto['pages'],scroll_cap=experience.scroll_cap(name),
                                                       learn_scroll=lambda cap,recovered:experience.record_scroll(name,cap,recovered),
                                                       read_anchors=(lambda image:self.read_stock_anchors(image,cfg['column_bounds'])) if auto.get('discovered') else None,
                                                       recover_layout=None,
                                                       deferred_view=lambda:bool(getattr(self,'page_pending',False)))
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
                                store.update(context,dict(cycle=cycle+(1 if next_rank==0 else 0),rank=next_rank,table=None,round_started=None if next_rank==0 else cycle_started,round_elapsed=0 if next_rank==0 else round_timer.elapsed()))
                            experience.observed(name,bool(collected) and rejected==0)
                            dwell=experience.wait(name,auto['seconds'])
                            mode='自適應加速' if experience.enabled else '固定間隔'
                            diagnostic.update(stage='排行讀取完成，等待下次切換',count=len(collected))
                            wait_until=time.monotonic()+dwell
                            if getattr(self,'deferred_names',None) and not getattr(self,'numeric_priority',False):
                                self.events.put((token,'auto_status','數字篩選已完成：利用輪切等待時間批次補讀股名（最多32檔，已記錄股號不重讀）'))
                                try:self.learn_deferred_names(c.check)
                                except AutomationStopped:raise
                                except Exception as error:
                                    self.events.put((token,'ranking_skipped',dict(ranking=name+'／股名補讀',cycle=cycle,reason='補讀失敗，數字掃描繼續：'+str(error))))
                                    diagnostic.update(stage='股名補讀失敗，主掃描繼續',optional_error=str(error))
                                    from scan_diagnostics import record_interruption
                                    from MitakeDetector import VERSION
                                    location=record_interruption(BASE/'scan-interruptions.jsonl',VERSION,diagnostic,error)
                                    self.events.put((token,'auto_status','股名補讀失敗，已略過並繼續數字掃描：'+str(error)+'｜'+location))
                            while time.monotonic()<wait_until:
                                remaining=max(1,int(wait_until-time.monotonic()+.999))
                                self.events.put((token,'auto_progress',(f'{name}｜已讀{len(collected)}檔｜略過{rejected}列｜{mode}｜{remaining}秒後繼續｜OCR重用{getattr(self,"ocr_cache_hits",0)}次',rank_index+1,len(auto['rankings']))))
                                c.pause(min(1,max(0,wait_until-time.monotonic())))
                        except Exception as exc:
                            # User stops, lost focus and obstructed windows must still halt.
                            if stop.is_set() or (isinstance(exc,AutomationStopped) and not str(exc).startswith(('選單中找不到','切換後標題'))):raise
                            c.check()
                            from scan_diagnostics import record_interruption
                            from MitakeDetector import VERSION
                            if diagnostic.get('stage')=='切換排行':switch_failures.append(name)
                            round_errors.append(f'{name}：{str(exc)[:120]}')
                            diagnostic.update(stage='排行失敗，已跳過')
                            location=record_interruption(BASE/'scan-interruptions.jsonl',VERSION,diagnostic,exc)
                            saved=None;skipped+=1
                            next_rank=(rank_index+1)%len(auto['rankings'])
                            if store:store.update(context,dict(cycle=cycle+(1 if next_rank==0 else 0),rank=next_rank,table=None,round_started=None if next_rank==0 else cycle_started,round_elapsed=0 if next_rank==0 else round_timer.elapsed()))
                            self.events.put((token,'ranking_skipped',dict(ranking=name,cycle=cycle,reason=str(exc)[:300])))
                            self.events.put((token,'auto_status',f'第{cycle}輪｜{name} 掃描失敗，已跳過，繼續下一排行：{exc}｜{location}'))
                            c.pause(.5)
                    if test_only:break
                    if skipped==attempted and attempted:
                        details='；'.join(round_errors[:3])
                        self.events.put((token,'auto_status','本輪全部排行失敗｜'+details))
                        if len(switch_failures)==attempted:
                            self.events.put((token,'auto_status','整輪切換失敗，正在重新定位排行選單；保留表頭座標'))
                            try:
                                geometry=switcher.recalibrate_menu()
                                auto.update(geometry);cfg['auto_config']=auto
                                context.clear();context.update(scan_context(cfg))
                                if store:store.update(context,dict(cycle=cycle+1,rank=0,table=None,round_started=None,round_elapsed=0))
                                self.events.put((token,'auto_layout_done',dict(auto_config=auto.copy(),column_bounds=cfg['column_bounds'],region=cfg['region'])))
                                self.events.put((token,'auto_status','選單重新定位完成，2秒後重新開始排行掃描；F8可停止'))
                                c.pause(2)
                            except AutomationStopped:raise
                            except Exception as recovery_error:
                                self.events.put((token,'ranking_skipped',dict(ranking='排行選單自動校正',cycle=cycle,reason=str(recovery_error)[:300])))
                                self.events.put((token,'auto_status','選單自動校正未完成：'+str(recovery_error)+'｜30秒後再試；F8可停止'))
                                c.pause(30)
                        else:
                            self.events.put((token,'auto_status','排行可切換，但資料讀取全部失敗｜'+details+'｜30秒後再試'))
                            c.pause(30)
                    self.events.put((token,'auto_counts',f'本輪已巡查｜跳過 {skipped} 個排行｜累計完成 {cycle} 遍｜完成一遍耗時 {round_timer.elapsed():.1f} 秒｜準備第 {cycle+1} 輪'))
                    self.events.put((token,'auto_phase',f'第{cycle}輪已完成｜跳過 {skipped} 個排行｜自動開始第{cycle+1}輪'))
                    cycle+=1;start_index=0;cycle_started=time.time();round_timer=RoundTimer()
                self.events.put((token,'auto_end','輪切測試完成' if test_only else '自動輪切已停止'))
            except Exception as exc:
                from scan_diagnostics import record_interruption
                from MitakeDetector import BASE,VERSION
                location=record_interruption(BASE/'scan-interruptions.jsonl',VERSION,diagnostic,exc)
                recoverable=not test_only and not stop.is_set() and not str(exc).startswith('F8')
                if recoverable:self.events.put((token,'ranking_skipped',dict(ranking=diagnostic.get('ranking','掃描流程'),cycle=diagnostic.get('cycle',0),reason='本次掃描中斷，將自動恢復：'+str(exc))))
                self.events.put((token,'auto_failed' if recoverable else 'auto_end','掃描已暫停，進度保留：'+str(exc)+'｜'+location))
            finally:
                self.events.put((token,'menu_numbers',None));self.restore_scan_cursor();stop.set()
        def launch():
            if token!=self.generation:return
            self.worker_thread=threading.Thread(target=job,daemon=True);self.worker_thread.start()
        self.root.after(700,launch)




