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
    def test_small_wheel_steps_do_not_skip_target(self):
        class WheelController(FakeController):
            def scroll(self,point,delta):
                self.check();self.actions.append(('scroll',delta))
                self.page=0 if delta>0 else self.page+(abs(delta)//120)*3
        c=WheelController();combo=[.1,.1,.2,.04];menu=[.1,.15,.2,.5]
        names=['漲停鎖住','跌停鎖住','即將漲停','即將跌停','瞬間拉抬','瞬間殺盤','漲幅排行','跌幅排行','成量排行','成值排行','估量排行','預估量增','大單流入','大單流出','大單匯集','大單渙散']
        def ocr(im):
            if im==tuple(combo):return [token('預估量增' if sum(a[0]=='click' for a in c.actions)>=2 else '漲停鎖住')]
            return [token(n,20+i*30) for i,n in enumerate(names[c.page:c.page+10])]
        RankingSwitcher(c,ocr,combo,menu).switch('預估量增')
        self.assertTrue(any(a==('scroll',-120) for a in c.actions))
        self.assertFalse(any(a[0]=='scroll' and a[1]<-120 for a in c.actions))
    def test_retry_accepts_only_exact_label(self):
        c=FakeController();combo=[.1,.1,.2,.04];menu=[.1,.15,.2,.5]
        calls=[]
        def retry(im):calls.append(im);return [token('漲幅排行')]
        RankingSwitcher(c,lambda im:[token('旅幅排行')],combo,menu,ocr_retry=retry).switch('漲幅排行')
        self.assertTrue(calls);self.assertEqual(c.actions,[])
    def test_current_ranking_needs_no_input(self):
        c=FakeController()
        RankingSwitcher(c,lambda im:[token('漲幅排行')],[.1,.1,.2,.04],[.1,.15,.2,.5]).switch('漲幅排行')
        self.assertEqual(c.actions,[])
    def test_setup_selects_already_open_popup_without_toggle(self):
        c=FakeController()
        RankingSwitcher(c,lambda im:[token('漲幅排行')],[.1,.1,.2,.04],[.1,.15,.2,.5]).switch('漲幅排行',menu_open=True)
        self.assertEqual(sum(a[0]=='click' for a in c.actions),1)
    def test_split_label_same_row_only(self):
        a=([[10,10],[50,10],[50,30],[10,30]],'漲幅',.99)
        b=([[55,10],[95,10],[95,30],[55,30]],'排行',.99)
        self.assertIsNotNone(find_menu_item([a,b],'漲幅排行'))
        b=([[55,40],[95,40],[95,60],[55,60]],'排行',.99)
        self.assertIsNone(find_menu_item([a,b],'漲幅排行'))
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
        def ocr(im):return [token('大單流入')] if im==tuple(menu) or sum(a[0]=='click' for a in c.actions)>=2 else [token('跌幅排行')]
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
        def ocr(im):return [token('大單流入')] if (im==tuple(menu) and c.page) or sum(a[0]=='click' for a in c.actions)>=2 else [token('漲幅排行')]
        RankingSwitcher(c,ocr,combo,menu).switch('大單流入')
        self.assertTrue(any(a[0]=='scroll' and a[1]<0 for a in c.actions))
if __name__=='__main__':unittest.main()

class NeighborTests(unittest.TestCase):
    def test_neighbor_geometry(self):
        from autoswitch import find_buy_power_between_neighbors as find
        rows=[token('大單匯集',20),token('大單渙散',44),token('分時賣力',92)]
        self.assertEqual(find(rows),(55,68))
        self.assertIsNone(find(rows[1:]))
        self.assertIsNone(find(rows+[token('累計買力',68)]))
        self.assertIsNone(find([token('大單匯集',20),token('大單渙散',44),token('分時賣力',68)]))
        shifted=([[110,87],[200,87],[200,97],[110,97]],'分時賣力',.99)
        self.assertIsNone(find(rows[:2]+[shifted]))
    def test_neighbor_click_requires_verified_title(self):
        combo=[.1,.1,.2,.04];menu=[.1,.15,.2,.5]
        rows=[token('大單匯集',20),token('大單渙散',44),token('分時賣力',92)]
        for correct in (True,False):
            c=FakeController()
            def read(im):
                if im==tuple(menu):return rows
                return [token('分時買力' if correct and any(a[0]=='click' for a in c.actions) else '大單流入')]
            switch=RankingSwitcher(c,read,combo,menu)
            if correct:
                switch.switch('分時買力',menu_open=True)
                self.assertIn(('click',(255,288)),c.actions)
            else:
                with self.assertRaises(AutomationStopped):switch.switch('分時買力',menu_open=True)
    def test_zoom_coordinates_and_label(self):
        from PIL import Image
        c=FakeController();seen=[]
        def read(im):
            seen.append(im.size)
            return [([[20,30],[100,30],[100,50],[20,50]],'分時買力',.99)] if im.width==200 else []
        switch=RankingSwitcher(c,read,[.1,.1,.2,.04],[.1,.15,.2,.5])
        point=switch.menu_point(Image.new('RGB',(100,80)),[],'分時買力')
        self.assertEqual(point,(30,20))
        c.image=lambda roi:(Image.new('RGB',(100,80)),{})
        self.assertTrue(switch.label_matches('分時買力'))
        self.assertIn((200,160),seen)
