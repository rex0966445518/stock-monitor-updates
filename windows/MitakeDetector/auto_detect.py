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
        if token[2]<.65 or l>w*.20 or t>h*.13:continue
        text=normalized(token[1])
        score=max(difflib.SequenceMatcher(None,text,normalized(n)).ratio() for n in RANKINGS)
        if score<.70:continue
        cy=(t+b)/2;cx=(l+r)/2
        edges=[]
        for y in range(max(0,int(t)-20),min(h,int(b)+20)):
            row=a[y,:min(w,int(w*.35))]
            mask=(row.max(1)-row.min(1)<55)&(row.mean(1)>65)&(row.mean(1)<190)
            for x0,x1 in runs(np.flatnonzero(mask)):
                if x0<cx<x1 and r-l+12<x1-x0<min(420,w*.35):edges.append((y,x0,x1))
        above=[e for e in edges if e[0]<cy-3];below=[e for e in edges if e[0]>cy+3]
        pairs=[(up,dn) for up in above for dn in below if abs(up[1]-dn[1])<6 and abs(up[2]-dn[2])<6 and 15<dn[0]-up[0]<80]
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


def detect_layout(image,ocr):
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
    frame=combo_frame(image,tokens)
    l,t,r,b=frame
    combo=[l/w,t/h,(r-l)/w,(b-t)/h]
    table=[0,yb/h,1,max(.01,(h-yb-12)/h)]
    return dict(combo=combo,table=table,column_bounds=bounds,headers=headers)


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
    if max(xs)-min(xs)>40:raise ValueError('選單文字位置不一致，請使用手動框選。')
    l=max(0,left+min(bbox(t)[0] for t in items)-10)
    r=min(w,left+max(bbox(t)[2] for t in items)+55)
    t=max(top,top+min(bbox(t)[1] for t in items)-6)
    b=min(h-8,top+max(bbox(t)[3] for t in items)+8)
    return [l/w,t/h,(r-l)/w,(b-t)/h]
