import datetime as dt
from types import SimpleNamespace
import unittest
from volume_rate import VolumeRates
from MitakeDetector import Engine,Quote,DEFAULT

class VolumeRateTests(unittest.TestCase):
    def test_cross_ranking_samples_form_one_ratio_without_relaxing_price_checks(self):
        rates=VolumeRates();cfg=dict(DEFAULT,use_accel=True,guard_enabled=False)
        rows=[]
        for stamp,volume in ((10000,2000),(10300,2300),(10600,2900)):
            engine=Engine(rates)
            rows,_=engine.process([Quote('2330','',100,2,volume,1,stamp)],cfg)
        self.assertAlmostEqual(rows[0][1],2)
        self.assertFalse(rows[0][3]);self.assertIn('等待第二次讀值',rows[0][2])
    def test_irregular_actual_windows_are_time_normalized_and_marked(self):
        rates=VolumeRates()
        for t,v in ((0,2000),(340,2340),(680,3020)):rates.ingest('2330',10000+t,v)
        result=rates.result('2330',10680)
        self.assertAlmostEqual(result.value,2);self.assertTrue(result.approximate)
        self.assertEqual(result.cell(),'約2.00');self.assertIn('340秒',result.status)
    def test_warmup_gap_and_zero_denominator_are_distinct(self):
        r=VolumeRates();r.ingest('2330',10000,1000);r.ingest('2330',10200,1000)
        self.assertEqual(r.result('2330',10200).cell(),'累積03:20/10:00')
        r.ingest('2330',10600,1000)
        self.assertEqual(r.result('2330',10600).status,'採樣缺口／需回訪')
        r=VolumeRates()
        for t,v in ((0,1000),(300,1000),(600,1100)):r.ingest('2330',10000+t,v)
        self.assertEqual(r.result('2330',10600).cell(),'前窗無成交量')
    def test_recent_zero_volume_is_real_zero_not_missing(self):
        r=VolumeRates()
        for t,v in ((0,1000),(300,1100),(600,1100)):r.ingest('2330',10000+t,v)
        self.assertEqual(r.result('2330',10600).cell(),'0.00')
    def test_day_change_duplicate_out_of_order_and_regression(self):
        r=VolumeRates()
        for t,v in ((10000,1000),(10300,1100),(10600,1300)):self.assertTrue(r.ingest('2330',t,v))
        self.assertFalse(r.ingest('2330',10600,1300));self.assertFalse(r.ingest('2330',10500,1200))
        self.assertFalse(r.ingest('2330',10605,1200));self.assertEqual(len(r.history['2330']),3)
        self.assertTrue(r.ingest('2330',10600+86400,10));self.assertIsNone(r.result('2330',97000).value)
        self.assertFalse(r.ingest('2330',10600,1300))
    def test_cross_rank_regression_blocks_incoming_row(self):
        rates=VolumeRates();a=Engine(rates);b=Engine(rates)
        a.process([Quote('2330','',100,2,2000,1,10000)],DEFAULT)
        rows,alerts=b.process([Quote('2330','',100,2,1900,1,10005)],DEFAULT)
        self.assertFalse(alerts);self.assertIn('跨排行',rows[0][2]);self.assertEqual(rates.history['2330'][-1][1],2000)
    def test_snapshot_survives_and_old_rank_histories_migrate(self):
        from resume_state import snapshot,restore
        class Tree:
            def get_children(self):return []
            def insert(self,*args,**kwargs):pass
        class Value:
            def set(self,value):pass
        rates=VolumeRates();engines={}
        for rank,t,v in (('漲幅排行',10000,1000),('成交排行',10300,1300),('預估量增',10600,1900)):
            e=Engine(rates);e.process([Quote('2330','',100,2,v,1,t)],DEFAULT);engines[rank]=e
        app=SimpleNamespace(volume_rates=rates,auto_engines=engines,rank_rows={},records=[],tree=Tree(),observation_summary=Value())
        for legacy in (False,True):
            data=snapshot(app)
            if legacy:data.pop('volume_rates',None)
            restore(app,data)
            self.assertAlmostEqual(app.volume_rates.result('2330',10600).value,2)
            self.assertTrue(all(e.volume_rates is app.volume_rates for e in app.auto_engines.values()))
    def test_display_explains_missing_and_approximate_values(self):
        from MitakeDetector import App
        r=VolumeRates();app=SimpleNamespace(volume_rates=r)
        q=Quote('2330','',100,2,1000,1,10000);r.ingest(q.code,q.ts,q.volume)
        self.assertEqual(App.volume_ratio_text(app,q,None,''),'累積00:00/10:00')
        self.assertEqual(App.volume_ratio_text(app,q,None,'讀值跳動／量倒退'),'讀值待複查')

class PriceTimeTests(unittest.TestCase):
    def test_second_price_time_appears_even_when_full_round_is_still_one(self):
        from price_history import PriceHistory
        h=PriceHistory();base=1770000000//60*60
        h.update([Quote('2330','',100,2,1000,1,base+1)],cycle=1,interval=60)
        first=h.headings()[0]
        h.update([Quote('2330','',101,3,1100,1,base+61)],cycle=1,interval=60)
        self.assertEqual(h.values('2330')[:2],['101.00','100.00'])
        self.assertEqual(h.headings()[1],first);self.assertNotEqual(h.headings()[0],first)
        h.update([Quote('2317','',200,2,2000,1,base+65)],cycle=1,interval=60)
        self.assertEqual(len(h.slots),2);self.assertEqual(h.values('2317')[:2],['200.00','—'])
        h.update([],cycle=2,interval=60);self.assertEqual(len(h.slots),2)
    def test_eight_time_windows_restore_and_old_samples_do_not_reorder_latest(self):
        from price_history import PriceHistory
        import json
        h=PriceHistory();base=1770000000//60*60
        for i in range(10):h.update([Quote('2330','',100+i,2,1000,1,base+i*60)],interval=60)
        self.assertEqual(len(h.slots),8);latest=h.headings()[0]
        h.update([Quote('2330','',1,2,1000,1,base)],interval=60)
        self.assertEqual(h.headings()[0],latest);self.assertEqual(h.values('2330')[0],'109.00')
        self.assertEqual(PriceHistory(json.loads(json.dumps(h.slots))).headings(),h.headings())

class RoundClockTests(unittest.TestCase):
    def test_second_full_round_emits_new_cycle_time_and_preserves_checkpoint(self):
        from pathlib import Path
        import tempfile
        from unittest.mock import patch
        from test_start_auto import UI,Controller,Switcher
        from resume_scan import ScanCheckpoint
        cfg=dict(DEFAULT,auto_config=dict(title='TEST RANK',window_size=[800,600],combo=[0,0,.2,.1],menu=[0,.1,.3,.5],table=[0,.2,1,.8],rankings=['漲幅排行'],seconds=3,pages=1,full_table=True))
        app=UI(cfg);calls=[]
        def scan(*args,**kwargs):
            calls.append(True)
            quotes=[Quote('2330','',100,2,1500+len(calls),1,10000+len(calls)*60)]
            kwargs['observed'](quotes,0)
            if len(calls)==2:app.stop_event.set()
            return quotes,0
        with tempfile.TemporaryDirectory() as folder,patch('MitakeDetector.BASE',Path(folder)),patch('auto_ui.WindowsController',Controller),patch('auto_ui.RankingSwitcher',Switcher),patch('adaptive.Experience.wait',return_value=0),patch('table_scan.scan_table',side_effect=scan):
            app.start_auto();app.worker_thread.join(3)
            self.assertFalse(app.worker_thread.is_alive());self.assertEqual(len(calls),2)
            events=[]
            while not app.events.empty():events.append(app.events.get())
            self.assertEqual([p[-1]['scan_cycle'] for _,kind,p in events if kind=='auto_quotes'],[1,2])
            second=[p for _,kind,p in events if kind=='auto_phase' and '第2輪' in p and '起始' in p]
            self.assertTrue(second);self.assertIn('本輪耗時',second[0])
            cursor=ScanCheckpoint(Path(folder)/'scan-checkpoint.json').data['cursor']
            self.assertEqual(cursor['cycle'],2);self.assertIsInstance(cursor['round_started'],float)
