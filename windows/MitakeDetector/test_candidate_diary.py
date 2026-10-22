from contextlib import closing
import datetime as dt
from pathlib import Path
import tempfile
import unittest
from candidate_diary import CandidateDiary,TAIPEI
from MitakeDetector import Quote,DEFAULT

CFG=dict(DEFAULT,use_turnover=True,min_turnover=3,use_accel=True,use_breakout=True)

def row(stamp=10000,code='2330',price=100,reason='等待觀測突破守穩',hit=False):
    return (Quote(code,'台積電',price,2,2000,4,stamp),2,reason,hit)

class CandidateDiaryTests(unittest.TestCase):
    def test_daily_first_peak_latest_valid_and_original_settings(self):
        with tempfile.TemporaryDirectory() as folder:
            diary=CandidateDiary(Path(folder)/'observations.sqlite3')
            diary.observe([row()],CFG,'0.3.58')
            diary.observe([row(10010,reason='轉強候選',hit=True)],CFG,'0.3.58')
            diary.observe([row(10020,price=20,reason='已轉弱；股價不足')],CFG,'0.3.58')
            diary.observe([row(10030,price=1000,reason='讀值跳動／量倒退')],CFG,'0.3.58')
            day=dt.datetime.fromtimestamp(10000,TAIPEI).date().isoformat()
            summary=diary.stocks(day)[0]
            self.assertEqual(summary[2:4],(10000,100));self.assertEqual(summary[4:6],(10020,20))
            self.assertEqual(summary[9:],(6,4));self.assertEqual(diary.days(day[:7])[day],1)
            details=CandidateDiary(diary.path).events(day,'2330')
            self.assertEqual(len(details),4);self.assertTrue(details[0][10]);self.assertIn('min_turnover',details[0][-1])
            self.assertEqual(details[0][13],'0.3.58')
            diary.path.unlink()
    def test_qualification_episodes_persist_and_ignore_duplicate_or_unknown(self):
        with tempfile.TemporaryDirectory() as folder:
            diary=CandidateDiary(Path(folder)/'scan.sqlite3')
            day='1970-01-01'
            for sample in [row(10000),row(10010,hit=True),row(10020,reason='等待重新複查'),row(10030)]:
                diary.observe([sample],CFG,'test')
            self.assertEqual(diary.qualification_counts(day),{'2330':1})
            diary.observe([row(10040,price=20)],CFG,'test')
            diary.observe([row(10050)],CFG,'test')
            diary.observe([row(10050)],dict(CFG,source_rank='其他排行'),'test')
            self.assertEqual(CandidateDiary(diary.path).qualification_counts(day),{'2330':2})
            self.assertEqual(diary.qualification_counts('1970-01-02'),{})
            # Late import of a valid weak observation reconstructs history.
            diary.observe([row(10025,price=20)],CFG,'test')
            self.assertEqual(diary.qualification_counts(day),{'2330':3})

    def test_duplicate_capture_does_not_duplicate_history(self):
        with tempfile.TemporaryDirectory() as folder:
            diary=CandidateDiary(Path(folder)/'scan.sqlite3')
            self.assertEqual(diary.observe([row()],CFG,'0.3.58'),1)
            self.assertEqual(diary.observe([row()],CFG,'0.3.58'),0)
            self.assertEqual(len(diary.events('1970-01-01','2330')),1)
    def test_invalid_reads_and_five_of_five_do_not_create_candidate(self):
        with tempfile.TemporaryDirectory() as folder:
            d=CandidateDiary(Path(folder)/'scan.sqlite3')
            for reason in ('讀值跳動／量倒退','引擎略過：重複或非遞增擷取時間','等待重新複查'):
                self.assertEqual(d.observe([row(reason=reason)],CFG,'fixture'),0)
            self.assertEqual(d.observe([row(reason='轉強候選')],dict(CFG,use_breakout=False),'fixture'),0)
            self.assertEqual(d.days('1970-01'),{})
    def test_days_do_not_share_watchlist_but_prior_days_remain(self):
        with tempfile.TemporaryDirectory() as folder:
            d=CandidateDiary(Path(folder)/'scan.sqlite3')
            d.observe([row()],CFG,'fixture')
            d.observe([row(10000+86400,price=20,reason='股價不足；等待觀測突破守穩')],CFG,'fixture')
            self.assertEqual(len(d.stocks('1970-01-01')),1);self.assertEqual(d.stocks('1970-01-02'),[])
            d.observe([row(10010+86400)],CFG,'fixture')
            self.assertEqual(d.days('1970-01'),{'1970-01-01':1,'1970-01-02':1})
    def test_archive_backfill_is_incremental_and_keeps_recorded_version(self):
        from scan_archive import ScanArchive
        with tempfile.TemporaryDirectory() as folder:
            path=Path(folder)/'scan.sqlite3';a=ScanArchive(path);d=CandidateDiary(path)
            for sample in [row(),row(10005,code='2317',reason='引擎略過：重複或非遞增擷取時間'),row(10010,reason='轉強候選',hit=True)]:
                a.append([sample[0]],[sample],CFG,'0.3.56',0,1)
            self.assertEqual(d.import_archive(),3);self.assertEqual(d.import_archive(),0)
            self.assertEqual(len(d.stocks('1970-01-01')),1)
            records=d.events('1970-01-01','2330');self.assertEqual(len(records),2);self.assertEqual(records[0][13],'0.3.56')
    def test_pagination_exposes_older_events_without_deleting_them(self):
        with tempfile.TemporaryDirectory() as folder:
            d=CandidateDiary(Path(folder)/'scan.sqlite3')
            d.observe([row(10000+i) for i in range(205)],CFG,'fixture')
            page=d.events('1970-01-01','2330');self.assertEqual(len(page),200)
            older=d.events('1970-01-01','2330',page[-1][0]);self.assertEqual(len(older),5)
            self.assertTrue(set(r[0] for r in page).isdisjoint(r[0] for r in older))
    def test_late_archive_import_keeps_events_in_capture_time_order(self):
        with tempfile.TemporaryDirectory() as folder:
            d=CandidateDiary(Path(folder)/'scan.sqlite3')
            d.observe([row(10200)],CFG,'live')
            d.observe([row(10000)],CFG,'archive')
            records=d.events('1970-01-01','2330')
            self.assertEqual([r[1] for r in records],[10200,10000])
            self.assertEqual(d.stocks('1970-01-01')[0][2],10000)
            self.assertEqual(d.events('1970-01-01','2330',records[0][0])[0][1],10000)

class LatestQuoteTests(unittest.TestCase):
    def test_cross_day_price_updates_without_rewriting_history(self):
        with tempfile.TemporaryDirectory() as folder:
            d=CandidateDiary(Path(folder)/'scan.sqlite3');d.observe([row()],CFG,'old')
            before=d.events('1970-01-01','2330')
            d.observe([row(10000+86400,price=20,reason='已轉弱；股價不足；等待觀測突破守穩')],CFG,'new')
            self.assertEqual(d.latest_quotes('1970-01-01')['2330']['price'],20)
            self.assertEqual(d.events('1970-01-01','2330'),before)
            self.assertEqual(d.stocks('1970-01-02'),[])
            d.update_quotes([dict(code='2330',price=150,ts=10000+86400+1,source='test')])
            d.update_quotes([dict(code='2330',price=999,ts=10000,source='older')])
            self.assertEqual(d.latest_quotes('1970-01-01')['2330']['price'],150)
            self.assertEqual(d.stocks('1970-01-01')[0][3],100)
    def test_mis_rejects_missing_trade_future_and_ambiguous_markets(self):
        from diary_quotes import parse_quotes
        base=dict(c='2330',ex='tse',z='123.5',d='20261020',t='13:30:00')
        now=dt.datetime(2026,10,21,tzinfo=TAIPEI).timestamp()
        self.assertEqual(parse_quotes(dict(rtcode='0000',msgArray=[base]),['2330'],now)[0]['price'],123.5)
        for delta in [dict(z='-'),dict(z='nan'),dict(z='0'),dict(d='20990101')]:
            self.assertEqual(parse_quotes(dict(rtcode='0000',msgArray=[dict(base,**delta)]),['2330'],now),[])
        self.assertEqual(parse_quotes(dict(rtcode='0000',msgArray=[base,dict(base,ex='otc')]),['2330'],now),[])
