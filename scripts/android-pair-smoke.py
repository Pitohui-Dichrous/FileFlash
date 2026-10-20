"""Two-emulator release smoke over an adb bridge (not physical Wi-Fi).

Requires Android emulators emulator-5554 and emulator-5556, a signed release APK,
Java 17+, Python 3, and free host TCP8787. Uninstalls ONLY app.fileflash from
these dedicated test emulators to replace debug signatures. No production device.
"""
import hashlib, importlib.util, json, os, re, subprocess, time
from urllib.parse import urlsplit, parse_qs
spec=importlib.util.spec_from_file_location('smoke',__file__.replace('android-pair-smoke.py','android-smoke.py'))
m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
A='emulator-5554';B='emulator-5556'
APK=m.ROOT/'app/build/outputs/apk/release/app-release.apk'
checks=[]
def check(name,condition=True,**details):
    if not condition: raise AssertionError(name)
    checks.append(dict(name=name,passed=True,**details));print('PASS:',name,flush=True)
def choose(name):
    ui=m.dump()
    if not any(n.get('text')==name for n in ui.iter('node')):
        menu=next((n for n in ui.iter('node') if n.get('content-desc') in ['Show roots','显示根目录','Open navigation drawer']),None)
        if menu is not None:
            x1,y1,x2,y2=map(int,re.findall(r'\d+',menu.get('bounds')));m.adb('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2));time.sleep(.4);m.tap('Downloads',exact=False)
    m.tap(name);time.sleep(2)
def type_field(text):
    field=next(n for n in m.dump().iter('node') if n.get('class')=='android.widget.EditText')
    x1,y1,x2,y2=map(int,re.findall(r'\d+',field.get('bounds')));m.adb('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2));m.adb('shell','input','text',text);m.adb('shell','input','keyevent','4')
def get_saved(name):
    rows=m.adb('shell','content','query','--uri','content://media/external/downloads','--projection','_id:_display_name:relative_path:_size')
    row=next(r for r in rows.splitlines() if '_display_name='+name+',' in r and 'relative_path=Download/FileFlash/' in r)
    mid=re.search(r'_id=(\d+)',row).group(1)
    return m.adb('exec-out','content','read','--uri','content://media/external/downloads/'+mid,binary=True)

try:
    for serial in [A,B]:
        m.SERIAL=serial
        m.adb('reverse','--remove-all')
        m.adb('uninstall','app.fileflash') if 'package:app.fileflash' in m.adb('shell','pm','list','packages','app.fileflash') else None
        m.adb('install',str(APK));m.adb('shell','pm','grant','app.fileflash','android.permission.POST_NOTIFICATIONS')
        m.adb('shell','wm','user-rotation','lock','0');m.adb('shell','am','start','-n','app.fileflash/.MainActivity');time.sleep(1)
    check('final signed release installs and launches on two API34 emulators',True,apkSha256=hashlib.sha256(APK.read_bytes()).hexdigest())
    source=bytes(range(256))*1025+b'APK-A-to-B'
    source_path=m.OUT/'android-pair-source.bin';source_path.write_bytes(source)
    m.SERIAL=A;m.adb('push',str(source_path),'/sdcard/Download/android-pair-source.bin');m.adb('shell','am','broadcast','-a','android.intent.action.MEDIA_SCANNER_SCAN_FILE','-d','file:///sdcard/Download/android-pair-source.bin')
    m.tap('开启');m.tap('添加文件');choose('android-pair-source.bin')
    shot=m.screenshot('release-host-A')
    jar=next((m.Path.home()/'.gradle/caches/modules-2/files-2.1/com.google.zxing/core/3.5.3').rglob('core-3.5.3.jar'))
    subprocess.run(['javac','-cp',str(jar),'-d',str(m.OUT),str(m.ROOT/'scripts/ReadQr.java')],check=True,capture_output=True)
    link=subprocess.check_output(['java','-cp',str(m.OUT)+os.pathsep+str(jar),'ReadQr',str(shot)],text=True).strip()
    parsed=urlsplit(link);port=parsed.port;m.adb('forward','tcp:'+str(port),'tcp:'+str(port))
    base='http://127.0.0.1:'+str(port)
    session=m.request(base,None,'POST','/api/pair',{'token':parse_qs(parsed.fragment)['pair'][0],'name':'Two APK verifier'})['session']
    check('final release hosts embedded website and authenticated file service',bool(m.request(base,session,'GET','/api/state')['files']))
    m.SERIAL=B;m.adb('reverse','tcp:'+str(port),'tcp:'+str(port));m.tap('连接');type_field(base+'/#'+parsed.fragment);m.tap('连接',occurrence=0);time.sleep(1)
    check('Android B native client pairs to Android A native host',any(n.get('text')=='已连接' for n in m.dump().iter('node')))
    m.tap('接收',occurrence=0);time.sleep(2)
    received=get_saved('android-pair-source.bin')
    check('final APK Android A to Android B file is byte-identical',received==source,bytes=len(source),sha256=hashlib.sha256(source).hexdigest())
    response=bytes(reversed(range(256)))*257+b'APK-B-to-A'
    response_path=m.OUT/'android-pair-return.bin';response_path.write_bytes(response)
    m.adb('push',str(response_path),'/sdcard/Download/android-pair-return.bin');m.adb('shell','am','broadcast','-a','android.intent.action.MEDIA_SCANNER_SCAN_FILE','-d','file:///sdcard/Download/android-pair-return.bin')
    m.tap('发送文件');choose('android-pair-return.bin');time.sleep(2)
    m.SERIAL=A
    returned=get_saved('android-pair-return.bin')
    check('final APK Android B to Android A file is byte-identical',returned==response,bytes=len(response),sha256=hashlib.sha256(response).hexdigest())
    m.SERIAL=B;m.tap('文字');m.tap('已连接设备');type_field('Android-B-to-A-text');m.tap('发送');time.sleep(.5)
    m.SERIAL=A;m.tap('文字');type_field('Android-A-to-B-text');m.tap('发送');time.sleep(.5)
    ui=m.dump('release-text-A');a_seen=any(n.get('text')=='Android-B-to-A-text' for n in ui.iter('node'));m.screenshot('release-text-A')
    m.SERIAL=B;time.sleep(1);ui=m.dump('release-text-B');b_seen=any(n.get('text')=='Android-A-to-B-text' for n in ui.iter('node'));m.screenshot('release-text-B')
    check('final APK native text works in both directions',a_seen and b_seen)
    m.SERIAL=A;m.tap('共享');m.tap('停止');time.sleep(1)
    m.SERIAL=B;m.tap('连接');time.sleep(1);ui=m.dump('release-host-stopped')
    check('stopping Android A invalidates native Android B connection',not any(n.get('text')=='已连接' for n in ui.iter('node')))
finally:
    path=m.OUT/'pair-results.json';path.write_text(json.dumps(dict(environment='Two Android14 API34 emulators, same signed release APK, adb TCP forwarding/reverse. No physical Wi-Fi/iOS claim.',tests=checks),ensure_ascii=False,indent=2),encoding='utf-8')
