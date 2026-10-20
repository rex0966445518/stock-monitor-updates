"""Real bundled Chinese OCR across synthetic laptop/desktop physical pixels."""
def run_layout_smoke(app):
    from PIL import Image,ImageDraw,ImageFont
    from auto_detect import bbox
    reports=[]
    for width,height,pixels in [(1280,720,14),(1366,768,18),(1920,1080,27),(2560,1440,36),(3840,2160,36)]:
        scale=pixels/18
        image=Image.new('RGB',(width,height),'#101820');draw=ImageDraw.Draw(image)
        font=ImageFont.truetype('C:/Windows/Fonts/msjh.ttc',pixels)
        combo=(round(6*scale),round(25*scale),round(180*scale),round(70*scale))
        draw.rectangle(combo,outline=(110,115,120),width=max(1,round(scale)))
        draw.text((round(18*scale),round(35*scale)),'漲幅排行',font=font,fill='white')
        y=round(height*.30)
        for i,text in enumerate(['股號','股名','成交價','漲幅%','成交量','週轉率','最高']):
            draw.text((round(width*(.02+i*.14)),y),text,font=font,fill='white')
        layout=app.detect_layout_recovering(image)
        assert len(layout['column_bounds'])==6,(width,layout)
        assert .28<layout['table'][1]<.39,(width,layout)
        assert layout['combo'] and layout['combo'][0]<.05,(width,layout)
        for field,(left,right) in layout['column_bounds'].items():assert 0<=left<right<=1,(width,field,left,right)
        assert layout['column_bounds']['turnover'][1]<.89,(width,layout)
        reports.append(dict(width=width,height=height,font_pixels=pixels,table_top=layout['table'][1]))
    return reports
