import json
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock,patch
from PIL import Image
from ocr_recovery import fresh_ocr,FreshMemory,tiled_tokens,tiled_numeric
from MitakeDetector import Quote
from auto_ui import AutoUI
from autoswitch import AutomationStopped

class RecoveryTests(unittest.TestCase):
    def test_fresh_flag_restored_even_on_error_and_nested(self):
        app=SimpleNamespace()
        with self.assertRaises(RuntimeError):
            with fresh_ocr(app):
                with fresh_ocr(app):self.assertTrue(app.force_fresh_ocr)
                self.assertTrue(app.force_fresh_ocr)
                raise RuntimeError('failed')
        self.assertFalse(app.force_fresh_ocr)
        memory=Mock();fresh=FreshMemory(memory)
        self.assertIsNone(fresh.lookup('old'));memory.lookup.assert_not_called()
        fresh.record('new',[],1,'fresh');memory.record.assert_called_once()

    def test_header_tiles_translate_and_deduplicate_seam(self):
        im=Image.new('RGB',(1200,100));origins=[]
        # Token center at x=630 is included in overlapping tiles but owned once.
        def read(crop):
            start=0 if crop.width==672 else 608
            if start==0:box=[[620,10],[640,10],[640,30],[620,30]]
            else:box=[[12,10],[32,10],[32,30],[12,30]]
            return [(box,'股號',.99)]
        result=tiled_tokens(im,read,status=origins.append)
        self.assertEqual(len(result),1);self.assertEqual(result[0][0][0],[620,10])
        self.assertEqual(origins[1],(608,0,1200,100))

    def test_numeric_bands_preserve_global_anchors_without_duplicate_rows(self):
        q1=Quote('2330','',100,2,1500,1,10);q2=Quote('2317','',200,3,2500,2,10)
        calls=[0]
        def scan(im,bounds,read,names,**kwargs):
            i=calls[0];calls[0]+=1
            kwargs['anchors_ready']([('2330',250 if i==0 else 26,20),('2317',280 if i==0 else 56,20)],{'2330':.99,'2317':.98})
            return [q1,q2],0
        with patch('fast_quotes.scan_numeric_batch',side_effect=scan):
            rows,bad,anchors,scores=tiled_numeric(Image.new('RGB',(600,500)),{},None,None)
        self.assertEqual([q.code for q in rows],['2330','2317'])
        self.assertEqual(anchors,[('2330',250,20),('2317',280,20)])

    def test_layout_runtime_error_retries_fresh_but_stop_propagates(self):
        app=AutoUI();app.read_auto_ocr=Mock();layout={'table':[0,.2,1,.8]}
        with patch('auto_detect.detect_layout',side_effect=[RuntimeError('OCR failed'),layout]) as detect:
            self.assertEqual(app.detect_layout_recovering(Image.new('RGB',(100,100))),layout)
            self.assertEqual(detect.call_count,2)
        self.assertFalse(app.force_fresh_ocr)
        with patch('auto_detect.detect_layout',side_effect=AutomationStopped('F1')) as detect:
            with self.assertRaises(AutomationStopped):app.detect_layout_recovering(Image.new('RGB',(100,100)))
            self.assertEqual(detect.call_count,1)

    def test_failure_capture_bounded_and_metadata_retains_capture_time(self):
        from scan_diagnostics import save_failed_capture
        with tempfile.TemporaryDirectory() as folder:
            im=Image.new('RGB',(30,20));im.info['captured_at']=123
            for i in range(7):path=save_failed_capture(folder,im,dict(segment=i))
            self.assertEqual(len(list(Path(folder).glob('*.png'))),5)
            self.assertEqual(len(list(Path(folder).glob('*.json'))),5)
            self.assertEqual(json.loads(Path(path).with_suffix('.json').read_text())['captured_at'],123)

    def test_actual_image_cache_bypassed_for_forced_read(self):
        import sys
        from collections import OrderedDict
        import hashlib
        im=Image.new('RGB',(40,20),'white')
        key=(im.size,hashlib.sha256(im.tobytes()).digest())
        app=AutoUI();app.ocr_image_cache=OrderedDict([(key,[([], 'cached',1)])]);app.get_ocr_learning=lambda:None
        app.ocr=Mock(return_value=([([[48,48],[68,48],[68,68],[48,68]],'fresh',.99)],None))
        with patch.dict(sys.modules,{'rapidocr_onnxruntime':SimpleNamespace(RapidOCR=Mock())}):
            self.assertEqual(app.read_auto_ocr(im)[0][1],'cached');app.ocr.assert_not_called()
            with fresh_ocr(app):self.assertEqual(app.read_auto_ocr(im)[0][1],'fresh')
        app.ocr.assert_called_once()

class WorkerRecoveryTests(unittest.TestCase):
    def test_worker_converts_ocr_error_to_retry_and_forces_fresh_second_read(self):
        from test_start_auto import UI,Controller,Switcher
        from MitakeDetector import DEFAULT
        cfg=dict(DEFAULT,auto_config=dict(title='TEST RANK',window_size=[800,600],combo=[0,0,.2,.1],menu=[0,.1,.3,.5],table=[0,.2,1,.8],rankings=['漲幅排行'],seconds=3,pages=1,full_table=True))
        app=UI(cfg);seen=[]
        def read(im,centers):
            seen.append(getattr(app,'force_fresh_ocr',False))
            if len(seen)==1:raise RuntimeError('simulated first-segment OCR failure')
            return [Quote('2330','TEST',100,2,1500,1,1)],0
        app.scan=read
        def table(controller,roi,reader,progress,**kwargs):
            im=Image.new('RGB',(100,100));im.info['captured_at']=10
            self.assertEqual(reader(im),([],1))
            return reader(im)
        with tempfile.TemporaryDirectory() as folder,patch('MitakeDetector.BASE',Path(folder)),patch('auto_ui.WindowsController',Controller),patch('auto_ui.RankingSwitcher',Switcher),patch('table_scan.scan_table',side_effect=table):
            app.start_auto();app.worker_thread.join(3)
            self.assertFalse(app.worker_thread.is_alive());self.assertEqual(seen,[False,True])
            self.assertFalse(app.force_fresh_ocr)
            self.assertEqual(len(list((Path(folder)/'scan-failures').glob('*.png'))),1)

