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


def tokens():return [token('漲停鎖住',12),token('跌停鎖住',36),token('即將漲停',60)]


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
        c=C();s=RankingSwitcher(c,Mock(return_value=tokens()),[0,0,.2,.1],[0,.1,.3,.5],numbering=True)
        region=dict(left=100,top=100,width=220,height=192)
        point=s.numbers.locate('開盤強勢',region)
        self.assertIsNotNone(point);self.assertEqual(c.offset,21);self.assertEqual(s.ocr.call_count,1)
        c.offset=0;self.assertIsNotNone(s.numbers.locate('開盤強勢',region));self.assertEqual(s.ocr.call_count,1)
    def test_native_index_path_needs_no_menu_ocr(self):
        s=RankingSwitcher(FakeController(),Mock(),[0,0,.2,.1],[0,.1,.3,.5],numbering=True)
        with patch('menu_numbers.NativeMenu') as native:
            native.return_value.point.return_value=(50,30);native.return_value.rows.return_value=[(21,30)]
            self.assertEqual(s.numbers.locate('開盤強勢',{}),(50,30))
            native.return_value.point.assert_called_once_with(27);s.ocr.assert_not_called()
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
            else:s.switch('開盤強勢');self.assertEqual(e.number('開盤強勢'),27)

    def test_recorded_catalog_and_all_default_numbers(self):
        from autoswitch import RANKINGS
        expected="漲停鎖住 跌停鎖住 即將漲停 即將跌停 瞬間拉抬 瞬間殺盤 漲幅排行 跌幅排行 成量排行 成值排行 估量排行 預估量增 大單流入 大單流出 大單匯集 大單渙散 分時買力 分時賣力 累計買力 累計賣力 價漲動能 價跌動能 單量排行 量增排行 高價排行 低價排行 開盤強勢 開盤弱勢 開高走低 開低走高 週轉排行 大幅震盪 連續買單 連續賣單 買氣強大 賣壓沉重 現股當沖 注意股 處置股 異常推介 特殊異常 暫停交易 延後開盤 延後收盤".split()
        self.assertEqual(RANKINGS,expected)
        s=RankingSwitcher(FakeController(),Mock(),[0,0,.2,.1],[0,.1,.3,.5],numbering=True)
        for index,name in enumerate(expected,1):self.assertEqual(s.numbers.number(name),index)
        self.assertNotIn('量縮排行',RANKINGS)
        with self.assertRaises(ValueError):PixelMenu(menu(),[token('漲幅排行',12),token('跌幅排行',36),token('成量排行',60)])

    def test_catalog_change_discards_learned_positions_preserves_manual(self):
        from adaptive import Experience
        from autoswitch import RANKINGS
        with tempfile.TemporaryDirectory() as tmp:
            path=Path(tmp)/'experience.json';old=Experience(path,{'size':[100,200]})
            old.record_number('開盤強勢',21)
            fresh=Experience(path,{'size':[100,200],'menu_catalog':RANKINGS})
            self.assertIsNone(fresh.number('開盤強勢'))
            s=RankingSwitcher(FakeController(),Mock(),[0,0,.2,.1],[0,.1,.3,.5],experience=fresh,numbering=True,menu_numbers={'開盤強勢':28})
            self.assertEqual(s.numbers.number('開盤強勢'),28)
            self.assertEqual(s.numbers.number('低價排行'),26)

class PreviewTests(unittest.TestCase):
    def test_full_pixel_preview_covers_all_44_before_closing(self):
        from menu_numbers import preview_numbers
        class C(FakeController):
            def image(self,roi):return menu(min(self.page*3,36)),{}
        c=C();shown=[]
        count=preview_numbers(c,[],dict(left=10,top=20,width=100,height=40),dict(left=0,top=0,width=220,height=192),None,PixelMenu(menu(),tokens()),shown.append)
        self.assertEqual(count,44)
        self.assertEqual(set(n for payload in shown for n,_ in payload['rows']),set(range(1,45)))
        self.assertEqual(shown[-1]['preview_label'],'已預覽 44/44 項')
        self.assertEqual(c.actions[-1],('click',(60,40)))
        self.assertGreaterEqual(sum(a==('scroll',-120) for a in c.actions),15)

    def test_native_preview_uses_actual_count_not_catalog_length(self):
        from menu_numbers import preview_numbers
        c=FakeController();native=Mock();native.count=43
        native.rows.side_effect=lambda:[(n,12+i*24) for i,n in enumerate(range(min(c.page*3,35)+1,min(c.page*3,35)+9))]
        count=preview_numbers(c,[],dict(left=10,top=20,width=100,height=40),dict(left=0,top=0,width=220,height=192),native,None,Mock())
        self.assertEqual(count,43)

    def test_stuck_first_page_never_claims_complete_or_clicks(self):
        from menu_numbers import preview_numbers
        c=FakeController();native=Mock();native.count=44;native.rows.return_value=[(i,12*i) for i in range(1,11)]
        with self.assertRaisesRegex(ValueError,'10/44'):
            preview_numbers(c,[],{},dict(left=0,top=0,width=220,height=192),native,None,Mock())
        self.assertFalse(any(a[0]=='click' for a in c.actions))

    def test_preview_timeout_is_incomplete(self):
        from menu_numbers import preview_numbers
        c=FakeController();native=Mock();native.count=44;native.rows.return_value=[(1,12)]
        with self.assertRaisesRegex(ValueError,'逾時'):
            preview_numbers(c,[],{},dict(left=0,top=0,width=220,height=192),native,None,Mock(),clock=Mock(side_effect=[0,121]))
        self.assertEqual(c.actions,[])

    def test_closed_popup_or_f8_never_clicks(self):
        from menu_numbers import preview_numbers
        for stopped in (False,True):
            c=FakeController();c.stopped=stopped;native=Mock();native.rows.side_effect=ValueError('closed')
            with self.assertRaises((ValueError,AutomationStopped)):
                preview_numbers(c,[],{}, dict(left=0,top=0,width=220,height=192),native,None,Mock())
            self.assertEqual(c.actions,[])
    def test_clear_page_outside_target_range_skips_retry(self):
        s=RankingSwitcher(FakeController(),Mock(),[0,0,.2,.1],[0,.1,.3,.5],ocr_retry=Mock())
        s.enlarged_reads=Mock(return_value=[])
        self.assertIsNone(s.menu_point(menu(),tokens(),'開盤強勢'))
        s.ocr_retry.assert_not_called();s.enlarged_reads.assert_not_called()
    def test_missing_intervening_label_still_retries(self):
        s=RankingSwitcher(FakeController(),Mock(),[0,0,.2,.1],[0,.1,.3,.5],ocr_retry=Mock(return_value=[token('跌停鎖住',36)]))
        self.assertIsNotNone(s.menu_point(menu(),[token('漲停鎖住',12),token('即將漲停',60),token('即將跌停',84)],'跌停鎖住'))
        s.ocr_retry.assert_called_once()
    def test_unchanged_menu_pixels_not_repeatedly_recognized(self):
        class C(FakeController):
            def image(self,roi):return menu(),dict(left=100,top=100,width=220,height=192)
        c=C();reader=Mock(return_value=tokens());s=RankingSwitcher(c,reader,[0,0,.2,.1],[0,.1,.3,.5])
        s.label_matches=Mock(return_value=False)
        with self.assertRaises(AutomationStopped):s.switch('開盤強勢',menu_open=True)
        reader.assert_called_once()
        self.assertEqual(sum(a==('scroll',-120) for a in c.actions),2)

import queue
import unittest
from unittest.mock import Mock,patch
from auto_ui import AutoUI


class PreviewWindowTests(unittest.TestCase):
    def ui(self):
        ui=AutoUI()
        ui.config={'auto_config':{'title':'三竹熱門排行','combo':[0,0,.2,.1],'menu':[0,.1,.2,.5]}}
        ui.idle=Mock(return_value=True);ui.dependencies=Mock(return_value=True)
        ui.status=Mock();ui.events=queue.Queue();ui.generation=0
        return ui

    def test_preview_after_restart_without_handle_starts_worker(self):
        ui=self.ui()
        with patch('auto_ui.WindowsController.windows',return_value=[(42,'三竹熱門排行')]),patch('auto_ui.threading.Thread') as thread,patch('tkinter.messagebox.showerror') as error:
            ui.preview_menu_numbers()
        error.assert_not_called();thread.return_value.start.assert_called_once()
        self.assertEqual(ui.auto_hwnd,42)

    def test_stale_handle_replaced_not_reused(self):
        ui=self.ui();ui.auto_hwnd=11
        with patch('auto_ui.WindowsController.windows',return_value=[(11,'其他軟體'),(42,'三竹熱門排行')]):
            self.assertEqual(ui.resolve_auto_window(ui.config['auto_config']),42)

    def test_missing_or_ambiguous_target_does_not_start_worker(self):
        for windows in ([],[(1,'其他軟體')],[(2,'三竹熱門排行'),(3,'三竹熱門排行')]):
            with self.subTest(windows=windows):
                ui=self.ui()
                with patch('auto_ui.WindowsController.windows',return_value=windows),patch('auto_ui.threading.Thread') as thread,patch('tkinter.messagebox.showerror') as error:
                    ui.preview_menu_numbers()
                thread.assert_not_called();error.assert_called_once()
                self.assertIn('選擇視窗並儲存',error.call_args.args[1])
                self.assertNotIn('attribute',error.call_args.args[1])

    def test_explicit_live_selection_resolves_duplicate_titles(self):
        ui=self.ui();ui.auto_hwnd=3
        with patch('auto_ui.WindowsController.windows',return_value=[(2,'三竹熱門排行'),(3,'三竹熱門排行')]):
            self.assertEqual(ui.resolve_auto_window(ui.config['auto_config']),3)

class PersistentMenuTests(unittest.TestCase):
    def test_restart_uses_saved_geometry_without_menu_ocr(self):
        from adaptive import Experience
        class C(FakeController):
            def __init__(self):super().__init__();self.offset=0
            def image(self,roi):return menu(self.offset),dict(left=100,top=100,width=220,height=192)
            def scroll(self,point,delta):self.offset=max(0,self.offset+(3 if delta<0 else -100))
        with tempfile.TemporaryDirectory() as folder:
            path=Path(folder)/'experience.json';region=dict(left=100,top=100,width=220,height=192)
            for restart in (False,True):
                ocr=Mock(side_effect=AssertionError('Repeated menu OCR')) if restart else Mock(return_value=tokens())
                s=RankingSwitcher(C(),ocr,[0,0,.2,.1],[0,.1,.3,.5],experience=Experience(path,{'size':[900,700]}),numbering=True)
                self.assertIsNotNone(s.numbers.locate('累計買力',region))
                self.assertEqual(ocr.call_count,0 if restart else 1)

    def test_changed_or_corrupt_geometry_rejected_without_click(self):
        g=PixelMenu(menu(),tokens());saved=g.snapshot()
        self.assertIsNotNone(PixelMenu.restore(menu(highlight=2),saved))
        self.assertIsNone(PixelMenu.restore(menu(20),saved))
        self.assertIsNone(PixelMenu.restore(Image.new('RGB',(230,192)),saved))
        for bad in [dict(saved,pitch=0),dict(saved,pitch=float('nan')),dict(saved,initial=['bad']),dict(saved,first=-1)]:
            self.assertIsNone(PixelMenu.restore(menu(),bad))

class BuyStrengthTests(unittest.TestCase):
    def test_all_44_numbers_and_buy_strength_35_without_repeated_menu_ocr(self):
        from autoswitch import RANKINGS
        class C(FakeController):
            def __init__(self):super().__init__();self.offset=0
            def image(self,roi):return menu(self.offset,count=10),dict(left=100,top=100,width=220,height=240)
            def scroll(self,point,delta):self.offset=0 if delta>0 else min(34,self.offset+3)
        c=C();ocr=Mock(return_value=tokens());s=RankingSwitcher(c,ocr,[0,0,.2,.1],[0,.1,.3,.5],numbering=True)
        region=dict(left=100,top=100,width=220,height=240)
        for number,name in enumerate(RANKINGS,1):
            c.offset=0;point=s.numbers.locate(name,region)
            self.assertIsNotNone(point,name)
            self.assertEqual(c.offset+int(point[1]//24)+1,number,name)
        self.assertEqual(RANKINGS[34],'買氣強大');self.assertEqual(ocr.call_count,1)

    def test_large_wheel_jump_retries_numeric_small_steps_and_remembers(self):
        from adaptive import Experience
        class C(FakeController):
            def __init__(self):super().__init__();self.offset=0;self.steps=[]
            def image(self,roi):return menu(self.offset,count=10),dict(left=100,top=100,width=220,height=240)
            def scroll(self,point,delta):
                self.steps.append(delta)
                self.offset=0 if delta>0 else min(34,self.offset+(9 if delta==-120 else 3))
        with tempfile.TemporaryDirectory() as folder:
            path=Path(folder)/'e.json';region=dict(left=100,top=100,width=220,height=240)
            for restart in (False,True):
                c=C();ocr=Mock(side_effect=AssertionError('Repeated OCR')) if restart else Mock(return_value=tokens())
                s=RankingSwitcher(c,ocr,[0,0,.2,.1],[0,.1,.3,.5],experience=Experience(path,[1]),numbering=True)
                point=s.numbers.locate('買氣強大',region)
                self.assertEqual(c.offset+int(point[1]//24)+1,35)
                self.assertIn(-40,c.steps)
                self.assertEqual(ocr.call_count,0 if restart else 1)
                if restart:self.assertNotIn(-120,c.steps)

class CumulativeBuyIntegrationTests(unittest.TestCase):
    def test_numbers_19_27_35_click_and_simplified_titles_without_text_fallback(self):
        from adaptive import Experience
        from autoswitch import absolute_roi
        combo=[0,0,.22,.04];popup=[0,.04,.22,.3]
        class C(FakeController):
            def __init__(self):super().__init__();self.offset=0;self.selected=7
            def image(self,roi):
                region=absolute_roi(roi,self.rect())
                if roi==combo:
                    image=Image.new('RGB',(220,32),(self.selected,0,0));image.info['title']=self.selected
                else:image=menu(self.offset,count=10)
                return image,region
            def scroll(self,point,delta):self.check();self.offset=0 if delta>0 else min(34,self.offset+3)
            def click(self,point):
                super().click(point)
                top=absolute_roi(popup,self.rect())['top']
                if point[1]>=top:self.selected=self.offset+int((point[1]-top)//24)+1
        c=C();calls=[];status=[]
        def ocr(image):
            if 'title' in image.info:return [token({19:'累计买力',27:'开盘强势',35:'买气强大'}.get(image.info['title'],'漲幅排行'))]
            calls.append(c.offset)
            self.assertEqual(c.offset,0,'Must not search lower menu pages by Chinese OCR')
            return tokens()
        experience=Experience()
        for desired,name in [(19,'累計買力'),(27,'開盤強勢'),(35,'買氣強大')]*2:
            c.selected=7
            s=RankingSwitcher(c,ocr,combo,popup,experience=experience,numbering=True,status=status.append)
            s.menu_point=Mock(side_effect=AssertionError('Slow Chinese search used'))
            s.switch(name)
            self.assertEqual(c.selected,desired);self.assertEqual(experience.number(name),desired)
        self.assertEqual(calls,[0]);self.assertTrue(all(any(f'第 {n} 項｜標題核對通過' in line for line in status) for n in (19,27,35)))

class LearnedRouteTests(unittest.TestCase):
    def setup_navigation(self,path=None):
        from adaptive import Experience
        class C(FakeController):
            def __init__(self):super().__init__();self.offset=0;self.images=0
            def image(self,roi):
                self.images+=1
                return menu(self.offset),dict(left=100,top=100,width=220,height=192)
            def scroll(self,point,delta):
                self.check();self.actions.append(('scroll',delta))
                self.offset=max(0,min(36,self.offset+abs(delta)//40 if delta<0 else 0))
        c=C();e=Experience(path,{'size':[220,192]})
        s=RankingSwitcher(c,Mock(return_value=tokens()),[0,0,.2,.1],[0,.1,.3,.5],experience=e,numbering=True)
        return c,e,s,dict(left=100,top=100,width=220,height=192)
    def test_verified_route_survives_restart_and_uses_one_capture(self):
        with tempfile.TemporaryDirectory() as d:
            path=Path(d)/'experience.json';c,e,s,region=self.setup_navigation(path)
            point=s.numbers.locate('買氣強大',region);self.assertIsNotNone(point)
            self.assertNotIn('買氣強大',e.data.get('number_routes',{}))
            initial_images=c.images;s.numbers.verified('買氣強大')
            c2,e2,s2,region=self.setup_navigation(path)
            self.assertEqual(s2.numbers.locate('買氣強大',region),point)
            self.assertEqual(c2.images,1);self.assertGreater(initial_images,c2.images);s2.ocr.assert_not_called()
            self.assertEqual(sum(a[0]=='scroll' for a in c2.actions),1)
    def test_rejected_route_is_never_reused(self):
        c,e,s,region=self.setup_navigation()
        s.numbers.locate('開盤強勢',region);s.numbers.verified('開盤強勢')
        s.numbers.rejected('開盤強勢')
        self.assertNotIn('開盤強勢',e.data['number_routes']);self.assertIsNone(e.number('開盤強勢'))
    def test_changed_wheel_behavior_relearns_without_blind_click(self):
        c,e,s,region=self.setup_navigation()
        s.numbers.locate('買氣強大',region);s.numbers.verified('買氣強大');c.offset=0
        old=c.scroll
        def scroll(point,delta):old(point,-120 if delta< -120 else delta)
        c.scroll=scroll
        self.assertIsNotNone(s.numbers.locate('買氣強大',region))
        self.assertTrue(any(a==('scroll',7200) for a in c.actions))
        self.assertFalse(any(a[0]=='click' for a in c.actions))
    def test_all_catalog_routes_keep_correct_number(self):
        from autoswitch import RANKINGS
        c,e,s,region=self.setup_navigation()
        for i,name in enumerate(RANKINGS,1):
            c.offset=0;point=s.numbers.locate(name,region);self.assertIsNotNone(point,name)
            self.assertEqual(c.offset+round((point[1]-12)/24)+1,i)
            s.numbers.verified(name);c.offset=0
            self.assertEqual(s.numbers.locate(name,region),point)
        self.assertEqual(len(e.data['number_routes']),44)
    def test_corrupt_route_and_layout_change_are_ignored(self):
        from adaptive import Experience
        import json
        with tempfile.TemporaryDirectory() as d:
            path=Path(d)/'e.json';c,e,s,region=self.setup_navigation(path)
            s.numbers.locate('開盤強勢',region);s.numbers.verified('開盤強勢')
            self.assertNotIn('number_routes',Experience(path,{'size':[440,384]}).data)
            data=json.loads(path.read_text());data['number_routes']['開盤強勢']['steps']=999999
            path.write_text(json.dumps(data));self.assertEqual(Experience(path,{'size':[220,192]}).data['number_routes'],{})
