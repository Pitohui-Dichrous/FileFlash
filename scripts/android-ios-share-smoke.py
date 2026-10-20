"""Test the one-file Shortcuts protocol on the actual signed Android APK.

Dedicated rooted emulators only. Synthetic HEIC/MOV byte patterns are neither
personal media nor evidence of a physical iPhone Shortcuts run. Credentials and
exact cleanup identities stay in a private manifest under .test-data.
"""
from __future__ import annotations

import argparse
import hashlib
import http.client
import importlib.util
import json
import os
from pathlib import Path
import re
import socket
import time
import urllib.error
from urllib.parse import quote, urlsplit
import uuid


ROOT = Path(__file__).resolve().parent.parent
PRIVATE = '/data/user/0/app.fileflash/files/server'
spec = importlib.util.spec_from_file_location('relay_fixture', ROOT / 'scripts/android-relay-smoke.py')
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apk', type=Path, required=True)
    parser.add_argument('--version', required=True)
    parser.add_argument('--serial', default=os.environ.get('ANDROID_SERIAL', 'emulator-5554'))
    parser.add_argument('--ready', type=Path, required=True, help='Private live Android host ready JSON')
    parser.add_argument('--evidence', type=Path, required=True)
    args = parser.parse_args()
    if os.environ.get('FILEFLASH_DEDICATED_EMULATOR') != '1' or not re.fullmatch(r'emulator-\d+', args.serial):
        raise RuntimeError('Explicit dedicated emulator opt-in is required; physical phones are forbidden')
    args.ready.resolve().relative_to((ROOT / '.test-data').resolve())
    apk = args.apk.resolve(strict=True)
    expected_hash = hashlib.sha256(apk.read_bytes()).hexdigest()
    m.SERIAL = args.serial
    if m.adb('shell', 'getprop', 'ro.kernel.qemu') != '1' or 'uid=0' not in m.adb('shell', 'id'):
        raise RuntimeError('Dedicated rooted emulator required')
    api = int(m.adb('shell', 'getprop', 'ro.build.version.sdk'))
    if api not in (29, 34):
        raise RuntimeError('Only the dedicated API29/API34 test images are supported')
    installed = m.version()
    package_path = m.adb('shell', 'pm', 'path', 'app.fileflash').removeprefix('package:')
    if not package_path.startswith('/data/app/') or not package_path.endswith('/base.apk') or '\n' in package_path:
        raise RuntimeError('Unexpected installed package path')
    installed_hash = m.adb('shell', 'sha256sum', package_path).split()[0]
    ready = json.loads(args.ready.read_text(encoding='utf-8'))
    m.BASE = ready['url']
    endpoint = urlsplit(m.BASE)
    if endpoint.scheme != 'http' or endpoint.hostname != '127.0.0.1' or endpoint.port is None:
        raise RuntimeError('Only this dedicated adb-forwarded localhost endpoint is accepted')
    run = 'ios-share-' + str(api) + '-' + uuid.uuid4().hex[:12]
    out = ROOT / '.test-data' / 'ios-share-android' / run
    out.mkdir(parents=True)
    m.OUT = out
    manifest_path = out / 'cleanup-manifest.json'
    manifest = dict(run=run, serial=args.serial, url=m.BASE, version=args.version, apkSha256=expected_hash, uploads=[])
    checks = []
    completed = False
    session_b = session_c = None
    control_session = None

    def save_manifest():
        manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding='utf-8')

    def report():
        payload = dict(environment=f'Dedicated rooted Android API{api} emulator; actual signed APK host via adb forwarding; synthetic HEIC/MOV bytes; not physical iPhone, Photos, Shortcuts, Wi-Fi, or personal media',
                       version=args.version, apkSha256=expected_hash, installedApkSha256=installed_hash,
                       passed=completed and bool(checks) and all(item['passed'] for item in checks), tests=checks)
        args.evidence.parent.mkdir(parents=True, exist_ok=True)
        args.evidence.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding='utf-8')
        (out / 'results.json').write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding='utf-8')

    def check(name, condition, **details):
        checks.append(dict(name=name, passed=bool(condition), **details))
        report()
        print(('PASS: ' if condition else 'FAIL: ') + name, flush=True)
        if not condition:
            raise AssertionError(name)

    def wait(callback, timeout=12):
        deadline, last = time.monotonic() + timeout, None
        while time.monotonic() < deadline:
            try:
                result = callback()
                if result:
                    return result
            except Exception as error:
                last = error
            time.sleep(.1)
        raise AssertionError('Timed out waiting for actual APK transfer cleanup') from last

    def state():
        return m.request(control_session, 'GET', '/api/state')

    def own_records():
        snapshot = state()
        return [item for item in snapshot['uploads'] if item.get('name', '').startswith(run + '-')]

    def valid_id(value):
        return bool(re.fullmatch(r'[A-Za-z0-9_-]{1,96}', value))

    def remember(item, active=False):
        if not valid_id(item['id']) or not item.get('name', '').startswith(run + '-'):
            raise AssertionError('Refusing to track any upload outside this run')
        entry = next((record for record in manifest['uploads'] if record['id'] == item['id']), None)
        if entry is None:
            entry = dict(id=item['id'], name=item['name'], ownerSession=session_b, state='tracked')
            manifest['uploads'].append(entry)
        if item.get('sha256'):
            entry['sha256'] = item['sha256']
        # Save the response ID/session before additional reads or assertions can fail.
        save_manifest()
        raw = m.adb('exec-out', 'cat', PRIVATE + '/uploads/' + item['id'] + '.json', check=False)
        if raw.startswith('{'):
            metadata = json.loads(raw)
            if metadata.get('id') == item['id'] and metadata.get('name', '').startswith(run + '-'):
                entry['resumeKey'] = metadata.get('resumeKey')
                entry['finalName'] = metadata.get('finalName')
                save_manifest()
        if not active:
            details = m.request(control_session, 'GET', '/api/uploads/' + item['id'], headers={'X-Upload-Key':entry['resumeKey']} if entry.get('resumeKey') else {})
            entry['resumeKey'] = details.get('resumeKey', entry.get('resumeKey'))
            save_manifest()
        return entry

    def directory_entries():
        result = set()
        for directory in ('uploads', 'received'):
            for name in m.adb('shell', 'ls', '-1', PRIVATE + '/' + directory).splitlines():
                result.add(directory + '/' + name)
        return result

    def no_record_or_private_bytes(entry):
        identifier = entry['id']
        if any(item['id'] == identifier for item in state()['uploads'] + state()['files']):
            return False
        for suffix in ('.part', '.json', '.json.tmp'):
            if m.exists(PRIVATE + '/uploads/' + identifier + suffix):
                return False
        final = entry.get('finalName')
        if final and ('/' in final or '\\' in final or not final.startswith(run + '-')):
            raise AssertionError('Refusing an unsafe private received filename')
        return not final or not m.exists(PRIVATE + '/received/' + final)

    def target(name):
        return '/api/shortcuts/files?name=' + quote(name, safe='')

    def send(method, path, body=None, session=None, headers=None, chunked=False):
        connection = http.client.HTTPConnection(endpoint.hostname, endpoint.port, timeout=30)
        request_headers = dict(headers or {})
        if session is not None:
            request_headers['Authorization'] = 'Bearer ' + session
        try:
            payload = (body[offset:offset+65536] for offset in range(0, len(body), 65536)) if chunked else body
            connection.request(method, path, body=payload, headers=request_headers, encode_chunked=chunked)
            response = connection.getresponse()
            raw = response.read()
            parsed = json.loads(raw) if response.getheader('Content-Type', '').startswith('application/json') else raw
            return response.status, parsed
        finally:
            connection.close()

    def pattern(size, kind):
        data = bytearray((bytes(range(256)) * ((size+255)//256))[:size])
        data[4:12] = b'ftypheic' if kind == 'HEIC' else b'ftypqt  '
        data[-32:] = hashlib.sha256(kind.encode()).digest()
        return bytes(data)

    def partial(label, chunked):
        name = run + '-' + label + '.MOV'
        request_id = str(uuid.uuid4())
        connection = socket.create_connection((endpoint.hostname, endpoint.port), timeout=15)
        connection.settimeout(15)
        header = (f'POST {target(name)} HTTP/1.1\r\nHost: {endpoint.netloc}\r\nAuthorization: Bearer {session_b}\r\n'
                  f'Content-Type: video/quicktime\r\nX-Request-ID: {request_id}\r\n' + ('Transfer-Encoding: chunked\r\n' if chunked else 'Content-Length: 8388625\r\n') + '\r\n')
        try:
            connection.sendall(header.encode('ascii'))
            connection.sendall((b'100000\r\n' if chunked else b'') + bytes(128*1024+19))
            item = wait(lambda: next((value for value in own_records() if value['name'] == name and value['offset'] > 0), None))
            entry = remember(item, active=True)
            connection.shutdown(socket.SHUT_WR)
            response_status = None
            response_error = None
            try:
                response = http.client.HTTPResponse(connection)
                response.begin()
                response_status = response.status
                response.read()
            except (http.client.RemoteDisconnected, ConnectionResetError, BrokenPipeError) as error:
                # adb forwarding may propagate the input EOF by closing both
                # directions. Confirm APK-side rejection from its own trace.
                response_error = type(error).__name__
            wait(lambda: no_record_or_private_bytes(entry))
            trace = wait(lambda: next((item for item in m.request(session_b,'GET','/api/diagnostics')['transport']['requests'] if item.get('clientRequestId') == request_id and item.get('errorCode')),None))
            expected_error = 'invalid_chunked_body' if chunked else 'incomplete_body'
            entry['state'] = 'automatically-removed-after-eof'
            save_manifest()
            check(('chunked' if chunked else 'Content-Length') + ' interrupted body removes its task, prefix, metadata and share', (response_status == 400 or response_error is not None) and trace.get('status') == 400 and trace.get('errorCode') == expected_error and not any(record['id'] == item['id'] for record in m.saved_files()), errorCode=trace.get('errorCode'), apkStatus=trace.get('status'), forwardedStatus=response_status, forwardedEof=response_error)
        finally:
            connection.close()

    save_manifest()
    try:
        check('actual dedicated Android APK matches the frozen release', installed['name'] == args.version and installed_hash == expected_hash, apiLevel=api)
        session_b = m.pair(ready['pairToken'], 'Synthetic system-share B')
        control_session = session_b
        session_c = m.pair(ready['pairToken'], 'Independent relay downloader C')
        manifest['sessionB'], manifest['sessionC'] = session_b, session_c
        save_manifest()
        check('upload sender and receiving peer authenticate independently', session_b != session_c)
        before = m.saved_files()
        readable = [record for record in before if m.saved_row_exists(record)]
        old_hashes = {record['uri']: hashlib.sha256(m.shared_bytes(record['uri'])).hexdigest() for record in readable}
        artifacts_before = directory_entries()
        guard_name = run + '-guard.HEIC'
        no_auth = send('POST', target(guard_name), b'x', headers={'Content-Type':'image/heic'})[0]
        invalid_auth = send('POST', target(guard_name), b'x', session='invalid-session')[0]
        cross_origin = send('POST', target(guard_name), b'x', session_b, {'Origin':'https://invalid.example'})[0]
        bad_method = send('GET', target(guard_name), session=session_b)[0]
        check('authentication, Origin and method rejection create no file tasks', (no_auth,invalid_auth,cross_origin,bad_method) == (401,401,403,405) and not own_records())
        for kind, chunked, size, mime in [('HEIC',False,8*1024*1024+17,'image/heic'),('MOV',True,8*1024*1024+37,'video/quicktime')]:
            name = run + ('-原图 + & 😀.HEIC' if kind == 'HEIC' else '-原视频 + & 😀.MOV')
            data = pattern(size, kind)
            digest = hashlib.sha256(data).hexdigest()
            status, receipt = send('POST', target(name), data, session_b, {'Content-Type':mime,'X-Request-ID':str(uuid.uuid4())}, chunked)
            if status == 200:
                remember(receipt)
            framing = 'chunked' if chunked else 'Content-Length'
            check(framing + ' original ' + kind + ' returns one final completed receipt', status == 200 and receipt.get('status') == 'complete' and receipt.get('size') == len(data) and receipt.get('offset') == len(data) and receipt.get('sha256') == digest and receipt.get('mime') == mime and receipt.get('temporary') is True and receipt.get('restartOnly') is True and 'resumeKey' not in receipt, bytes=len(data), sha256=digest)
            downloaded = m.request(session_c, 'GET', '/api/files/' + receipt['id'])
            check('independent C receives unchanged ' + kind + ' source bytes', downloaded == data, bytes=len(downloaded), sha256=hashlib.sha256(downloaded).hexdigest())
            time.sleep(1.2)
            visible = next((item for item in state()['files'] if item['id'] == receipt['id']), None)
            check(kind + ' remains private temporary relay without automatic Downloads publication', bool(visible) and visible.get('temporary') is True and not any(item['id'] == receipt['id'] for item in m.saved_files()) and name not in m.media_rows())
        partial('fixed-interrupted', False)
        partial('chunked-interrupted', True)
        malformed_name = run + '-malformed.MOV'
        with socket.create_connection((endpoint.hostname,endpoint.port), timeout=10) as connection:
            connection.sendall((f'POST {target(malformed_name)} HTTP/1.1\r\nHost: {endpoint.netloc}\r\nAuthorization: Bearer {session_b}\r\nTransfer-Encoding: chunked\r\n\r\nzz\r\n').encode('ascii'))
            response = http.client.HTTPResponse(connection)
            response.begin()
            response.read()
            check('malformed chunk framing fails without retaining an upload', response.status == 400 and not any(item['name'] == malformed_name for item in own_records()))
        restricted_status, _ = send('POST', '/api/uploads', b'{}', session_b, {'Content-Type':'application/json'}, True)
        check('chunked framing remains forbidden on the ordinary native upload endpoint', restricted_status == 400)
        m.request(session_b, 'DELETE', '/api/session')
        control_session = session_c
        manifest['senderSessionRevoked'] = True
        save_manifest()
        completed_entries = [item for item in manifest['uploads'] if item.get('sha256')]
        peer_after_revoke = all(hashlib.sha256(m.request(session_c,'GET','/api/files/'+item['id'])).hexdigest() == item['sha256'] for item in completed_entries)
        for entry in manifest['uploads']:
            if no_record_or_private_bytes(entry):
                continue
            headers = {'X-Upload-Key':entry['resumeKey']} if entry.get('resumeKey') else {}
            m.request(control_session, 'DELETE', '/api/uploads/' + entry['id'], headers=headers)
            wait(lambda: no_record_or_private_bytes(entry))
            entry['state'] = 'deleted-by-exact-id'
            save_manifest()
        check('sender session revocation preserves C download and exact-key deletion removes only own temporary data', len(completed_entries) == 2 and peer_after_revoke and not own_records() and all(no_record_or_private_bytes(item) for item in manifest['uploads']) and not directory_entries().difference(artifacts_before), completedFilesReadableAfterSenderRevocation=len(completed_entries))
        check('pre-existing saved ledger and readable Downloads bytes remain unchanged', all(record in m.saved_files() for record in before) and all(hashlib.sha256(m.shared_bytes(uri)).hexdigest() == digest for uri,digest in old_hashes.items()), ledgerEntries=len(before), readableDownloads=len(readable), unreadableBefore=len(before)-len(readable))
        ready.update(version=args.version, serverApkSha256=installed_hash, serial=args.serial)
        args.ready.write_text(json.dumps(ready,indent=2),encoding='utf-8')
        completed = True
    finally:
        cleanup_errors = []
        if control_session is not None:
            try:
                for item in own_records():
                    remember(item, active=True)
            except Exception as error:
                cleanup_errors.append(type(error).__name__)
            for entry in manifest['uploads']:
                try:
                    if not no_record_or_private_bytes(entry):
                        m.request(control_session, 'DELETE', '/api/uploads/' + entry['id'], headers={'X-Upload-Key':entry['resumeKey']} if entry.get('resumeKey') else {})
                        wait(lambda: no_record_or_private_bytes(entry))
                    entry['cleanupVerified'] = True
                    save_manifest()
                except Exception as error:
                    cleanup_errors.append(type(error).__name__)
            for secret in (session_b, session_c):
                if secret is None:
                    continue
                try:
                    m.request(secret, 'DELETE', '/api/session')
                except urllib.error.HTTPError as error:
                    if error.code != 401:
                        cleanup_errors.append(type(error).__name__)
                except Exception as error:
                    cleanup_errors.append(type(error).__name__)
        if cleanup_errors:
            completed = False
            checks.append(dict(name='private manifest cleanup completes for every own fixture',passed=False,errorClasses=cleanup_errors))
        manifest['completed'] = completed
        save_manifest()
        report()


if __name__ == '__main__':
    main()
