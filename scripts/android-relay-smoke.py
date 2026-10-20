"""Explicit-save relay acceptance on a dedicated rooted API29 / API34 emulator.

Never targets a physical phone or uninstalls the app. An existing signed installation
is upgraded, its saved Downloads are checked, and only this run's temporary fixtures
are discarded. Pairing/session/recovery credentials stay under private .test-data.
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
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path
from urllib.parse import parse_qs, urlsplit


ROOT = Path(__file__).resolve().parent.parent
PRIVATE = '/data/user/0/app.fileflash'
RUN = str(time.time_ns())
SERIAL = os.environ.get('ANDROID_SERIAL', 'emulator-5554')
SDK = Path(os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT') or ROOT / '.tools/android-sdk')
EXE = '.exe' if os.name == 'nt' else ''
ADB = SDK / 'platform-tools' / ('adb' + EXE)
if not ADB.is_file():
    ADB = Path(shutil.which('adb') or str(ADB))
JAVA = Path(os.environ['JAVA_HOME']) / 'bin' if os.environ.get('JAVA_HOME') else Path(shutil.which('java') or 'java').parent
OUT = ROOT / '.test-data' / 'relay-android'
EVIDENCE = ROOT / 'docs' / 'test-evidence' / 'relay-android.json'
API_LEVEL = None
BASE = ''
RESULTS = []


def adb(*args, binary=False, check=True):
    result = subprocess.run([str(ADB), '-s', SERIAL, *map(str, args)], capture_output=True, check=check)
    return result.stdout if binary else result.stdout.decode('utf-8', 'replace').strip()


def wait(predicate, timeout=30):
    deadline = time.monotonic() + timeout
    last_error = None
    while time.monotonic() < deadline:
        try:
            result = predicate()
            if result:
                return result
        except Exception as error:
            last_error = error
        time.sleep(.25)
    raise AssertionError('Timed out waiting for emulator state') from last_error


def save_report():
    EVIDENCE.parent.mkdir(parents=True, exist_ok=True)
    EVIDENCE.write_text(json.dumps({
        'environment': f'Dedicated rooted Android API{API_LEVEL} emulator; two desktop HTTP sessions over adb forwarding, not physical Wi-Fi or iOS',
        'run': RUN, 'tests': RESULTS,
    }, ensure_ascii=False, indent=2), encoding='utf-8')


def check(name, condition, **details):
    RESULTS.append(dict(name=name, passed=bool(condition), **details))
    save_report()
    print(('PASS: ' if condition else 'FAIL: ') + name, flush=True)
    if not condition:
        raise AssertionError(name)


def pref_json(key, default=None):
    raw = adb('exec-out', 'cat', PRIVATE + '/shared_prefs/fileflash.xml', check=False)
    tree = ET.fromstring(raw) if raw.startswith('<?xml') else ET.Element('map')
    return json.loads(next((node.text for node in tree if node.get('name') == key), json.dumps(default if default is not None else [])))


def saved_files():
    return pref_json('saved')


def shared_bytes(uri):
    return adb('exec-out', 'content', 'read', '--uri', uri, binary=True)


def saved_row_exists(file):
    answer = adb('shell', 'content', 'query', '--uri', file['uri'], '--projection', '_id')
    if 'No result found' in answer:
        return False
    if not re.search(r'\b_id=\d+', answer):
        raise AssertionError('Unable to determine exact saved MediaStore row existence')
    return True


def media_rows():
    return adb('shell', 'content', 'query', '--uri', 'content://media/external/downloads?includePending=1',
               '--projection', '_id:_display_name:is_pending:owner_package_name:relative_path')


def exists(path):
    return subprocess.run([str(ADB), '-s', SERIAL, 'shell', 'test', '-f', path], capture_output=True).returncode == 0


def upload_record(item):
    raw = adb('exec-out', 'cat', PRIVATE + '/files/server/uploads/' + item['id'] + '.json', check=False)
    return json.loads(raw) if raw.startswith('{') else {}


def private_file(item):
    name = upload_record(item).get('finalName')
    if not name or '/' in name or '\\' in name or name in ('.', '..'):
        return None
    return PRIVATE + '/files/server/received/' + name


def private_matches(item, data):
    path = private_file(item)
    return bool(path and exists(path) and adb('exec-out', 'cat', path, binary=True) == data)


def write_own_server_file(item, target, data, label):
    """Fault injection is restricted to one uniquely named file created by this run."""
    if (not item['name'].startswith('relay-' + RUN[-10:] + '-')
            or target not in (PRIVATE + '/files/server/uploads/' + item['id'] + '.json',
                              PRIVATE + '/files/server/received/' + item['name'])):
        raise AssertionError('Refusing to write outside this run\'s private fixture paths')
    local = OUT / (label + '-' + item['id'] + '.bin')
    local.write_bytes(data)
    uid = re.search(r'uid:(\d+)', adb('shell', 'cmd', 'package', 'list', 'packages', '-U', 'app.fileflash')).group(1)
    adb('push', local, target)
    adb('shell', 'chown', uid + ':' + uid, target)
    adb('shell', 'chmod', '660', target)


def age_own_temporary(item):
    """With the app stopped, age only this run's complete temporary metadata for TTL QA."""
    record = upload_record(item)
    if (record.get('id') != item['id'] or record.get('name') != item['name']
            or not item['name'].startswith('relay-' + RUN[-10:] + '-')
            or record.get('status') != 'complete' or record.get('temporaryReceived') is not True
            or record.get('saveRequested', False)):
        raise AssertionError('Refusing to age metadata outside this run\'s unsaved temporary fixture')
    record['updatedAt'] = int(time.time() * 1000) - 25 * 60 * 60 * 1000
    # Recovery keys in this metadata stay exclusively in private .test-data.
    write_own_server_file(item, PRIVATE + '/files/server/uploads/' + item['id'] + '.json',
                          json.dumps(record, ensure_ascii=False).encode('utf-8'), 'aged')


def inject_deleted_saved_copy(item, metadata, data, saved):
    """Reproduce only a controlled post-save crash residue, then delete its own saved URI."""
    if (metadata.get('id') != item['id'] or metadata.get('name') != item['name']
            or metadata.get('finalName') != item['name'] or metadata.get('status') != 'complete'
            or metadata.get('size') != len(data) or metadata.get('offset') != len(data)
            or metadata.get('sha256') != hashlib.sha256(data).hexdigest()
            or metadata.get('temporaryReceived') is not True or saved['id'] != item['id']
            or not re.fullmatch(r'content://media/external(?:_primary)?/downloads/\d+', saved['uri'])):
        raise AssertionError('Refusing fault injection outside this run\'s verified saved fixture')
    metadata = dict(metadata, saveRequested=True)
    write_own_server_file(item, PRIVATE + '/files/server/received/' + item['name'], data, 'deleted-saved-source')
    write_own_server_file(item, PRIVATE + '/files/server/uploads/' + item['id'] + '.json',
                          json.dumps(metadata, ensure_ascii=False).encode('utf-8'), 'deleted-saved-metadata')
    # MediaProvider requires an attributed package for deletion; root UID0 has none.
    # The dedicated emulator's shell UID2000 is associated with com.android.shell.
    adb('shell', 'su', '2000', 'content', 'delete', '--uri', saved['uri'])
    if 'No result found' not in adb('shell', 'content', 'query', '--uri', saved['uri'], '--projection', '_id'):
        raise AssertionError('Failed to delete this run\'s synthetic saved URI for fault injection')


def version():
    raw = adb('shell', 'dumpsys', 'package', 'app.fileflash')
    name, code = re.search(r'\bversionName=(\S+)', raw), re.search(r'\bversionCode=(\d+)', raw)
    return dict(name=name.group(1) if name else None, code=int(code.group(1)) if code else None)


def stop():
    adb('shell', 'am', 'force-stop', 'app.fileflash')
    time.sleep(.3)


def launch():
    adb('shell', 'am', 'start', '-n', 'app.fileflash/.MainActivity')
    time.sleep(1.4)


def dump():
    adb('shell', 'uiautomator', 'dump', '/sdcard/fileflash-relay-window.xml')
    raw = adb('exec-out', 'cat', '/sdcard/fileflash-relay-window.xml')
    (OUT / 'window.xml').write_text(raw, encoding='utf-8')
    return ET.fromstring(raw)


def screenshot(name):
    (OUT / (name + '.png')).write_bytes(adb('exec-out', 'screencap', '-p', binary=True))


def tap_node(node):
    x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.get('bounds', '')))
    adb('shell', 'input', 'tap', (x1 + x2) // 2, (y1 + y2) // 2)
    time.sleep(.6)


def tap(text, occurrence=0):
    nodes = [node for node in dump().iter('node') if node.get('text') == text]
    if not nodes:
        raise AssertionError('UI text absent: ' + text)
    tap_node(nodes[occurrence])


def scroll(direction):
    screen = adb('shell', 'wm', 'size')
    sizes = re.findall(r'(\d+)x(\d+)', screen)
    width, height = map(int, sizes[-1])
    start, end = (.78, .30) if direction == 'down' else (.30, .78)
    adb('shell', 'input', 'swipe', width // 2, int(height * start), width // 2, int(height * end), 250)
    time.sleep(.3)


def history():
    tap('记录')
    if any(node.get('text') == '任务' for node in dump().iter('node')):
        tap('任务')


def stop_host():
    tap('共享')
    for _ in range(4):
        scroll('up')
    tap('停止')
    wait(lambda: any(node.get('text') == '开启' for node in dump().iter('node')))


def tap_file_action(name, action):
    """Find the smallest visible card containing this filename and the requested action."""
    history()
    for _ in range(4):
        scroll('up')
    for _ in range(14):
        tree = dump()
        parents = {child: parent for parent in tree.iter() for child in parent}
        filename = next((node for node in tree.iter('node') if node.get('text') == name), None)
        current = filename
        while current is not None:
            buttons = [node for node in current.iter('node') if node.get('text') == action and node.get('enabled') != 'false']
            if buttons:
                if action == '保存':
                    screenshot('temporary-controls')
                tap_node(buttons[0])
                return
            current = parents.get(current)
        scroll('down')
    raise AssertionError(f'Visible file action absent: {name} / {action}')


def clear_cache():
    history()
    for _ in range(4):
        scroll('up')
    tap('清理')
    wait(lambda: any(node.get('text') == '清理临时文件？' for node in dump().iter('node')))
    tap('清理', -1)
    time.sleep(.8)


def request(session, method, path, body=None, headers=None):
    h = dict(headers or {})
    if session:
        h['Authorization'] = 'Bearer ' + session
    if body is not None and not isinstance(body, bytes):
        body = json.dumps(body).encode('utf-8')
        h['Content-Type'] = 'application/json'
    with urllib.request.urlopen(urllib.request.Request(BASE + path, body, h, method=method), timeout=60) as response:
        data = response.read()
        return json.loads(data) if response.headers.get('Content-Type', '').startswith('application/json') else data


def pair(token, name):
    return request(None, 'POST', '/api/pair', dict(token=token, name=name))['session']


def host():
    global BASE
    tap('共享')
    if any(node.get('text') == '开启' for node in dump().iter('node')):
        tap('开启')
    wait(lambda: any(node.get('text') == '共享已开启' for node in dump().iter('node')))
    screenshot('host')
    jar = next((Path.home() / '.gradle/caches/modules-2/files-2.1/com.google.zxing/core/3.5.3').rglob('core-3.5.3.jar'))
    subprocess.run([str(JAVA / ('javac' + EXE)), '-cp', str(jar), '-d', str(OUT), str(ROOT / 'scripts/ReadQr.java')], check=True, capture_output=True)
    link = subprocess.check_output([str(JAVA / ('java' + EXE)), '-cp', str(OUT) + os.pathsep + str(jar), 'ReadQr', str(OUT / 'host.png')], text=True).strip()
    uri = urlsplit(link)
    token = parse_qs(uri.fragment)['pair'][0]
    if uri.scheme != 'http' or not uri.port or len(token) < 24:
        raise AssertionError('QR contains an invalid endpoint or pairing secret')
    adb('forward', 'tcp:' + str(uri.port), 'tcp:' + str(uri.port))
    BASE = 'http://127.0.0.1:' + str(uri.port)
    return pair(token, 'Relay uploader B'), pair(token, 'Relay downloader C'), dict(url=BASE, port=uri.port, pairToken=token)


def upload(session, label, data):
    name = 'relay-' + RUN[-10:] + '-' + label + '.bin'
    item = request(session, 'POST', '/api/uploads', dict(name=name, size=len(data), mime='application/octet-stream'))
    headers = {'X-Upload-Key': item['resumeKey']}
    chunk_size = 512 * 1024
    for offset in range(0, len(data), chunk_size):
        chunk = data[offset:offset + chunk_size]
        answer = request(session, 'PUT', '/api/uploads/' + item['id'], chunk, {
            **headers, 'X-Offset': str(offset), 'X-Chunk-SHA256': hashlib.sha256(chunk).hexdigest(),
        })
        if answer['offset'] != offset + len(chunk):
            raise AssertionError('Unexpected acknowledged upload offset')
    digest = hashlib.sha256(data).hexdigest()
    complete = request(session, 'POST', '/api/uploads/' + item['id'] + '/complete', dict(sha256=digest), headers)
    if complete['status'] != 'complete' or complete['sha256'] != digest:
        raise AssertionError('Upload completion checksum mismatch')
    item['name'] = name
    return item


def shared_file(session, item):
    return next((file for file in request(session, 'GET', '/api/state')['files'] if file['id'] == item['id']), None)


def absent_from_downloads(item):
    return all(saved['id'] != item['id'] for saved in saved_files()) and item['name'] not in media_rows()


def gone(session, item):
    if shared_file(session, item) is not None:
        return False
    try:
        request(session, 'GET', '/api/files/' + item['id'])
    except urllib.error.HTTPError as error:
        return error.code in (404, 410)
    return False


def baseline_intact(hashes):
    return all(hashlib.sha256(shared_bytes(uri)).hexdigest() == digest for uri, digest in hashes.items())


def main():
    global API_LEVEL, OUT, EVIDENCE
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apk', type=Path, default=ROOT / 'app/build/outputs/apk/release/app-release.apk')
    parser.add_argument('--version', required=True, help='Expected installed versionName')
    parser.add_argument('--ready', type=Path, help='Private ready JSON path under .test-data')
    parser.add_argument('--evidence', type=Path, help='Public evidence JSON output')
    args = parser.parse_args()
    if os.environ.get('FILEFLASH_DEDICATED_EMULATOR') != '1' or not re.fullmatch(r'emulator-\d+', SERIAL):
        raise RuntimeError('Set FILEFLASH_DEDICATED_EMULATOR=1 and ANDROID_SERIAL=emulator-N; physical phones are forbidden')
    # The first adb operation is read-only. No mutation is allowed before the emulator guard.
    if adb('shell', 'getprop', 'ro.kernel.qemu') != '1':
        raise RuntimeError('The selected target is not an Android emulator')
    if 'uid=0(root)' not in adb('shell', 'id'):
        raise RuntimeError('Dedicated test emulator must already have adb root enabled')
    API_LEVEL = int(adb('shell', 'getprop', 'ro.build.version.sdk'))
    if API_LEVEL not in (29, 34):
        raise RuntimeError('This script accepts only the dedicated API29 and API34 test emulators')
    OUT = ROOT / '.test-data' / f'relay-android-api{API_LEVEL}'
    EVIDENCE = args.evidence or ROOT / 'docs' / 'test-evidence' / f'relay-android-api{API_LEVEL}.json'
    ready = args.ready or ROOT / '.test-data' / f'relay-android-api{API_LEVEL}-ready.json'
    try:
        ready.resolve().relative_to((ROOT / '.test-data').resolve())
    except ValueError as error:
        raise RuntimeError('Pairing credentials must remain under private .test-data') from error
    if not args.apk.is_file():
        raise FileNotFoundError(args.apk)
    OUT.mkdir(parents=True, exist_ok=True)
    previous = version()
    stop()
    before = saved_files()
    readable_before = [file for file in before if saved_row_exists(file)]
    previous_bytes = {file['uri']: shared_bytes(file['uri']) for file in readable_before}
    if any(len(previous_bytes[file['uri']]) != file['size'] for file in readable_before):
        raise AssertionError('Existing saved row bytes do not match its ledger size')
    hashes = {uri: hashlib.sha256(data).hexdigest() for uri, data in previous_bytes.items()}
    check('dedicated emulator guard and existing saved-file baseline recorded', True, api=API_LEVEL,
          previousVersion=previous, savedFiles=len(before), readableDownloads=len(readable_before),
          unreadableBefore=len(before) - len(readable_before))
    check('new signed build upgrades without uninstall', 'Success' in adb('install', '-r', args.apk),
          apkSha256=hashlib.sha256(args.apk.read_bytes()).hexdigest())
    check('expected relay release version installed', version()['name'] == args.version, installed=version())
    if API_LEVEL >= 33:
        adb('shell', 'pm', 'grant', 'app.fileflash', 'android.permission.POST_NOTIFICATIONS', check=False)
    launch()
    check('upgrade retains existing saved ledger and Downloads bytes',
          {file['id'] for file in before} <= {file['id'] for file in saved_files()} and baseline_intact(hashes),
          ledgerEntries=len(before), readableDownloads=len(readable_before))
    session_b, session_c, _ = host()
    check('B and C authenticate as separate clients on the Android A host', session_b != session_c)
    payload = bytes(range(256)) * (4 * 1024 * 1024 // 256) + b'FileFlash temporary relay\x00' + bytes(range(173))
    first = upload(session_b, 'save', payload)
    first_path = private_file(first)
    time.sleep(1.5)  # Covers the former automatic publication sweep, not only completion instant.
    visible = shared_file(session_c, first)
    check('B upload remains a private temporary relay without saved ledger or MediaStore publication',
          absent_from_downloads(first) and private_matches(first, payload) and visible is not None and visible.get('temporary') is True,
          bytes=len(payload), sha256=hashlib.sha256(payload).hexdigest())
    check('separate client C downloads B temporary upload byte-for-byte before A saves',
          request(session_c, 'GET', '/api/files/' + first['id']) == payload)
    stop()
    launch()
    session_b, session_c, _ = host()
    check('unsaved temporary file restores after hard process restart without automatic publication',
          absent_from_downloads(first) and private_matches(first, payload)
          and request(session_c, 'GET', '/api/files/' + first['id']) == payload)
    screenshot('temporary-before-save')
    stop_host()
    tap_file_action(first['name'], '保存')
    saved = wait(lambda: next((file for file in saved_files() if file['id'] == first['id']), None))
    wait(lambda: upload_record(first).get('status') == 'released' and not private_file(first))
    check('A explicit Save works with sharing stopped and publishes exact Downloads bytes with durable ledger',
          shared_bytes(saved['uri']) == payload and first['name'] in media_rows(), sha256=hashlib.sha256(payload).hexdigest())
    session_b, session_c, _ = host()
    check('explicit Save releases private duplicate while preserving C same-id download',
          upload_record(first).get('status') == 'released' and bool(first_path) and not exists(first_path)
          and request(session_c, 'GET', '/api/files/' + first['id']) == payload)
    history()
    tap('已保存')
    screenshot('saved-files')
    tap('任务')
    discard_data = b'temporary discard\x00' * 60001
    discarded = upload(session_b, 'discard', discard_data)
    discarded_path = private_file(discarded)
    tap_file_action(discarded['name'], '丢弃')
    wait(lambda: any(node.get('text') == '丢弃临时文件？' for node in dump().iter('node')))
    tap('丢弃', -1)
    wait(lambda: gone(session_c, discarded))
    check('A explicit Discard deletes private relay and removes C access without creating a saved copy',
          bool(discarded_path) and not exists(discarded_path) and absent_from_downloads(discarded))
    clear_data = b'temporary clear\x00' * 70001
    cleared = upload(session_b, 'clear', clear_data)
    cleared_path = private_file(cleared)
    clear_cache()
    wait(lambda: gone(session_c, cleared))
    check('manual cache clear removes idle completed unsaved relay immediately',
          bool(cleared_path) and not exists(cleared_path) and absent_from_downloads(cleared))
    check('Discard and cache clear preserve permanent saved and prior Downloads',
          shared_bytes(saved['uri']) == payload and baseline_intact(hashes)
          and request(session_c, 'GET', '/api/files/' + first['id']) == payload)
    restart_data = b'restart temporary relay\x00' * 30001
    restarted = upload(session_b, 'restart', restart_data)
    expired = upload(session_b, 'ttl', b'expire only this relay fixture\x00' * 8193)
    expired_path = private_file(expired)
    stop()
    age_own_temporary(expired)
    launch()
    session_b, session_c, ready_data = host()
    check('hard restart restores saved and unsaved files together with correct storage semantics',
          request(session_c, 'GET', '/api/files/' + first['id']) == payload
          and request(session_c, 'GET', '/api/files/' + restarted['id']) == restart_data
          and absent_from_downloads(restarted) and private_matches(restarted, restart_data)
          and shared_bytes(saved['uri']) == payload)
    check('startup automatic TTL removes only aged unsaved relay while preserving saved Downloads',
          bool(expired_path) and not exists(expired_path) and gone(session_c, expired)
          and absent_from_downloads(expired) and shared_bytes(saved['uri']) == payload and baseline_intact(hashes),
          simulatedIdleHours=25, policyHours=24)
    screenshot('restored-relay')
    clear_cache()
    wait(lambda: gone(session_c, restarted))
    check('repeated manual cleanup leaves saved Downloads usable', shared_bytes(saved['uri']) == payload and baseline_intact(hashes))
    orphan_data = b'controlled deleted-saved residue\x00' * 8193
    orphan = upload(session_b, 'deleted-saved', orphan_data)
    orphan_metadata, orphan_path = upload_record(orphan), private_file(orphan)
    tap_file_action(orphan['name'], '保存')
    orphan_saved = wait(lambda: next((file for file in saved_files() if file['id'] == orphan['id']), None))
    wait(lambda: upload_record(orphan).get('status') == 'released')
    stop()
    inject_deleted_saved_copy(orphan, orphan_metadata, orphan_data, orphan_saved)
    launch()
    session_b, session_c, ready_data = host()
    check('fault-injected deleted Downloads copy leaves its retained private source accessible for explicit management',
          private_matches(orphan, orphan_data) and request(session_c, 'GET', '/api/files/' + orphan['id']) == orphan_data,
          faultInjection='Only this run\'s saved URI was deleted and same-id private metadata/bytes restored to model deferred-release process death; not a real download race')
    tap_file_action(orphan['name'], '丢弃')
    wait(lambda: any(node.get('text') == '丢弃临时文件？' for node in dump().iter('node')))
    screenshot('deleted-saved-discard-confirmation')
    tap('丢弃', -1)
    wait(lambda: gone(session_c, orphan))
    check('deleted saved URI does not strand retained private bytes: A can discard the temporary card',
          bool(orphan_path) and not exists(orphan_path) and shared_bytes(saved['uri']) == payload and baseline_intact(hashes))
    history()
    tap('已保存')
    screenshot('saved-files-final')
    tap('共享')
    ready.parent.mkdir(parents=True, exist_ok=True)
    ready.write_text(json.dumps(ready_data), encoding='utf-8')
    screenshot('completed')
    print('READY: dedicated host remains running; pairing credentials are private under .test-data.', flush=True)


if __name__ == '__main__':
    try:
        main()
    except Exception:
        RESULTS.append(dict(name='relay smoke execution interrupted', passed=False))
        save_report()
        if API_LEVEL is not None:
            try:
                screenshot('failure')
            except Exception:
                pass
        raise
