from pathlib import Path
import subprocess,sys,zipfile,json,hashlib
root=Path(__file__).resolve().parents[1]
out=root/'dist';out.mkdir(exist_ok=True)
version=json.loads((root/'version.json').read_text(encoding='utf-8-sig'))['version']
def cc(args):
 subprocess.run([sys.executable,'-m','ziglang','cc','-target','x86_64-windows-gnu','-O2','-municode','-Wl,--subsystem,windows',str(root/'build/launcher.c'),'-luser32',*args],check=True,cwd=root)
cc(['-o',str(out/'NineMonitor.exe')])
files=['Launch.ps1','Monitor.ps1','Core.psm1','OcrWorker.ps1','Native.cs','UpdateWorker.ps1','Uninstall.ps1','version.json','README.txt','Install.ps1','SetupShell.cs']
archive=out/'payload.zip'
with zipfile.ZipFile(archive,'w',zipfile.ZIP_DEFLATED) as z:
 for name in files:z.write(root/name,name)
 z.write(out/'NineMonitor.exe','NineMonitor.exe')
def header(path,name,data):
 path.write_text('static const unsigned char '+name+'[] = {\n'+','.join(str(x) for x in data)+'\n};\n',encoding='ascii')
header(root/'build/payload.h','payload_bytes',archive.read_bytes())
header(root/'build/bootstrap.h','bootstrap_bytes',(root/'build/bootstrap.ps1').read_bytes())
cc(['-DSETUP_BUILD','-o',str(out/'NineMonitor-Setup.exe')])
setup=out/'NineMonitor-Setup.exe'
sha=hashlib.sha256(setup.read_bytes()).hexdigest()
manifest={'app':'NineMonitor-ThreeColor','version':version,'sha256':sha,'url':f'https://github.com/rex0966445518/stock-monitor-updates/releases/download/ninemonitor-v{version}/NineMonitor-Setup.exe'}
(out/'ninemonitor-update.json').write_text(json.dumps(manifest,indent=2)+'\n',encoding='utf-8')
(out/'SHA256SUMS.txt').write_text(sha+'  NineMonitor-Setup.exe\n')
print(json.dumps({'version':version,'size':setup.stat().st_size,'sha256':sha}))
