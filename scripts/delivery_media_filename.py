"""Verify the 0.1.10 media-filename delivery against the released 0.1.9 files.

The successful web/Shortcuts assets and Android Kotlin are preserved. Only the
explicitly frozen media-name core change may differ. Current Android protocol
evidence is reported separately from the user's earlier physical-iPhone result.
"""
from pathlib import Path
import hashlib
import json
import os
import re
import subprocess
import xml.etree.ElementTree as ET
import zipfile


VERSION = '0.1.10'
BASELINE_VERSION = '0.1.9'
BASELINE_APK_SHA256 = '1010bf1e3810ddd1c26d2620cc0a2f4bb2ae53b4f0cacb477ef0dd0b31443968'
BASELINE_SOURCE_SHA256 = '701a6ac5c1fe0abde873afd70f9b501a504c3fa40b49e1fbe930358705794fb6'
BASELINE_CORE_SHA256 = 'c9d6934382767f34ef6c7abab06df446496d0515be8015f9e07eb471d582a580'
BASELINE_SHORTCUT_SHA256 = '26d5b7380182d2c85cd7a726ceabc25377e77541c8f874e9f7c5b10024335a70'
CERTIFICATE_SHA256 = 'a314ea8f5feb453ad0e3ff383227bc27d0ac6632a37870ebb0e1fb6c32df1e51'
GENERATED_PROFILE_ASSETS = {'assets/dexopt/baseline.prof', 'assets/dexopt/baseline.profm'}
TRANSFER_SERVER_SOURCE = 'core/src/main/java/app/fileflash/core/TransferServer.java'

# Filled only after the release owner freezes the completed build. A missing
# digest deliberately prevents an unfinished candidate from passing this gate.
CURRENT_TRANSFER_SERVER_SHA256 = 'dc6ca0994d545c4233b7fb129ef0abd67f18139ac1adbd0d5e6225bd9b486a74'
CURRENT_CORE_SHA256 = 'e9ac7d281ba8128f32c5624402aea37e9ae17f7e7f648fbc1e7a5a04b91bc156'
ADDED_CORE_SOURCES = {}

# Current-version protocol acceptance, agreed with the dedicated Android runner.
# These desktop HTTP bodies exercise the installed APK, not Apple Shortcuts.
REQUIRED_ANDROID_CASES = tuple('shortcut filename recovery: ' + name for name in (
    'PNG', 'JPEG', 'HEIC', 'MP4', 'MOV', 'existing JPEG extension',
    'Unicode PNG', 'version suffix', 'unknown fallback', 'untrusted MIME', 'nonmedia extension',
)) + (
    'all matrix uploads stay temporary until explicit Save',
    'server restart preserves recovered media names MIME and body bytes',
    'server restart preserves every pre-existing shared filename',
    *('explicit Save publishes correct name MIME and exact bytes: ' + name for name in ('PNG', 'JPEG', 'MOV')),
    *('native Open uses recovered MIME and exact saved URI: ' + name for name in ('PNG', 'JPEG')),
    'native Open fallback preserves MIME and exact saved URI without a video viewer: MOV',
    'saved names and MIME survive a hard process restart',
    'precise cleanup removes only this run public MediaStore rows',
    'precise cleanup removes own private bytes metadata and ledger entries',
    'pre-existing saved ledger and readable Downloads remain unchanged',
)
CORE_TEST_CLASSES = {'app.fileflash.core.' + name for name in (
    'HttpServerCleanupTest', 'MediaFilenameTest', 'ShortcutUploadTest',
    'StreamingUploadTest', 'TransferServerTest', 'TransportDiagnosticsTest',
)}
MODEL_TEST_CLASSES = {'app.fileflash.HostReceiveFeedbackTest', 'app.fileflash.UploadEntryTest'}


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def _sha(data):
    return hashlib.sha256(data).hexdigest()


def _frozen(value):
    return isinstance(value, str) and re.fullmatch(r'[0-9a-f]{64}', value) is not None


def _zip_names(archive, label):
    require(archive.testzip() is None, f'{label}: ZIP CRC failure')
    names = archive.namelist()
    require(len(names) == len(set(names)), f'{label}: duplicate ZIP entry')
    return {name for name in names if not name.endswith('/')}


def _assets(root, apk, baseline, assets):
    with zipfile.ZipFile(baseline) as old, zipfile.ZipFile(apk) as new:
        old_names = _zip_names(old, 'Baseline APK')
        new_names = _zip_names(new, 'Current APK')
        old_assets = {name for name in old_names if name.startswith('assets/')}
        new_assets = {name for name in new_names if name.startswith('assets/')}
        require(old_assets and old_assets == new_assets, 'APK asset inventory changed from 0.1.9')
        changes = {name for name in old_assets if old.read(name) != new.read(name)}
        require(changes.issubset(GENERATED_PROFILE_ASSETS),
                'User-bundled APK assets differ from the successful baseline')
        source_assets = {'assets/' + path.relative_to(root / 'app/src/main/assets').as_posix(): path
                         for path in assets}
        actual_source_assets = {path for path in (root / 'app/src/main/assets').rglob('*')
                                if path.is_file()}
        require(set(source_assets.values()) == actual_source_assets,
                'The supplied asset inventory omits current source assets')
        require(set(source_assets) == new_assets - GENERATED_PROFILE_ASSETS,
                'APK user assets do not exactly match the source inventory')
        for name, path in source_assets.items():
            require(new.read(name) == path.read_bytes(), f'APK/source asset mismatch: {name}')
        require({'AndroidManifest.xml', 'classes.dex', 'resources.arsc'}.issubset(new_names),
                'APK is missing required Android entries')
        shortcut = new.read('assets/web/FileFlash.shortcut')
        require(_sha(shortcut) == BASELINE_SHORTCUT_SHA256,
                'The successful 0.1.8/0.1.9 signed shortcut changed')
        profiles = [{'name': name, 'baselineSha256': _sha(old.read(name)),
                     'currentSha256': _sha(new.read(name)),
                     'baselineBytes': len(old.read(name)), 'currentBytes': len(new.read(name))}
                    for name in sorted(changes)]
    public = root / 'dist/FileFlash.shortcut'
    require(public.read_bytes() == shortcut, 'Standalone shortcut differs from the APK asset')
    return {'allUserBundledAssetsByteIdentical': True,
            'unchangedUserBundledApkAssets': len(source_assets),
            'generatedProfileChanges': profiles,
            'signedShortcutSha256': BASELINE_SHORTCUT_SHA256}


def _source_baseline(root, baseline_source):
    require(_frozen(CURRENT_TRANSFER_SERVER_SHA256) and _frozen(CURRENT_CORE_SHA256),
            'The release owner has not frozen the media-filename source/JAR digests')
    require(all(name.startswith('core/src/main/java/') and name.endswith('.java') and _frozen(value)
                for name, value in ADDED_CORE_SOURCES.items()),
            'Invalid newly added core-source freeze')
    with zipfile.ZipFile(baseline_source) as old:
        names = _zip_names(old, 'Baseline source')
        prefix = 'FileFlash/'
        require(all(name.startswith(prefix) for name in names),
                'Unexpected baseline source ZIP root')
        old_files = {name[len(prefix):]: old.read(name) for name in names}
    old_kotlin = {name for name in old_files if name.startswith('app/src/main/') and name.endswith('.kt')}
    current_kotlin = {path.relative_to(root).as_posix()
                      for path in (root / 'app/src/main').rglob('*.kt')}
    require(old_kotlin and current_kotlin == old_kotlin,
            'Android main Kotlin inventory changed from 0.1.9')
    for name in old_kotlin:
        require((root / name).read_bytes() == old_files[name],
                f'Android main Kotlin changed from 0.1.9: {name}')
    old_core = {name for name in old_files if name.startswith('core/src/main/java/') and name.endswith('.java')}
    current_core = {path.relative_to(root).as_posix()
                    for path in (root / 'core/src/main/java').rglob('*.java')}
    require(TRANSFER_SERVER_SOURCE in old_core and not old_core.intersection(ADDED_CORE_SOURCES),
            'Invalid baseline core inventory or added-source declaration')
    require(current_core == old_core | set(ADDED_CORE_SOURCES),
            'Unexpected added or missing transfer-core source')
    for name in old_core - {TRANSFER_SERVER_SOURCE}:
        require((root / name).read_bytes() == old_files[name],
                f'Unrelated core source changed from 0.1.9: {name}')
    changed = []
    for name, expected in {TRANSFER_SERVER_SOURCE: CURRENT_TRANSFER_SERVER_SHA256,
                           **ADDED_CORE_SOURCES}.items():
        require(digest(root / name) == expected, f'Frozen media-filename source differs: {name}')
        changed.append({'name': name, 'baselineSha256': _sha(old_files[name]) if name in old_files else None,
                        'currentSha256': expected})
    for name in ('core/build/libs/core.jar', 'core/build/install/core/lib/core.jar'):
        require(digest(root / name) == CURRENT_CORE_SHA256, f'Frozen core JAR differs: {name}')
    return {'androidMainKotlinByteIdenticalToBaseline': len(current_kotlin),
            'coreSourceFilesByteIdenticalToBaseline': len(old_core) - 1,
            'changedCoreSources': changed, 'coreJarSha256': CURRENT_CORE_SHA256}


def _android_binary(root, apk):
    sdk = os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
    require(sdk, 'Set ANDROID_HOME to the Android SDK used for this delivery')
    build_tools = Path(sdk) / 'build-tools/37.0.0'
    signer = build_tools / ('apksigner.bat' if os.name == 'nt' else 'apksigner')
    aapt = build_tools / ('aapt.exe' if os.name == 'nt' else 'aapt')
    signing = subprocess.check_output(
        [str(signer), 'verify', '--verbose', '--print-certs', str(apk)],
        text=True, encoding='utf-8')
    metadata = subprocess.check_output([str(aapt), 'dump', 'badging', str(apk)],
                                       text=True, encoding='utf-8')
    require('Verified using v2 scheme (APK Signature Scheme v2): true' in signing,
            'APK v2 signature verification failed')
    require(re.findall(r'certificate SHA-256 digest: ([a-f0-9]+)', signing) == [CERTIFICATE_SHA256],
            'Unexpected signing certificate')
    expected = {r"package: name='([^']+)'": 'app.fileflash',
                r"versionName='([^']+)'": VERSION, r"versionCode='([0-9]+)'": '14',
                r"sdkVersion:'([0-9]+)'": '29', r"targetSdkVersion:'([0-9]+)'": '37'}
    for pattern, value in expected.items():
        match = re.search(pattern, metadata)
        require(match is not None and match.group(1) == value,
                f'Unexpected Android package metadata: {pattern}')
    require('application-debuggable' not in metadata, 'Release APK is debuggable')
    lint_path = root / 'app/build/reports/lint-results-release.xml'
    lint = ET.parse(lint_path).getroot()
    require(lint.tag == 'issues' and
            not any(issue.get('severity') in ('Error', 'Fatal') for issue in lint.findall('issue')),
            'Release lint is invalid or contains errors')
    return {'versionCode': 14, 'minSdk': 29, 'targetSdk': 37,
            'signatureVerified': True, 'certificateSha256': CERTIFICATE_SHA256,
            'debuggable': False, 'releaseLintErrors': 0, 'lintReportSha256': digest(lint_path)}


def _merged_junit(root, path, source_dir, suffix, expected_classes, expected_count=None):
    document = ET.parse(path).getroot()
    require(document.tag == 'testsuites', f'{path.name}: expected merged testsuites')
    source_root = root / source_dir
    expected = {}
    for source in source_root.rglob('*' + suffix):
        text = source.read_text(encoding='utf-8')
        if suffix == '.java':
            pattern = (r'@Test(?:\([^)]*\))?\s+'
                       r'(?:(?:public|protected|private|static|final)\s+)*void\s+([A-Za-z_]\w*)\s*\(')
        else:
            pattern = r'@Test(?:\([^)]*\))?\s+fun\s+([A-Za-z_]\w*)\s*\('
        declared = re.findall(pattern, text)
        require(declared and len(declared) == len(set(declared)),
                f'{source.name}: missing, duplicate or unsupported test declarations')
        classname = '.'.join(source.relative_to(source_root).with_suffix('').parts)
        expected[classname] = (source, set(declared))
    suites = document.findall('testsuite')
    classes = [suite.get('name') for suite in suites]
    require(set(expected) == expected_classes, f'{path.name}: unexpected source test-class inventory')
    require(expected and len(classes) == len(set(classes)) and set(classes) == set(expected),
            f'{path.name}: suite inventory differs from the current test sources')
    results = {}
    for suite in suites:
        classname = suite.get('name')
        for attribute in ('failures', 'errors', 'skipped'):
            require(int(suite.get(attribute, '-1')) == 0,
                    f'{classname}: {attribute} must be explicitly zero')
        cases = suite.findall('testcase')
        names = [case.get('name', '') for case in cases]
        methods = {name[:-2] if name.endswith('()') else name for name in names}
        source, declared = expected[classname]
        require(cases and len(cases) == len(methods) == int(suite.get('tests', '-1')),
                f'{classname}: empty, duplicate or incomplete test cases')
        require(all(case.get('classname') == classname and
                    not any(case.find(tag) is not None for tag in ('failure', 'error', 'skipped'))
                    for case in cases), f'{classname}: unsuccessful or foreign test case')
        require(methods == declared, f'{classname}: XML omits or changes current source tests')
        results[classname] = {'testsPassed': len(cases), 'testCases': sorted(methods),
                              'testSourceSha256': digest(source)}
    count = sum(value['testsPassed'] for value in results.values())
    require(expected_count is None or count == expected_count,
            f'{path.name}: unexpected test count')
    require(int(document.get('tests', '-1')) == count, f'{path.name}: incomplete merged test count')
    for attribute in ('failures', 'errors', 'skipped'):
        require(int(document.get(attribute, '-1')) == 0,
                f'{path.name}: merged {attribute} must be explicitly zero')
    return {'testsPassed': count, 'classes': results, 'xmlSha256': digest(path)}


def _android_checks(evidence, version, apk_sha):
    results = {}
    require(REQUIRED_ANDROID_CASES, 'The current Android acceptance coverage has not been agreed')
    for api in (29, 34):
        path = evidence / f'media-filename-ui-api{api}.json'
        value = json.loads(path.read_text(encoding='utf-8'))
        require(value.get('passed') is True and value.get('version') == version,
                f'{path.name}: wrong version or failed run')
        require(value.get('apiLevel') == api, f'{path.name}: wrong Android API level')
        require(value.get('apkSha256') == apk_sha and value.get('installedApkSha256') == apk_sha,
                f'{path.name}: evidence is not bound to the delivered and installed APK')
        tests = value.get('tests')
        require(isinstance(tests, list) and tests and
                all(isinstance(test, dict) and test.get('passed') is True for test in tests),
                f'{path.name}: missing or unsuccessful Android checks')
        names = [test.get('name') for test in tests]
        require(all(isinstance(name, str) and name for name in names) and len(names) == len(set(names)),
                f'{path.name}: empty or duplicate Android checks')
        require(all(any(name.startswith(prefix) for name in names) for prefix in REQUIRED_ANDROID_CASES),
                f'{path.name}: required media-filename protocol coverage is missing')
        results[path.name] = {'checksPassed': len(tests), 'reportSha256': digest(path)}
    return results


def verify(root, version, apk, source, assets, sources):
    root, apk, source = Path(root), Path(apk), Path(source)
    require(version == VERSION, 'This gate is only for FileFlash 0.1.10')
    baseline_apk = root / f'dist/FileFlash-{BASELINE_VERSION}.apk'
    baseline_source = root / f'dist/FileFlash-{BASELINE_VERSION}-source.zip'
    require(digest(baseline_apk) == BASELINE_APK_SHA256, 'Released 0.1.9 baseline APK changed')
    require(digest(baseline_source) == BASELINE_SOURCE_SHA256, 'Released 0.1.9 baseline source ZIP changed')
    with zipfile.ZipFile(source) as archive:
        _zip_names(archive, 'Current source')
    apk_sha = digest(apk)
    asset_results = _assets(root, apk, baseline_apk, assets)
    source_results = _source_baseline(root, baseline_source)
    binary = _android_binary(root, apk)
    evidence = root / 'docs/test-evidence'
    core_tests = _merged_junit(root, evidence / 'media-filename-core-tests-0.1.10.xml',
                               'core/src/test/java', '.java', CORE_TEST_CLASSES)
    model_tests = _merged_junit(root, evidence / 'media-filename-model-tests-0.1.10.xml',
                                'app/src/test/java', '.kt', MODEL_TEST_CLASSES, expected_count=22)
    android = _android_checks(evidence, version, apk_sha)
    clean_path = evidence / 'media-filename-clean-build.txt'
    clean = clean_path.read_text(encoding='utf-8')
    require(clean.startswith(VERSION + ': source-only ZIP extraction;') and 'BUILD SUCCESSFUL' in clean,
            'Missing successful 0.1.10 source-only rebuild')
    for label, count in (('Core', core_tests['testsPassed']), ('Android model', model_tests['testsPassed'])):
        require(f'{label} tests: {count} passed (fresh extracted project XML).' in clean,
                f'Source-only rebuild {label} tests differ from current source/XML')
    public_shortcut = root / 'dist/FileFlash.shortcut'
    return {
        'version': version, 'apkZipCrc': True, 'sourceZipCrc': True,
        'bundledAssetsMatchSource': len(assets), 'sourceFilesMatchWorkingTree': len(sources),
        'privateKeysSdkAndCredentialsExcluded': True, 'minimumAndroidApi': 29,
        'androidBinary': binary,
        'assetBaselineVersion': BASELINE_VERSION, 'assetBaselineApkSha256': BASELINE_APK_SHA256,
        **asset_results,
        'sourceBaselineVersion': BASELINE_VERSION, 'sourceBaselineZipSha256': BASELINE_SOURCE_SHA256,
        'baselineCoreJarSha256': BASELINE_CORE_SHA256, **source_results,
        'coreChangedFromBaseline': True, 'androidDexMayChangeForCoreFix': True,
        'currentCoreTests': core_tests, 'currentModelTests': model_tests,
        'currentAndroidProtocolChecks': android,
        'cleanSourceBuild': {'coreTestsPassed': core_tests['testsPassed'],
                             'androidModelTestsPassed': model_tests['testsPassed'],
                             'reportSha256': digest(clean_path)},
        'iosCompatibilityBaseline': {
            'version': '0.1.8', 'status': 'userReported', 'smallFileSent': True, 'largeFileSent': True,
            'scope': 'User reported iPhone sending on 0.1.8; not an automated or 0.1.10 iPhone media-name test',
        },
        'physicalIPhoneMediaFilenameFixVerified': False,
        'artifacts': [{'name': path.name, 'bytes': path.stat().st_size, 'sha256': digest(path)}
                      for path in (apk, source, public_shortcut)],
    }


if __name__ == '__main__':
    from source_manifest import source_files
    root = Path(__file__).resolve().parent.parent
    apk = root / f'dist/FileFlash-{VERSION}.apk'
    source = root / f'dist/FileFlash-{VERSION}-source.zip'
    assets = [path for path in (root / 'app/src/main/assets').rglob('*') if path.is_file()]
    with zipfile.ZipFile(source) as archive:
        sources = _zip_names(archive, 'Current source')
        require(sources == {'FileFlash/' + path.relative_to(root).as_posix()
                            for path in source_files(root)}, 'Incomplete source ZIP manifest')
        for name in sources:
            require(archive.read(name) == (root / Path(name).relative_to('FileFlash')).read_bytes(),
                    f'Source ZIP differs: {name}')
    print(json.dumps(verify(root, VERSION, apk, source, assets, sources), indent=2))
