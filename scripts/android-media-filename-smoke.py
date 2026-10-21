"""Media filename acceptance against the actual signed Android server APK.

Dedicated rooted API29/API34 emulators only; never uninstalls or clears app data.
Desktop HTTP senders exercise the Shortcuts endpoint, not physical iPhone or
Shortcuts execution. PNG/JPEG are generated, decodable images. MP4/MOV are tiny
generated H.264 clips, desktop-decoded before embedding. HEIC is deliberately
signature-only synthetic data and is never claimed to decode. Saves three valid
media fixtures; exact IDs, keys and publication URIs remain private .test-data.
"""
from __future__ import annotations

import argparse
import base64
import hashlib
import http.client
import importlib.util
import io
import json
import os
from pathlib import Path
import re
import shlex
import struct
import time
import urllib.error
from urllib.parse import quote, unquote, urlsplit
import uuid
import xml.etree.ElementTree as ET
import zlib

from PIL import Image


ROOT = Path(__file__).resolve().parent.parent
PRIVATE = '/data/user/0/app.fileflash'
SERVER = PRIVATE + '/files/server'
spec = importlib.util.spec_from_file_location('media_relay_helpers', ROOT / 'scripts/android-relay-smoke.py')
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)

# Generated locally with FFmpeg 7.1/libx264: two solid-color 64x64 frames,
# baseline H.264, yuv420p, one second, no audio. Both clips were decoded to null
# without error before embedding; no downloaded/personal media is included.
MP4_ZLIB = 'eNp9VE9vHDUU9yZNBRWHHFLRQ1SMCASJ7OzMZLtNQi2lhJY9gMSFCARo5LU9mdHaY8f2bnZ7QoIDnwAhvkH5AqgHpB6QQKrEFSJxgAuCC3yE5Xk220wD1LL93vP7vT9+zzMIIZz7qSmdVggtoUBhpXTMEmW6CULLrymtxwghqcYFR0+M5T/q1UJhno/Wk6iL8j566liCeegtHQL/kR/WMZf/7S3Efnqc/4y7/ziBDcG9A7oupPPnFmd+a9vWj4qXFBis+MW7zx29910ttAsu7UIzLrloIg9B1n1acSkCpjVVZZUDszZWtdNmmht8rlvnVuSNazw3shLP+dau8wMJ/APnHW9g7oem/U8pQq77qA+0v0BcfRvwvSRKdqMkjrEsB5O0121YXJvNYO8A6qD1xsPrs9/Q80dATy+98vDN0JJLYVu99f4D3EIrxaNPH4UEVw115iyBsNYG3p5V9+rqfKFrzteVXyQKHQ+maA0u1DwPax3O2IWzpYbu3oWX8yXQK3NfTDe6+iFQOuI+dPMDJWoaxovNzkG3LTVGNuu2Vc6fx8Z9r3XdGVob1xWGEuZQwpuhgiA+k1sBrV/6WgEIqLm8MpvJX+78+e3vp/1vvtj6GZ++9Nffocy4jZm2AifA2u1kN8Us7Yq4x0DRjwDQeefdO2+1u/j24QEguQiKA22mUuQep3G83U7jNLgpvDd7nc7JyUkUHp6WtIq0PeqEKFHhlQSMNr7UldvDjA4oIzGGx0W2MRcDqdmQJHvxXoxpReXUCRJPQJ4kSYKVIIWYYDcaAHcTGzclSdgzy0kSxWAEG1blRPAseAwWmaXVkSBJD7PCakUzME2wt0LK0gG3M9nhzEMK7FjBzgXl93QlSJpsQcScOp8ZNywNQM8cHJtM57kTnrRT7AsLFo6kWGo9pAUI2eIswU6WTJwfxLiydQxWKupDHmXlhZUUQHA+kCNLpxnTytA6IygR/HfKClwA0NKAyS1VIrg6EeVR4Q1wQzEFNUlvLNgMPmjIyDFRCTbypBvj2j4UxQpXhIKz7HHCQW8ZYTbHCr4OERIDgaTbUYyPQzokjnrAmuC3pnRCervAOC8M6eLSQJmhpdAF8EWPoYOhH+iT8KsSn3+2cuvuq+hK63vUev2HX9HLL2xubn48+mk+A+by7a92np3c/QcefVBu'
MOV_ZLIB = 'eNp9VM9vHDUU9m6aCkoOPQSRQwSWaBsO2dmZybJNQi2lpA17oFIvVBxAI6/tyYzW82Nt769eQIIDVw4gxJ0D/AOIA1IPSCD1D4BIHOBSwQX+hOW92WwyTVFnx/bz8/c+f37PO4SQ9djNyqGjlJAmWYwrzawoxoQQnY0TSZ56Vv6qWoPge/40nkZdnB+Q5z5NeHNn+ADsD9yg2nPlWTbc+/n7/O++B2cCrinpLIybSlt3HnHKW8U2nmQy5WDQTF48+4Loo3k1aSVSGzSyRJtxKlUdufYAHEWP51IrADU+z9I8Bv/6OKtI6zK3l0QSiEZG0zrRS3e442c85Jpc8GxKo+LakddO47As163ra7C/sc7KGuYLPhZBPU1HR/fuV7nHdgC/Hpi9JeLldwHfDbxgzwt8n+q0Pw27nZq0jTkmog2ow8bbj16d/0leOYbx5NKNR3ewfJewu3rr/R9og6wmjz95jAKvltyWpwKwbVhXVWQpCpRU518H8XU/tk3wiQu+Zm3t4YUb9RWMVxZcoqhV+wZWdyQdVnnjWzsxZO29n+C0MZz2Jh4W/C9Mqoo2v8skh5vSLC+vzuf697t///jkpPf9l9u/0ZPX//kXM0JbVBRG0QBMsxPshVSEHeV3BSz0PAC0792/+06rQ28/OASkVLhwWJQzrWJHQ9/faYV+iDSJc+V+uz2ZTDy8T4XmuVeY4zbu4iUu04ApSpcWud2ngve5YD6Fe8B2qFR9XYgBC/b9fZ/ynOuZVcyfwnwaBAHNFEvUlNpRH6ybtLQzFmAfGckCz4cg6GiWTpWMkBEjIsPzY8WCLhWJKTIeQWhAnVFapxas3emuFA4kiGEGvVRcPixyxcJgG3aMuXVRaQdpCdBTgmEZFXFslWOtkLrEQIRlIdVFMeAJTKKlL6BWp0KdO3yam2oPkWbcoY40d8poDiDw9/XI8FkkiqzklSJIEXxO0hwoUvywICY2PFNINVHpceJKsAZqBsssfHNpRvA/BUVWqFyJkWMdn1bxmBSjbIIJF9GZYFw3ggkT06wPmUFhMGHhjufTIcphvtcFs0TeauRT1t0DwzpVsg5NS0gzlBSqAFx8CBXEepCP8Y6qzz5dvXX0BrnS+Jk03vrlD3L9ta2trQ9Hvy5exFy+/fXui9Oj/wB35ktT'


def digest(data):
    return hashlib.sha256(data).hexdigest()


def fixtures(run):
    png = io.BytesIO()
    jpeg = io.BytesIO()
    image = Image.new('RGB', (16, 16), (48, 104, 184))
    image.save(png, format='PNG')
    image.save(jpeg, format='JPEG', quality=85)
    png, jpeg = png.getvalue(), jpeg.getvalue()
    mp4, mov = zlib.decompress(base64.b64decode(MP4_ZLIB)), zlib.decompress(base64.b64decode(MOV_ZLIB))
    if digest(mp4) != 'fd0993c2c7ad2997cb66383be1b13d689f19e5a33ac22d88e335ac010e9d7a58' or digest(mov) != '92b19ccc18b1b6e73ea74918a1c476ff516ce01849330a8aa7330c4b696d22a9':
        raise AssertionError('Embedded generated media fixture changed')
    heic = struct.pack('>I4s4sI4s4s', 24, b'ftyp', b'heic', 0, b'mif1', b'heic') + b'QA signature only; not a decodable HEIC image.'
    matrix = [
        ('PNG', 'png-source', png, '.png', 'image/png', False, True),
        ('JPEG', 'jpeg-source', jpeg, '.jpg', 'image/jpeg', False, True),
        ('HEIC', 'heic-source', heic, '.heic', 'image/heic', True, False),
        ('MP4', 'mp4-source', mp4, '.mp4', 'video/mp4', True, True),
        ('MOV', 'mov-source', mov, '.mov', 'video/quicktime', False, True),
        ('existing JPEG extension', 'existing.JPEG', jpeg, '', 'image/jpeg', False, True),
        ('Unicode PNG', '原图_😀', png, '.png', 'image/png', True, True),
        ('version suffix', 'trip.v2', png, '.png', 'image/png', False, True),
        ('unknown fallback', 'unknown', b'FileFlash synthetic unknown bytes\x00\x01', '.bin', 'application/octet-stream', False, False),
        ('untrusted MIME', 'untrusted-mime', b'Not a QuickTime movie, despite the incoming Content-Type', '.bin', 'application/octet-stream', False, False),
        ('nonmedia extension', 'original.CUSTOM', png, '', 'image/png', False, True),
    ]
    return [dict(label=label, originalName=run + '-' + name, name=run + '-' + name + suffix,
                 data=data, mime=mime, chunked=chunked, decodableMedia=valid,
                 incomingMime='video/quicktime' if label == 'untrusted MIME' else 'application/octet-stream')
            for label, name, data, suffix, mime, chunked, valid in matrix]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apk', required=True, type=Path)
    parser.add_argument('--version', required=True)
    parser.add_argument('--serial', default=os.environ.get('ANDROID_SERIAL', 'emulator-5554'))
    parser.add_argument('--evidence', required=True, type=Path)
    parser.add_argument('--ready', type=Path)
    parser.add_argument('--resume-evidence', type=Path, help='Preserve passed same-APK cases after an exact-cleaned harness-only failure')
    parser.add_argument('--resume-manifest', type=Path, help='Private manifest proving every previous fixture was precisely removed')
    args = parser.parse_args()
    if os.environ.get('FILEFLASH_DEDICATED_EMULATOR') != '1' or args.serial not in ('emulator-5554', 'emulator-5556'):
        raise RuntimeError('Explicit dedicated emulator opt-in required; physical devices are forbidden')
    expected_hash = digest(args.apk.resolve(strict=True).read_bytes())
    m.SERIAL = args.serial
    if m.adb('shell', 'getprop', 'ro.kernel.qemu') != '1' or 'uid=0(root)' not in m.adb('shell', 'id'):
        raise RuntimeError('Dedicated rooted emulator required before any device mutation')
    api = int(m.adb('shell', 'getprop', 'ro.build.version.sdk'))
    if api not in (29, 34):
        raise RuntimeError('Only the dedicated API29/API34 images are supported')
    prior = None
    if args.resume_evidence or args.resume_manifest:
        if not args.resume_evidence or not args.resume_manifest:
            raise RuntimeError('Both resume evidence and exact-cleanup manifest are required')
        prior = json.loads(args.resume_evidence.read_text(encoding='utf-8'))
        prior_manifest = json.loads(args.resume_manifest.read_text(encoding='utf-8'))
        if (prior.get('version') != args.version or prior.get('apiLevel') != api
                or prior.get('apkSha256') != expected_hash or prior.get('installedApkSha256') != expected_hash
                or prior_manifest.get('apkSha256') != expected_hash or prior_manifest.get('serial') != args.serial
                or not prior_manifest.get('uploads')
                or not all(e.get('privateCleanupVerified') and (not e.get('saved') or e.get('publicCleanupVerified'))
                           for e in prior_manifest['uploads'])):
            raise RuntimeError('Resume requires same-version same-APK evidence and verified exact cleanup')
        failed = [t for t in prior['tests'] if not t['passed']]
        allowed_failure = 'native Open fallback preserves MIME and exact saved URI without a video viewer: MOV'
        if not failed or any(t['name'] not in (allowed_failure, 'media filename acceptance completes') for t in failed):
            raise RuntimeError('Only the documented MOV resolver harness mismatch may resume')
    run = 'media-name-' + str(api) + '-' + uuid.uuid4().hex[:10]
    out = ROOT / '.test-data' / 'media-filename' / run
    out.mkdir(parents=True)
    m.OUT = out
    matrix = fixtures(run)
    ready_path = args.ready or ROOT / '.test-data' / ('media-filename-api' + str(api) + '-ready.json')
    ready_path.resolve().relative_to((ROOT / '.test-data').resolve())
    manifest_path = out / 'cleanup-manifest.json'
    manifest = dict(run=run, serial=args.serial, version=args.version, apkSha256=expected_hash, uploads=[], sessions=[])
    checks = [dict(t, evidenceRun=prior_manifest['run']) for t in prior['tests'] if t['passed']] if prior else []
    screenshots = [shot for shot in prior.get('screenshots', []) if shot['stage'] not in ('failure', 'open-mov')] if prior else []
    completed = False
    installed_hash = None
    session_b = session_c = None
    before, baseline_hashes, baseline_files = [], {}, {}
    cleanup_errors = []
    device_mutated = False

    def save_manifest():
        manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding='utf-8')

    def report():
        payload = dict(environment=f'Dedicated rooted Android API{api} emulator; actual signed APK host/native Save/Open UI; desktop HTTP Shortcuts-protocol senders, not a physical iPhone or Shortcuts execution',
                       version=args.version, apiLevel=api, apkSha256=expected_hash, installedApkSha256=installed_hash,
                       passed=completed and bool(checks) and all(item['passed'] for item in checks), tests=checks,
                       fixtureCatalog=[dict(label=f['label'], bytes=len(f['data']), sha256=digest(f['data']),
                                            decodableMedia=f['decodableMedia'], framing='chunked' if f['chunked'] else 'Content-Length') for f in matrix],
                       fixtureNotes='PNG/JPEG generated with Pillow; embedded MP4/MOV are generated 64x64 H.264 clips decoded successfully on desktop before embedding. HEIC is signature-only, not a valid decodable image. Unknown/untrusted MIME cases are synthetic nonmedia bytes.',
                       screenshots=screenshots)
        if prior:
            payload['evidenceContinuation'] = dict(priorRun=prior_manifest['run'], continuationRun=run,
                reason='Corrected harness assumption: the actual video/quicktime request has no VIEW handler; an alternate video/mp4 handler does not invalidate the observed unchanged QuickTime share fallback. Previously passed same-APK cases are retained with their original evidenceRun; the failed assertion is replaced by a fresh MOV Save/Open/restart/cleanup check.',
                priorExactCleanupVerified=True, repeatedFilenameMatrix=False)
        args.evidence.parent.mkdir(parents=True, exist_ok=True)
        args.evidence.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding='utf-8')
        (out / 'results.json').write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding='utf-8')

    def check(name, condition, **details):
        existing = next((item for item in checks if item['name'] == name), None)
        if existing is None:
            checks.append(dict(name=name, passed=bool(condition), **details))
        elif not condition:
            existing.update(passed=False, **details)
        report()
        print(('PASS: ' if condition else 'FAIL: ') + name, flush=True)
        if not condition:
            raise AssertionError(name)

    def wait(callback, timeout=30):
        deadline, last = time.monotonic() + timeout, None
        while time.monotonic() < deadline:
            try:
                result = callback()
                if result is not None and result is not False:
                    return result
            except Exception as error:
                last = error
            time.sleep(.2)
        raise AssertionError('Timed out waiting for media filename acceptance state') from last

    def snapshot():
        return m.request(session_c, 'GET', '/api/state')

    def remember(receipt, fixture):
        if (not re.fullmatch(r'[A-Za-z0-9_-]{1,96}', receipt.get('id', ''))
                or not receipt.get('name', '').startswith(run + '-')):
            raise AssertionError('Refusing to track a foreign file identity')
        entry = next((item for item in manifest['uploads'] if item['id'] == receipt['id']), None)
        if entry is None:
            entry = dict(id=receipt['id'], name=receipt['name'], label=fixture['label'], sha256=digest(fixture['data']),
                         size=len(fixture['data']), ownerSession=session_b)
            manifest['uploads'].append(entry)
        save_manifest()  # Track exact identity before any more reads/assertions.
        raw = m.adb('exec-out', 'cat', SERVER + '/uploads/' + entry['id'] + '.json', check=False)
        if raw.startswith('{'):
            metadata = json.loads(raw)
            if metadata.get('id') != entry['id'] or not metadata.get('name', '').startswith(run + '-'):
                raise AssertionError('Upload metadata ownership mismatch')
            for key in ('resumeKey', 'finalName'):
                if metadata.get(key):
                    entry[key] = metadata[key]
            save_manifest()
        return entry

    def upload(fixture):
        endpoint = urlsplit(m.BASE)
        connection = http.client.HTTPConnection(endpoint.hostname, endpoint.port, timeout=30)
        try:
            body = (fixture['data'][i:i+83] for i in range(0, len(fixture['data']), 83)) if fixture['chunked'] else fixture['data']
            connection.request('POST', '/api/shortcuts/files?name=' + quote(fixture['originalName'], safe=''),
                               body=body, headers={'Authorization': 'Bearer ' + session_b,
                                                   'Content-Type': fixture['incomingMime']}, encode_chunked=fixture['chunked'])
            response = connection.getresponse()
            receipt = json.loads(response.read())
            if response.status != 200:
                raise AssertionError('Actual APK rejected media fixture with HTTP ' + str(response.status))
            fixture['entry'] = remember(receipt, fixture)
            return receipt
        finally:
            connection.close()

    def download(fixture):
        endpoint = urlsplit(m.BASE)
        connection = http.client.HTTPConnection(endpoint.hostname, endpoint.port, timeout=30)
        try:
            connection.request('GET', '/api/files/' + fixture['entry']['id'], headers={'Authorization': 'Bearer ' + session_c})
            response = connection.getresponse()
            body = response.read()
            disposition = response.getheader('Content-Disposition', '')
            extended = re.search(r"(?:^|;\s*)filename\*=UTF-8''([^;]+)", disposition, re.I)
            fallback = re.search(r'(?:^|;\s*)filename="([^"\r\n]*)"', disposition, re.I)
            correct = (response.status == 200 and body == fixture['data'] and digest(body) == digest(fixture['data'])
                       and response.getheader('Content-Type', '').split(';')[0] == fixture['mime']
                       and extended is not None and unquote(extended.group(1)) == fixture['name']
                       and fallback is not None and fallback.group(1).isascii()
                       and fallback.group(1).lower().endswith(Path(fixture['name']).suffix.lower()))
            return correct, dict(contentDispositionExtendedName=unquote(extended.group(1)) if extended else None,
                                 contentType=response.getheader('Content-Type'), bytes=len(body), sha256=digest(body))
        finally:
            connection.close()

    def host():
        nonlocal session_b, session_c
        session_b, session_c, ready = m.host()
        manifest['sessions'] += [session_b, session_c]
        manifest['url'] = m.BASE
        save_manifest()
        ready.update(version=args.version, serial=args.serial, serverApkSha256=installed_hash)
        ready_path.parent.mkdir(parents=True, exist_ok=True)
        ready_path.write_text(json.dumps(ready, indent=2), encoding='utf-8')

    def bounds(node):
        return list(map(int, re.findall(r'\d+', node.get('bounds', ''))))

    def action(name, label, saved=False):
        m.history()
        # History's TabRow is an item inside its LazyColumn. Returning from a
        # deep file row preserves that scroll position; expose the header
        # before attempting either child-tab selection.
        target_tab = '已保存' if saved else '任务'
        for _ in range(16):
            header = m.dump()
            tabs = [node for node in header.iter('node') if node.get('text') == target_tab
                    and len(bounds(node)) == 4 and bounds(node)[2] > bounds(node)[0] and bounds(node)[3] > bounds(node)[1]]
            if tabs:
                m.tap_node(tabs[0])
                break
            m.scroll('up')
        else:
            raise AssertionError('History child-tab header could not be exposed')
        for _ in range(22):
            tree = m.dump()
            parents = {child: parent for parent in tree.iter() for child in parent}
            for node in [item for item in tree.iter('node') if item.get('text') == name]:
                row = parents.get(node)
                while row is not None:
                    values = [item.get('text', '') for item in row.iter('node')]
                    choices = [item for item in row.iter('node') if item.get('text') == label and item.get('enabled') != 'false']
                    contains_other = any(f['name'] != name and f['name'] in values for f in matrix)
                    contains_progress = any(item.get('content-desc') == '接收进度' for item in row.iter('node'))
                    row_box = bounds(row)
                    if (len(choices) == 1 and not contains_other and not contains_progress and len(row_box) == 4
                            and row_box[3] - row_box[1] < 1000 and (saved or '丢弃' in values)):
                        box = bounds(choices[0])
                        if len(box) == 4 and box[2] > box[0] and box[3] > box[1]:
                            m.tap_node(choices[0])
                            return
                    row = parents.get(row)
            m.scroll('down')
        raise AssertionError('Exact synthetic file UI action absent: ' + label)

    def picture(label):
        path = out / (label + '.png')
        path.write_bytes(m.adb('exec-out', 'screencap', '-p', binary=True))
        screenshots.append(dict(stage=label, path=str(path.relative_to(ROOT)).replace('\\', '/')))

    def saved_record(fixture):
        return next((item for item in m.saved_files() if item['id'] == fixture['entry']['id']), None)

    def media_row(saved):
        if not re.fullmatch(r'content://media/external(?:_primary)?/downloads/\d+', saved['uri']):
            raise AssertionError('Unexpected publication URI')
        return m.adb('shell', 'content', 'query', '--uri', saved['uri'],
                     '--projection', '_id:_display_name:mime_type:_size:is_pending')

    def media_result(fixture, saved):
        row = media_row(saved)
        actual_mime = re.search(r'(?:^|,\s*)mime_type=([^,\r\n]*)', row)
        actual_size = re.search(r'(?:^|,\s*)_size=(\d+)', row)
        actual_mime = actual_mime.group(1) if actual_mime else None
        body = m.shared_bytes(saved['uri'])
        # Android's media scanner recognizes the ISO-BMFF video stream in a
        # valid QuickTime container as video/mp4. Preserve the .mov name and
        # original container MIME in FileFlash; allow only this observed alias
        # in the platform-owned MediaStore record, with unchanged file bytes.
        allowed = ('video/quicktime', 'video/mp4') if fixture['label'] == 'MOV' else (fixture['mime'],)
        details = dict(requestedMime=fixture['mime'], actualMediaStoreMime=actual_mime,
                       platformMimeNormalized=actual_mime != fixture['mime'], displayName=saved['name'],
                       bytes=len(body), sha256=digest(body))
        (out / ('mediastore-' + fixture['label'].lower() + '.json')).write_text(json.dumps(
            dict(expectedName=fixture['name'], expectedMime=fixture['mime'], expectedSha256=digest(fixture['data']),
                 savedRecord=saved, rawMediaStoreRow=row, **details), ensure_ascii=False, indent=2), encoding='utf-8')
        correct = (saved['name'] == fixture['name'] and saved['mime'] == fixture['mime']
                   and ('_display_name=' + fixture['name'] + ',') in row and actual_mime in allowed
                   and actual_size is not None and int(actual_size.group(1)) == len(fixture['data'])
                   and 'is_pending=0' in row and body == fixture['data'])
        return correct, details

    def media_correct(fixture, saved):
        return media_result(fixture, saved)[0]

    def open_saved(fixture, saved):
        action(fixture['name'], '打开', saved=True)
        activity = m.adb('shell', 'dumpsys', 'activity', 'activities')
        (out / ('open-' + fixture['label'].lower() + '-activity.txt')).write_text(activity, encoding='utf-8')
        tree = m.dump()
        picture('open-' + fixture['label'].lower())
        values = [node.get('text', '') for node in tree.iter('node')]
        problems = ('无法打开', '无法播放', "Can't play this video", 'Cannot play', 'Unsupported media', 'No apps can perform')
        correct_intent = (saved['uri'] in activity and ('typ=' + fixture['mime']) in activity
                          and 'act=android.intent.action.VIEW' in activity)
        external = any(node.get('package') not in ('app.fileflash', 'com.android.systemui', 'com.android.launcher3',
                                                    'com.google.android.apps.nexuslauncher', '') for node in tree.iter('node'))
        details = dict(mediaType=saved['mime'], desktopDecodedFixture=True)
        if fixture['label'] == 'MOV':
            resolvers = {mime: m.adb('shell', 'cmd', 'package', 'resolve-activity', '--brief',
                                    '-a', 'android.intent.action.VIEW', '-d', saved['uri'], '-t', mime)
                         for mime in ('video/quicktime', 'video/mp4')}
            no_video_viewer = 'No activity found' in resolvers['video/quicktime']
            chooser = ('ChooserActivity' in activity and 'act=android.intent.action.CHOOSER' in activity
                       and any(node.get('package') == 'android' or 'ChooserActivity' in str(node.attrib) or 'intentresolver' in node.get('package', '')
                               or 'resolver' in node.get('package', '') for node in tree.iter('node')))
            readable = m.shared_bytes(saved['uri']) == fixture['data']
            identity_visible = (saved['name'] in values or saved['uri'].rsplit('/', 1)[-1] in values)
            source = (ROOT / 'app/src/main/java/app/fileflash/Storage.kt').read_text(encoding='utf-8')
            source_contract = all(text in source for text in (
                'Intent(Intent.ACTION_SEND).setType(file.mime).putExtra(Intent.EXTRA_STREAM, uri)',
                '.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)', 'Intent.createChooser('))
            correct_intent = no_video_viewer and chooser and readable and identity_visible and source_contract
            details.update(openMode='share-chooser-no-video-viewer', noViewHandlerForRequestedQuicktimeMime=no_video_viewer,
                           alternateMp4ViewHandlerAvailable='No activity found' not in resolvers['video/mp4'],
                           alternateMp4PlaybackAttempted=False,
                           runtimeChooserObserved=chooser, savedFileIdentityVisible=identity_visible,
                           exactSavedUriReadableWithOriginalBytes=readable,
                           sourceAction='android.intent.action.SEND', sourceMimeFromSavedRecord=saved['mime'],
                           sourceExtraStreamUsesExactSavedUri=True, sourceReadGrantFlag=True,
                           sourceContractVerified=source_contract, storageSourceSha256=digest(source.encode('utf-8')),
                           runtimeInnerExtrasVisible=False, runtimeReadGrantObserved=False,
                           evidenceLimit='System activity dump exposes only outer chooser intent; inner MIME/EXTRA_STREAM/read flag are verified in frozen Storage.open source, not runtime extras.',
                           physicalOrEmulatorVideoPlaybackVerified=False)
        else:
            details.update(openMode='external-view', runtimeViewMimeAndExactSavedUriVerified=correct_intent)
        m.adb('shell', 'input', 'keyevent', '4')
        m.launch()
        return correct_intent and external and not any(problem in value for value in values for problem in problems), details

    def save_and_open(fixture):
        action(fixture['name'], '保存')
        saved = wait(lambda: saved_record(fixture))
        fixture['entry']['saved'] = saved
        save_manifest()
        correct, details = media_result(fixture, saved)
        check('explicit Save publishes correct name MIME and exact bytes: ' + fixture['label'], correct, **details)
        opened, open_details = open_saved(fixture, saved)
        title = ('native Open fallback preserves MIME and exact saved URI without a video viewer: MOV'
                 if fixture['label'] == 'MOV' else 'native Open uses recovered MIME and exact saved URI: ' + fixture['label'])
        check(title, opened, **open_details)

    def own_entry(entry):
        return (re.fullmatch(r'[A-Za-z0-9_-]{1,96}', entry.get('id', ''))
                and entry.get('name', '').startswith(run + '-'))

    def remove_public_fixtures():
        for entry in manifest['uploads']:
            saved = entry.get('saved')
            if saved is None:
                continue
            if (not own_entry(entry) or saved['id'] != entry['id'] or not saved['name'].startswith(run + '-')
                    or not re.fullmatch(r'content://media/external(?:_primary)?/downloads/\d+', saved['uri'])):
                raise AssertionError('Refusing to remove an unowned public file')
            row = media_row(saved)
            if 'No result found' not in row:
                if ('_display_name=' + saved['name'] + ',') not in row or digest(m.shared_bytes(saved['uri'])) != entry['sha256']:
                    raise AssertionError('Published fixture identity or bytes changed; refusing deletion')
                m.adb('shell', 'su', '2000', 'content', 'delete', '--uri', saved['uri'])
            if 'No result found' not in media_row(saved):
                raise AssertionError('Exact synthetic MediaStore row was not removed')
            entry['publicCleanupVerified'] = True
            save_manifest()

    def cleanup_private_and_ledger():
        # No network writer or SharedPreferences process may race precise cleanup.
        m.stop()
        owned = {entry['id']: entry for entry in manifest['uploads']}
        raw = m.adb('exec-out', 'cat', PRIVATE + '/shared_prefs/fileflash.xml')
        tree = ET.fromstring(raw)
        for node in tree:
            if node.get('name') not in ('saved', 'tasks'):
                continue
            values = json.loads(node.text or '[]')
            kept = []
            for item in values:
                identifier = item.get('id', '')
                belongs = identifier in owned if node.get('name') == 'saved' else identifier.startswith('save-') and identifier[5:] in owned
                if belongs:
                    if not item.get('name', '').startswith(run + '-'):
                        raise AssertionError('Refusing to edit another file\'s ledger item')
                else:
                    kept.append(item)
            node.text = json.dumps(kept, ensure_ascii=False)
        local = out / 'fileflash-cleaned-private.xml'
        local.write_bytes(ET.tostring(tree, encoding='utf-8', xml_declaration=True))
        uid = re.search(r'uid:(\d+)', m.adb('shell', 'cmd', 'package', 'list', 'packages', '-U', 'app.fileflash')).group(1)
        m.adb('push', local, PRIVATE + '/shared_prefs/fileflash.xml')
        m.adb('shell', 'chown', uid + ':' + uid, PRIVATE + '/shared_prefs/fileflash.xml')
        m.adb('shell', 'chmod', '660', PRIVATE + '/shared_prefs/fileflash.xml')
        # All successful saves must already have acknowledged their journal.
        journal = m.adb('exec-out', 'cat', PRIVATE + '/shared_prefs/publication-journal.xml', check=False)
        if journal.startswith('<?xml') and any(node.get('name') in owned for node in ET.fromstring(journal)):
            raise AssertionError('Own save journal unexpectedly remains; preserve it for inspection')
        for entry in manifest['uploads']:
            if not own_entry(entry):
                raise AssertionError('Private cleanup ownership guard failed')
            metadata_path = SERVER + '/uploads/' + entry['id'] + '.json'
            metadata_raw = m.adb('exec-out', 'cat', metadata_path, check=False)
            final = entry.get('finalName')
            if metadata_raw.startswith('{'):
                metadata = json.loads(metadata_raw)
                if (metadata.get('id') != entry['id'] or metadata.get('name') != entry['name']
                        or metadata.get('size') != entry['size'] or metadata.get('sha256') != entry['sha256']):
                    raise AssertionError('Refusing private deletion after fixture metadata mismatch')
                final = metadata.get('finalName') or final
            if final:
                if not final.startswith(run + '-') or '/' in final or '\\' in final:
                    raise AssertionError('Unsafe owned received filename')
                target = SERVER + '/received/' + final
                if m.exists(shlex.quote(target)):
                    if m.adb('shell', 'sha256sum', shlex.quote(target)).split()[0] != entry['sha256']:
                        raise AssertionError('Refusing to remove changed private fixture bytes')
                    m.adb('shell', 'rm', '-f', shlex.quote(target))
            for suffix in ('.json', '.json.tmp', '.part'):
                target = SERVER + '/uploads/' + entry['id'] + suffix
                m.adb('shell', 'rm', '-f', target)
                if m.exists(target):
                    raise AssertionError('Own metadata/prefix remains')
            entry['privateCleanupVerified'] = True
            save_manifest()
        received_names = m.adb('shell', 'ls', '-1', SERVER + '/received').splitlines()
        if any(name.startswith(run + '-') for name in received_names):
            raise AssertionError('An own private received file remains after exact cleanup')

    save_manifest()
    try:
        before = m.saved_files()
        index = m.adb('shell', 'content', 'query', '--uri', 'content://media/external/downloads?includePending=1', '--projection', '_id')
        ids = set(re.findall(r'\b_id=(\d+)\b', index))
        if not ids and 'No result found' not in index:
            raise AssertionError('Unable to read the existing Downloads index')
        print('BASELINE: preserving old ledger and readable Downloads.', flush=True)
        for saved in before:
            identifier = re.fullmatch(r'content://media/external(?:_primary)?/downloads/(\d+)', saved['uri'])
            if (identifier and identifier.group(1) in ids) or (not identifier and m.saved_row_exists(saved)):
                baseline_hashes[saved['uri']] = digest(m.shared_bytes(saved['uri']))
        m.stop()
        result = m.adb('install', '-r', args.apk.resolve())
        device_mutated = True
        package_path = m.adb('shell', 'pm', 'path', 'app.fileflash').removeprefix('package:')
        if not package_path.startswith('/data/app/') or not package_path.endswith('/base.apk') or '\n' in package_path:
            raise AssertionError('Unexpected installed APK path')
        installed_hash = m.adb('shell', 'sha256sum', package_path).split()[0]
        check('signed release upgrades and installed APK hash matches', 'Success' in result and installed_hash == expected_hash
              and m.version()['name'] == args.version)
        check('previous saved ledger and readable Downloads unchanged after upgrade', m.saved_files() == before
              and all(digest(m.shared_bytes(uri)) == value for uri, value in baseline_hashes.items()),
              ledgerEntries=len(before), readableDownloads=len(baseline_hashes))
        if api >= 33:
            m.adb('shell', 'pm', 'grant', 'app.fileflash', 'android.permission.POST_NOTIFICATIONS', check=False)
        m.launch()
        host()
        check('independent sender B and receiver C authenticate', session_b != session_c)
        baseline_files = {item['id']: (item['name'], item['mime'], item['size']) for item in snapshot()['files']}
        for fixture in (matrix if not prior else [next(f for f in matrix if f['label'] == 'MOV')]):
            receipt = upload(fixture)
            listing = next((item for item in snapshot()['files'] if item['id'] == receipt['id']), None)
            correct_download, headers = download(fixture)
            correct = (receipt.get('name') == fixture['name']
                  and receipt.get('mime') == fixture['mime'] and receipt.get('sha256') == digest(fixture['data'])
                  and receipt.get('temporary') is True and listing is not None and listing.get('name') == fixture['name']
                  and listing.get('mime') == fixture['mime'] and correct_download)
            if not correct:
                raise AssertionError('Fresh continuation media bytes/name mismatch')
            if not prior:
                check('shortcut filename recovery: ' + fixture['label'], correct,
                      framing='chunked' if fixture['chunked'] else 'Content-Length', decodableMedia=fixture['decodableMedia'], **headers)
            if fixture['label'] == 'PNG':
                # Check real Android publication/open early; do not discover a
                # platform integration failure only after the whole matrix.
                fixture['temporaryBeforeSave'] = (saved_record(fixture) is None and fixture['name'] not in m.media_rows())
                save_and_open(fixture)
        saved_ids = {saved['id'] for saved in m.saved_files()}
        rows = m.media_rows()
        if not prior:
            check('all matrix uploads stay temporary until explicit Save', next(f for f in matrix if f['label'] == 'PNG')['temporaryBeforeSave']
              and all(f['entry']['id'] not in saved_ids and f['name'] not in rows for f in matrix if f['label'] != 'PNG'))
        m.stop()
        m.launch()
        host()
        current = snapshot()
        if not prior:
            check('server restart preserves recovered media names MIME and body bytes', all(
            next((item for item in current['files'] if item['id'] == f['entry']['id']), {}).get('name') == f['name']
            and download(f)[0] for f in matrix))
            check('server restart preserves every pre-existing shared filename', all(
            next(((item['name'], item['mime'], item['size']) for item in current['files'] if item['id'] == identifier), None) == value
            for identifier, value in baseline_files.items()), previousSharedFiles=len(baseline_files))
        for label in (('JPEG', 'MOV') if not prior else ('MOV',)):
            fixture = next(item for item in matrix if item['label'] == label)
            save_and_open(fixture)
        m.stop()
        m.launch()
        host()
        check('saved names and MIME survive a hard process restart', all(
            media_correct(f, saved_record(f)) for f in matrix if f.get('entry', {}).get('saved')),
              testedFreshMedia=[f['label'] for f in matrix if f.get('entry', {}).get('saved')])
        remove_public_fixtures()
        check('precise cleanup removes only this run public MediaStore rows', all(entry.get('publicCleanupVerified')
              for entry in manifest['uploads'] if entry.get('saved')), publicFixtures=sum(bool(e.get('saved')) for e in manifest['uploads']))
        cleanup_private_and_ledger()
        m.launch()
        host()
        current = snapshot()
        check('precise cleanup removes own private bytes metadata and ledger entries', not any(
            item.get('name', '').startswith(run + '-') for item in current['files'] + current['uploads'] + m.saved_files())
              and all(entry.get('privateCleanupVerified') for entry in manifest['uploads']), privateFixtures=len(manifest['uploads']))
        check('pre-existing saved ledger and readable Downloads remain unchanged', m.saved_files() == before
              and all(digest(m.shared_bytes(uri)) == value for uri, value in baseline_hashes.items()),
              ledgerEntries=len(before), readableDownloads=len(baseline_hashes), unreadableBefore=len(before)-len(baseline_hashes))
        check('APK remains exact frozen version after media filename scenarios', m.adb('shell', 'sha256sum', package_path).split()[0] == expected_hash
              and m.version()['name'] == args.version)
        completed = True
    except Exception as error:
        checks.append(dict(name='media filename acceptance completes', passed=False, errorClass=type(error).__name__))
        try:
            picture('failure')
        except Exception:
            pass
        raise
    finally:
        if device_mutated and not completed:
            # Track a publication even if an assertion fired just after Save.
            try:
                for saved in m.saved_files():
                    entry = next((item for item in manifest['uploads'] if item['id'] == saved['id']), None)
                    if entry and saved.get('name', '').startswith(run + '-'):
                        entry['saved'] = saved
                save_manifest()
                remove_public_fixtures()
                cleanup_private_and_ledger()
                m.launch()
                host()
            except Exception as error:
                cleanup_errors.append(type(error).__name__)
        for secret in manifest['sessions']:
            try:
                m.request(secret, 'DELETE', '/api/session')
            except urllib.error.HTTPError as error:
                if error.code != 401:
                    cleanup_errors.append(type(error).__name__)
            except Exception as error:
                cleanup_errors.append(type(error).__name__)
        if cleanup_errors:
            completed = False
            checks.append(dict(name='all exact media fixtures cleanly removed', passed=False, errorClasses=cleanup_errors))
        manifest['completed'] = completed
        save_manifest()
        report()
    print('READY: media filename acceptance completed; server remains running; credentials stay private.', flush=True)


if __name__ == '__main__':
    main()
