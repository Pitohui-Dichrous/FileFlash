"""Current 0.1.6 evidence; older release evidence is not relabelled."""
import hashlib
import json
import xml.etree.ElementTree as ET


def verify(root, version, apk, source, assets, sources):
    evidence_dir = root / 'docs/test-evidence'
    digest = lambda p: hashlib.sha256(p.read_bytes()).hexdigest()
    apk_sha = digest(apk)

    def load(name):
        value = json.loads((evidence_dir / name).read_text(encoding='utf-8'))
        assert value['passed'], name
        return value

    core = ET.parse(evidence_dir / 'core-tests.xml').getroot()
    unit = ET.parse(evidence_dir / 'android-model-tests.xml').getroot()
    for tests in [core, unit]:
        assert int(tests.attrib['failures']) == int(tests.attrib['errors']) == int(tests.attrib.get('skipped', '0')) == 0
    core_count, unit_count = int(core.attrib['tests']), int(unit.attrib['tests'])
    assert core_count == 70 and unit_count == 2

    signed = load('ios-shortcut-signature.json')
    assert signed['signatureVerified'] and signed['certificateChainVerified']
    assert signed['archiveIntegrityVerified'] and signed['workflowMatchesUnsigned']
    assert signed['signedSha256'] == digest(root / 'app/src/main/assets/web/FileFlash.shortcut')
    assert signed['unsignedSha256'] == digest(root / 'scripts/shortcut/FileFlash.unsigned.plist')

    binary = load('ios-share-apk.json')
    assert binary['version'] == version and binary['apkSha256'] == apk_sha
    assert binary['versionCode'] == 10 and binary['minSdk'] == 29 and binary['targetSdk'] == 37
    assert binary['signatureVerified'] and binary['certificateSha256'] == 'a314ea8f5feb453ad0e3ff383227bc27d0ac6632a37870ebb0e1fb6c32df1e51'
    assert not binary['debuggable'] and binary['releaseLintErrors'] == 0

    checks = {}
    for api in [29, 34]:
        for stem, count in [('receive-ui', 25), ('relay-android', 18)]:
            name = f'{stem}-api{api}-0.1.6.json'
            value = load(name)
            assert len(value['tests']) == count and all(test['passed'] for test in value['tests']), name
            if stem == 'receive-ui':
                assert value['version'] == version and value['apkSha256'] == apk_sha and value['installedApkSha256'] == apk_sha, name
            else:
                assert value['version'] == version and value['apkSha256'] == apk_sha and value['installedApkSha256'] == apk_sha, name
            checks[name] = count
        name = f'ios-share-android-api{api}-0.1.6.json'
        value = load(name)
        assert value['version'] == version and value['apkSha256'] == apk_sha and value['installedApkSha256'] == apk_sha, name
        assert len(value['tests']) == 15 and all(test['passed'] for test in value['tests']), name
        checks[name] = len(value['tests'])

    browser = {}
    for engine in ['chromium', 'webkit']:
        for suffix in ['', '-real-server']:
            name = f'photo-picker-browser-{engine}{suffix}-ios-share.json'
            value = load(name)
            assert value['version'] == version and len(value['checks']) == 9, name
            assert set(value['assetSha256']) == {p.name for p in (root / 'app/src/main/assets/web').iterdir() if p.is_file()}, name
            for filename, sha in value['assetSha256'].items():
                assert digest(root / 'app/src/main/assets/web' / filename) == sha, name
            assert value['fixtureCleanup'] == {'created': 7, 'deleted': 7, 'stateAbsent': True}, name
            if suffix:
                assert value['serverApkSha256'] == apk_sha and value['serverVersion'] == version, name
            browser[name] = len(value['checks'])

    large = load('ios-share-large.json')
    assert large['version'] == version
    assert large['runtimeCoreSha256'] == digest(root / 'core/build/install/core/lib/core.jar')
    api29 = load('ios-share-core-api29.json')
    assert api29['platformApi'] == 29 and api29['javacExitCode'] == 0 and not api29['hostJdkPlatformFallback']
    assert api29['coreJarSha256'] == large['runtimeCoreSha256']
    assert set(api29['sources']) == {p.relative_to(root).as_posix() for p in (root / 'core/src/main/java').rglob('*.java')}
    for filename, sha in api29['sources'].items():
        assert digest(root / filename) == sha
    # The large-file script proves both wire encodings independently.
    assert len(large['modes']) == 2
    assert {item['mode'] for item in large['modes']} == {'content-length', 'chunked'}
    for item in large['modes']:
        assert item['passed'] and item['bytes'] > 4 * 1024 ** 3
        assert item['dataRequests'] == 1 and not item['senderChecksumPrepass']
        assert item['createUploadRequests'] == 0 and item['download']['independentlyPairedReceiver']
        assert item['download']['bytes'] == item['bytes'] and item['download']['sha256'] == item['sha256']
        assert item['download']['range']['crosses4GiB'] and item['download']['range']['exactSourceBytes']
        assert len(item['failureCases']) == 3 and all(case['passed'] and case['prefixBytes'] > 0 and case['remainingRecords'] == 0 for case in item['failureCases'])
        assert item['remainingRecordsAfterOwnerDelete'] == 0
    native = load('native-large-0.1.6.json')
    assert native['version'] == version and native['bytes'] > 4 * 1024 ** 3
    assert native['runtimeCoreSha256'] == large['runtimeCoreSha256']
    assert native['javaMaxHeapMiB'] == large['javaMaxHeapMiB'] == 128
    for required in ['hard process restart: stale auth rejected, recovery key required, uncommitted tail truncated',
                     'committed-prefix recovery SHA-256 beyond 4GiB matches streamed source',
                     'complete upload SHA-256 verified by server', 'full streamed download exact length and SHA-256']:
        assert required in native['checks']
    assert any(check.startswith('HTTP Range offset ') for check in native['checks'])

    clean = (evidence_dir / 'ios-share-clean-build.txt').read_text(encoding='utf-8')
    assert version in clean and 'BUILD SUCCESSFUL' in clean
    assert f'Core tests: {core_count} passed (fresh extracted project XML).' in clean
    assert 'Android model tests: 2 passed (fresh extracted project XML).' in clean
    return {
        'version': version, 'apkZipCrc': True, 'bundledAssetsMatchSource': len(assets),
        'sourceZipCrc': True, 'sourceFilesMatchWorkingTree': len(sources),
        'privateKeysSdkAndCredentialsExcluded': True,
        'minimumAndroidApi': 29, 'coreTestsPassed': core_count, 'androidModelTestsPassed': unit_count,
        'currentAndroidChecks': checks, 'currentBrowserChecks': browser,
        'signedShortcutSha256': signed['signedSha256'], 'shortcutSignatureAndFullWorkflowVerified': True,
        'currentLargeFileModes': [{key: item[key] for key in ['mode', 'bytes']} for item in large['modes']],
        'nativeResumableLargeFileBytes': native['bytes'],
        'cleanSourceBuildCoreTestsPassed': core_count,
        'physicalIPhoneShortcutImportVerified': False, 'physicalIPhonePhotosShareVerified': False,
        'artifacts': [{'name': p.name, 'bytes': p.stat().st_size, 'sha256': digest(p)} for p in [apk, source]],
    }
