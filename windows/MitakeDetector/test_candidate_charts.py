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
        self.assertEqual(source_links('2330')[0],'https://www.wantgoo.com/stock/2330/technical-chart')
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
