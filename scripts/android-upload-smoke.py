"""Signed-release upload regression checks on dedicated rooted API29 / API34 emulators.

Never targets a physical phone. Set FILEFLASH_DEDICATED_EMULATOR=1 explicitly.
An existing installation is upgraded in place; shared Downloads are retained.
The final host stays running and a private ready JSON enables browser checks.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import time
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path
from urllib.parse import parse_qs, urlsplit


ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / '.test-data' / 'upload-android'
EVIDENCE = ROOT / 'docs' / 'test-evidence' / 'upload-android.json'
PRIVATE = '/data/user/0/app.fileflash'
CHUNK = 2 * 1024 * 1024
SERIAL = os.environ.get('ANDROID_SERIAL', 'emulator-5554')
SDK = Path(os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT') or ROOT / '.tools/android-sdk')
EXE = '.exe' if os.name == 'nt' else ''
ADB = SDK / 'platform-tools' / ('adb' + EXE)
if not ADB.is_file():
    ADB = Path(shutil.which('adb') or str(ADB))
JAVA = Path(os.environ['JAVA_HOME']) / 'bin' if os.environ.get('JAVA_HOME') else Path(shutil.which('java') or 'java').parent
RESULTS = []
BASE = ''
API_LEVEL = None
RUN = str(time.time_ns())


def adb(*args, binary=False, check=True):
    result = subprocess.run([str(ADB), '-s', SERIAL, *map(str, args)], capture_output=True, check=check)
    return result.stdout if binary else result.stdout.decode('utf-8', 'replace').strip()


def save():
    EVIDENCE.parent.mkdir(parents=True, exist_ok=True)
    EVIDENCE.write_text(json.dumps({
        'environment': f'Dedicated rooted Android API{API_LEVEL} emulator; adb forwarding, not physical Wi-Fi, Huawei hardware, or iOS',
        'run': RUN, 'tests': RESULTS,
    }, ensure_ascii=False, indent=2), encoding='utf-8')


def check(name, condition, **details):
    RESULTS.append(dict(name=name, passed=bool(condition), **details))
    save()
    print(('PASS: ' if condition else 'FAIL: ') + name, flush=True)
    if not condition:
        raise AssertionError(name)


def wait(predicate, timeout=25):
    deadline = time.monotonic() + timeout
    last_error = None
    while time.monotonic() < deadline:
        try:
            value = predicate()
            if value:
                return value
        except Exception as error:
            last_error = error
        time.sleep(.25)
    raise AssertionError('Timed out waiting for emulator state') from last_error


def prefs():
    raw = adb('exec-out', 'cat', PRIVATE + '/shared_prefs/fileflash.xml', check=False)
    return ET.fromstring(raw) if raw.startswith('<?xml') else ET.Element('map')


def saved_files():
    return json.loads(next((n.text for n in prefs() if n.get('name') == 'saved'), '[]'))


def shared_bytes(uri):
    return adb('exec-out', 'content', 'read', '--uri', uri, binary=True)


def version():
    info = adb('shell', 'dumpsys', 'package', 'app.fileflash')
    name = re.search(r'\bversionName=(\S+)', info)
    code = re.search(r'\bversionCode=(\d+)', info)
    return {'name': name.group(1) if name else None, 'code': int(code.group(1)) if code else None}


def stop():
    adb('shell', 'am', 'force-stop', 'app.fileflash')
    time.sleep(.3)


def launch():
    adb('shell', 'am', 'start', '-n', 'app.fileflash/.MainActivity')
    time.sleep(1.5)


def dump():
    adb('shell', 'uiautomator', 'dump', '/sdcard/fileflash-upload-window.xml')
    raw = adb('exec-out', 'cat', '/sdcard/fileflash-upload-window.xml')
    (OUT / 'window.xml').write_text(raw, encoding='utf-8')
    return ET.fromstring(raw)


def tap(text):
    item = next((n for n in dump().iter('node') if n.get('text') == text), None)
    if item is None:
        raise AssertionError('UI text absent: ' + text)
    x1, y1, x2, y2 = map(int, re.findall(r'\d+', item.get('bounds')))
    adb('shell', 'input', 'tap', (x1+x2)//2, (y1+y2)//2)
    time.sleep(.7)


def request(session, method, path, body=None, headers=None):
    h = dict(headers or {})
    if session:
        h['Authorization'] = 'Bearer ' + session
    if body is not None and not isinstance(body, bytes):
        body = json.dumps(body).encode('utf-8')
        h['Content-Type'] = 'application/json'
    req = urllib.request.Request(BASE + path, body, h, method=method)
    with urllib.request.urlopen(req, timeout=60) as response:
        data = response.read()
        return json.loads(data) if response.headers.get('Content-Type', '').startswith('application/json') else data


def host():
    global BASE
    tap('共享')
    if any(n.get('text') == '开启' for n in dump().iter('node')):
        tap('开启')
    wait(lambda: any(n.get('text') == '共享已开启' for n in dump().iter('node')))
    shot = OUT / 'host.png'
    shot.write_bytes(adb('exec-out', 'screencap', '-p', binary=True))
    jar = next((Path.home() / '.gradle/caches/modules-2/files-2.1/com.google.zxing/core/3.5.3').rglob('core-3.5.3.jar'))
    subprocess.run([str(JAVA / ('javac' + EXE)), '-cp', str(jar), '-d', str(OUT), str(ROOT / 'scripts/ReadQr.java')], check=True, capture_output=True)
    link = subprocess.check_output([str(JAVA / ('java' + EXE)), '-cp', str(OUT) + os.pathsep + str(jar), 'ReadQr', str(shot)], text=True).strip()
    uri = urlsplit(link)
    token = parse_qs(uri.fragment)['pair'][0]
    if uri.scheme != 'http' or not uri.port or len(token) < 24:
        raise AssertionError('QR has invalid local endpoint or pairing secret')
    # Matching the forwarded and actual ports also exercises normal Host validation.
    adb('forward', 'tcp:' + str(uri.port), 'tcp:' + str(uri.port))
    BASE = 'http://127.0.0.1:' + str(uri.port)
    paired = request(None, 'POST', '/api/pair', {'token': token, 'name': 'Upload regression verifier'})
    return paired['session'], {'url': BASE, 'port': uri.port, 'pairToken': token}


def upload(session, label, data, *, prefix=False):
    digest = hashlib.sha256(data).hexdigest()
    # No full-file digest is needed at task creation: new web uploads hash while sending.
    item = request(session, 'POST', '/api/uploads', {
        'name': 'upload-' + RUN + '-' + label + '.bin', 'size': len(data), 'mime': 'application/octet-stream',
    })
    key = {'X-Upload-Key': item['resumeKey']}
    for offset in range(0, len(data), CHUNK):
        chunk = data[offset:offset+CHUNK]
        answer = request(session, 'PUT', '/api/uploads/' + item['id'], chunk, {
            **key, 'X-Offset': str(offset), 'X-Chunk-SHA256': hashlib.sha256(chunk).hexdigest(),
        })
        if answer['offset'] != offset + len(chunk):
            raise AssertionError('Unexpected acknowledged offset')
        if prefix and offset == 0:
            proof = request(session, 'GET', '/api/uploads/' + item['id'] + '/prefix', headers=key)
            check('actual Android host hashes only acknowledged resume prefix',
                  proof['id'] == item['id'] and proof['size'] == len(data)
                  and proof['offset'] == len(chunk) and proof['prefixSha256'] == hashlib.sha256(chunk).hexdigest(),
                  prefixBytes=proof['offset'])
    result = request(session, 'POST', '/api/uploads/' + item['id'] + '/complete', {'sha256': digest}, key)
    if result['status'] != 'complete' or result['sha256'] != digest:
        raise AssertionError('Final upload checksum not confirmed')
    saved = wait(lambda: next((s for s in saved_files() if s['id'] == item['id']), None))
    return item, saved


def released(item):
    raw = adb('exec-out', 'cat', PRIVATE + '/files/server/uploads/' + item['id'] + '.json', check=False)
    record = json.loads(raw) if raw.startswith('{') else {}
    return record.get('status') == 'released' and not record.get('finalName')


def main():
    global API_LEVEL, EVIDENCE
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apk', type=Path, default=ROOT / 'app/build/outputs/apk/release/app-release.apk')
    parser.add_argument('--version', default='0.1.2', help='Expected release versionName')
    parser.add_argument('--ready', type=Path, help='Private browser ready JSON, under .test-data')
    parser.add_argument('--evidence', type=Path, help='Optional public evidence JSON path')
    args = parser.parse_args()
    if os.environ.get('FILEFLASH_DEDICATED_EMULATOR') != '1' or not re.fullmatch(r'emulator-\d+', SERIAL):
        raise RuntimeError('Set FILEFLASH_DEDICATED_EMULATOR=1 and ANDROID_SERIAL=emulator-N; personal phones are forbidden')
    # Guard before all mutating adb operations.
    if adb('shell', 'getprop', 'ro.kernel.qemu') != '1':
        raise RuntimeError('The target is not an Android emulator')
    if 'uid=0(root)' not in adb('shell', 'id'):
        raise RuntimeError('Dedicated emulator must already have adb root enabled')
    API_LEVEL = int(adb('shell', 'getprop', 'ro.build.version.sdk'))
    if API_LEVEL not in (29, 34):
        raise RuntimeError('This regression script targets dedicated API29 or API34 emulators')
    if args.evidence:
        EVIDENCE = args.evidence
    elif API_LEVEL != 34:
        EVIDENCE = ROOT / f'docs/test-evidence/upload-android-api{API_LEVEL}.json'
    if args.ready is None:
        suffix = '' if API_LEVEL == 34 else f'-api{API_LEVEL}'
        args.ready = ROOT / f'.test-data/upload-android{suffix}-ready.json'
    if not args.apk.is_file():
        raise FileNotFoundError(args.apk)
    try:
        args.ready.resolve().relative_to((ROOT / '.test-data').resolve())
    except ValueError as error:
        raise RuntimeError('Ready credentials must remain in the private .test-data directory') from error
    OUT.mkdir(parents=True, exist_ok=True)
    baseline = version()
    check('guarded dedicated rooted Android emulator selected', True, api=API_LEVEL)
    stop()
    if API_LEVEL >= 33:
        adb('shell', 'pm', 'grant', 'app.fileflash', 'android.permission.POST_NOTIFICATIONS', check=False)
    before = saved_files()
    if not before:
        launch()
        old_session, _ = host()
        upload(old_session, 'upgrade-baseline', b'FileFlash signed upgrade baseline\x00' * 97)
        stop()
        before = saved_files()
    old_hashes = {s['uri']: hashlib.sha256(shared_bytes(s['uri'])).hexdigest() for s in before}
    check('nonempty saved-file baseline recorded before signed upgrade', bool(before),
          previousVersion=baseline, savedFiles=len(before))
    check('new signed release installs as in-place upgrade', 'Success' in adb('install', '-r', args.apk),
          apkSha256=hashlib.sha256(args.apk.read_bytes()).hexdigest())
    installed = version()
    check('expected release version is installed', installed['name'] == args.version, installed=installed)
    if API_LEVEL >= 33:
        adb('shell', 'pm', 'grant', 'app.fileflash', 'android.permission.POST_NOTIFICATIONS', check=False)
    launch()
    after = saved_files()
    check('upgrade preserves saved ledger and existing Downloads bytes',
          {s['id'] for s in before} <= {s['id'] for s in after}
          and all(hashlib.sha256(shared_bytes(uri)).hexdigest() == digest for uri, digest in old_hashes.items()),
          checkedFiles=len(before), previousVersion=baseline, installedVersion=installed,
          upgradePath=('Harmony compatibility experiment to Android mainline' if 'harmony' in (baseline['name'] or '').lower() else 'Android mainline in-place upgrade'))
    session, _ = host()
    check('desktop pairs with actual Android APK host', bool(session))
    data = bytes(range(256)) * (4 * 1024 * 1024 // 256) + b'upload regression\x00' + bytes(range(155))
    item, saved = upload(session, 'multi-chunk', data, prefix=True)
    digest = hashlib.sha256(data).hexdigest()
    check('multichunk upload publishes identical MediaStore Downloads bytes',
          shared_bytes(saved['uri']) == data, bytes=len(data), sha256=digest)
    wait(lambda: released(item))
    check('successful upload automatically releases private duplicate', released(item))
    check('released file downloads under original share id', request(session, 'GET', '/api/files/' + item['id']) == data)
    stop()
    launch()
    session, ready = host()
    receipt = request(session, 'GET', '/api/uploads/' + item['id'], headers={'X-Upload-Key': item['resumeKey']})
    check('new-session lost-ACK recovery receipt survives process restart',
          receipt['status'] == 'complete' and receipt['offset'] == len(data) and receipt['sha256'] == digest)
    repeated = request(session, 'POST', '/api/uploads/' + item['id'] + '/complete', {'sha256': digest}, {'X-Upload-Key': item['resumeKey']})
    check('completion retry is idempotent after restart', repeated['status'] == 'complete' and repeated['offset'] == len(data))
    check('same-id saved-file download survives restart', request(session, 'GET', '/api/files/' + item['id']) == data)
    service = adb('shell', 'dumpsys', 'activity', 'services', 'app.fileflash')
    check('host retains dataSync foreground service', 'isForeground=true' in service)
    args.ready.parent.mkdir(parents=True, exist_ok=True)
    args.ready.write_text(json.dumps(ready), encoding='utf-8')
    # Never print QR URLs, pairing tokens, sessions, or upload recovery secrets.
    print('READY: host left running for browser acceptance; credentials stored only under .test-data.', flush=True)


if __name__ == '__main__':
    main()
