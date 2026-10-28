import tempfile
import unittest
from pathlib import Path
from PIL import Image
from fast_quotes import StockNames,scan_numeric_batch
from test_auto_detect import token
from MitakeDetector import FIELDS


class FastQuoteTests(unittest.TestCase):
    def test_remembers_names_restarts_and_always_reads_new_numbers(self):
        image=Image.new('RGB',(600,80));bounds={f:[i/6,(i+1)/6] for i,f in enumerate(FIELDS)}
        focus=[]
        calls=[];price=['100.50']
        def read(im):
            calls.append(im.width)
            if im.width==100:return [token('台積電',5,20,width=60)]
            return [token(text,40+i*164,20,width=50) for i,text in enumerate(['2330',price[0],'+2.3%','1500','1.2%'])]
        with tempfile.TemporaryDirectory() as folder:
            path=Path(folder)/'names.json';names=StockNames(path)
            rows,bad=scan_numeric_batch(image,bounds,read,names,focus=lambda fields,stage:focus.append(list(fields)))
            self.assertEqual((rows[0].code,rows[0].name,rows[0].price,bad),('2330','台積電',100.5,0))
            self.assertEqual(len(calls),2)
            self.assertEqual(focus,[['code','price','gain','volume','turnover'],['name']])
            calls.clear();price[0]='102.00';names=StockNames(path)
            rows,bad=scan_numeric_batch(image,bounds,read,names)
            self.assertEqual(len(calls),1);self.assertEqual(rows[0].name,'台積電')
            self.assertEqual(rows[0].price,102);self.assertEqual(names.reused,1)

    def test_uncertain_name_is_zoomed_then_persisted_for_next_scan(self):
        image=Image.new('RGB',(600,80));bounds={f:[i/6,(i+1)/6] for i,f in enumerate(FIELDS)}
        calls=[];focus=[]
        def read(im):
            calls.append(im.width)
            if im.width>500:return [token(t,40+i*164,20,width=50) for i,t in enumerate(['2330','100','+2%','1500','1%'])]
            if im.width==100:return [(token('台積雷',5,20,width=60)[0],'台積雷',.6)]
            return [token('台積電',5,5,width=100)]
        with tempfile.TemporaryDirectory() as folder:
            path=Path(folder)/'names.json';names=StockNames(path)
            rows,_=scan_numeric_batch(image,bounds,read,names,focus=lambda *args:focus.append(args))
            self.assertEqual(rows[0].name,'台積電');self.assertIn(200,calls)
            self.assertTrue(any(len(args)==3 and '放大' in args[1] for args in focus))
            calls.clear()
            rows,_=scan_numeric_batch(image,bounds,read,StockNames(path))
            self.assertEqual(rows[0].name,'台積電');self.assertEqual(len(calls),1)

    def test_moderate_names_need_two_distinct_proofs_and_survive_restart(self):
        with tempfile.TemporaryDirectory() as folder:
            path=Path(folder)/'names.json';names=StockNames(path)
            self.assertFalse(names.consider('2330','台積電',.88,'original'))
            self.assertFalse(names.consider('2330','台積電',.88,'original'))
            names.save();names=StockNames(path)
            self.assertTrue(names.consider('2330','台積電',.88,'zoom2'))
            names.save();self.assertEqual(StockNames(path).names['2330'],'台積電')
            self.assertFalse(names.consider('2330','台積雷',.99,'new'))
            self.assertEqual(names.names['2330'],'台積電')
    def test_same_unreadable_name_is_not_repeatedly_zoomed(self):
        names=StockNames()
        self.assertTrue(names.ready_to_zoom('2330','same',now=100))
        self.assertFalse(names.ready_to_zoom('2330','same',now=101))
        self.assertTrue(names.ready_to_zoom('2330','changed',now=102))
        self.assertTrue(names.ready_to_zoom('2330','changed',now=223))
    def test_fallback_also_learns_verified_identity(self):
        from fast_quotes import learn_mapped_names
        names=StockNames()
        learn_mapped_names([token('2330',40,20,width=20),token('台積電',140,20,width=20)],names,'frame1')
        self.assertEqual(names.names['2330'],'台積電')

    def test_only_unknown_name_rows_are_batched_when_most_names_are_known(self):
        image=Image.new('RGB',(600,260));bounds={f:[i/6,(i+1)/6] for i,f in enumerate(FIELDS)}
        names=StockNames();names.names={'2330':'台積電','2317':'鴻海','2454':'聯發科'};name_heights=[]
        def read(im):
            if im.width==100:
                name_heights.append(im.height);return [token('群電',5,10,width=60)]
            return [token(value,40+i*164,20+r*60,width=50) for r,code in enumerate(['2330','2317','2454','6412']) for i,value in enumerate([code,'100','+2%','1500','1%'])]
        rows,_=scan_numeric_batch(image,bounds,read,names)
        self.assertEqual(len(rows),4);self.assertEqual(rows[-1].name,'群電')
        self.assertEqual(len(name_heights),1);self.assertLess(name_heights[0],100)

    def test_bad_identity_and_conflicting_names_do_not_replace_memory(self):
        names=StockNames()
        self.assertFalse(names.learn('2330','12345',.99))
        self.assertFalse(names.learn('2330','台積電',.60))
        self.assertTrue(names.learn('2330','台積電',.99))
        self.assertFalse(names.learn('2330','台積雷',.99))
        self.assertEqual(names.names['2330'],'台積電')

    def test_batch_does_not_shift_missing_numeric_fields(self):
        image=Image.new('RGB',(600,80));bounds={f:[i/6,(i+1)/6] for i,f in enumerate(FIELDS)}
        def read(im):return [token(text,40+i*164,20,width=50) for i,text in enumerate(['2330','--','+2.3%','1500','1.2%'])] if im.width>100 else []
        rows,bad=scan_numeric_batch(image,bounds,read,StockNames())
        self.assertEqual(rows,[]);self.assertEqual(bad,1)


if __name__=='__main__':unittest.main()

class NumericPriorityTests(unittest.TestCase):
    def test_numeric_scan_skips_unknown_name_ocr_but_keeps_anchors(self):
        image=Image.new('RGB',(600,80));bounds={f:[i/6,(i+1)/6] for i,f in enumerate(FIELDS)}
        calls=[];anchors=[]
        def read(im):
            calls.append(im.size)
            return [token(text,40+i*164,20,width=50) for i,text in enumerate(['2330','100','+2%','1500','1%'])]
        names=StockNames()
        rows,bad=scan_numeric_batch(image,bounds,read,names,defer_names=True,anchors_ready=lambda a,s:anchors.extend(a))
        self.assertEqual(len(calls),1);self.assertEqual(rows[0].code,'2330')
        self.assertEqual(rows[0].name,'');self.assertEqual(anchors[0][0],'2330')
    def test_idle_name_learning_is_batched_and_anchor_cache_invalidates(self):
        from auto_ui import AutoUI
        app=AutoUI();app.stock_names=StockNames();app.ocr_learning=False
        app.focus_columns=lambda *args,**kwargs:None
        image=Image.new('RGB',(600,80));bounds={f:[i/6,(i+1)/6] for i,f in enumerate(FIELDS)}
        app.remember_fast_rows(image,bounds,[('2330',30,18)],{'2330':.99})
        calls=[]
        def read(im):
            calls.append(im.size)
            return [token('台積電',8,8,width=50)]
        app.read_auto_ocr=read
        self.assertEqual(app.read_stock_anchors(image,bounds),['2330'])
        self.assertEqual(calls,[])
        # First encounter learns independently of the candidate score.
        app.name_eligible_codes=set()
        app.learn_deferred_names()
        self.assertNotIn('2330',app.deferred_names)
        self.assertEqual(app.stock_names.names['2330'],'台積電')
        self.assertEqual(len(calls),1)
        app.read_auto_ocr=lambda im:[]
        changed=image.copy();changed.putpixel((0,0),(255,255,255))
        self.assertEqual(app.read_stock_anchors(changed,bounds),[])
