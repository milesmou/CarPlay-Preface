"""Explicitly build/sign the pinned AMap 9.5 E02 native-hints candidate.

Requires --build. Uses existing reviewed public AOSP signing inputs. Changes
only classes.dex, the manifest's integer versionCode, the built-in default voice
restored from the pinned public APK, and invalid signatures.
This is the user-selected AMap APK, not a CarPlay/authorization-component build.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import struct
import sys
import zipfile

import amap95_instrument_analysis as analysis
import android9_authorization as signing
import verify_amap95_observer as common


ROOT = common.ROOT
BASE = ROOT / "agent_docs/amap-public95-instrument-20261030"
CANDIDATE = BASE / "working-hints-candidate"
OUTPUT = BASE / "test-apk-07"
VERSION_CODE = 26000008
NAME = f"AMap-9.5.0.600013-E02-native-hints-{VERSION_CODE}-test.apk"
VOICE_ENTRY = "assets/res/HmiRes/voice_init/tts/voices/nvzhongyin"
PUBLIC_VOICE_SHA256 = "1810793a5bd40b40652e76abb34870b18ef012e9bb2c510cac64f1a174e6653f"


def read_public_default_voice():
    public = BASE / "amap95-public-user.apk"
    analysis.verify_apk(public, analysis.PUBLIC_SHA256)
    with zipfile.ZipFile(public) as archive:
        if archive.namelist().count(VOICE_ENTRY) != 1:
            raise ValueError("Public default voice entry missing or duplicated")
        data = archive.read(VOICE_ENTRY)
    if hashlib.sha256(data).hexdigest() != PUBLIC_VOICE_SHA256:
        raise ValueError("Public default voice hash mismatch")
    return data


def signature_entry(name):
    return name.upper().startswith("META-INF/") and (
        name.upper() == "META-INF/MANIFEST.MF" or name.upper().endswith((".SF", ".RSA", ".DSA", ".EC")))


def bump_manifest(data):
    result = bytearray(data)
    kind, header, size = struct.unpack_from("<HHI", data)
    if kind != 3 or header != 8 or size != len(data): raise ValueError("Unexpected binary manifest")
    position, resource_ids, edits = 8, None, []
    while position < size:
        kind, header, length = struct.unpack_from("<HHI", data, position)
        if length < header or length < 8 or position + length > size: raise ValueError("Invalid AXML chunk")
        if kind == 0x180:
            if header != 8 or (length - header) % 4: raise ValueError("Invalid resource map")
            resource_ids = struct.unpack_from("<" + "I" * ((length - header) // 4), data, position + header)
        elif kind == 0x102:
            if resource_ids is None or header != 16: raise ValueError("Unexpected start element")
            attribute_start, stride, count = struct.unpack_from("<HHH", data, position + 24)
            if stride != 20: raise ValueError("Unexpected AXML attribute stride")
            start = position + 16 + attribute_start
            for i in range(count):
                at = start + stride * i
                if at + stride > position + length: raise ValueError("Truncated AXML attribute")
                _, name, raw, typed_size, _, value_type, value = struct.unpack_from("<IIIHBBI", data, at)
                if name < len(resource_ids) and resource_ids[name] == 0x0101021B:
                    if raw != 0xFFFFFFFF or typed_size != 8 or value_type != 0x10 or value != 26000001:
                        raise ValueError("Original versionCode representation changed")
                    struct.pack_into("<I", result, at + 16, VERSION_CODE)
                    edits.append(at + 16)
        position += length
    if len(edits) != 1: raise ValueError("Expected exactly one versionCode attribute")
    at = edits[0]
    if data[:at] != result[:at] or data[at + 4:] != result[at + 4:]: raise ValueError("Unexpected manifest mutation")
    return bytes(result), at


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--build", action="store_true", help="用户已明确要求打包后才能使用")
    parser.add_argument("--install", action="store_true", help="覆盖安装到所有在线设备，不卸载或降级")
    args = parser.parse_args()
    if not args.build: parser.error("显式打包必须提供 --build；此脚本不默认构建")
    OUTPUT.mkdir(parents=True, exist_ok=True)
    report = {"success": False, "apkBuilt": False, "instrumentFunctionVerified": False,
              "scope": "User-authorized working modified AMap 9.5 plus native navigation hints; E02 only"}
    def run(argv, label, timeout=45):
        value = common.execute(argv, timeout)
        report.setdefault("commands", []).append({"label": label, **value})
        return common.require_success(value, label)
    try:
        config = json.loads((ROOT / "agent_docs/build-environment.local.json").read_text(encoding="utf-8-sig"))
        java = Path(config["javaHome"]) / "bin/java.exe"
        build = Path(config["androidHome"]) / "build-tools" / config["buildToolsVersion"]
        align, signer, aapt, adb = build / "zipalign.exe", build / "lib/apksigner.jar", build / "aapt2.exe", Path(config["adbPath"])
        for p in (java, align, signer, aapt, adb):
            if not p.is_file(): raise ValueError(f"缺少缓存工具：{p}")
        key_dir = ROOT / "signing/platform-test-key"
        for name, expected in signing.KEY_HASHES.items():
            if common.digest(key_dir / name) != expected: raise ValueError("Reviewed public signing input hash mismatch")
        prepared = json.loads((CANDIDATE / "verification.json").read_text(encoding="utf-8"))
        if not prepared["success"] or not prepared["candidateDexPrepared"]: raise ValueError("Candidate DEX verification missing")
        for path, expected in prepared["sourceSha256"].items():
            if common.digest(ROOT / path) != expected: raise ValueError("Candidate source changed; re-run DEX preparation")
        dex = CANDIDATE / "candidate.dex"
        if common.digest(dex) != prepared["artifacts"]["candidate.dex"]: raise ValueError("Candidate DEX hash mismatch")
        original = analysis.HINT_BASE_APK
        analysis.verify_apk(original, analysis.HINT_BASE_SHA256)
        if prepared["inputApkSha256"] != analysis.HINT_BASE_SHA256: raise ValueError("Prepared DEX base differs from working APK")
        public_voice = read_public_default_voice()
        unsigned, aligned, final = OUTPUT / "unsigned.apk", OUTPUT / "aligned.apk", OUTPUT / NAME
        with zipfile.ZipFile(original) as source, zipfile.ZipFile(unsigned, "w", allowZip64=False) as target:
            for entry in source.infolist():
                if signature_entry(entry.filename): continue
                payload = source.read(entry)
                if entry.filename == "classes.dex": payload = dex.read_bytes()
                elif entry.filename == VOICE_ENTRY: payload = public_voice
                elif entry.filename == "AndroidManifest.xml":
                    payload, manifest_offset = bump_manifest(payload)
                    report["manifestVersionCodeByteOffset"] = manifest_offset
                target.writestr(entry, payload)
        run([align, "-P", "16", "-f", "4", unsigned, aligned], "ZIP alignment")
        run([java, "-jar", signer, "sign", "--key", key_dir / "platform.pk8", "--cert", key_dir / "platform.x509.pem",
             "--out", final, aligned], "APK signing")
        verification = run([java, "-jar", signer, "verify", "--min-sdk-version", "28", "--verbose", "--print-certs", final], "APK API28 signature verification")
        certificate = signing.signer_digest(verification)
        if certificate != signing.PLATFORM: raise ValueError("Final signer differs from reviewed stock327 certificate")
        run([align, "-c", "-P", "16", "4", final], "Final ZIP alignment verification")
        badging = run([aapt, "dump", "badging", final], "Final APK identity")
        identity = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging)
        if identity is None or identity.groups() != ("com.autonavi.amapauto", str(VERSION_CODE), "9.5.0.600013"):
            raise ValueError("Final identity mismatch")
        preserved = []
        with zipfile.ZipFile(original) as before, zipfile.ZipFile(final) as after:
            if after.testzip() is not None: raise ValueError("Final ZIP CRC failure")
            old = {n for n in before.namelist() if not signature_entry(n)}
            new = {n for n in after.namelist() if not signature_entry(n)}
            if old != new or len(after.namelist()) != len(set(after.namelist())): raise ValueError("APK entry set changed")
            for name in sorted(old):
                data = after.read(name)
                if name == "classes.dex":
                    if data != dex.read_bytes(): raise ValueError("Packaged DEX mismatch")
                elif name == VOICE_ENTRY:
                    if data != public_voice or hashlib.sha256(data).hexdigest() != PUBLIC_VOICE_SHA256:
                        raise ValueError("Packaged default voice differs from pinned public version")
                elif name == "AndroidManifest.xml":
                    expected, _ = bump_manifest(before.read(name))
                    if data != expected: raise ValueError("Manifest changed outside versionCode")
                else:
                    if data != before.read(name): raise ValueError("Unrelated APK payload changed: " + name)
                    preserved.append({"path": name, "sha256": hashlib.sha256(data).hexdigest()})
        report.update(apkBuilt=True, output=str(final.relative_to(ROOT)), sha256=common.digest(final),
                      bytes=final.stat().st_size, packageName=identity[1], versionCode=int(identity[2]), versionName=identity[3],
                      certificateSha256=certificate, candidateDexSha256=common.digest(dex),
                      preservedEntries=preserved, originalNativeLibraryCount=sum(x["path"].startswith("lib/") for x in preserved),
                      manifestChangedOnlyVersionCode=True, noSigningInputsPackaged=True,
                      sourceApkSha256=analysis.HINT_BASE_SHA256, candidateVerificationSha256=common.digest(CANDIDATE / "verification.json"),
                      baseStartupAndSignatureHandlingPreserved=True)
        report["restoredPublicDefaultVoice"] = {
            "entry": VOICE_ENTRY, "sha256": PUBLIC_VOICE_SHA256, "bytes": len(public_voice),
            "sourceApkSha256": analysis.PUBLIC_SHA256,
            "existingUserDownloadedOrSelectedVoiceSettingsNotRewritten": True,
        }
        if args.install:
            devices = run([adb, "devices", "-l"], "Online Android devices")
            online = [line.split()[0] for line in devices.splitlines()[1:] if len(line.split()) > 1 and line.split()[1] == "device"]
            report["installations"] = []
            for serial in online:
                if re.fullmatch(r"[A-Za-z0-9_.:\[\]-]+", serial) is None: raise ValueError("Unexpected ADB serial")
                installed_paths = run([adb, "-s", serial, "shell", "pm", "path", identity[1]], "Installed map location")
                paths = [line[8:] for line in installed_paths.splitlines() if line.startswith("package:")]
                if paths:
                    if len(paths) != 1 or not re.fullmatch(r"/[A-Za-z0-9_./=+~-]+/base\.apk", paths[0]):
                        raise ValueError("Existing map APK path/split layout needs review")
                    backup = OUTPUT / ("installed-before-" + serial.replace(":", "_") + ".apk")
                    run([adb, "-s", serial, "pull", paths[0], backup], "Read existing installed APK", timeout=60)
                    installed_cert = signing.signer_digest(run([java, "-jar", signer, "verify", "--min-sdk-version", "28", "--print-certs", backup], "Installed map signer"))
                    if installed_cert != certificate: raise ValueError("Existing map signer differs; preserving application and data")
                    record = {"device": serial, "previousApkSha256": common.digest(backup), "certificateSha256": installed_cert}
                else: record = {"device": serial, "previousMapAbsent": True}
                record["result"] = run([adb, "-s", serial, "install", "-r", final], "Install current candidate", timeout=60)
                report["installations"].append(record)
        report["success"] = True
        print(str(final))
        print("Signed APK payload verified; " + ("installation completed" if args.install and report.get("installations") else "no installation performed"))
    except (KeyError, OSError, RuntimeError, ValueError, zipfile.BadZipFile, struct.error) as error:
        report["error"] = str(error)
        print(str(error), file=sys.stderr)
    finally:
        # Full badging/native assets remain in the ignored local report only.
        (OUTPUT / "verification.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    return 0 if report["success"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
