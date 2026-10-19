"""Exercise actual bundled Tcl/Tk, OCR models, sample rows and persistent storage."""
import datetime as dt
import json
import sys
import traceback
from pathlib import Path


def run_smoke_test(report):
    report=Path(report);report.parent.mkdir(parents=True,exist_ok=True)
    info={}
    root=None
    try:
        import tkinter as tk
        from PIL import Image,ImageDraw,ImageFont,ImageGrab
        from MitakeDetector import App,Quote,DEFAULT,VERSION,BASE
        from updater import validate_manifest
        root=tk.Tk();root.withdraw();app=App(root)
        assert root.title()=='漲停板探測器-雷允澤製 v'+VERSION
        import tkinter.font as tkfont
        from tkinter import ttk
        scale=app.display_scale.percent
        app.display_scale.apply(200,save=True,resize=False)
        assert tkfont.Font(root=root,font=app.log.cget('font')).actual('size')==20
        assert int(ttk.Style(root).lookup('Treeview','rowheight'))==60
        assert app.tree.column('code','width')==app.display_scale.widths['code']*2
        from display_scale import DisplayScale
        assert DisplayScale(app,BASE/'display-settings.json').initial==200
        app.display_scale.apply(100,save=False,resize=False)
        assert tkfont.Font(root=root,font=app.log.cget('font')).actual('size')==10
        app.display_scale.apply(scale,save=True,resize=False)
        assert app.log.cget('wrap')=='word'
        root.update_idletasks()
        assert app.status_label.cget('justify')=='left'
        app.status.set('掃描已暫停\n原因：焦點離開三竹\n第22段，已讀39檔\n進度保留，按F8接續')
        # Verify real Tk widgets with simulated bytes; never install in this test.
        root.deiconify()
        app.set_update_progress(dict(stage='checking',message='CI check'))
        root.update()
        assert app.update_panel.window.winfo_ismapped()
        assert app.log.winfo_height()>=100
        assert app.log.winfo_rooty()+app.log.winfo_height()<=root.winfo_rooty()+root.winfo_height()
        app.set_update_progress(dict(stage='downloading',version='99.0.0',percent=42,downloaded=42*1048576,total=100*1048576,elapsed=4,message='CI download'))
        root.update()
        assert float(app.update_panel.bar['value'])==42
        assert float(app.update_bar['value'])==42
        assert '42%' in app.update_panel.amount.get() and '42.0 / 100.0' in app.update_panel.amount.get()
        app.update_panel.update(dict(stage='downloading',version=VERSION,percent=40,downloaded=40*1048576,total=100*1048576,elapsed=0))
        app.update_panel.update(dict(stage='downloading',version=VERSION,percent=42,downloaded=42*1048576,total=100*1048576,elapsed=2))
        assert '1.0 MiB/s' in app.update_panel.amount.get(),'Resume bytes must not inflate speed'
        assert '預估剩餘' in app.update_panel.amount.get()
        app.update_panel.window.withdraw();app.update_panel.show();root.update()
        assert app.update_panel.window.winfo_ismapped()
        from PIL import ImageGrab
        win=app.update_panel.window
        ImageGrab.grab(bbox=(win.winfo_rootx(),win.winfo_rooty(),win.winfo_rootx()+win.winfo_width(),win.winfo_rooty()+win.winfo_height())).save(report.with_name('update-progress.png'))
        app.set_update_progress(dict(stage='verifying',message='CI verify'))
        assert str(app.update_panel.bar['mode'])=='indeterminate'
        app.set_update_progress(dict(stage='error',message='CI failure'))
        assert '未完成' in app.update_panel.stage.get()
        app.update_panel.close();app.update_panel=None
        app.update_bar.stop();app.update_bar.pack_forget();app.update_status.set('')
        root.withdraw()
        font=ImageFont.truetype('C:/Windows/Fonts/arial.ttf',24)
        im=Image.new('RGB',(1200,260),'#101820');draw=ImageDraw.Draw(im)
        for row,values in enumerate([['2330','TESTA','100.50','2.30%','1500','1.20%'],['2317','TESTB','205.00','3.10%','2800','2.40%']]):
            for x,text in zip(DEFAULT['centers'],values):
                draw.text((x*12,60+row*65),text,font=font,fill='white',anchor='mm')
        quotes,rejected=app.scan(im,DEFAULT['centers'])
        assert len(quotes)==2 and rejected==0, f'OCR result: {quotes}, rejected {rejected}'
        assert app.ocr.use_cls is False
        assert quotes[0].code=='2330' and quotes[0].volume==1500 and quotes[1].price==205
        from fast_quotes import StockNames,scan_numeric_batch
        from MitakeDetector import FIELDS
        edges=[0]+[(a+b)/200 for a,b in zip(DEFAULT['centers'],DEFAULT['centers'][1:])]+[1]
        bounds={field:[edges[i],edges[i+1]] for i,field in enumerate(FIELDS)}
        names=StockNames();names.names={'2330':'TESTA','2317':'TESTB'}
        import time
        started=time.perf_counter()
        fast,bad=scan_numeric_batch(im,bounds,app.read_auto_ocr,names,memory=app.get_ocr_learning())
        fast_seconds=time.perf_counter()-started
        assert len(fast)==2 and bad==0,repr(fast)
        assert fast[0].price==100.5 and fast[1].volume==2800
        assert fast[0].name=='TESTA' and names.reused==2
        info.update(numeric_batch_rows=len(fast),numeric_batch_seconds=fast_seconds,remembered_names_reused=names.reused)
        # Actual bundled OCR: unknown names must not block numeric results.
        app.stock_names=StockNames();app.numeric_priority=True
        started=time.perf_counter()
        quick,quick_bad=app.scan_recovering_table(im,bounds)
        assert len(quick)==2 and quick_bad==0 and all(not q.name for q in quick)
        assert app.deferred_names,'Unknown names must be queued for later learning'
        reader=app.read_auto_ocr
        def no_extra_ocr(image):raise AssertionError('Same-view stock anchors were OCRed twice')
        app.read_auto_ocr=no_extra_ocr
        try:assert set(app.read_stock_anchors(im,bounds))=={'2330','2317'}
        finally:app.read_auto_ocr=reader
        info.update(priority_numeric_seconds=time.perf_counter()-started,priority_name_queue=len(app.deferred_names))
        app.learn_deferred_names()
        assert app.stock_names.names,'Deferred names were never learned'
        app.numeric_priority=False
        now=dt.datetime.now().timestamp()
        app.mode='CI smoke / synthetic data'
        app.render([Quote('9001','SMOKE',100,2,1500,1,now)],0,0,dict(DEFAULT),persist=True)
        assert (BASE/'observations.sqlite3').is_file()
        # Verify the actual GUI has only the everyday action buttons.
        actions=[child.cget('text') for child in app.main_actions.winfo_children() if child.winfo_class()=='TButton']
        assert actions==['開始／繼續（F8）','暫停（F8）','重新開始','掃描設定','一鍵更新'],actions
        from tkinter import ttk
        style=ttk.Style()
        assert style.lookup('TEntry','foreground')=='#101827'
        assert style.lookup('TEntry','fieldbackground')=='#f4f7fc'
        assert style.lookup('TEntry','foreground',('readonly',))=='#101827'
        history=app.show_version_history();root.update();assert history.winfo_exists();history.destroy()
        learning_window=app.show_ocr_learning();root.update();assert learning_window.winfo_exists();learning_window.destroy()
        app.stock_names=StockNames();app.stock_names.names={'9001':'LEARNED'}
        app.render([Quote('9001','',100,2,1500,1,now+.1)],0,0,dict(DEFAULT),persist=False)
        assert app.tree.item(app.tree.get_children()[0],'values')[1]=='LEARNED'
        app.stock_names.names={}
        # Changing rankings must retain other observed stocks and deduplicate codes.
        cfg=dict(DEFAULT,source_rank='漲幅排行',use_breakout=False)
        app.render([Quote('9001','SMOKE',100,2,1500,1,now+1)],0,0,cfg,persist=False)
        app.render([Quote('9001','SMOKE',100,2,1501,1,now+2)],0,0,cfg,persist=False)
        app.render([Quote('9002','SMOKE2',101,3,1600,1,now+3)],0,0,dict(cfg,source_rank='預估量增'),persist=False)
        assert len(app.tree.get_children())==2
        assert '轉強候選 1 檔' in app.observation_summary.get(),app.observation_summary.get()
        app.render([Quote('9001','SMOKE',100,2,1502,1,now+4)],0,0,dict(cfg,source_rank='預估量增'),persist=False)
        assert len(app.tree.get_children())==1  # This source's previous row has left the ranking.
        assert app.tree.item(app.tree.get_children()[0],'values')[0]=='9001'
        # Incremental OCR batches are visible immediately and retain earlier rows.
        partial=dict(cfg,source_rank='分時買力',source_partial=True,source_reset=True)
        app.render([Quote('9010','FIRST',100,2,1500,1,now+5)],0,0,partial,persist=False)
        partial['source_reset']=False
        app.render([Quote('9011','SECOND',101,3,1600,1,now+6)],0,0,partial,persist=False)
        app.render([Quote('9011','SECOND',101,3,1600,1,now+6)],0,0,partial,persist=False)
        codes=[app.tree.item(item,'values')[0] for item in app.tree.get_children()]
        assert set(codes)=={'9001','9010','9011'},codes
        assert '已辨識股票 3 檔' in app.observation_summary.get()
        from resume_state import snapshot
        saved=snapshot(app);app.restore_scan_snapshot(saved)
        assert len(app.tree.get_children())==3
        assert '已還原 3 檔' in app.observation_summary.get()
        # Verify ranking, highlight and stock-specific speech using real widgets.
        app.rank_rows={};app.auto_engines={}
        spoken=[];original_announce=app.announce_task;app.announce_task=spoken.append
        candidate_cfg=dict(DEFAULT,source_rank='候選排序測試',use_breakout=False)
        try:
            for offset,volume in [(20,1500),(21,1501)]:
                app.render([Quote('9021','NEAR',100,3,950,1,now+offset),
                            Quote('9020','FULL',100,2,volume,1,now+offset)],0,0,candidate_cfg,persist=False)
            items=app.tree.get_children()
            assert app.tree.item(items[0],'values')[0]=='9020','Fully matched stock must rank first'
            assert '完全符合' in app.tree.item(items[0],'values')[2]
            assert 'hit' in app.tree.item(items[0],'tags'),'Fully matched stock must be highlighted'
            assert '接近候選' in app.tree.item(items[1],'values')[2]
            assert len(spoken)==1 and '9020 FULL' in spoken[0],'Voice must name the matching stock'
        finally:
            app.announce_task=original_announce
            notice=getattr(app,'candidate_notice',None)
            if notice:notice.close()
            app.restore_scan_snapshot(saved)
        from automation_smoke import run_automation_smoke
        automation=run_automation_smoke(root,app)
        app.demo();root.geometry('1180x900');root.deiconify()
        def finish():
            try:
                root.update_idletasks()
                assert len(app.tree.get_children())==2
                screenshot=report.with_suffix('.png')
                ImageGrab.grab(bbox=(root.winfo_rootx(),root.winfo_rooty(),root.winfo_rootx()+root.winfo_width(),root.winfo_rooty()+root.winfo_height())).save(screenshot)
                info.update(automation=automation,ok=True,version=VERSION,frozen=bool(getattr(sys,'frozen',False)),ocr_rows=len(quotes),gui_rows=len(app.tree.get_children()),database=True,screenshot=str(screenshot))
            except Exception:
                info.update(ok=False,error=traceback.format_exc())
            finally:
                app.stop();root.destroy()
        root.after(1800,finish);root.mainloop()
    except Exception:
        info.update(ok=False,error=traceback.format_exc())
        if root:
            try:root.destroy()
            except Exception:pass
    report.write_text(json.dumps(info,ensure_ascii=False,indent=2),'utf-8')
    if not info.get('ok'):
        if sys.stdout is not None:print(json.dumps(info,ensure_ascii=True))
        raise SystemExit(1)
