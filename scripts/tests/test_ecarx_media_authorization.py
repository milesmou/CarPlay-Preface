"""Local provisioning checks use synthetic inputs; no source credentials or APK build."""
import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import zipfile

spec = importlib.util.spec_from_file_location('media_authorization',
    Path(__file__).resolve().parents[1] / 'ecarx_media_authorization.py')
subject = importlib.util.module_from_spec(spec)
spec.loader.exec_module(subject)


class MediaAuthorizationInputsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.apk = self.root / 'source.apk'
        self.output = self.root / 'private-inputs'
        self.values = {'eCarX_OpenAPI_AppId': 'synthetic-app-id',
                       'eCarX_OpenAPI_AppKey': 'synthetic-app-key'}
        self.license = b'synthetic-signed-license-data'
        with zipfile.ZipFile(self.apk, 'w') as archive:
            archive.writestr('assets/license.txt', self.license)
        self.source_hash = subject.digest(self.apk.read_bytes())
        self.xmltree = '\n'.join(
            'E: meta-data\n A: http://schemas.android.com/apk/res/android:name(0x01)="' + key +
            '"\n A: http://schemas.android.com/apk/res/android:value(0x02)="' + value + '"'
            for key, value in self.values.items())
        for name, value in [('SOURCE_SHA256', self.source_hash),
                            ('LICENSE_SHA256', subject.digest(self.license)),
                            ('METADATA_SHA256', subject.digest(subject.metadata_bytes(self.values)))]:
            patcher = patch.object(subject, name, value)
            patcher.start()
            self.addCleanup(patcher.stop)

    def import_inputs(self, manifest=None):
        result = subprocess.CompletedProcess([], 0,
            (self.xmltree if manifest is None else manifest).encode(), b'')
        with patch.object(subject.subprocess, 'run', return_value=result):
            subject.import_inputs(self.apk, Path('aapt2'), self.output)

    def test_import_preserves_exact_license_and_separates_metadata_from_assets(self):
        self.import_inputs()
        self.assertEqual(self.license, (self.output / 'assets/license.txt').read_bytes())
        self.assertEqual(self.values, json.loads((self.output / 'manifest-values.json').read_bytes()))
        provenance = json.loads((self.output / 'provenance.json').read_bytes())
        self.assertTrue(provenance['preserveApplicationPackageAndSigning'])
        self.assertFalse(provenance['admissionConfirmed'])
        self.assertNotIn(self.values['eCarX_OpenAPI_AppKey'], json.dumps(provenance))
        subject.verify_inputs(self.output)

    def test_wrong_source_is_rejected_before_reading_manifest_or_creating_outputs(self):
        self.apk.write_bytes(b'unexpected APK')
        with patch.object(subject.subprocess, 'run') as tool:
            with self.assertRaises(ValueError):
                subject.import_inputs(self.apk, Path('aapt2'), self.output)
            tool.assert_not_called()
        self.assertFalse(self.output.exists())

    def test_missing_paired_metadata_cannot_produce_partial_outputs(self):
        with self.assertRaises(ValueError):
            self.import_inputs('E: application')
        self.assertFalse(self.output.exists())

    def test_altered_license_metadata_or_provenance_is_rejected(self):
        for relative in ['assets/license.txt', 'manifest-values.json', 'provenance.json']:
            with self.subTest(relative=relative):
                self.import_inputs()
                (self.output / relative).write_bytes(b'changed')
                with self.assertRaises(ValueError):
                    subject.verify_inputs(self.output)

    def test_other_local_files_are_not_silently_bundled(self):
        self.import_inputs()
        (self.output / 'assets/extra.txt').write_bytes(b'private local file')
        with self.assertRaises(ValueError):
            subject.verify_inputs(self.output)

    def test_duplicate_metadata_is_rejected(self):
        with self.assertRaises(ValueError):
            subject.manifest_values(self.xmltree + '\n' + self.xmltree)


if __name__ == '__main__':
    unittest.main()
