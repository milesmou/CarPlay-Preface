"""Installer guard regression tests: no real device calls or permission mutations."""
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('authorization_installer', Path(__file__).resolve().parents[1] / 'android9_authorization.py')
subject = importlib.util.module_from_spec(spec)
spec.loader.exec_module(subject)

PATHS = {'java': 'java', 'signer': 'signer', 'aapt2': 'aapt2', 'adb': 'adb'}
CERTIFICATE = 'Signer #1 certificate SHA-256 digest: ' + subject.PLATFORM
MARKER = 'android:name="preface.authorization.component"\nandroid:value="android9-v1"'
SHARED_USER = 'android:sharedUserId(0x0101000b)="android.uid.system"'
MANIFEST = SHARED_USER + '\n' + MARKER
BADGE = "package: name='com.android.vpndialogs' versionCode='8' versionName='preface-android9-8'\nminSdkVersion:'28'\ntargetSdkVersion:'28'\n"
DECLARED_PERMISSIONS = ''.join("uses-permission: name='android.permission." + name + "'\n" for name in subject.PERMISSIONS)


class InstallerGuardTest(unittest.TestCase):
    def test_stock_and_unmarked_components_are_not_update_targets(self):
        own = "package: name='com.android.vpndialogs' versionCode='1' versionName='preface-android9-1'"
        marker = MANIFEST
        self.assertTrue(subject.installed_supplement(marker, own))
        self.assertFalse(subject.installed_supplement(marker, "package: name='com.android.vpndialogs' versionName='9'"))
        self.assertFalse(subject.installed_supplement('', own))

    def test_unknown_sdk_skips_before_reading_or_changing_packages(self):
        paths = {'adb': 'adb'}
        with patch.object(subject, 'run', return_value='34') as run:
            result = subject.install(paths, 'emulator', Path('supplement.apk'))
        self.assertFalse(result['installed'])
        self.assertEqual(1, run.call_count)
        self.assertIn('API 34', result['reason'])

    def test_platform_mismatch_never_installs(self):
        paths = {'adb': 'adb', 'java': 'java', 'signer': 'signer'}
        with tempfile.TemporaryDirectory() as directory, patch.object(subject, 'ROOT', Path(directory)):
            with patch.object(subject, 'run', side_effect=['28', '', 'Signer #1 certificate SHA-256 digest: ' + '0' * 64]) as run:
                result = subject.install(paths, 'head-unit', Path('supplement.apk'))
        self.assertFalse(result['installed'])
        self.assertFalse(any('install' in call.args for call in run.call_args_list))

    def test_stock_vpn_package_is_not_overwritten_even_when_platform_matches(self):
        paths = {'adb': 'adb', 'java': 'java', 'signer': 'signer', 'aapt2': 'aapt2'}
        answers = ['28', '', 'Signer #1 certificate SHA-256 digest: ' + subject.PLATFORM,
                   'package:/system/priv-app/VpnDialogs/VpnDialogs.apk', '', 'stock manifest',
                   "package: name='com.android.vpndialogs' versionName='9'"]
        with tempfile.TemporaryDirectory() as directory, patch.object(subject, 'ROOT', Path(directory)):
            with patch.object(subject, 'run', side_effect=answers) as run:
                result = subject.install(paths, 'head-unit', Path('supplement.apk'))
        self.assertFalse(result['installed'])
        self.assertIn('Stock or unknown', result['reason'])
        self.assertFalse(any('install' in call.args for call in run.call_args_list))

    def test_user_uninstalled_original_package_is_not_replaced(self):
        paths = {'adb': 'adb', 'java': 'java', 'signer': 'signer'}
        answers = ['28', '', 'Signer #1 certificate SHA-256 digest: ' + subject.PLATFORM, '', 'package:com.android.vpndialogs']
        with tempfile.TemporaryDirectory() as directory, patch.object(subject, 'ROOT', Path(directory)):
            with patch.object(subject, 'run', side_effect=answers) as run:
                result = subject.install(paths, 'head-unit', Path('supplement.apk'))
        self.assertFalse(result['installed'])
        self.assertIn('registered', result['reason'])
        self.assertFalse(any('install' in call.args for call in run.call_args_list))

    def test_permissions_are_reported_individually_without_any_grant_command(self):
        dump = ' android.permission.CONTROL_VPN: granted=true\n android.permission.GET_APP_OPS_STATS: granted=false\n'
        self.assertTrue(subject.permission_granted(dump, 'CONTROL_VPN'))
        self.assertFalse(subject.permission_granted(dump, 'GET_APP_OPS_STATS'))
        self.assertFalse(subject.permission_granted(dump, 'CONNECTIVITY_INTERNAL'))

    def test_multiple_or_missing_apk_signers_are_rejected(self):
        for output in ['', ('Signer #1 certificate SHA-256 digest: ' + subject.PLATFORM + '\n') * 2]:
            with self.assertRaises(ValueError):
                subject.signer_digest(output)

    def test_current_aapt_min_sdk_format_is_accepted(self):
        paths = {'java': 'java', 'signer': 'signer', 'aapt2': 'aapt2'}
        badge = (BADGE + DECLARED_PERMISSIONS).replace('\n', '\r\n')
        marker = MANIFEST
        with patch.object(subject, 'run', side_effect=['Signer #1 certificate SHA-256 digest: ' + subject.PLATFORM, badge, marker]):
            subject.check_apk(paths, Path('supplement.apk'))

    def test_debuggable_privileged_apk_is_rejected(self):
        paths = {'java': 'java', 'signer': 'signer', 'aapt2': 'aapt2'}
        badge = "package: name='com.android.vpndialogs' versionName='preface-android9-1'\nminSdkVersion:'28'\ntargetSdkVersion:'28'"
        marker = MANIFEST + '\nandroid:debuggable(0x101000f)=true'
        with patch.object(subject, 'run', side_effect=['Signer #1 certificate SHA-256 digest: ' + subject.PLATFORM, badge, marker]):
            with self.assertRaisesRegex(ValueError, 'debuggable'):
                subject.check_apk(paths, Path('supplement.apk'))

    def test_shared_uid_component_requires_read_app_ops_permission(self):
        permissions = ''.join("uses-permission: name='android.permission." + name + "'\n"
                              for name in subject.PERMISSIONS if name != 'GET_APP_OPS_STATS')
        self.assertNotIn('UPDATE_APP_OPS_STATS', subject.PERMISSIONS)
        with patch.object(subject, 'run', side_effect=[CERTIFICATE, BADGE + permissions, MANIFEST]):
            with self.assertRaisesRegex(ValueError, 'GET_APP_OPS_STATS'):
                subject.check_apk(PATHS, Path('supplement.apk'))
        with patch.object(subject, 'run', side_effect=[CERTIFICATE, BADGE + DECLARED_PERMISSIONS, MANIFEST]):
            subject.check_apk(PATHS, Path('supplement.apk'))

    def test_independent_and_other_shared_uid_artifacts_are_rejected(self):
        for shared_user in ['', 'android:sharedUserId="android.uid.phone"']:
            with self.subTest(shared_user=shared_user), patch.object(subject, 'run', side_effect=[
                    CERTIFICATE, BADGE + DECLARED_PERMISSIONS, shared_user + '\n' + MARKER]):
                with self.assertRaisesRegex(ValueError, 'sharedUserId=android.uid.system'):
                    subject.check_apk(PATHS, Path('supplement.apk'))

    def test_shared_uid_from_both_aapt_manifest_formats_is_recognized(self):
        self.assertEqual(subject.SHARED_USER_ID, subject.manifest_shared_user_id(MANIFEST))
        self.assertEqual(subject.SHARED_USER_ID, subject.manifest_shared_user_id(
            'A: http://schemas.android.com/apk/res/android:sharedUserId(0x0101000b)="android.uid.system" (Raw: "android.uid.system")'))
        self.assertIsNone(subject.manifest_shared_user_id(MARKER))

    def mock_install(self, answers):
        with tempfile.TemporaryDirectory() as directory, patch.object(subject, 'ROOT', Path(directory)):
            with patch.object(subject, 'run', side_effect=answers) as calls:
                result = subject.install(PATHS, 'head-unit', Path('supplement.apk'))
        commands = [tuple(map(str, call.args)) for call in calls.call_args_list]
        self.assertFalse(any('uninstall' in command or 'clear' in command for command in commands))
        return result, commands

    def test_old_owned_identity_requires_manual_removal_before_install(self):
        for manifest in [MARKER, 'android:sharedUserId="android.uid.phone"\n' + MARKER]:
            with self.subTest(manifest=manifest):
                result, commands = self.mock_install([
                    '28', '', CERTIFICATE, 'package:/data/app/authorization/base.apk', '', manifest, BADGE, CERTIFICATE])
                self.assertFalse(result['installed'])
                self.assertFalse(result['identityReady'])
                self.assertIn('Manually remove only this old authorization supplement', result['reason'])
                self.assertFalse(any('install' in command for command in commands))

    def test_matching_owned_shared_uid_can_update_and_verifies_actual_current_user_uid(self):
        dump = ''.join(' android.permission.' + name + ': granted=true\n' for name in subject.PERMISSIONS)
        result, commands = self.mock_install([
            '28', '', CERTIFICATE, 'package:/data/app/authorization/base.apk', '', MANIFEST, BADGE, CERTIFICATE,
            '10', 'package:com.android.vpndialogs uid:1001000',
            'Success', '10', 'package:com.android.vpndialogs uid:1001000', dump])
        self.assertTrue(result['installed'])
        self.assertTrue(result['identityReady'])
        self.assertTrue(result['permissionsReady'])
        self.assertEqual(10, result['currentUserId'])
        self.assertEqual(1001000, result['uid'])
        self.assertEqual(1000, result['appId'])
        self.assertTrue(any(command[3:5] == ('install', '-r') for command in commands))
        self.assertIn(('adb', '-s', 'head-unit', 'shell', 'pm', 'list', 'packages', '-U', '--user', '10', 'com.android.vpndialogs'), commands)

    def test_matching_declared_shared_uid_with_wrong_runtime_uid_cannot_update(self):
        for current_user, uid_output in [('0', 'package:com.android.vpndialogs uid:10123'),
                                         ('10', 'package:com.android.vpndialogs uid:1000')]:
            with self.subTest(current_user=current_user, uid_output=uid_output):
                result, commands = self.mock_install([
                    '28', '', CERTIFICATE, 'package:/data/app/authorization/base.apk', '', MANIFEST, BADGE, CERTIFICATE,
                    current_user, uid_output])
                self.assertFalse(result['installed'])
                self.assertFalse(result['identityReady'])
                self.assertFalse(result['existingIdentity']['identityReady'])
                self.assertIn('runtime UID', result['reason'])
                self.assertFalse(any('install' in command for command in commands))

    def test_failed_existing_runtime_identity_query_cannot_update(self):
        for lookup_answers in [[RuntimeError('device disconnected')], ['unknown user'],
                               ['0', RuntimeError('package query failed')]]:
            with self.subTest(lookup_answers=lookup_answers):
                result, commands = self.mock_install([
                    '28', '', CERTIFICATE, 'package:/data/app/authorization/base.apk', '', MANIFEST, BADGE, CERTIFICATE,
                    *lookup_answers])
                self.assertFalse(result['installed'])
                self.assertFalse(result['identityReady'])
                self.assertIn('Cannot verify runtime identity', result['reason'])
                self.assertFalse(any('install' in command for command in commands))

    def test_missing_or_ambiguous_existing_package_uid_cannot_update(self):
        for uid_output in ['', 'package:com.android.vpndialogs.other uid:1000',
                           'package:com.android.vpndialogs uid:1000\npackage:com.android.vpndialogs uid:10123']:
            with self.subTest(uid_output=uid_output):
                result, commands = self.mock_install([
                    '28', '', CERTIFICATE, 'package:/data/app/authorization/base.apk', '', MANIFEST, BADGE, CERTIFICATE,
                    '0', uid_output])
                self.assertFalse(result['installed'])
                self.assertFalse(result['identityReady'])
                self.assertIsNone(result['existingIdentity']['uid'])
                self.assertFalse(any('install' in command for command in commands))

    def test_successful_install_with_wrong_runtime_identity_is_not_ready(self):
        for uid_output in ['package:com.android.vpndialogs uid:10123', '', 'package:com.android.vpndialogs.other uid:1000']:
            with self.subTest(uid_output=uid_output):
                result, _ = self.mock_install(['28', '', CERTIFICATE, '', '', 'Success', '0', uid_output, ''])
                self.assertTrue(result['installed'])
                self.assertFalse(result['identityReady'])
                self.assertFalse(result['permissionsReady'])
                self.assertIn('runtime UID', result['reason'])

    def test_post_install_verification_failure_preserves_install_success(self):
        result, _ = self.mock_install(['28', '', CERTIFICATE, '', '', 'Success', RuntimeError('device disconnected')])
        self.assertTrue(result['installed'])
        self.assertFalse(result['identityReady'])
        self.assertIn('device disconnected', result['reason'])

    def test_wrong_current_user_uid_is_not_treated_as_ready(self):
        result, _ = self.mock_install(['28', '', CERTIFICATE, '', '', 'Success', '10', 'package:com.android.vpndialogs uid:1000', ''])
        self.assertTrue(result['installed'])
        self.assertFalse(result['identityReady'])

    def test_asset_metadata_records_shared_uid(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            apk = root / 'supplement.apk'
            apk.write_bytes(b'mock apk')
            target = root / 'assets'
            with patch.object(sys, 'argv', ['android9_authorization.py', '--apk', str(apk), '--asset-output', str(target)]), \
                    patch.object(subject, 'tools', return_value=PATHS), patch.object(subject, 'check_apk'), \
                    patch.object(subject, 'run', return_value=BADGE), patch('builtins.print'):
                subject.main()
            metadata = json.loads((target / 'authorization/manifest.json').read_text())
            self.assertEqual('android.uid.system', metadata['sharedUserId'])

    def test_signing_provenance_records_shared_uid_without_real_signing(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(subject, 'ROOT', Path(directory)):
            output = Path(directory) / 'authorization-components/build/outputs/apk/release'
            output.mkdir(parents=True)
            (output / 'output-metadata.json').write_text(json.dumps({
                'applicationId': 'com.android.vpndialogs', 'elements': [{
                    'outputFile': 'unsigned.apk', 'versionCode': 8, 'versionName': 'preface-android9-8'}]}))
            (output / 'unsigned.apk').write_bytes(b'mock unsigned apk')
            (output / 'Preface-Android9-Authorization-8.apk').write_bytes(b'mock signed apk')
            with patch.object(subject, 'prepare_key', return_value=Path(directory)), patch.object(subject, 'run'), patch.object(subject, 'check_apk'):
                subject.sign(PATHS, False)
            provenance = json.loads((output / 'authorization-provenance.json').read_text())
            self.assertEqual('android.uid.system', provenance['sharedUserId'])


if __name__ == '__main__':
    unittest.main()
