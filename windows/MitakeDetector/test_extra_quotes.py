import unittest
from collections import OrderedDict
from unittest.mock import patch
from PIL import Image
from MitakeDetector import FIELDS, EXTRA_FIELDS, parse_ocr, Quote
from auto_detect import _layout_from_tokens
from fast_quotes import StockNames, scan_numeric_batch
from auto_ui import AutoUI
from test_auto_detect import token

class ExtraQuoteTests(unittest.TestCase):
    def setUp(self):
        self.fields=FIELDS+EXTRA_FIELDS
        self.bounds={f:[i/9,(i+1)/9] for i,f in enumerate(self.fields)}
        self.values=['2330','測試','101','4','2070','2','100.2','99','102']
    def parse(self,values=None,confidence=.99,duplicate=False):
        toks=[(b,t,confidence if i>=6 else c) for i,(b,t,c) in enumerate(token(v,40+i*100,20,20) for i,v in enumerate(values or self.values))]
        if duplicate:toks.append(token('100.5',640,20,20))
        return parse_ocr(toks,900,[(50+i*100)/9 for i in range(9)],10,fields=self.fields)
    def test_optional_values_and_legacy(self):
        q=self.parse()[0][0];self.assertEqual((q.average,q.open_price,q.day_high),(100.2,99,102))
        self.assertIsNone(Quote('2330','',100,2,1000).average)
    def test_low_confidence_missing_duplicate_or_inconsistent_not_pass(self):
        self.assertIsNone(self.parse(confidence=.8)[0][0].average)
        self.assertIsNone(self.parse(duplicate=True)[0][0].average)
        values=self.values.copy();values[6]='--'
        self.assertIsNone(self.parse(values)[0][0].average)
        values=self.values.copy();values[8]='100'
        q=self.parse(values)[0][0];self.assertIsNone(q.day_high);self.assertIsNone(q.average)
    def test_headers_with_unrelated_columns(self):
        labels=['名稱','股號','成交','漲幅','單量','成交量','週轉率','最高','最低','開盤','參考價','均價','漲停價']
        tokens=[token(t,i*95,40,60) for i,t in enumerate(labels)]
        layout=_layout_from_tokens(Image.new('RGB',(1300,400)),tokens,False)
        b=layout['column_bounds'];self.assertEqual(set(b),set(self.fields))
        for field,index in [('day_high',7),('open_price',9),('average',11)]:
            self.assertLess(b[field][0],(index*95+30)/1300);self.assertGreater(b[field][1],(index*95+30)/1300)
    def test_missing_time_header_does_not_expand_average_into_time(self):
        labels=['名稱','股號','成交','漲幅','單量','成交量','週轉率','最高','最低','開盤','參考價','均價','時間','日期']
        image=Image.new('RGB',(1450,400))
        for omitted in (set(),{'時間'},{'時間','日期'}):
            tokens=[token(t,i*100,40,60) for i,t in enumerate(labels) if t not in omitted]
            layout=_layout_from_tokens(image,tokens,False)
            left,right=layout['column_bounds']['average']
            self.assertLess(left*1450,1130)
            self.assertGreater(right*1450,1160)
            self.assertLessEqual(right*1450,1180)
        from auto_detect import optional_column_edges
        self.assertEqual(optional_column_edges('average',[(1000,1060),(1100,1160),(1300,1360)],1,1080,1230),(1080,1180))

    def test_duplicate_optional_header_is_not_guessed(self):
        labels=['股號','名稱','成交','漲幅','成交量','週轉率','均價','均價']
        layout=_layout_from_tokens(Image.new('RGB',(900,400)),[token(t,i*100,40,60) for i,t in enumerate(labels)],False)
        self.assertNotIn('average',layout['column_bounds'])
    def test_batch_and_mapped_paths(self):
        image=Image.new('RGB',(900,80));names=StockNames();names.names={'2330':'測試'}
        values=[self.values[self.fields.index(f)] for f in ['code','price','gain','volume','turnover']+EXTRA_FIELDS]
        rows,bad=scan_numeric_batch(image,self.bounds,lambda im:[token(v,40+i*164,20,40) for i,v in enumerate(values)],names)
        self.assertEqual(bad,0);self.assertEqual(rows[0].average,100.2)
        class App(AutoUI):
            def focus_columns(self,*a,**kw):pass
            def read_auto_ocr(self,image):return [token(next(self.vals),10,20,30)]
        app=App();app.vals=iter(self.values)
        rows,bad=app.scan_mapped_table(image,self.bounds)
        self.assertEqual(bad,0);self.assertEqual(rows[0].open_price,99);self.assertEqual(rows[0].day_high,102)
    def test_fast_cells_missing_extra_keeps_base_row(self):
        from numeric_cells import scan_cells
        image=Image.new('RGB',(180,60));values=['2330','101','4','2070','2','100.2','99','102']
        for row in range(2):
            for i,col in enumerate([0,2,3,4,5,6,7,8]):image.putpixel((col*20+2,row*30+2),(i+1,0,0))
        def recognize(images):
            return [(values[im.getpixel((2,2))[0]-1],.99) for im in images]
        with patch('numeric_cells.row_bands',return_value=[(0,30),(30,60)]):
            # Duplicate stock rows are not accepted; use a distinct code on row two.
            image.putpixel((2,32),(9,0,0));values.append('2317')
            rows,bad,metrics=scan_cells(image,self.bounds,lambda im:[],StockNames(),OrderedDict(),recognize_cells=recognize,review_if=lambda *a:False)
            self.assertEqual(len(rows),2);self.assertEqual(rows[0].average,100.2)
            values[5]='--'
            rows,bad,metrics=scan_cells(image,self.bounds,lambda im:[],StockNames(),OrderedDict(),recognize_cells=recognize,review_if=lambda *a:False)
            self.assertEqual(len(rows),2);self.assertIsNone(rows[0].average)
    def test_all_four_price_conditions_use_captured_values(self):
        from test_scan_profiles import ProfilesTest
        from scan_profiles import ShrinkTracker
        test=ProfilesTest();test.setUp();cfg=dict(test.cfg)
        for k in ('above_average','average_up','above_open','near_high'):cfg['use_shrink_'+k]=True
        tracker=ShrinkTracker()
        for i,(s,p,v) in enumerate(((0,99.9,1000),(300,100,1500),(600,100.5,1850),(900,101,2070))):
            q=test.q(s,p,v);q.average=99+i*.3;q.open_price=99;q.day_high=101.5
            rows,alerts,reports=tracker.apply([(q,None,'',False)],cfg,q.ts)
        self.assertTrue(rows[0][3]);self.assertEqual(len(alerts),1)
        q.open_price=102
        self.assertFalse(tracker.evaluate(q,cfg,q.ts)['passed'])
    def test_archive_serialization_keeps_fields(self):
        from dataclasses import asdict
        q=self.parse()[0][0];restored=Quote(**asdict(q))
        self.assertEqual((restored.average,restored.open_price,restored.day_high),(100.2,99,102))

if __name__=='__main__':unittest.main()
