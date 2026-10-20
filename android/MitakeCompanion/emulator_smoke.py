"""Synthetic PC-to-Android protocol, foreground service, notifications and UI."""
import json
import os
import re
import shlex
import subprocess
import sys
import tempfile
import threading
import time
import xml.etree.ElementTree as ET
from pathlib import Path
from urllib.parse import urlencode
sys.path.insert(0,str(Path('windows/MitakeDetector').resolve()))
from mobile_bridge import MobileBridge
from MitakeDetector import DEFAULT,Quote

out=Path('android/MitakeCompanion/app/build/ui-previews');out.mkdir(parents=True,exist_ok=True)
def adb(*args,binary=False):return subprocess.check_output(['adb',*args],timeout=45,text=not binary)
def screen(name):
    (out/name).write_bytes(adb('exec-out','screencap','-p',binary=True))
def dump():
    adb('shell','uiautomator','dump','/sdcard/mitake-ui.xml')
    return adb('shell','cat','/sdcard/mitake-ui.xml')

def tap(label):
    ui=dump()
    button=next(n for n in ET.fromstring(ui).iter('node') if n.attrib.get('text')==label)
    x1,y1,x2,y2=map(int,re.findall(r'\d+',button.attrib['bounds']))
    adb('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2))

def assert_scanner():
    time.sleep(2)
    active=adb('shell','dumpsys','activity','activities')
    assert re.search(r'mResumedActivity.*com.rex.mitakecompanion/com.journeyapps.barcodescanner.CaptureActivity',active),active[-5000:]
    assert 'FATAL EXCEPTION' not in adb('logcat','-d','-s','AndroidRuntime:E')

# Exercise the actual camera UI before protocol tests; the old test skipped it.
adb('install','-r','android/MitakeCompanion/app/build/outputs/apk/debug/app-debug.apk')
adb('logcat','-c')
adb('shell','am','start','-W','-n','com.rex.mitakecompanion/.MainActivity')
tap('配對');tap('掃描QR碼')
ui=dump()
# Android permission button IDs are stable even if the emulator locale changes.
node=next(n for n in ET.fromstring(ui).iter('node') if n.attrib.get('resource-id','').endswith('/permission_deny_button'))
x1,y1,x2,y2=map(int,re.findall(r'\d+',node.attrib['bounds']))
adb('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2))
assert '相機權限未開啟' in dump()
tap('確定')
adb('shell','pm','grant','com.rex.mitakecompanion','android.permission.CAMERA')
tap('配對');tap('掃描QR碼');assert_scanner();screen('qr-camera.png')
adb('shell','input','keyevent','4')
# Main-page scan entry is below the fold on small screens.
adb('shell','input','swipe','450','1100','450','350','400')
tap('掃描電腦配對碼');assert_scanner()
adb('shell','input','keyevent','4')
assert 'FATAL EXCEPTION' not in adb('logcat','-d','-s','AndroidRuntime:E')

with tempfile.TemporaryDirectory() as folder:
    bridge=MobileBridge(Path(folder)/'pair.json');bridge.start()
    stop=threading.Event();promote=threading.Event()
    cfg=dict(DEFAULT,use_turnover=True,min_turnover=2,use_accel=True,accel=1.5,use_breakout=True)
    def feed():
        while not stop.is_set():
            now=time.time();hit=promote.is_set()
            bridge.set_status(True,'合成測試｜第2輪｜漲幅排行')
            bridge.publish([(Quote('2330','測試候選',100,3,2000,4,now),2,'轉強候選' if hit else '等待觀測突破守穩',hit)],cfg,'EMULATOR-SYNTHETIC')
            stop.wait(1)
    thread=threading.Thread(target=feed,daemon=True);thread.start()
    try:
        adb('install','-r','android/MitakeCompanion/app/build/outputs/apk/debug/app-debug.apk')
        adb('shell','pm','grant','com.rex.mitakecompanion','android.permission.POST_NOTIFICATIONS')
        url='mitake://pair?'+urlencode(dict(address='http://10.0.2.2:8765',token=bridge.token))
        adb('shell','am','start','-W','-a','android.intent.action.VIEW','-d',shlex.quote(url))
        ui=dump();nodes=ET.fromstring(ui).iter('node')
        button=next(n for n in nodes if n.attrib.get('text')=='連線')
        x1,y1,x2,y2=map(int,re.findall(r'\d+',button.attrib['bounds']))
        adb('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2))
        time.sleep(8)
        ui=dump();assert '測試候選' in ui and '5/6' in ui,ui
        screen('companion-5of6.png')
        notification=adb('shell','dumpsys','notification','--noredact')
        assert 'candidate-2330' in notification,notification[-5000:]
        promote.set();time.sleep(7)
        ui=dump();assert '6/6' in ui and '必要檢核已通過' in ui,ui
        screen('companion-6of6.png')
        # Verify offline state preserves cards and stops presenting them as live.
        stop.set();thread.join(2);bridge.close();time.sleep(12)
        ui=dump();assert '目前非即時' in ui or '非即時／待複查' in ui,ui
        assert '測試候選' in ui
        screen('companion-offline.png')
        (out/'ui.xml').write_text(ui,'utf-8')
        (out/'integration.json').write_text(json.dumps(dict(ok=True,synthetic=True,upgrade_notifications=True,offline_retention=True)),'utf-8')
    except Exception:
        screen('companion-failure.png');(out/'logcat.txt').write_text(adb('logcat','-d','-t','300'),'utf-8');raise
    finally:stop.set();bridge.close()
