"""Batch numeric columns and retain only code/name identities, never quote values."""
import json
import sqlite3
from contextlib import closing
import re
import time
import unicodedata
from pathlib import Path


class StockNames:
    def __init__(self,path=None):
        self.path=Path(path) if path else None;self.names={};self.reused=0;self.pending={};self.dirty=False
        self.db_path=self.path.with_suffix(".sqlite3") if self.path else None
        self.last_error="";self.confidence={}
        if self.path:
            try:
                data=json.loads(self.path.read_text(encoding='utf-8'))
                self.names={c:n for c,n in data.items() if self.valid(c,n)}
            except (OSError,ValueError,AttributeError,TypeError):pass
            try:
                pending=json.loads(self.path.with_name(self.path.stem+'-learning.json').read_text(encoding='utf-8'))
                if isinstance(pending,dict):
                    self.pending={c:e for c,e in list(pending.items())[-2000:] if re.fullmatch(r'[0-9]{4}',c) and isinstance(e,dict)}
            except (OSError,ValueError,TypeError):pass
            # Verified legacy JSON identities migrate once; DB wins on conflicts.
            try:
                self.path.parent.mkdir(parents=True,exist_ok=True)
                with closing(self.connect()) as c,c:
                    c.executemany('INSERT OR IGNORE INTO stock_names VALUES (?,?,?,?,?)',[(code,name,.92,'legacy-verified-cache',time.time()) for code,name in self.names.items()])
                    self.names={code:name for code,name in c.execute('SELECT code,name FROM stock_names') if self.valid(code,name)}
            except (OSError,sqlite3.Error) as exc:self.last_error=str(exc)

    def connect(self):
        c=sqlite3.connect(self.db_path,timeout=5)
        c.execute('CREATE TABLE IF NOT EXISTS stock_names(code TEXT PRIMARY KEY,name TEXT NOT NULL,confidence REAL,source TEXT,learned_at REAL)')
        return c

    @staticmethod
    def valid(code,name):
        return isinstance(code,str) and isinstance(name,str) and bool(re.fullmatch(r'[0-9]{4}',code)) and bool(re.fullmatch(r'[\u3400-\u9fffA-Za-z0-9&*\-]{2,24}',name)) and bool(re.search(r'[\u3400-\u9fffA-Za-z]',name))

    def learn(self,code,name,confidence):
        if confidence<.92 or not self.valid(code,name) or code in self.names:return False
        self.names[code]=name;self.confidence[code]=confidence;self.pending.pop(code,None);self.dirty=True;return True

    def consider(self,code,name,confidence,proof):
        name=unicodedata.normalize('NFKC',str(name)).replace(' ','').strip()
        if code in self.names or not self.valid(code,name) or confidence<.85:return False
        if confidence>=.92:return self.learn(code,name,confidence)
        entry=self.pending.setdefault(code,{})
        proofs=entry.get('proofs',[]) if entry.get('candidate')==name else []
        if not isinstance(proofs,list):proofs=[]
        if proof not in proofs:proofs=(proofs+[proof])[-2:];self.dirty=True
        entry.update(candidate=name,proofs=proofs)
        if len(proofs)>=2:return self.learn(code,name,.92)
        return False

    def ready_to_zoom(self,code,fingerprint,now=None):
        now=time.time() if now is None else now
        entry=self.pending.setdefault(code,{})
        if entry.get('retry_key')==fingerprint and isinstance(entry.get('retry_after'),(int,float)) and entry['retry_after']>now:return False
        entry.update(retry_key=fingerprint,retry_after=now+120);self.dirty=True
        return True

    def save(self):
        if not self.path:return
        try:
            self.path.parent.mkdir(parents=True,exist_ok=True)
            with closing(self.connect()) as c,c:
                c.executemany('INSERT OR IGNORE INTO stock_names VALUES (?,?,?,?,?)',[(code,name,self.confidence.get(code,.92),'verified-ocr',time.time()) for code,name in self.names.items() if self.valid(code,name)])
            temporary=self.path.with_suffix('.tmp');temporary.write_text(json.dumps(self.names,ensure_ascii=False),encoding='utf-8');temporary.replace(self.path)
            learning=self.path.with_name(self.path.stem+'-learning.json');self.pending=dict(list(self.pending.items())[-2000:])
            temporary=learning.with_suffix('.tmp');temporary.write_text(json.dumps(self.pending,ensure_ascii=False),encoding='utf-8');temporary.replace(learning)
            self.dirty=False;self.last_error=""
        except (OSError,sqlite3.Error) as exc:self.last_error=str(exc)


def scan_numeric_batch(image,bounds,read,names,memory=None,focus=None,defer_names=False,anchors_ready=None):
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
    logical=[];anchors=[];code_scores={}
    def append(field,box,text,confidence):
        text=unicodedata.normalize('NFKC',str(text)).strip()
        if field=='code':
            text=re.sub(r'^[^0-9]+','',text)
            if re.fullmatch(r'[0-9 ]{4,9}',text):text=text.replace(' ','')
        y=sum(p[1] for p in box)/4;h=max(p[1] for p in box)-min(p[1] for p in box)
        x=50+FIELDS.index(field)*100
        logical.append(([[x-10,y-h/2],[x+10,y-h/2],[x+10,y+h/2],[x-10,y+h/2]],text,confidence))
        if field=='code' and confidence>=.70 and re.fullmatch(r'[0-9]{4,12}[A-Za-z]{0,2}',text):
            anchors.append((text,y,max(h,8)));code_scores[text]=confidence
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
    if anchors_ready:anchors_ready(anchors,code_scores)
    unknown=[a for a in anchors if re.fullmatch(r'[0-9]{4}',a[0]) and a[0] not in names.names]
    changed=False
    if unknown and not defer_names:
        left,right=bounds['name']
        crop=image.crop((round(left*image.width),0,round(right*image.width),image.height))
        if focus:focus(['name'],'補讀未知股號的股名')
        if len(unknown)*2<=len(anchors):
            sections=[];height=8
            for code,y,h in unknown:
                top=max(0,int(y-h*.70));bottom=min(crop.height,int(y+h*.70)+1)
                sections.append((height,height+bottom-top,top,crop.crop((0,top,crop.width,bottom))))
                height+=bottom-top+16
            name_sheet=Image.new('RGB',(crop.width,height),background)
            for start,end,top,piece in sections:name_sheet.paste(piece,(0,start))
            tokens=[]
            for box,text,confidence in read(name_sheet):
                y=sum(p[1] for p in box)/4
                matching=[s for s in sections if s[0]<=y<s[1]]
                if len(matching)==1:
                    start,end,top,_=matching[0]
                    tokens.append(([[x,yy-start+top] for x,yy in box],text,confidence))
        else:tokens=read(crop)
        zoom_budget=6
        for code,y,h in unknown:
            if code_scores.get(code,0)<.92:continue
            top=max(0,int(y-h*.70));bottom=min(crop.height,int(y+h*.70)+1)
            row=crop.crop((0,top,crop.width,bottom))
            fingerprint=hashlib.sha256(row.tobytes()).hexdigest()
            near=[t for t in tokens if abs(sum(p[1] for p in t[0])/4-y)<h*.55]
            if len(near)==1:
                box,text,confidence=near[0];text=unicodedata.normalize('NFKC',str(text)).replace(' ','').strip()
                changed=names.consider(code,text,confidence,'raw:'+fingerprint) or changed
            if code in names.names:continue
            top=max(0,int(y-h*.70));bottom=min(crop.height,int(y+h*.70)+1)
            if bottom<=top or zoom_budget<=0 or not names.ready_to_zoom(code,fingerprint):continue
            zoom_budget-=1
            row=crop.crop((0,top,crop.width,bottom))
            for scale in (2,3):
                if focus:focus(['name'],f'股號{code}：股名放大{scale}倍複查',(top,bottom))
                enlarged=row.resize((row.width*scale,row.height*scale),Image.Resampling.LANCZOS)
                candidates=[]
                for _,text,confidence in read(enlarged):
                    text=unicodedata.normalize('NFKC',str(text)).replace(' ','').strip()
                    if confidence>=.85 and names.valid(code,text):candidates.append((text,confidence))
                if len(candidates)==1:
                    text,confidence=candidates[0]
                    changed=names.consider(code,text,confidence,f'zoom{scale}:'+fingerprint) or changed
                    if code in names.names:break
    for code,y,h in anchors:
        name=names.names.get(code)
        if name:
            append('name',[[0,y-h/2],[10,y-h/2],[10,y+h/2],[0,y+h/2]],name,1)
            if code not in {a[0] for a in unknown}:names.reused+=1
    if changed or names.dirty:names.save()
    return parse_ocr(logical,600,[100*(50+i*100)/600 for i in range(6)],time.time())


def learn_mapped_names(tokens,names,proof):
    """Learn identity pairs from row-aligned fallback OCR, retaining actual confidence."""
    cells=[]
    for box,text,confidence in tokens:
        x=sum(p[0] for p in box)/4;y=sum(p[1] for p in box)/4
        field=round((x-50)/100)
        text=unicodedata.normalize('NFKC',str(text)).replace(' ','').strip()
        cells.append((field,y,max(8,max(p[1] for p in box)-min(p[1] for p in box)),text,confidence))
    for field,y,h,code,confidence in cells:
        if field!=0 or confidence<.92 or not re.fullmatch(r'[0-9]{4}',code):continue
        near=[c for c in cells if c[0]==1 and abs(c[1]-y)<h*.55]
        if len(near)==1:names.consider(code,near[0][3],near[0][4],proof)
    if names.dirty:names.save()
