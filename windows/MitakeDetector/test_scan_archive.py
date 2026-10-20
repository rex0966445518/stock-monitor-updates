from contextlib import closing
import datetime as dt
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from MitakeDetector import Quote,DEFAULT
from scan_archive import ScanArchive,replay
from research_validation import Validator,experiments,metrics,better
import tdcc_history

class ArchiveTests(unittest.TestCase):
    def test_all_quotes_roundtrip_and_backup_without_erasing_old_tables(self):
        with tempfile.TemporaryDirectory() as folder:
            path=Path(folder)/'observations.sqlite3';a=ScanArchive(path)
            q=Quote('2330','台積電',100,2,1234,None,1000.125)
            with closing(a.connect()) as c,c:c.execute('CREATE TABLE old_data(value)');c.execute('INSERT INTO old_data VALUES (9)')
            a.append([q,q],[(q,None,'股價不足',False)],dict(DEFAULT,source_rank='漲幅排行'),'fixture',3,.2,received=18000)
            a.append([],[],DEFAULT,'fixture',2,.1,received=18001)
            self.assertEqual(a.summary()[0][1:4],(2,2,1))
            loaded=ScanArchive(path).batches('1970-01-01')
            self.assertEqual(loaded[0]['quotes'][0]['ts'],1000.125)
            self.assertEqual(loaded[0]['config']['source_rank'],'漲幅排行')
            backup=Path(folder)/'backup.sqlite3';a.backup(backup)
            with closing(ScanArchive(backup).connect()) as c:self.assertEqual(c.execute('SELECT value FROM old_data').fetchone(),(9,))
            path.unlink();backup.unlink()
    def test_taipei_day_and_ignored_engine_rows(self):
        with tempfile.TemporaryDirectory() as folder:
            a=ScanArchive(Path(folder)/'scan.sqlite3');q=Quote('2330','',100,2,1000,1,0)
            a.append([q],[],DEFAULT,'fixture',0,0,received=16*3600)
            self.assertEqual(a.summary()[0][0],'1970-01-02')
            with closing(a.connect()) as c:self.assertIn('引擎略過',c.execute('SELECT reason FROM scan_quotes').fetchone()[0])
    def test_replay_cannot_fill_missing_future_observations(self):
        cfg=dict(DEFAULT,steady_enabled=False,use_breakout=False,guard_enabled=False,min_volume=0)
        batches=[]
        for ts in (1000,1005):
            batches.append(dict(rank='漲幅排行',config=cfg,version='fixture',received=ts+1,quotes=[dict(code='2330',name='',price=100+ts/10000,gain=2,volume=ts,turnover=None,ts=ts)]))
        report=replay(batches)
        self.assertEqual(report['signals'],1);self.assertEqual(report['covered'],0);self.assertEqual(report['missing'],1)

class HistoryAndValidationTests(unittest.TestCase):
    def test_tdcc_requires_identity_date_and_complete_tiers(self):
        page='證券代號：2330 證券名稱：台積電 資料日期：115年10月08日'
        for tier in range(1,18):page+=f'<tr><td>{tier}</td><td>級距</td><td>1</td><td>{1000 if tier==17 else 10}</td><td>1</td></tr>'
        row=tdcc_history.parse(page,'2330','20261008')[0]
        self.assertEqual(row[3]['large'],4)
        with self.assertRaises(ValueError):tdcc_history.parse(page,'6234','20261008')
        with self.assertRaises(ValueError):tdcc_history.parse(page,'2330','20261002')
        with self.assertRaises(ValueError):tdcc_history.parse(page[:page.rfind('<tr>')],'2330','20261008')
    def test_daily_execution_starts_next_open_cost_and_nonoverlap(self):
        start=dt.date(2025,1,1)
        p=[dict(day=(start+dt.timedelta(days=i)).isoformat(),open=100,high=101,low=99,close=100,volume=1000) for i in range(27)]
        p[20].update(close=102,high=103,volume=2000)
        p[21].update(open=103,high=104,close=103,low=102)
        p[25].update(close=105,high=106)
        trades,_=experiments({'2330':p},1.5)
        self.assertEqual(len(trades),1)
        self.assertAlmostEqual(trades[0]['net'],(105/103-1)*100-.8)
        p[21].update(high=103,low=103)
        self.assertEqual(experiments({'2330':p},1.5)[0],[])
    def test_sparse_or_worse_sample_cannot_pass(self):
        base=dict(n=50,codes=10,days=20,mean=1,p10=-2)
        self.assertFalse(better(dict(base,n=2,mean=10),base))
        self.assertFalse(better(dict(base,mean=2,p10=-3),base))
    def test_promotion_holdout_not_reused_and_forward_rollback_logged(self):
        start=dt.date(2025,1,1)
        prices=[dict(day=(start+dt.timedelta(days=i)).isoformat()) for i in range(300)]
        histories={'2330':prices}
        def sample(histories,multiple):
            return [dict(code=str(1000+c),day=p['day'],end=p['day'],net={1.5:1,2.0:2,2.5:-1}[multiple]) for p in prices for c in range(10)],0
        with tempfile.TemporaryDirectory() as folder:
            validator=Validator(Path(folder)/'research.sqlite3')
            with patch('research_validation.experiments',side_effect=sample):
                state=validator.run(histories);self.assertEqual(state['active'],2)
                count=len(validator.audit());validator.run(histories);self.assertEqual(len(validator.audit()),count)
            later=[dict(code=str(1000+c),day=(start+dt.timedelta(days=301+i)).isoformat(),end=(start+dt.timedelta(days=301+i)).isoformat(),net=-2) for i in range(20) for c in range(10)]
            prices.extend(dict(day=(start+dt.timedelta(days=300+i)).isoformat()) for i in range(30))
            with patch('research_validation.experiments',return_value=(later,0)):
                state=validator.run(histories)
            self.assertEqual(state['active'],1.5);self.assertIn('回復',state['status']);self.assertIn('rollback',validator.audit()[0][1])
    def test_small_history_does_not_consume_final_test_period(self):
        with tempfile.TemporaryDirectory() as folder:
            v=Validator(Path(folder)/'research.sqlite3');state=v.run({})
            self.assertEqual(state['active'],1.5);self.assertEqual(state['last_test_end'],'')
