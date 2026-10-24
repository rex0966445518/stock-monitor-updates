import unittest
from candidate_charts import daily_series,macd,source_links

class CandidateChartTests(unittest.TestCase):
    def row(self,**changes):
        return dict(dict(day='2026-01-02',open=10,high=12,low=9,close=11,volume=1000),**changes)
    def test_invalid_bars_never_draw(self):
        rows=[self.row(),self.row(day='2026-01-03',low=13),self.row(day='2026-01-04',volume=float('nan')),self.row(day='2099-01-01')]
        self.assertEqual(len(daily_series(rows)),1)
    def test_sort_deduplicate_and_preserve_volume_unit(self):
        rows=daily_series([self.row(day='2026-01-03'),self.row(),self.row(close=12)])
        self.assertEqual([r['day'] for r in rows],['2026-01-02','2026-01-03'])
        self.assertEqual(rows[0]['close'],12)
        self.assertEqual(rows[0]['volume'],1000)
    def test_macd_warmup_and_constant_series(self):
        out=macd([self.row() for _ in range(40)])
        self.assertEqual(len(out),40)
        self.assertIsNone(out[24][0]);self.assertIsNone(out[32][1])
        self.assertEqual(out[33],(0,0,0))
    def test_macd_linear_series_known_values(self):
        out=macd([self.row(close=i+1) for i in range(50)])
        self.assertAlmostEqual(out[-1][0],7)
        self.assertAlmostEqual(out[-1][1],7)
        self.assertAlmostEqual(out[-1][2],0)
    def test_links_validate_codes(self):
        self.assertEqual(source_links('2330')[0],'https://www.investing.com/equities/taiwan-semicon-chart')
        for code in ['23300','<script>','１２３４']:
            with self.assertRaises(ValueError):source_links(code)

class WantGooTests(unittest.TestCase):
    def test_reject_daily_or_wrong_stock_or_empty_chart(self):
        from wantgoo_chart import validate_state
        state=dict(selected=True,title='台積電(2330)',stamp='2026-01-02 13:30',candles=20,loading=False)
        self.assertEqual(validate_state(state,'2330'),state['stamp'])
        for delta in [dict(selected=False),dict(stamp='2026-01-02'),dict(candles=0),dict(loading=True),dict(title='台積電(2331)')]:
            with self.assertRaises(ValueError):validate_state(dict(state,**delta),'2330')
    def test_previous_day_cache_and_failed_cache(self):
        import tempfile,json,time
        from pathlib import Path
        from wantgoo_chart import WantGooCharts,taipei_day
        with tempfile.TemporaryDirectory() as d:
            service=WantGooCharts(d,lambda data:None);p=service.base/'2330';p.mkdir(parents=True)
            data=dict(code='2330',period='5m',day='2000-01-01',error='timeout',fetched_at=time.time())
            (p/'result.json').write_text(json.dumps(data))
            self.assertIsNone(service.cached('2330'))
            data['day']=taipei_day();(p/'result.json').write_text(json.dumps(data))
            self.assertEqual(service.cached('2330')['error'],'timeout')
            data['error']='';(p/'result.json').write_text(json.dumps(data))
            self.assertIsNone(service.cached('2330'))

class PreviewTests(unittest.TestCase):
    def test_preview_scores_are_explicit_and_not_market_quotes(self):
        from candidate_charts import test_candidates
        rows=test_candidates()
        self.assertEqual([m['count'] for q,m in rows],[6,5])
        self.assertTrue(all(m['total']==6 and q.price is None for q,m in rows))
        rows[0][1]['count']=0
        self.assertEqual(test_candidates()[0][1]['count'],6)

class DayRangeTests(unittest.TestCase):
    def test_reject_multiday_and_missing_open(self):
        from wantgoo_chart import validate_day_range
        valid=dict(one_day=True,axis_labels=['10/20 09:00','10/20 13:00'])
        self.assertEqual(validate_day_range(valid,'2026-10-20 13:30'),'10/20')
        for delta in [dict(one_day=False),dict(axis_labels=['10/19 09:00','10/20 13:00']),dict(axis_labels=['10/20 10:00']),dict(axis_labels=[])]:
            with self.assertRaises(ValueError):validate_day_range(dict(valid,**delta),'2026-10-20 13:30')

class ChartRetryTests(unittest.TestCase):
    def test_explicit_retry_bypasses_recent_failure_and_deduplicates(self):
        import tempfile,time
        from unittest.mock import patch
        from wantgoo_chart import WantGooCharts
        with tempfile.TemporaryDirectory() as directory:
            events=[];service=WantGooCharts(directory,events.append)
            service.thread=object() # keep the worker queue observable
            failure=dict(code='2330',error='timeout',fetched_at=time.time())
            with patch.object(service,'cached',return_value=failure):
                service.request('2330')
                self.assertEqual(events,[failure]);self.assertTrue(service.queue.empty())
                service.request('2330',force=True)
                service.request('2330',force=True)
                self.assertEqual(service.queue.qsize(),1)
                self.assertTrue(events[-1]['loading'])
    def test_failure_cache_expires_after_thirty_seconds(self):
        import tempfile,time
        from unittest.mock import patch
        from wantgoo_chart import WantGooCharts
        with tempfile.TemporaryDirectory() as directory:
            service=WantGooCharts(directory,lambda data:None);service.thread=object()
            with patch.object(service,'cached',return_value=dict(error='timeout',fetched_at=time.time()-31)):
                service.request('2330')
                self.assertEqual(service.queue.qsize(),1)

class RetainedChartTests(unittest.TestCase):
    def test_weakened_candidate_keeps_current_score_and_peak(self):
        from candidate_charts import retained_chart_match,candidate_heading
        match=dict(count=3,total=6,label='待觀察',text='條件轉弱')
        result=retained_chart_match(match,5)
        self.assertEqual(result['count'],3)
        self.assertEqual(result['peak'],5)
        self.assertIn('目前 3/6',candidate_heading(result))
        self.assertIn('曾達 5/6',candidate_heading(result))
        self.assertNotIn('peak',match)
    def test_new_weak_stock_is_not_added(self):
        from candidate_charts import retained_chart_match
        self.assertIsNone(retained_chart_match(dict(count=4,total=6),0))
        self.assertIsNotNone(retained_chart_match(dict(count=5,total=6),0))
    def test_pending_recheck_retained_without_claiming_fresh_qualification(self):
        from candidate_charts import retained_chart_match,candidate_heading
        result=retained_chart_match(dict(count=0,total=6,text='等待重新複查'),6)
        self.assertIn('等待重新複查',candidate_heading(result))
        self.assertEqual(result['count'],0)

class IntradayFallbackTests(unittest.TestCase):
    def payload(self):
        import datetime as dt
        from intraday_chart import TZ
        t=int(dt.datetime(2026,1,2,9,tzinfo=TZ).timestamp())
        return {'chart':{'result':[{'meta':{'symbol':'2465.TW','currency':'TWD','dataGranularity':'5m'},'timestamp':[t,t+300,t+600],'indicators':{'quote':[{'open':[10,11,12],'high':[12,13,14],'low':[9,10,11],'close':[11,12,13],'volume':[100,200,300]}]}}]}}
    def test_valid_bars_and_reject_wrong_identity(self):
        from intraday_chart import parse_bars
        data=self.payload();self.assertEqual(len(parse_bars(data,'2465.TW')),3)
        for key,value in [('symbol','2330.TW'),('currency','JPY'),('dataGranularity','1d')]:
            bad=self.payload();bad['chart']['result'][0]['meta'][key]=value
            with self.assertRaises(ValueError):parse_bars(bad,'2465.TW')
    def test_missing_or_invalid_bar_is_not_fabricated(self):
        from intraday_chart import parse_bars
        data=self.payload();data['chart']['result'][0]['indicators']['quote'][0]['close'][1]=None
        rows=parse_bars(data,'2465.TW');self.assertEqual(len(rows),2);self.assertEqual(rows[1]['ts']-rows[0]['ts'],600)
    def test_rendered_fallback_is_readable_image(self):
        import tempfile
        from pathlib import Path
        from PIL import Image
        from intraday_chart import parse_bars,render_bars
        with tempfile.TemporaryDirectory() as d:
            stamp=render_bars(parse_bars(self.payload(),'2465.TW'),'2465',d)
            self.assertEqual(stamp,'2026-01-02 09:10')
            with Image.open(Path(d)/'full.png') as im:self.assertEqual(im.size,(1000,570))
    def test_worker_uses_fallback_on_browser_failure(self):
        import tempfile,threading
        from unittest.mock import patch
        from wantgoo_chart import WantGooCharts
        with tempfile.TemporaryDirectory() as d:
            events=[];done=threading.Event()
            def emit(value):
                events.append(value)
                if not value.get('loading'):done.set()
            service=WantGooCharts(d,emit)
            result=dict(code='2465',error='',source='Yahoo',directory=d)
            with patch('wantgoo_chart.subprocess.Popen',side_effect=OSError('no Edge')),patch('intraday_chart.fallback_chart',return_value=result):
                service.request('2465');self.assertTrue(done.wait(3));service.close();service.thread.join(2)
            self.assertTrue(events[0]['loading']);self.assertEqual(events[-1],result);self.assertFalse(service.pending)
    def test_full_queue_has_explicit_failure(self):
        import tempfile
        from wantgoo_chart import WantGooCharts
        with tempfile.TemporaryDirectory() as d:
            events=[];service=WantGooCharts(d,events.append);service.thread=object()
            for i in range(9):service.request(str(2000+i))
            self.assertIn('佇列已滿',events[-1]['error']);self.assertNotIn('2008',service.pending)


class InvestingTests(unittest.TestCase):
    def test_verify_symbol_interval_candles_and_loaded_values(self):
        from investing_chart import validate_chart
        valid=dict(heading='Test (2330)',legend='Test, Taiwan, 5, Taiwan',
                   selected=True,candles=True,values=['100','102','99','101'],canvas=True)
        validate_chart(valid,'2330','Test')
        for delta in (dict(heading='Test (5202)'),dict(legend='Test, Taiwan, D, Taiwan'),
                      dict(legend='Other, Taiwan, 5, Taiwan'),dict(selected=False),
                      dict(candles=False),dict(values=['n/a']*4),dict(canvas=False),
                      dict(values=['100','90','99','101'])):
            with self.assertRaises(ValueError):validate_chart(dict(valid,**delta),'2330','Test')
    def test_dedicated_cache_and_no_silent_provider_fallback(self):
        import tempfile
        from investing_chart import InvestingCharts,chart_url,safe_chart_url
        with tempfile.TemporaryDirectory() as folder:
            service=InvestingCharts(folder,lambda data:None)
            self.assertEqual(service.base.name,'investing-charts')
            self.assertFalse(service.fallback_enabled)
            self.assertEqual(service.capture_flag,'--investing-capture')
        self.assertTrue(safe_chart_url(chart_url('5202')))
        self.assertFalse(safe_chart_url('https://evil.test/equities/test-chart'))
        self.assertFalse(safe_chart_url('https://www.investing.com.evil.test/equities/test-chart'))
        with self.assertRaises(ValueError):chart_url('../1234')
