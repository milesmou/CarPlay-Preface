#!/usr/bin/env python3
"""Sign/install the E02 Android 9 consent supplement; never auto-grant app-ops or replace stock UI."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import ssl
import shutil
import subprocess
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
PLATFORM = 'c8a2e9bccf597c2fb6dc66bee293fc13f2fc47ec77bc6b2b0d52c11f51192ab8'
SHARED_USER_ID = 'android.uid.system'
SYSTEM_APP_ID = 1000
PER_USER_RANGE = 100000
SOURCE = '57adea3c0d8722ec23a487122d85c853f7a93d44'
KEY_HASHES = {
    'platform.pk8': '1ad8ef556870edb70f69a9d3c112544c07de5162ba440d84d33f8bb0c5962875',
    'platform.x509.pem': '9837de028f460c35cc8d3fa45f14eecce30f6fbfe4b93d399aef1acb80c20d14',
}
PERMISSIONS = ['CONTROL_VPN', 'CONNECTIVITY_INTERNAL', 'MANAGE_APP_OPS_MODES', 'GET_APP_OPS_STATS', 'WRITE_SECURE_SETTINGS', 'MANAGE_USERS', 'BLUETOOTH', 'BLUETOOTH_ADMIN']


def run(*command):
    result = subprocess.run([str(v) for v in command], capture_output=True, timeout=90)
    if result.returncode:
        raise RuntimeError(result.stderr.decode('utf-8', 'replace').strip() or result.stdout.decode('utf-8', 'replace').strip())
    return result.stdout.decode('utf-8', 'replace').strip()


def signer_digest(output):
    matches = re.findall(r'Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]{64})', output)
    if len(matches) != 1:
        raise ValueError('Expected exactly one verified APK signer')
    return matches[0].lower()


def installed_supplement(manifest, badging):
    # Both identity and our explicit marker are required; a stock VpnDialogs is never updated.
    return bool(re.search(r"package: name='com.android.vpndialogs'.*versionName='preface-android9-\d+'", badging) and
                re.search(r'"preface.authorization.component"[^\n]*\n[^\n]*"android9-v1"', manifest))


def manifest_shared_user_id(manifest):
    match = re.search(r'\b(?:android:|http://schemas\.android\.com/apk/res/android:)sharedUserId(?:\([^\n)]*\))?="([^"]+)"', manifest)
    return match[1] if match else None


def sdk_compatible(value):
    return value.strip() == '28'


def permission_granted(dump, permission):
    return bool(re.search(r'(?m)^\s*android\.permission\.' + re.escape(permission) + r': granted=true(?:\s|$)', dump))


def runtime_identity(adb):
    current_user = adb('shell', 'am', 'get-current-user').strip()
    if not current_user.isdecimal():
        raise ValueError('Cannot verify the current Android user: ' + current_user)
    user_id = int(current_user)
    uid_output = adb('shell', 'pm', 'list', 'packages', '-U', '--user', str(user_id), 'com.android.vpndialogs')
    uid_matches = re.findall(r'(?m)^package:com\.android\.vpndialogs\s+uid:(\d+)\s*$', uid_output)
    uid = int(uid_matches[0]) if len(uid_matches) == 1 else None
    return {'currentUserId': user_id, 'uid': uid, 'appId': uid % PER_USER_RANGE if uid is not None else None,
            'sharedUserId': SHARED_USER_ID, 'identityReady': uid == user_id * PER_USER_RANGE + SYSTEM_APP_ID}


def tools():
    cache = ROOT / 'agent_docs/build-environment.local.json'
    saved = json.loads(cache.read_text(encoding='utf-8-sig')) if cache.exists() else {}
    sdk = os.environ.get('ANDROID_HOME') or saved.get('androidHome')
    java_home = os.environ.get('JAVA_HOME') or saved.get('javaHome')
    if not sdk or not java_home:
        raise ValueError('Provide existing ANDROID_HOME and JAVA_HOME; no toolchains are downloaded')
    sdk, java_home = Path(sdk), Path(java_home)
    build = sdk / 'build-tools' / saved.get('buildToolsVersion', '36.0.0')
    suffix = '.exe' if os.name == 'nt' else ''
    paths = {'java': java_home / f'bin/java{suffix}', 'aapt2': build / f'aapt2{suffix}',
             'signer': build / 'lib/apksigner.jar', 'adb': sdk / f'platform-tools/adb{suffix}'}
    for name, path in paths.items():
        if not path.is_file():
            raise FileNotFoundError(f'Existing {name} not available: {path}')
    return paths


def check_apk(paths, apk):
    digest = signer_digest(run(paths['java'], '-jar', paths['signer'], 'verify', '--print-certs', apk))
    if digest != PLATFORM:
        raise ValueError('Supplement platform certificate mismatch')
    badging = run(paths['aapt2'], 'dump', 'badging', apk)
    manifest = run(paths['aapt2'], 'dump', 'xmltree', apk, '--file', 'AndroidManifest.xml')
    if not installed_supplement(manifest, badging) or not re.search(r"(?m)^(?:minSdkVersion|sdkVersion):'28'\r?$", badging) or "targetSdkVersion:'28'" not in badging:
        raise ValueError('Unexpected supplement package, version, marker or API level')
    if manifest_shared_user_id(manifest) != SHARED_USER_ID:
        raise ValueError('Supplement must declare sharedUserId=' + SHARED_USER_ID)
    if re.search(r'android:debuggable[^\n]*=(?:true|0xffffffff|0x00000001)(?:\s|$)', manifest):
        raise ValueError('Installable privileged supplement must not be debuggable')
    declared = set(re.findall(r"(?m)^uses-permission: name='([^']+)'", badging))
    missing = {'android.permission.' + name for name in PERMISSIONS} - declared
    if missing:
        raise ValueError('Supplement permissions missing: ' + ', '.join(sorted(missing)))


def prepare_key(fetch_key):
    key_dir = ROOT / 'agent_docs/android9-authorization/platform-test-key'
    key_dir.mkdir(parents=True, exist_ok=True)
    for name, expected in KEY_HASHES.items():
        path = key_dir / name
        if not path.exists():
            if not fetch_key:
                raise FileNotFoundError(f'{path}: use --fetch-public-aosp-key for the reviewed public development key')
            url = f'https://raw.githubusercontent.com/aosp-mirror/platform_build/{SOURCE}/target/product/security/{name}'
            path.write_bytes(urllib.request.urlopen(url, timeout=30).read())
        if hashlib.sha256(path.read_bytes()).hexdigest() != expected:
            raise ValueError(f'Public AOSP input hash mismatch: {name}')
    cert = ssl.PEM_cert_to_DER_cert((key_dir / 'platform.x509.pem').read_text())
    if hashlib.sha256(cert).hexdigest() != PLATFORM:
        raise ValueError('Public platform certificate does not match reference firmware')
    return key_dir


def sign(paths, fetch_key):
    output = ROOT / 'authorization-components/build/outputs/apk/release'
    metadata = json.loads((output / 'output-metadata.json').read_text(encoding='utf-8'))
    if metadata['applicationId'] != 'com.android.vpndialogs' or len(metadata['elements']) != 1:
        raise ValueError('Unexpected Gradle output metadata')
    element = metadata['elements'][0]
    unsigned = output / element['outputFile']
    if not unsigned.is_file():
        raise FileNotFoundError('Build :authorization-components:assembleRelease first')
    key_dir = prepare_key(fetch_key)
    apk = output / f"Preface-Android9-Authorization-{element['versionCode']}.apk"
    run(paths['java'], '-jar', paths['signer'], 'sign', '--key', key_dir / 'platform.pk8',
        '--cert', key_dir / 'platform.x509.pem', '--min-sdk-version', '28', '--out', apk, unsigned)
    check_apk(paths, apk)
    record = {'apk': str(apk), 'sha256': hashlib.sha256(apk.read_bytes()).hexdigest(),
              'versionName': element['versionName'], 'versionCode': element['versionCode'],
              'platformCertificateSha256': PLATFORM, 'sharedUserId': SHARED_USER_ID, 'publicAospBuildCommit': SOURCE,
              'keyInputSha256': KEY_HASHES, 'vehicleVerified': False}
    (output / 'authorization-provenance.json').write_text(json.dumps(record, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    return apk


def install(paths, serial, apk):
    def adb(*args):
        return run(paths['adb'], '-s', serial, *args)
    result = {'serial': serial, 'installed': False, 'identityReady': False}
    sdk = adb('shell', 'getprop', 'ro.build.version.sdk')
    if not sdk_compatible(sdk):
        return {**result, 'reason': f'Android API {sdk}; this component requires API 28'}
    cache = ROOT / 'agent_docs/android9-authorization/devices' / hashlib.sha256(serial.encode()).hexdigest()[:16]
    cache.mkdir(parents=True, exist_ok=True)
    framework = cache / 'framework-res.apk'
    adb('pull', '/system/framework/framework-res.apk', framework)
    if signer_digest(run(paths['java'], '-jar', paths['signer'], 'verify', '--print-certs', framework)) != PLATFORM:
        return {**result, 'reason': 'Actual device platform certificate does not match reference firmware'}
    existing = adb('shell', 'pm', 'path', 'com.android.vpndialogs')
    if not existing and adb('shell', 'pm', 'list', 'packages', '-u', 'com.android.vpndialogs'):
        return {**result, 'reason': 'VpnDialogs is registered but not available to this user; inspect original package state first'}
    if existing:
        existing_paths = [line.removeprefix('package:') for line in existing.splitlines() if line.startswith('package:')]
        if len(existing_paths) != 1:
            return {**result, 'reason': 'Unexpected installed VpnDialogs layout; no overwrite'}
        installed = cache / 'existing-vpndialogs.apk'
        adb('pull', existing_paths[0], installed)
        manifest = run(paths['aapt2'], 'dump', 'xmltree', installed, '--file', 'AndroidManifest.xml')
        badging = run(paths['aapt2'], 'dump', 'badging', installed)
        if not installed_supplement(manifest, badging):
            return {**result, 'reason': 'Stock or unknown VpnDialogs already exists; no overwrite or uninstall'}
        if signer_digest(run(paths['java'], '-jar', paths['signer'], 'verify', '--print-certs', installed)) != PLATFORM:
            return {**result, 'reason': 'Existing supplement signature mismatch'}
        if manifest_shared_user_id(manifest) != SHARED_USER_ID:
            return {**result, 'reason': 'The verified repository authorization supplement has an old or different shared UID; '
                    'Android cannot migrate it with install -r. Manually remove only this old authorization supplement '
                    'and then install the new supplement; this script never uninstalls a package'}
        try:
            existing_identity = runtime_identity(adb)
        except Exception as failure:
            return {**result, 'reason': 'Cannot verify runtime identity of the existing repository authorization supplement; '
                    'no update attempted: ' + str(failure)}
        result['existingIdentity'] = existing_identity
        if not existing_identity['identityReady']:
            return {**result, 'reason': 'The verified repository authorization supplement runtime UID does not match '
                    'the current user android.uid.system/appId 1000; no update attempted. '
                    'Manually remove only this old authorization supplement and then install the new supplement; '
                    'this script never uninstalls a package'}
    message = adb('install', '-r', apk)
    result.update(installed='Success' in message, installResult=message)
    if result['installed']:
        try:
            result.update(runtime_identity(adb))
            if not result['identityReady']:
                result['reason'] = 'APK installed, but the current user runtime UID does not match android.uid.system/appId 1000'
            dump = adb('shell', 'dumpsys', 'package', 'com.android.vpndialogs')
            result['permissions'] = {name: permission_granted(dump, name) for name in PERMISSIONS}
            result['permissionsReady'] = all(result['permissions'].values())
        except Exception as failure:
            result['reason'] = 'APK installed; runtime identity or permission verification failed: ' + str(failure)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sign', action='store_true')
    parser.add_argument('--fetch-public-aosp-key', action='store_true')
    parser.add_argument('--apk', type=Path)
    parser.add_argument('--install-connected', action='store_true')
    parser.add_argument('--serial')
    parser.add_argument('--asset-output', type=Path, help='Copy only the verified public APK and minimal integrity metadata for main-app assets')
    parser.add_argument('--prepare-signing-inputs', action='store_true', help='Prepare only the reviewed public development signing inputs before a fresh Gradle build')
    args = parser.parse_args()
    if args.prepare_signing_inputs:
        print(f'Verified public development signing inputs: {prepare_key(args.fetch_public_aosp_key)}')
        return
    paths = tools()
    apk = sign(paths, args.fetch_public_aosp_key) if args.sign else args.apk
    if apk is None:
        parser.error('Use --sign or --apk with a previously built, verified supplement')
    apk = apk.resolve()
    check_apk(paths, apk)
    print(f'Verified APK: {apk}')
    if args.asset_output:
        badging = run(paths['aapt2'], 'dump', 'badging', apk)
        version = re.search(r"versionCode='(\d+)' versionName='([^']+)'", badging)
        if not version:
            raise ValueError('Cannot read component version')
        target = args.asset_output / 'authorization'
        target.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(apk, target / 'authorization.apk')
        record = {'packageName': 'com.android.vpndialogs', 'versionCode': int(version[1]),
                  'versionName': version[2], 'sha256': hashlib.sha256(apk.read_bytes()).hexdigest(),
                  'certificateSha256': PLATFORM, 'sharedUserId': SHARED_USER_ID}
        (target / 'manifest.json').write_text(json.dumps(record, indent=2) + '\n', encoding='utf-8')
    outcomes = []
    if args.install_connected or args.serial:
        devices = run(paths['adb'], 'devices', '-l')
        serials = [line.split()[0] for line in devices.splitlines()[1:] if len(line.split()) > 1 and line.split()[1] == 'device']
        if args.serial:
            if args.serial not in serials:
                raise ValueError('Selected device is not online and authorized')
            serials = [args.serial]
        for serial in serials:
            try:
                outcomes.append(install(paths, serial, apk))
            except Exception as failure:
                outcomes.append({'serial': serial, 'installed': False, 'reason': str(failure)})
        if not serials:
            print('No online installable devices')
        print(json.dumps(outcomes, ensure_ascii=False, indent=2))
        output = ROOT / 'agent_docs/android9-authorization/install-results.json'
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(json.dumps(outcomes, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')


if __name__ == '__main__':
    main()
