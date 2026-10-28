import copy
import datetime as dt
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from MitakeDetector import DEFAULT,Quote,Engine
from resume_scan import ScanCheckpoint,scan_context
from table_scan import scan_table
from test_table_scan import TableController


class ResumeTests(unittest.TestCase):
    def test_interrupt_and_resume_preserves_rows_without_scroll_to_top(self):
        c=TableController();saved=[]
        def read(position):return [Quote(str(1000+i),'TEST',100,2,1500,1,position+1) for i in range(position,position+6)],0
        def interrupted(position):
            if position>=6:raise RuntimeError('interrupted')
            return read(position)
        with self.assertRaisesRegex(RuntimeError,'interrupted'):
            scan_table(c,[0,0,1,1],interrupted,lambda *s:None,checkpoint=lambda state:saved.append(copy.deepcopy(state)))
        self.assertEqual(len(saved[-1]['quotes']),9)
        self.assertEqual(saved[-1]['segment'],2)
        c.scrolls.clear()
        rows,_=scan_table(c,[0,0,1,1],read,lambda *s:None,resume=saved[-1])
        self.assertEqual(len(rows),17);self.assertFalse(any(delta>0 for delta in c.scrolls))

    def test_context_and_date_mismatch_do_not_discard_existing_progress(self):
        cfg=dict(DEFAULT,auto_config={'rankings':['漲幅排行']});context=scan_context(cfg)
        with tempfile.TemporaryDirectory() as folder:
            path=Path(folder)/'resume.json';store=ScanCheckpoint(path)
            cursor=dict(cycle=2,rank=0,table=None);store.update(context,cursor)
            self.assertEqual(ScanCheckpoint(path).resume(context),cursor)
            other=scan_context(dict(cfg,min_gain=9))
            original=path.read_bytes()
            with self.assertRaisesRegex(ValueError,'設定已變更'):store.resume(other)
            self.assertEqual(path.read_bytes(),original)
            store.clear();self.assertFalse(path.exists())

    def test_configured_page_limit_is_resumable(self):
        c=TableController()
        read=lambda n:([Quote(str(1000+i),'TEST',100,2,1500,1,n+1) for i in range(n,n+6)],0)
        rows,_=scan_table(c,[0,0,1,1],read,lambda *s:None,limit_pages=2)
        self.assertEqual(len(rows),9)

    def test_snapshot_restores_warmup_and_alert_cooldown(self):
        from resume_state import snapshot,restore
        class Tree:
            def get_children(self):return []
            def insert(self,*args,**kwargs):pass
        class Value:
            def set(self,value):self.value=value
        now=dt.datetime.now().timestamp();engine=Engine();cfg=dict(DEFAULT,guard_enabled=False,use_breakout=False)
        engine.process([Quote('2330','TEST',100,2,1500,1,now)],cfg)
        rows,_=engine.process([Quote('2330','TEST',101,3,1600,1,now+5)],cfg)
        app=SimpleNamespace(auto_engines={'漲幅排行':engine},rank_rows={'漲幅排行':rows},records=[['old']],tree=Tree(),observation_summary=Value())
        data=snapshot(app);app.auto_engines={};restore(app,data)
        self.assertEqual(len(app.auto_engines['漲幅排行'].history['2330']),2)
        self.assertEqual(app.auto_engines['漲幅排行'].last_alert,engine.last_alert)
        self.assertFalse(app.rank_rows['漲幅排行'][0][3])
        self.assertEqual(app.records,[['old']])


if __name__=='__main__':unittest.main()

class DayRolloverTests(unittest.TestCase):
    def test_prior_day_is_archived_exactly_then_today_starts_fresh(self):
        import json
        cfg=dict(DEFAULT,auto_config={'rankings':['漲幅排行']});context=scan_context(cfg)
        with tempfile.TemporaryDirectory() as folder:
            path=Path(folder)/'resume.json'
            old=dict(version=1,day='2000-01-01',context=context,cursor=dict(cycle=8,rank=0,table={'quotes':[{'code':'2330'}]}),ui={'records':['old-observation'],'cooldown':'old'})
            path.write_text(json.dumps(old,ensure_ascii=False),encoding='utf-8');original=path.read_bytes()
            store=ScanCheckpoint(path)
            self.assertIsNone(store.resume(context))
            self.assertEqual(store.archived_path.read_bytes(),original)
            self.assertFalse(path.exists());self.assertEqual(store.data,{})
            cursor=dict(cycle=1,rank=0,table=None);store.update(context,cursor)
            today=ScanCheckpoint(path)
            self.assertEqual(today.resume(context),cursor)
            self.assertNotIn('ui',today.data)
            self.assertEqual(len(list((Path(folder)/'scan-history').glob('*.json'))),1)
    def test_archive_failure_keeps_original_and_refuses_to_overwrite(self):
        import json
        from unittest.mock import patch
        context=scan_context(dict(DEFAULT,auto_config={'rankings':['漲幅排行']}))
        with tempfile.TemporaryDirectory() as folder:
            path=Path(folder)/'resume.json'
            old=dict(version=1,day='2000-01-01',context=context,cursor=dict(cycle=8,rank=0,table=None))
            path.write_text(json.dumps(old),encoding='utf-8');original=path.read_bytes();store=ScanCheckpoint(path)
            with patch('resume_scan.os.fsync',side_effect=OSError('disk full')):
                with self.assertRaisesRegex(OSError,'原進度仍保留'):store.resume(context)
            self.assertEqual(path.read_bytes(),original);self.assertEqual(store.data,old)
            self.assertIsNone(store.archived_path)
            self.assertFalse(list((Path(folder)/'scan-history').glob('*.json')))

class DailyTableTests(unittest.TestCase):
    def test_atomic_today_only_roundtrip(self):
        from resume_state import save_daily,load_daily
        with tempfile.TemporaryDirectory() as folder:
            path=Path(folder)/'daily-table.json'
            self.assertIsNone(load_daily(path))
            data={'display_rows':[[{'code':'2330','price':100},None,'待複查',False]],'price_history':[{'prices':{'2330':[123,100]}}]}
            save_daily(path,data,day='2026-10-19')
            self.assertEqual(load_daily(path,day='2026-10-19'),data)
            self.assertIsNone(load_daily(path,day='2026-10-20'))
            save_daily(path,{'records':[1]},day='2026-10-19')
            self.assertEqual(load_daily(path,day='2026-10-19'),{'records':[1]})

class ForwardSignalReviewTests(unittest.TestCase):
    def test_real_observed_returns_and_missing_intervals(self):
        from signal_review import SignalReview
        with tempfile.TemporaryDirectory() as folder:
            review=SignalReview(Path(folder)/'review.sqlite3');cfg=dict(DEFAULT)
            q=Quote('2330','TEST',100,3,2000,4,10000)
            review.observe([q],[(q,'基礎觀察候選','等待守穩')],cfg)
            review.observe([Quote('2330','TEST',99,2,2100,4,10060)],[],cfg)
            rows=review.rows();self.assertEqual(rows[0][4],60);self.assertAlmostEqual(rows[0][7],-1)
            review.observe([Quote('2330','TEST',110,2,2200,4,10220)],[],cfg)
            self.assertEqual(len(review.rows()),1)  # 180s horizon was missed by >30s.
            review.observe([Quote('2330','TEST',102,2,2300,4,10300)],[],cfg)
            self.assertEqual([r[4] for r in review.rows()],[60,300])
