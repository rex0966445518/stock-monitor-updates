import unittest
from PIL import Image,ImageDraw
from auto_detect import combo_frame,detect_menu,detect_layout
from auto_ui import AutoUI


def token(text,x,y,width=70):return ([[x,y],[x+width,y],[x+width,y+18],[x,y+18]],text,.99)


class DetectionTests(unittest.TestCase):
    def test_zero_strip_read_recovers_from_whole_table_without_guessing(self):
        from MitakeDetector import FIELDS
        class App(AutoUI):
            def scan_mapped_table(self,image,bounds):return [],1
            def read_auto_ocr(self,image):
                return [token(text,10+i*100,20,width=30) for i,text in enumerate(self.values)]
        app=App();app.values=['2 330','台積電','100.50','+3.00%','1500','1.20%']
        image=Image.new('RGB',(600,80));bounds={n:[i/6,(i+1)/6] for i,n in enumerate(FIELDS)}
        rows,bad=app.scan_recovering_table(image,bounds)
        self.assertEqual((len(rows),bad),(1,0));self.assertEqual(rows[0].code,'2330')
        app.values[2]='--'
        rows,bad=app.scan_recovering_table(image,bounds)
        self.assertEqual(rows,[]);self.assertEqual(bad,1)
        self.assertIn('股號文字',app.last_scan_diagnostic)

    def test_task_steps_describe_real_work(self):
        from task_progress import task_stage
        self.assertIn('1/4',task_stage('正在辨識排行下拉框與六個表頭…'))
        self.assertIn('2/4',task_stage('正在定位展開選單…'))
        self.assertIn('3/4',task_stage('正在讀取股票資料並驗證欄位…'))
        self.assertIn('4/4',task_stage('自動設定完成，準備啟動輪切掃描…'))
        self.assertIn('切換',task_stage('第1輪｜排行1/5：正在切換漲幅排行'))
        self.assertIn('讀取',task_stage('第1輪｜漲幅排行｜第1/1頁辨識中'))
        self.assertIn('等待',task_stage('漲幅排行｜已讀26檔｜15秒後繼續'))
    def test_voice_command_encodes_chinese_and_escapes_quotes(self):
        import base64
        from windows_voice import speech_command
        cmd=speech_command("開始執行掃描 '測試'")
        self.assertEqual(cmd[0],'powershell.exe')
        script=base64.b64decode(cmd[-1]).decode('utf-16-le')
        self.assertIn("開始執行掃描 ''測試''",script)
        self.assertIn('404',script)
    def test_update_button_starts_download_without_second_dialog(self):
        from MitakeDetector import App
        class Fake:
            update_busy=True
            def say(self,text):pass
            def download_update(self,payload):self.downloaded=payload
        app=Fake();manifest={'version':'99.0.0','size':100,'notes':'test'}
        App.handle_update(app,'update_checked',manifest)
        self.assertEqual(app.downloaded,manifest)
        self.assertFalse(app.update_busy)
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
