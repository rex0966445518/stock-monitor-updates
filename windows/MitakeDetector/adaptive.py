"""Bounded local experience: no market data or blind click coordinates retained."""
import json
from pathlib import Path


class Experience:
    def __init__(self, path=None, layout=None, enabled=True):
        self.path=Path(path) if path else None
        self.key=json.dumps(layout,sort_keys=True,separators=(',',':'))
        self.enabled=enabled
        self.data={'version':1,'layout':self.key,'ranks':{},'scrolls':{},'numbers':{}}
        if self.path and enabled:
            try:
                value=json.loads(self.path.read_text(encoding='utf-8'))
                if value.get('version')==1 and value.get('layout')==self.key and isinstance(value.get('ranks'),dict):
                    if value.get('menu_wheel') in (40,120):self.data['menu_wheel']=value['menu_wheel']
                    if isinstance(value.get('menu_geometry'),dict):self.data['menu_geometry']=value['menu_geometry']
                    if isinstance(value.get('number_routes'),dict):
                        from menu_numbers import valid_route
                        self.data['number_routes']={name:r for name,r in value['number_routes'].items() if valid_route(r)}
                    # Whitelist and clamp every value: a broken profile cannot drive inputs.
                    for name,entry in value['ranks'].items():
                        if not isinstance(entry,dict):continue
                        page=entry.get('page');wins=entry.get('wins',0)
                        if type(page) is int and 0<=page<60 and type(wins) is int and wins>=0:
                            self.data['ranks'][name]={'page':page,'wins':min(wins,20)}
                            sig=entry.get('signature');point=entry.get('point')
                            if isinstance(sig,list) and len(sig)==2 and isinstance(sig[0],list) and len(sig[0])==2 and all(type(v) is int and 0<v<=32768 for v in sig[0]) and isinstance(sig[1],str) and len(sig[1])==64:
                                self.data['ranks'][name].update(signature=sig,point=point)
                                if self.position(name,sig) is None:
                                    self.data['ranks'][name].pop('point',None);self.data['ranks'][name].pop('signature',None)
                    for name,number in value.get('numbers',{}).items():
                        if type(number) is int and 1<=number<=500:self.data['numbers'][name]=number
                    for name,entry in value.get('scrolls',{}).items():
                        if isinstance(entry,dict) and type(entry.get('cap')) is int and 1<=entry['cap']<=12:
                            self.data['scrolls'][name]={'cap':entry['cap'],'recoveries':max(0,min(100000,int(entry.get('recoveries',0))))}
            except (OSError,ValueError,TypeError,AttributeError):pass

    def number(self,name):return self.data['numbers'].get(name) if self.enabled else None
    def record_number(self,name,number):
        if self.enabled and type(number) is int and 1<=number<=500:
            self.data['numbers'][name]=number;self.save()
    def forget_number(self,name):
        self.data['numbers'].pop(name,None);self.save()

    def scroll_cap(self,name):
        return self.data['scrolls'].get(name,{}).get('cap',12) if self.enabled else 12

    def record_scroll(self,name,cap,recovered):
        if not self.enabled:return
        entry=self.data['scrolls'].get(name,{'cap':12,'recoveries':0})
        value={'cap':max(1,min(12,int(cap))),'recoveries':entry['recoveries']+int(recovered)}
        if value!=entry:self.data['scrolls'][name]=value;self.save()

    def hint(self,name):
        return self.data['ranks'].get(name,{}).get('page') if self.enabled else None

    def position(self,name,signature):
        if not self.enabled or signature is None:return None
        entry=self.data['ranks'].get(name,{})
        point=entry.get('point')
        if entry.get('signature')!=signature or not isinstance(point,list) or len(point)!=2:return None
        if all(type(v) in (int,float) for v in point) and 0<=point[0]<signature[0][0] and 0<=point[1]<signature[0][1]:return tuple(point)
        return None

    def record(self,name,page,point=None,signature=None):
        if not self.enabled:return
        previous=self.data['ranks'].get(name,{})
        wins=previous.get('wins',0) if previous.get('page')==page else 0
        self.data['ranks'][name]={'page':page,'wins':min(20,wins)}
        if signature is not None and point is not None:
            self.data['ranks'][name].update(point=list(point),signature=signature)
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
