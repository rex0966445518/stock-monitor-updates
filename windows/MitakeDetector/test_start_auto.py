import copy
import queue
import tempfile
import threading
import unittest
from pathlib import Path
from unittest.mock import patch
from auto_ui import AutoUI
from autoswitch import AutomationStopped
from MitakeDetector import DEFAULT,Quote
from resume_scan import ScanCheckpoint,scan_context


class Root:
    def iconify(self):pass
    def after(self,delay,callback):callback()


class Controller:
    def __init__(self,hwnd,stop):self.stop=stop;self.initial_rect=(0,0,800,600)
    @staticmethod
    def windows():return [(1,'TEST RANK')]
    def activate(self):pass
    def rect(self):return self.initial_rect
    def check(self):
        if self.stop.is_set():raise AutomationStopped('test pause')
    def monitor_hotkey(self):pass
    def pause(self,seconds):self.stop.set();raise AutomationStopped('test pause')


class Switcher:
    def __init__(self,*args,**kwargs):pass
    def switch(self,name):pass
    def label_matches(self,name):return True


class UI(AutoUI):
    def __init__(self,cfg):
        self.config=cfg;self.root=Root();self.events=queue.Queue();self.generation=0
        self.stop_event=threading.Event();self.auto_hwnd=1;self.auto_engines={}
    def hide_scan_cursor(self,token):pass
    def restore_scan_cursor(self):pass
    def idle(self):return True
    def dependencies(self):return True
    def settings(self):return copy.deepcopy(self.config)
    def reset(self,mode):self.generation+=1;self.stop_event=threading.Event();return self.generation
    def show_task_progress(self,*args):pass
    def announce_task(self,*args):pass
    def restore_scan_snapshot(self,*args):pass
    def say(self,*args):pass


class StartAutoTests(unittest.TestCase):
    def test_cursor_reveals_only_on_hardware_motion(self):
        from cursor_visibility import physical_motion
        self.assertTrue(physical_motion(123,1,0))
        self.assertTrue(physical_motion(123,0,-1))
        self.assertFalse(physical_motion(None,100,100))
        self.assertFalse(physical_motion(123,0,0))

    def test_error_feedback_splits_details_without_losing_text(self):
        from MitakeDetector import format_feedback
        source='掃描已暫停：失敗｜第17段｜已讀33檔｜table_scan.py:56'
        shown=format_feedback(source)
        self.assertEqual(shown.replace('\n','｜'),source)
        self.assertEqual(len(shown.splitlines()),4)

    def test_rate_is_measured_and_stays_after_task_text_changes(self):
        from scan_rate import ScanRate
        rate=ScanRate();rate.update(dict(start=10))
        self.assertIn('5 秒',rate.text(now=15))
        rate.update(dict(elapsed=2,count=30))
        self.assertIn('2.0 秒／段',rate.text());self.assertIn('15.00 檔／秒',rate.text())
        rate.update(dict(start=20));rate.stop();self.assertIn('15.00 檔／秒',rate.text())

    def test_completed_ranking_advances_to_second_cycle(self):
        cfg=dict(DEFAULT,auto_config=dict(title='TEST RANK',window_size=[800,600],combo=[0,0,.2,.1],menu=[0,.1,.3,.5],table=[0,.2,1,.8],rankings=['漲幅排行'],seconds=3,pages=1,full_table=True))
        app=UI(cfg);calls=[]
        def scan(*args,**kwargs):
            calls.append(True)
            if len(calls)==2:app.stop_event.set()
            return [Quote('2330','TEST',100,2,1500,1,1)],0
        with tempfile.TemporaryDirectory() as folder,patch('MitakeDetector.BASE',Path(folder)),patch('auto_ui.WindowsController',Controller),patch('auto_ui.RankingSwitcher',Switcher),patch('adaptive.Experience.wait',return_value=0),patch('table_scan.scan_table',side_effect=scan):
            app.start_auto();app.worker_thread.join(3)
            self.assertFalse(app.worker_thread.is_alive());self.assertEqual(len(calls),2)
            messages=[]
            while not app.events.empty():messages.append(app.events.get())
            self.assertTrue(any(kind=='auto_phase' and '第1輪已完成' in value and '第2輪' in value for _,kind,value in messages))

    def test_real_worker_enters_scan_for_new_and_resumed_sessions(self):
        for resumed in (False,True):
            with self.subTest(resumed=resumed),tempfile.TemporaryDirectory() as folder:
                cfg=dict(DEFAULT,auto_config=dict(title='TEST RANK',window_size=[800,600],combo=[0,0,.2,.1],menu=[0,.1,.3,.5],table=[0,.2,1,.8],rankings=['漲幅排行'],seconds=3,pages=1,full_table=True))
                state={'segment':2,'codes':['2330'],'quotes':[],'offset':1} if resumed else None
                if resumed:ScanCheckpoint(Path(folder)/'scan-checkpoint.json').update(scan_context(cfg),dict(cycle=7,rank=0,table=state))
                app=UI(cfg)
                with patch('MitakeDetector.BASE',Path(folder)),patch('auto_ui.WindowsController',Controller),patch('auto_ui.RankingSwitcher',Switcher),patch('table_scan.scan_table',return_value=([Quote('2330','TEST',100,2,1500,1,1)],0)) as scan:
                    app.start_auto();app.worker_thread.join(3)
                    self.assertFalse(app.worker_thread.is_alive())
                    scan.assert_called_once()
                    self.assertEqual(scan.call_args.kwargs['resume'],state)
                    messages=[]
                    while not app.events.empty():messages.append(app.events.get())
                    endings=[value for _,kind,value in messages if kind=='auto_end']
                    self.assertTrue(endings)
                    self.assertNotIn("local variable 'saved'",endings[-1])
                    self.assertIn('診斷已保存',endings[-1])
                    import json
                    report=json.loads((Path(folder)/'scan-interruptions.jsonl').read_text().splitlines()[-1])
                    self.assertEqual(report['ranking'],'漲幅排行')
                    self.assertEqual(report['error_type'],'AutomationStopped')
                    self.assertIn('test pause',report['traceback'])

class CandidateAlertTests(unittest.TestCase):
    def test_cross_ranking_cooldown_and_new_target(self):
        from task_progress import fresh_candidate_alerts
        last={};q=Quote('2330','TEST',100,2,1500,1,100)
        self.assertEqual(len(fresh_candidate_alerts([(q,'hit')],last,60)),1)
        self.assertEqual(fresh_candidate_alerts([(q,'other rank')],last,60),[])
        q.ts=159;self.assertEqual(fresh_candidate_alerts([(q,'hit')],last,60),[])
        q.ts=160;self.assertEqual(len(fresh_candidate_alerts([(q,'hit')],last,60)),1)
        q2=Quote('2454','TEST2',100,2,1500,1,160)
        self.assertEqual(len(fresh_candidate_alerts([(q2,'hit')],last,60)),1)
    def test_no_hits_no_popup(self):
        from task_progress import fresh_candidate_alerts
        self.assertEqual(fresh_candidate_alerts([],{},60),[])

class CursorIdleTests(unittest.TestCase):
    def test_motion_reveals_then_idle_rehides_repeatedly(self):
        from cursor_visibility import CursorIdleState
        state=CursorIdleState()
        self.assertIsNone(state.tick(0))
        self.assertEqual(state.motion(10),'show')
        self.assertIsNone(state.motion(11))
        self.assertIsNone(state.tick(12.9))
        self.assertEqual(state.tick(13),'hide')
        self.assertEqual(state.motion(14),'show')
        self.assertEqual(state.tick(16),'hide')

class YellowBandTests(unittest.TestCase):
    def test_reference_screenshot_bounds(self):
        from task_progress import status_bounds
        l,t,r,b=status_bounds((0,0,2000,1066),[0,.021,.077,.024])
        self.assertGreaterEqual(l,155);self.assertGreaterEqual(t,0)
        self.assertLessEqual(r,1985);self.assertLessEqual(b,53)
    def test_scaled_and_negative_origin_bounds(self):
        from task_progress import status_bounds
        for rect in [(0,0,800,600),(-1920,0,0,1080),(50,60,1550,960)]:
            x,y,r,b=rect
            l,t,right,bottom=status_bounds(rect,[0,.02,.077,.025])
            self.assertGreater(l,x+(r-x)*.077)
            self.assertGreaterEqual(t,y)
            self.assertLessEqual(right,r)
            self.assertLessEqual(bottom,y+round((b-y)*.049))

class ScanGoalTests(unittest.TestCase):
    def test_goal_survives_stage_changes(self):
        from task_progress import goal_title,SCAN_GOAL
        for stage in ['搜尋選單類目','逐段捲動與讀取股票','本次擷取完成，等待繼續']:
            self.assertIn(SCAN_GOAL,goal_title(stage))
            self.assertIn(stage,goal_title(stage))
    def test_only_enabled_conditions_described(self):
        from task_progress import goal_conditions
        cfg=dict(DEFAULT,guard_enabled=False,use_turnover=False,use_accel=False,use_breakout=False)
        text=goal_conditions(cfg)
        self.assertIn('總量≥1000張',text)
        self.assertNotIn('換手率',text);self.assertNotIn('量速比',text);self.assertNotIn('守穩',text)
        cfg.update(use_turnover=True,min_turnover=3,use_accel=True,accel=1.8,use_breakout=True)
        text=goal_conditions(cfg)
        self.assertIn('換手率≥3%',text);self.assertIn('量速比≥1.8倍',text);self.assertIn('兩次守穩',text)

class CandidateRankingTests(unittest.TestCase):
    def test_full_then_more_conditions_then_closeness(self):
        from candidate_ranking import ordered_candidates
        cfg=dict(DEFAULT,use_breakout=False)
        def row(code,volume,price=100,hit=False):return (Quote(code,'TEST',price,3,volume,1,100),None,'轉強候選' if hit else '總量不足',hit)
        rows=[row('9001',500),row('9002',950),row('9003',2000,hit=True),row('9004',500,40)]
        ordered=ordered_candidates(rows,cfg)
        self.assertEqual([r[0].code for r,m in ordered],['9003','9002','9001','9004'])
        self.assertEqual(ordered[0][1]['text'],'★ 完全符合 3/3')
    def test_unverified_and_stale_never_become_hits(self):
        from candidate_ranking import assess
        cfg=dict(DEFAULT,use_breakout=False)
        q=Quote('2330','TEST',100,3,2000,1,100)
        m=assess((q,None,'等待第二次讀值',False),cfg)
        self.assertFalse(m['hit']);self.assertIn('有效複查',m['missing'])
        self.assertEqual(assess((q,None,'等待重新複查',False),cfg)['label'],'待複查')
    def test_disabled_conditions_are_not_counted(self):
        from candidate_ranking import assess
        q=Quote('2330','TEST',100,3,2000,None,100)
        cfg=dict(DEFAULT,use_turnover=True,min_turnover=3,use_accel=True,use_breakout=True)
        m=assess((q,None,'等待觀測突破守穩',False),cfg)
        self.assertEqual((m['count'],m['total']),(3,6))
        self.assertEqual(m['missing'],['換手率','量速','突破守穩'])

class OptionalLearningRecoveryTests(unittest.TestCase):
    def test_name_learning_failure_does_not_stop_next_scan_cycle(self):
        cfg=dict(DEFAULT,auto_config=dict(title='TEST RANK',window_size=[800,600],combo=[0,0,.2,.1],menu=[0,.1,.3,.5],table=[0,.2,1,.8],rankings=['漲幅排行'],seconds=3,pages=1,full_table=True))
        app=UI(cfg);calls=[];app.deferred_names={'2330':None}
        def fail_learning(check):raise RuntimeError('simulated optional name failure')
        app.learn_deferred_names=fail_learning
        def scan(*args,**kwargs):
            calls.append(True)
            if len(calls)==2:app.stop_event.set()
            return [Quote('2330','TEST',100,2,1500,1,1)],0
        with tempfile.TemporaryDirectory() as folder,patch('MitakeDetector.BASE',Path(folder)),patch('auto_ui.WindowsController',Controller),patch('auto_ui.RankingSwitcher',Switcher),patch('adaptive.Experience.wait',return_value=0),patch('table_scan.scan_table',side_effect=scan):
            app.start_auto();app.worker_thread.join(3)
            self.assertFalse(app.worker_thread.is_alive());self.assertEqual(len(calls),2)
            messages=[]
            while not app.events.empty():messages.append(app.events.get())
            self.assertTrue(any(kind=='auto_status' and '股名補讀失敗' in value for _,kind,value in messages))
            self.assertTrue(any(kind=='auto_phase' and '第2輪' in value for _,kind,value in messages))
            self.assertTrue((Path(folder)/'scan-interruptions.jsonl').exists())

class PriorDayStartTests(unittest.TestCase):
    def test_f8_start_archives_prior_day_and_enters_today_scan_without_dialog(self):
        import json
        from unittest.mock import Mock
        cfg=dict(DEFAULT,auto_config=dict(title='TEST RANK',window_size=[800,600],combo=[0,0,.2,.1],menu=[0,.1,.3,.5],table=[0,.2,1,.8],rankings=['漲幅排行'],seconds=3,pages=1,full_table=True))
        with tempfile.TemporaryDirectory() as folder:
            path=Path(folder)/'scan-checkpoint.json'
            old=dict(version=1,day='2000-01-01',context=scan_context(cfg),cursor=dict(cycle=8,rank=0,table={'segment':9,'codes':['2330']}),ui={'old':'do not restore'})
            path.write_text(json.dumps(old),encoding='utf-8');original=path.read_bytes()
            app=UI(cfg);app.restore_scan_snapshot=Mock();app.say=Mock()
            with patch('MitakeDetector.BASE',Path(folder)),patch('auto_ui.WindowsController',Controller),patch('auto_ui.RankingSwitcher',Switcher),patch('tkinter.messagebox.showerror') as dialog,patch('table_scan.scan_table',return_value=([Quote('2330','TEST',100,2,1500,1,1)],0)) as scan:
                app.start_auto();app.worker_thread.join(3)
                self.assertFalse(app.worker_thread.is_alive());dialog.assert_not_called();scan.assert_called_once()
                self.assertIsNone(scan.call_args.kwargs['resume'])
                app.restore_scan_snapshot.assert_not_called()
            archives=list((Path(folder)/'scan-history').glob('*.json'))
            self.assertEqual(len(archives),1);self.assertEqual(archives[0].read_bytes(),original)
            self.assertEqual(ScanCheckpoint(path).data['cursor']['cycle'],2)  # First ranking completed; next cycle is queued.
            report=json.loads((Path(folder)/'scan-interruptions.jsonl').read_text().splitlines()[-1])
            self.assertEqual(report['cycle'],1)
            self.assertTrue(any('開始今日第1輪' in call.args[0] for call in app.say.call_args_list))

class DashboardDisplayTests(unittest.TestCase):
    def test_start_auto_keeps_dashboard_visible(self):
        from unittest.mock import Mock
        cfg=dict(DEFAULT,auto_config=dict(title='TEST RANK',window_size=[800,600],combo=[0,0,.2,.1],menu=[0,.1,.3,.5],table=[0,.2,1,.8],rankings=['漲幅排行'],seconds=3,pages=1,full_table=True))
        app=UI(cfg);app.root.iconify=Mock()
        with tempfile.TemporaryDirectory() as folder,patch('MitakeDetector.BASE',Path(folder)),patch('auto_ui.WindowsController',Controller),patch('auto_ui.RankingSwitcher',Switcher),patch('table_scan.scan_table',return_value=([Quote('2330','TEST',100,2,1500,1,1)],0)):
            app.start_auto();app.worker_thread.join(3)
            self.assertFalse(app.worker_thread.is_alive());app.root.iconify.assert_not_called()
    def test_zoom_fits_current_monitor_including_negative_coordinates(self):
        from display_scale import fit_window
        for work,window in [((1920,0,5760,2160),(2000,80,1500,900)),((-3840,0,0,2160),(-3600,50,1500,900))]:
            x,y,w,h=fit_window(window,work,2)
            self.assertGreaterEqual(x,work[0]);self.assertGreaterEqual(y,work[1])
            self.assertLessEqual(x+w,work[2]);self.assertLessEqual(y+h,work[3])
            self.assertEqual((w,h),(3000,1800))


class ResultFilterTests(unittest.TestCase):
    def test_name_gate_and_filters(self):
        from candidate_ranking import assess,name_eligible,hidden_result
        cfg=dict(DEFAULT,use_turnover=True,min_turnover=3,use_accel=True,use_breakout=True)
        q=Quote('2330','TEST',100,3,2000,1,100)
        row=(q,None,'等待觀測突破守穩',False)
        self.assertFalse(name_eligible(assess(row,cfg)))
        q.turnover=4
        self.assertFalse(name_eligible(assess(row,cfg)))  # 4/6: skip name OCR
        self.assertTrue(name_eligible(assess((q,2,'等待觀測突破守穩',False),cfg)))  # 5/6
        self.assertTrue(name_eligible(assess((q,2,'轉強候選',True),cfg)))  # 6/6
        self.assertTrue(name_eligible(dict(count=4,total=4)))
        self.assertFalse(name_eligible(dict(count=3,total=4)))
        for key in ['price','gain','turnover']:self.assertFalse(hidden_result(q,cfg,{key:True}))
        q.price=20;q.gain=12;q.turnover=None
        for key in ['price','gain','turnover']:self.assertTrue(hidden_result(q,cfg,{key:True}))
        self.assertFalse(hidden_result(q,dict(cfg,use_turnover=False),{'turnover':True}))
        self.assertFalse(hidden_result(q,cfg,{}))

class PriceHistoryTests(unittest.TestCase):
    def test_rounds_missing_restore_and_new_day(self):
        from price_history import PriceHistory
        import json
        h=PriceHistory();base=1770000000
        for cycle in range(1,11):h.update([Quote('2330','TEST',100+cycle,3,2000,1,base+cycle*10)],cycle)
        self.assertEqual(len(h.slots),8)
        self.assertEqual(h.values('2330'),[f'{v:.2f}' for v in range(110,102,-1)])
        first=h.headings()[0]
        h.update([Quote('2330','TEST',112,3,2000,1,base+105)],10)
        self.assertEqual(len(h.slots),8);self.assertEqual(h.headings()[0],first)
        h.update([Quote('2317','TEST',200,3,2000,1,base+106)],10)
        self.assertEqual(h.values('2317'),['200.00']+['—']*7)
        self.assertEqual(PriceHistory(json.loads(json.dumps(h.slots))).values('2330'),h.values('2330'))
        h.update([Quote('2330','TEST',99,3,2000,1,base+100)],10)
        self.assertEqual(h.values('2330')[0],'112.00')
        h.update([],11);self.assertEqual(len(h.slots),8)
        h.update([Quote('2330','TEST',120,3,2000,1,base+86400)],1)
        self.assertEqual(len(h.slots),1)

class RetainedCandidateTests(unittest.TestCase):
    def test_qualify_disappear_decline_and_refresh(self):
        from retained_candidates import RetainedCandidates
        r=RetainedCandidates()
        cfg=dict(DEFAULT,use_turnover=True,min_turnover=3,use_accel=True,use_breakout=True)
        q=Quote('2330','TEST',100,3,2000,4,100)
        row=(q,2,'等待觀測突破守穩',False)
        r.merge([row],cfg)
        self.assertEqual(r.peak['2330'],5)
        missing=r.merge([],cfg)
        self.assertEqual(len(missing),1)
        self.assertIn('等待重新複查',missing[0][2]);self.assertFalse(missing[0][3])
        lower=(Quote('2330','TEST',20,0,50,1,101),None,'股價不足',False)
        self.assertEqual(r.merge([lower],cfg)[0],lower)
        self.assertEqual(r.merge([],cfg)[0][0].price,20)
        self.assertEqual(r.peak['2330'],5)
        r.merge([(Quote('2317','OTHER',100,3,2000,4,101),None,'等待觀測突破守穩',False)],cfg)
        self.assertNotIn('2317',r.peak)


class LiveConditionOrderTests(unittest.TestCase):
    def test_new_batch_promotes_stock_immediately(self):
        from candidate_ranking import ordered_candidates
        cfg=dict(DEFAULT,use_turnover=True,min_turnover=3,use_accel=True,use_breakout=True)
        a=(Quote('1111','A',100,3,2000,4,100),None,'等待觀測突破守穩',False)
        b=(Quote('2222','B',100,3,2000,1,100),None,'等待觀測突破守穩',False)
        self.assertEqual([row[0].code for row,m in ordered_candidates([a,b],cfg)],['1111','2222'])
        b=(Quote('2222','B',100,3,2000,4,101),2,'等待觀測突破守穩',False)
        self.assertEqual([row[0].code for row,m in ordered_candidates([a,b],cfg)],['2222','1111'])


class NearVoiceTests(unittest.TestCase):
    def test_five_conditions_only_and_shared_cooldown(self):
        from near_alerts import near_alerts,voice_text
        cfg=dict(DEFAULT,use_turnover=True,min_turnover=3,use_accel=True,use_breakout=True)
        q=Quote('2330','台積電',100,3,2000,4,100)
        row=(q,2,'等待觀測突破守穩',False);last={}
        alerts=near_alerts([row],cfg,last)
        self.assertEqual(alerts,[q]);self.assertIn('台積電，股號 2330，觀察候選',voice_text(alerts,[row],cfg))
        self.assertEqual(near_alerts([row],cfg,last),[])
        self.assertEqual(near_alerts([(q,None,row[2],False)],cfg,{}),[])
        self.assertEqual(near_alerts([(q,2,'等待重新複查',False)],cfg,{}),[])
        self.assertEqual(near_alerts([(q,2,'轉強候選',True)],cfg,{}),[])
        q.ts+=cfg['cooldown'];self.assertEqual(near_alerts([row],cfg,last),[q])

class EntryGuardTests(unittest.TestCase):
    def samples(self):
        # Dense 3-minute observation: rising intervals dominate, breakout held.
        return [Quote('2330','TEST',100+i*.05,3,2000+i*20,4,10000+i*10) for i in range(19)]
    def test_dense_rise_and_time_hold_pass(self):
        from entry_guard import evaluate
        holds={};cfg=dict(DEFAULT)
        samples=self.samples()
        for i in range(1,len(samples)):
            failures,weak=evaluate(samples[:i+1],samples[i-1].price,holds,cfg,samples[i].ts)
        self.assertEqual(failures,[]);self.assertFalse(weak)
    def test_two_fast_reads_warmup_and_stale_fail(self):
        from entry_guard import evaluate
        samples=self.samples()[:2]
        failures,_=evaluate(samples,100,{},DEFAULT,samples[-1].ts+20)
        self.assertIn('觀測暖機不足',failures)
        self.assertIn('掃描讀值已過期',failures)
        self.assertIn('觀測突破尚未持續守穩',failures)
    def test_declining_large_volume_and_gap_fail(self):
        from entry_guard import evaluate
        samples=self.samples();samples[-1].price=98;samples[-1].volume+=10000
        failures,weak=evaluate(samples,101,{},DEFAULT,samples[-1].ts)
        self.assertTrue(weak)
        self.assertIn('上漲區間量未大於下跌區間量',failures)
        self.assertIn('距觀測高點回落過大',failures)
        sparse=[samples[i] for i in [0,12,13,18]]
        failures,_=evaluate(sparse,101,{},DEFAULT,sparse[-1].ts)
        self.assertIn('觀測間隔過長',failures)
    def test_engine_new_defaults_block_fast_confirmation_and_mark_weak(self):
        from unittest.mock import patch
        from MitakeDetector import Engine
        e=Engine();cfg=dict(DEFAULT)
        for i in range(3):
            with patch('MitakeDetector.time.time',return_value=10000+i*5):
                rows,alerts=e.process([Quote('2330','TEST',100+i*.1,3,2000+i*10,4,10000+i*5)],cfg)
        self.assertFalse(alerts);self.assertIn('進場檢核未通過',rows[0][2])
        self.assertFalse(rows[0][3])
    def test_guard_warm_state_survives_snapshot(self):
        from resume_state import snapshot,restore
        from types import SimpleNamespace
        from MitakeDetector import Engine
        e=Engine();e.guard_holds={'2330':[100,1,2]}
        class Tree:
            def get_children(self):return []
            def insert(self,*args,**kwargs):pass
        app=SimpleNamespace(auto_engines={'rank':e},rank_rows={},records=[],tree=Tree(),observation_summary=SimpleNamespace(set=lambda text:None))
        restore(app,snapshot(app))
        self.assertEqual(app.auto_engines['rank'].guard_holds,e.guard_holds)


class GuardEngineAcceptanceTests(unittest.TestCase):
    def test_full_entry_alert_requires_actual_warmup(self):
        from MitakeDetector import Engine
        from unittest.mock import patch
        cfg=dict(DEFAULT);e=Engine();hits=[]
        for sec in range(0,211,10):
            price=100+min(sec,180)*.01
            with patch('MitakeDetector.time.time',return_value=10000+sec):
                rows,alerts=e.process([Quote('2330','TEST',price,3,2000+sec*2,4,10000+sec)],cfg)
            if sec<180:self.assertFalse(alerts)
            hits.extend(alerts)
        self.assertTrue(hits)
        self.assertIn('進場檢核通過',hits[0][1])
        with patch('MitakeDetector.time.time',return_value=10220):
            rows,alerts=e.process([Quote('2330','TEST',100,3,5000,4,10220)],cfg)
        self.assertFalse(alerts);self.assertIn('已轉弱',rows[0][2])


class CaptureTimeTests(unittest.TestCase):
    def test_ocr_completion_does_not_refresh_old_capture(self):
        from auto_ui import AutoUI
        from PIL import Image
        app=AutoUI();q=Quote('2330','TEST',100,3,2000,4,99999)
        app._scan_recovering_table=lambda image,bounds:([q],0)
        image=Image.new('RGB',(100,100));image.info['captured_at']=10000
        rows,bad=app.scan_recovering_table(image,{})
        self.assertEqual(rows[0].ts,10000)

class SavedRotationTests(unittest.TestCase):
    def test_twelve_rankings_three_seconds_survive_disk_and_recalibration(self):
        import json
        import MitakeDetector as app
        from auto_ui import calibrated_auto
        from autoswitch import RANKINGS
        prefs=dict(rankings=list(RANKINGS)[:12],seconds=3,pages=7,
                   full_table=False,adaptive=False,combo=[0,0,.2,.1])
        ui=UI(copy.deepcopy(DEFAULT));ui.save=lambda:app.App.save(ui)
        with tempfile.TemporaryDirectory() as folder,patch.object(app,'BASE',Path(folder)):
            ui.persist_auto_preferences(prefs)
            loaded=json.loads((Path(folder)/'settings.json').read_text('utf-8'))
            result=calibrated_auto(loaded['auto_config'],combo=[.1,.1,.3,.1],discovered=1)
            for key in ('rankings','seconds','pages','full_table','adaptive'):
                self.assertEqual(result[key],prefs[key])
            self.assertNotEqual(result['combo'],prefs['combo'])
            ui.persist_auto_preferences(result)
            self.assertEqual(json.loads((Path(folder)/'settings.json').read_text('utf-8'))['auto_config'],result)

    def test_failed_write_rolls_back_without_success(self):
        ui=UI(copy.deepcopy(DEFAULT));original=ui.config
        def fail():raise OSError('disk full')
        ui.save=fail
        with self.assertRaises(OSError):ui.persist_auto_preferences(dict(seconds=3))
        self.assertIs(ui.config,original)

    def test_first_setup_has_defaults_and_never_mutates_saved_preferences(self):
        from auto_ui import calibrated_auto
        self.assertEqual(calibrated_auto(None)['seconds'],15)
        old=dict(rankings=['漲幅排行'],seconds=3)
        result=calibrated_auto(old,title='new screen')
        result['rankings'].append('預估量增')
        self.assertEqual(old['rankings'],['漲幅排行'])
