"""Batch numeric columns and retain only code/name identities, never quote values."""
import json
import re
import time
import unicodedata
from pathlib import Path


class StockNames:
    def __init__(self,path=None):
        self.path=Path(path) if path else None;self.names={};self.reused=0
        if self.path:
            try:
                data=json.loads(self.path.read_text(encoding='utf-8'))
                self.names={c:n for c,n in data.items() if self.valid(c,n)}
            except (OSError,ValueError,AttributeError,TypeError):pass

    @staticmethod
    def valid(code,name):
        return isinstance(code,str) and isinstance(name,str) and bool(re.fullmatch(r'\d{4,6}',code)) and bool(re.fullmatch(r'[\u3400-\u9fffA-Za-z0-9&*\-]{2,24}',name)) and bool(re.search(r'[\u3400-\u9fffA-Za-z]',name))

    def learn(self,code,name,confidence):
        if confidence<.92 or not self.valid(code,name) or code in self.names:return False
        self.names[code]=name;return True

    def save(self):
        if not self.path:return
        try:
            self.path.parent.mkdir(parents=True,exist_ok=True)
            temporary=self.path.with_suffix('.tmp');temporary.write_text(json.dumps(self.names,ensure_ascii=False),encoding='utf-8');temporary.replace(self.path)
        except OSError:pass


def scan_numeric_batch(image,bounds,read,names,memory=None,focus=None):
    from PIL import Image
    from MitakeDetector import FIELDS,parse_ocr
    import hashlib
    fields=['code','price','gain','volume','turnover'];pieces=[];cached=[];cursor=32
    for field in fields:
        left,right=bounds[field]
        if not 0<=left<right<=1:raise ValueError('自動欄位範圍無效')
        crop=image.crop((round(left*image.width),0,round(right*image.width),image.height)).convert('RGB')
        key=f'numeric-column:{field}:{crop.width}x{crop.height}:'+hashlib.sha256(crop.tobytes()).hexdigest()
        remembered=memory.lookup(key) if memory else None
        if remembered is not None:
            cached.append((field,remembered));memory.record(key,remembered,0,'數字欄位重用')
        else:pieces.append((field,cursor,cursor+crop.width,crop,key));cursor+=crop.width+64
    background=image.convert('RGB').getpixel((0,0))
    sheet=Image.new('RGB',(cursor,image.height),background)
    for field,left,right,crop,key in pieces:sheet.paste(crop,(left,0))
    logical=[];anchors=[]
    def append(field,box,text,confidence):
        text=unicodedata.normalize('NFKC',str(text)).strip()
        if field=='code':
            text=re.sub(r'^[^0-9]+','',text)
            if re.fullmatch(r'[0-9 ]{4,9}',text):text=text.replace(' ','')
        y=sum(p[1] for p in box)/4;h=max(p[1] for p in box)-min(p[1] for p in box)
        x=50+FIELDS.index(field)*100
        logical.append(([[x-10,y-h/2],[x+10,y-h/2],[x+10,y+h/2],[x-10,y+h/2]],text,confidence))
        if field=='code' and confidence>=.70 and re.fullmatch(r'\d{4,6}',text):anchors.append((text,y,max(h,8)))
    for field,tokens in cached:
        for box,text,confidence in tokens:append(field,box,text,confidence)
    learned={field:[] for field,_,_,_,_ in pieces}
    if pieces and focus:focus([p[0] for p in pieces],'批次辨識數字欄')
    for box,text,confidence in (read(sheet) if pieces else []):
        l=min(p[0] for p in box);r=max(p[0] for p in box)
        matches=[field for field,left,right,_,_ in pieces if left<=l and r<=right]
        if len(matches)==1:
            field=matches[0];append(field,box,text,confidence)
            left=next(left for f,left,_,_,_ in pieces if f==field)
            learned[field].append(([[x-left,y] for x,y in box],text,confidence))
    if memory:
        for field,left,right,crop,key in pieces:memory.record(key,learned[field],0,'數字欄位學習')
    unknown=[a for a in anchors if a[0] not in names.names]
    changed=False
    if unknown:
        left,right=bounds['name']
        crop=image.crop((round(left*image.width),0,round(right*image.width),image.height))
        if focus:focus(['name'],'補讀未知股號的股名')
        tokens=read(crop)
        for code,y,h in unknown:
            near=[t for t in tokens if abs(sum(p[1] for p in t[0])/4-y)<h*.55]
            if len(near)!=1:continue
            box,text,confidence=near[0];text=unicodedata.normalize('NFKC',str(text)).replace(' ','').strip()
            changed=names.learn(code,text,confidence) or changed
    for code,y,h in anchors:
        name=names.names.get(code)
        if name:
            append('name',[[0,y-h/2],[10,y-h/2],[10,y+h/2],[0,y+h/2]],name,1)
            if code not in {a[0] for a in unknown}:names.reused+=1
    if changed:names.save()
    return parse_ocr(logical,600,[100*(50+i*100)/600 for i in range(6)],time.time())
