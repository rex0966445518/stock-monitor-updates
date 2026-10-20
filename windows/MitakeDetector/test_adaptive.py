import json
import tempfile
import unittest
from pathlib import Path
from adaptive import Experience
from autoswitch import RankingSwitcher, AutomationStopped
from test_autoswitch import FakeController, token


class AdaptiveTests(unittest.TestCase):
    def test_recovered_scroll_cap_is_retained_across_restarts(self):
        with tempfile.TemporaryDirectory() as folder:
            path=Path(folder)/'experience.json';p=Experience(path,[1])
            p.record_scroll('漲幅排行',2,True)
            self.assertEqual(Experience(path,[1]).scroll_cap('漲幅排行'),2)
            self.assertEqual(Experience(path,[2]).scroll_cap('漲幅排行'),6)
    def test_only_valid_reads_reduce_wait_and_failure_resets(self):
        p=Experience(layout=[1]);p.record('大單流入',2)
        self.assertEqual(p.wait('大單流入',15),15)
        p.observed('大單流入',True)
        self.assertAlmostEqual(p.wait('大單流入',15),9.75)
        for _ in range(20):p.observed('大單流入',True)
        self.assertEqual(p.wait('大單流入',15),1)
        p.observed('大單流入',False)
        self.assertEqual(p.wait('大單流入',15),15)

    def test_persistence_layout_invalidation_and_disabled(self):
        with tempfile.TemporaryDirectory() as d:
            path=Path(d)/'experience.json'
            p=Experience(path,[100,200]);p.record('大單流入',5);p.observed('大單流入',True)
            self.assertEqual(Experience(path,[100,200]).hint('大單流入'),5)
            self.assertIsNone(Experience(path,[101,200]).hint('大單流入'))
            p=Experience(path,[100,200],False)
            self.assertIsNone(p.hint('大單流入'));self.assertEqual(p.wait('大單流入',15),15)
            path.write_text('{bad',encoding='utf8')
            self.assertIsNone(Experience(path,[100,200]).hint('大單流入'))

    def test_untrusted_profile_clamps_or_rejects_values(self):
        with tempfile.TemporaryDirectory() as d:
            path=Path(d)/'experience.json';p=Experience(path,[1])
            path.write_text(json.dumps({'version':1,'layout':p.key,'ranks':{'x':{'page':999,'wins':4},'y':{'page':2,'wins':99999}}}))
            p=Experience(path,[1]);self.assertIsNone(p.hint('x'));self.assertEqual(p.wait('y',15),1)

    def make_switch(self,experience,missing_hint=False,wrong_label=False):
        c=FakeController();combo=[.1,.1,.2,.04];menu=[.1,.15,.2,.5];calls=[]
        def scroll(point,delta):
            c.actions.append(('scroll',delta));c.page=0 if delta>0 else c.page+1
        c.scroll=scroll
        def ocr(im):
            if im==tuple(combo):return [token('大單流出' if wrong_label else ('大單流入' if sum(a[0]=='click' for a in c.actions)>=2 else '漲幅排行'))]
            calls.append(c.page)
            return [token('大單流入' if c.page==(1 if missing_hint else 5) else '跌幅排行'+str(c.page))]
        return c,calls,RankingSwitcher(c,ocr,combo,menu,experience=experience)

    def test_learned_path_avoids_intermediate_menu_ocr(self):
        p=Experience();c,calls,s=self.make_switch(p);s.switch('大單流入')
        self.assertEqual(len(calls),6);self.assertEqual(p.hint('大單流入'),5)
        c,calls,s=self.make_switch(p);s.switch('大單流入')
        self.assertEqual(calls,[5])  # six menu OCR calls become one

    def test_changed_menu_falls_back_and_relearns(self):
        p=Experience();p.record('大單流入',5)
        c,calls,s=self.make_switch(p,missing_hint=True);s.switch('大單流入')
        self.assertEqual(calls,[5,0,1]);self.assertEqual(p.hint('大單流入'),1)

    def test_wrong_title_clears_experience_and_stops(self):
        p=Experience();p.record('大單流入',5)
        c,calls,s=self.make_switch(p,wrong_label=True)
        with self.assertRaises(AutomationStopped):s.switch('大單流入')
        self.assertIsNone(p.hint('大單流入'))

    def test_stop_interrupts_learned_path(self):
        p=Experience();p.record('大單流入',5)
        c,calls,s=self.make_switch(p);c.stopped=True
        with self.assertRaises(AutomationStopped):s.switch('大單流入')
        self.assertEqual(c.actions,[])

class OCRCacheTests(unittest.TestCase):
    def test_exact_pixels_only_and_bounded_cache(self):
        import sys,types
        import numpy
        from unittest.mock import patch
        from PIL import Image
        from auto_ui import AutoUI
        calls=[]
        class Engine:
            def __call__(self,image,**kwargs):
                calls.append(image.shape)
                return [([[48,48],[68,48],[68,68],[48,68]],'1234',.99)],None
        ui=AutoUI();ui.ocr=Engine();ui.ocr_learning=False
        with patch.dict(sys.modules,{'rapidocr_onnxruntime':types.SimpleNamespace(RapidOCR=Engine)}):
            image=Image.new('RGB',(20,20),'white')
            first=ui.read_auto_ocr(image)
            self.assertEqual(first,ui.read_auto_ocr(image.copy()))
            self.assertEqual(len(calls),1)
            self.assertEqual(first[0][0][0],[0,0])
            image.putpixel((0,0),(0,0,0));ui.read_auto_ocr(image)
            self.assertEqual(len(calls),2)
            for n in range(30):
                image.putpixel((0,0),(n,n,n));ui.read_auto_ocr(image)
            self.assertEqual(len(ui.ocr_image_cache),24)

if __name__=='__main__':unittest.main()

class PositionCacheTests(AdaptiveTests):
    def test_identical_menu_skips_ocr_but_still_checks_title(self):
        p=Experience();c,calls,s=self.make_switch(p)
        s.menu_signature=lambda im:[[200,500],'a'*64]
        s.switch('大單流入');self.assertEqual(len(calls),6)
        c,calls,s=self.make_switch(p);s.menu_signature=lambda im:[[200,500],'a'*64]
        s.switch('大單流入');self.assertEqual(calls,[])
        self.assertTrue(any(a[0]=='click' for a in c.actions))
    def test_changed_pixels_require_ocr(self):
        p=Experience();p.record('大單流入',5,(20,20),[[200,500],'a'*64])
        c,calls,s=self.make_switch(p);s.menu_signature=lambda im:[[200,500],'b'*64]
        s.switch('大單流入');self.assertEqual(calls,[5])
    def test_position_persists_and_rejects_out_of_bounds(self):
        with tempfile.TemporaryDirectory() as d:
            path=Path(d)/'experience.json';p=Experience(path,[1]);sig=[[200,500],'a'*64]
            p.record('大單流入',5,(20,30),sig)
            self.assertEqual(Experience(path,[1]).position('大單流入',sig),(20,30))
            self.assertIsNone(Experience(path,[2]).position('大單流入',sig))
            p.record('大單流入',5,(2000,30),sig)
            self.assertIsNone(Experience(path,[1]).position('大單流入',sig))
