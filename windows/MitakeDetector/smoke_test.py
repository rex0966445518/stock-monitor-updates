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
        from display_scale import enable_dpi_awareness
        enable_dpi_awareness()
        import tkinter as tk
        from PIL import Image,ImageDraw,ImageFont,ImageGrab
        from MitakeDetector import App,Quote,DEFAULT,VERSION,BASE
        from updater import validate_manifest
        root=tk.Tk();root.withdraw();app=App(root)
        if (BASE/'daily-table.json').exists():
            from resume_state import load_daily
            if load_daily(BASE/'daily-table.json'):
                assert app.tree.get_children(),'Startup failed to restore today table'
        app.rank_rows={};app.auto_engines={};app.display_rows=[]

        assert 'ratio' in app.tree['displaycolumns'],'Five-minute volume rate must appear in the default overview'
        assert app.tree.heading('ratio','text')=='5分量速比'
        overview=tuple(app.tree['displaycolumns'])
        app.expand_columns_button.invoke()
        assert app.table_view.get()=='完整欄位'
        assert all(c in app.tree['displaycolumns'] for c in ['ratio','turnover','state']+[f'history{i}' for i in range(8)])
        app.tree.configure(displaycolumns=overview);app.table_view.set('行情總覽')
        assert root.title()=='漲停板探測器-雷允澤製 v'+VERSION
        assert app.product_title.cget('text')=='漲停板探測器'
        import tkinter.font as tkfont
        from tkinter import ttk
        scale=app.display_scale.percent
        app.display_scale.apply(200,save=True,resize=False)
        assert tkfont.Font(root=root,font=app.log.cget('font')).actual('size')==20
        assert int(ttk.Style(root).lookup('Treeview','rowheight'))==60
        assert app.tree.column('code','width')>=app.display_scale.widths['code']*2
        from display_scale import DisplayScale
        assert DisplayScale(app,BASE/'display-settings.json').initial==200
        app.display_scale.apply(100,save=False,resize=False)
        assert tkfont.Font(root=root,font=app.log.cget('font')).actual('size')==10
        app.display_scale.apply(scale,save=True,resize=False)
        assert app.log.cget('wrap')=='word'
        app.stock_names.learn('9998','記憶測試',.99);app.stock_names.save()
        from fast_quotes import StockNames
        assert StockNames(BASE/'stock-names.json').names['9998']=='記憶測試'
        assert (BASE/'stock-names.sqlite3').is_file()
        info['stock_name_database_verified']=True
        assert app.config['steady_enabled']
        steady=app.show_steady_settings();root.update_idletasks()
        assert steady.winfo_exists();steady.destroy()
        info['steady_settings_gui']=True
        assert app.config['guard_enabled']
        panel=app.show_guard_settings();root.update_idletasks()
        assert panel.winfo_exists();panel.destroy()
        review=app.show_signal_reviews();root.update_idletasks()
        assert review.winfo_exists();review.destroy()
        app.research_reports={'2330':app.research_service.store.report('2330')}
        research=app.show_research();root.update_idletasks()
        items=app.research_tree.get_children()
        assert items and app.research_tree.item(items[0],'values')[0]=='2330'
        assert '資料不足' in app.research_tree.item(items[0],'values')[2]
        app.show_research_details('2330')
        assert '集保週資料0/3期' in app.research_details.get('1.0','end')
        research.destroy()
        validation=app.show_validation_audit();root.update_idletasks()
        assert validation.winfo_exists();validation.destroy()


        root.update_idletasks()
        # Real Windows glyph metrics must fit full Chinese content at every common scale.
        import tkinter.font as tkfont
        original_scale=app.display_scale.percent
        for scale in (100,150,200):
            app.display_scale.apply(scale,save=False,resize=False)
            sample=app.tree.insert('','end',values=['2330','台灣積體電路製造','保留候選｜待盤尾檢核','1234.50'])
            app.display_scale.fit_tables(reset=True)
            measured=tkfont.Font(root=root,font=app.ttk.Style(root).lookup('Treeview.Heading','font'))
            for column,text in [('name','台灣積體電路製造'),('match','保留候選｜待盤尾檢核')]:
                assert app.tree.column(column,'width')>=measured.measure(text)+20
                assert not app.tree.column(column,'stretch')
            for column in app.today_tree['columns']:
                assert app.today_tree.column(column,'width')>=measured.measure(app.today_tree.heading(column,'text'))+20
            app.tree.delete(sample)
        app.display_scale.apply(original_scale,save=False,resize=False)
        assert not hasattr(app,'status_label')
        assert app.log.cget('wrap')=='word'
        app.status.set('掃描已暫停\n原因：焦點離開三竹\n第22段，已讀39檔\n進度保留，按F8接續')
        assert '第22段，已讀39檔' in app.log.get('1.0','end')
        app.scan_rate_var.set('17.9 秒 / 段 | 0.33 檔 / 秒')
        assert str(app.rate_label.cget('textvariable'))==str(app.scan_rate_var)
        assert app.rate_label.cget('text')=='17.9 秒 / 段 | 0.33 檔 / 秒'
        # Verify real Tk widgets with simulated bytes; never install in this test.
        root.deiconify()
        app.set_update_progress(dict(stage='checking',message='CI check'))
        root.update()
        assert app.update_panel.window.winfo_ismapped()
        assert app.log.winfo_height()>=100
        assert app.phase_label.master==app.header_status
        assert app.summary_label.master==app.header_status
        assert app.header_status.winfo_rooty()>app.main_actions.winfo_rooty()
        assert app.focus_chart.frame.winfo_rootx()>app.tree.winfo_rootx()
        assert app.focus_chart.canvas.winfo_width()>150
        assert app.tree.winfo_rooty()>app.main_actions.winfo_rooty()
        settings_preview=app.tk.Toplevel(root)
        app.build_filter_settings(settings_preview)
        root.update_idletasks()
        entries=[w for frame in settings_preview.winfo_children() for w in frame.winfo_children() if w.winfo_class()=='TEntry']
        assert len(entries)==8
        assert entries[0].get()==app.vars['min_price'].get()
        settings_preview.destroy()
        assert app.market_label.winfo_rootx()>app.product_title.winfo_rootx()
        assert app.log.winfo_rootx()>app.today_tree.winfo_rootx()
        assert app.market_label.winfo_rootx()+app.market_label.winfo_width()<=app.ui_root.winfo_rootx()+app.ui_root.winfo_width()
        app.update_market_index(dict(quote=dict(price=10000,change=-10,percent=-.1,ts=dt.datetime.now().timestamp()),error=''))
        assert '10,000.00' in app.market_var.get()
        assert app.market_label.cget('fg')=='#59d99d'
        app.update_market_index(dict(error='fixture offline'))
        assert '保留前值' in app.market_var.get()

        assert app.log.winfo_rooty()+app.log.winfo_height()<=app.ui_root.winfo_rooty()+app.ui_root.winfo_height()
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
        from task_progress import TaskProgress
        panel=TaskProgress(root,app.tk,app.ttk,(0,0,1200,800),'掃描中')
        root.update()
        assert panel.toggle_window.winfo_ismapped() and panel.window.winfo_ismapped()
        panel.toggle_label.event_generate('<Button-1>');root.update()
        assert panel.collapsed and not panel.window.winfo_ismapped()
        assert panel.toggle_window.winfo_width()<=24 and panel.toggle_window.winfo_height()<=24
        panel.update('第2輪｜擷取股票資料');panel.counts.set('已掃描 42 股／總股數 100｜累計完成 1 遍')
        assert panel.values['scanned'].get()=='42 股'
        panel.toggle_label.event_generate('<Button-1>');root.update()
        assert not panel.collapsed and panel.window.winfo_ismapped()
        assert '第2輪' in panel.detail.get()
        import ctypes
        u=ctypes.windll.user32
        get_style=u.GetWindowLongPtrW if ctypes.sizeof(ctypes.c_void_p)==8 else u.GetWindowLongW
        assert get_style(panel.toggle_hwnd,-20)&0x08000000
        assert not get_style(panel.toggle_hwnd,-20)&0x20
        assert get_style(panel.native_hwnd,-20)&0x20
        panel.toggle_collapsed();panel.close()
        panel=TaskProgress(root,app.tk,app.ttk,(0,0,1200,800),'繼續掃描');root.update()
        assert panel.collapsed and not panel.window.winfo_ismapped()
        panel.toggle_collapsed();panel.close()
        info['collapsible_status_verified']=True
        app.set_update_progress(dict(stage='verifying',message='CI verify'))
        assert str(app.update_panel.bar['mode'])=='indeterminate'
        app.set_update_progress(dict(stage='error',message='CI failure'))
        assert '未完成' in app.update_panel.stage.get()
        app.update_panel.close();app.update_panel=None
        app.update_bar.stop();app.update_bar.pack_forget();app.update_status.set('')
        root.withdraw()
        font=ImageFont.truetype('C:/Windows/Fonts/arial.ttf',24)
        # Exercise the exact recognition-only API inside both source and frozen builds.
        direct_samples=[]
        for value in ('2330','100.50','+3.50%','1500','2.50%'):
            cell=Image.new('RGB',(160,40),'#172534')
            ImageDraw.Draw(cell).text((6,4),value,font=font,fill='white')
            direct_samples.append(cell)
        direct=app.read_numeric_cells(direct_samples)
        assert [r[0] for r in direct]==['2330','100.50','+3.50%','1500','2.50%'],direct
        assert all(r[1]>=.93 for r in direct),direct
        info['direct_numeric_recognition']=True
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
        from ocr_recovery import tiled_numeric,fresh_ocr
        with fresh_ocr(app):
            recovered,rb,anchors,scores=tiled_numeric(im,bounds,app.read_auto_ocr,names,height=128)
        assert {q.code for q in recovered}=={'2330','2317'},repr(recovered)
        assert next(q for q in recovered if q.code=='2317').price==205
        info['fresh_tiled_recovery_rows']=len(recovered)
        from types import SimpleNamespace
        from candidate_charts import daily_series
        charts=app.candidate_charts
        charts.cache['2330']={'rows':daily_series([dict(day=f'2026-01-{i:02d}',open=100,high=102,low=99,close=101,volume=1000) for i in range(1,29)]),'error':'','read_at':__import__('time').monotonic()}
        charts.update([(SimpleNamespace(code='2330',name='測試股',price=101),{'count':5,'total':6})])
        app.root.update_idletasks()
        assert len(charts.cards['2330'][0])==4
        assert len(charts.cards['2330'][0][1].find_all())>20
        assert '至少需要 34' in str(charts.cards['2330'][0][3].itemcget(charts.cards['2330'][0][3].find_all()[-1],'text'))
        charts.update([])
        info['candidate_four_panel_smoke']=True
        from terminal_ui import refresh
        saved_rows=getattr(app,'display_rows',[])
        q=Quote('2330','台積電',101,3.5,1500,6,dt.datetime.now().timestamp())
        app.display_rows=[(q,1.5,'等待觀測突破守穩',False)]
        app.scan_rate.update({'elapsed':8.6,'count':1})
        app._terminal_refresh=0;refresh(app);root.update_idletasks()
        assert app.metric_vars['scanned'].get()=='1'
        assert app.metric_vars['seconds'].get()=='8.6 秒'
        app.focus_chart.mode.set('日 K');app.focus_chart.invalidate();root.update_idletasks()
        assert '2330' in app.focus_chart.title.get()
        assert len(app.focus_chart.canvas.find_all())>30
        app.focus_chart.mode.set('日 MACD');app.focus_chart.invalidate()
        assert any('至少需要' in str(app.focus_chart.canvas.itemcget(i,'text')) for i in app.focus_chart.canvas.find_all() if app.focus_chart.canvas.type(i)=='text')
        app.display_rows=saved_rows
        info['terminal_selection_and_measured_metrics']=True

        # Test bundled browser driver, Edge launch and period validation without relying on external network.
        import subprocess,os
        target=report.parent/'chart-fixture'
        command=[sys.executable]+([] if getattr(sys,'frozen',False) else [str(Path(__file__).with_name('MitakeDetector.py'))])+['--wantgoo-capture','2330',str(target),'--chart-fixture']
        environment=os.environ.copy();environment['PYINSTALLER_RESET_ENVIRONMENT']='1'
        checked=subprocess.run(command,env=environment,timeout=60,creationflags=getattr(subprocess,'CREATE_NO_WINDOW',0))
        chart_result=json.loads((target/'result.json').read_text(encoding='utf-8'))
        assert checked.returncode==0 and not chart_result['error'],chart_result
        assert (target/'thumb.png').stat().st_size>100
        charts.receive_wantgoo(dict(chart_result,directory=str(target)))
        charts.update([(SimpleNamespace(code='2330',name='測試股',price=101),{'count':5,'total':6})])
        app.root.update_idletasks()
        assert any(charts.cards['2330'][0][0].type(item)=='image' for item in charts.cards['2330'][0][0].find_all())
        charts.enlarge('2330');app.root.update_idletasks()
        charts.update([])
        info['wantgoo_edge_fixture']=chart_result
        original_rows=list(app.display_rows)
        charts.test_button.invoke();app.root.update_idletasks()
        assert charts.test_mode and set(charts.cards)=={'2330','2317'}
        assert [m['count'] for q,m in charts.rows]==[6,5]
        assert all(q.price is None for q,m in charts.rows)
        charts.update([])
        assert set(charts.cards)=={'2330','2317'},'Live scan redraw erased test mode'
        assert app.display_rows==original_rows,'Test button changed actual scanner results'
        charts.test_button.invoke();app.root.update_idletasks()
        assert not charts.test_mode and not charts.cards
        info['candidate_test_button']=True


        from layout_smoke import run_layout_smoke
        info['adaptive_header_layouts']=run_layout_smoke(app)
        # Actual bundled OCR: unknown names must not block numeric results.
        app.stock_names=StockNames();app.numeric_priority=True
        started=time.perf_counter()
        quick,quick_bad=app.scan_recovering_table(im,bounds)
        assert len(quick)==2 and quick_bad==0 and {q.code for q in quick}=={'2330','2317'}
        assert not app.stock_names.names,'Numeric-only scan must not learn stock names'
        reader=app.read_auto_ocr
        def no_extra_ocr(image):raise AssertionError('Same-view stock anchors were OCRed twice')
        app.read_auto_ocr=no_extra_ocr
        try:assert set(app.read_stock_anchors(im,bounds))=={'2330','2317'}
        finally:app.read_auto_ocr=reader
        info.update(priority_numeric_seconds=time.perf_counter()-started,priority_name_queue=len(getattr(app,'deferred_names',{})))
        assert not getattr(app,'deferred_names',{}),'Numeric-only scan must not queue name OCR'
        app.stock_names.names={'2330':'台積電','2317':'鴻海'}
        # Real bundled OCR: reuse high-confidence cells; weak cells may be retried.
        before=time.perf_counter()
        same,same_bad=app.scan_recovering_table(im,bounds)
        info['unchanged_cells_seconds']=time.perf_counter()-before
        if hasattr(app,'cell_metrics'):
            assert app.cell_metrics['reused']>=9,app.cell_metrics
            assert [(q.code,q.price,q.gain,q.volume,q.turnover) for q in same]==[(q.code,q.price,q.gain,q.volume,q.turnover) for q in quick]
            info['numeric_cells_reused']=app.cell_metrics['reused']
        assert len(same)==2 and same_bad==0
        excluded=Image.new('RGB',(1200,260),'#101820');draw=ImageDraw.Draw(excluded)
        for row,code in enumerate(['00929','00679B']):
            for x,text in zip(DEFAULT['centers'],[code,'IGNORED','100.50','2.30%','1500','1.20%']):
                draw.text((x*12,60+row*65),text,font=font,fill='white',anchor='mm')
        skipped,skip_bad=app.scan_recovering_table(excluded,bounds)
        assert not skipped and skip_bad==0,(skipped,skip_bad)
        assert set(app.read_stock_anchors(excluded,bounds))=={'00929','00679B'}
        assert not {'00929','00679B'}&set(app.stock_names.names)
        info['long_instrument_codes_skipped']=2
        pairing=app.show_mobile_pairing();root.update()
        pairing.destroy()
        app.numeric_priority=False
        from price_history import PriceHistory
        app.price_history=PriceHistory()
        for cycle in range(1,10):
            app.price_history.update([Quote('9090','HISTORY',100+cycle,3,2000,1,1770000000+cycle*10)],cycle)
        app.display_cfg=dict(DEFAULT)
        app.display_rows=[(Quote('9090','HISTORY',109,3,2000,1,1770000090),None,'待複查',False)]
        app.draw_results()
        values=app.tree.item(app.tree.get_children()[0],'values')
        assert len(values)==19 and values[9:17]==tuple(f'{v:.2f}' for v in range(109,101,-1)),values
        assert app.tree.heading('history0','text')==app.price_history.headings()[0]
        assert len(app.display_scale.widths)==19
        app.price_history=PriceHistory()
        from retained_candidates import RetainedCandidates
        app.retained_candidates=RetainedCandidates()
        retained_cfg=dict(DEFAULT,use_turnover=True,min_turnover=3,use_accel=True,use_breakout=True)
        candidate=(Quote('9091','RETAIN',100,3,2000,4,1770000100),2,'等待觀測突破守穩',False)
        app.display_cfg=retained_cfg
        app.retained_candidates.merge([candidate],retained_cfg)
        app.display_rows=app.retained_candidates.merge([],retained_cfg)
        app.draw_results()
        assert app.tree.item(app.tree.get_children()[0],'values')[0]=='9091'
        assert '保留候選' in app.tree.item(app.tree.get_children()[0],'values')[2]
        assert '等待重新複查' in app.tree.item(app.tree.get_children()[0],'values')[8]
        app.retained_candidates=RetainedCandidates()
        now=dt.datetime.now().timestamp()
        app.mode='CI smoke / synthetic data'
        app.render([Quote('9001','SMOKE',100,2,1500,1,now)],0,0,dict(DEFAULT),persist=True)
        assert '盤尾緩漲未通過' in next(row[2] for row in app.display_rows if row[0].code=='9001')
        assert not next(row[3] for row in app.display_rows if row[0].code=='9001')
        assert '09:00–12:50' in app.observation_summary.get() or '歷史載入' in app.observation_summary.get()
        assert (BASE/'observations.sqlite3').is_file()
        from scan_archive import ScanArchive
        assert ScanArchive(BASE/'observations.sqlite3').summary()
        archive=app.show_scan_archive();root.update_idletasks()
        assert '讀值筆數' in app.archive_output.get('1.0','end')
        archive.destroy()
        # Exercise actual Tk cells for shared cross-ranking volume warmup and rates.
        from volume_rate import VolumeRates
        app.volume_rates=VolumeRates();app.auto_engines={};app.rank_rows={}
        for sec,volume,rank in [(0,2000,'漲幅排行'),(340,2340,'成交排行'),(680,3020,'預估量增')]:
            app.render([Quote('9088','RATE',100,2,volume,1,now-680+sec)],0,0,dict(DEFAULT,source_rank=rank),persist=False)
        rate_values=[app.tree.item(item,'values')[7] for item in app.tree.get_children() if app.tree.item(item,'values')[0]=='9088']
        assert rate_values==['約2.00'],rate_values
        assert 'ratio' in app.tree['displaycolumns'],'Measured rate must remain visible after scans'
        assert app.tree.column('ratio','width')>=190
        first_time=app.tree.heading('history0','text')
        app.render([Quote('9088','RATE',100.1,2,3030,1,now+61)],0,0,dict(DEFAULT,source_rank='預估量增',scan_cycle=1),persist=False)
        assert app.tree.heading('history1','text')==first_time
        assert app.tree.heading('history0','text')!=first_time
        from price_history import PriceHistory
        app.price_history=PriceHistory()
        app.volume_rates=VolumeRates();app.auto_engines={};app.rank_rows={}
        # Verify the actual calendar, dated list and immutable 5/6 details.
        from candidate_diary import CandidateDiary,TAIPEI
        diary_cfg=dict(DEFAULT,use_turnover=True,min_turnover=3,use_accel=True,use_breakout=True)
        CandidateDiary(BASE/'observations.sqlite3').observe([(Quote('9089','DIARY',100,2,2000,4,now),2,'等待觀測突破守穩',False)],diary_cfg,VERSION)
        diary_top=app.show_candidate_diary();root.update_idletasks()
        view=app.candidate_diary_view;today=dt.datetime.fromtimestamp(now,TAIPEI).date()
        items=view.tree.get_children();assert any(view.tree.item(item,'values')[0]=='9089' for item in items)
        item=next(item for item in items if view.tree.item(item,'values')[0]=='9089')
        view.tree.selection_set(item);view.select()
        assert '5/6' in view.text.get('1.0','end')
        assert 'min_turnover' in view.text.get('1.0','end')
        view.choose((today-dt.timedelta(days=10000)).isoformat());assert not view.tree.get_children()
        view.choose(today.isoformat());assert view.tree.get_children()
        CandidateDiary(BASE/'observations.sqlite3').update_quotes([dict(code='9089',price=123.45,ts=now+2,source='GUI測試行情')])
        view.refresh(force=True)
        updated=next(view.tree.item(i,'values') for i in view.tree.get_children() if view.tree.item(i,'values')[0]=='9089')
        assert updated[5]=='123.45' and 'GUI測試行情' in updated[7],updated
        assert updated[3]=='100.00',updated
        diary_top.destroy()
        assert view.quote_stop.is_set()
        # The daily panel survives weaker readings and invalid OCR spikes.
        CandidateDiary(BASE/'observations.sqlite3').observe([(Quote('9089','DIARY',20,2,2200,4,now+1),2,'已轉弱；股價不足；等待觀測突破守穩',False)],diary_cfg,VERSION)
        CandidateDiary(BASE/'observations.sqlite3').observe([(Quote('9089','DIARY',9999,2,2200,4,now+2),2,'讀值跳動／量倒退',False)],diary_cfg,VERSION)
        app.refresh_today_candidates(force=True)
        daily=[app.today_tree.item(item,'values') for item in app.today_tree.get_children() if app.today_tree.item(item,'values')[0]=='9089']
        assert daily and daily[0][4]=='20.00',daily
        assert '待複查' in daily[0][5],daily

        # Verify the actual GUI has only the everyday action buttons.
        actions=[child.cget('text') for child in app.main_actions.winfo_children() if child.winfo_class()=='TButton']
        assert actions==['▶ 開始掃描 F8','Ⅱ 暫停','掃描設定','排行定位'],actions
        menu_labels=[app.advanced_menu.entrycget(i,'label') for i in range(app.advanced_menu.index('end')+1)]
        assert {'重新開始','緩漲明細／設定','手機連動／配對','使用說明'}<=set(menu_labels)
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
        cfg=dict(DEFAULT,guard_enabled=False,source_rank='漲幅排行',use_breakout=False)
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
        app.display_cfg=dict(DEFAULT,use_turnover=True,min_turnover=3,use_accel=True,use_breakout=True)
        app.display_rows=[(Quote('9080','LOW',20,0,50,1,now),None,'等待觀測突破守穩',False),
                          (Quote('9081','GOOD',100,3,2000,4,now),2,'轉強候選',True)]
        app.draw_results()
        assert len(app.tree.get_children())==2
        app.display_rows.append((Quote('9082','FOUR',100,3,2000,4,now),None,'等待觀測突破守穩',False))
        app.draw_results()
        four=next(item for item in app.tree.get_children() if app.tree.item(item,'values')[0]=='9082')
        assert app.tree.item(four,'values')[1]=='FOUR'
        assert '9082' not in app.name_eligible_codes
        app.display_rows.pop();app.draw_results()
        assert app.tree.item(app.tree.get_children()[-1],'values')[1]!=''
        app.result_filters['price'].set(True);app.change_result_filters()
        assert len(app.tree.get_children())==1
        assert json.loads((BASE/'settings.json').read_text('utf-8'))['result_filters']['price']
        app.result_filters['price'].set(False);app.change_result_filters()
        assert len(app.tree.get_children())==2
        candidate_cfg=dict(DEFAULT,guard_enabled=False,source_rank='候選排序測試',use_breakout=False)
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
        app.demo();app.display_scale.apply(100,save=False,resize=False);root.geometry('1540x1000');root.deiconify()
        def finish():
            try:
                root.update_idletasks()
                assert len(app.tree.get_children())==2
                screenshot=report.with_suffix('.png')
                ImageGrab.grab(bbox=(root.winfo_rootx(),root.winfo_rooty(),root.winfo_rootx()+root.winfo_width(),root.winfo_rooty()+root.winfo_height())).save(screenshot)
                app.save_daily_table()
                from resume_state import load_daily
                daily=load_daily(BASE/'daily-table.json')
                assert daily and daily['display_rows']
                expected=[app.tree.item(item,'values')[0] for item in app.tree.get_children()]
                app.display_rows=[];app.rank_rows={};app.draw_results()
                app.load_daily_table()
                assert [app.tree.item(item,'values')[0] for item in app.tree.get_children()]==expected
                assert all('複查' in str(app.tree.item(item,'values')) for item in app.tree.get_children())
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
