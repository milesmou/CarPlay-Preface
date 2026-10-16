"""Installer guard regression tests: no real device calls or permission mutations."""
import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('authorization_installer', Path(__file__).resolve().parents[1] / 'android9_authorization.py')
subject = importlib.util.module_from_spec(spec)
spec.loader.exec_module(subject)


class InstallerGuardTest(unittest.TestCase):
    def test_stock_and_unmarked_components_are_not_update_targets(self):
        own = "package: name='com.android.vpndialogs' versionCode='1' versionName='preface-android9-1'"
        marker = 'android:name="preface.authorization.component"\nandroid:value="android9-v1"'
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
        badge = "package: name='com.android.vpndialogs' versionName='preface-android9-1'\r\nminSdkVersion:'28'\r\ntargetSdkVersion:'28'"
        marker = 'android:name="preface.authorization.component"\nandroid:value="android9-v1"'
        with patch.object(subject, 'run', side_effect=['Signer #1 certificate SHA-256 digest: ' + subject.PLATFORM, badge, marker]):
            subject.check_apk(paths, Path('supplement.apk'))

    def test_debuggable_privileged_apk_is_rejected(self):
        paths = {'java': 'java', 'signer': 'signer', 'aapt2': 'aapt2'}
        badge = "package: name='com.android.vpndialogs' versionName='preface-android9-1'\nminSdkVersion:'28'\ntargetSdkVersion:'28'"
        marker = 'android:name="preface.authorization.component"\nandroid:value="android9-v1"\nandroid:debuggable(0x101000f)=true'
        with patch.object(subject, 'run', side_effect=['Signer #1 certificate SHA-256 digest: ' + subject.PLATFORM, badge, marker]):
            with self.assertRaisesRegex(ValueError, 'debuggable'):
                subject.check_apk(paths, Path('supplement.apk'))


if __name__ == '__main__':
    unittest.main()
