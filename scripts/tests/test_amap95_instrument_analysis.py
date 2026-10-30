"""Focused checks for the read-only AMap metadata analysis tool."""
import hashlib
import importlib.util
import io
from pathlib import Path
import struct
import sys
import tempfile
import unittest
import warnings
import zipfile


SCRIPT = Path(__file__).resolve().parents[1] / "amap95_instrument_analysis.py"
SPEC = importlib.util.spec_from_file_location("amap95_instrument_analysis", SCRIPT)
analysis = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = analysis
SPEC.loader.exec_module(analysis)


def sample_library():
    names = b"\0libc.so\0required\0optional\0exported\0hidden\0"
    data = bytearray(64)
    data[:7] = b"\x7fELF\x02\x01\x01"
    struct.pack_into("<HH", data, 16, 3, 183)
    sections = [(0,) * 10]

    def add(kind, payload, link=0, entry_size=0):
        offset = len(data)
        data.extend(payload)
        sections.append((0, kind, 0, 0, offset, len(payload), link, 0, 1, entry_size))

    add(3, names)
    symbols = b"".join([
        struct.pack("<IBBHQQ", 0, 0, 0, 0, 0, 0),
        struct.pack("<IBBHQQ", names.index(b"required"), 0x12, 0, 0, 0, 0),
        struct.pack("<IBBHQQ", names.index(b"optional"), 0x22, 0, 0, 0, 0),
        struct.pack("<IBBHQQ", names.index(b"exported"), 0x12, 0, 1, 1, 1),
        struct.pack("<IBBHQQ", names.index(b"hidden"), 0x12, 2, 1, 2, 1),
    ])
    add(11, symbols, link=1, entry_size=24)
    add(6, struct.pack("<qQqQ", 1, 1, 0, 0), link=1, entry_size=16)
    add(7, struct.pack("<III", 4, 4, 3) + b"GNU\0" + b"\x01\x02\x03\x04")
    struct.pack_into("<Q", data, 40, len(data))
    struct.pack_into("<HH", data, 58, 64, len(sections))
    for section in sections:
        data.extend(struct.pack("<IIQQQQIIQQ", *section))
    return bytes(data)


class NativeMetadataTest(unittest.TestCase):
    def test_required_imports_exclude_optional_and_hidden_symbols(self):
        module = analysis.NativeModule.parse(sample_library())
        self.assertEqual(module.needed, {"libc.so"})
        self.assertEqual(module.imports, {"required"})
        self.assertEqual(module.exports, {"exported"})
        self.assertEqual(module.build_ids, ("01020304",))

    def test_rejects_wrong_architecture_and_truncated_table(self):
        data = bytearray(sample_library())
        struct.pack_into("<H", data, 18, 62)
        for value in (bytes(data), sample_library()[:-1], b"not an ELF"):
            with self.subTest(size=len(value)), self.assertRaises(ValueError):
                analysis.NativeModule.parse(value)

    def test_pinned_hash_failure_preserves_input(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "source.apk"
            path.write_bytes(b"original input")
            before = path.read_bytes()
            with self.assertRaisesRegex(ValueError, "hash mismatch"):
                analysis.verify_apk(path, "0" * 64)
            self.assertEqual(path.read_bytes(), before)
            analysis.verify_apk(path, hashlib.sha256(before).hexdigest())

    def test_does_not_treat_unreadable_entries_as_inspected(self):
        buffer = io.BytesIO()
        with zipfile.ZipFile(buffer, "w") as archive:
            archive.writestr(analysis.LIB_PREFIX + "libgood.so", sample_library())
            archive.writestr(analysis.LIB_PREFIX + "libcontainer.so", b"resource container")
        with zipfile.ZipFile(buffer) as archive:
            modules, unparsed = analysis.native_modules(archive)
        self.assertEqual(set(modules), {"libgood.so"})
        self.assertEqual(len(unparsed), 1)
        self.assertEqual(unparsed[0]["entry"], analysis.LIB_PREFIX + "libcontainer.so")

    def test_duplicate_zip_entries_fail_instead_of_selecting_one(self):
        buffer = io.BytesIO()
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", UserWarning)
            with zipfile.ZipFile(buffer, "w") as archive:
                archive.writestr("duplicate", b"first")
                archive.writestr("duplicate", b"second")
        with zipfile.ZipFile(buffer) as archive, self.assertRaisesRegex(ValueError, "Duplicate"):
            analysis.native_modules(archive)


class RendererEntryPointTest(unittest.TestCase):
    def test_main_flag_cannot_make_an_external_device_rendered(self):
        code = struct.pack("<5I", 0x39451008, 0x7100003F, 0x7A400904, 0x1A9F07E0, 0xD65F03C0)
        table = analysis.device_rendered_truth_table(code)
        self.assertEqual([row["returns"] for row in table], [False, True, False, False, False, False])

    def test_changed_native_code_is_unknown_instead_of_successful(self):
        # CSET EQ is the opposite predicate. Never reuse a previous contract
        # after even this one-bit semantic change in the native implementation.
        changed = struct.pack("<5I", 0x39451008, 0x7100003F, 0x7A400904, 0x1A9F17E0, 0xD65F03C0)
        with self.assertRaisesRegex(ValueError, "Unsupported IsDeviceRendered"):
            analysis.device_rendered_truth_table(changed)

    def test_function_reader_rejects_a_symbol_in_nonexecutable_data(self):
        with self.assertRaisesRegex(ValueError, "executable section"):
            analysis.function_bytes(sample_library(), "exported")

    def test_default_creation_call_is_not_an_external_surface_selector(self):
        instructions = [0xF94002A8, 0x911B43E2, 0x12000305, 0xAA1503E0,
                        0x2A1F03E1, 0xAA1703E3, 0xF9402508, 0x92800004, 0xD63F0100]
        code = struct.pack("<9I", *instructions)
        self.assertEqual(analysis.primary_surface_creation_call(code)
                         ["firstScalarPassedToCreationVirtualSlot0x48"], 0)
        instructions[4] = 0x52807D21  # MOV W1, #1001 instead of MOV W1, WZR.
        with self.assertRaisesRegex(ValueError, "Unsupported InitMapView creation"):
            analysis.primary_surface_creation_call(struct.pack("<9I", *instructions))

    def test_observer_binding_rejects_a_different_library_identity(self):
        with self.assertRaisesRegex(ValueError, "pinned HSL GNU build ID"):
            analysis.observer_binding_contract(sample_library())


class ConfigurationScopeTest(unittest.TestCase):
    def test_report_selects_e02_screen_and_omits_unrelated_private_fields(self):
        config = {
            "private": "do not report",
            "Screens": {"InstrumentPannelLarge": {"Surface": "target", "Modes": [
                {"Guide": {"Render": "guide"}}]}, "unrelated": "do not report"},
            "SurfaceConfigs": {"target": {"WindowId": 1001, "SurfaceWidth": 1920,
                                         "SurfaceHeight": 720, "private": "do not report"}},
            "RenderConfigs": {"guide": {"IsUiVisiable": 0, "private": "do not report"}},
        }
        result = analysis.large_screen_reference(config)
        self.assertEqual(result, {"surface": {"WindowId": 1001, "SurfaceWidth": 1920,
                                            "SurfaceHeight": 720},
                                  "modes": [{"Guide": {"IsUiVisiable": 0}}]})


if __name__ == "__main__":
    unittest.main()
