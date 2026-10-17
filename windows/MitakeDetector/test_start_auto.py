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
    def idle(self):return True
    def dependencies(self):return True
    def settings(self):return copy.deepcopy(self.config)
    def reset(self,mode):self.generation+=1;self.stop_event=threading.Event();return self.generation
    def show_task_progress(self,*args):pass
    def announce_task(self,*args):pass
    def restore_scan_snapshot(self,*args):pass
    def say(self,*args):pass


class StartAutoTests(unittest.TestCase):
    def test_rate_is_measured_and_stays_after_task_text_changes(self):
        from scan_rate import ScanRate
        rate=ScanRate();rate.update(dict(start=10))
        self.assertIn('5 秒',rate.text(now=15))
        rate.update(dict(elapsed=2,count=30))
        self.assertIn('2.0 秒／段',rate.text());self.assertIn('15.00 檔／秒',rate.text())
        rate.update(dict(start=20));rate.stop();self.assertIn('15.00 檔／秒',rate.text())

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
