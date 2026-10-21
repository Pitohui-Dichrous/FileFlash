"""Global server receive-feedback acceptance on a dedicated Android emulator.

Uses the actual signed APK and real, paced network bodies. Desktop HTTP senders
exercise the browser stream, system-share chunked and native 2 MiB chunk
protocols; they do not claim to be physical iPhone, Shortcuts, browsers or native
sender UI tests. Never uninstalls/clears data or deletes pre-existing files.
Credentials and exact cleanup identities remain under private .test-data.
"""
from __future__ import annotations

import argparse
import hashlib
import http.client
import importlib.util
import io
import json
import os
from pathlib import Path
import re
import socket
import threading
import time
import urllib.error
from urllib.parse import quote, urlsplit
import uuid
import xml.etree.ElementTree as ET

from PIL import Image


ROOT = Path(__file__).resolve().parent.parent
PRIVATE = '/data/user/0/app.fileflash/files/server'
TABS = ('共享', '连接', '文字', '记录')
ACTIVE = '接收中'
WAITING = '等待数据'
COMPLETE = '已接收'
INTERRUPTED = '接收中断'
spec = importlib.util.spec_from_file_location('host_receive_relay_helpers', ROOT / 'scripts/android-relay-smoke.py')
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)


def sha(data):
    return hashlib.sha256(data).hexdigest()


def pattern(size, seed):
    """Nonpersonal, bounded-memory source bytes with a run-specific tail."""
    data = bytearray((bytes(range(256)) * ((size + 255) // 256))[:size])
    data[-32:] = hashlib.sha256(seed.encode()).digest()
    return bytes(data)


class PacedUpload:
    """One upload with genuine TCP backpressure and explicit pause/finish gates."""
    def __init__(self, base, session, name, data, kind, create, remember):
        self.endpoint = urlsplit(base)
        self.session, self.name, self.data, self.kind = session, name, data, kind
        self.create, self.remember = create, remember
        self.item = None
        self.sent = 0
        self.committed = 0
        self.response = None
        self.failure = None
        self.request_id = str(uuid.uuid4())
        self.started = threading.Event()
        self.finished = threading.Event()
        self.paused = threading.Event()
        self.fast = threading.Event()
        self.cancelled = threading.Event()
        self.connection = None
        self.thread = None

    def start(self):
        if self.kind != 'shortcuts':
            self.item = self.create(self.name, len(self.data), self.kind == 'stream')
            self.remember(self.item)
        self.thread = threading.Thread(target=self._run, name='host-feedback-' + self.kind, daemon=True)
        self.thread.start()
        if not self.started.wait(10):
            raise AssertionError('Paced upload never started')
        return self

    def _gate(self):
        while self.paused.is_set() and not self.cancelled.is_set():
            time.sleep(.04)
        if self.cancelled.is_set():
            raise InterruptedError('Synthetic sender interrupted')

    def _request(self, method, path, data, headers=None, chunked=False, offset=0):
        connection = http.client.HTTPConnection(self.endpoint.hostname, self.endpoint.port, timeout=40)
        self.connection = connection
        connection.putrequest(method, path)
        all_headers = {'Authorization': 'Bearer ' + self.session,
                       'Content-Type': 'application/octet-stream',
                       'X-Request-ID': self.request_id, 'Connection': 'close'}
        all_headers.update(headers or {})
        all_headers['Transfer-Encoding' if chunked else 'Content-Length'] = 'chunked' if chunked else str(len(data))
        for name, value in all_headers.items():
            connection.putheader(name, value)
        connection.endheaders()
        self.started.set()
        try:
            for position in range(0, len(data), 64 * 1024):
                self._gate()
                piece = data[position:position + 64 * 1024]
                connection.send((('%x\r\n' % len(piece)).encode() + piece + b'\r\n') if chunked else piece)
                self.sent = offset + position + len(piece)
                if not self.fast.is_set():
                    # 640 KiB/s leaves a stable, genuinely receiving UI to inspect.
                    self.cancelled.wait(.1)
            if chunked:
                connection.send(b'0\r\n\r\n')
            response = connection.getresponse()
            raw = response.read()
            if response.status != 200:
                raise AssertionError('Actual APK rejected paced body with status ' + str(response.status))
            return json.loads(raw)
        finally:
            connection.close()
            self.connection = None

    def _run(self):
        try:
            if self.kind == 'shortcuts':
                self.response = self._request('POST', '/api/shortcuts/files?name=' + quote(self.name, safe=''),
                                              self.data, chunked=True)
            elif self.kind == 'stream':
                self.response = self._request('POST', '/api/uploads/' + self.item['id'] + '/stream', self.data,
                                              {'X-Upload-Key': self.item['resumeKey']})
            elif self.kind == 'native':
                for offset in range(0, len(self.data), 2 * 1024 * 1024):
                    self._gate()
                    chunk = self.data[offset:offset + 2 * 1024 * 1024]
                    answer = self._request('PUT', '/api/uploads/' + self.item['id'], chunk,
                                           {'X-Upload-Key': self.item['resumeKey'], 'X-Offset': str(offset),
                                            'X-Chunk-SHA256': sha(chunk)}, offset=offset)
                    self.committed = answer['offset']
                    if self.committed != offset + len(chunk):
                        raise AssertionError('Unexpected native committed offset')
                self.response = m.request(self.session, 'POST', '/api/uploads/' + self.item['id'] + '/complete',
                                          {'sha256': sha(self.data)}, {'X-Upload-Key': self.item['resumeKey']})
            else:
                raise AssertionError('Unknown synthetic protocol')
        except Exception as error:
            self.failure = error
        finally:
            self.finished.set()

    def finish(self, timeout=35):
        self.fast.set()
        self.paused.clear()
        if not self.finished.wait(timeout):
            raise AssertionError('Paced upload failed to finish')
        if self.failure is not None:
            raise self.failure
        return self.response

    def abort(self):
        self.cancelled.set()
        self.paused.clear()
        connection = self.connection
        if connection is not None and connection.sock is not None:
            try:
                connection.sock.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
            connection.close()
        if self.thread is not None:
            self.thread.join(6)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apk', required=True, type=Path)
    parser.add_argument('--version', required=True)
    parser.add_argument('--serial', default=os.environ.get('ANDROID_SERIAL', 'emulator-5554'))
    parser.add_argument('--evidence', required=True, type=Path)
    parser.add_argument('--ready', type=Path, help='Private final live server connection information')
    args = parser.parse_args()
    if os.environ.get('FILEFLASH_DEDICATED_EMULATOR') != '1' or args.serial not in ('emulator-5554', 'emulator-5556'):
        raise RuntimeError('Opt in to one of the two dedicated emulators; physical devices are forbidden')
    expected_hash = sha(args.apk.resolve(strict=True).read_bytes())
    ready_path = args.ready or ROOT / '.test-data' / ('host-receive-' + args.serial + '-ready.json')
    ready_path.resolve().relative_to((ROOT / '.test-data').resolve())
    m.SERIAL = args.serial
    # Only read-only emulator checks are allowed before any device mutation.
    if m.adb('shell', 'getprop', 'ro.kernel.qemu') != '1' or 'uid=0(root)' not in m.adb('shell', 'id'):
        raise RuntimeError('Dedicated emulator must already be booted with adb root')
    api = int(m.adb('shell', 'getprop', 'ro.build.version.sdk'))
    if api not in (29, 34):
        raise RuntimeError('Only dedicated API29 and API34 images are accepted')
    run = 'host-receive-' + str(api) + '-' + uuid.uuid4().hex[:10]
    out = ROOT / '.test-data' / 'host-receive-ui' / run
    out.mkdir(parents=True)
    m.OUT = out
    manifest_path = out / 'cleanup-manifest.json'
    manifest = dict(run=run, serial=args.serial, apkSha256=expected_hash, uploads=[], sessions=[])
    checks, screenshots, workers = [], [], []
    completed = False
    installed_hash = None
    session_b = session_c = None
    ready = None
    settings_before = {}
    geometry_before = {}
    saved_before, old_hashes, baseline_files = [], {}, set()
    cleanup_errors = []

    def cancel_requested():
        marker = out / 'cancel-requested'
        if not marker.exists():
            return False
        # Consume this run's one-shot request before raising, so waits in the
        # mandatory finally cleanup remain usable.
        marker.unlink()
        return True

    def save_manifest():
        manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding='utf-8')

    def report():
        payload = dict(environment=f'Dedicated rooted Android API{api} emulator; actual signed APK server and native receiving UI; paced desktop HTTP protocol fixtures for browser stream, Shortcuts chunked and native PUT; not physical iPhone/Shortcuts/browser/native-sender/Wi-Fi testing',
                       apiLevel=api, version=args.version, apkSha256=expected_hash, installedApkSha256=installed_hash,
                       passed=completed and bool(checks) and all(item['passed'] for item in checks),
                       tests=checks, screenshots=screenshots)
        args.evidence.parent.mkdir(parents=True, exist_ok=True)
        args.evidence.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding='utf-8')
        (out / 'results.json').write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding='utf-8')

    def check(name, condition, **details):
        if cancel_requested():
            raise InterruptedError('QA cancellation requested by parent')
        checks.append(dict(name=name, passed=bool(condition), **details))
        report()
        print(('PASS: ' if condition else 'FAIL: ') + name, flush=True)
        if not condition:
            raise AssertionError(name)

    def wait(callback, timeout=25):
        deadline, last = time.monotonic() + timeout, None
        while time.monotonic() < deadline:
            if cancel_requested():
                raise InterruptedError('QA cancellation requested by parent')
            try:
                result = callback()
                if result:
                    return result
            except Exception as error:
                last = error
            time.sleep(.15)
        raise AssertionError('Timed out waiting for actual server receive feedback') from last

    def ui(label='window'):
        m.adb('shell', 'uiautomator', 'dump', '/sdcard/fileflash-host-receive-window.xml')
        raw = m.adb('exec-out', 'cat', '/sdcard/fileflash-host-receive-window.xml')
        (out / (label + '.xml')).write_text(raw, encoding='utf-8')
        return ET.fromstring(raw)

    def texts(node):
        return [value.get('text', '') for value in node.iter('node')] if node is not None else []

    def bounds(node):
        return list(map(int, re.findall(r'-?\d+', node.get('bounds', '')))) if node is not None else []

    def selected_tab(tree):
        for node in tree.iter('node'):
            if node.get('selected') == 'true':
                labels = [label for label in TABS if label in texts(node)]
                if len(labels) == 1:
                    return labels[0]
        return None

    def tap_tab(label):
        tree = ui()
        candidates = [node for node in tree.iter('node') if node.get('text') == label and len(bounds(node)) == 4]
        if not candidates:
            raise AssertionError('Navigation tab absent: ' + label)
        target = max(candidates, key=lambda node: bounds(node)[1])
        left, top, right, bottom = bounds(target)
        m.adb('shell', 'input', 'tap', (left + right) // 2, (top + bottom) // 2)
        time.sleep(.2)

    def feedback(tree):
        anchors = [node for node in tree.iter('node') if node.get('content-desc') == '接收进度']
        if not anchors:
            return None
        parents = {child: parent for parent in tree.iter() for child in parent}
        # The global card can put its label on the outer card or the progress bar.
        # Find its smallest ancestor containing actual receive-status text.
        for anchor in sorted(anchors, key=lambda node: bounds(node)[1] if len(bounds(node)) == 4 else 99999):
            candidate = anchor
            while candidate is not None:
                if any(value.startswith((ACTIVE, WAITING, COMPLETE, INTERRUPTED)) for value in texts(candidate)):
                    return candidate
                candidate = parents.get(candidate)
        return None

    def feedback_state(expected, expected_tab=None, label='window'):
        tree = ui(label)
        card = feedback(tree)
        values = texts(card)
        if expected not in values and not any(value.startswith(expected + ' ') for value in values):
            return None
        if expected_tab is not None and selected_tab(tree) != expected_tab:
            return None
        return tree, card

    def picture(label, tree=None, card=None):
        raw = m.adb('exec-out', 'screencap', '-p', binary=True)
        raw_path = out / (label + '-private-full.png')
        raw_path.write_bytes(raw)
        # Keep QR codes, pairing links, existing filenames and clients private.
        # Publicly referenced evidence contains only the top bar and receive card.
        if card is not None and len(bounds(card)) == 4:
            image = Image.open(io.BytesIO(raw))
            left, top, right, bottom = bounds(card)
            visible_bottom = max(1, min(image.height, bottom + 8))
            public_path = out / (label + '.png')
            image.crop((0, 0, image.width, visible_bottom)).save(public_path)
            screenshots.append(dict(stage=label, path=str(public_path.relative_to(ROOT)).replace('\\', '/')))
        return raw_path

    def within_viewport(tree, card):
        box = bounds(card)
        all_boxes = [bounds(node) for node in tree.iter('node') if len(bounds(node)) == 4]
        width = max(value[2] for value in all_boxes)
        height = max(value[3] for value in all_boxes)
        navigation = [bounds(node) for node in tree.iter('node')
                      if node.get('resource-id') == 'android:id/navigationBarBackground' and len(bounds(node)) == 4
                      and bounds(node)[2] > bounds(node)[0] and bounds(node)[3] > bounds(node)[1]]
        def disjoint(a, b):
            return a[2] <= b[0] or a[0] >= b[2] or a[3] <= b[1] or a[1] >= b[3]
        return (len(box) == 4 and 0 <= box[0] < box[2] <= width and 0 <= box[1] < box[3] <= height
                and all(disjoint(box, nav) for nav in navigation)
                and all(box[0] <= bounds(node)[0] < bounds(node)[2] <= box[2]
                        and box[1] <= bounds(node)[1] < bounds(node)[3] <= box[3]
                        for node in card.iter('node') if node.get('text') and len(bounds(node)) == 4))

    def has_bytes(card):
        return any(re.search(r'\d(?:[\d.,]*\d)?\s*(?:B|KB|MB|GB)', value) for value in texts(card))

    def percentages(card):
        return [int(value) for text in texts(card) for value in re.findall(r'(?<!\d)(\d{1,3})\s*%', text)]

    def state():
        return m.request(session_c, 'GET', '/api/state')

    def own_items():
        return [item for item in state()['uploads'] if item.get('name', '').startswith(run + '-')]

    def remember(item):
        if not re.fullmatch(r'[A-Za-z0-9_-]{1,96}', item.get('id', '')) or not item.get('name', '').startswith(run + '-'):
            raise AssertionError('Refusing a cleanup identity outside this run')
        entry = next((record for record in manifest['uploads'] if record['id'] == item['id']), None)
        if entry is None:
            entry = dict(id=item['id'], name=item['name'], ownerSession=session_b)
            manifest['uploads'].append(entry)
        if item.get('resumeKey'):
            entry['resumeKey'] = item['resumeKey']
        if item.get('sha256'):
            entry['sha256'] = item['sha256']
        save_manifest()  # Exact identity is durable before any additional operation.
        raw = m.adb('exec-out', 'cat', PRIVATE + '/uploads/' + entry['id'] + '.json', check=False)
        if raw.startswith('{'):
            metadata = json.loads(raw)
            if metadata.get('id') == entry['id'] and metadata.get('name', '').startswith(run + '-'):
                for key in ('resumeKey', 'finalName'):
                    if metadata.get(key):
                        entry[key] = metadata[key]
                save_manifest()
        return entry

    def create(name, size, restart_only):
        item = m.request(session_b, 'POST', '/api/uploads', dict(name=name, size=size,
                                                               mime='application/octet-stream', restartOnly=restart_only))
        remember(item)
        return item

    def start(kind, label, size=64 * 1024 * 1024):
        name = run + '-' + label + '.bin'
        worker = PacedUpload(m.BASE, session_b, name, pattern(size, name), kind, create, remember)
        workers.append(worker)
        worker.start()
        if kind == 'shortcuts':
            item = wait(lambda: next((item for item in own_items() if item['name'] == name), None))
            worker.item = remember(item)
        return worker

    def no_public_copy(worker):
        return (not any(item['id'] == worker.item['id'] for item in m.saved_files())
                and worker.name not in m.media_rows())

    def finished_correctly(worker, capture_label=None, expected_tab=None):
        receipt = worker.finish()
        remember(receipt)
        if capture_label is not None:
            # Capture the four-second terminal state before large-file download
            # verification or MediaStore queries can outlive its visibility.
            worker.completed_ui = wait(lambda: feedback_state(COMPLETE, expected_tab, capture_label), 8)
            picture(capture_label, *worker.completed_ui)
        return (receipt.get('status') == 'complete' and receipt.get('offset') == len(worker.data)
                and receipt.get('sha256') == sha(worker.data) and receipt.get('temporary') is True
                and m.request(session_c, 'GET', '/api/files/' + receipt['id']) == worker.data)

    def gone(entry):
        if any(item['id'] == entry['id'] for item in state()['uploads'] + state()['files']):
            return False
        for suffix in ('.part', '.json', '.json.tmp'):
            if m.exists(PRIVATE + '/uploads/' + entry['id'] + suffix):
                return False
        final = entry.get('finalName')
        if final is not None and ('/' in final or '\\' in final or not final.startswith(run + '-')):
            raise AssertionError('Unsafe private cleanup path')
        return final is None or not m.exists(PRIVATE + '/received/' + final)

    def delete_own(entry):
        if not gone(entry):
            try:
                m.request(session_c, 'DELETE', '/api/uploads/' + entry['id'],
                          headers={'X-Upload-Key': entry['resumeKey']} if entry.get('resumeKey') else {})
            except urllib.error.HTTPError as error:
                body = json.loads(error.read())
                if error.code != 409 or body.get('code') != 'already_complete':
                    raise
                # The unchanged native protocol's DELETE cancels incomplete
                # uploads only. Discard its completed temporary file through
                # the real UI, using the verified exact synthetic filename.
                discard_own_native(entry)
            wait(lambda: gone(entry))
        entry['cleanupVerified'] = True
        save_manifest()

    def discard_own_native(entry):
        if not entry['name'].startswith(run + '-'):
            raise AssertionError('Refusing to discard another run\'s file')
        m.history()
        for _ in range(3):
            m.scroll('up')
        for _ in range(24):
            tree = ui()
            parents = {child: parent for parent in tree.iter() for child in parent}
            for name in [node for node in tree.iter('node') if node.get('text') == entry['name']]:
                row = parents.get(name)
                while row is not None:
                    values = texts(row)
                    # A single temporary row, not the whole page or top card.
                    if values.count('丢弃') == 1 and values.count('保存') == 1 and len(bounds(row)) == 4:
                        target = next(node for node in row.iter('node') if node.get('text') == '丢弃')
                        left, top, right, bottom = bounds(target)
                        m.adb('shell', 'input', 'tap', (left + right)//2, (top + bottom)//2)
                        def own_dialog():
                            current = ui()
                            return current if '丢弃临时文件？' in texts(current) else None
                        dialog = wait(own_dialog)
                        # The confirmation must name exactly the tracked item.
                        if not any(value.startswith(entry['name'] + '\n') for value in texts(dialog)):
                            raise AssertionError('Discard dialog does not match the exact cleanup manifest')
                        button = next(node for node in dialog.iter('node') if node.get('text') == '丢弃')
                        left, top, right, bottom = bounds(button)
                        m.adb('shell', 'input', 'tap', (left + right)//2, (top + bottom)//2)
                        return
                    row = parents.get(row)
            m.scroll('down')
        raise AssertionError('Own native temporary file discard action not found')

    def setting(namespace, name, value):
        key = (namespace, name)
        if key not in settings_before:
            settings_before[key] = m.adb('shell', 'settings', 'get', namespace, name)
        m.adb('shell', 'settings', 'put', namespace, name, value)

    def restore_device():
        for namespace, name in settings_before:
            value = settings_before[(namespace, name)]
            m.adb('shell', 'settings', 'delete' if value == 'null' else 'put', namespace, name,
                  *([] if value == 'null' else [value]))
        for key in ('size', 'density'):
            if key in geometry_before:
                previous = re.search(r'Override ' + key + r':\s*(\S+)', geometry_before[key], re.IGNORECASE)
                m.adb('shell', 'wm', key, previous.group(1) if previous else 'reset')

    def setup_host():
        nonlocal session_b, session_c, ready
        session_b, session_c, ready = m.host()
        manifest['sessions'] += [session_b, session_c]
        manifest['url'] = m.BASE
        save_manifest()
        ready.update(version=args.version, serverApkSha256=installed_hash, serial=args.serial)
        ready_path.parent.mkdir(parents=True, exist_ok=True)
        ready_path.write_text(json.dumps(ready, indent=2), encoding='utf-8')

    save_manifest()
    try:
        saved_before = m.saved_files()
        # One provider index query replaces an expensive IPC for every stale
        # historical ledger URI. Actual readable bytes are still re-hashed.
        provider_index = m.adb('shell', 'content', 'query', '--uri', 'content://media/external/downloads?includePending=1',
                               '--projection', '_id')
        existing_ids = set(re.findall(r'\b_id=(\d+)\b', provider_index))
        if not existing_ids and 'No result found' not in provider_index:
            raise AssertionError('Unable to enumerate the Downloads baseline')
        print('BASELINE: preserving existing ledger and re-hashing readable Downloads.', flush=True)
        for saved in saved_before:
            media_uri = re.fullmatch(r'content://media/external(?:_primary)?/downloads/(\d+)', saved['uri'])
            readable = media_uri.group(1) in existing_ids if media_uri else m.saved_row_exists(saved)
            if readable:
                old_hashes[saved['uri']] = sha(m.shared_bytes(saved['uri']))
        m.stop()
        result = m.adb('install', '-r', args.apk.resolve())
        package_path = m.adb('shell', 'pm', 'path', 'app.fileflash').removeprefix('package:')
        if not package_path.startswith('/data/app/') or not package_path.endswith('/base.apk') or '\n' in package_path:
            raise RuntimeError('Unexpected installed package path')
        installed_hash = m.adb('shell', 'sha256sum', package_path).split()[0]
        check('final signed APK upgrades in place and actual installed bytes match', 'Success' in result
              and m.version()['name'] == args.version and installed_hash == expected_hash, apiLevel=api)
        check('upgrade retains previous saved ledger and readable Downloads', m.saved_files() == saved_before
              and all(sha(m.shared_bytes(uri)) == digest for uri, digest in old_hashes.items()),
              ledgerEntries=len(saved_before), readableDownloads=len(old_hashes))
        if api >= 33:
            m.adb('shell', 'pm', 'grant', 'app.fileflash', 'android.permission.POST_NOTIFICATIONS', check=False)
        m.launch()
        setup_host()
        baseline_files = {item['id'] for item in state()['files']}
        time.sleep(1.2)
        first_ui = ui('baseline-no-incoming')
        check('starting server does not replay old uploads as newly received', feedback(first_ui) is None,
              previousSharedFiles=len(baseline_files))
        # Begin on a different tab: an incoming upload must not take navigation away.
        tap_tab('文字')
        stream = start('stream', '网页-长文件名用于验证大字号全局收件进度与状态完整可读')
        tree, card = wait(lambda: feedback_state(ACTIVE, '文字', 'stream-progress'))
        check('Content-Length browser stream exposes immediate global receiving feedback without switching tab',
              stream.sent > 0 and within_viewport(tree, card) and has_bytes(card), selectedTab=selected_tab(tree))
        check('known-length upload displays a bounded nonfinal percentage', bool(percentages(card))
              and all(0 <= value < 100 for value in percentages(card)), percentages=percentages(card))
        picture('stream-progress', tree, card)
        # Exercise the formerly clipped layout first, on this same live source.
        # A failed inset check should not consume the rest of a full test run.
        setting('system', 'font_scale', '1.3')
        geometry_before['size'] = m.adb('shell', 'wm', 'size')
        geometry_before['density'] = m.adb('shell', 'wm', 'density')
        m.adb('shell', 'wm', 'size', '720x1280')
        m.adb('shell', 'wm', 'density', '320')
        setting('system', 'accelerometer_rotation', '0')
        setting('system', 'user_rotation', '0')
        time.sleep(.6)
        tree, card = wait(lambda: feedback_state(ACTIVE, '文字', 'layout-large-font-small-screen'))
        check('long filename, 1.3 font and 360dp screen retain visible state and byte progress',
              within_viewport(tree, card) and has_bytes(card)
              and any(stream.name == value for value in texts(card)), fontScale=1.3, screenDp='360x640')
        picture('large-font-small-screen', tree, card)
        setting('system', 'user_rotation', '1')
        time.sleep(.6)
        tree, card = wait(lambda: feedback_state(ACTIVE, '文字', 'layout-landscape'))
        check('landscape retains the entire global receive status without tab change',
              within_viewport(tree, card) and has_bytes(card), cardBounds=bounds(card),
              navigationBarBounds=[bounds(node) for node in tree.iter('node')
                                   if node.get('resource-id') == 'android:id/navigationBarBackground'],
              zeroNavigationFrameNeedsVisualReview=any(bounds(node) == [0, 0, 0, 0] for node in tree.iter('node')
                                                      if node.get('resource-id') == 'android:id/navigationBarBackground'))
        picture('large-font-small-landscape', tree, card)
        for name in ('animator_duration_scale', 'transition_animation_scale', 'window_animation_scale'):
            setting('global', name, '0')
        tree, card = wait(lambda: feedback_state(ACTIVE, '文字', 'layout-animation-off'))
        check('system animation disabled retains known-length feedback and progress information',
              within_viewport(tree, card) and has_bytes(card) and bool(percentages(card)))
        picture('animations-disabled', tree, card)
        restore_device()
        for tab in TABS:
            tap_tab(tab)
            tree, card = wait(lambda tab=tab: feedback_state(ACTIVE, tab, 'global-' + str(TABS.index(tab))))
            check('receiving progress remains global on ' + tab + ' tab', within_viewport(tree, card)
                  and has_bytes(card), selectedTab=selected_tab(tree))
            picture('global-tab-' + str(TABS.index(tab)), tree, card)
        check('stream source is still in flight during cross-tab observations', not stream.finished.is_set()
              and 0 < stream.sent < len(stream.data))
        check('browser stream finishes as one unchanged temporary file for independent client C', finished_correctly(stream, 'stream-complete', '记录'),
              bytes=len(stream.data), sha256=sha(stream.data))
        tree, card = stream.completed_ui
        check('server completion says received rather than saved', COMPLETE in texts(card)
              and not any('已保存' in text for text in texts(card)) and no_public_copy(stream))
        check('layout fixture completes without changing the temporary-storage contract', no_public_copy(stream))
        wait(lambda: feedback(ui()) is None, 12)
        check('completed feedback expires without becoming a persistent old receive event', True, terminalDisplayMs=4000)

        # Unknown-length Shortcuts transfer: received byte count is useful, 0/0 or
        # a fabricated percentage is not. The actual progress node is inspected.
        tap_tab('连接')
        shortcuts = start('shortcuts', 'Shortcuts-unknown-length', 16 * 1024 * 1024 + 37)
        tree, card = wait(lambda: feedback_state(ACTIVE, '连接', 'shortcuts-unknown'))
        progress_nodes = [node for node in card.iter('node') if node.get('class') == 'android.widget.ProgressBar'
                          or node.get('content-desc') == '接收进度']
        check('chunked Shortcuts protocol shows received bytes with an indeterminate progress node',
              has_bytes(card) and not percentages(card) and bool(progress_nodes)
              and not any('/ 0 B' in value or '0 / 0' in value for value in texts(card)),
              knownLength=False)
        picture('shortcuts-unknown-length', tree, card)
        shortcuts.paused.set()
        tree, card = wait(lambda: feedback_state(WAITING, '连接', 'waiting-for-data'), 20)
        bytes_at_pause = shortcuts.sent
        check('paused sender becomes waiting for data without a false failure or completion',
              has_bytes(card) and not shortcuts.finished.is_set() and not percentages(card),
              senderBytes=bytes_at_pause)
        picture('waiting-for-data', tree, card)
        shortcuts.paused.clear()
        tree, card = wait(lambda: feedback_state(ACTIVE, '连接', 'resumed-data'))
        check('new bytes return waiting feedback to receiving on the current tab',
              shortcuts.sent > bytes_at_pause and has_bytes(card))
        check('chunked Shortcuts protocol finishes unchanged without automatic Downloads',
              finished_correctly(shortcuts, 'shortcuts-complete', '连接') and no_public_copy(shortcuts), bytes=len(shortcuts.data), sha256=sha(shortcuts.data))
        wait(lambda: feedback(ui()) is None, 12)

        native = start('native', 'native-2MiB-chunks', 12 * 1024 * 1024 + 19)
        wait(lambda: native.committed > 0)
        tree, card = wait(lambda: feedback_state(ACTIVE, '连接', 'native-progress'))
        check('native chunk protocol shows committed byte and percentage progress globally',
              has_bytes(card) and bool(percentages(card)) and any(0 < value < 100 for value in percentages(card)),
              committedBytes=native.committed)
        picture('native-chunk-progress', tree, card)
        check('native chunk protocol completes unchanged as temporary without Downloads',
              finished_correctly(native, 'native-complete', '连接') and no_public_copy(native), bytes=len(native.data), sha256=sha(native.data))
        wait(lambda: feedback(ui()) is None, 12)

        interrupted = start('stream', 'interrupted', 12 * 1024 * 1024)
        wait(lambda: feedback_state(ACTIVE, '连接'))
        wait(lambda: interrupted.sent >= 512 * 1024)
        interrupted.abort()
        tree, card = wait(lambda: feedback_state(INTERRUPTED, '连接', 'interrupted'))
        interruption_observed = time.monotonic()
        picture('interrupted', tree, card)
        # Reproduce the reported regression while the previous outcome is
        # still visible: the new small file must own a fresh feedback batch.
        quick = start('stream', 'fresh-after-interruption', 32 * 1024 + 13)
        delay_after_interruption_ms = int((time.monotonic() - interruption_observed) * 1000)
        quick.fast.set()
        quick_valid = finished_correctly(quick, 'new-small-after-interruption', '连接')
        quick_tree, quick_card = quick.completed_ui
        check('connection interruption is visible without claiming a received or saved file',
              not any(COMPLETE in value or '已保存' in value for value in texts(card))
              and no_public_copy(interrupted) and gone(remember(interrupted.item)))
        check('a fast new file inside interruption display window clears the old batch outcome',
              delay_after_interruption_ms < 4000 and quick_valid and quick.name in texts(quick_card)
              and COMPLETE in texts(quick_card) and INTERRUPTED not in texts(quick_card)
              and not any(re.search(r'\d+\s*个文件', value) for value in texts(quick_card))
              and no_public_copy(quick), newUploadStartedMsAfterInterruptionObservation=delay_after_interruption_ms,
              bytes=len(quick.data), selectedTab=selected_tab(quick_tree))
        wait(lambda: feedback(ui()) is None, 12)

        concurrent_a = start('stream', 'concurrent-known', 24 * 1024 * 1024)
        concurrent_b = start('shortcuts', 'concurrent-unknown', 24 * 1024 * 1024 + 7)
        tree, card = wait(lambda: feedback_state(ACTIVE, '连接', 'concurrent'))
        # Aggregate text must convey that at least two files are active. Filename
        # rendering is intentionally not prescribed for a multiple-file card.
        check('concurrent known and unknown uploads aggregate as two active files',
              any(re.search(r'2\s*(?:个|项|份|文件)', value) for value in texts(card))
              and has_bytes(card) and not percentages(card), activeFiles=2)
        picture('concurrent-two-files', tree, card)
        concurrent_a.finish()
        tree, card = wait(lambda: feedback_state(ACTIVE, '连接', 'concurrent-one-remains'))
        check('one completion does not replace the remaining active upload with a completed banner',
              not concurrent_b.finished.is_set() and COMPLETE not in texts(card))
        check('both concurrent files remain temporary and byte-identical', finished_correctly(concurrent_a)
              and finished_correctly(concurrent_b) and no_public_copy(concurrent_a) and no_public_copy(concurrent_b))
        wait(lambda: feedback(ui()) is None, 12)

        stopping = start('stream', 'stop-while-receiving')
        wait(lambda: feedback_state(ACTIVE, '连接'))
        wait(lambda: stopping.sent > 512 * 1024)
        m.stop_host()
        stopping.abort()
        check('stopping an actively receiving server clears global feedback immediately',
              feedback(ui('stopped-no-feedback')) is None)
        setup_host()
        time.sleep(1)
        check('restarting server begins without stale received or interrupted feedback',
              feedback(ui('restarted-no-feedback')) is None)

        # Every own object is removed using its exact id and recovery key. This
        # also checks recovery-key cleanup after server sessions were revoked.
        for entry in manifest['uploads']:
            delete_own(entry)
        check('exact-ID cleanup removes all this run temporary records and bytes only',
              not own_items() and all(gone(entry) for entry in manifest['uploads'])
              and baseline_files <= {item['id'] for item in state()['files']})
        m.stop()
        m.launch()
        setup_host()
        time.sleep(1)
        check('hard process restart does not replay old server receives', feedback(ui('process-restart-no-feedback')) is None)
        check('previous saved ledger and readable Downloads remain unchanged after all scenarios',
              all(record in m.saved_files() for record in saved_before)
              and all(sha(m.shared_bytes(uri)) == digest for uri, digest in old_hashes.items()),
              ledgerEntries=len(saved_before), readableDownloads=len(old_hashes), unreadableBefore=len(saved_before)-len(old_hashes))
        ready.update(version=args.version, serverApkSha256=installed_hash, serial=args.serial)
        ready_path.parent.mkdir(parents=True, exist_ok=True)
        ready_path.write_text(json.dumps(ready, indent=2), encoding='utf-8')
        completed = True
    except Exception as error:
        checks.append(dict(name='server receive-feedback acceptance finishes', passed=False, errorClass=type(error).__name__))
        try:
            failure_ui = ui('failure')
            picture('failure', failure_ui, feedback(failure_ui))
        except Exception:
            pass
        raise
    finally:
        for worker in workers:
            if not worker.finished.is_set():
                worker.abort()
        try:
            restore_device()
        except Exception as error:
            cleanup_errors.append(type(error).__name__)
        # Cleanup remains idempotent even after a mid-test assertion or EOF.
        if session_c is not None:
            try:
                for item in own_items():
                    remember(item)
                for entry in manifest['uploads']:
                    delete_own(entry)
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
            checks.append(dict(name='all own fixtures and device settings cleanly restored', passed=False, errorClasses=cleanup_errors))
        manifest['completed'] = completed
        save_manifest()
        report()
    print('READY: actual APK server remains running; pairing information stays private.', flush=True)


if __name__ == '__main__':
    main()
