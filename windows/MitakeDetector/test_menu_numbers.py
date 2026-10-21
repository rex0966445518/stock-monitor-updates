import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock,patch
from PIL import Image,ImageDraw
from menu_numbers import PixelMenu,NumberNavigator,validate_numbers
from autoswitch import RankingSwitcher,AutomationStopped
from test_autoswitch import FakeController,token


def menu(start=0,count=8,highlight=-1):
    import random
    image=Image.new('RGB',(220,count*24),'#172534');draw=ImageDraw.Draw(image)
    for slot in range(count):
        index=start+slot;rng=random.Random(index+61);y=slot*24
        bg,fg=('#eeeeee','#111111') if slot==highlight else ('#172534','#eeeeee')
        draw.rectangle((0,y,219,y+23),fill=bg)
        for col in range(7):
            x=20+col*21
            for a in range(4):
                if rng.randrange(2):draw.line((x+a*4,y+5,x+a*4,y+18),fill=fg,width=2)
                if rng.randrange(2):draw.line((x,y+5+a*4,x+14,y+5+a*4),fill=fg,width=2)
    return image


def tokens():return [token('漲幅排行',12),token('跌幅排行',36),token('成量排行',60)]


class NumberTests(unittest.TestCase):
    def test_manual_numbers_blank_range_duplicates_and_persistence(self):
        self.assertEqual(validate_numbers({'開盤強勢':'21','低價排行':''}),{'開盤強勢':21})
        for value in [0,-1,501,True,'1.5','abc']:
            with self.assertRaises((ValueError,TypeError)):validate_numbers({'開盤強勢':value})
        with self.assertRaises(ValueError):validate_numbers({'開盤強勢':20,'低價排行':20})
        from adaptive import Experience
        with tempfile.TemporaryDirectory() as tmp:
            path=Path(tmp)/'experience.json';e=Experience(path,{'size':[100,200]})
            e.record_number('開盤強勢',21);self.assertEqual(Experience(path,{'size':[100,200]}).number('開盤強勢'),21)
            self.assertIsNone(Experience(path,{'size':[200,200]}).number('開盤強勢'))
            e.forget_number('開盤強勢');self.assertIsNone(Experience(path,{'size':[100,200]}).number('開盤強勢'))
    def test_pixel_scroll_counts_actual_rows_in_both_directions(self):
        g=PixelMenu(menu(),tokens());self.assertEqual(g.rows()[0],(1,12))
        self.assertTrue(g.update(menu(3)));self.assertEqual(g.rows()[0],(4,12))
        self.assertEqual(g.point(8),(99,108))
        self.assertTrue(g.update(menu(6,highlight=2)));self.assertEqual(g.offset,6)
        self.assertTrue(g.update(menu(4)));self.assertEqual(g.offset,4)
        self.assertTrue(g.reset(menu(highlight=1)));self.assertEqual(g.offset,0)
    def test_unmatched_jump_resize_and_bad_first_page_do_not_guess(self):
        g=PixelMenu(menu(),tokens());self.assertFalse(g.update(menu(30)))
        self.assertEqual(g.offset,0);self.assertFalse(g.reset(Image.new('RGB',(230,192))))
        with self.assertRaises(ValueError):PixelMenu(menu(),[token('漲幅排行',12)])
    def test_navigator_ocr_once_then_scrolls_by_pixels(self):
        class C(FakeController):
            def __init__(self):super().__init__();self.offset=0
            def image(self,roi):return menu(self.offset),dict(left=100,top=100,width=220,height=192)
            def scroll(self,point,delta):self.offset=max(0,self.offset+(3 if delta<0 else -100))
        c=C();s=RankingSwitcher(c,Mock(return_value=tokens()),[0,0,.2,.1],[0,.1,.3,.5],numbering=True,menu_numbers={'開盤強勢':21})
        region=dict(left=100,top=100,width=220,height=192)
        point=s.numbers.locate('開盤強勢',region)
        self.assertIsNotNone(point);self.assertEqual(c.offset,15);self.assertEqual(s.ocr.call_count,1)
        c.offset=0;self.assertIsNotNone(s.numbers.locate('開盤強勢',region));self.assertEqual(s.ocr.call_count,1)
    def test_native_index_path_needs_no_menu_ocr(self):
        s=RankingSwitcher(FakeController(),Mock(),[0,0,.2,.1],[0,.1,.3,.5],numbering=True)
        with patch('menu_numbers.NativeMenu') as native:
            native.return_value.point.return_value=(50,30);native.return_value.rows.return_value=[(21,30)]
            self.assertEqual(s.numbers.locate('開盤強勢',{}),(50,30))
            native.return_value.point.assert_called_once_with(21);s.ocr.assert_not_called()
    def test_wrong_manual_number_falls_back_and_never_records_success(self):
        from adaptive import Experience
        c=FakeController();e=Experience()
        s=RankingSwitcher(c,lambda im:[token('開盤強勢')],[.1,.1,.2,.04],[.1,.15,.2,.5],experience=e,numbering=True,menu_numbers={'開盤強勢':20})
        s.label_matches=Mock(return_value=False);s.verify_label=Mock(side_effect=[False,True])
        s.numbers.locate=Mock(return_value=(20,30));s.numbers.show=Mock()
        s.switch('開盤強勢');self.assertIn('開盤強勢',s.numbers.disabled)
        self.assertIsNone(e.number('開盤強勢'));s.numbers.locate.assert_called_once()
        self.assertEqual(s.verify_label.call_count,2);s.numbers.show.assert_called_with(None)
    def test_verified_number_is_saved_but_stop_never_learns(self):
        from adaptive import Experience
        for stop in [False,True]:
            e=Experience();c=FakeController();s=RankingSwitcher(c,Mock(),[.1,.1,.2,.04],[.1,.15,.2,.5],experience=e,numbering=True)
            s.label_matches=Mock(return_value=False);s.numbers.locate=Mock(return_value=(20,30))
            s.verify_label=Mock(side_effect=AutomationStopped('F8') if stop else None,return_value=True)
            if stop:
                with self.assertRaises(AutomationStopped):s.switch('開盤強勢')
                self.assertIsNone(e.number('開盤強勢'))
            else:s.switch('開盤強勢');self.assertEqual(e.number('開盤強勢'),21)
