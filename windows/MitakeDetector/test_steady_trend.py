import datetime as dt
import tempfile
import unittest
from pathlib import Path
from MitakeDetector import Quote,Engine,DEFAULT
from steady_trend import SteadyTracker,TAIPEI,session,gate_rows,process,load_archive,validate_config,BLOCK

START=dt.datetime(2026,10,20,9,tzinfo=TAIPEI).timestamp()
END=START+230*60
CFG=dict(DEFAULT,guard_enabled=False,use_breakout=False,min_price=1,min_volume=1,min_gain=0,max_gain=10)
def quote(minute,price=None,code='1234'):
    return Quote(code,'測試',100+minute*.01 if price is None else price,2,1000+minute*10,3,START+minute*60)
def tracker(begin=0,stop=230,price=None,skip=()):
    t=SteadyTracker()
    for minute in range(begin,stop+1):
        if minute not in skip:t.add(quote(minute,price(minute) if price else None),now=END)
    return t

class SteadyTests(unittest.TestCase):
    def test_smooth_pass_only_after_window(self):
        t=tracker()
        self.assertFalse(t.evaluate('1234',CFG,END-1)['passed'])
        r=t.evaluate('1234',CFG,END)
        self.assertTrue(r['passed'],r);self.assertAlmostEqual(r['rise'],2.3)
        self.assertEqual(r['coverage'],100)
    def test_late_start_is_insufficient(self):
        r=tracker(begin=10).evaluate('1234',CFG,END)
        self.assertFalse(r['passed']);self.assertEqual(r['status'],'資料不足')
    def test_early_stop_is_insufficient(self):
        self.assertEqual(tracker(stop=224).evaluate('1234',CFG,END)['status'],'資料不足')
    def test_gap_and_sparse_coverage(self):
        self.assertEqual(tracker(skip=range(50,75)).evaluate('1234',CFG,END)['status'],'資料不足')
        t=tracker(skip=[m for m in range(231) if m%10!=0])
        self.assertEqual(t.evaluate('1234',CFG,END)['status'],'資料不足')
    def test_flat_and_tail_pump_rejected(self):
        for f in (lambda m:100,lambda m:100 if m<200 else 100+(m-200)*.08):
            r=tracker(price=f).evaluate('1234',CFG,END)
            self.assertFalse(r['passed'],r)
    def test_spike_rejected(self):
        r=tracker(price=lambda m:100+m*.002+(2 if m>=100 else 0)).evaluate('1234',CFG,END)
        self.assertFalse(r['passed']);self.assertIn('急拉',r['detail'])
    def test_drawdown_rejected(self):
        r=tracker(price=lambda m:100+m*.01 if m<190 else 100+(m-190)*.01).evaluate('1234',CFG,END)
        self.assertFalse(r['passed']);self.assertIn('回撤',r['detail'])
    def test_weak_after_window(self):
        self.assertFalse(tracker().evaluate('1234',CFG,END+300,100)['passed'])
    def test_invalid_future_wrong_day_never_fills(self):
        t=SteadyTracker()
        for q,reason in [(quote(0),'讀值跳動'),(quote(1,float('nan')),''),(quote(231),''),(quote(0),'等待第二次讀值')]:t.add(q,reason,END)
        t.add(quote(4),'',START+60)
        self.assertFalse(t.points)
        t.add(quote(1),'',END+86400)
        self.assertFalse(t.points)
    def test_minute_dedup_preserves_first_anchor(self):
        t=tracker();q=quote(0);q.ts+=30;q.price=100.1;t.add(q,now=END)
        self.assertEqual(len(t.points['1234']),231)
        self.assertEqual(t.first['1234'][1],100)
        self.assertAlmostEqual(t.evaluate('1234',CFG,END)['rise'],2.3)
    def test_merge_preserves_latest_and_day_rollover(self):
        t=tracker(begin=200);t.merge(tracker(stop=199))
        self.assertTrue(t.evaluate('1234',CFG,END)['passed'])
        t.reset_day(END+86400);t.merge(tracker())
        self.assertFalse(t.points)
    def test_gate_blocks_desktop_near_and_mobile(self):
        from near_alerts import near_alerts
        from candidate_ranking import assess
        cfg=dict(CFG,use_breakout=True,use_accel=True,use_turnover=True,min_turnover=1,accel=1)
        rows,_=gate_rows([(quote(230),2,'等待觀測突破守穩',False)],cfg,SteadyTracker(),END)
        self.assertEqual(assess(rows[0],cfg)['count'],5)
        self.assertEqual(assess(rows[0],cfg)['label'],'待盤尾檢核')
        self.assertFalse(near_alerts(rows,cfg,{}))
        from mobile_bridge import MobileBridge
        with tempfile.TemporaryDirectory() as d:
            bridge=MobileBridge(Path(d)/'pair.json');bridge.publish(rows,cfg,'test')
            self.assertFalse(bridge.events)
    def test_pass_enables_near(self):
        from near_alerts import near_alerts
        cfg=dict(CFG,use_breakout=True,use_accel=True,use_turnover=True,min_turnover=1,accel=1)
        rows,_=gate_rows([(quote(230),2,'等待觀測突破守穩',False)],cfg,tracker(),END)
        self.assertEqual(len(near_alerts(rows,cfg,{})),1)
    def test_disabled_and_loading(self):
        raw=[(quote(230),2,'轉強候選',True)]
        rows,_=gate_rows(raw,dict(CFG,steady_enabled=False),SteadyTracker(),END)
        self.assertEqual(rows,raw)
        rows,_=gate_rows(raw,CFG,tracker(),END,ready=False)
        self.assertFalse(rows[0][3]);self.assertIn('歷史載入中',rows[0][2])
    def test_no_cooldown_consumption_before_ready(self):
        e=Engine();t=tracker(stop=228)
        for minute in (228,229):
            rows,alerts,_=process(e,[quote(minute)],CFG,t,START+minute*60)
            self.assertFalse(alerts);self.assertNotIn('1234',e.last_alert)
        rows,alerts,_=process(e,[quote(230)],CFG,t,END)
        self.assertEqual(len(alerts),1,rows);self.assertIn('盤尾緩漲通過',alerts[0][1])
    def test_archive_causal_restore_and_replay(self):
        from scan_archive import ScanArchive,replay
        with tempfile.TemporaryDirectory() as d:
            path=Path(d)/'obs.db';archive=ScanArchive(path)
            for m in range(231):
                q=quote(m);archive.append([q],[(q,None,'正常',False)],CFG,'test',0,0,received=q.ts)
            loaded=load_archive(path,END)
            self.assertTrue(loaded.evaluate('1234',CFG,END)['passed'])
            early=load_archive(path,START+60*100)
            self.assertEqual(max(v[0] for v in early.points['1234'].values()),START+6000)
            batches=archive.batches(session(END)[0])
            self.assertEqual(replay(batches)['signals'],1)
            self.assertEqual(replay(batches[:200])['signals'],0)
            self.assertFalse(load_archive(path,END+86400).points)
    def test_configuration_limits(self):
        validate_config(CFG)
        for key,val in [('steady_coverage',101),('steady_r2',2),('steady_min_rise',0),('steady_max_gap',31)]:
            with self.assertRaises(ValueError):validate_config(dict(CFG,**{key:val}))

if __name__=='__main__':unittest.main()
