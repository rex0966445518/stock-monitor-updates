import threading
import unittest
from autoswitch import RankingSwitcher,AutomationStopped,absolute_roi,normalized,find_menu_item,validate_roi,F8Toggle


def token(text,y=20):return ([[10,y-5],[100,y-5],[100,y+5],[10,y+5]],text,.99)
class FakeController:
    def __init__(self):self.actions=[];self.stopped=False;self.page=0
    def check(self):
        if self.stopped:raise AutomationStopped('stopped')
    def rect(self):return (100,100,1100,900)
    def image(self,roi):self.check();return tuple(roi),absolute_roi(roi,self.rect())
    def click(self,point):self.check();self.actions.append(('click',point))
    def scroll(self,point,delta):self.check();self.actions.append(('scroll',delta));self.page+=int(delta<0)
    def pause(self,*args,**kwargs):self.check()

class AutoTests(unittest.TestCase):
    def test_f8_start_release_and_hold(self):
        key=F8Toggle()
        self.assertIsNone(key.update(True,False))
        for _ in range(20):self.assertIsNone(key.update(True,False))
        self.assertEqual(key.update(False,False),'start')
        self.assertIsNone(key.update(False,False))
    def test_f8_stop_cannot_restart_on_release(self):
        key=F8Toggle()
        self.assertEqual(key.update(True,True),'stop')
        self.assertIsNone(key.update(True,False))
        self.assertIsNone(key.update(False,False))
        self.assertIsNone(key.update(True,False))
        self.assertEqual(key.update(False,False),'start')
    def test_f8_busy_during_release(self):
        key=F8Toggle();key.update(True,False)
        self.assertIsNone(key.update(False,True))
    def test_translation(self):self.assertEqual(normalized('漲幅排行 ▼'),normalized('涨幅排行'))
    def test_exact_match(self):
        self.assertIsNone(find_menu_item([token('大單流入量')],'大單流入'))
        self.assertIsNotNone(find_menu_item([token('大单流入')],'大單流入'))
    def test_roi_motion(self):
        r=[.1,.2,.3,.4]
        self.assertEqual(absolute_roi(r,(100,100,1100,900)),dict(left=200,top=260,width=300,height=320))
        for bad in [None,[0,0,2,1],[-.1,0,.3,.3],[0,0,0,1]]:
            with self.assertRaises(ValueError):validate_roi(bad)
    def test_switch_and_label_verification(self):
        c=FakeController();combo=[.1,.1,.2,.04];menu=[.1,.15,.2,.5]
        def ocr(im):return [token('大單流入')] if im==tuple(menu) or im==tuple(combo) else []
        RankingSwitcher(c,ocr,combo,menu).switch('大單流入')
        self.assertEqual(sum(a[0]=='click' for a in c.actions),2)
    def test_wrong_label_stops(self):
        c=FakeController();combo=[.1,.1,.2,.04];menu=[.1,.15,.2,.5]
        def ocr(im):return [token('大單流入')] if im==tuple(menu) else [token('大單流出')]
        with self.assertRaises(AutomationStopped):RankingSwitcher(c,ocr,combo,menu).switch('大單流入')
    def test_missing_rank_stops_without_guessing(self):
        c=FakeController()
        with self.assertRaises(AutomationStopped):RankingSwitcher(c,lambda im:[token('跌幅排行')],[.1,.1,.2,.04],[.1,.15,.2,.5]).switch('大單流入')
        self.assertEqual(sum(a[0]=='click' for a in c.actions),1)
    def test_stop_blocks_input(self):
        c=FakeController();c.stopped=True
        with self.assertRaises(AutomationStopped):RankingSwitcher(c,lambda im:[],[.1,.1,.2,.04],[.1,.15,.2,.5]).switch('漲幅排行')
        self.assertEqual(c.actions,[])
    def test_scroll_finds_later_page(self):
        c=FakeController();combo=[.1,.1,.2,.04];menu=[.1,.15,.2,.5]
        def ocr(im):return [token('大單流入')] if c.page or im==tuple(combo) else [token('漲幅排行')]
        RankingSwitcher(c,ocr,combo,menu).switch('大單流入')
        self.assertTrue(any(a[0]=='scroll' and a[1]<0 for a in c.actions))
if __name__=='__main__':unittest.main()
