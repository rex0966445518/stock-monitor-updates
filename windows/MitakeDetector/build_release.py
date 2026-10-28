"""Generate the per-product update feed from the verified installer."""
import ast
import hashlib
import json
import os
from pathlib import Path
from updater import REPO, validate_manifest


def version():
    tree=ast.parse(Path('MitakeDetector.py').read_text('utf-8'))
    for node in tree.body:
        if isinstance(node,ast.Assign) and any(isinstance(t,ast.Name) and t.id=='VERSION' for t in node.targets):
            return ast.literal_eval(node.value)
    raise RuntimeError('VERSION missing')

if __name__=='__main__':
    v=version()
    out=Path('release-output');out.mkdir(exist_ok=True)
    installer=out/f'MitakeDetector-Setup-v{v}.exe'
    data=installer.read_bytes()
    assert data[:2]==b'MZ'
    notes=Path('RELEASE_NOTES.md').read_text('utf-8')
    manifest=dict(product='MitakeDetector',platform='windows-x64',channel='stable',version=v,size=len(data),sha256=hashlib.sha256(data).hexdigest(),url=f'https://github.com/{REPO}/releases/download/mitake-v{v}/{installer.name}',notes=notes)
    validate_manifest(manifest)
    (out/'mitake-update.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2),'utf-8')
    (out/'SHA256SUMS.txt').write_text(f"{manifest['sha256']}  {installer.name}\n",'utf-8')
    print(f'Manifest verified for v{v}: {len(data)} bytes')
