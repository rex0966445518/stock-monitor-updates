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
        calls=[];price=['100.50']
        def read(im):
            calls.append(im.width)
            if im.width==100:return [token('台積電',5,20,width=60)]
            return [token(text,40+i*164,20,width=50) for i,text in enumerate(['2330',price[0],'+2.3%','1500','1.2%'])]
        with tempfile.TemporaryDirectory() as folder:
            path=Path(folder)/'names.json';names=StockNames(path)
            rows,bad=scan_numeric_batch(image,bounds,read,names)
            self.assertEqual((rows[0].code,rows[0].name,rows[0].price,bad),('2330','台積電',100.5,0))
            self.assertEqual(len(calls),2)
            calls.clear();price[0]='102.00';names=StockNames(path)
            rows,bad=scan_numeric_batch(image,bounds,read,names)
            self.assertEqual(len(calls),1);self.assertEqual(rows[0].name,'台積電')
            self.assertEqual(rows[0].price,102);self.assertEqual(names.reused,1)

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
