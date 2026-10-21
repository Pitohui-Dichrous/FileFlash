"""Verify source ZIP and APK contents against the delivered working tree."""
from pathlib import Path
import hashlib
import json
import re
import xml.etree.ElementTree as ET
import zipfile
from source_manifest import source_files

root = Path(__file__).resolve().parent.parent
version = re.search(r'versionName\s*=\s*"([^"]+)"', (root / 'app/build.gradle.kts').read_text(encoding='utf-8')).group(1)
apk = root / f'dist/FileFlash-{version}.apk'
source = root / f'dist/FileFlash-{version}-source.zip'
with zipfile.ZipFile(apk) as archive:
    assert archive.testzip() is None
    names = archive.namelist()
    assert 'AndroidManifest.xml' in names and 'classes.dex' in names and 'resources.arsc' in names
    assets = list((root / 'app/src/main/assets').rglob('*'))
    assets = [p for p in assets if p.is_file()]
    for file in assets:
        assert archive.read('assets/' + file.relative_to(root / 'app/src/main/assets').as_posix()) == file.read_bytes(), file
with zipfile.ZipFile(source) as archive:
    assert archive.testzip() is None
    sources = archive.namelist()
    assert len(sources) == len(set(sources)), 'Duplicate source ZIP entry'
    assert set(sources) == {'FileFlash/' + p.relative_to(root).as_posix() for p in source_files(root)}, 'Incomplete source ZIP manifest'
    for name in sources:
        relative = Path(name).relative_to('FileFlash')
        assert not any(part in {'.signing', '.tools', '.test-data', 'node_modules', 'build'} for part in relative.parts), relative
        assert relative.as_posix() != 'local.properties'
        assert archive.read(name) == (root / relative).read_bytes(), 'Source ZIP differs: ' + str(relative)
if version in {'0.1.6', '0.1.7', '0.1.8'}:
    if version in {'0.1.7', '0.1.8'}:
        from delivery_shortcut_fix import verify
    else:
        from delivery_ios_share import verify
    report = verify(root, version, apk, source, assets, sources)
    (root / 'dist/verification.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
    (root / 'dist/SHA256SUMS.txt').write_text(''.join(item['sha256'] + '  ' + item['name'] + '\n' for item in report['artifacts']), encoding='utf-8')
    print(json.dumps(report, indent=2))
    raise SystemExit(0)
if version == '0.1.5-test.2':
    apk_sha = hashlib.sha256(apk.read_bytes()).hexdigest()
    binary = json.loads((root / 'docs/test-evidence/photo-picker-binary.json').read_text(encoding='utf-8'))
    assert binary['passed'] and binary['version'] == version and binary['serverApkSha256'] == apk_sha
    assert binary['allDexByteIdentical'] and binary['baselineApkSha256'] == 'a612b1a915c8ff18f400a845aa69baa2dde241148d4ee63449afc7a1a3c2d5dd'
    with zipfile.ZipFile(apk) as archive:
        for entry in binary['dex']:
            assert hashlib.sha256(archive.read(entry['name'])).hexdigest() == entry['sha256']
    current_checks = {}
    for engine in ['chromium', 'webkit']:
        for suffix in ['', '-real-server']:
            name = f'photo-picker-browser-{engine}{suffix}.json'
            evidence = json.loads((root / 'docs/test-evidence' / name).read_text(encoding='utf-8'))
            assert evidence['passed'] and evidence['version'] == version
            for filename, digest in evidence['assetSha256'].items():
                assert hashlib.sha256((root / 'app/src/main/assets/web' / filename).read_bytes()).hexdigest() == digest
            if suffix:
                assert evidence['serverApkSha256'] == apk_sha and evidence['serverVersion'] == version
            current_checks[name] = len(evidence['checks'])
    upgrade = json.loads((root / 'docs/test-evidence/transport-upgrade-emulator-5556-photo-picker.json').read_text(encoding='utf-8'))
    assert upgrade['passed'] and upgrade['serverApkSha256'] == apk_sha and upgrade['version'] == version
    assert upgrade['savedBytesUnchanged'] and not upgrade['uninstalled']
    clean = (root / 'docs/test-evidence/photo-picker-clean-build.txt').read_text(encoding='utf-8')
    assert version in clean and 'BUILD SUCCESSFUL' in clean and 'Core tests: 49 passed' in clean
    report = {
        'version': version, 'apkZipCrc': True, 'bundledAssetsMatchSource': len(assets),
        'sourceZipCrc': True, 'sourceFilesMatchWorkingTree': len(sources),
        'privateKeysSdkAndCredentialsExcluded': True, 'minimumAndroidApi': 29,
        'allAndroidAndTransportDexByteIdenticalTo': binary['baselineVersion'],
        'currentPickerChecks': current_checks, 'androidApi29UpgradeSavedBytesUnchanged': True,
        'cleanSourceBuildCoreTestsPassed': 49,
        'physicalIPhonePickerVerified': False, 'photosLibraryFixed': False,
        'artifacts': [{'name': p.name, 'bytes': p.stat().st_size, 'sha256': hashlib.sha256(p.read_bytes()).hexdigest()} for p in [apk, source]],
    }
    (root / 'dist/verification.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
    (root / 'dist/SHA256SUMS.txt').write_text(''.join(item['sha256'] + '  ' + item['name'] + '\n' for item in report['artifacts']), encoding='utf-8')
    print(json.dumps(report, indent=2))
    raise SystemExit(0)
core = ET.parse(root / 'docs/test-evidence/core-tests.xml').getroot()
assert int(core.attrib['failures']) == 0 and int(core.attrib['errors']) == 0
cache = json.loads((root / 'docs/test-evidence/cache-android.json').read_text(encoding='utf-8'))
assert cache['tests'] and all(item['passed'] for item in cache['tests'])
large = json.loads((root / 'docs/test-evidence/large.json').read_text(encoding='utf-8'))
assert large['passed'] and large['bytes'] > 4 * 1024 ** 3
upload_checks = {}
apk_sha = hashlib.sha256(apk.read_bytes()).hexdigest()
diagnostic_release = version.startswith('0.1.5-test.')
receive_release = version == '0.1.5-test.3'
transport_version = '0.1.5-test.1' if receive_release else version
transport_sha = hashlib.sha256((root / f'dist/FileFlash-{transport_version}.apk').read_bytes()).hexdigest()
normalized_browser_assets = []
if receive_release:
    browser_baseline = root / 'dist/baselines/FileFlash-0.1.5-test.2.apk'
    assert hashlib.sha256(browser_baseline.read_bytes()).hexdigest() == '93cd0404a9df6edd1867c4492b1d3f120780310f3dae65d1e2dead9f1e31958e'
    with zipfile.ZipFile(browser_baseline) as previous, zipfile.ZipFile(apk) as current:
        assert {name for name in previous.namelist() if name.startswith('assets/')} == {name for name in current.namelist() if name.startswith('assets/')}
        for file in assets:
            name = 'assets/' + file.relative_to(root / 'app/src/main/assets').as_posix()
            before, after = previous.read(name), current.read(name)
            if before != after:
                assert file.suffix in {'.js', '.html', '.css', '.svg', '.webmanifest'}, 'Browser binary asset changed: ' + name
                assert before.replace(b'\r\n', b'\n') == after.replace(b'\r\n', b'\n'), 'Browser code changed: ' + name
                normalized_browser_assets.append(name)
android_evidence = (['transport-upgrade-emulator-5554.json', 'transport-upgrade-emulator-5556.json'] if diagnostic_release else
    ['upload-android.json', 'upload-android-api29.json'] if version == '0.1.2' else ['relay-android-api34.json', 'relay-android-api29.json'])
for name in android_evidence:
    evidence = json.loads((root / 'docs/test-evidence' / name).read_text(encoding='utf-8'))
    assert evidence['tests'] and all(item['passed'] for item in evidence['tests']), name
    if diagnostic_release:
        assert evidence.get('serverApkSha256') == transport_sha and evidence.get('version') == transport_version, name
    else:
        assert any(item.get('apkSha256') == apk_sha for item in evidence['tests']), name
    upload_checks[name] = len(evidence['tests'])
actual_web_name = 'transport-webview-api34.json' if diagnostic_release else 'upload-webkit-android.json' if version == '0.1.2' else 'relay-webkit-android.json'
browser_evidence = (['stream-browser-chromium.json', 'stream-browser-webkit.json'] if diagnostic_release else
    ['ios-upload-browser-chromium.json', 'ios-upload-browser-webkit.json'])
if diagnostic_release:
    browser_evidence += ['stream-live-chromium.json', 'stream-live-webkit.json', 'stream-ios-live-webkit.json', 'stream-relay-webkit-android.json']
for name in browser_evidence + [actual_web_name]:
    evidence = json.loads((root / 'docs/test-evidence' / name).read_text(encoding='utf-8'))
    assert evidence['passed'], name
    upload_checks[name] = True
actual_web = json.loads((root / 'docs/test-evidence' / actual_web_name).read_text(encoding='utf-8'))
assert actual_web['serverApkSha256'] == transport_sha
if diagnostic_release:
    assert actual_web['version'] == transport_version and actual_web['fileBytes'] == 60 * 1024 ** 2
    native = json.loads((root / 'docs/test-evidence/transport-native-api34.json').read_text(encoding='utf-8'))
    assert native['passed'] and native['version'] == transport_version and native['serverApkSha256'] == transport_sha
    upload_checks['transport-native-api34.json'] = True
    stream = json.loads((root / 'docs/test-evidence/browser-stream-large.json').read_text(encoding='utf-8'))
    assert stream['passed'] and stream['bytes'] > 4 * 1024 ** 3
    upload_checks['browser-stream-large.json'] = True
    relay_web = json.loads((root / 'docs/test-evidence/stream-relay-webkit-android.json').read_text(encoding='utf-8'))
    assert relay_web['serverApkSha256'] == transport_sha and relay_web['version'] == transport_version
receive_checks = {}
picker_checks = {}
if receive_release:
    for name in ['receive-ui-api29.json', 'receive-ui-api34.json']:
        evidence = json.loads((root / 'docs/test-evidence' / name).read_text(encoding='utf-8'))
        assert evidence['passed'] and evidence['version'] == version and evidence['apkSha256'] == apk_sha, name
        assert evidence['installedApkSha256'] == apk_sha, name
        assert len(evidence['tests']) == 25 and all(item['passed'] for item in evidence['tests']), name
        receive_checks[name] = len(evidence['tests'])
    assert stream['runtimeCoreSha256'] == hashlib.sha256((root / 'core/build/install/core/lib/core.jar').read_bytes()).hexdigest()
    for engine in ['chromium', 'webkit']:
        for suffix in ['', '-real-server']:
            name = f'photo-picker-browser-{engine}{suffix}-test3.json'
            evidence = json.loads((root / 'docs/test-evidence' / name).read_text(encoding='utf-8'))
            assert evidence['passed'] and evidence['version'] == version, name
            assert len(evidence['checks']) == 8, name
            for filename, digest in evidence['assetSha256'].items():
                assert hashlib.sha256((root / 'app/src/main/assets/web' / filename).read_bytes()).hexdigest() == digest, name
            if suffix:
                assert evidence['serverApkSha256'] == apk_sha and evidence['serverVersion'] == version, name
            picker_checks[name] = len(evidence['checks'])
    clean = (root / 'docs/test-evidence/receive-clean-build.txt').read_text(encoding='utf-8')
    assert version in clean and 'BUILD SUCCESSFUL' in clean and 'Core tests: 49 passed (fresh extracted project XML).' in clean
    clean_core = ET.parse(root / 'docs/test-evidence/core-tests.xml').getroot()
    assert int(clean_core.attrib['tests']) == 49
report = {
    'version': version,
    'apkZipCrc': True,
    'bundledAssetsMatchSource': len(assets),
    'sourceZipCrc': True,
    'sourceFilesMatchWorkingTree': len(sources),
    'privateKeysSdkAndCredentialsExcluded': True,
    'coreTestsPassed': int(core.attrib['tests']),
    'androidCacheChecksPassed': len(cache['tests']),
    'androidCacheChecksVersion': '0.1.1 historical baseline',
    **({'currentReceiveUiChecks': receive_checks, 'currentPickerChecks': picker_checks,
        'browserBaselineVersion': '0.1.5-test.2', 'coreBaselineVersion': transport_version,
        'previousUploadChecks': upload_checks, 'browserCodeAndAssetsEquivalentToPreviousApk': True,
        'browserTextLineEndingsNormalized': normalized_browser_assets,
        'coreRuntimeMatchesPreviousLargeFileEvidence': True,
        'cleanSourceBuildCoreTestsPassed': 49,
        'photosLibraryFixed': False} if receive_release else {'currentUploadChecks': upload_checks}),
    'minimumAndroidApi': 29,
    'realHttpLargeFileBytes': large['bytes'],
    **({'browserSingleRequestBytes': stream['bytes'], 'physicalIPhoneVerified': False} if diagnostic_release else {}),
    'artifacts': [{'name': p.name, 'bytes': p.stat().st_size, 'sha256': hashlib.sha256(p.read_bytes()).hexdigest()} for p in [apk, source]],
}
(root / 'dist/verification.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
(root / 'dist/SHA256SUMS.txt').write_text(''.join(item['sha256'] + '  ' + item['name'] + '\n' for item in report['artifacts']), encoding='utf-8')
print(json.dumps(report, indent=2))
