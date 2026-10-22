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
