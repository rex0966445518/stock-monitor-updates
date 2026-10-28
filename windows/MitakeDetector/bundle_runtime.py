"""Bundle the licensed VS redistributable CRT beside the frozen runtime."""
import json
import os
from pathlib import Path
import shutil
import subprocess


def prepare():
    target=Path('dist/MitakeDetector/_internal')
    if not (target/'python311.dll').is_file():raise RuntimeError('Missing bundled Python DLL')
    vswhere=Path(os.environ['ProgramFiles(x86)'])/'Microsoft Visual Studio/Installer/vswhere.exe'
    installations=json.loads(subprocess.check_output([str(vswhere),'-products','*','-format','json'],encoding='utf-8-sig'))
    choices=[]
    for install in installations:
        choices.extend((Path(install['installationPath'])/'VC/Redist/MSVC').glob('*/x64/Microsoft.VC*.CRT'))
    if not choices:raise RuntimeError('Visual Studio redistributable CRT not found')
    source=max(choices,key=lambda p:tuple(int(n) for n in p.parents[1].name.split('.')))
    copied=[]
    for file in source.glob('*.dll'):
        shutil.copy2(file,target/file.name);copied.append(file.name)
    for name in ('vcruntime140.dll','vcruntime140_1.dll','msvcp140.dll'):
        if not (target/name).is_file():raise RuntimeError('Missing runtime dependency: '+name)
    Path('release-output').mkdir(exist_ok=True)
    Path('release-output/runtime-manifest.json').write_text(json.dumps({'layout':'onedir','python':'_internal/python311.dll','crt_source':str(source),'crt_files':copied},indent=2),encoding='utf-8')
    print('Fixed installation runtime verified: '+', '.join(copied))


if __name__=='__main__':prepare()
