"""Exercise actual bundled Tcl/Tk, OCR models, sample rows and persistent storage."""
import datetime as dt
import json
import sys
import traceback
from pathlib import Path


def run_smoke_test(report):
    report=Path(report);report.parent.mkdir(parents=True,exist_ok=True)
    info={}
    root=None
    try:
        import tkinter as tk
        from PIL import Image,ImageDraw,ImageFont,ImageGrab
        from MitakeDetector import App,Quote,DEFAULT,VERSION,BASE
        from updater import validate_manifest
        root=tk.Tk();root.withdraw();app=App(root)
        font=ImageFont.truetype('C:/Windows/Fonts/arial.ttf',24)
        im=Image.new('RGB',(1200,260),'#101820');draw=ImageDraw.Draw(im)
        for row,values in enumerate([['2330','TESTA','100.50','2.30%','1500','1.20%'],['2317','TESTB','205.00','3.10%','2800','2.40%']]):
            for x,text in zip(DEFAULT['centers'],values):
                draw.text((x*12,60+row*65),text,font=font,fill='white',anchor='mm')
        quotes,rejected=app.scan(im,DEFAULT['centers'])
        assert len(quotes)==2 and rejected==0, f'OCR result: {quotes}, rejected {rejected}'
        assert quotes[0].code=='2330' and quotes[0].volume==1500 and quotes[1].price==205
        now=dt.datetime.now().timestamp()
        app.mode='CI smoke / synthetic data'
        app.render([Quote('9001','SMOKE',100,2,1500,1,now)],0,0,dict(DEFAULT),persist=True)
        assert (BASE/'observations.sqlite3').is_file()
        from automation_smoke import run_automation_smoke
        automation=run_automation_smoke(root,app)
        app.demo();root.geometry('1180x900');root.deiconify()
        def finish():
            try:
                root.update_idletasks()
                assert len(app.tree.get_children())==2
                screenshot=report.with_suffix('.png')
                ImageGrab.grab(bbox=(root.winfo_rootx(),root.winfo_rooty(),root.winfo_rootx()+root.winfo_width(),root.winfo_rooty()+root.winfo_height())).save(screenshot)
                info.update(automation=automation,ok=True,version=VERSION,frozen=bool(getattr(sys,'frozen',False)),ocr_rows=len(quotes),gui_rows=len(app.tree.get_children()),database=True,screenshot=str(screenshot))
            except Exception:
                info.update(ok=False,error=traceback.format_exc())
            finally:
                app.stop();root.destroy()
        root.after(1800,finish);root.mainloop()
    except Exception:
        info.update(ok=False,error=traceback.format_exc())
        if root:
            try:root.destroy()
            except Exception:pass
    report.write_text(json.dumps(info,ensure_ascii=False,indent=2),'utf-8')
    if not info.get('ok'):raise SystemExit(1)
