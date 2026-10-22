import json,tempfile,unittest
from pathlib import Path
from unittest.mock import Mock
from PIL import Image
from autoswitch import RankingSwitcher,AutomationStopped,absolute_roi
from ranking_training import TeachingMemory,same_teaching_layout
from test_autoswitch import FakeController

class TeachingReplayTests(unittest.TestCase):
    def setup_switch(self,path):
        memory=TeachingMemory(path,'layout');memory.remember('累計買力',None,3,(40,80),(200,400))
        c=FakeController();c.image=lambda roi:(Image.new('RGB',(200,400),'blue'),absolute_roi(roi,c.rect()))
        s=RankingSwitcher(c,lambda im:[],[.1,.1,.2,.04],[.1,.15,.2,.5],teaching=memory)
        s.label_matches=Mock(return_value=False);s.verify_label=Mock(return_value=True)
        return s,c,memory
    def test_replays_manual_without_ocr_or_pixel_hash(self):
        with tempfile.TemporaryDirectory() as td:
            p=Path(td)/'memory.json';s,c,m=self.setup_switch(p)
            s.menu_point=Mock(side_effect=AssertionError('must use manual path'))
            s.switch('累計買力')
            self.assertEqual(c.actions.count(('scroll',-120)),3)
            self.assertIn(('click',(240,300)),c.actions)
            self.assertEqual(TeachingMemory(p,'layout').get('累計買力')['verification'],'passed')
    def test_failure_keeps_manual_candidate_and_marks_failed(self):
        with tempfile.TemporaryDirectory() as td:
            s,c,m=self.setup_switch(Path(td)/'memory.json')
            s.verify_label.return_value=False;s.menu_point=Mock(return_value=None)
            with self.assertRaises(AutomationStopped):s.switch('累計買力')
            self.assertEqual(m.get('累計買力')['verification'],'failed')
            self.assertEqual(s.verify_label.call_count,1)
    def test_current_title_does_not_certify_replay(self):
        with tempfile.TemporaryDirectory() as td:
            s,c,m=self.setup_switch(Path(td)/'memory.json');s.label_matches.return_value=True
            s.switch('累計買力');self.assertNotIn('verification',m.get('累計買力'))
    def test_table_change_does_not_invalidate_menu(self):
        a=dict(table=[1],menu=[2],combo=[3]);b=dict(a,table=[4])
        self.assertTrue(same_teaching_layout(json.dumps(a),json.dumps(b)))
        b['menu']=[9];self.assertFalse(same_teaching_layout(json.dumps(a),json.dumps(b)))
