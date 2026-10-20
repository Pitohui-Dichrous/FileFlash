"""Receive feedback acceptance on one dedicated rooted Android emulator.

Runs the signed Android application against the real Java TransferServer with
synthetic throttled Sources. No physical Wi-Fi or iOS claim. Never uninstalls,
clears application data, or removes files outside this run's fixture names.
Requires an existing :core:installDist and FILEFLASH_DEDICATED_EMULATOR=1.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import time
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parent.parent
PRIVATE = '/data/user/0/app.fileflash'
JAVA_FIXTURE = r'''
import app.fileflash.core.*;
import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public final class ReceiveUiServer {
  public static void main(String[] args) throws Exception {
    File dir = new File(args[0]); String run = args[1];
    TransferServer server = new TransferServer(new File(dir,"storage"), p -> null);
    JsonArray files = new JsonArray();
    add(server, files, run + "-正常下载-很长的文件名称用于检查大字号和操作按钮.bin", 12*1024*1024, false);
    add(server, files, run + "-失败后重试.bin", 2*1024*1024, true);
    add(server, files, run + "-暂停重试.bin", 12*1024*1024, false);
    server.start(0);
    Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
    JsonObject info = new JsonObject(); info.addProperty("port", server.getPort());
    info.addProperty("pairToken", server.getPairToken()); info.add("files", files);
    Files.write(new File(dir,"ready.json").toPath(), info.toString().getBytes(StandardCharsets.UTF_8));
    new CountDownLatch(1).await();
  }
  private static void add(TransferServer server, JsonArray files, String name, int size, boolean failOnce) throws Exception {
    AtomicInteger opens = new AtomicInteger(); MessageDigest digest = MessageDigest.getInstance("SHA-256");
    byte[] block = new byte[65536]; for(int i=0;i<block.length;i++) block[i]=(byte)i;
    for(int left=size;left>0;left-=Math.min(left,block.length)) digest.update(block,0,Math.min(left,block.length));
    String hash = HexFormat.of().formatHex(digest.digest());
    ShareFile file = server.share(name,size,"application/octet-stream",hash, offset -> {
      if(failOnce && opens.getAndIncrement()==0) throw new IOException("Synthetic first-read failure");
      if(!failOnce) opens.incrementAndGet();
      return new InputStream() {
        long at=offset;
        public int read() throws IOException { byte[] one=new byte[1]; return read(one,0,1)<0?-1:one[0]&255; }
        public int read(byte[] b,int start,int len) throws IOException {
          if(at>=size) return -1;
          int count=(int)Math.min(Math.min(len,65536),size-at);
          try { Thread.sleep(62); } catch(InterruptedException error) { Thread.currentThread().interrupt(); throw new IOException(error); }
          for(int i=0;i<count;i++) b[start+i]=(byte)(at+i);
          at+=count; return count;
        }
      };
    });
    JsonObject item=new JsonObject(); item.addProperty("id",file.id); item.addProperty("name",name);
    item.addProperty("size",size); item.addProperty("sha256",hash); files.add(item);
  }
}
'''


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apk', required=True, type=Path)
    parser.add_argument('--serial', default=os.environ.get('ANDROID_SERIAL', 'emulator-5554'))
    parser.add_argument('--version', required=True)
    parser.add_argument('--keep-fixtures', action='store_true')
    args = parser.parse_args()
    if os.environ.get('FILEFLASH_DEDICATED_EMULATOR') != '1' or not re.fullmatch(r'emulator-\d+', args.serial):
        raise RuntimeError('Explicit dedicated emulator opt-in and emulator serial required')
    apk = args.apk.resolve(strict=True)
    apk_sha256 = hashlib.sha256(apk.read_bytes()).hexdigest()
    sdk = Path(os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT') or ROOT / '.tools/android-sdk')
    adb_exe = sdk / 'platform-tools' / ('adb.exe' if os.name == 'nt' else 'adb')
    java_dir = Path(os.environ['JAVA_HOME']) / 'bin' if os.environ.get('JAVA_HOME') else Path(shutil.which('java') or 'java').parent
    java = java_dir / ('java.exe' if os.name == 'nt' else 'java')
    javac = java_dir / ('javac.exe' if os.name == 'nt' else 'javac')
    run = 'receive-ui-' + args.serial.removeprefix('emulator-') + '-' + str(time.time_ns())
    out = ROOT / '.test-data' / 'receive-ui' / run
    out.mkdir(parents=True)
    runtime = ROOT / 'core/build/install/core/lib'
    if not (runtime / 'core.jar').is_file():
        raise RuntimeError('Build :core:installDist before running acceptance')
    checks, screenshots = [], []
    completed = False
    process = None
    server_log = None
    port = None
    api = None
    fixtures = []
    original_font = None
    installed_sha256 = None
    evidence = ROOT / 'docs/test-evidence' / ('receive-ui-' + args.serial + '.json')

    def adb(*items, binary=False, check=True):
        result = subprocess.run([str(adb_exe), '-s', args.serial, *map(str, items)], capture_output=True, check=check)
        return result.stdout if binary else result.stdout.decode('utf-8', 'replace').strip()

    def wait(callback, timeout=35):
        deadline, last = time.monotonic() + timeout, None
        while time.monotonic() < deadline:
            try:
                result = callback()
                if result:
                    return result
            except Exception as error:
                last = error
            time.sleep(.2)
        raise AssertionError('Timed out waiting for receive state') from last

    def report():
        payload = dict(environment=f'Dedicated rooted API{api} Android emulator; real native APK client and Java TransferServer via adb reverse; throttled synthetic Source; not physical Wi-Fi or iOS',
                       version=args.version, apkVersion=args.version, apkSha256=apk_sha256, installedApkSha256=installed_sha256, passed=completed and bool(checks) and all(c['passed'] for c in checks), tests=checks,
                       screenshots=screenshots)
        evidence.parent.mkdir(parents=True, exist_ok=True)
        evidence.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding='utf-8')
        (out / 'results.json').write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding='utf-8')

    def check(name, condition, **details):
        checks.append(dict(name=name, passed=bool(condition), **details))
        report()
        print(('PASS: ' if condition else 'FAIL: ') + name, flush=True)
        if not condition:
            raise AssertionError(name)

    def pref(key):
        raw = adb('exec-out', 'cat', PRIVATE + '/shared_prefs/fileflash.xml')
        tree = ET.fromstring(raw)
        return json.loads(next((n.text for n in tree if n.get('name') == key), '[]'))

    def tasks(file):
        return [task for task in pref('tasks') if task.get('direction') == '接收' and task.get('remote', {}).get('id') == file['id']]

    def task(file):
        found = tasks(file)
        return found[0] if found else None

    def dump(label='window'):
        adb('shell', 'uiautomator', 'dump', '/sdcard/fileflash-receive-ui.xml')
        raw = adb('exec-out', 'cat', '/sdcard/fileflash-receive-ui.xml')
        (out / (label + '.xml')).write_text(raw, encoding='utf-8')
        return ET.fromstring(raw)

    def texts(ui):
        return [node.get('text', '') for node in ui.iter('node')]

    def shot(label):
        path = out / (label + '.png')
        path.write_bytes(adb('exec-out', 'screencap', '-p', binary=True))
        screenshots.append(dict(stage=label, path=str(path.relative_to(ROOT)).replace('\\', '/')))
        return path

    def bounds(node):
        return list(map(int, re.findall(r'\d+', node.get('bounds', ''))))

    def tap_node(node, delay=.3):
        left, top, right, bottom = bounds(node)
        adb('shell', 'input', 'tap', (left + right)//2, (top + bottom)//2)
        time.sleep(delay)

    def tap_text(text, occurrence=0, ui=None):
        ui = ui if ui is not None else dump()
        nodes = [node for node in ui.iter('node') if node.get('text') == text]
        if len(nodes) <= occurrence:
            raise AssertionError('UI action absent: ' + text)
        tap_node(nodes[occurrence])

    def card(ui, file):
        parents = {child: parent for parent in ui.iter() for child in parent}
        candidates = [node for node in ui.iter('node') if file['name'] == node.get('text', '')]
        if not candidates:
            return None
        return parents.get(candidates[0])

    def has_action(row):
        return row is not None and any(label in texts(row) for label in ('接收', '暂停', '重试', '打开', '重新接收'))

    def show(file):
        ui = dump()
        current = card(ui, file)
        if has_action(current):
            return ui
        # Another card may have focused itself above the desired row.
        if current is None:
            for _ in range(3):
                adb('shell', 'input', 'swipe', '500', '500', '500', '1450', '180')
        for _ in range(7):
            ui = dump()
            if has_action(card(ui, file)):
                return ui
            adb('shell', 'input', 'swipe', '500', '1450', '500', '500', '250')
            time.sleep(.15)
        raise AssertionError('Receive card not visible: ' + file['name'])

    def action(file, label, ui=None):
        ui = ui if ui is not None else show(file)
        row = card(ui, file)
        node = next((n for n in row.iter('node') if n.get('text') == label), None) if row is not None else None
        if node is None:
            raise AssertionError('Receive-card action absent: ' + label)
        tap_node(node)

    def assert_inline(file, expected, label):
        ui = show(file)
        row = card(ui, file)
        values = texts(row)
        check(label, any(expected in value for value in values) and '任务' not in texts(ui), uiState=expected)
        return ui, row

    def verify_saved(file):
        current = wait(lambda: task(file) if task(file) and task(file)['status'] == '完成' else None, 50)
        saved = next((record for record in pref('saved') if record['id'] == current['id']), None)
        check('completed receive has a durable saved URI', bool(saved) and saved['uri'] == current.get('savedUri'))
        data = adb('exec-out', 'content', 'read', '--uri', saved['uri'], binary=True)
        check('received Downloads bytes match synthetic source', len(data) == file['size'] and hashlib.sha256(data).hexdigest() == file['sha256'], bytes=len(data), sha256=hashlib.sha256(data).hexdigest())
        return saved

    saved_before, readable_before = [], {}
    try:
        if adb('shell', 'getprop', 'ro.kernel.qemu') != '1' or 'uid=0' not in adb('shell', 'id'):
            raise RuntimeError('Dedicated rooted emulator required; no physical-device operation')
        api = int(adb('shell', 'getprop', 'ro.build.version.sdk'))
        evidence = ROOT / 'docs/test-evidence' / ('receive-ui-api' + str(api) + '.json')
        saved_before = pref('saved') if 'package:app.fileflash' in adb('shell', 'pm', 'list', 'packages', 'app.fileflash') else []
        for saved in saved_before:
            data = adb('exec-out', 'content', 'read', '--uri', saved['uri'], binary=True, check=False)
            if len(data) == saved.get('size'):
                readable_before[saved['uri']] = hashlib.sha256(data).hexdigest()
        original_font = adb('shell', 'settings', 'get', 'system', 'font_scale')
        adb('install', '-r', apk)
        package_info = adb('shell', 'dumpsys', 'package', 'app.fileflash')
        package_path = adb('shell', 'pm', 'path', 'app.fileflash').removeprefix('package:')
        if not package_path.startswith('/data/app/') or not package_path.endswith('/base.apk') or '\n' in package_path:
            raise RuntimeError('Unexpected installed package path')
        installed_sha256 = adb('shell', 'sha256sum', package_path).split()[0]
        check('signed APK upgrades without clearing saved records', 'versionName=' + args.version in package_info and pref('saved') == saved_before and installed_sha256 == apk_sha256, apiLevel=api, installedApkSha256=installed_sha256)
        if api >= 33:
            adb('shell', 'pm', 'grant', 'app.fileflash', 'android.permission.POST_NOTIFICATIONS')
        source = out / 'ReceiveUiServer.java'
        source.write_text(JAVA_FIXTURE, encoding='utf-8')
        cp = str(runtime / '*')
        subprocess.run([str(javac), '-encoding', 'UTF-8', '-cp', cp, '-d', str(out), str(source)], check=True, capture_output=True)
        server_log = (out / 'server.log').open('wb')
        process = subprocess.Popen([str(java), '-Xmx128m', '-cp', str(out) + os.pathsep + cp, 'ReceiveUiServer', str(out), run], stdout=server_log, stderr=subprocess.STDOUT)
        ready = wait(lambda: json.loads((out / 'ready.json').read_text(encoding='utf-8')) if (out / 'ready.json').exists() else None)
        port, fixtures = ready['port'], ready['files']
        adb('reverse', 'tcp:' + str(port), 'tcp:' + str(port))
        adb('shell', 'settings', 'put', 'system', 'font_scale', '1.0')
        adb('shell', 'am', 'force-stop', 'app.fileflash')
        adb('shell', 'am', 'start', '-n', 'app.fileflash/.MainActivity')
        time.sleep(1)
        tap_text('连接')
        ui = dump()
        field = next(n for n in ui.iter('node') if n.get('class') == 'android.widget.EditText')
        tap_node(field)
        link = f'http://127.0.0.1:{port}/#pair={ready["pairToken"]}'
        adb('shell', 'input', 'text', link)
        adb('shell', 'input', 'keyevent', '4')
        time.sleep(.2)
        tap_text('连接', occurrence=0)
        wait(lambda: '已连接' in texts(dump()))
        check('native connection page pairs with actual TransferServer', True)

        normal, failing, pausing = fixtures
        ui = show(normal)
        row = card(ui, normal)
        receive = next(n for n in row.iter('node') if n.get('text') == '接收')
        # Measure the first, single-click receive before exercising rapid taps.
        left, top, right, bottom = bounds(receive)
        adb('shell', 'input', 'tap', (left+right)//2, (top+bottom)//2)
        start_ui = dump('receive-started')
        shot('receive-started')
        start_parents = {child: parent for parent in start_ui.iter() for child in parent}
        snack_text = next((n for n in start_ui.iter('node') if n.get('text') == '开始下载'), None)
        start_row = card(start_ui, normal)
        pause_text = next((n for n in start_row.iter('node') if n.get('text') == '暂停'), None) if start_row is not None else None
        snack = start_parents.get(snack_text) if snack_text is not None else None
        pause = pause_text
        while pause is not None and pause.get('clickable') != 'true':
            pause = start_parents.get(pause)
        snack_bounds = bounds(snack) if snack is not None else []
        pause_bounds = bounds(pause) if pause is not None else []
        no_overlap = (len(snack_bounds) == 4 and len(pause_bounds) == 4
                      and snack_bounds[2] > snack_bounds[0] and snack_bounds[3] > snack_bounds[1]
                      and pause_bounds[2] > pause_bounds[0] and pause_bounds[3] > pause_bounds[1]
                      and (pause_bounds[2] <= snack_bounds[0] or pause_bounds[0] >= snack_bounds[2]
                           or pause_bounds[3] <= snack_bounds[1] or pause_bounds[1] >= snack_bounds[3]))
        check('visible start Snackbar does not overlap the pause action', no_overlap,
              snackbarVisible=snack_text is not None, snackbarBounds=snack_bounds, pauseBounds=pause_bounds)
        ui, row = assert_inline(normal, '下载中', 'click receive exposes progress on the same connection page')
        check('active receive has an inline progress bar and byte progress', any(n.get('class') == 'android.widget.ProgressBar' or n.get('content-desc') == '接收进度' for n in row.iter('node')) and any('/' in value for value in texts(row)))
        shot('receive-progress')
        wait(lambda: task(normal) if task(normal) and task(normal)['status'] == '完成' else None, 50)
        ui, row = assert_inline(normal, '已保存', 'completed receive stays visible on connection page')
        check('saved receive exposes open and receive-again actions', '打开' in texts(row) and '重新接收' in texts(row))
        shot('receive-complete')
        again = next(n for n in row.iter('node') if n.get('text') == '重新接收')
        left, top, right, bottom = bounds(again)
        adb('shell', 'input', 'tap', (left+right)//2, (top+bottom)//2)
        adb('shell', 'input', 'tap', (left+right)//2, (top+bottom)//2)
        wait(lambda: len(tasks(normal)) >= 2, 5)
        check('double receive-again click creates only one new task', len(tasks(normal)) == 2)
        # The second tap can legitimately reach a newly rendered Pause action.
        # Restore that task, without changing the first-click layout evidence.
        repeat_ui = show(normal)
        if '已暂停' in texts(card(repeat_ui, normal)):
            wait(lambda: '开始下载' not in texts(dump()), 10)
            action(normal, '重试')
        verify_saved(normal)

        action(failing, '接收')
        wait(lambda: task(failing) if task(failing) and task(failing)['status'] == '失败' else None)
        ui, row = assert_inline(failing, '下载失败', 'read failure is visible on the originating connection card')
        check('failed receive exposes retry and preserves an error detail', '重试' in texts(row) and bool(task(failing).get('error')))
        shot('receive-failed')
        failed_id = task(failing)['id']
        action(failing, '重试', ui)
        verify_saved(failing)
        check('inline failure retry reuses the original task', len(tasks(failing)) == 1 and task(failing)['id'] == failed_id)
        assert_inline(failing, '已保存', 'failure retry completes visibly on the connection page')

        action(pausing, '接收')
        time.sleep(.6)
        ui = show(pausing)
        action(pausing, '暂停', ui)
        wait(lambda: task(pausing) if task(pausing) and task(pausing)['status'] == '已暂停' else None)
        ui, row = assert_inline(pausing, '已暂停', 'pause stays visible on the same receive card')
        check('paused receive exposes retry', '重试' in texts(row))
        paused_id = task(pausing)['id']
        shot('receive-paused')
        for _ in range(3):
            adb('shell', 'input', 'swipe', '500', '500', '500', '1450', '180')
        tap_text('断开')
        tap_text('记录')
        tap_text('连接')
        assert_inline(pausing, '已暂停', 'disconnect then history and connection preserve the inline paused task')
        shot('receive-paused-after-disconnect-tab-return')
        for _ in range(3):
            adb('shell', 'input', 'swipe', '500', '500', '500', '1450', '180')
        tap_text('连接', occurrence=0)
        wait(lambda: '已连接' in texts(dump()))
        time.sleep(.5)
        action(pausing, '重试')
        verify_saved(pausing)
        check('pause retry resumes one original task', len(tasks(pausing)) == 1 and task(pausing)['id'] == paused_id)

        # Accessible large-text rendering: screenshots remain the visual acceptance evidence.
        adb('shell', 'settings', 'put', 'system', 'font_scale', '1.3')
        time.sleep(1)
        adb('shell', 'input', 'swipe', '500', '500', '500', '1500', '250')
        ui = show(normal)
        row = card(ui, normal)
        shot('receive-complete-font-1_3')
        display = re.search(r'(\d+)x(\d+)', adb('shell', 'wm', 'size'))
        width, height = map(int, display.groups())
        action_nodes = [n for n in row.iter('node') if n.get('text') in ('打开', '重新接收')]
        check('large text keeps completion actions inside the viewport', len(action_nodes) == 2 and all(len(bounds(n)) == 4 and 0 <= bounds(n)[0] < bounds(n)[2] <= width and 0 <= bounds(n)[1] < bounds(n)[3] <= height for n in action_nodes), fontScale=1.3)

        # Baseline records are never removed or changed by fixture cleanup.
        saved_after = pref('saved')
        check('previously saved records remain intact', all(record in saved_after for record in saved_before), records=len(saved_before))
        check('readable pre-existing Downloads remain byte-identical', all(hashlib.sha256(adb('exec-out', 'content', 'read', '--uri', uri, binary=True)).hexdigest() == digest for uri, digest in readable_before.items()), readableRecords=len(readable_before), unreadableBefore=len(saved_before)-len(readable_before))
        completed = True
    finally:
        if original_font is not None:
            if original_font == 'null':
                adb('shell', 'settings', 'delete', 'system', 'font_scale', check=False)
            else:
                adb('shell', 'settings', 'put', 'system', 'font_scale', original_font, check=False)
        if not args.keep_fixtures and fixtures:
            for file in fixtures:
                if not file['name'].startswith(run + '-'):
                    continue
                for saved in pref('saved'):
                    if saved.get('name') == file['name'] and re.fullmatch(r'content://media/external(?:_primary)?/downloads/\d+', saved.get('uri', '')):
                        adb('shell', 'su', '2000', 'content', 'delete', '--uri', saved['uri'], check=False)
        if port is not None:
            adb('reverse', '--remove', 'tcp:' + str(port), check=False)
        if process is not None:
            process.terminate()
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)
        if server_log is not None:
            server_log.close()
        report()


if __name__ == '__main__':
    main()
