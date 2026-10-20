import copy
import queue
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock,patch
from PIL import Image,ImageDraw
from auto_detect import _layout_from_tokens,combo_frame,cached_layout
from auto_ui import AutoUI
from autoswitch import AutomationStopped
from ocr_recovery import zoom_tokens

LABELS=['股號','股名','成交價','漲幅%','成交量','週轉率','最高']

def headers(width,height,scale=1,y=None):
    y=round(height*.3) if y is None else y
    def token(text,x,top):
        w=len(text)*16*scale;h=18*scale
        return ([[x,top],[x+w,top],[x+w,top+h],[x,top+h]],text,.99)
    tokens=[token(text,width*(.02+i*.14),y) for i,text in enumerate(LABELS)]
    tokens.append(token('漲幅排行',16*scale,34*scale))
    im=Image.new('RGB',(width,height),'black');d=ImageDraw.Draw(im)
    d.rectangle((6*scale,25*scale,160*scale,65*scale),outline=(100,105,110),width=max(1,round(scale)))
    return im,tokens

class AdaptiveLayoutTests(unittest.TestCase):
    def test_complete_headers_same_row_at_multiple_resolutions_and_font_sizes(self):
        for w,h,scale in [(1024,768,.75),(1366,768,1),(1920,1080,1.25),(2560,1440,1.5),(3840,2160,2),(1920,1080,3)]:
            with self.subTest(size=(w,h),scale=scale):
                im,tokens=headers(w,h,scale)
                layout=_layout_from_tokens(im,tokens,True)
                self.assertEqual(len(layout['column_bounds']),6)
                self.assertGreater(layout['table'][1],.3)
                for field,(l,r) in layout['column_bounds'].items():self.assertTrue(0<=l<r<=1)
                self.assertLess(layout['column_bounds']['turnover'][1],.89) # extra column excluded
                self.assertLess(abs(layout['combo'][1]*h-25*scale),5)

    def test_missing_duplicate_or_different_row_headers_never_guessed(self):
        im,tokens=headers(1366,768)
        for bad in [tokens[1:],tokens+[tokens[2]],tokens[:2]+[( [[x,y+100] for x,y in tokens[2][0]],tokens[2][1],.99)]+tokens[3:]]:
            with self.assertRaises(ValueError):_layout_from_tokens(im,bad,True)

    def test_normalized_zoom_coordinates(self):
        result=zoom_tokens(Image.new('RGB',(100,50)),lambda im:[([[20,30],[60,30],[60,50],[20,50]],'股號',.99)])
        self.assertEqual(result[0][0][0],[10,15]);self.assertEqual(result[0][0][2],[30,25])

    def test_new_geometry_saved_in_header_cache_and_old_size_not_reused(self):
        im,tokens=headers(1366,768);layout=_layout_from_tokens(im,tokens,True)
        with tempfile.TemporaryDirectory() as folder:
            detect=Mock(return_value=layout);path=Path(folder)/'header.json'
            self.assertFalse(cached_layout(im,None,path,None,None,detector=detect)[1])
            self.assertTrue(cached_layout(im,None,path,layout['column_bounds'],layout['table'],detector=detect)[1])
            self.assertFalse(cached_layout(Image.new('RGB',(1920,1080)),None,path,layout['column_bounds'],layout['table'],detector=detect)[1])
            self.assertEqual(detect.call_count,2)

    def test_resize_locates_before_clicking_and_preserves_user_options(self):
        im,tokens=headers(1366,768);layout=_layout_from_tokens(im,tokens,True)
        app=AutoUI();app.events=queue.Queue();app.read_auto_ocr=Mock();app.read_menu_ocr_retry=Mock()
        auto=dict(title='TEST',window_size=[1920,1080],combo=[0,0,.1,.1],menu=[0,.1,.1,.4],table=[0,.2,1,.8],rankings=['漲幅排行'],seconds=9,pages=3,full_table=True)
        cfg=dict(min_price=100,column_bounds={},auto_config=auto)
        c=Mock();c.initial_rect=(-1366,0,0,768);c.rect.return_value=c.initial_rect;c.image.return_value=(im,None)
        app.detect_layout_recovering=Mock(return_value=layout)
        with tempfile.TemporaryDirectory() as folder,patch('MitakeDetector.BASE',Path(folder)),patch('auto_detect.detect_menu',return_value=[.01,.1,.2,.7]),patch('auto_ui.RankingSwitcher') as switch:
            self.assertTrue(app.adapt_scan_layout(c,auto,cfg,1,'漲幅排行'))
            switch.return_value.switch.assert_called_once_with('漲幅排行',menu_open=True)
        self.assertEqual(auto['window_size'],[1366,768]);self.assertEqual(auto['seconds'],9)
        self.assertEqual(auto['pages'],3);self.assertEqual(cfg['min_price'],100)
        self.assertLess(c.click.call_args.args[0][0],0) # correct monitor origin
        self.assertEqual(cfg['column_bounds'],layout['column_bounds'])

    def test_failed_new_header_never_uses_old_click_position(self):
        app=AutoUI();app.events=queue.Queue();app.read_auto_ocr=Mock();app.detect_layout_recovering=Mock(side_effect=ValueError('missing header'))
        auto=dict(window_size=[1920,1080]);original=copy.deepcopy(auto)
        c=Mock();c.initial_rect=(0,0,1366,768);c.image.return_value=(Image.new('RGB',(1366,768)),None)
        with tempfile.TemporaryDirectory() as folder,patch('MitakeDetector.BASE',Path(folder)):
            with self.assertRaises(ValueError):app.adapt_scan_layout(c,auto,{},1,'漲幅排行')
        c.click.assert_not_called();self.assertEqual(auto,original)

    def test_rebase_keeps_quotes_rejected_count_and_does_not_mutate_old_cursor(self):
        from resume_scan import rebase_viewport_cursor
        old=dict(segment=90,offset=155,codes=['2330'],quotes=[dict(code='2330',price=100)],rejected=5)
        new=rebase_viewport_cursor(old)
        self.assertEqual(new['quotes'],old['quotes']);self.assertEqual(new['rejected'],5)
        self.assertEqual((new['offset'],new['segment'],new['codes']),(0,0,[]))
        self.assertEqual(old['offset'],155)

class ResizedWorkerTests(unittest.TestCase):
    def test_resized_worker_recalibrates_and_rebases_checkpoint_without_losing_quotes(self):
        from test_start_auto import UI,Controller,Switcher
        from MitakeDetector import DEFAULT,Quote
        from resume_scan import ScanCheckpoint,scan_context
        from dataclasses import asdict
        class Resized(Controller):
            def __init__(self,*args):super().__init__(*args);self.initial_rect=(0,0,1366,768)
            def image(self,roi):return Image.new('RGB',(1366,768)),None
            def click(self,point):pass
            def pause(self,seconds):
                if seconds==.6:return
                super().pause(seconds)
        class MenuSwitcher(Switcher):
            def switch(self,*args,**kwargs):pass
        im,tokens=headers(1366,768);layout=_layout_from_tokens(im,tokens,True)
        cfg=dict(DEFAULT,auto_config=dict(title='TEST RANK',window_size=[1920,1080],combo=[0,0,.2,.1],menu=[0,.1,.3,.5],table=[0,.2,1,.8],rankings=['漲幅排行'],seconds=3,pages=1,full_table=True,discovered=1),column_bounds={})
        q=Quote('2330','TEST',100,2,1500,1,1)
        table=dict(segment=22,codes=['2330'],quotes=[asdict(q)],offset=120,rejected=3)
        app=UI(cfg);app.detect_layout_recovering=Mock(return_value=layout)
        with tempfile.TemporaryDirectory() as folder:
            path=Path(folder)/'scan-checkpoint.json'
            ScanCheckpoint(path).update(scan_context(cfg),dict(cycle=7,rank=0,table=table))
            with patch('MitakeDetector.BASE',Path(folder)),patch('auto_ui.WindowsController',Resized),patch('auto_ui.RankingSwitcher',MenuSwitcher),patch('auto_detect.detect_menu',return_value=[0,.1,.2,.6]),patch('table_scan.scan_table',return_value=([q],0)) as scan:
                app.start_auto();app.worker_thread.join(3)
            self.assertFalse(app.worker_thread.is_alive());scan.assert_called_once()
            resumed=scan.call_args.kwargs['resume']
            self.assertEqual(resumed['quotes'],table['quotes']);self.assertEqual(resumed['offset'],0)
            data=ScanCheckpoint(path).data
            self.assertEqual(data['context']['auto']['window_size'],[1366,768])
            self.assertEqual(data['cursor']['cycle'],8)

class RelayoutScrollTests(unittest.TestCase):
    def test_new_geometry_revisits_top_while_retaining_previous_rows(self):
        from test_table_scan import TableController
        from table_scan import scan_table
        from resume_scan import rebase_viewport_cursor
        from MitakeDetector import Quote
        from dataclasses import asdict
        c=TableController();c.position=9
        old=Quote('9999','OLD',100,2,1500,1,1)
        cursor=rebase_viewport_cursor(dict(segment=2,codes=['9999'],quotes=[asdict(old)],offset=6))
        read=lambda n:([Quote(str(1000+i),'TEST',100,2,1500,1,n+1) for i in range(n,n+6)],0)
        rows,bad=scan_table(c,[0,0,1,1],read,lambda *s:None,resume=cursor)
        self.assertTrue(any(delta>10000 for delta in c.scrolls))
        self.assertIn('9999',{q.code for q in rows});self.assertIn('1000',{q.code for q in rows})

class HeaderFragmentTests(unittest.TestCase):
    def test_split_labels_at_small_and_large_fonts(self):
        from auto_detect import merge_label_fragments
        for scale in (.65,1,1.5,2.5):
            im,tokens=headers(2560,1440,scale,y=450)
            fragmented=[]
            for points,text,score in tokens:
                if text=='漲幅排行':fragmented.append((points,text,score));continue
                l,t=points[0];step=16*scale
                for i,ch in enumerate(text):
                    x=l+i*step
                    fragmented.append(([[x,t],[x+step-1,t],[x+step-1,t+18*scale],[x,t+18*scale]],ch,score))
            with self.subTest(scale=scale):
                layout=_layout_from_tokens(im,fragmented,True)
                self.assertEqual(len(layout['column_bounds']),6)
                self.assertTrue(any(t[1]=='成交量' for t in merge_label_fragments(fragmented)))

    def test_never_join_separate_rows_or_distant_columns(self):
        from auto_detect import merge_label_fragments
        def t(text,x,y):return ([[x,y],[x+12,y],[x+12,y+18],[x,y+18]],text,.99)
        for pieces in ([t('成交',0,0),t('量',100,0)], [t('成交',0,0),t('量',13,30)]):
            self.assertNotIn('成交量',[v[1] for v in merge_label_fragments(pieces)])

    def test_low_headers_search_beyond_old_55_percent(self):
        from auto_detect import detect_layout
        im,tokens=headers(1366,768,y=500)
        def read(crop):return [t for t in tokens if max(p[1] for p in t[0])<=crop.height]
        layout=detect_layout(im,read)
        self.assertGreater(layout['table'][1],.65)

    def test_single_header_read_without_per_label_ocr(self):
        from auto_detect import detect_layout
        im,tokens=headers(1366,768,y=90)
        read=Mock(return_value=tokens)
        layout=detect_layout(im,read)
        self.assertEqual(len(layout['column_bounds']),6)
        self.assertEqual(read.call_count,1)

    def test_zoom_scale_maps_coordinates_exactly(self):
        for scale in (.75,3):
            result=zoom_tokens(Image.new('RGB',(100,50)),lambda im:[([[10*scale,5*scale],[20*scale,5*scale],[20*scale,15*scale],[10*scale,15*scale]],'股號',.99)],scale)
            self.assertEqual(result[0][0][0],[10,5])
            self.assertEqual(result[0][0][2],[20,15])

    def test_recovery_reaches_small_font_scale_and_keeps_stop_exception(self):
        app=AutoUI();app.read_auto_ocr=Mock(return_value=[])
        result={'column_bounds':{}}
        with patch('auto_detect.detect_layout',side_effect=[ValueError('native'),ValueError('tile'),ValueError('2x'),result]) as detect:
            self.assertEqual(app.detect_layout_recovering(Image.new('RGB',(100,50))),result)
            self.assertEqual(detect.call_count,4)
        with patch('auto_detect.detect_layout',side_effect=AutomationStopped('stop')):
            with self.assertRaises(AutomationStopped):app.detect_layout_recovering(Image.new('RGB',(100,50)))
