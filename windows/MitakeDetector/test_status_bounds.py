import unittest
from task_progress import status_bounds,status_top,duration_text

class StatusBoundsTests(unittest.TestCase):
    def test_right_side_reserves_entire_popup(self):
        rect=(100,100,1700,1000)
        combo=[.01,.02,.15,.04];menu=[.01,.06,.3,.55]
        left,top,right,bottom=status_bounds(rect,combo,menu)
        self.assertGreater(left,100+.31*1600)
        self.assertEqual(right,1700)
    def test_narrow_window_places_status_below_popup(self):
        result=status_bounds((0,0,520,500),[.02,.05,.5,.08],[.02,.13,.5,.4])
        self.assertGreater(result[1],265)
    def test_duration_does_not_wrap_at_one_hour(self):
        self.assertEqual(duration_text(3661),'01:01:01')

    def test_bottom_dock_clears_top_header_at_multiple_heights(self):
        for height in (72,136,180):
            bounds=status_bounds((0,0,1920,1040),[0,.02,.1,.04],[0,.06,.2,.6])
            top=status_top(bounds,height)
            self.assertGreater(top,100)
            self.assertEqual(top+height,1032)
