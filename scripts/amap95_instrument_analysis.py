"""Inspect the two pinned AMap APKs without changing or executing either one.

Only ELF metadata and the E02 large-screen configuration are reported. This is
an analysis tool, not an APK builder or an instrument renderer.
"""
import argparse
from dataclasses import dataclass
import hashlib
import json
from pathlib import Path
import struct
import zipfile


ROOT = Path(__file__).resolve().parents[1]
STOCK_SHA256 = "82272e4dc531c3ff1b1c6fff02d5fc0362f963319e435e0f5021d4b6dc5c67dc"
PUBLIC_SHA256 = "f2855369c11b346756ff828fa453447c812563b0cda678fcc6a36326d385fa00"
HINT_BASE_APK = ROOT / "agent_docs/amap-public95-instrument-20261030/working-base-20261030/amap95-working-user.apk"
HINT_BASE_SHA256 = "094e296ef0783a76d3c2a82a2b91a5eb3198cef2338db78549295617f523ed92"
LIB_PREFIX = "lib/arm64-v8a/"
SCREEN_PREFIX = "assets/res/HmiRes/"
LARGE_PLUGIN = "libInstrumentPannelLarge.so"
HSL_BUILD_ID = "71b0534ad0e68635f46cd19c669e1ec6686f63c7"
OBSERVER_FUNCTIONS = (
    "_ZN3hsl3HSL19GetMapServiceHandleEv",
    "_ZN3hsl3HSL16GetMapViewHandleEN2bl11MapEngineIDE",
    "_ZN3hsl15MapViewBaseImpl11GetEngineIdEv",
    "_ZN3hsl15MapViewBaseImpl11GetDeviceIdEv",
    "_ZN3hsl18MultiScreenService11GetWindowIdEN2bl11MapEngineIDE",
    "_ZN3hsl18MapViewServiceImpl17SetDeviceObserverEPNS_19IMapServiceObserverE",
    "_ZN3hsl18MapViewServiceImpl20RemoveDeviceObserverEPNS_19IMapServiceObserverE",
)
MULTI_SCREEN_INSTANCE = "_ZN3hsl18MultiScreenService10s_instanceE"
SYSTEM_LIBRARIES = frozenset({
    "libandroid.so", "libc.so", "libdl.so", "libEGL.so", "libGLESv1_CM.so",
    "libGLESv2.so", "libGLESv3.so", "libjnigraphics.so", "liblog.so", "libm.so",
    "libmediandk.so", "libOpenSLES.so", "libstdc++.so", "libvulkan.so", "libz.so",
})


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def checked_slice(data, offset, size):
    if offset < 0 or size < 0 or offset + size > len(data):
        raise ValueError("ELF range outside the input")
    return data[offset:offset + size]


def cstring(data, offset):
    if offset < 0 or offset >= len(data):
        raise ValueError("ELF string offset outside the string table")
    end = data.find(b"\0", offset)
    if end == -1:
        raise ValueError("Unterminated ELF string")
    return data[offset:end].decode("utf-8")


@dataclass(frozen=True)
class NativeModule:
    digest: str
    needed: frozenset
    imports: frozenset
    exports: frozenset
    build_ids: tuple

    @classmethod
    def parse(cls, data):
        if len(data) < 64 or data[:7] != b"\x7fELF\x02\x01\x01":
            raise ValueError("Expected a little-endian ELF64 file")
        if struct.unpack_from("<HH", data, 16) != (3, 183):
            raise ValueError("Expected an AArch64 shared library")
        table = struct.unpack_from("<Q", data, 40)[0]
        stride, count = struct.unpack_from("<HH", data, 58)
        if stride != 64 or not count:
            raise ValueError("Unsupported ELF section table")
        checked_slice(data, table, stride * count)
        sections = [struct.unpack_from("<IIQQQQIIQQ", data, table + n * stride)
                    for n in range(count)]

        def contents(section):
            return checked_slice(data, section[4], section[5])

        def strings(section):
            if section[6] >= len(sections):
                raise ValueError("Invalid ELF section link")
            linked = sections[section[6]]
            if linked[1] != 3:
                raise ValueError("Expected an ELF string table")
            return contents(linked)

        needed, imports, exports, build_ids = set(), set(), set(), []
        for section in sections:
            kind, entry_size = section[1], section[9]
            if kind == 11:  # SHT_DYNSYM
                if entry_size != 24 or section[5] % entry_size:
                    raise ValueError("Unsupported ELF dynamic symbol table")
                names = strings(section)
                for name, info, other, index, _, _ in struct.iter_unpack("<IBBHQQ", contents(section)):
                    binding = info >> 4
                    if name == 0 or binding not in (1, 2):
                        continue
                    symbol = cstring(names, name)
                    # Weak undefined symbols do not require a definition.
                    if index == 0 and binding == 1:
                        imports.add(symbol)
                    elif index != 0 and (other & 3) in (0, 3):
                        exports.add(symbol)
            elif kind == 6:  # SHT_DYNAMIC
                if entry_size != 16 or section[5] % entry_size:
                    raise ValueError("Unsupported ELF dynamic section")
                names = strings(section)
                for tag, value in struct.iter_unpack("<qQ", contents(section)):
                    if tag == 1:
                        needed.add(cstring(names, value))
            elif kind == 7:  # SHT_NOTE
                notes = contents(section)
                pos = 0
                while pos < len(notes):
                    header = checked_slice(notes, pos, 12)
                    name_size, desc_size, note_type = struct.unpack("<III", header)
                    name = checked_slice(notes, pos + 12, name_size)
                    desc_pos = pos + 12 + ((name_size + 3) & ~3)
                    desc = checked_slice(notes, desc_pos, desc_size)
                    pos = desc_pos + ((desc_size + 3) & ~3)
                    if pos > len(notes):
                        raise ValueError("Truncated ELF note padding")
                    if name == b"GNU\0" and note_type == 3:
                        build_ids.append(desc.hex())
        return cls(sha256(data), frozenset(needed), frozenset(imports),
                   frozenset(exports), tuple(build_ids))


def function_bytes(data, symbol_name):
    """Read a defined function without loading the library or scanning strings."""
    NativeModule.parse(data)
    table = struct.unpack_from("<Q", data, 40)[0]
    stride, count = struct.unpack_from("<HH", data, 58)
    sections = [struct.unpack_from("<IIQQQQIIQQ", data, table + n * stride)
                for n in range(count)]
    for section in sections:
        if section[1] != 11:
            continue
        names_section = sections[section[6]]
        names = checked_slice(data, names_section[4], names_section[5])
        symbols = checked_slice(data, section[4], section[5])
        for name, info, _, index, address, size in struct.iter_unpack("<IBBHQQ", symbols):
            if (info & 15) != 2 or not index or cstring(names, name) != symbol_name:
                continue
            if index >= len(sections) or not size:
                raise ValueError("Function has no supported section or size")
            code = sections[index]
            relative = address - code[3]
            if not (code[2] & 4) or relative < 0 or relative + size > code[5]:
                raise ValueError("Function outside its executable section")
            return checked_slice(data, code[4] + relative, size)
    raise ValueError(f"Defined function not found: {symbol_name}")


def device_rendered_truth_table(code):
    """Evaluate only the exact five inspected AArch64 instructions.

    CCMP's fallback immediate 4 sets Z=1, so CSET NE returns false when
    device != 0. Export presence is not evidence of secondary-device support.
    """
    expected = struct.pack("<5I", 0x39451008, 0x7100003F, 0x7A400904, 0x1A9F07E0, 0xD65F03C0)
    if code != expected:
        raise ValueError("Unsupported IsDeviceRendered implementation; do not infer readiness")
    rows = []
    for device in (0, 1, 1001):
        for main_flag in (0, 1):
            zero_flag = (main_flag == 0) if device == 0 else bool(4 & 4)
            rows.append({"deviceArgument": device, "mainFlag": main_flag, "returns": not zero_flag})
    return rows


def primary_surface_creation_call(creation):
    # At c13004..c13024, the first scalar passed to virtual slot 0x48 is zero.
    # Manual vtable/data-flow review connects that slot to CreatMapViewBase
    # and its CreateAndroidDisplay/GAI_MapSurfaceViewStatusGet selector.
    expected_creation = struct.pack("<9I", 0xF94002A8, 0x911B43E2, 0x12000305,
                                    0xAA1503E0, 0x2A1F03E1, 0xAA1703E3,
                                    0xF9402508, 0x92800004, 0xD63F0100)
    if creation != expected_creation:
        raise ValueError("Unsupported InitMapView creation call; do not infer an external-window factory")
    return {
        "creationCallSha256": sha256(creation),
        "firstScalarPassedToCreationVirtualSlot0x48": 0,
        "creationCallVirtualAddress": "0xc13024",
    }


def renderer_entry_points(hsl_data):
    device_code = function_bytes(hsl_data, "_ZNK3hsl18MapViewServiceImpl16IsDeviceRenderedEN2bl11EGLDeviceIDE")
    display_code = function_bytes(hsl_data, "_ZN3hsl18MapViewServiceImpl10GetDisplayEN2bl11MapEngineIDE")
    init_code = function_bytes(hsl_data, "_ZN3hsl18MapViewServiceImpl11InitMapViewEN2bl11MapEngineIDElPNS1_12MapViewParamE")
    creation = primary_surface_creation_call(checked_slice(init_code, 0xC13004 - 0xC12660, 36))
    return {
        "isDeviceRendered": {
            "functionSha256": sha256(device_code),
            "instructionCount": len(device_code) // 4,
            "truthTable": device_rendered_truth_table(device_code),
            "secondaryDeviceFirstFrameCheckSupported": False,
        },
        "getDisplay": {
            "functionSha256": sha256(display_code),
            "returnsNullInPinnedBinaryByManualControlFlowReview": True,
            "notAnAvailableDisplayFactory": True,
        },
        "initMapView": {
            "nullParameterPathReadsPrimarySurfaceSize": True,
            "primarySurfaceSizeCallVirtualAddress": "0xc12bd0",
            "externalMapViewParamConstructionRecovered": False,
            "secondScalarArgumentSemanticsNotEstablished": True,
            "notInvokedWithGuessedArguments": True,
            **creation,
        },
        "noReadyPublishedByThisAnalysis": True,
    }


def observer_binding_contract(hsl_data):
    module = NativeModule.parse(hsl_data)
    required = set(OBSERVER_FUNCTIONS) | {MULTI_SCREEN_INSTANCE}
    missing = sorted(required - module.exports)
    if module.build_ids != (HSL_BUILD_ID,):
        raise ValueError("Observer adapter requires the pinned HSL GNU build ID")
    if missing:
        raise ValueError("Observer binding symbols missing: " + ", ".join(missing))
    return {
        "moduleSha256": module.digest,
        "gnuBuildId": HSL_BUILD_ID,
        "functionSha256": {symbol: sha256(function_bytes(hsl_data, symbol))
                           for symbol in OBSERVER_FUNCTIONS},
        "borrowedSingletonSymbol": MULTI_SCREEN_INSTANCE,
        "noSdkStartupOrGetMapEngineIdCall": True,
        "windowMustBe1001AndActualDeviceMustBeNonPrimary": True,
        "registrationDoesNotPublishReady": True,
    }


def verify_apk(path, expected_digest):
    with path.open("rb") as stream:
        actual = hashlib.file_digest(stream, "sha256").hexdigest()
    if actual != expected_digest:
        raise ValueError(f"Input APK hash mismatch: {path.name}")


def native_modules(archive):
    names = [i.filename for i in archive.infolist()]
    if len(names) != len(set(names)):
        raise ValueError("Duplicate ZIP entries are not supported")
    modules, unparsed = {}, []
    for name in names:
        if not name.startswith(LIB_PREFIX) or not name.endswith(".so"):
            continue
        data = archive.read(name)
        try:
            modules[Path(name).name] = NativeModule.parse(data)
        except (ValueError, struct.error, UnicodeDecodeError) as error:
            # Some cmb resource containers have an ELF header but malformed
            # section metadata. Do not count them as successfully inspected.
            unparsed.append({"entry": name, "sha256": sha256(data), "reason": str(error)})
    return modules, unparsed


def large_screen_reference(config):
    """Select only the target screen and referenced entries, never FuncConfig."""
    screen = config["Screens"]["InstrumentPannelLarge"]
    surface = config["SurfaceConfigs"][screen["Surface"]]
    allowed = ("SurfaceWidth", "SurfaceHeight", "IsSeparateRender", "SurfaceRenderType",
               "WindowId", "EGLAlphaSize", "RemoveSurface", "CreateSurfaceWhenInit",
               "IsMainScreenForegroundStopRender")
    modes = []
    for mode in screen["Modes"]:
        scenes = {}
        for scene in ("Cruise", "Guide", "Preview"):
            if scene not in mode:
                continue
            render = config["RenderConfigs"][mode[scene]["Render"]]
            keys = ("IsMapVisiable", "IsUiVisiable", "MapWidth", "MapHeight", "UiWidth",
                    "UiHeight", "Fps", "Dpi")
            scenes[scene] = {key: render[key] for key in keys if key in render}
        modes.append(scenes)
    return {"surface": {key: surface[key] for key in allowed if key in surface},
            "modes": modes}


def compare_plugin(plugin, public_modules):
    # This generous union is an upper bound: presence does not prove linker
    # scope, symbol-version compatibility, C++ layout, or runtime availability.
    exports = set().union(*(module.exports for module in public_modules.values()))
    missing = sorted(plugin.imports - exports)
    return {
        "sha256": plugin.digest,
        "gnuBuildIds": list(plugin.build_ids),
        "absentNonSystemNeededLibraries": sorted(plugin.needed - public_modules.keys() - SYSTEM_LIBRARIES),
        "strongCxxImportsAbsentFromAllPackagedExports": [s for s in missing if s.startswith("_Z")],
        "otherStrongImportsNotFoundInPackagedExports": [s for s in missing if not s.startswith("_Z")],
        "systemSymbolsNotResolvedByThisTool": True,
        "exportUnionDoesNotEstablishAbiCompatibility": True,
    }


def analyze(stock_path, public_path):
    verify_apk(stock_path, STOCK_SHA256)
    verify_apk(public_path, PUBLIC_SHA256)
    with zipfile.ZipFile(stock_path) as stock, zipfile.ZipFile(public_path) as public:
        public_modules, public_unparsed = native_modules(public)
        # Check duplicate entries in the stock input, too, before selecting it.
        stock_modules, stock_unparsed = native_modules(stock)
        stock_config_bytes = stock.read(SCREEN_PREFIX + "C04010181178_MultipleScreen.json")
        public_config_bytes = public.read(SCREEN_PREFIX + "MultipleScreen.json")
        stock_config = json.loads(stock_config_bytes.decode("utf-8-sig"))
        public_config = json.loads(public_config_bytes.decode("utf-8-sig"))
        hsl_data = public.read(LIB_PREFIX + "libhsl.so")
        renderer_bindings = renderer_entry_points(hsl_data)
        observer_bindings = observer_binding_contract(hsl_data)
    core = public_modules["libautonavi.so"]
    frame = public_modules["libGFrame.so"]
    return {
        "schemaVersion": 1,
        "scope": "2023 Xingrui E02; stock327 reference and supplied public9.5 only",
        "inputs": {"stockApkSha256": STOCK_SHA256, "public95ApkSha256": PUBLIC_SHA256},
        "public95NativeLibraryEntries": len(public_modules) + len(public_unparsed),
        "public95ParsedNativeLibraries": len(public_modules),
        "public95UnparsedNativeEntries": public_unparsed,
        "stock327UnparsedNativeEntries": stock_unparsed,
        "exportAbsenceAppliesToParsedLibrariesOnly": True,
        "largePluginPackagedInPublic95": LARGE_PLUGIN in public_modules,
        "largePlugin": compare_plugin(stock_modules[LARGE_PLUGIN], public_modules),
        "windowConfiguration": {
            "stock327E02": {"sha256": sha256(stock_config_bytes), **large_screen_reference(stock_config)},
            "public95Default": {"sha256": sha256(public_config_bytes), **large_screen_reference(public_config)},
            "stock327ReferenceIsNotAnActual169ConfigurationReadback": True,
            "configurationCopiedOrEnabled": False,
        },
        "tbtCandidate": {
            "coreSha256": core.digest,
            "coreGnuBuildIds": list(core.build_ids),
            "frameSha256": frame.digest,
            "frameGnuBuildIds": list(frame.build_ids),
            "startupExportPresent": "Startuptbt" in core.exports,
            "exitExportPresent": "Exittbt" in core.exports,
            "matchingResourceLibraryPresent": "libcmb_TbtPackage.so" in public_modules,
            "dropInReplacementEstablished": False,
            "missingWork": ["external-frame map renderer and real render completion",
                            "E02 actual Display/Surface lifecycle and teardown",
                            "existing JSON preparation chain driven by real callbacks"],
        },
        "mapRendererEntryPointReview": renderer_bindings,
        "observerBindingContract": observer_bindings,
        "portReady": False,
        "apkModifiedBuiltSignedOrInstalled": False,
        "runtimeOrVehicleValidationPerformed": False,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--stock", type=Path, default=ROOT / "agent_docs/firmware/ihu519g-327/system/app/AutoMap/AutoMap.apk")
    parser.add_argument("--public95", type=Path, default=ROOT / "agent_docs/amap-public95-instrument-20261030/amap95-public-user.apk")
    parser.add_argument("--output", type=Path, default=ROOT / "agent_docs/amap-public95-instrument-20261030/renderer-contract.json")
    args = parser.parse_args()
    try:
        report = analyze(args.stock, args.public95)
        output = args.output.resolve()
        if output.suffix.lower() != ".json" or output in (args.stock.resolve(), args.public95.resolve()):
            raise ValueError("Report output must be a separate JSON file")
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    except (ValueError, OSError, KeyError, zipfile.BadZipFile, struct.error) as error:
        parser.exit(1, f"Analysis failed: {error}\n")
    missing = report["largePlugin"]["strongCxxImportsAbsentFromAllPackagedExports"]
    print(f"Analysis complete: {len(missing)} unresolved C++ import names; portReady=false")
    print(output)


if __name__ == "__main__":
    main()
