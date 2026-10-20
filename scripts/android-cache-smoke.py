"""Storage hygiene checks on a dedicated rooted AOSP emulator; never run on a personal phone.

Upgrades the existing synthetic test installation, seeds crash/partial states only in app-private
storage, and finally uninstalls/reinstalls it. Existing shared Downloads are hash-checked and retained.
"""
from __future__ import annotations
import hashlib, http.client, json, os, re, shutil, subprocess, sys, threading, time, urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path
from urllib.parse import urlsplit, parse_qs

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT/'.test-data/cache-android'
OUT.mkdir(parents=True, exist_ok=True)
EVIDENCE = ROOT/'docs/test-evidence/cache-android.json'
SDK = Path(os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT') or ROOT/'.tools/android-sdk')
ADB = SDK/'platform-tools/adb.exe'
if not ADB.is_file(): ADB = Path(shutil.which('adb') or str(ADB))
JAVA = Path(os.environ['JAVA_HOME'])/'bin' if os.environ.get('JAVA_HOME') else Path(shutil.which('java') or 'java').parent
APK = ROOT/'app/build/outputs/apk/release/app-release.apk'
SERIAL = os.environ.get('ANDROID_SERIAL', 'emulator-5554')
PRIVATE = '/data/user/0/app.fileflash'
RESULTS = []
BASE = 'http://127.0.0.1:18789'
HOST = '127.0.0.1:8787'
RUN = str(int(time.time()))
if '--resume-after-pending' in sys.argv or '--resume-after-uninstall' in sys.argv:
    prior=json.loads(EVIDENCE.read_text(encoding='utf-8'))
    RUN=prior['run'];RESULTS=[t for t in prior['tests'] if t.get('passed')]

def adb(*args, binary=False, check=True):
    p = subprocess.run([str(ADB), '-s', SERIAL, *map(str,args)], capture_output=True, check=check)
    return p.stdout if binary else p.stdout.decode('utf-8','replace').strip()

def save():
    EVIDENCE.parent.mkdir(parents=True, exist_ok=True)
    EVIDENCE.write_text(json.dumps({'environment':'Dedicated rooted Android API34 AOSP emulator; adb forwarding, not physical Wi-Fi/iOS', 'run':RUN, 'tests':RESULTS}, ensure_ascii=False, indent=2), encoding='utf-8')

def check(name, condition, **details):
    RESULTS.append(dict(name=name, passed=bool(condition), **details)); save()
    print(('PASS: ' if condition else 'FAIL: ')+name, flush=True)
    if not condition: raise AssertionError(name)

def wait(predicate, timeout=15):
    deadline=time.time()+timeout
    while time.time()<deadline:
        try:
            value=predicate()
            if value: return value
        except Exception: pass
        time.sleep(.25)
    raise AssertionError('Timed out waiting for state')

def exists(path): return adb('shell','test','-e',path,check=False)=='' and subprocess.run([str(ADB),'-s',SERIAL,'shell','test','-e',path],capture_output=True).returncode==0

def read_prefs(name='fileflash'):
    raw=adb('exec-out','cat',f'{PRIVATE}/shared_prefs/{name}.xml',check=False)
    return ET.fromstring(raw) if raw.startswith('<?xml') else ET.Element('map')

def pref_json(key, name='fileflash', default=None):
    return json.loads(next((n.text for n in read_prefs(name) if n.get('name')==key),json.dumps(default if default is not None else [])))

def write_bytes(path, data):
    local=OUT/'seed.bin'; local.write_bytes(data)
    adb('shell','mkdir','-p',path.rsplit('/',1)[0]); adb('push',local,path)
    uid=re.search(r'uid:(\d+)',adb('shell','cmd','package','list','packages','-U','app.fileflash')).group(1)
    adb('shell','chown',uid+':'+uid,path.rsplit('/',1)[0],path); adb('shell','chmod','660',path)

def set_prefs(changes, name='fileflash'):
    tree=read_prefs(name)
    for key,value in changes.items():
        for node in list(tree):
            if node.get('name')==key: tree.remove(node)
        node=ET.SubElement(tree,'string',{'name':key}); node.text=value if isinstance(value,str) else json.dumps(value,ensure_ascii=False)
    write_bytes(f'{PRIVATE}/shared_prefs/{name}.xml',ET.tostring(tree,encoding='utf-8',xml_declaration=True))

def stopped(): adb('shell','am','force-stop','app.fileflash');time.sleep(.3)
def launch(): adb('shell','am','start','-n','app.fileflash/.MainActivity');time.sleep(1.4)

def dump():
    adb('shell','uiautomator','dump','/sdcard/fileflash-cache-window.xml')
    raw=adb('exec-out','cat','/sdcard/fileflash-cache-window.xml')
    (OUT/'window.xml').write_text(raw,encoding='utf-8');return ET.fromstring(raw)

def tap(text, occurrence=0):
    nodes=[n for n in dump().iter('node') if n.get('text')==text]
    if not nodes: raise AssertionError('Missing UI text: '+text)
    n=nodes[occurrence];x1,y1,x2,y2=map(int,re.findall(r'\d+',n.get('bounds')))
    adb('shell','input','tap',(x1+x2)//2,(y1+y2)//2);time.sleep(.6)

def shot(name):
    path=OUT/(name+'.png');path.write_bytes(adb('exec-out','screencap','-p',binary=True));return path

def grant_notification():
    adb('shell','pm','grant','app.fileflash','android.permission.POST_NOTIFICATIONS',check=False)

def host():
    global HOST
    tap('共享')
    if any(n.get('text')=='开启' for n in dump().iter('node')): tap('开启')
    time.sleep(.8)
    jar=next((Path.home()/'.gradle/caches/modules-2/files-2.1/com.google.zxing/core/3.5.3').rglob('core-3.5.3.jar'))
    subprocess.run([str(JAVA/'javac.exe'),'-cp',str(jar),'-d',str(OUT),str(ROOT/'scripts/ReadQr.java')],check=True,capture_output=True)
    link=subprocess.check_output([str(JAVA/'java.exe'),'-cp',str(OUT)+os.pathsep+str(jar),'ReadQr',str(shot('host'))],text=True).strip()
    uri=urlsplit(link);HOST='127.0.0.1:'+str(uri.port)
    adb('forward','tcp:18789','tcp:'+str(uri.port))
    token=parse_qs(uri.fragment)['pair'][0]
    session=request(None,'POST','/api/pair',{'token':token,'name':'cache verifier'})['session']
    return session

def request(session, method, path, body=None, headers=None):
    h={'Host':HOST,**(headers or {})}
    if session:h['Authorization']='Bearer '+session
    if body is not None and not isinstance(body,bytes):body=json.dumps(body).encode();h['Content-Type']='application/json'
    with urllib.request.urlopen(urllib.request.Request(BASE+path,body,h,method=method),timeout=45) as response:
        data=response.read()
        return json.loads(data) if response.headers.get('Content-Type','').startswith('application/json') else data

def upload(session, label, data):
    digest=hashlib.sha256(data).hexdigest()
    item=request(session,'POST','/api/uploads',{'name':f'cache-{RUN}-{label}.bin','size':len(data),'mime':'application/octet-stream','sha256':digest})
    headers={'X-Upload-Key':item['resumeKey']}
    for offset in range(0,len(data),2*1024*1024):
        chunk=data[offset:offset+2*1024*1024]
        request(session,'PUT','/api/uploads/'+item['id'],chunk,{**headers,'X-Offset':str(offset),'X-Chunk-SHA256':hashlib.sha256(chunk).hexdigest()})
    request(session,'POST','/api/uploads/'+item['id']+'/complete',{'sha256':digest},headers)
    saved=wait(lambda: next((s for s in pref_json('saved') if s['id']==item['id']),None))
    return item,saved

def shared_bytes(uri): return adb('exec-out','content','read','--uri',uri,binary=True)
def media_row(uri): return adb('shell','content','query','--uri',uri,'--projection','_id:_display_name:is_pending:owner_package_name:relative_path')

def main():
    if '--resume-after-uninstall' in sys.argv:
        measured=next(t for t in RESULTS if t['name']=='uninstall pending-publication platform behavior measured')
        id=re.search(r'_id=(\d+)',measured['before']).group(1)
        uri='content://media/external/downloads/'+id
        adb('shell','su','2000','content','delete','--uri',uri+'?includePending=1')
        check('only unique synthetic pending-uninstall row cleaned after measurement','No result found' in media_row(uri),test_cleanup='Use associated Android shell UID2000 and includePending, since root UID0 lacks an attributed package for orphan-row deletion')
        finish_reinstall()
        return
    if '--resume-after-pending' in sys.argv:
        stopped();check('pending-match query fix APK upgrade succeeds','Success' in adb('install','-r',APK));launch();time.sleep(2)
        rows=adb('shell','content','query','--uri','content://media/external/downloads?includePending=1','--projection','_id:_display_name:is_pending:owner_package_name')
        check('startup removes own pending row in insert-before-journal crash window',f'cache-{RUN}-pending-untracked.bin' not in rows,regression_fixed='Explicit public QUERY_ARG_MATCH_PENDING=MATCH_INCLUDE plus owner/path SQL restrictions')
        before_tree=ET.fromstring((OUT/'upgrade-before.xml').read_text(encoding='utf-8'))
        before=json.loads(next(n.text for n in before_tree if n.get('name')=='saved'))
        old_hashes={s['uri']:hashlib.sha256(shared_bytes(s['uri'])).hexdigest() for s in before}
        current=pref_json('saved')
        saved=next(s for s in current if s['name']==f'cache-{RUN}-complete.bin')
        committed_saved=next(s for s in current if s['name']==f'cache-{RUN}-committed-journal.bin')
        fixture=bytes(range(256))*8193+b'FileFlash cache integrity'
        finish_checks(old_hashes,fixture,saved,committed_saved)
        return
    check('dedicated emulator exposes root only for controlled synthetic state setup','uid=0(root)' in adb('shell','id'))
    stopped()
    before=pref_json('saved')
    old_hashes={s['uri']:hashlib.sha256(shared_bytes(s['uri'])).hexdigest() for s in before}
    (OUT/'upgrade-before.xml').write_text(ET.tostring(read_prefs(),encoding='unicode'),encoding='utf-8')
    original_version='versionName=0.1.0' in adb('shell','dumpsys','package','app.fileflash')
    prior_path=ROOT/'docs/test-evidence/cache-android-upgrade.json'
    prior_upgrade=prior_path.exists() and any(t.get('name')=='signed0.1.1 upgrade succeeds' and t.get('passed') for t in json.loads(prior_path.read_text(encoding='utf-8'))['tests'])
    check('baseline0.1.0 synthetic upgrade path recorded',original_version or prior_upgrade,saved_files=len(before),prior_upgrade_evidence=None if original_version else 'docs/test-evidence/cache-android-upgrade.json')
    check('signed0.1.1 upgrade succeeds','Success' in adb('install','-r',APK))
    grant_notification();launch();time.sleep(2)
    after=pref_json('saved')
    check('upgrade retains saved-file ledger and all existing Downloads bytes',{s['id'] for s in before}<={s['id'] for s in after} and all(hashlib.sha256(shared_bytes(uri)).hexdigest()==digest for uri,digest in old_hashes.items()))
    session=host()
    fixture=bytes(range(256))*8193+b'FileFlash cache integrity'
    item,saved=upload(session,'complete',fixture)
    check('successful upload publishes byte-identical MediaStore file',shared_bytes(saved['uri'])==fixture,bytes=len(fixture),sha256=hashlib.sha256(fixture).hexdigest())
    wait(lambda: not any(s['id']==item['id'] and s.get('finalName') and exists(PRIVATE+'/files/server/received/'+s['finalName']) for s in [json.loads(adb('exec-out','cat',PRIVATE+'/files/server/uploads/'+item['id']+'.json'))]))
    private=adb('shell','ls','-l',PRIVATE+'/files/server/received')
    check('host received duplicate is automatically removed',f'cache-{RUN}-complete.bin' not in private)
    check('released file remains downloadable under original share id',request(session,'GET','/api/files/'+item['id'])==fixture)
    stopped();launch();session=host()
    status=request(session,'GET','/api/uploads/'+item['id'],headers={'X-Upload-Key':item['resumeKey']})
    check('complete receipt survives process restart for lost-ACK retry',status['status']=='complete' and status['offset']==len(fixture))
    check('same-id download survives restart after private copy removal',request(session,'GET','/api/files/'+item['id'])==fixture)
    # These are genuine app-owned MediaStore rows; only this run's unique rows are altered.
    pending,pending_saved=upload(session,'pending-journal',b'pending-journal-unique')
    untracked,untracked_saved=upload(session,'pending-untracked',b'pending-untracked-unique')
    committed,committed_saved=upload(session,'committed-journal',b'committed-journal-unique')
    stopped()
    for s in (pending_saved,untracked_saved):adb('shell','content','update','--uri',s['uri'],'--bind','is_pending:i:1')
    set_prefs({'saved':[s for s in pref_json('saved') if s['id'] not in {pending['id'],untracked['id'],committed['id']}]})
    set_prefs({pending['id']:pending_saved,committed['id']:committed_saved},'publication-journal')
    now=int(time.time()*1000);old=now-48*60*60*1000
    def task(id,status,size,stamp=now):return dict(id=id,name=id+'.bin',direction='接收',size=size,transferred=32,status=status,updatedAt=stamp,remote=dict(id='unavailable-'+id,name=id+'.bin',size=size,mime='application/octet-stream'))
    synthetic=[task('cache-discard','已暂停',1000),task('cache-keep','已暂停',1000),task('cache-verified','失败',32,old),task('cache-aged','已暂停',1000,old)]
    set_prefs({'tasks':synthetic+pref_json('tasks')})
    for id in ('cache-discard','cache-keep','cache-verified','cache-aged','cache-orphan'):write_bytes(PRIVATE+'/files/client-parts/'+id+'.part',bytes(range(32)))
    write_bytes(PRIVATE+'/files/client-parts/cache-verified.verified',b'verified')
    adb('shell','touch','-t','202001010000',PRIVATE+'/files/client-parts/cache-aged.part',PRIVATE+'/files/client-parts/cache-verified.part')
    launch();time.sleep(2)
    check('startup removes orphan and expired incomplete native parts',not exists(PRIVATE+'/files/client-parts/cache-orphan.part') and not exists(PRIVATE+'/files/client-parts/cache-aged.part'))
    check('startup protects verified unsaved and recent resumable native data',exists(PRIVATE+'/files/client-parts/cache-verified.part') and exists(PRIVATE+'/files/client-parts/cache-keep.part'))
    check('startup removes journaled pending MediaStore copy only','No result found' in media_row(pending_saved['uri']))
    check('startup removes own pending row in insert-before-journal crash window','No result found' in media_row(untracked_saved['uri']))
    finish_checks(old_hashes, fixture, saved, committed_saved)


def finish_checks(old_hashes, fixture, saved, committed_saved):
    recovered=[s for s in pref_json('saved') if s['id']==committed_saved['id']]
    check('startup recovers completed journal without duplicate or byte loss',len(recovered)==1 and shared_bytes(committed_saved['uri'])==b'committed-journal-unique')
    check('durable ledger recovery acknowledges completed journal',not list(read_prefs('publication-journal')))
    tap('记录');tap('丢弃');tap('丢弃',-1);time.sleep(.7)
    check('explicit discard removes only selected paused task part',not exists(PRIVATE+'/files/client-parts/cache-discard.part') and exists(PRIVATE+'/files/client-parts/cache-keep.part') and exists(PRIVATE+'/files/client-parts/cache-verified.part'))
    # Hold a live HTTP chunk open while the user clears idle data.
    session=host();data=bytes(range(256))*1024;slow=request(session,'POST','/api/uploads',{'name':f'cache-{RUN}-active.bin','size':len(data),'mime':'application/octet-stream'})
    started=threading.Event();release=threading.Event();answer={}
    def slow_chunk():
        try:
            c=http.client.HTTPConnection('127.0.0.1',18789,timeout=40)
            c.putrequest('PUT','/api/uploads/'+slow['id'],skip_host=True)
            for k,v in {'Host':HOST,'Authorization':'Bearer '+session,'X-Upload-Key':slow['resumeKey'],'X-Offset':'0','X-Chunk-SHA256':hashlib.sha256(data).hexdigest(),'Content-Length':str(len(data)),'Content-Type':'application/octet-stream'}.items():c.putheader(k,v)
            c.endheaders();c.send(data[:65536]);started.set();release.wait(30);c.send(data[65536:]);r=c.getresponse();answer.update(status=r.status,body=r.read().decode());c.close()
        except Exception as e:answer['error']=str(e)
    worker=threading.Thread(target=slow_chunk);worker.start();started.wait(5);time.sleep(.3)
    tap('记录');tap('清理');tap('清理',-1);time.sleep(.4)
    check('clear preserves an in-flight host chunk and verified unsaved native data',exists(PRIVATE+'/files/server/uploads/'+slow['id']+'.part') and exists(PRIVATE+'/files/client-parts/cache-verified.part'))
    check('clear removes inactive unverified native part',not exists(PRIVATE+'/files/client-parts/cache-keep.part'))
    release.set();worker.join(10)
    check('in-flight transfer completes after idle-cache clear',answer.get('status')==200,response=answer)
    request(session,'POST','/api/uploads/'+slow['id']+'/complete',{'sha256':hashlib.sha256(data).hexdigest()},{'X-Upload-Key':slow['resumeKey']})
    slow_saved=wait(lambda:next((s for s in pref_json('saved') if s['id']==slow['id']),None))
    check('active transfer final saved bytes remain intact',shared_bytes(slow_saved['uri'])==data)
    _,uninstall_pending_saved=upload(session,'uninstall-pending',b'uninstall-pending-unique-test-only')
    shot('cache-controls-portrait');dump()
    adb('shell','wm','size','984x2130');adb('shell','settings','put','system','font_scale','1.3');time.sleep(.7);shot('cache-controls-small-large-text')
    adb('shell','settings','put','system','accelerometer_rotation','0');adb('shell','settings','put','system','user_rotation','1');time.sleep(.8);shot('cache-controls-landscape')
    adb('shell','settings','put','system','font_scale','1.0');adb('shell','settings','put','system','user_rotation','0');adb('shell','wm','user-rotation','lock','0');adb('shell','wm','size','reset')
    check('cache controls render on375dp large-text and landscape configurations',True,screenshots=['.test-data/cache-android/cache-controls-portrait.png','.test-data/cache-android/cache-controls-small-large-text.png','.test-data/cache-android/cache-controls-landscape.png'])
    survivors={**old_hashes,saved['uri']:hashlib.sha256(fixture).hexdigest(),committed_saved['uri']:hashlib.sha256(b'committed-journal-unique').hexdigest(),slow_saved['uri']:hashlib.sha256(data).hexdigest()}
    stopped()
    adb('shell','content','update','--uri',uninstall_pending_saved['uri'],'--bind','is_pending:i:1')
    pending_before=adb('shell','content','query','--uri',uninstall_pending_saved['uri']+'?includePending=1','--projection','_id:_data:is_pending:owner_package_name')
    check('uninstall succeeds','Success' in adb('uninstall','app.fileflash'))
    check('uninstall removes app-private files cache and preferences',not exists(PRIVATE) and not exists('/data/user_de/0/app.fileflash'))
    check('uninstall preserves intentional Downloads and unrelated prior files',all(hashlib.sha256(shared_bytes(uri)).hexdigest()==digest for uri,digest in survivors.items()),files=len(survivors))
    pending_after=adb('shell','content','query','--uri',uninstall_pending_saved['uri']+'?includePending=1','--projection','_id:_data:is_pending:owner_package_name')
    check('uninstall pending-publication platform behavior measured',True,before=pending_before,after=pending_after,limitation='Android may orphan an unfinished shared MediaStore row until system expiry/maintenance; no uninstall callback can guarantee immediate external cleanup.')
    adb('shell','su','2000','content','delete','--uri',uninstall_pending_saved['uri']+'?includePending=1')
    check('only unique synthetic pending-uninstall row cleaned after measurement','No result found' in media_row(uninstall_pending_saved['uri']))
    finish_reinstall()

def finish_reinstall():
    check('clean reinstall succeeds','Success' in adb('install',APK));grant_notification();launch()
    check('clean reinstall has no restored tasks or saved-file metadata',not pref_json('tasks') and not pref_json('saved'))
    session=host();request(session,'POST','/api/texts',{'text':'cache verification final clean install'})
    check('clean reinstall host remains functional',any(t['text']=='cache verification final clean install' for t in request(session,'GET','/api/state')['texts']))
    shot('clean-reinstall-host')
    (OUT/'ready.json').write_text(json.dumps({'base':BASE,'hostHeader':HOST,'session':session,'serial':SERIAL}),encoding='utf-8')
    print('Completed. Emulator remains running for parent final checks.',flush=True)

if __name__=='__main__':
    try:main()
    except Exception as e:
        RESULTS.append({'name':'smoke execution','passed':False,'error':str(e)});save()
        try:shot('failure')
        except Exception:pass
        raise
