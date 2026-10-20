import json
import sqlite3
import tempfile
import unittest
from pathlib import Path
from collections import OrderedDict
from unittest.mock import patch
from PIL import Image
from fast_quotes import StockNames,scan_numeric_batch
from MitakeDetector import parse_ocr,FIELDS,Quote
from auto_ui import AutoUI
from test_auto_detect import token

class IdentityTests(unittest.TestCase):
    def test_sqlite_survives_without_legacy_json_and_keeps_verified_pair(self):
        with tempfile.TemporaryDirectory() as d:
            path=Path(d)/'stock-names.json';names=StockNames(path)
            self.assertTrue(names.learn('2330','台積電',.99));names.save()
            with sqlite3.connect(path.with_suffix('.sqlite3')) as c:
                row=c.execute('SELECT code,name,confidence,source,learned_at FROM stock_names').fetchone()
                self.assertEqual(row[:4],('2330','台積電',.99,'verified-ocr'));self.assertGreater(row[4],0)
            path.unlink();names=StockNames(path)
            self.assertEqual(names.names,{'2330':'台積電'})
            self.assertFalse(names.learn('2330','錯誤股名',.99))
    def test_legacy_migrates_only_four_digit_codes_and_db_wins(self):
        with tempfile.TemporaryDirectory() as d:
            path=Path(d)/'stock-names.json'
            path.write_text(json.dumps({'2330':'台積電','00929':'長股號','0050':'元大台灣'}))
            names=StockNames(path);self.assertEqual(set(names.names),{'2330','0050'})
            path.write_text(json.dumps({'2330':'錯誤名稱'}))
            self.assertEqual(StockNames(path).names['2330'],'台積電')
    def test_weak_or_long_code_never_enters_database(self):
        names=StockNames()
        for c,n,score in [('2330','台積雷',.6),('00929','中文名稱',.99),('123456','中文名稱',.99),('2330','123',.99)]:
            self.assertFalse(names.learn(c,n,score))
        self.assertFalse(names.names)
    def test_database_write_error_is_visible_without_losing_memory(self):
        with tempfile.TemporaryDirectory() as d:
            names=StockNames(Path(d)/'names.json');names.learn('2330','台積電',.99)
            with patch.object(names,'connect',side_effect=sqlite3.OperationalError('fixture locked')):names.save()
            self.assertIn('fixture locked',names.last_error);self.assertTrue(names.dirty)
            names.save();self.assertFalse(names.last_error)
    def test_parser_keeps_four_digits_with_leading_zero_without_truncating_long_codes(self):
        tokens=[]
        for row,code in enumerate(['2330','00929','123456','0050','１２３４５']):
            for col,value in enumerate([code,'中文名稱','100','2','1000','1']):tokens.append(token(value,40+col*100,20+row*30,width=20))
        rows,bad=parse_ocr(tokens,600,[100*(50+i*100)/600 for i in range(6)],1)
        self.assertEqual([q.code for q in rows],['2330','0050']);self.assertEqual(bad,0)
    def test_first_encounter_learning_ignores_candidate_score_and_second_scan_skips_names(self):
        app=AutoUI();app.stock_names=StockNames();app.name_eligible_codes=set()
        image=Image.new('RGB',(600,80));bounds={f:[i/6,(i+1)/6] for i,f in enumerate(FIELDS)}
        app.remember_fast_rows(image,bounds,[('2330',30,18),('00929',60,18)],{'2330':.99,'00929':.99})
        calls=[]
        app.read_auto_ocr=lambda im:calls.append(im.size) or [token('台積電',8,8,width=50)]
        app.learn_deferred_names()
        self.assertEqual(app.stock_names.names,{'2330':'台積電'});self.assertEqual(len(calls),1)
        app.remember_fast_rows(image,bounds,[('2330',30,18)],{'2330':.99})
        app.learn_deferred_names();self.assertEqual(len(calls),1)
    def test_immediate_name_learning_preserves_numeric_capture_timestamp(self):
        app=AutoUI();app.stock_names=StockNames();app.name_eligible_codes=set()
        image=Image.new('RGB',(600,80));image.info['captured_at']=123
        bounds={f:[i/6,(i+1)/6] for i,f in enumerate(FIELDS)}
        def numeric(im,b):
            app.remember_fast_rows(im,b,[('2330',30,18)],{'2330':.99})
            return [Quote('2330','',100,2,1000,1,999)],0
        app._scan_recovering_table=numeric
        app.read_auto_ocr=lambda im:[token('台積電',8,8,width=50)]
        rows,bad=app.scan_recovering_table(image,bounds)
        self.assertEqual((rows[0].name,rows[0].ts),('台積電',123))
    def test_filtered_page_scrolls_past_without_false_ocr_failure(self):
        from test_table_scan import TableController
        from table_scan import scan_table
        codes=['100001','100002','100003','100004','100005','100006','2330','2317','2454','6412','1101','1102']
        c=TableController(rows=len(codes));messages=[]
        anchors=lambda n:codes[n:n+6]
        read=lambda n:([Quote(code,'中文名稱',100,2,1000,1,1) for code in anchors(n) if len(code)==4],0)
        rows,_=scan_table(c,[0,0,1,1],read,lambda *args:messages.append(args[2]),read_anchors=anchors)
        self.assertEqual({q.code for q in rows},set(codes[6:]));self.assertTrue(any('跳過' in m for m in messages))
    def test_all_filtered_page_finishes_without_repositioning_headers(self):
        from test_table_scan import TableController
        from table_scan import scan_table
        c=TableController(rows=6)
        rows,bad=scan_table(c,[0,0,1,1],lambda im:([],0),lambda *args:None,read_anchors=lambda im:['100001','00679B'],recover_layout=lambda:self.fail('Valid excluded page must not relocalize'))
        self.assertEqual(rows,[]);self.assertEqual(bad,0)
    def test_four_digit_anchor_with_no_quote_still_fails(self):
        from test_table_scan import TableController
        from table_scan import scan_table,RecoverableScanError
        with self.assertRaises(RecoverableScanError):
            scan_table(TableController(rows=6),[0,0,1,1],lambda im:([],0),lambda *args:None,read_anchors=lambda im:['2330'])
    def test_code_first_never_reads_long_code_numeric_cells(self):
        from test_numeric_cells import CellTests
        from numeric_cells import scan_cells
        fixture=CellTests();fixture.setUp();fixture.values[0]='00929';fixture.values[5]='00679B'
        seen=[]
        def read(im):
            tokens=fixture.read(im);seen.extend(t[1] for t in tokens);return tokens
        with patch('numeric_cells.row_bands',return_value=[(0,30),(30,60)]):
            rows,bad,metrics=scan_cells(fixture.image,fixture.bounds,read,StockNames(),OrderedDict())
        self.assertEqual(rows,[]);self.assertEqual(seen,['00929','00679B'])
        self.assertEqual(metrics['skipped'],2);self.assertEqual(metrics['changed'],2)
    def test_mixed_codes_only_read_valid_rows_and_retain_navigation_anchors(self):
        from test_numeric_cells import CellTests
        from numeric_cells import scan_cells
        fixture=CellTests();fixture.setUp();fixture.values[0]='00929';anchors=[]
        with patch('numeric_cells.row_bands',return_value=[(0,30),(30,60)]):
            rows,bad,metrics=scan_cells(fixture.image,fixture.bounds,fixture.read,StockNames(),OrderedDict(),anchors_ready=lambda a,s:anchors.extend(a))
        self.assertEqual([q.code for q in rows],['2317']);self.assertEqual(metrics['changed'],6)
        self.assertEqual({c for c,y,h in anchors},{'00929','2317'})

if __name__=='__main__':unittest.main()
