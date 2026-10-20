"""Opt-in, read-only companion bridge for LAN or private VPN, with bearer pairing."""
import copy
import datetime as dt
import ipaddress
import json
import secrets
import socket
import threading
import time
import uuid
from collections import deque
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlsplit,parse_qs

TAIL=ipaddress.ip_network('100.64.0.0/10')
def private_address(value):
    try:
        ip=ipaddress.ip_address(value)
        return ip.version==4 and (ip in TAIL or any(ip in ipaddress.ip_network(n) for n in ('10.0.0.0/8','172.16.0.0/12','192.168.0.0/16','127.0.0.0/8')))
    except ValueError:return False

def local_addresses():
    result=set()
    try:
        for item in socket.getaddrinfo(socket.gethostname(),None,socket.AF_INET):
            ip=item[4][0]
            if private_address(ip) and not ip.startswith('127.'):result.add(ip)
    except OSError:pass
    return sorted(result) or ['127.0.0.1']

class MobileBridge:
    def __init__(self,path,port=8765):
        self.path=Path(path);self.port=port;self.server=None;self.lock=threading.RLock()
        self.session=uuid.uuid4().hex;self.seq=0;self.events=deque(maxlen=300);self.rows={};self.last={};self.day=None
        self.active=False;self.status='等待電腦開始掃描';self.scan_time=0;self.last_seen=0
        try:self.token=json.loads(self.path.read_text('utf-8'))['token']
        except (OSError,ValueError,KeyError,TypeError):self.token=''
        if not isinstance(self.token,str) or len(self.token)!=48:self.token=secrets.token_hex(24)
    def save_token(self):
        self.path.parent.mkdir(parents=True,exist_ok=True)
        temp=self.path.with_suffix('.tmp');temp.write_text(json.dumps(dict(token=self.token)),'utf-8');temp.replace(self.path)
    def rotate(self):
        with self.lock:self.token=secrets.token_hex(24);self.save_token()
    def set_status(self,active,status):
        with self.lock:self.active=bool(active);self.status=str(status)[:500]
    def publish(self,rows,cfg,version,now=None):
        from candidate_ranking import assess
        now=time.time() if now is None else now
        day=dt.datetime.fromtimestamp(now,dt.timezone(dt.timedelta(hours=8))).date().isoformat()
        with self.lock:
            if self.day!=day:self.day=day;self.rows={};self.last={};self.events.clear()
            for q,ratio,reason,hit in rows:
                match=assess((q,ratio,reason,hit),cfg)
                if match['total']!=6:continue
                old=self.rows.get(q.code)
                if old and q.ts<=old['ts']:continue
                fresh=0<=now-q.ts<=cfg.get('guard_max_age',15)
                valid='盤尾緩漲未通過' not in reason and match['label']!='待複查' and not reason.startswith('引擎略過') and '已轉弱' not in reason
                count=match['count'];confirmed=bool(match['hit'])
                if count<5 and old is None:continue
                row=dict(code=q.code,name=q.name or (old or {}).get('name',''),price=q.price,gain=q.gain,volume=q.volume,
                    turnover=q.turnover,ratio=ratio,matched=count,total=6,confirmed=confirmed,ts=q.ts,reason=reason,
                    valid=valid,peak=max(count,(old or {}).get('peak',0)),source=cfg.get('source_rank',''),version=version)
                self.rows[q.code]=row;self.last_seen=now
                key=(count,confirmed);previous=self.last.get(q.code)
                if valid and fresh and count>=5 and (previous is None or key!=previous[0] and (count>previous[0][0] or confirmed and not previous[0][1]) or now-previous[1]>=max(60,cfg.get('cooldown',300))):
                    self.seq+=1;self.events.append(dict(row,seq=self.seq,created=now));self.last[q.code]=(key,now)
            if len(self.rows)>500:
                self.rows=dict(sorted(self.rows.items(),key=lambda p:p[1]['ts'],reverse=True)[:500])
    def snapshot(self,after=0,session=''):
        with self.lock:
            now=time.time()
            events=[dict(e) for e in self.events if (session!=self.session or e['seq']>after) and 0<=now-e['ts']<=15
                and self.rows.get(e['code'],{}).get('valid',False) and self.rows[e['code']]['matched']>=e['matched']]
            rows=sorted(self.rows.values(),key=lambda r:(-r['matched'],-r['ts'],r['code']))
            return copy.deepcopy(dict(protocol=1,session=self.session,seq=self.seq,server_time=now,day=self.day,
                active=self.active,status=self.status,last_seen=self.last_seen,rows=rows,events=events))
    def start(self,host='0.0.0.0'):
        if self.server:return
        self.save_token();bridge=self
        class Handler(BaseHTTPRequestHandler):
            def log_message(self,*args):pass
            def setup(self):super().setup();self.connection.settimeout(5)
            def do_GET(self):
                if not private_address(self.client_address[0]):self.send_error(403);return
                expected='Bearer '+bridge.token
                if not secrets.compare_digest(self.headers.get('Authorization','').encode('utf-8'),expected.encode('utf-8')):self.send_error(401);return
                request=urlsplit(self.path)
                if request.path!='/v1/snapshot':self.send_error(404);return
                try:
                    query=parse_qs(request.query);after=max(0,int(query.get('after',['0'])[0]));session=query.get('session',[''])[0][:64]
                    data=json.dumps(bridge.snapshot(after,session),ensure_ascii=False,allow_nan=False).encode()
                except (ValueError,TypeError):self.send_error(400);return
                self.send_response(200);self.send_header('Content-Type','application/json; charset=utf-8')
                self.send_header('Cache-Control','no-store');self.send_header('Content-Length',str(len(data)));self.end_headers();self.wfile.write(data)
        self.server=ThreadingHTTPServer((host,self.port),Handler);self.server.daemon_threads=True
        self.port=self.server.server_address[1]
        self.thread=threading.Thread(target=self.server.serve_forever,kwargs={'poll_interval':.1},daemon=True);self.thread.start()
    def close(self):
        server=self.server;self.server=None
        if server:server.shutdown();server.server_close()
