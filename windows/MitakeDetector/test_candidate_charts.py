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
        self.assertIn('time=5',source_links('2330')[0])
        for code in ['23300','<script>','１２３４']:
            with self.assertRaises(ValueError):source_links(code)
