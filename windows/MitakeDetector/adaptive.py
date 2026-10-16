"""Bounded local experience: no market data or blind click coordinates retained."""
import json
from pathlib import Path


class Experience:
    def __init__(self, path=None, layout=None, enabled=True):
        self.path=Path(path) if path else None
        self.key=json.dumps(layout,sort_keys=True,separators=(',',':'))
        self.enabled=enabled
        self.data={'version':1,'layout':self.key,'ranks':{}}
        if self.path and enabled:
            try:
                value=json.loads(self.path.read_text(encoding='utf-8'))
                if value.get('version')==1 and value.get('layout')==self.key and isinstance(value.get('ranks'),dict):
                    # Whitelist and clamp every value: a broken profile cannot drive inputs.
                    for name,entry in value['ranks'].items():
                        if not isinstance(entry,dict):continue
                        page=entry.get('page');wins=entry.get('wins',0)
                        if type(page) is int and 0<=page<60 and type(wins) is int and wins>=0:
                            self.data['ranks'][name]={'page':page,'wins':min(wins,20)}
            except (OSError,ValueError,TypeError,AttributeError):pass

    def hint(self,name):
        return self.data['ranks'].get(name,{}).get('page') if self.enabled else None

    def record(self,name,page):
        if not self.enabled:return
        previous=self.data['ranks'].get(name,{})
        wins=previous.get('wins',0) if previous.get('page')==page else 0
        self.data['ranks'][name]={'page':page,'wins':min(20,wins)}
        self.save()

    def observed(self,name,valid):
        if not self.enabled:return
        entry=self.data['ranks'].get(name)
        if entry is not None:
            entry['wins']=min(20,entry['wins']+1) if valid else 0
            self.save()

    def forget(self,name):
        self.data['ranks'].pop(name,None)
        self.save()

    def wait(self,name,maximum):
        """Only successful verified reads reduce user-configured maximum dwell."""
        if not self.enabled:return maximum
        wins=self.data['ranks'].get(name,{}).get('wins',0)
        return min(maximum,max(1.0,maximum*(.65**wins)))

    def save(self):
        if not self.path or not self.enabled:return
        try:
            self.path.parent.mkdir(parents=True,exist_ok=True)
            temporary=self.path.with_suffix('.tmp')
            temporary.write_text(json.dumps(self.data,ensure_ascii=False),encoding='utf-8')
            temporary.replace(self.path)
        except OSError:pass  # An unwritable profile must never stop scanning.
