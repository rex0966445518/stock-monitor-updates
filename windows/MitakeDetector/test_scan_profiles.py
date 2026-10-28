import datetime as dt
import unittest
from dataclasses import replace
from MitakeDetector import Quote, Engine, DEFAULT
from scan_profiles import ShrinkTracker, RULES, validate, TZ
from steady_trend import SteadyTracker, process
START=dt.datetime(2026,10,23,9,tzinfo=TZ).timestamp()
class ProfilesTest(unittest.TestCase):
    def setUp(self):
        self.cfg=dict(DEFAULT,scan_profile='shrink',cooldown=10)
        self.tracker=ShrinkTracker();self.tracker.configure(self.cfg,START)
    def q(self,s,p=100,v=1000):return Quote('2330','測試',p,4,v,5,START+s)
    def feed(self):
        for s,p,v in ((0,99.9,1000),(300,100,1500),(600,100.5,1850),(900,101,2070)):
            q=self.q(s,p,v);result=self.tracker.apply([(q,None,'',False)],self.cfg,q.ts)
        return q,result
    def test_pass_and_one_alert(self):
        q,(rows,alerts,reports)=self.feed()
        self.assertTrue(rows[0][3]);self.assertEqual(len(alerts),1)
        self.assertEqual(reports[q.code]['volumes'],[500,350,220])
        q=replace(q,ts=q.ts+15,volume=2071)
        rows,alerts,r=self.tracker.apply([(q,None,'',False)],self.cfg,q.ts)
        self.assertTrue(rows[0][3]);self.assertFalse(alerts);self.assertEqual(r[q.code]['triggers'],1)
    def test_missing_boundary(self):
        q,_=self.feed();self.tracker.points[q.code].popleft()
        self.assertFalse(self.tracker.evaluate(q,self.cfg,q.ts)['passed'])
    def test_capture_gaps(self):
        for s,p,v in ((0,99.9,1000),(250,100,1500),(600,100.5,1850),(900,101,2070)):
            q=self.q(s,p,v);self.tracker.add(q,q.ts)
        self.assertFalse(self.tracker.evaluate(q,self.cfg,q.ts)['passed'])
    def test_tolerance_visible(self):
        for s,p,v in ((0,99.9,1000),(295,100,1500),(590,100.5,1850),(900,101,2070)):
            q=self.q(s,p,v);self.tracker.add(q,q.ts)
        r=self.tracker.evaluate(q,self.cfg,q.ts)
        self.assertTrue(r['passed']);self.assertEqual(r['durations'],[295,295,310])
    def test_missing_fields(self):
        q,_=self.feed()
        for key in ('above_average','above_open','near_high','macd','average_up'):
            c=dict(self.cfg,**{'use_shrink_'+key:True})
            r=self.tracker.evaluate(q,c,q.ts)
            self.assertFalse(r['passed']);self.assertEqual(r['status'],'待確認')
    def test_duplicate_does_not_rearm(self):
        q,_=self.feed();rows,alerts,_=self.tracker.apply([(q,None,'',False)],self.cfg,q.ts)
        self.assertFalse(rows[0][3]);self.assertFalse(alerts)
        q=replace(q,ts=q.ts+2,volume=2071)
        self.assertFalse(self.tracker.apply([(q,None,'',False)],self.cfg,q.ts)[1])
    def test_zero_volume(self):
        q,_=self.feed();self.tracker.points[q.code][-1]=replace(q,volume=1850)
        self.assertFalse(self.tracker.evaluate(q,self.cfg,q.ts)['passed'])
    def test_invalid_read(self):
        q,_=self.feed()
        for v,p in ((100,101),(2071,200)):
            b=replace(q,volume=v,price=p,ts=q.ts+1)
            self.assertFalse(self.tracker.apply([(b,None,'',True)],self.cfg,b.ts)[0][0][3])
    def test_next_day_and_after_close(self):
        q,_=self.feed();b=replace(q,ts=START+5*3600)
        self.assertFalse(self.tracker.apply([(b,None,'',True)],self.cfg,b.ts)[1])
        self.tracker.configure(self.cfg,q.ts+86400)
        self.assertFalse(self.tracker.points);self.assertFalse(self.tracker.counts)
    def test_combined_requires_base(self):
        self.cfg['scan_profile']='combined';q,(rows,alerts,_)=self.feed()
        self.assertFalse(rows[0][3]);self.assertFalse(alerts)
    def test_validation_and_config_change(self):
        q,_=self.feed();self.cfg['shrink_percent']=99;self.tracker.configure(self.cfg,q.ts)
        self.assertFalse(self.tracker.states);self.assertFalse(self.tracker.counts)
        for k,_,_ in RULES:self.cfg['use_shrink_'+k]=False
        with self.assertRaises(ValueError):validate(self.cfg)
    def test_profile_score(self):
        q,(rows,_,_)=self.feed()
        from candidate_ranking import assess
        self.assertEqual(assess(rows[0],self.cfg)['total'],4)
        self.assertTrue(assess(rows[0],self.cfg)['hit'])
    def test_cross_ranking_pipeline(self):
        tracker=SteadyTracker();engines=[Engine(),Engine()];shrink=ShrinkTracker()
        cfg=dict(self.cfg,use_accel=True,accel=99,steady_enabled=True,guard_enabled=True)
        for i,(s,p,v) in enumerate(((0,99.9,1000),(300,100,1500),(600,100.5,1850),(900,101,2070))):
            q=self.q(s,p,v);rows,alerts,_=process(engines[i%2],[q],cfg,tracker,q.ts,True,shrink)
        self.assertTrue(rows[0][3]);self.assertEqual(len(alerts),1)
    def test_original_unchanged(self):
        cfg=dict(self.cfg,scan_profile='original',steady_enabled=False,guard_enabled=False,use_breakout=False,use_accel=False)
        a,b=Engine(),Engine();tracker=SteadyTracker()
        for q in [self.q(0),self.q(1,100.1,1001)]:
            self.assertEqual(a.process([q],cfg,now=q.ts),process(b,[q],cfg,tracker,q.ts)[:2])
if __name__=='__main__':unittest.main()
