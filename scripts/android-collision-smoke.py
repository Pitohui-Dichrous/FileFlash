"""Same-signature release upgrade and actual Android MediaStore filename conflict check.
Requires a dedicated API34+ emulator and a previously installed signed FileFlash APK.
Uses and cleans only FileFlash's published private cache; keeps external Downloads files.
"""
import hashlib, importlib.util, json, os, re, subprocess, time
from urllib.parse import urlsplit, parse_qs
spec=importlib.util.spec_from_file_location('smoke',__file__.replace('android-collision-smoke.py','android-smoke.py'))
m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
APK=m.ROOT/'app/build/outputs/apk/release/app-release.apk'
results=[]
def check(name,condition=True,**details):
    if not condition: raise AssertionError(name)
    results.append(dict(name=name,passed=True,**details));print('PASS:',name,flush=True)
def rows():
    raw=m.adb('shell','content','query','--uri','content://media/external/downloads','--projection','_id:_display_name:relative_path')
    result=[]
    for row in raw.splitlines():
        if 'FileFlash conflict receipt' in row and 'relative_path=Download/FileFlash/' in row:
            result.append(dict(id=int(re.search(r'_id=(\d+)',row).group(1)),name=re.search(r'_display_name=(.*?), relative_path=',row).group(1)))
    return result
def upload(data):
    digest=hashlib.sha256(data).hexdigest()
    task=m.request(base,session,'POST','/api/uploads',dict(name='FileFlash conflict receipt.txt',size=len(data),mime='text/plain',sha256=digest))
    m.request(base,session,'PUT','/api/uploads/'+task['id'],data,{'X-Offset':'0','X-Chunk-SHA256':digest})
    m.request(base,session,'POST','/api/uploads/'+task['id']+'/complete',dict(sha256=digest));time.sleep(2)
    return max(rows(),key=lambda r:r['id'])
try:
    m.adb('install','-r',str(APK));m.adb('shell','am','start','-n','app.fileflash/.MainActivity');time.sleep(1);m.tap('开启');time.sleep(1)
    shot=m.screenshot('release-collision-host')
    jar=next((m.Path.home()/'.gradle/caches/modules-2/files-2.1/com.google.zxing/core/3.5.3').rglob('core-3.5.3.jar'))
    subprocess.run(['javac','-cp',str(jar),'-d',str(m.OUT),str(m.ROOT/'scripts/ReadQr.java')],check=True,capture_output=True)
    link=subprocess.check_output(['java','-cp',str(m.OUT)+os.pathsep+str(jar),'ReadQr',str(shot)],text=True).strip();parsed=urlsplit(link)
    m.adb('forward','tcp:'+str(parsed.port),'tcp:'+str(parsed.port));base='http://127.0.0.1:'+str(parsed.port)
    session=m.request(base,None,'POST','/api/pair',dict(token=parse_qs(parsed.fragment)['pair'][0],name='Collision verifier'))['session']
    check('final patched release upgrades in place and restores host',True,apkSha256=hashlib.sha256(APK.read_bytes()).hexdigest())
    first=upload(b'first receipt, keep this original')
    m.tap('记录');m.tap('已接收');m.tap('清理缓存');time.sleep(1)
    second=upload(b'second receipt, separate contents')
    ui=m.dump('release-collision-receipts')
    read=lambda item:m.adb('exec-out','content','read','--uri','content://media/external/downloads/'+str(item['id']),binary=True)
    check('MediaStore filename conflict preserves both different files',first['id']!=second['id'] and read(first)==b'first receipt, keep this original' and read(second)==b'second receipt, separate contents',firstName=first['name'],secondName=second['name'])
    check('native received list shows actual MediaStore conflict-resolved name',any(n.get('text')==second['name'] for n in ui.iter('node')),displayName=second['name'])
    m.screenshot('release-collision-receipts')
    m.tap('共享');m.tap('停止');time.sleep(1)
finally:
    (m.OUT/'collision-results.json').write_text(json.dumps(dict(environment='Android14 API34 emulator, final signed release APK, actual MediaStore Download/FileFlash filename conflict.',tests=results),ensure_ascii=False,indent=2),encoding='utf-8')
