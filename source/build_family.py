from pathlib import Path
import os,subprocess,zipfile,sys,shutil,xml.etree.ElementTree as ET
base=Path(__file__).resolve().parent
sdk=Path(os.environ.get('ANDROID_SDK_ROOT','/tmp/six-sdk'));bt=sdk/'build-tools/35.0.0';android=sdk/'platforms/android-35/android.jar';ecj=os.environ.get('ECJ_JAR','/tmp/six-ecj.jar')
def run(*args):subprocess.run([str(a) for a in args],check=True)
for folder,label in [('tv-learning','LearningIsland-TV'),('parent-control','LearningIsland-Parent')]:
 if len(sys.argv)>1 and sys.argv[1] not in [folder, 'parent' if folder=='parent-control' else 'tv']:continue
 r=base/folder;build=r/'build';build.mkdir(exist_ok=True);classes=build/'classes';shutil.rmtree(classes,ignore_errors=True);classes.mkdir(exist_ok=True)
 for stale in ['base.apk','aligned.apk','classes.dex']:
  (build/stale).unlink(missing_ok=True)
 sources=list((r/'src').rglob('*.java'))+list((base/'common').glob('*.java'))
 run('java','-jar',ecj,'-nowarn','-source','1.8','-target','1.8','-encoding','UTF-8','-classpath',android,'-d',classes,*sources)
 args=[bt/'aapt','package','--ignore-assets','!.svn:!.git:!.ds_store:!*.scc:.*:CVS:thumbs.db:picasa.ini:*~:*.mp3:*.wav:*.female','-f','-M',r/'AndroidManifest.xml','-S',r/'res','-I',android,'-F',build/'base.apk']
 if (r/'assets').exists():args.extend(['-A',r/'assets'])
 run(*args)
 run(bt/'d8','--min-api','23','--lib',android,'--output',build,*list(classes.rglob('*.class')))
 with zipfile.ZipFile(build/'base.apk','a') as z:z.write(build/'classes.dex','classes.dex')
 key=base/'tv-learning/signing.p12'
 run(bt/'zipalign','-f','4',build/'base.apk',build/'aligned.apk')
 version=ET.parse(r/'AndroidManifest.xml').getroot().attrib['{http://schemas.android.com/apk/res/android}versionName']
 output=base/(label+'-v'+version+'.apk')
 run(bt/'apksigner','sign','--ks',key,'--ks-pass','pass:learningtv-local','--out',output,build/'aligned.apk')
 run(bt/'apksigner','verify','--verbose',output)
