"""Bounded OCR recovery utilities; coordinates and sample times are preserved."""
from contextlib import contextmanager

@contextmanager
def fresh_ocr(app):
    previous=getattr(app,'force_fresh_ocr',False);app.force_fresh_ocr=True
    try:yield
    finally:app.force_fresh_ocr=previous

class FreshMemory:
    def __init__(self,memory):self.memory=memory
    def lookup(self,key):return None
    def record(self,*args,**kwargs):return self.memory.record(*args,**kwargs)

def tiled_tokens(image,read,status=lambda *args:None,width=640,height=256,overlap=32):
    """Overlap protects characters at seams; ownership avoids duplicated tokens."""
    tokens=[]
    for top in range(0,image.height,height):
        for left in range(0,image.width,width):
            right=min(image.width,left+width);bottom=min(image.height,top+height)
            box=(max(0,left-overlap),max(0,top-overlap),min(image.width,right+overlap),min(image.height,bottom+overlap))
            status(box)
            for points,text,confidence in read(image.crop(box)):
                mapped=[[x+box[0],y+box[1]] for x,y in points]
                cx=sum(p[0] for p in mapped)/4;cy=sum(p[1] for p in mapped)/4
                if left<=cx<right and top<=cy<bottom:tokens.append((mapped,text,confidence))
    return tokens

def tiled_numeric(image,bounds,read,names,memory=None,status=lambda *args:None,height=256,overlap=32):
    from fast_quotes import scan_numeric_batch
    found={};anchors_all=[];scores_all={};bad=0
    for top in range(0,image.height,height):
        bottom=min(image.height,top+height);a=max(0,top-overlap);b=min(image.height,bottom+overlap)
        band=image.crop((0,a,image.width,b));anchors=[];scores={}
        status((0,a,image.width,b))
        def ready(items,confidence):anchors.extend(items);scores.update(confidence)
        rows,rejected=scan_numeric_batch(band,bounds,read,names,memory=memory,defer_names=True,anchors_ready=ready)
        allowed={code for code,y,h in anchors if top<=y+a<bottom}
        anchors_all.extend((code,y+a,h) for code,y,h in anchors if code in allowed)
        scores_all.update({code:confidence for code,confidence in scores.items() if code in allowed})
        for q in rows:
            if q.code in allowed:found[q.code]=q
        bad+=rejected
    return list(found.values()),bad,anchors_all,scores_all


def zoom_tokens(image,read,scale=2):
    """Enlarge small header glyphs, returning coordinates in original pixels."""
    from PIL import Image,ImageOps
    image=ImageOps.autocontrast(image.convert('RGB'))
    enlarged=image.resize((max(1,round(image.width*scale)),max(1,round(image.height*scale))),Image.Resampling.LANCZOS)
    return [([[x/scale,y/scale] for x,y in box],text,score) for box,text,score in read(enlarged)]
