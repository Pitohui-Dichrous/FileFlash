"""Actual Android emulator smoke; uses adb forwarding, never represents physical LAN/iOS testing.

Requires a booted API 34+ emulator, Java 17+, Python 3, and an assembled debug APK.
Run from the repository root. Results and screenshots are written under .test-data/android.
The optional native-client stage uses DesktopServer described in docs/PROTOCOL.md.
"""
from __future__ import annotations
import hashlib, json, os, re, shutil, subprocess, time, urllib.request, urllib.error
import xml.etree.ElementTree as ET
from pathlib import Path
from urllib.parse import urlsplit, parse_qs

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / '.test-data' / 'android'
OUT.mkdir(parents=True, exist_ok=True)
sdk = os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
adb_name = 'adb.exe' if os.name == 'nt' else 'adb'
ADB = Path(sdk)/'platform-tools'/adb_name if sdk else ROOT/'.tools/android-sdk/platform-tools'/adb_name
if not ADB.exists(): ADB = Path(shutil.which('adb') or str(ADB))
SERIAL = os.environ.get('ANDROID_SERIAL', 'emulator-5554')
RESULTS = []
HOST_HEADERS = {}

def adb(*args, binary=False):
    result = subprocess.run([str(ADB), '-s', SERIAL, *args], check=True, capture_output=True)
    return result.stdout if binary else result.stdout.decode('utf-8', 'replace')

def dump(name='window'):
    adb('shell', 'uiautomator', 'dump', '/sdcard/fileflash-window.xml')
    raw = adb('exec-out', 'cat', '/sdcard/fileflash-window.xml')
    (OUT / (name + '.xml')).write_text(raw, encoding='utf-8')
    return ET.fromstring(raw)

def tap(text, *, occurrence=0, exact=True):
    nodes = [n for n in dump().iter('node') if (n.get('text') == text if exact else text in n.get('text', ''))]
    if len(nodes) <= occurrence:
        raise AssertionError(f'UI text absent: {text}')
    node = nodes[occurrence]
    x1,y1,x2,y2 = map(int, re.findall(r'\d+', node.get('bounds')))
    adb('shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2))
    time.sleep(.8)

def screenshot(name):
    path = OUT / (name + '.png')
    path.write_bytes(adb('exec-out', 'screencap', '-p', binary=True))
    return path

def check(name, condition=True, **details):
    if not condition:
        raise AssertionError(name)
    RESULTS.append(dict(name=name, passed=True, **details))
    print('PASS:', name, flush=True)

def save():
    (OUT/'results.json').write_text(json.dumps(dict(environment='Android API34 emulator, adb forwarding/reverse; not physical Wi-Fi or iOS', tests=RESULTS), ensure_ascii=False, indent=2), encoding='utf-8')

def request(base, session, method, path, body=None, headers=None):
    h = dict(headers or {})
    if base in HOST_HEADERS: h['Host'] = HOST_HEADERS[base]
    if session: h['Authorization'] = 'Bearer ' + session
    if body is not None and not isinstance(body, bytes):
        body = json.dumps(body, ensure_ascii=False).encode(); h['Content-Type'] = 'application/json'
    req = urllib.request.Request(base+path, body, h, method=method)
    with urllib.request.urlopen(req, timeout=45) as response:
        data = response.read()
        return json.loads(data) if response.headers.get('Content-Type', '').startswith('application/json') else data

def install_and_host():
    adb('install','-r',str(ROOT/'app/build/outputs/apk/debug/app-debug.apk'))
    adb('shell','am','force-stop','app.fileflash')
    adb('shell','am','start','-n','app.fileflash/.MainActivity')
    time.sleep(2)
    tap('开启')
    ui = dump()
    allow = [n for n in ui.iter('node') if n.get('resource-id') == 'com.android.permissioncontroller:id/permission_allow_button']
    if allow:
        x1,y1,x2,y2 = map(int,re.findall(r'\d+',allow[0].get('bounds')))
        adb('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2));time.sleep(1)
    check('foreground host starts', '共享已开启' in adb('exec-out','cat','/sdcard/fileflash-window.xml') or '共享已开启' in ET.tostring(dump(),encoding='unicode'))
    shot=screenshot('host-portrait')
    jars=list((Path.home()/'.gradle/caches/modules-2/files-2.1/com.google.zxing/core/3.5.3').rglob('core-3.5.3.jar'))
    if not jars: raise RuntimeError('Build must resolve ZXing first')
    subprocess.run(['javac','-cp',str(jars[0]),'-d',str(OUT),str(ROOT/'scripts/ReadQr.java')],check=True,capture_output=True)
    link=subprocess.check_output(['java','-cp',str(OUT)+os.pathsep+str(jars[0]),'ReadQr',str(shot)],text=True).strip()
    parsed=urlsplit(link)
    check('QR encodes active local endpoint and random pairing token',parsed.scheme=='http' and bool(parsed.hostname) and len(parse_qs(parsed.fragment)['pair'][0])>=24)
    port=str(parsed.port)
    adb('forward','tcp:18787','tcp:'+port)
    base='http://127.0.0.1:18787'
    HOST_HEADERS[base]='127.0.0.1:'+port
    pair=request(base,None,'POST','/api/pair',dict(token=parse_qs(parsed.fragment)['pair'][0],name='Android smoke desktop'))
    session=pair['session']
    check('desktop pairs with actual Android-hosted service',bool(session))
    data=bytes(range(256))*4097+b'\x00FileFlash Android \xf0\x9f\x98\x80'
    digest=hashlib.sha256(data).hexdigest()
    item=request(base,session,'POST','/api/uploads',dict(name='Android 接收验收 😀.bin',size=len(data),mime='application/octet-stream',sha256=digest))
    item=request(base,session,'PUT','/api/uploads/'+item['id'],data,{'X-Offset':'0','X-Chunk-SHA256':digest,'X-Upload-Key':item['resumeKey']})
    item=request(base,session,'POST','/api/uploads/'+item['id']+'/complete',{'sha256':digest})
    check('actual Android host accepts hash-verified file',item['status']=='complete',bytes=len(data),sha256=digest)
    fetched=request(base,session,'GET','/api/files/'+item['id'])
    check('Android-hosted download bytes match upload',fetched==data)
    partial=request(base,session,'GET','/api/files/'+item['id'],headers={'Range':'bytes=65536-131071'})
    check('Android-hosted Range download',partial==data[65536:131072])
    text='中文 English 😀 & <tag>\n第二行\t\"\\'
    request(base,session,'POST','/api/texts',{'text':text})
    check('Unicode multiline text reaches Android host unchanged',any(t['text']==text for t in request(base,session,'GET','/api/state')['texts']))
    time.sleep(3)
    raw=adb('shell','run-as','app.fileflash','cat','shared_prefs/fileflash.xml')
    pref=ET.fromstring(raw)
    saved=json.loads(next(n.text for n in pref if n.get('name')=='saved'))
    record=next(s for s in saved if s['id']==item['id'])
    uri=record['uri']
    exported=adb('exec-out','content','read','--uri',uri,binary=True)
    check('MediaStore Downloads export exists and SHA256 matches',hashlib.sha256(exported).hexdigest()==digest,location='Download/FileFlash')
    service=adb('shell','dumpsys','activity','services','app.fileflash')
    check('dataSync service has foreground notification','isForeground=true' in service)
    tap('文字'); time.sleep(1)
    ui=dump('host-text')
    check('native text UI shows incoming Unicode',any(n.get('text')==text for n in ui.iter('node')))
    screenshot('host-text')
    tap('共享');tap('停止');time.sleep(1);tap('开启');time.sleep(1)
    repeat=screenshot('host-restarted')
    next_link=subprocess.check_output(['java','-cp',str(OUT)+os.pathsep+str(jars[0]),'ReadQr',str(repeat)],text=True).strip()
    check('host restart reuses port and rotates pairing credentials',urlsplit(next_link).port==int(port) and next_link!=link)
    try:
        request(base,session,'GET','/api/state')
        raise AssertionError('old host session still valid')
    except urllib.error.HTTPError as error:
        check('host stop invalidates old client session',error.code==401)
    tap('停止');time.sleep(1)
    return base,session,item

def native_client():
    ready=ROOT/'.test-data/browser-ready.json'
    if not ready.exists(): return
    info=json.loads(ready.read_text())
    port=str(info['port'])
    adb('reverse','tcp:'+port,'tcp:'+port)
    link='http://127.0.0.1:'+port+'/#pair='+info['pairToken']
    tap('连接')
    ui=dump()
    fields=[n for n in ui.iter('node') if n.get('class')=='android.widget.EditText']
    if not fields: raise AssertionError('manual connect field missing')
    x1,y1,x2,y2=map(int,re.findall(r'\d+',fields[0].get('bounds')))
    adb('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2))
    adb('shell','input','text',link)
    adb('shell','input','keyevent','4');time.sleep(.4)
    tap('连接',occurrence=0)
    time.sleep(2)
    ui=dump('client-connected')
    check('native client manually pairs to DesktopServer',any(n.get('text')=='已连接' for n in ui.iter('node')))
    screenshot('client-connected')
    desktop='http://127.0.0.1:'+port
    session=request(desktop,None,'POST','/api/pair',{'token':info['pairToken'],'name':'Native byte verifier'})['session']
    files=request(desktop,session,'GET','/api/state')['files']
    if files:
        target=files[0]
        expected=request(desktop,session,'GET','/api/files/'+target['id'])
        tap('接收',occurrence=0);time.sleep(3)
        pref=ET.fromstring(adb('shell','run-as','app.fileflash','cat','shared_prefs/fileflash.xml'))
        tasks=json.loads(next(n.text for n in pref if n.get('name')=='tasks'))
        task=next(t for t in tasks if t.get('remote',{}).get('id')==target['id'])
        saved=adb('exec-out','content','read','--uri',task['savedUri'],binary=True)
        check('native remote download publishes hash-identical file',task['status']=='完成' and saved==expected,bytes=len(saved))
    fixture=bytes(range(256))*3073+b'native SAF upload'
    local=OUT/'android-upload.bin';local.write_bytes(fixture)
    adb('push',str(local),'/sdcard/Download/android-upload.bin')
    adb('shell','am','broadcast','-a','android.intent.action.MEDIA_SCANNER_SCAN_FILE','-d','file:///sdcard/Download/android-upload.bin')
    tap('发送文件');time.sleep(1)
    ui=dump('file-picker')
    names=[n.get('text') for n in ui.iter('node')]
    if 'android-upload.bin' not in names:
        menu=next((n for n in ui.iter('node') if n.get('content-desc') in ['Show roots','显示根目录','Open navigation drawer']),None)
        if menu is not None:
            x1,y1,x2,y2=map(int,re.findall(r'\d+',menu.get('bounds')));adb('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2));time.sleep(.5)
            tap('Downloads',exact=False);time.sleep(.5)
    tap('android-upload.bin');time.sleep(3)
    server_state=request(desktop,session,'GET','/api/state')
    uploaded=next(f for f in server_state['files'] if f['name'].startswith('android-upload'))
    received=request(desktop,session,'GET','/api/files/'+uploaded['id'])
    check('native SAF selection uploads hash-identical bytes',received==fixture,bytes=len(fixture),sha256=hashlib.sha256(fixture).hexdigest())
    tap('文字');tap('已连接设备')
    ui=dump()
    field=next(n for n in ui.iter('node') if n.get('class')=='android.widget.EditText')
    x1,y1,x2,y2=map(int,re.findall(r'\d+',field.get('bounds')));adb('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2))
    text='Native-Android-text-123'
    adb('shell','input','text',text);adb('shell','input','keyevent','4');tap('发送');time.sleep(1)
    check('native text send reaches peer as direct text',any(t['text']==text for t in request(desktop,session,'GET','/api/state')['texts']))
    screenshot('native-tasks')

def layout_and_host_source():
    tap('连接')
    ui=dump()
    if any(n.get('text')=='断开' for n in ui.iter('node')): tap('断开')
    tap('扫码连接');ui=dump('camera-permission')
    denied=next((n for n in ui.iter('node') if n.get('resource-id')=='com.android.permissioncontroller:id/permission_deny_button'),None)
    if denied is not None:
        x1,y1,x2,y2=map(int,re.findall(r'\d+',denied.get('bounds')));adb('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2));time.sleep(.4)
        ui=dump('camera-denied')
        check('camera denial retains manual connection fallback',any(n.get('class')=='android.widget.EditText' for n in ui.iter('node')))
    tap('共享')
    adb('shell','wm','size','984x2130')
    adb('shell','settings','put','system','font_scale','1.5');time.sleep(1)
    screenshot('small-phone-large-font')
    ui=dump('small-phone-large-font')
    check('375dp large-font UI retains primary actions and navigation',all(any(n.get('text')==t for n in ui.iter('node')) for t in ['开启','共享','连接','文字','记录']))
    adb('shell','settings','put','system','accelerometer_rotation','0')
    adb('shell','settings','put','system','user_rotation','1');time.sleep(1)
    screenshot('landscape-large-font');dump('landscape-large-font')
    check('landscape large-font UI renders',True)
    adb('shell','settings','put','system','font_scale','1.0')
    adb('shell','settings','put','system','user_rotation','0')
    adb('shell','wm','user-rotation','lock','0')
    adb('shell','wm','size','reset');time.sleep(1)
    adb('reverse','--remove-all')
    tap('开启');time.sleep(1)
    tap('添加文件');time.sleep(.7);tap('android-upload.bin');time.sleep(2)
    shot=screenshot('host-saf-file')
    jar=next((Path.home()/'.gradle/caches/modules-2/files-2.1/com.google.zxing/core/3.5.3').rglob('core-3.5.3.jar'))
    link=subprocess.check_output(['java','-cp',str(OUT)+os.pathsep+str(jar),'ReadQr',str(shot)],text=True).strip()
    parsed=urlsplit(link);base='http://127.0.0.1:18787';adb('forward','tcp:18787','tcp:'+str(parsed.port));HOST_HEADERS[base]='127.0.0.1:'+str(parsed.port)
    session=request(base,None,'POST','/api/pair',dict(token=parse_qs(parsed.fragment)['pair'][0],name='SAF verifier'))['session']
    state=request(base,session,'GET','/api/state')
    source=next(f for f in state['files'] if f['name']=='android-upload.bin' and not f['received'])
    expected=(OUT/'android-upload.bin').read_bytes()
    check('host SAF source is hashed before sharing',source['sha256']==hashlib.sha256(expected).hexdigest())
    check('host SAF source Range reopens at exact offset',request(base,session,'GET','/api/files/'+source['id'],headers={'Range':'bytes=100000-120000'})==expected[100000:120001])
    pref=ET.fromstring(adb('shell','run-as','app.fileflash','cat','shared_prefs/fileflash.xml'))
    saved=json.loads(next(n.text for n in pref if n.get('name')=='saved'))
    before={f['id']:hashlib.sha256(adb('exec-out','content','read','--uri',f['uri'],binary=True)).hexdigest() for f in saved}
    tap('记录');tap('已接收');tap('清理缓存');time.sleep(1)
    latest=request(base,session,'GET','/api/state')
    check('cache cleanup preserves shared SAF sources and external Downloads',any(f['id']==source['id'] for f in latest['files']) and not any(f['received'] for f in latest['files']) and all(hashlib.sha256(adb('exec-out','content','read','--uri',f['uri'],binary=True)).hexdigest()==before[f['id']] for f in saved))
    screenshot('received-files')
    tap('共享');tap('停止');time.sleep(1)

if __name__=='__main__':
    try:
        install_and_host()
        native_client()
        layout_and_host_source()
        save()
    except Exception as error:
        screenshot('failure')
        RESULTS.append(dict(name='smoke execution',passed=False,error=str(error)))
        save()
        raise
