import hashlib
import json
import sys
import time
import urllib.request
from pathlib import Path
from updater import REPO, FEED_URL, validate_manifest, request

local=validate_manifest(json.loads(Path('release-output/mitake-update.json').read_text('utf-8')))
mode=sys.argv[1]
url=FEED_URL if mode=='stable' else f"https://github.com/{REPO}/releases/download/mitake-v{local['version']}/mitake-update.json"
for attempt in range(5):
    try:
        with urllib.request.urlopen(request(url+'?verify='+str(time.time_ns())),timeout=40) as response:
            public=validate_manifest(json.load(response))
        assert public==local,'Published manifest mismatch'
        digest=hashlib.sha256();size=0
        with urllib.request.urlopen(request(public['url']),timeout=60) as response:
            while chunk:=response.read(1024*1024):
                digest.update(chunk);size+=len(chunk)
        assert size==public['size'] and digest.hexdigest()==public['sha256'],'Public installer hash mismatch'
        print(f"Verified public {mode} update feed and installer: {public['version']}, {size} bytes")
        break
    except Exception:
        if attempt==4:raise
        time.sleep(5)
