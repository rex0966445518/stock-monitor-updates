"""User-confirmed full-label corrections, scoped to ranking OCR."""
import json
import os
from pathlib import Path

class RankingAliases:
    def __init__(self,path=None):
        self.path=Path(path) if path else Path(os.environ.get('LOCALAPPDATA',str(Path.home())))/'MitakeDetector'/'ranking-ocr-aliases.json'
        try:self.mapping=json.loads(self.path.read_text(encoding='utf-8'))
        except (OSError,ValueError):self.mapping={}
        if not isinstance(self.mapping,dict):self.mapping={}
    def save(self,raw,target):
        from autoswitch import normalized,RANKINGS
        raw=raw.strip()
        if target not in RANKINGS or not raw or len(raw)>30:raise ValueError('請選擇辨識原文及正確排行')
        if normalized(raw) in {normalized(n) for n in RANKINGS if n!=target}:raise ValueError('不能把另一個有效排行改成此排行')
        if raw in self.mapping and self.mapping[raw]!=target:raise ValueError('此原文已有其他對應；請先刪除舊對應')
        updated=dict(self.mapping);updated[raw]=target
        self.path.parent.mkdir(parents=True,exist_ok=True)
        temp=self.path.with_suffix('.tmp');temp.write_text(json.dumps(updated,ensure_ascii=False,indent=2),encoding='utf-8');temp.replace(self.path)
        self.mapping=updated
    def remove(self,raw):
        updated=dict(self.mapping);updated.pop(raw,None)
        self.path.parent.mkdir(parents=True,exist_ok=True)
        temp=self.path.with_suffix('.tmp');temp.write_text(json.dumps(updated,ensure_ascii=False,indent=2),encoding='utf-8');temp.replace(self.path)
        self.mapping=updated
    def apply(self,tokens):
        from autoswitch import RANKINGS,normalized
        result=[]
        for box,raw,score in tokens or []:
            target=self.mapping.get(raw.strip())
            valid=target in RANKINGS and normalized(raw) not in {normalized(n) for n in RANKINGS if n!=target}
            result.append((box,target if valid else raw,score))
        return result

class RankingReader:
    def __init__(self,reader,aliases,observer=None):
        self.reader=reader;self.aliases=aliases;self.observer=observer
    def __call__(self,image):
        tokens=self.reader(image)
        if self.observer:self.observer(tokens or [])
        return self.aliases.apply(tokens)
    def __getattr__(self,name):return getattr(self.reader,name)
