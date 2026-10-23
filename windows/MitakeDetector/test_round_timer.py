import unittest
from round_timer import RoundTimer
class TimerTests(unittest.TestCase):
    def test_resume_excludes_offline_hours(self):
        now=[10.0];timer=RoundTimer(clock=lambda:now[0]);now[0]=25
        saved=timer.elapsed();self.assertEqual(saved,15)
        now[0]=70000;resumed=RoundTimer(saved,clock=lambda:now[0]);now[0]+=5
        self.assertEqual(resumed.elapsed(),20)
    def test_new_round_and_legacy_have_no_old_wall_time(self):
        self.assertEqual(RoundTimer(clock=lambda:80000).elapsed(),0)
        for bad in (None,'bad',float('nan'),-5):
            self.assertEqual(RoundTimer(bad,clock=lambda:80000).elapsed(),0)

class OutlineServiceTests(unittest.TestCase):
    def test_fast_service_displays_then_clears_before_capture(self):
        from auto_ui import AutoUI
        from unittest.mock import Mock
        import threading
        app=AutoUI();app.root=Mock();app.generation=7;app.show_scan_outline=Mock()
        shown=threading.Event();region={'left':10,'top':20,'width':30,'height':40}
        app.outline_show_pending=(7,region,shown)
        app.poll_capture_clear()
        self.assertTrue(shown.is_set());app.show_scan_outline.assert_called_with(region)
        cleared=threading.Event();app.outline_clear_pending=(7,cleared)
        app.poll_capture_clear()
        self.assertTrue(cleared.is_set());app.show_scan_outline.assert_called_with(None)
    def test_full_column_focus_is_visible(self):
        from auto_ui import AutoUI
        from unittest.mock import Mock
        import threading,queue
        app=AutoUI();app.scan_capture_region=dict(left=10,top=20,width=100,height=200)
        app.scan_focus_context=(7,threading.Event());app.events=queue.Queue();app.highlight_scan=Mock()
        image=Mock(size=(100,200),width=100,height=200)
        app.focus_columns(image,{'code':(0,.2)},['code'],'read')
        self.assertEqual(app.highlight_scan.call_args.args[1],[dict(left=10,top=20,width=20,height=200)])
