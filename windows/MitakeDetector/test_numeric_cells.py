import unittest
from collections import OrderedDict
from unittest.mock import patch
from PIL import Image
from numeric_cells import scan_cells,row_bands
from fast_quotes import StockNames
from MitakeDetector import FIELDS

class CellTests(unittest.TestCase):
    def setUp(self):
        self.image=Image.new('RGB',(120,60));self.bounds={f:[i/6,(i+1)/6] for i,f in enumerate(FIELDS)}
        self.values=['2330','100','+3%','1500','2%','2317','200','+4%','2000','3%','101']
        for row in range(2):
            for field,col in enumerate([0,2,3,4,5]):self.image.putpixel((col*20+2,row*30+2),(row*5+field+1,0,0))
        self.calls=0
    def read(self,image):
        self.calls+=1;result=[]
        for y in range(image.height):
            for x in range(image.width):
                r,g,b=image.getpixel((x,y))
                if r and g==b==0:result.append(([[x,y],[x+8,y],[x+8,y+8],[x,y+8]],self.values[r-1],.99))
        return result
    def scan(self,image,cache,**kw):
        with patch('numeric_cells.row_bands',return_value=[(0,30),(30,60)]):return scan_cells(image,self.bounds,self.read,StockNames(),cache,**kw)
    def test_unchanged_cells_skip_ocr_and_changed_price_is_reread(self):
        cache=OrderedDict();first=self.scan(self.image,cache)
        self.assertEqual(len(first[0]),2);self.assertEqual(first[2]['changed'],10)
        second=self.scan(self.image,cache);self.assertEqual(self.calls,2);self.assertEqual(second[2]['reused'],10)
        changed=self.image.copy();changed.putpixel((42,2),(11,0,0))
        third=self.scan(changed,cache);self.assertEqual(self.calls,3)
        self.assertEqual(third[0][0].price,101);self.assertEqual(third[2]['changed'],1)
        self.assertEqual(third[2]['reused'],9)
    def test_forced_recovery_does_not_reuse_cell_cache(self):
        cache=OrderedDict();self.scan(self.image,cache);result=self.scan(self.image,cache,force=True)
        self.assertEqual(self.calls,4);self.assertEqual(result[2]['reused'],0)
    def test_incomplete_cells_return_to_existing_fallback(self):
        cache=OrderedDict();self.read=lambda image:[]
        self.assertIsNone(self.scan(self.image,cache));self.assertEqual(len(cache),0)
    def test_row_projection_ignores_horizontal_and_vertical_grid_lines(self):
        from PIL import ImageDraw
        image=Image.new('RGB',(120,90));draw=ImageDraw.Draw(image)
        for y in (12,42,72):
            for x in (3,8,13):draw.rectangle((x,y,x+2,y+10),fill='white')
        draw.line((0,30,19,30),fill='white');draw.line((0,0,0,89),fill='white')
        bands=row_bands(image,self.bounds);self.assertEqual(len(bands),3)
        self.assertEqual(bands[0][0],0);self.assertEqual(bands[-1][1],90)
