import csv
import datetime as dt
import io
import json
from pathlib import Path
import tempfile
import unittest
import research_sources as sources
from research_model import ResearchStore,evaluate


class OfficialParserTests(unittest.TestCase):
    def test_dates_and_monthly_otc_units(self):
        self.assertEqual(sources.date('115/10/16'),'2026-10-16')
        self.assertEqual(sources.date('20261016'),'2026-10-16')
        self.assertEqual(sources.date('11509',month=True),'2026-09-01')
        sample=dict(tables=[dict(data=[['115/09/01','267','9,632','36.1','36.7','35.8','35.8']])])
        rows=sources.monthly(sample,'6234','TPEx')
        self.assertEqual(rows[0][3]['volume'],267000)
        self.assertEqual(rows[0][3]['amount'],9632000)
    def test_weekly_requires_all_tiers_and_excludes_adjustment(self):
        text=io.StringIO();writer=csv.writer(text)
        writer.writerow(['資料日期','證券代號','持股分級','人數','股數','占集保庫存數比例%'])
        for tier in range(1,18):writer.writerow(['20261016','2330  ',tier,1,1000 if tier==17 else 999 if tier==16 else 10,0])
        row=sources.weekly(text.getvalue())[0]
        self.assertEqual(row[:3],('weekly','2330','2026-10-16'))
        self.assertEqual(row[3]['large'],4)
        self.assertEqual(row[3]['small'],3)
        with self.assertRaises(ValueError):sources.weekly('\n'.join(text.getvalue().splitlines()[:-1]))
    def test_foreign_and_trust_are_separate_and_dates_verified(self):
        fields=['證券代號','外陸資買賣超股數(不含外資自營商)','投信買賣超股數','自營商買賣超股數']
        data=dict(stat='OK',date='20261016',fields=fields,data=[['2330','1,000','-200','900']])
        row=sources.institutions(data,expected='2026-10-16')[0]
        self.assertEqual(row[3],dict(foreign=1000,trust=-200,dealer=900))
        with self.assertRaises(ValueError):sources.institutions(data,expected='2026-10-15')
    def test_otc_historical_layout_changes_are_rejected(self):
        fields=['代號','名稱']+['買進股數','賣出股數','買賣超股數']*7+['三大法人買賣超股數合計']
        row=['6234','TEST']+['0']*22;row[4]='100';row[13]='-20';row[22]='50'
        data=dict(stat='ok',tables=[dict(date='115/10/16',fields=fields,data=[row])])
        parsed=sources.otc_institution_history(data,'2026-10-16')
        self.assertEqual(parsed[0][3],dict(foreign=100,trust=-20,dealer=50))
        data['tables'][0]['fields'].pop()
        with self.assertRaises(ValueError):sources.otc_institution_history(data,'2026-10-16')


class ResearchEvidenceTests(unittest.TestCase):
    def sample(self):
        today=dt.date(2026,10,19)
        weeks=[dict(day=f'2026-10-{day:02d}',large=20+i,small=10-i,total=1000000) for i,day in enumerate([2,9,16])]
        days=[];day=today
        while len(days)<25:
            if day.weekday()<5:days.append(day.isoformat())
            day-=dt.timedelta(days=1)
        days.reverse()
        prices=[dict(day=day,open=100,high=101,low=99,close=100,volume=1000000,amount=100000000,market='TWSE') for day in days]
        inst=[dict(day=day,foreign=1000,trust=0,dealer=0) for day in days[-5:]]
        revenue=[dict(day='2026-09-01',yoy=10,mom=2)]
        return today,weeks,inst,prices,revenue
    def test_incomplete_weeks_never_become_accumulation(self):
        t,w,i,p,r=self.sample();report=evaluate('2330',w[:1],i,p,r,[],today=t)
        self.assertIn('資料不足',report['stage']);self.assertIn('集保週資料1/3期',report['missing'])
    def test_two_week_increases_and_repeated_buying_form_watch_only(self):
        t,w,i,p,r=self.sample();report=evaluate('2330',w,i,p,r,[],today=t)
        self.assertEqual(report['stage'],'疑似吸籌觀察')
        self.assertEqual(report['large_change'],2)
        w[-1]['total']*=2
        self.assertIn('資料不足',evaluate('2330',w,i,p,r,[],today=t)['stage'])
    def test_breakout_requires_later_retest_and_advance(self):
        t,w,i,p,r=self.sample()
        p[-3].update(high=103,close=102,volume=2000000)
        p[-2].update(high=102,low=101,close=101.5,volume=700000)
        p[-1].update(high=104,close=103,volume=1200000)
        report=evaluate('2330',w,i,p,r,[],today=t)
        self.assertEqual(report['stage'],'日線轉強確認（非買入指令）')
        p[-1]['close']=98
        self.assertIn('失效',evaluate('2330',w,i,p,r,[],today=t)['stage'])
    def test_misaligned_and_old_data_block_confirmation(self):
        t,w,i,p,r=self.sample();i[-1]['day']='2026-10-16'
        self.assertIn('資料不足',evaluate('2330',w,i,p,r,[],today=t)['stage'])
        self.assertIn('資料不足',evaluate('2330',w,[],p,r,[],today=t+dt.timedelta(days=30))['stage'])
    def test_store_idempotent_dates_names_and_reopen(self):
        with tempfile.TemporaryDirectory() as folder:
            path=Path(folder)/'research.sqlite3';store=ResearchStore(path)
            source='https://openapi.twse.com.tw/'
            store.put([('weekly','2330','2026-10-02',dict(large=20,small=10,total=1000))],source)
            store.put([('weekly','2330','2026-10-02',dict(large=21,small=9,total=1000))],source)
            self.assertEqual(len(ResearchStore(path).facts('weekly','2330')),1)
            store.source_status('test',123,'failed')
            self.assertEqual(store.statuses()[0][2],'failed')
            path.unlink()  # Windows must release every database connection.


class ResearchWorkerTests(unittest.TestCase):
    def test_network_errors_preserve_last_good_data(self):
        from research_service import ResearchService
        with tempfile.TemporaryDirectory() as folder:
            events=[]
            def fail(url):raise OSError('offline')
            service=ResearchService(Path(folder),lambda *args:events.append(args),fetcher=fail)
            service.store.put([('weekly','2330','2026-10-02',dict(large=20,small=10,total=1000))],'fixture')
            service.dataset('weekly',sources.URLS['weekly'],sources.weekly)
            self.assertEqual(len(service.store.facts('weekly','2330')),1)
            self.assertIn('失敗',service.store.statuses()[0][2]);service.close()
