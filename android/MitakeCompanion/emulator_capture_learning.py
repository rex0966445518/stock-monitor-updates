"""Run on-device Chinese OCR/zoom/position-learning against six synthetic screens."""
import json,re,subprocess
from pathlib import Path
base=Path(__file__).resolve().parent
apk=base/'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'
subprocess.run(['adb','install','-r',str(apk)],check=True)
r=subprocess.run(['adb','shell','am','instrument','-w','-r','com.rex.mitakecompanion.test/com.rex.mitakecompanion.portfolio.CaptureLearningInstrumentation'],text=True,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,check=True)
print(r.stdout)
assert 'INSTRUMENTATION_CODE: -1' in r.stdout,r.stdout
match=re.search(r'INSTRUMENTATION_RESULT: capture_learning=(\{[^\n]+\})',r.stdout)
assert match,r.stdout
result=json.loads(match.group(1));assert result['ok'] and result['six_page_ocr']==6
out=base/'app/build/ui-previews';out.mkdir(parents=True,exist_ok=True)
(out/'capture-learning-integration.json').write_text(json.dumps(result,ensure_ascii=False),encoding='utf-8')
