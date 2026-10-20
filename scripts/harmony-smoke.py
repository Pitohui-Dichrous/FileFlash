"""Compatibility QA on a dedicated rooted AOSP API29/31 emulator, never a personal phone.

This tests the APK compatibility path, not Huawei hardware or HarmonyOS itself. It intentionally
uninstalls this package at the start on the explicitly selected emulator, then creates only synthetic
FileFlash data. Requires JAVA_HOME, ANDROID_HOME and ANDROID_SERIAL; the parent runner boots the AVD.
"""
from __future__ import annotations
import hashlib, http.client, json, os, re, struct, subprocess, sys, threading, time, urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path
from urllib.parse import parse_qs, urlsplit

ROOT = Path(__file__).resolve().parent.parent
SDK = Path(os.environ.get('ANDROID_HOME') or ROOT/'.tools/android-sdk')
ADB = SDK/'platform-tools/adb.exe'
JAVA = Path(os.environ['JAVA_HOME'])/'bin'
SERIAL = os.environ['ANDROID_SERIAL']
APK = ROOT/'app/build/outputs/apk/release/app-release.apk'
ORIGINAL = ROOT/'dist/FileFlash-0.1.1.apk'
PRIVATE = '/data/user/0/app.fileflash'
RUN = str(int(time.time()))
RESULTS = []
API = 0
OUT = ROOT/'.test-data'/('harmony-'+SERIAL)
OUT.mkdir(parents=True, exist_ok=True)
PORT = int(os.environ.get('FILEFLASH_QA_PORT', '18829'))
BASE = 'http://127.0.0.1:'+str(PORT)
HOST = '127.0.0.1:8787'
ENVIRONMENT = {}

def adb(*args, binary=False, check=True):
    result = subprocess.run([str(ADB), '-s', SERIAL, *map(str, args)], capture_output=True, check=check)
    return result.stdout if binary else (result.stdout+result.stderr).decode('utf-8', 'replace').strip()

def save():
    report = ROOT/f'docs/test-evidence/harmony-api{API}.json'
    report.parent.mkdir(parents=True, exist_ok=True)
    report.write_text(json.dumps(dict(environment=ENVIRONMENT, run=RUN, tests=RESULTS), ensure_ascii=False, indent=2), encoding='utf-8')

def check(name, condition, **details):
    RESULTS.append(dict(name=name, passed=bool(condition), **details)); save()
    print(('PASS: ' if condition else 'FAIL: ')+name, flush=True)
    if not condition: raise AssertionError(name)

def wait(predicate, timeout=20):
    end = time.time()+timeout
    while time.time()<end:
        try:
            value=predicate()
            if value: return value
        except Exception: pass
        time.sleep(.25)
    raise AssertionError('Timed out waiting for test state')

def dump(name='window'):
    adb('shell', 'uiautomator', 'dump', '/sdcard/fileflash-harmony-window.xml')
    raw=adb('exec-out', 'cat', '/sdcard/fileflash-harmony-window.xml')
    (OUT/(name+'.xml')).write_text(raw, encoding='utf-8')
    return ET.fromstring(raw)

def tap(text, occurrence=0):
    nodes=[n for n in dump().iter('node') if n.get('text')==text]
    if not nodes: raise AssertionError('Missing UI control: '+text)
    x1,y1,x2,y2=map(int, re.findall(r'\d+', nodes[occurrence].get('bounds')))
    adb('shell', 'input', 'tap', (x1+x2)//2, (y1+y2)//2); time.sleep(.6)

def shot(name):
    path=OUT/(name+'.png'); path.write_bytes(adb('exec-out', 'screencap', '-p', binary=True)); return path

def stopped(): adb('shell', 'am', 'force-stop', 'app.fileflash'); time.sleep(.4)
def launch(): adb('shell', 'am', 'start', '-n', 'app.fileflash/.MainActivity'); time.sleep(1.3)
def exists(path):
    return subprocess.run([str(ADB), '-s', SERIAL, 'shell', 'test', '-e', path], capture_output=True).returncode==0

def read_prefs(name='fileflash'):
    raw=adb('exec-out', 'cat', f'{PRIVATE}/shared_prefs/{name}.xml', check=False)
    return ET.fromstring(raw) if raw.startswith('<?xml') else ET.Element('map')

def pref_json(key, name='fileflash'):
    return json.loads(next((n.text for n in read_prefs(name) if n.get('name')==key), '[]'))

def write_bytes(path, data):
    local=OUT/'seed.bin'; local.write_bytes(data)
    adb('shell', 'mkdir', '-p', path.rsplit('/', 1)[0]); adb('push', local, path)
    uid=re.search(r'uid:(\d+)', adb('shell', 'cmd', 'package', 'list', 'packages', '-U', 'app.fileflash')).group(1)
    adb('shell', 'chown', uid+':'+uid, path.rsplit('/', 1)[0], path); adb('shell', 'chmod', '660', path)

def set_prefs(changes, name='fileflash'):
    tree=read_prefs(name)
    for key,value in changes.items():
        for node in list(tree):
            if node.get('name')==key: tree.remove(node)
        node=ET.SubElement(tree, 'string', {'name':key}); node.text=json.dumps(value, ensure_ascii=False)
    write_bytes(f'{PRIVATE}/shared_prefs/{name}.xml', ET.tostring(tree, encoding='utf-8', xml_declaration=True))

def request(session, method, path, body=None, headers=None):
    h={'Host':HOST, **(headers or {})}
    if session: h['Authorization']='Bearer '+session
    if body is not None and not isinstance(body, bytes): body=json.dumps(body).encode(); h['Content-Type']='application/json'
    with urllib.request.urlopen(urllib.request.Request(BASE+path, body, h, method=method), timeout=45) as response:
        data=response.read()
        return json.loads(data) if response.headers.get('Content-Type', '').startswith('application/json') else data

def host():
    global HOST
    tap('共享')
    if any(n.get('text')=='开启' for n in dump().iter('node')): tap('开启')
    ui=dump()
    check('host starts without a notification runtime permission on this pre33 system', any(n.get('text')=='共享已开启' for n in ui.iter('node')) and all(n.get('package')!='com.android.permissioncontroller' for n in ui.iter('node')))
    jar=next((Path.home()/'.gradle/caches/modules-2/files-2.1/com.google.zxing/core/3.5.3').rglob('core-3.5.3.jar'))
    subprocess.run([str(JAVA/'javac.exe'), '-cp', str(jar), '-d', str(OUT), str(ROOT/'scripts/ReadQr.java')], check=True, capture_output=True)
    link=subprocess.check_output([str(JAVA/'java.exe'), '-cp', str(OUT)+os.pathsep+str(jar), 'ReadQr', str(shot('host'))], text=True).strip()
    parsed=urlsplit(link); HOST='127.0.0.1:'+str(parsed.port)
    adb('forward', 'tcp:'+str(PORT), 'tcp:'+str(parsed.port))
    check('QR encodes a live local endpoint and random pairing token', parsed.scheme=='http' and bool(parsed.hostname) and len(parse_qs(parsed.fragment)['pair'][0])>=24)
    (OUT/'browser-ready.json').write_text(json.dumps(dict(url='http://127.0.0.1:'+str(parsed.port), pairToken=parse_qs(parsed.fragment)['pair'][0])), encoding='utf-8')
    return request(None, 'POST', '/api/pair', {'token':parse_qs(parsed.fragment)['pair'][0], 'name':f'API{API} compatibility verifier'})['session']

def create_upload(session, label, data):
    return request(session, 'POST', '/api/uploads', {'name':f'harmony-{RUN}-{label}.bin', 'size':len(data), 'mime':'application/octet-stream', 'sha256':hashlib.sha256(data).hexdigest()})

def chunk(session, item, offset, data):
    return request(session, 'PUT', '/api/uploads/'+item['id'], data, {'X-Upload-Key':item['resumeKey'], 'X-Offset':str(offset), 'X-Chunk-SHA256':hashlib.sha256(data).hexdigest()})

def complete(session, item, data):
    return request(session, 'POST', '/api/uploads/'+item['id']+'/complete', {'sha256':hashlib.sha256(data).hexdigest()}, {'X-Upload-Key':item['resumeKey']})

def saved(item): return wait(lambda: next((s for s in pref_json('saved') if s['id']==item['id']), None))
def shared_bytes(uri): return adb('exec-out', 'content', 'read', '--uri', uri, binary=True)
def media_row(uri): return adb('shell', 'content', 'query', '--uri', uri+'?includePending=1', '--projection', '_id:_display_name:is_pending:owner_package_name:relative_path')

def upload(session, label, data):
    item=create_upload(session, label, data)
    for offset in range(0, len(data), 2*1024*1024): chunk(session, item, offset, data[offset:offset+2*1024*1024])
    complete(session, item, data)
    return item,saved(item)

def released(item):
    raw=adb('exec-out', 'cat', PRIVATE+'/files/server/uploads/'+item['id']+'.json')
    record=json.loads(raw)
    return record.get('status')=='released' and record.get('finalName') is None and not exists(PRIVATE+'/files/server/received/'+record['name'])

def main():
    global API,ENVIRONMENT,RUN,RESULTS
    API=int(adb('shell', 'getprop', 'ro.build.version.sdk'))
    if API not in (29,31) or adb('shell', 'getprop', 'ro.kernel.qemu')!='1' or 'uid=0(root)' not in adb('shell', 'id'):
        raise RuntimeError('This script requires an explicitly selected, dedicated rooted AOSP API29 or API31 emulator')
    ENVIRONMENT=dict(description='Dedicated rooted AOSP emulator; APK compatibility verification only, not Huawei HarmonyOS hardware or physical Wi-Fi/iOS', api=API, serial=SERIAL, fingerprint=adb('shell', 'getprop', 'ro.build.fingerprint'), apkSha256=hashlib.sha256(APK.read_bytes()).hexdigest(), minSdk=29, targetSdk=37)
    if '--resume-layout' in sys.argv:
        prior=json.loads((ROOT/f'docs/test-evidence/harmony-api{API}.json').read_text(encoding='utf-8'))
        if prior['environment']['apkSha256']!=ENVIRONMENT['apkSha256']: raise RuntimeError('Resume requires the identical tested APK')
        RUN=prior['run']; RESULTS=[t for t in prior['tests'] if t['passed'] and not t['name'].startswith('simulated ')]
        ENVIRONMENT['harnessNote']='Initial2208px requested viewport was clamped to2160px by the emulator. Layout was retested at2160px; production APK unchanged.'
        launch(); session=host()
        item=next(f for f in request(session,'GET','/api/state')['files'] if f['name']==f'harmony-{RUN}-restart-resume.bin')
        finish_layout(session,item,bytes(range(256)))
        return
    adb('uninstall', 'app.fileflash', check=False)
    old=subprocess.run([str(ADB), '-s', SERIAL, 'install', str(ORIGINAL)], capture_output=True)
    old_result=(old.stdout+old.stderr).decode('utf-8', 'replace')
    check('original Android14-minimum APK is rejected on this older API', old.returncode!=0 and 'INSTALL_FAILED_OLDER_SDK' in old_result, installerResult=old_result.strip())
    check('signed compatibility release installs normally', 'Success' in adb('install', APK))
    adb('logcat', '-c'); launch(); session=host()
    check('host service posts an actual foreground notification', 'isForeground=true' in adb('shell', 'dumpsys', 'activity', 'services', 'app.fileflash'))
    fixture=bytes(range(256))*(3*8192)+b'\x00Harmony compatibility \xf0\x9f\x98\x80'
    item=create_upload(session, 'restart-resume', fixture)
    first=chunk(session, item, 0, fixture[:2*1024*1024])
    check('first 2MiB chunk is durably acknowledged', first['offset']==2*1024*1024)
    stopped(); launch(); session=host()
    state=request(session, 'GET', '/api/uploads/'+item['id'], headers={'X-Upload-Key':item['resumeKey']})
    check('hard process restart retains the exact upload id offset and private resume credential', state['id']==item['id'] and state['offset']==2*1024*1024)
    for offset in range(state['offset'], len(fixture), 2*1024*1024): chunk(session, item, offset, fixture[offset:offset+2*1024*1024])
    check('resumed multi-chunk upload completes with whole-file SHA256', complete(session, item, fixture)['status']=='complete', bytes=len(fixture), sha256=hashlib.sha256(fixture).hexdigest())
    received=saved(item)
    check('MediaStore Downloads contains the exact received bytes', shared_bytes(received['uri'])==fixture, location='Download/FileFlash')
    wait(lambda: released(item)); check('successful system save releases the private duplicate automatically', True)
    check('same share id downloads after private-copy release', request(session, 'GET', '/api/files/'+item['id'])==fixture)
    check('saved-URI Range download reopens at the requested byte offset', request(session, 'GET', '/api/files/'+item['id'], headers={'Range':'bytes=2097120-2097215'})==fixture[2097120:2097216])
    stopped(); launch(); session=host()
    status=request(session, 'GET', '/api/uploads/'+item['id'], headers={'X-Upload-Key':item['resumeKey']})
    check('lost-completion-ACK receipt survives process restart and private-copy release', status['status']=='complete' and status['offset']==len(fixture) and complete(session, item, fixture)['status']=='complete')
    check('same-id published download survives process restart', request(session, 'GET', '/api/files/'+item['id'])==fixture and len([s for s in pref_json('saved') if s['id']==item['id']])==1)
    message='鸿蒙兼容测试 English 😀 <text>\n第二行'
    request(session, 'POST', '/api/texts', {'text':message})
    check('host receives Unicode multiline text unchanged', any(t['text']==message for t in request(session, 'GET', '/api/state')['texts']))
    tap('文字'); check('native text UI renders the received message', any(n.get('text')==message for n in dump().iter('node')))
    pending,pending_saved=upload(session, 'pending-journal', b'pending-journal-unique')
    orphan,orphan_saved=upload(session, 'pending-untracked', b'pending-untracked-unique')
    committed,committed_saved=upload(session, 'committed-journal', b'committed-journal-unique')
    stopped()
    for row in (pending_saved,orphan_saved):
        check('crash fixture belongs to FileFlash before changing its pending flag', 'owner_package_name=app.fileflash' in media_row(row['uri']))
        adb('shell', 'content', 'update', '--uri', row['uri'], '--bind', 'is_pending:i:1')
    removed={pending['id'],orphan['id'],committed['id']}
    set_prefs({'saved':[s for s in pref_json('saved') if s['id'] not in removed]})
    set_prefs({pending['id']:pending_saved, committed['id']:committed_saved}, 'publication-journal')
    now=int(time.time()*1000); old=now-48*60*60*1000
    def task(label, size, stamp=now):
        id='harmony-'+RUN+'-'+label
        return dict(id=id, name=id+'.bin', direction='接收', size=size, transferred=32, status='已暂停', updatedAt=stamp, remote=dict(id='missing-'+id, name=id+'.bin', size=size, mime='application/octet-stream'))
    synthetic=[task('discard',1000),task('keep',1000),task('verified',32,old),task('aged',1000,old)]
    set_prefs({'tasks':synthetic+pref_json('tasks')})
    for label in ('discard','keep','verified','aged','orphan'):
        write_bytes(PRIVATE+'/files/client-parts/harmony-'+RUN+'-'+label+'.part', bytes(range(32)))
    verified=PRIVATE+'/files/client-parts/harmony-'+RUN+'-verified'
    write_bytes(verified+'.verified', b'verified')
    for label in ('aged','verified'): adb('shell', 'touch', '-t', '202001010000', PRIVATE+'/files/client-parts/harmony-'+RUN+'-'+label+'.part')
    launch(); time.sleep(2)
    check('startup removes its journaled pending MediaStore copy', 'No result found' in media_row(pending_saved['uri']))
    check('startup removes its untracked pending copy through the API-specific collection query', 'No result found' in media_row(orphan_saved['uri']), pendingQuery='setIncludePending URI' if API==29 else 'QUERY_ARG_MATCH_PENDING')
    check('startup recovers a completed publication once without duplicate or byte loss', len([s for s in pref_json('saved') if s['id']==committed['id']])==1 and shared_bytes(committed_saved['uri'])==b'committed-journal-unique' and not list(read_prefs('publication-journal')))
    check('startup removes orphan and expired incomplete native parts', all(not exists(PRIVATE+'/files/client-parts/harmony-'+RUN+'-'+label+'.part') for label in ('aged','orphan')))
    check('startup protects verified-unsaved and recent resumable data', exists(verified+'.part') and exists(PRIVATE+'/files/client-parts/harmony-'+RUN+'-keep.part'))
    tap('记录'); tap('丢弃'); tap('丢弃',-1)
    check('manual discard removes only the selected paused part', not exists(PRIVATE+'/files/client-parts/harmony-'+RUN+'-discard.part') and exists(PRIVATE+'/files/client-parts/harmony-'+RUN+'-keep.part') and exists(verified+'.part'))
    session=host(); active_data=bytes(range(256))*1024; active=create_upload(session, 'active-during-clear', active_data)
    started=threading.Event(); release=threading.Event(); answer={}
    def slow_chunk():
        try:
            c=http.client.HTTPConnection('127.0.0.1',PORT,timeout=45)
            c.putrequest('PUT','/api/uploads/'+active['id'],skip_host=True)
            headers={'Host':HOST,'Authorization':'Bearer '+session,'X-Upload-Key':active['resumeKey'],'X-Offset':'0','X-Chunk-SHA256':hashlib.sha256(active_data).hexdigest(),'Content-Length':str(len(active_data)),'Content-Type':'application/octet-stream'}
            for k,v in headers.items(): c.putheader(k,v)
            c.endheaders(); c.send(active_data[:65536]); started.set(); release.wait(30); c.send(active_data[65536:]); response=c.getresponse(); answer.update(status=response.status,body=response.read().decode()); c.close()
        except Exception as error: answer['error']=str(error)
    worker=threading.Thread(target=slow_chunk); worker.start(); started.wait(5); time.sleep(.3)
    try:
        tap('记录'); tap('清理'); tap('清理',-1)
        check('manual clear preserves an active host chunk and verified-unsaved bytes', exists(PRIVATE+'/files/server/uploads/'+active['id']+'.part') and exists(verified+'.part'))
        check('manual clear removes idle incomplete native data and retains saved Downloads', not exists(PRIVATE+'/files/client-parts/harmony-'+RUN+'-keep.part') and shared_bytes(received['uri'])==fixture)
    finally: release.set(); worker.join(10)
    check('active upload continues successfully after manual cache cleanup', answer.get('status')==200, response=answer)
    complete(session,active,active_data); active_saved=saved(active)
    check('active transfer publishes its original bytes after cleanup',shared_bytes(active_saved['uri'])==active_data)
    finish_layout(session,item,fixture)

def finish_layout(session,item,fixture):
    tap('共享')
    for label,size,density,rotation in [('folded360dp','1080x2400','480','0'),('unfolded1080dp','2160x1840','320','0'),('unfolded-rotated','2160x1840','320','1')]:
        adb('shell','wm','size',size); adb('shell','wm','density',density)
        adb('shell','settings','put','system','accelerometer_rotation','0'); adb('shell','settings','put','system','user_rotation',rotation)
        adb('shell','wm','user-rotation','lock',rotation,check=False); time.sleep(1)
        ui=dump(label); screenshot=shot(label)
        dimensions=struct.unpack('>II',screenshot.read_bytes()[16:24])
        expected=tuple(map(int,size.split('x')))
        if rotation=='1': expected=expected[::-1]
        check('simulated '+label+' keeps host actions navigation and server alive', dimensions==expected and all(any(n.get('text')==t for n in ui.iter('node')) for t in ('共享','连接','文字','记录','共享已开启','停止')) and request(session,'GET','/api/files/'+item['id'],headers={'Range':'bytes=0-255'})==fixture[:256], screenshot=str(screenshot.relative_to(ROOT)), pixels=dimensions, density=int(density), simulation='Viewport only, not actual fold posture or Huawei hardware')
    adb('shell','wm','size','reset'); adb('shell','wm','density','reset'); adb('shell','settings','put','system','user_rotation','0'); adb('shell','wm','user-rotation','lock','0',check=False)
    crash=adb('logcat','-d','-b','crash')
    (OUT/'crash-logcat.txt').write_text(crash,encoding='utf-8')
    check('no FileFlash fatal runtime exception recorded during the full run', 'app.fileflash' not in crash)
    (OUT/'ready.json').write_text(json.dumps(dict(base=BASE,hostHeader=HOST,session=session,serial=SERIAL)),encoding='utf-8')
    print(f'Completed {len(RESULTS)} checks. Host remains live for independent browser verification.',flush=True)

if __name__=='__main__':
    try: main()
    except Exception as error:
        RESULTS.append(dict(name='smoke execution',passed=False,error=str(error))); save()
        try: shot('failure')
        except Exception: pass
        raise
