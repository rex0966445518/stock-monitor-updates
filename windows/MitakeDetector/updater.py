"""Dedicated MitakeDetector release channel; no credentials are embedded."""
import hashlib
import json
import os
import re
import subprocess
import sys
import time
import urllib.parse
import urllib.request
from pathlib import Path

REPO = 'rex0966445518/stock-monitor-updates'
PRODUCT = 'MitakeDetector'
FEED_URL = f'https://github.com/{REPO}/releases/download/mitake-latest/mitake-update.json'
MAX_INSTALLER_SIZE = 500 * 1024 * 1024


def installer_environment(environ=None):
    """The installer and restarted app must outlive the old frozen process."""
    source=os.environ if environ is None else environ
    env={k:v for k,v in source.items() if not k.upper().startswith('_PYI_') and k.upper()!='_MEIPASS2'}
    env['PYINSTALLER_RESET_ENVIRONMENT']='1'
    return env


def spawn_independent(args):
    restore=None
    if sys.platform=='win32':
        import ctypes
        kernel=ctypes.windll.kernel32
        buffer=ctypes.create_unicode_buffer(32768)
        kernel.GetDllDirectoryW(len(buffer),buffer)
        previous=buffer.value
        kernel.SetDllDirectoryW.argtypes=[ctypes.c_wchar_p]
        if not kernel.SetDllDirectoryW(None):raise OSError('無法重設更新程序DLL搜尋路徑')
        restore=lambda:kernel.SetDllDirectoryW(previous or None)
    try:
        return subprocess.Popen(args,close_fds=True,env=installer_environment())
    finally:
        if restore:restore()


def launch_installer(path):
    return spawn_independent([str(path),'/SP-','/SILENT','/NORESTART','/CLOSEAPPLICATIONS','/RESTARTAPPLICATIONS','/LAUNCHAPP=1'])


def version_tuple(value):
    if not isinstance(value, str) or not re.fullmatch(r'\d{1,5}\.\d{1,5}\.\d{1,5}', value):
        raise ValueError('版本格式不正確')
    return tuple(map(int, value.split('.')))


def validate_manifest(data):
    if not isinstance(data, dict) or data.get('product') != PRODUCT or data.get('platform') != 'windows-x64' or data.get('channel') != 'stable':
        raise ValueError('更新檔不是此偵測器的 Windows 版本')
    version = data.get('version')
    version_tuple(version)
    size = data.get('size')
    if type(size) is not int or not 0 < size <= MAX_INSTALLER_SIZE:
        raise ValueError('安裝檔大小無效')
    sha = data.get('sha256')
    if not isinstance(sha, str) or not re.fullmatch('[a-f0-9]{64}', sha):
        raise ValueError('缺少有效的 SHA-256')
    expected = f'https://github.com/{REPO}/releases/download/mitake-v{version}/MitakeDetector-Setup-v{version}.exe'
    if data.get('url') != expected:
        raise ValueError('安裝檔來源不符合專用 GitHub 發布管道')
    return dict(data)


def request(url):
    return urllib.request.Request(url, headers={'User-Agent': 'MitakeDetector-Updater', 'Cache-Control': 'no-cache'})


def check_update(current_version, opener=urllib.request.urlopen):
    with opener(request(FEED_URL + '?t=' + str(int(time.time()))), timeout=25) as response:
        raw = response.read(128 * 1024 + 1)
    if len(raw) > 128 * 1024:
        raise ValueError('更新資訊過大')
    data = validate_manifest(json.loads(raw.decode('utf-8-sig')))
    return data if version_tuple(data['version']) > version_tuple(current_version) else None


def download_installer(manifest, directory, progress=lambda percent: None, opener=urllib.request.urlopen,
                       transfer=lambda downloaded,total,elapsed:None, stage=lambda value:None):
    manifest = validate_manifest(manifest)
    directory = Path(directory)
    directory.mkdir(parents=True, exist_ok=True)
    final = directory / f"MitakeDetector-Setup-v{manifest['version']}.exe"
    partial = final.with_suffix('.exe.partial')
    from download_transfer import parallel_download,clear_segments,valid_range
    started=time.monotonic()
    def report(count,total,elapsed,resumed=0):
        progress(min(99,int(count*100/total)))
        transfer(count,total,elapsed)
    try:
        stage('connecting')
        # Verified local installers are immediately reusable.
        if final.exists() and final.stat().st_size==manifest['size']:
            if hashlib.sha256(final.read_bytes()).hexdigest()==manifest['sha256'] and final.read_bytes()[:2]==b'MZ':
                stage('verifying');progress(100);stage('ready');return final
        parallel=False
        if manifest['size']>=2*1024*1024:
            parallel=parallel_download(manifest,partial,opener,request,report)
        if not parallel:
            count=partial.stat().st_size if partial.exists() else 0
            if count>=manifest['size']:count=0;partial.unlink(missing_ok=True)
            req=request(manifest['url'])
            if count:req.add_header('Range',f"bytes={count}-{manifest['size']-1}")
            with opener(req,timeout=40) as response:
                if count and not valid_range(response,count,manifest['size']-1,manifest['size']):
                    if getattr(response,'status',200)==200:count=0
                    else:raise ValueError('續傳來源區段無效')
                transfer(count,manifest['size'],0)
                with partial.open('ab' if count else 'wb') as output:
                    while True:
                        chunk=response.read(32768)
                        if not chunk:break
                        count+=len(chunk)
                        if count>manifest['size']:raise ValueError('下載內容大於公布大小')
                        output.write(chunk);report(count,manifest['size'],time.monotonic()-started)
                    output.flush();os.fsync(output.fileno())
        stage('verifying')
        digest=hashlib.sha256();count=0;header=b''
        with partial.open('rb') as source:
            while True:
                chunk=source.read(256*1024)
                if not chunk:break
                if not header:header=chunk[:2]
                digest.update(chunk);count+=len(chunk)
        if count!=manifest['size'] or digest.hexdigest()!=manifest['sha256'] or header!=b'MZ':
            raise ValueError('安裝檔驗證失敗，已取消安裝；請重新檢查更新')
        partial.replace(final);clear_segments(partial)
        progress(100);stage('ready');return final
    except ValueError:
        partial.unlink(missing_ok=True);clear_segments(partial);raise
    # Network failures intentionally retain partial files for the next attempt.
