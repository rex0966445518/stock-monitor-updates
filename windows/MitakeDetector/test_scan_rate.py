import unittest
from scan_rate import ScanRate,digit_count

class RateTests(unittest.TestCase):
    def test_digits_and_rolling_window(self):
        self.assertEqual(digit_count(['23.20','-9.95%','買力','１２３']),10)
        rate=ScanRate();rate.update(dict(digits=30,at=10.1));rate.update(dict(digits=40,at=10.7))
        self.assertTrue(rate.text(now=11).startswith('70 字／秒'))
        self.assertTrue(rate.text(now=11.2).startswith('40 字／秒'))
        self.assertTrue(rate.text(now=12).startswith('0 字／秒'))
    def test_stop_clears_digits(self):
        rate=ScanRate();rate.update(dict(digits=70,at=10));rate.stop()
        self.assertTrue(rate.text(now=10).startswith('0 字／秒'))

class HeaderRateTests(unittest.TestCase):
    def test_header_displays_digits_not_stock_rate(self):
        from types import SimpleNamespace
        from unittest.mock import Mock
        from task_progress import TaskProgress
        value=Mock();panel=SimpleNamespace(rate=Mock(),values={'rate':value})
        panel.rate.get.return_value='70 字／秒｜7.2 秒／段｜4.17 檔／秒'
        TaskProgress.refresh_rate(panel)
        value.set.assert_called_with('70 字/秒')
        panel.rate.get.return_value='0 字／秒｜7.2 秒／段｜4.17 檔／秒'
        TaskProgress.refresh_rate(panel)
        value.set.assert_called_with('0 字/秒')

    def test_round_seconds_updates_only_on_completed_round(self):
        from types import SimpleNamespace
        from unittest.mock import Mock
        from task_progress import TaskProgress
        values={key:Mock() for key in ('scanned','total','passes','round_seconds')}
        panel=SimpleNamespace(counts=Mock(),values=values)
        panel.counts.get.return_value='累計完成 2 遍｜完成一遍耗時 36.7 秒｜準備第 3 輪'
        TaskProgress.refresh_counts(panel)
        values['round_seconds'].set.assert_called_once_with('36.7 秒')
        panel.counts.get.return_value='已掃描 20 股｜累計完成 2 遍'
        TaskProgress.refresh_counts(panel)
        values['round_seconds'].set.assert_called_once_with('36.7 秒')
