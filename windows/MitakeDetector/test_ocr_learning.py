import json
import tempfile
import unittest
from pathlib import Path
from PIL import Image
from ocr_learning import OCRLearning
from fast_quotes import StockNames,scan_numeric_batch
from MitakeDetector import FIELDS
from test_auto_detect import token


class OCRLearningTests(unittest.TestCase):
    def test_persistent_exact_key_and_low_confidence_not_cached(self):
        with tempfile.TemporaryDirectory() as folder:
            path=Path(folder)/'memory.sqlite3';memory=OCRLearning(path)
            tokens=[token('2330',10,10)]
            memory.record('image-a',tokens,.5,'重新辨識')
            self.assertEqual(OCRLearning(path).lookup('image-a')[0][1],'2330')
            self.assertIsNone(memory.lookup('image-b'))
            weak=[(tokens[0][0],'2330',.5)]
            memory.record('weak',weak,.5,'重新辨識')
            self.assertIsNone(memory.lookup('weak'))
            cached,rows=memory.recent();self.assertEqual(cached,1);self.assertEqual(len(rows),2)
            memory.clear_cache();self.assertIsNone(memory.lookup('image-a'))
            self.assertEqual(len(memory.recent()[1]),2)

    def test_only_changed_numeric_column_is_read_and_unchanged_pixels_reuse(self):
        with tempfile.TemporaryDirectory() as folder:
            memory=OCRLearning(Path(folder)/'memory.sqlite3')
            image=Image.new('RGB',(600,80));bounds={f:[i/6,(i+1)/6] for i,f in enumerate(FIELDS)}
            names=StockNames();names.names={'2330':'台積電'};calls=[]
            def read(im):
                calls.append(im.width)
                if im.width<300:return [token('102.00',40,20,width=50)]
                return [token(text,40+i*164,20,width=50) for i,text in enumerate(['2330','100.50','+2.3%','1500','1.2%'])]
            rows,_=scan_numeric_batch(image,bounds,read,names,memory)
            self.assertEqual(rows[0].price,100.5);self.assertEqual(len(calls),1)
            image.putpixel((205,2),(255,255,255));calls.clear()
            rows,_=scan_numeric_batch(image,bounds,read,names,memory)
            self.assertEqual(rows[0].price,102);self.assertEqual(calls,[196])
            calls.clear();rows,_=scan_numeric_batch(image,bounds,read,names,memory)
            self.assertEqual(calls,[]);self.assertEqual(rows[0].volume,1500)


if __name__=='__main__':unittest.main()
