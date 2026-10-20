"""Verify source ZIP and APK contents against the delivered working tree."""
from pathlib import Path
import hashlib
import json
import re
import xml.etree.ElementTree as ET
import zipfile

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
    for name in sources:
        relative = Path(name).relative_to('FileFlash')
        assert not any(part in {'.signing', '.tools', '.test-data', 'node_modules', 'build'} for part in relative.parts), relative
        assert relative.as_posix() != 'local.properties'
        assert archive.read(name) == (root / relative).read_bytes(), 'Source ZIP differs: ' + str(relative)
core = ET.parse(root / 'docs/test-evidence/core-tests.xml').getroot()
assert int(core.attrib['failures']) == 0 and int(core.attrib['errors']) == 0
cache = json.loads((root / 'docs/test-evidence/cache-android.json').read_text(encoding='utf-8'))
assert cache['tests'] and all(item['passed'] for item in cache['tests'])
large = json.loads((root / 'docs/test-evidence/large.json').read_text(encoding='utf-8'))
assert large['passed'] and large['bytes'] > 4 * 1024 ** 3
upload_checks = {}
apk_sha = hashlib.sha256(apk.read_bytes()).hexdigest()
for name in ['upload-android.json', 'upload-android-api29.json']:
    evidence = json.loads((root / 'docs/test-evidence' / name).read_text(encoding='utf-8'))
    assert evidence['tests'] and all(item['passed'] for item in evidence['tests']), name
    assert any(item.get('apkSha256') == apk_sha for item in evidence['tests']), name
    upload_checks[name] = len(evidence['tests'])
for name in ['ios-upload-browser-chromium.json', 'ios-upload-browser-webkit.json', 'ios-upload-live-webkit.json', 'upload-webkit-android.json']:
    evidence = json.loads((root / 'docs/test-evidence' / name).read_text(encoding='utf-8'))
    assert evidence['passed'], name
    upload_checks[name] = True
actual_web = json.loads((root / 'docs/test-evidence/upload-webkit-android.json').read_text(encoding='utf-8'))
assert actual_web['serverApkSha256'] == apk_sha
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
    'currentUploadChecks': upload_checks,
    'minimumAndroidApi': 29,
    'realHttpLargeFileBytes': large['bytes'],
    'artifacts': [{'name': p.name, 'bytes': p.stat().st_size, 'sha256': hashlib.sha256(p.read_bytes()).hexdigest()} for p in [apk, source]],
}
(root / 'dist/verification.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
(root / 'dist/SHA256SUMS.txt').write_text(''.join(item['sha256'] + '  ' + item['name'] + '\n' for item in report['artifacts']), encoding='utf-8')
print(json.dumps(report, indent=2))
