"""Discover a ranking layout from verified text and visible frame edges."""
import difflib
import re
import unicodedata
from autoswitch import RANKINGS, normalized


def bbox(token):
    b=token[0]
    return min(p[0] for p in b),min(p[1] for p in b),max(p[0] for p in b),max(p[1] for p in b)


def runs(indices,gap=1):
    result=[]
    for x in indices:
        if not result or x-result[-1][1]>gap:result.append([int(x),int(x)])
        else:result[-1][1]=int(x)
    return result


def combo_frame(image,tokens):
    import numpy as np
    a=np.asarray(image.convert('RGB')).astype(int);h,w=a.shape[:2]
    candidates=[]
    for token in tokens:
        l,t,r,b=bbox(token)
        if token[2]<.65 or l>w*.35 or t>h*.45:continue
        unit=max(.5,min(4,(b-t)/18));pad=max(10,round(20*unit))
        text=normalized(token[1])
        score=max(difflib.SequenceMatcher(None,text,normalized(n)).ratio() for n in RANKINGS)
        if score<.70:continue
        cy=(t+b)/2;cx=(l+r)/2
        edges=[]
        for y in range(max(0,int(t)-pad),min(h,int(b)+pad)):
            row=a[y,:min(w,int(w*.60))]
            mask=(row.max(1)-row.min(1)<55)&(row.mean(1)>65)&(row.mean(1)<190)
            for x0,x1 in runs(np.flatnonzero(mask)):
                if x0<cx<x1 and r-l+max(4,6*unit)<x1-x0<min(420*unit,w*.60):edges.append((y,x0,x1))
        above=[e for e in edges if e[0]<cy-3];below=[e for e in edges if e[0]>cy+3]
        pairs=[(up,dn) for up in above for dn in below if abs(up[1]-dn[1])<6 and abs(up[2]-dn[2])<6 and max(8,10*unit)<dn[0]-up[0]<80*unit]
        if pairs:
            up,dn=min(pairs,key=lambda pair:pair[1][0]-pair[0][0])
            candidates.append((min(up[1],dn[1])-1,up[0]-1,max(up[2],dn[2])+2,dn[0]+2))
    if len(candidates)!=1:raise ValueError('無法唯一辨識左上角排行下拉框，請保持熱門排行完整顯示，或使用手動框選。')
    return candidates[0]


ALIASES={
 'code':{'股號','股号','代號','代码'},'name':{'名稱','名称','股名','名'},
 'price':{'成交','成交價','成交价'},'gain':{'幅度','漲幅','涨幅'},
 'volume':{'成交量','總量','总量'},'turnover':{'週轉率','周轉率','周转率','換手率','换手率','轉率','转率','调轉率','调转率'}
}
def clean(text):return re.sub(r'[^\u3400-\u9fffA-Za-z0-9]','',unicodedata.normalize('NFKC',text))


def _detect_layout_pixels(image,ocr,require_combo=True):
    import numpy as np
    w,h=image.size
    top=image.crop((0,0,w,min(h,max(150,int(h*.18)))))
    tokens=ocr(top)
    code=[t for t in tokens if clean(t[1]) in ALIASES['code'] and t[2]>=.65]
    if len(code)!=1:raise ValueError('找不到唯一的「股號」表頭，請顯示股號欄並保持表頭可見。')
    _,yt,_,yb=bbox(code[0]);yt=max(0,int(yt));yb=min(h,int(yb)+1)
    a=np.asarray(image.convert('RGB'))[yt:yb].astype(int)
    mask=(a.max(2)-a.min(2)<100)&(a.mean(2)>65)
    spans=runs(np.flatnonzero(mask.sum(0)>=2),gap=max(6,int((yb-yt)*.30)))
    spans=[(l,r+1) for l,r in spans if r-l>=8 and r-l<w*.25]
    found={};headers=[]
    for l,r in spans:
        crop=image.crop((max(0,l-3),max(0,yt-2),min(w,r+3),min(h,yb+2)))
        text=clean(''.join(t[1] for t in ocr(crop) if t[2]>=.60))
        headers.append((l,r,text))
        for field,aliases in ALIASES.items():
            if text in aliases:
                if field in found:raise ValueError('表頭重複：'+field)
                found[field]=(l,r)
    missing=set(ALIASES)-set(found)
    if missing:raise ValueError('表頭辨識不完整：'+','.join(sorted(missing))+'；請加寬欄位或使用手動校正。')
    bounds={}
    for field,(l,r) in found.items():
        index=next(i for i,s in enumerate(spans) if s==(l,r))
        left=(spans[index-1][1]+l)/2 if index else 0
        right=(r+spans[index+1][0])/2 if index+1<len(spans) else w
        # Quotes are right-aligned; allow the header's right gap but keep the code away from name icons.
        if field=='code':left=max(left,l-18)
        bounds[field]=[max(0,left/w),min(1,right/w)]
    combo=None
    if require_combo:
        l,t,r,b=combo_frame(image,tokens)
        combo=[l/w,t/h,(r-l)/w,(b-t)/h]
    table=[0,yb/h,1,max(.01,(h-yb-12)/h)]
    return dict(combo=combo,table=table,column_bounds=bounds,headers=headers)


def _layout_from_tokens(image,tokens,require_combo):
    """Require all six unique labels on one row, independent of font pixels."""
    w,h=image.size
    usable=[t for t in tokens if t[2]>=.65 and bbox(t)[3]>bbox(t)[1]]
    candidates=[]
    for code in [t for t in usable if clean(t[1]) in ALIASES['code']]:
        _,ct,_,cb=bbox(code);cy=(ct+cb)/2;ch=cb-ct
        row=[t for t in usable if abs((bbox(t)[1]+bbox(t)[3])/2-cy)<=max(ch,bbox(t)[3]-bbox(t)[1])*.55]
        found={}
        for field,aliases in ALIASES.items():
            matches=[t for t in row if clean(t[1]) in aliases]
            if len(matches)!=1:break
            found[field]=bbox(matches[0])
        if len(found)!=len(ALIASES):continue
        ordered=sorted(found.items(),key=lambda pair:pair[1][0])
        if any(a[1][2]>=b[1][0] for a,b in zip(ordered,ordered[1:])):continue
        # Extra visible columns also delimit fields; do not absorb their values.
        spans=sorted((bbox(t)[0],bbox(t)[2],clean(t[1])) for t in row)
        if any(a[1]>b[0] for a,b in zip(spans,spans[1:])):continue
        bounds={}
        for field,(l,t,r,b) in found.items():
            i=next(i for i,v in enumerate(spans) if v[:2]==(l,r))
            left=(spans[i-1][1]+l)/2 if i else 0
            right=(r+spans[i+1][0])/2 if i+1<len(spans) else w
            if field=='code':left=max(left,l-ch)
            bounds[field]=[max(0,left/w),min(1,right/w)]
        bottom=max(v[3] for v in found.values())+max(2,round(ch*.18))
        if bottom>=h-20:continue
        combo=None
        if require_combo:
            try:
                l,t,r,b=combo_frame(image,tokens);combo=[l/w,t/h,(r-l)/w,(b-t)/h]
            except ValueError:continue
        candidates.append(dict(combo=combo,table=[0,bottom/h,1,(h-bottom-max(4,round(ch*.5)))/h],
                               column_bounds=bounds,headers=spans))
    if len(candidates)!=1:raise ValueError('未找到唯一且完整的六欄表頭；請保持股號、股名、成交價、漲幅、成交量、週轉率完整可見。')
    return candidates[0]


def detect_layout(image,ocr,require_combo=True):
    try:return _detect_layout_pixels(image,ocr,require_combo)
    except ValueError:
        # Laptop DPI/titlebars can put headers below the old top-18% strip.
        height=min(image.height,max(240,round(image.height*.55)))
        return _layout_from_tokens(image,ocr(image.crop((0,0,image.width,height))),require_combo)


def detect_menu(image,combo,ocr):
    w,h=image.size
    x,y,cw,ch=combo
    left=max(0,int(x*w)-5);top=int((y+ch)*h)
    right=min(w,left+max(320,int(cw*w*2.0)))
    bottom=min(h-8,top+int(h*.85))
    tokens=ocr(image.crop((left,top,right,bottom)))
    items=[t for t in tokens if t[2]>=.65 and normalized(t[1]) in {normalized(n) for n in RANKINGS}]
    if len(items)<3:raise ValueError('展開選單後未辨識到至少三個排行名稱，已停止自動設定。')
    xs=[bbox(t)[0] for t in items]
    if max(xs)-min(xs)>max(40,max(bbox(t)[3]-bbox(t)[1] for t in items)*2):raise ValueError('選單文字位置不一致，請使用手動框選。')
    l=max(0,left+min(bbox(t)[0] for t in items)-10)
    r=min(w,left+max(bbox(t)[2] for t in items)+55)
    t=max(top,top+min(bbox(t)[1] for t in items)-6)
    b=min(h-8,top+max(bbox(t)[3] for t in items)+8)
    return [l/w,t/h,(r-l)/w,(b-t)/h]


def cached_layout(image,ocr,path,configured_bounds,configured_table,detector=None):
    """Reuse verified geometry after checking only the two end header patches."""
    import hashlib
    import json
    from pathlib import Path
    path=Path(path)
    def signature(box):
        return hashlib.sha256(image.crop(tuple(box)).convert('RGB').tobytes()).hexdigest()
    try:
        saved=json.loads(path.read_text(encoding='utf-8'))
        layout=saved['layout'];ends=saved['ends']
        if (saved['version']==1 and saved['size']==list(image.size)
                and layout['column_bounds']==configured_bounds and layout['table']==configured_table
                and len(ends)==2 and all(signature(e['box'])==e['hash'] for e in ends)):
            return layout,True
    except (OSError,ValueError,KeyError,TypeError):pass
    layout=detector(image) if detector else detect_layout(image,ocr)
    headers=sorted(layout['headers'],key=lambda x:x[0])
    w,h=image.size;y=int(layout['table'][1]*h)
    # Include the full header height and its surrounding position, never live quote rows.
    top=max(0,y-max(32,int(h*.06)))
    ends=[]
    for left,right,_ in (headers[0],headers[-1]):
        box=[max(0,int(left)-3),top,min(w,int(right)+3),y]
        ends.append(dict(box=box,hash=signature(box)))
    value=dict(version=1,size=list(image.size),layout=layout,ends=ends)
    try:
        path.parent.mkdir(parents=True,exist_ok=True)
        temporary=path.with_suffix('.tmp');temporary.write_text(json.dumps(value,ensure_ascii=False),encoding='utf-8');temporary.replace(path)
    except OSError:pass
    return layout,False
