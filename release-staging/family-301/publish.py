import pathlib,json,base64,zlib,hashlib,urllib.request,subprocess,os
root=pathlib.Path('release-staging/family-301')
recipe=json.loads((root/'recipe.json').read_text())
raw=zlib.decompress(base64.b64decode(''.join(p.read_text() for p in sorted(root.glob('payload-*.b64'))),validate=True))
bt=sorted((pathlib.Path(os.environ['ANDROID_HOME'])/'build-tools').glob('*/apksigner'))[-1].parent
pending=[]
for kind,entry in recipe.items():
 url='https://github.com/rex0966445518/stock-monitor-updates/releases/download/learning-island-family-v3.0.0/'+entry['base']
 with urllib.request.urlopen(url,timeout=120) as response: old=response.read()
 assert hashlib.sha256(old).hexdigest()==entry['baseSha256'],'Base hash mismatch'
 data=b''.join((old if op[0]=='base' else raw)[op[1]:op[1]+op[2]] for op in entry['ops'])
 info=entry['manifest']
 assert len(data)==info['bytes'] and hashlib.sha256(data).hexdigest()==info['sha256'],'APK hash mismatch'
 assert 0<len(data)<=52428800
 name=entry['file'];pathlib.Path(name).write_bytes(data)
 verified=subprocess.check_output([str(bt/'apksigner'),'verify','--print-certs',name],text=True)
 assert '19616a68907d87cd1c83c99db7c26cc1edbd67d7ce52498fa521953ddb8608e1' in verified.lower(),'Certificate mismatch'
 badging=subprocess.check_output([str(bt/'aapt'),'dump','badging',name],text=True).splitlines()[0]
 assert "name='"+info['packageName']+"'" in badging
 assert "versionCode='301'" in badging and "versionName='3.0.1'" in badging
 assert '/learning-island-family/' in info['apkUrl']
 pending.append((kind,info))
for kind,info in pending:pathlib.Path(kind+'.json').write_text(json.dumps(info,ensure_ascii=False,indent=2)+'\n')
print('Both signed family APKs verified; manifests ready.')
