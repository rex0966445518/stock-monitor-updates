import unittest
from task_progress import status_bounds,status_top,duration_text

class StatusBoundsTests(unittest.TestCase):
    def test_top_strip_starts_right_of_combo(self):
        rect=(100,100,1700,1000)
        combo=[.01,.02,.15,.04];menu=[.01,.06,.3,.55]
        left,top,right,bottom=status_bounds(rect,combo,menu)
        self.assertGreater(left,100+.16*1600)
        self.assertGreaterEqual(top,100)
        self.assertLessEqual(right,1700)
        self.assertLessEqual(bottom,100+round(900*.049))
    def test_narrow_window_keeps_fixed_top_strip(self):
        result=status_bounds((0,0,520,500),[.02,.05,.5,.08],[.02,.13,.5,.4])
        self.assertGreater(result[0],.52*520)
        self.assertLessEqual(result[3],round(500*.049))
    def test_duration_does_not_wrap_at_one_hour(self):
        self.assertEqual(duration_text(3661),'01:01:01')
    def test_content_height_does_not_change_top_docking(self):
        bounds=status_bounds((0,0,1920,1040),[0,.02,.1,.04],[0,.06,.2,.6])
        for height in (24,48,180):self.assertEqual(status_top(bounds,height),bounds[1])

class CollapseTests(unittest.TestCase):
    def test_collapsing_changes_visibility_only_and_remembers_session_choice(self):
        from unittest.mock import Mock
        from types import SimpleNamespace
        from task_progress import TaskProgress
        p=TaskProgress.__new__(TaskProgress)
        p.alive=True;p.collapsed=False;p.root=SimpleNamespace()
        p.window=Mock();p.toggle_window=Mock();p.toggle_label=Mock();p.show_passive=Mock()
        p.detail=Mock();p.started=123;p.after_id='timer'
        p.toggle_collapsed()
        self.assertTrue(p.collapsed);self.assertTrue(p.root._scan_status_collapsed)
        p.window.withdraw.assert_called_once();p.detail.set.assert_not_called()
        self.assertEqual(p.started,123);self.assertEqual(p.after_id,'timer')
        p.toggle_collapsed()
        self.assertFalse(p.collapsed);p.show_passive.assert_called_once_with(p.window,True)
        self.assertEqual(p.toggle_label.configure.call_args.kwargs['text'],'◀')
