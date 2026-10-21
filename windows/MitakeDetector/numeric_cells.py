"""Exact-pixel cell reuse for overlapping numeric viewports; no value prediction."""
from collections import OrderedDict
import hashlib

def row_bands(image,bounds):
    import numpy as np
    from statistics import median
    l,r=bounds['code'];a=np.asarray(image.convert('RGB'))[:,round(l*image.width):round(r*image.width)].astype('int16')
    if a.shape[1]<16:return None
    background=np.median(a,axis=1,keepdims=True)
    ink=(np.max(np.abs(a-background),axis=2)>55).sum(axis=1)
    alternate=(np.max(np.abs(a-np.median(a,axis=0,keepdims=True)),axis=2)>55).sum(axis=1)
    ink=np.where(ink>=a.shape[1]*.8,alternate,ink)
    active=[int(y) for y in np.flatnonzero((ink>=max(3,a.shape[1]*.035))&(ink<a.shape[1]*.8))]
    groups=[]
    for y in active:
        if not groups or y-groups[-1][-1]>3:groups.append([y])
        else:groups[-1].append(y)
    groups=[g for g in groups if 6<=g[-1]-g[0]+1<=90]
    if not 2<=len(groups)<=100:return None
    centers=[(g[0]+g[-1])/2 for g in groups]
    pitch=median(b-a for a,b in zip(centers,centers[1:]))
    if pitch<10 or any(b-a<pitch*.6 or b-a>pitch*1.6 for a,b in zip(centers,centers[1:])):return None
    edges=[0]+[round((a+b)/2) for a,b in zip(centers,centers[1:])]+[image.height]
    return list(zip(edges,edges[1:]))

def scan_cells(image,bounds,read,names,cache,memory=None,force=False,focus=None,anchors_ready=None,recognize_cells=None,focus_cells=None):
    from PIL import Image
    from fast_quotes import scan_numeric_batch
    import re,unicodedata
    bands=row_bands(image,bounds)
    if not bands:return None
    fields=['code','price','gain','volume','turnover']
    def make(field,top,bottom):
        left,right=[round(v*image.width) for v in bounds[field]]
        if right-left<5:raise ValueError('數字欄位寬度不足')
        crop=image.crop((left,top,right,bottom)).convert('RGB')
        key=f'cell-v2:{field}:{crop.width}x{crop.height}:'+hashlib.sha256(crop.tobytes()).hexdigest()
        return dict(field=field,top=top,bottom=bottom,image=crop,key=key,tokens=None)
    metrics=dict(reused=0,changed=0,total=0,skipped=0)
    def resolve(cells):
        if not cells:return
        known={} if force else memory.lookup_many([c['key'] for c in cells]) if memory and hasattr(memory,'lookup_many') else {}
        for c in cells:
            if not force:c['tokens']=cache.get(c['key'],known.get(c['key']))
        missing=[c for c in cells if c['tokens'] is None]
        metrics['reused']+=len(cells)-len(missing);metrics['changed']+=len(missing);metrics['total']+=len(cells)
        # Row/column geometry already identifies a single text line: skip detection.
        direct=[]
        if missing and recognize_cells and not force:
            if focus_cells:
                results=recognize_cells([c['image'] for c in missing],on_batch=lambda start,end:focus_cells(missing[start:end],'數字格直接辨識'))
            else:
                if focus:focus(list(dict.fromkeys(c['field'] for c in missing)),f'數字格直接辨識｜{len(missing)} 格')
                results=recognize_cells([c['image'] for c in missing])
            if len(results)==len(missing):
                for c,(text,confidence) in zip(missing,results):
                    text=unicodedata.normalize('NFKC',str(text)).replace(' ','').strip()
                    pattern=r'[0-9]{4,12}[A-Za-z]{0,2}' if c['field']=='code' else r'[+-]?[0-9]+(?:,[0-9]{3})*(?:\.[0-9]+)?%?'
                    if confidence>=.93 and re.fullmatch(pattern,text):
                        w,h=c['image'].size
                        c['tokens']=[([[0,0],[w,0],[w,h],[0,h]],text,float(confidence))]
                        direct.append(c)
            missing=[c for c in missing if c['tokens'] is None]
        for c in direct:
            cache[c['key']]=c['tokens'];cache.move_to_end(c['key'])
        while len(cache)>4096:cache.popitem(last=False)
        if memory and hasattr(memory,'record_many') and direct:
            memory.record_many([(c['key'],c['tokens'],0,'數字格直接辨識') for c in direct])
        x=12;y=12;line=0;width=max(768,max(c['image'].width for c in cells)+24)
        for c in missing:
            crop=c['image']
            if x+crop.width+12>width:x=12;y+=line+20;line=0
            c['box']=(x,y,x+crop.width,y+crop.height);x+=crop.width+20;line=max(line,crop.height)
        if not missing:return
        sheet=Image.new('RGB',(width,y+line+12),image.convert('RGB').getpixel((0,0)))
        for c in missing:sheet.paste(c['image'],c['box'][:2]);c['tokens']=[]
        if focus_cells:focus_cells(missing,'低信心數字格複查')
        elif focus:focus(list(dict.fromkeys(c['field'] for c in cells)),f'股號先行｜變動數字格 {len(missing)}｜重用 {metrics["reused"]} 格')
        for box,text,confidence in read(sheet):
            l=min(p[0] for p in box);t=min(p[1] for p in box);r=max(p[0] for p in box);b=max(p[1] for p in box)
            owners=[c for c in missing if c['box'][0]<=l and r<=c['box'][2] and c['box'][1]<=t and b<=c['box'][3]]
            if len(owners)!=1:continue
            c=owners[0];cx,cy,_,_=c['box']
            c['tokens'].append(([[px-cx,py-cy] for px,py in box],text,confidence))
        learned=[]
        for c in missing:
            tokens=c['tokens']
            if tokens and min(t[2] for t in tokens)>=.93:
                cache[c['key']]=tokens;cache.move_to_end(c['key'])
            learned.append((c['key'],tokens,0,'變動數字格'))
        while len(cache)>4096:cache.popitem(last=False)
        if memory and hasattr(memory,'record_many'):memory.record_many(learned)
    code_cells=[make('code',top,bottom) for top,bottom in bands]
    resolve(code_cells)
    accepted=[]
    for c in code_cells:
        tokens=c['tokens'] or []
        if len(tokens)!=1 or tokens[0][2]<.93:return None
        code=unicodedata.normalize('NFKC',str(tokens[0][1])).replace(' ','').strip()
        code=re.sub(r'^[^0-9]+','',code)
        if not re.fullmatch(r'[0-9]{4,12}[A-Za-z]{0,2}',code):return None
        if len(code)==4:accepted.append(c)
        else:metrics['skipped']+=1
    numeric=[make(field,c['top'],c['bottom']) for c in accepted for field in fields[1:]]
    resolve(numeric)
    by_field={f:[] for f in fields}
    for c in code_cells+numeric:
        by_field[c['field']].extend(([[x,y+c['top']] for x,y in box],text,confidence) for box,text,confidence in c['tokens'])
    class Columns:
        def lookup(self,key):return by_field[key.split(':')[1]]
        def record(self,*args):pass
    rows,bad=scan_numeric_batch(image,bounds,lambda im:[],names,memory=Columns(),defer_names=True,anchors_ready=anchors_ready)
    if bad or len(rows)!=len(accepted):return None
    return rows,bad,metrics
