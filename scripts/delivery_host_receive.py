"""Verify the 0.1.9 Android host-receive feedback delivery.

The Android UI may change DEX files. The successful 0.1.8 web/Shortcuts assets
and seven core sources stay unchanged; HttpServer has one separately frozen
stop/error-cleanup fix. New evidence belongs to this delivery, not an old APK.
"""
from pathlib import Path
import hashlib
import json
import os
import re
import subprocess
import xml.etree.ElementTree as ET
import zipfile


BASELINE_APK_SHA256 = '67e0233f745f7fca6e95bc3e3a4954be22322063f7ab3410f5a22aff242c3831'
BASELINE_CORE_SHA256 = 'b74b8805679682eca23c18b88dee3ec314299339bc1286eaae1b677bbdcd7c57'
# Frozen after the release owner's cleanup-fix build and regression run.
CURRENT_HTTP_SERVER_SHA256 = '75b5a8a583559d140c99a5dd789dac6e6290184694fc7966f0b636332f435b10'
CURRENT_CORE_SHA256 = 'c9d6934382767f34ef6c7abab06df446496d0515be8015f9e07eb471d582a580'
HTTP_SERVER_SOURCE = 'core/src/main/java/app/fileflash/core/HttpServer.java'
BASELINE_SHORTCUT_SHA256 = '26d5b7380182d2c85cd7a726ceabc25377e77541c8f874e9f7c5b10024335a70'
CERTIFICATE_SHA256 = 'a314ea8f5feb453ad0e3ff383227bc27d0ac6632a37870ebb0e1fb6c32df1e51'
GENERATED_PROFILE_ASSETS = {'assets/dexopt/baseline.prof', 'assets/dexopt/baseline.profm'}
BASELINE_CORE_SOURCES = {
    'core/src/main/java/app/fileflash/core/AssetProvider.java': 'e42dad62f8ac2287656393c32dbe409efefa12acbcb3395b3bc3f7bc1deb56f5',
    'core/src/main/java/app/fileflash/core/ClientInfo.java': '277d757b19a1b02e428cac68853b5bc8b52685b70245664ca7e38280f39d8744',
    'core/src/main/java/app/fileflash/core/DesktopServer.java': 'dfe8205c1ee7dab842cef29d869c74edc29740077718e36b8d99583038ebfa44',
    'core/src/main/java/app/fileflash/core/HttpServer.java': '967a2bfa02b60274a6a97038ff9868c434075a40cb7e4876e852d9ea21f9abe6',
    'core/src/main/java/app/fileflash/core/ShareFile.java': 'd5cf2b29aa5cdcb43eedfa0b8c9c1397ae48800a91efe6158dc06dd6f5ce069d',
    'core/src/main/java/app/fileflash/core/Source.java': 'f4588b55a3d2fbcd9a37f429091aaced3d62af69ff1d87e82c3318d4010eb719',
    'core/src/main/java/app/fileflash/core/TransferServer.java': '0ad503627119ead5ea71f59ca22ec739d73bc95db538d28d003d221f74d56d61',
    'core/src/main/java/app/fileflash/core/TransportDiagnostics.java': '83e9c092c318085bf8d3ac7f8d228b8357496fc52dea96bded5d7dfb1c9d2f05',
}
REQUIRED_UI_CASES = (
    'Content-Length browser stream', 'known-length upload',
    'chunked Shortcuts protocol', 'paused sender', 'native chunk protocol',
    'connection interruption', 'concurrent known and unknown',
    'long filename', 'landscape', 'system animation disabled',
    'stopping an actively receiving server', 'restarting server',
    'hard process restart', 'exact-ID cleanup', 'previous saved ledger',
)


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _junit(path, classname, test_source, required_names=()):
    suite = ET.parse(path).getroot()
    require(suite.tag == 'testsuite' and suite.get('name') == classname,
            f'{path.name}: unexpected test suite')
    for attribute in ('failures', 'errors', 'skipped'):
        require(int(suite.get(attribute, '0')) == 0,
                f'{path.name}: {attribute} must be zero')
    cases = suite.findall('testcase')
    names = {case.get('name') for case in cases}
    require(cases and len(cases) == len(names) == int(suite.get('tests', '0')),
            f'{path.name}: empty, duplicate or incomplete test cases')
    require(all(case.get('classname') == classname and
                not any(case.find(tag) is not None for tag in ('failure', 'error', 'skipped'))
                for case in cases), f'{path.name}: unsuccessful or foreign test case')
    source = test_source.read_text(encoding='utf-8')
    declared = set(re.findall(r'@Test(?:\([^)]*\))?\s+fun\s+([A-Za-z_]\w*)\s*\(', source))
    require(declared and names == declared,
            f'{path.name}: XML does not execute every current source test')
    require(set(required_names).issubset(names),
            f'{path.name}: required behavior coverage is missing')
    return {'testsPassed': len(cases), 'testCases': sorted(names),
            'xmlSha256': digest(path), 'testSourceSha256': digest(test_source)}


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
    certificates = re.findall(r'certificate SHA-256 digest: ([a-f0-9]+)', signing)
    require(certificates == [CERTIFICATE_SHA256], 'Unexpected signing certificate')
    expected_metadata = {
        r"package: name='([^']+)'": 'app.fileflash',
        r"versionName='([^']+)'": '0.1.9',
        r"versionCode='([0-9]+)'": '13',
        r"sdkVersion:'([0-9]+)'": '29',
        r"targetSdkVersion:'([0-9]+)'": '37',
    }
    for pattern, expected in expected_metadata.items():
        match = re.search(pattern, metadata)
        require(match is not None and match.group(1) == expected,
                f'Unexpected Android package metadata: {pattern}')
    require('application-debuggable' not in metadata, 'Release APK is debuggable')
    lint_path = root / 'app/build/reports/lint-results-release.xml'
    lint = ET.parse(lint_path).getroot()
    require(not any(issue.get('severity') in ('Error', 'Fatal') for issue in lint.findall('issue')),
            'Release lint contains errors')
    return {'versionCode': 13, 'minSdk': 29, 'targetSdk': 37,
            'signatureVerified': True, 'certificateSha256': CERTIFICATE_SHA256,
            'debuggable': False, 'releaseLintErrors': 0,
            'lintReportSha256': digest(lint_path)}


def _core_junit(root, path):
    document = ET.parse(path).getroot()
    require(document.tag == 'testsuites', f'{path.name}: expected merged testsuites')
    suites = document.findall('testsuite')
    source_root = root / 'core/src/test/java'
    source_files = list(source_root.rglob('*.java'))
    expected = {'.'.join(source.relative_to(source_root).with_suffix('').parts): source
                for source in source_files}
    require(len(expected) == len(source_files) == 5,
            'Expected the four existing core suites and HttpServerCleanupTest')
    classnames = [suite.get('name') for suite in suites]
    require(len(classnames) == len(set(classnames)) == 5 and set(classnames) == set(expected),
            f'{path.name}: core suite inventory differs from current test sources')
    classes = {}
    for suite in suites:
        classname = suite.get('name')
        for attribute in ('failures', 'errors', 'skipped'):
            require(int(suite.get(attribute, '0')) == 0,
                    f'{classname}: {attribute} must be zero')
        cases = suite.findall('testcase')
        names = [case.get('name', '') for case in cases]
        # Gradle's JUnit 5 XML uses methodName(); these suites have no custom
        # display names or parameterized invocations.
        methods = {name[:-2] if name.endswith('()') else name for name in names}
        require(cases and len(cases) == len(methods) == int(suite.get('tests', '0')),
                f'{classname}: empty, duplicate or incomplete test cases')
        require(all(case.get('classname') == classname and
                    not any(case.find(tag) is not None for tag in ('failure', 'error', 'skipped'))
                    for case in cases), f'{classname}: unsuccessful or foreign test case')
        source = expected[classname]
        declared = set(re.findall(
            r'@Test(?:\([^)]*\))?\s+(?:(?:public|protected|private|static|final)\s+)*void\s+([A-Za-z_]\w*)\s*\(',
            source.read_text(encoding='utf-8')))
        require(declared and methods == declared,
                f'{classname}: XML does not execute every current source test')
        classes[classname] = {'testsPassed': len(cases), 'testCases': sorted(methods),
                              'testSourceSha256': digest(source)}
    count = sum(value['testsPassed'] for value in classes.values())
    cleanup = classes.get('app.fileflash.core.HttpServerCleanupTest')
    require(count == 73 and cleanup is not None and cleanup['testsPassed'] == 3,
            'Expected 70 existing core tests and three cleanup regressions')
    require(int(document.get('tests', '-1')) == count,
            f'{path.name}: merged test count is incomplete')
    for attribute in ('failures', 'errors', 'skipped'):
        require(int(document.get(attribute, '-1')) == 0,
                f'{path.name}: merged {attribute} must be zero')
    return {'testsPassed': count, 'classes': classes, 'xmlSha256': digest(path)}


def verify(root, version, apk, source, assets, sources):
    root, apk, source = Path(root), Path(apk), Path(source)
    require(version == '0.1.9', 'This gate is only for FileFlash 0.1.9')
    apk_sha = digest(apk)
    baseline = root / 'dist/FileFlash-0.1.8.apk'
    require(digest(baseline) == BASELINE_APK_SHA256, '0.1.8 baseline APK changed')
    with zipfile.ZipFile(baseline) as old, zipfile.ZipFile(apk) as new:
        require(old.testzip() is None and new.testzip() is None, 'APK ZIP CRC failure')
        old_names, new_names = old.namelist(), new.namelist()
        require(len(old_names) == len(set(old_names)) and len(new_names) == len(set(new_names)),
                'Duplicate APK ZIP entry')
        old_assets = {name for name in old_names if name.startswith('assets/') and not name.endswith('/')}
        new_assets = {name for name in new_names if name.startswith('assets/') and not name.endswith('/')}
        require(old_assets and old_assets == new_assets, 'APK asset inventory changed from 0.1.8')
        changed_assets = {name for name in old_assets if old.read(name) != new.read(name)}
        require(changed_assets.issubset(GENERATED_PROFILE_ASSETS),
                'User-bundled APK assets changed from the successful 0.1.8 baseline')
        # AGP compiles these profiles against DEX checksums/indices. UI changes
        # legitimately regenerate them; copying an old profile is not a fix.
        profile_changes = [
            {'name': name, 'baselineSha256': hashlib.sha256(old.read(name)).hexdigest(),
             'currentSha256': hashlib.sha256(new.read(name)).hexdigest(),
             'baselineBytes': len(old.read(name)), 'currentBytes': len(new.read(name))}
            for name in sorted(changed_assets)
        ]
        require({'AndroidManifest.xml', 'classes.dex', 'resources.arsc'}.issubset(new_names),
                'APK is missing required Android entries')
        shortcut_bytes = new.read('assets/web/FileFlash.shortcut')
        require(hashlib.sha256(shortcut_bytes).hexdigest() == BASELINE_SHORTCUT_SHA256,
                'The successful 0.1.8 shortcut changed')
        for asset in assets:
            name = 'assets/' + asset.relative_to(root / 'app/src/main/assets').as_posix()
            require(new.read(name) == asset.read_bytes(), f'APK/source asset mismatch: {name}')

    actual_core_sources = {path.relative_to(root).as_posix()
                           for path in (root / 'core/src/main/java').rglob('*.java')}
    require(actual_core_sources == set(BASELINE_CORE_SOURCES), 'Transfer core source inventory changed')
    require(isinstance(CURRENT_HTTP_SERVER_SHA256, str) and
            re.fullmatch(r'[0-9a-f]{64}', CURRENT_HTTP_SERVER_SHA256) and
            isinstance(CURRENT_CORE_SHA256, str) and re.fullmatch(r'[0-9a-f]{64}', CURRENT_CORE_SHA256),
            'Release owner has not frozen the cleanup source/JAR digests')
    expected_core_sources = dict(BASELINE_CORE_SOURCES)
    expected_core_sources[HTTP_SERVER_SOURCE] = CURRENT_HTTP_SERVER_SHA256
    for name, expected in expected_core_sources.items():
        require(digest(root / name) == expected, f'Transfer core source changed: {name}')
    for name in ('core/build/libs/core.jar', 'core/build/install/core/lib/core.jar'):
        require(digest(root / name) == CURRENT_CORE_SHA256, f'Transfer core JAR changed: {name}')

    binary = _android_binary(root, apk)
    evidence = root / 'docs/test-evidence'
    core_tests = _core_junit(root, evidence / 'host-receive-core-tests-0.1.9.xml')
    model_tests = {
        'HostReceiveFeedbackTest': _junit(
            evidence / 'host-receive-feedback-tests-0.1.9.xml',
            'app.fileflash.HostReceiveFeedbackTest',
            root / 'app/src/test/java/app/fileflash/HostReceiveFeedbackTest.kt'),
        'UploadEntryTest': _junit(
            evidence / 'server-model-tests-0.1.9.xml',
            'app.fileflash.UploadEntryTest',
            root / 'app/src/test/java/app/fileflash/UploadEntryTest.kt'),
    }
    android_checks = {}
    for api in (29, 34):
        name = f'host-receive-ui-api{api}.json'
        value = json.loads((evidence / name).read_text(encoding='utf-8'))
        require(value.get('passed') is True and value.get('version') == version,
                f'{name}: wrong version or failed run')
        require(value.get('apkSha256') == apk_sha and value.get('installedApkSha256') == apk_sha,
                f'{name}: evidence is not bound to the delivered and installed APK')
        require(value.get('apiLevel') == api, f'{name}: wrong Android API level')
        tests = value.get('tests')
        require(isinstance(tests, list) and tests and
                all(test.get('passed') is True for test in tests),
                f'{name}: missing or unsuccessful Android checks')
        names = [test.get('name') for test in tests]
        require(all(isinstance(item, str) and item for item in names) and len(names) == len(set(names)),
                f'{name}: empty or duplicate Android checks')
        require(all(any(item.startswith(prefix) for item in names) for prefix in REQUIRED_UI_CASES),
                f'{name}: required protocol, layout or lifecycle coverage is missing')
        navigation = [test for test in tests
                      if test['name'].startswith('receiving progress remains global on ')]
        require(len(navigation) == 4 and
                len({test.get('selectedTab') for test in navigation
                     if isinstance(test.get('selectedTab'), str) and test['selectedTab']}) == 4,
                f'{name}: receiving feedback was not checked on four distinct tabs')
        android_checks[name] = {'checksPassed': len(tests), 'reportSha256': digest(evidence / name)}

    clean_path = evidence / 'host-receive-clean-build.txt'
    clean = clean_path.read_text(encoding='utf-8')
    model_count = sum(value['testsPassed'] for value in model_tests.values())
    require(clean.startswith('0.1.9: source-only ZIP extraction;') and 'BUILD SUCCESSFUL' in clean,
            'Missing successful 0.1.9 source-only rebuild')
    core_count = core_tests['testsPassed']
    require(f'Core tests: {core_count} passed (fresh extracted project XML).' in clean,
            'Source-only rebuild core tests do not match current source/XML')
    require(f'Android model tests: {model_count} passed (fresh extracted project XML).' in clean,
            'Source-only rebuild model tests do not match current source/XML')
    public_shortcut = root / 'dist/FileFlash.shortcut'
    require(public_shortcut.read_bytes() == shortcut_bytes, 'Standalone shortcut differs from APK baseline')
    return {
        'version': version, 'apkZipCrc': True, 'sourceZipCrc': True,
        'bundledAssetsMatchSource': len(assets), 'sourceFilesMatchWorkingTree': len(sources),
        'privateKeysSdkAndCredentialsExcluded': True, 'minimumAndroidApi': 29,
        'androidBinary': binary,
        'assetBaselineVersion': '0.1.8', 'assetBaselineApkSha256': BASELINE_APK_SHA256,
        'allUserBundledAssetsByteIdentical': True,
        'unchangedUserBundledApkAssets': len(old_assets - GENERATED_PROFILE_ASSETS),
        'generatedProfileChanges': profile_changes,
        'signedShortcutSha256': BASELINE_SHORTCUT_SHA256,
        'coreSourceFilesByteIdenticalToBaseline': len(BASELINE_CORE_SOURCES) - 1,
        'coreBaselineVersion': '0.1.8', 'baselineCoreJarSha256': BASELINE_CORE_SHA256,
        'coreChangedFromBaseline': True, 'coreJarSha256': CURRENT_CORE_SHA256,
        'changedCoreSources': [{
            'name': HTTP_SERVER_SOURCE, 'baselineSha256': BASELINE_CORE_SOURCES[HTTP_SERVER_SOURCE],
            'currentSha256': CURRENT_HTTP_SERVER_SHA256,
            'scope': 'Contain network exceptions while sending error responses and closing stopped connections',
        }],
        'androidDexMayChangeForUi': True,
        'currentCoreTests': core_tests,
        'currentModelTests': model_tests, 'currentAndroidUiChecks': android_checks,
        'cleanSourceBuild': {'coreTestsPassed': core_count, 'androidModelTestsPassed': model_count,
                             'reportSha256': digest(clean_path)},
        'iosCompatibilityBaseline': {
            'version': '0.1.8', 'status': 'userReported',
            'smallFileSent': True, 'largeFileSent': True,
            'scope': 'User reported successful iPhone sending on 0.1.8; not an automated run or 0.1.9 UI validation',
        },
        'physicalIPhoneHostReceiveUiVerified': False,
        'artifacts': [{'name': path.name, 'bytes': path.stat().st_size, 'sha256': digest(path)}
                      for path in (apk, source, public_shortcut)],
    }


if __name__ == '__main__':
    from source_manifest import source_files
    root = Path(__file__).resolve().parent.parent
    apk = root / 'dist/FileFlash-0.1.9.apk'
    source = root / 'dist/FileFlash-0.1.9-source.zip'
    assets = [path for path in (root / 'app/src/main/assets').rglob('*') if path.is_file()]
    with zipfile.ZipFile(source) as archive:
        require(archive.testzip() is None, 'Source ZIP CRC failure')
        sources = archive.namelist()
        require(len(sources) == len(set(sources)), 'Duplicate source ZIP entry')
        require(set(sources) == {'FileFlash/' + path.relative_to(root).as_posix()
                                for path in source_files(root)}, 'Incomplete source ZIP manifest')
        for name in sources:
            require(archive.read(name) == (root / Path(name).relative_to('FileFlash')).read_bytes(),
                    f'Source ZIP differs: {name}')
    print(json.dumps(verify(root, '0.1.9', apk, source, assets, sources), indent=2))
