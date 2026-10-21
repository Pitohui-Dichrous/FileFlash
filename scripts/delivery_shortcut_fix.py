"""Verify shortcut-only updates against the published 0.1.6 APK.

Old device tests remain baseline evidence; this verifier never relabels them as
tests of a new shortcut running on an iPhone.
"""
import hashlib
import json
import os
import re
import subprocess
import xml.etree.ElementTree as ET
import zipfile


def verify(root, version, apk, source, assets, sources, export_only=False):
    digest = lambda p: hashlib.sha256(p.read_bytes()).hexdigest()
    baseline = root / 'dist/FileFlash-0.1.6.apk'
    version_code = {'0.1.7': 11, '0.1.8': 12}[version]
    assert digest(baseline) == 'a2e57f382be2cd711a9fc346e36d31215d7e7561c1f2790834c78a619b54c4f8'
    changed = []
    with zipfile.ZipFile(baseline) as old, zipfile.ZipFile(apk) as new:
        assert old.testzip() is None
        assert set(old.namelist()) == set(new.namelist())
        for name in old.namelist():
            if old.read(name) != new.read(name):
                changed.append(name)
        vcs_entry = 'META-INF/version-control-info.textproto'
        assert set(changed) == {'AndroidManifest.xml', 'assets/web/FileFlash.shortcut', vcs_entry}, changed
        normalize_vcs = lambda data: re.sub(rb'revision: "[0-9a-f]{40}"', b'revision: "<commit>"', data)
        assert normalize_vcs(old.read(vcs_entry)) == normalize_vcs(new.read(vcs_entry))
        shortcut = root / 'app/src/main/assets/web/FileFlash.shortcut'
        assert new.read('assets/web/FileFlash.shortcut') == shortcut.read_bytes()
        dex = [name for name in new.namelist() if re.fullmatch(r'classes\d*\.dex', name)]
        assert dex

    sdk = os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
    assert sdk, 'Set ANDROID_HOME to your Android SDK'
    from pathlib import Path
    build_tools = Path(sdk) / 'build-tools/37.0.0'
    signer = build_tools / ('apksigner.bat' if os.name == 'nt' else 'apksigner')
    aapt = build_tools / ('aapt.exe' if os.name == 'nt' else 'aapt')
    signing = subprocess.check_output([str(signer), 'verify', '--verbose', '--print-certs', str(apk)], text=True)
    metadata = subprocess.check_output([str(aapt), 'dump', 'badging', str(apk)], text=True, encoding='utf-8')
    assert 'Verified using v2 scheme (APK Signature Scheme v2): true' in signing
    certificate = re.search(r'certificate SHA-256 digest: ([a-f0-9]+)', signing).group(1)
    assert certificate == 'a314ea8f5feb453ad0e3ff383227bc27d0ac6632a37870ebb0e1fb6c32df1e51'
    assert re.search(r"versionName='([^']+)'", metadata).group(1) == version
    assert re.search(r"versionCode='([0-9]+)'", metadata).group(1) == str(version_code)
    assert re.search(r"sdkVersion:'([0-9]+)'", metadata).group(1) == '29'
    assert re.search(r"targetSdkVersion:'([0-9]+)'", metadata).group(1) == '37'
    assert 'application-debuggable' not in metadata
    lint = ET.parse(root / 'app/build/reports/lint-results-release.xml').getroot()
    assert not any(item.get('severity') in ['Error', 'Fatal'] for item in lint.findall('issue'))
    evidence = root / 'docs/test-evidence'
    signed = json.loads((evidence / f'ios-shortcut-signature-{version}.json').read_text(encoding='utf-8'))
    assert all(signed[key] for key in ['passed', 'signatureVerified', 'certificateChainVerified', 'archiveIntegrityVerified', 'workflowMatchesUnsigned'])
    assert signed['signedSha256'] == digest(shortcut)
    assert signed['unsignedSha256'] == digest(root / 'scripts/shortcut/FileFlash.unsigned.plist')
    assert signed['actionCount'] == 38
    regression = json.loads((evidence / f'ios-shortcut-regression-{version}.json').read_text(encoding='utf-8'))
    assert regression['passed'] and regression['unsignedSha256'] == signed['unsignedSha256']
    assert len(regression['checks']) == 17 and all(item['passed'] for item in regression['checks'])
    assert regression['fixtureSha256'] == digest(root / 'scripts/shortcut/fixtures/ios016-condition-regression.json')
    if version == '0.1.8':
        contract = json.loads((evidence / f'ios-shortcut-contract-{version}.json').read_text(encoding='utf-8'))
        assert contract['passed'] and contract['unsignedSha256'] == signed['unsignedSha256']
        assert contract['actionCount'] == 38 and not contract['uncheckedFields']
        assert contract['fixtureSha256'] == digest(root / 'scripts/shortcut/fixtures/parameter-contract.json')
        assert contract['configuredParameterCount'] == len(contract['parameters']) == 136
        assert all(item['passed'] for item in contract['parameters'])
        assert all(item['passed'] for item in contract['checks'])
    public_shortcut = root / 'dist/FileFlash.shortcut'
    assert public_shortcut.read_bytes() == shortcut.read_bytes()
    binary = dict(passed=True, version=version, apkSha256=digest(apk),
                  baselineVersion='0.1.6', baselineApkSha256=digest(baseline),
                  archiveEntryNamesUnchanged=True, changedApkEntries=changed,
                  allDexByteIdentical=True, dexFiles=dex, signatureVerified=True,
                  certificateSha256=certificate, versionCode=version_code, minSdk=29, targetSdk=37,
                  releaseLintErrors=0, physicalIPhoneExecutionVerified=False)
    (evidence / f'ios-shortcut-binary-{version}.json').write_text(json.dumps(binary, indent=2) + '\n', encoding='utf-8')
    if export_only:
        return binary
    # The new evidence must already be in the source-only ZIP, so callers export
    # this report once, repackage, then run the complete delivery check again.
    with zipfile.ZipFile(source) as archive:
        expected = f'FileFlash/docs/test-evidence/ios-shortcut-binary-{version}.json'
        assert archive.read(expected) == (evidence / f'ios-shortcut-binary-{version}.json').read_bytes()
    return dict(version=version, apkZipCrc=True, bundledAssetsMatchSource=len(assets),
                sourceZipCrc=True, sourceFilesMatchWorkingTree=len(sources),
                privateKeysSdkAndCredentialsExcluded=True, minimumAndroidApi=29,
                baselineAndroidVersion='0.1.6', androidAndCoreDexByteIdentical=True,
                changedApkEntries=changed, signedShortcutSha256=signed['signedSha256'],
                shortcutSignatureAndFullWorkflowVerified=True,
                shortcutSchemaRegressionPassed=regression['passed'],
                shortcutParameterContractVerified=(version == '0.1.8'),
                physicalIPhoneShortcutImportVerified=False,
                physicalIPhonePhotosShareVerified=False, largeMediaStallResolved=False,
                artifacts=[dict(name=p.name, bytes=p.stat().st_size, sha256=digest(p))
                           for p in [apk, source, public_shortcut]])


if __name__ == '__main__':
    import argparse
    from pathlib import Path
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--export-binary', action='store_true', required=True)
    parser.parse_args()
    root = Path(__file__).resolve().parent.parent
    version = re.search(r'versionName\s*=\s*"([^"]+)"', (root/'app/build.gradle.kts').read_text(encoding='utf-8')).group(1)
    print(json.dumps(verify(root, version, root/f'dist/FileFlash-{version}.apk', None, [], [], export_only=True), indent=2))
