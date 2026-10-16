import unittest
from PIL import Image,ImageDraw
from auto_detect import combo_frame,detect_menu,detect_layout
from auto_ui import AutoUI


def token(text,x,y,width=70):return ([[x,y],[x+width,y],[x+width,y+18],[x,y+18]],text,.99)


class DetectionTests(unittest.TestCase):
    def test_combo_requires_visible_frame(self):
        im=Image.new('RGB',(1000,500),'#102c40')
        draw=ImageDraw.Draw(im);draw.rectangle((5,25,155,51),outline=(100,105,110),width=1)
        roi=combo_frame(im,[token('漲幅排行',15,29)])
        self.assertLess(abs(roi[0]-5),3);self.assertLess(abs(roi[2]-155),4)
        with self.assertRaises(ValueError):combo_frame(Image.new('RGB',im.size,'black'),[token('漲幅排行',15,29)])
    def test_no_guess_for_missing_headers(self):
        with self.assertRaises(ValueError):detect_layout(Image.new('RGB',(1000,500),'black'),lambda im:[])
    def test_menu_requires_multiple_known_items(self):
        im=Image.new('RGB',(1000,600),'black');combo=[.005,.03,.15,.05]
        with self.assertRaises(ValueError):detect_menu(im,combo,lambda im:[token('漲幅排行',15,10)])
        roi=detect_menu(im,combo,lambda im:[token(n,15,10+i*30) for i,n in enumerate(['漲幅排行','預估量增','大單流入'])])
        self.assertGreater(roi[2],.1);self.assertGreater(roi[3],.1)
    def test_mapped_scan_ignores_extra_columns_and_reversed_identity(self):
        class App(AutoUI):
            def read_auto_ocr(self,image):
                self.seen.append(image.width)
                return [token(self.values.pop(0),5,20,width=30)]
        app=App();app.seen=[];app.values=['3354','律勝','41.80','+10.00%','2697','3.85%']
        bounds={'name':[0,.1],'code':[.1,.2],'price':[.2,.3],'gain':[.4,.5],'volume':[.6,.7],'turnover':[.7,.8]}
        quotes,bad=app.scan_mapped_table(Image.new('RGB',(1000,80)),bounds)
        self.assertEqual(bad,0);self.assertEqual(len(quotes),1)
        q=quotes[0];self.assertEqual((q.code,q.name,q.price,q.gain,q.volume,q.turnover),('3354','律勝',41.8,10,2697,3.85))
        self.assertEqual(app.seen,[100]*6)
    def test_mapped_scan_missing_price_is_not_filled(self):
        class App(AutoUI):
            def read_auto_ocr(self,image):return [token(self.values.pop(0),5,20,width=30)]
        app=App();app.values=['3354','律勝','--','+10%','2697','3.85%']
        names=['code','name','price','gain','volume','turnover']
        quotes,bad=app.scan_mapped_table(Image.new('RGB',(600,80)),{n:[i/6,(i+1)/6] for i,n in enumerate(names)})
        self.assertEqual(quotes,[]);self.assertEqual(bad,1)


if __name__=='__main__':unittest.main()
