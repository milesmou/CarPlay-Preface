"""Read-only audit of pinned public, working and instrument-test AMap APKs.

Compares ZIP payloads and DEX methods without building, signing, installing or
executing an APK. Compiles only a host-side metadata reader using cached JDK.
Vendor bytes and detailed reports stay in the ignored agent_docs directory.
"""
import hashlib
import json
from pathlib import Path
import struct
import sys
import zipfile

import verify_amap95_observer as common


ROOT = common.ROOT
OUTPUT = ROOT / "agent_docs/amap-public95-instrument-20261030/public-rebuild-audit"
INPUTS = {
    "public": ("agent_docs/amap-public95-instrument-20261030/amap95-public-user.apk",
               "f2855369c11b346756ff828fa453447c812563b0cda678fcc6a36326d385fa00"),
    "working": ("agent_docs/amap-public95-instrument-20261030/working-base-20261030/amap95-working-user.apk",
                "094e296ef0783a76d3c2a82a2b91a5eb3198cef2338db78549295617f523ed92"),
    "current": ("agent_docs/amap-public95-instrument-20261030/test-apk-05/AMap-9.5.0.600013-E02-native-hints-26000006-test.apk",
                "3190527f8fbbc1b0369b0c09e4ba667546a7eec615e864812f46efdf112d26f8"),
}


def digest(data):
    return hashlib.sha256(data).hexdigest()


def signature_entry(name):
    return name.upper().startswith("META-INF/") and (
        name.upper() == "META-INF/MANIFEST.MF" or name.upper().endswith((".SF", ".RSA", ".DSA", ".EC")))


def inventory(path):
    with zipfile.ZipFile(path) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)):
            raise ValueError("Duplicate ZIP entries")
        if archive.testzip() is not None:
            raise ValueError("Input ZIP CRC failure")
        return {name: {"bytes": archive.getinfo(name).file_size, "sha256": digest(archive.read(name))}
                for name in sorted(names)}


def compare_entries(a, b):
    added, removed = sorted(b.keys() - a.keys()), sorted(a.keys() - b.keys())
    changed = [{"path": n, "before": a[n], "after": b[n]} for n in sorted(a.keys() & b.keys())
               if a[n] != b[n]]
    return {"added": added, "removed": removed, "changed": changed,
            "equalCount": sum(a[n] == b[n] for n in a.keys() & b.keys()),
            "signatureEntriesSeparated": True,
            "nonSignatureChanged": [x for x in changed if not signature_entry(x["path"])],
            "nonSignatureAdded": [n for n in added if not signature_entry(n)],
            "nonSignatureRemoved": [n for n in removed if not signature_entry(n)]}


def verify_boundaries(report, inventories, paths):
    for name in ("working", "current"):
        native = {n: v for n, v in inventories[name].items() if n.startswith("lib/")}
        original = {n: v for n, v in inventories["public"].items() if n.startswith("lib/")}
        if len(native) != 63 or native != original:
            raise ValueError("The 63 original native entries changed")
    with zipfile.ZipFile(paths["public"]) as public, zipfile.ZipFile(paths["working"]) as working, \
            zipfile.ZipFile(paths["current"]) as current:
        original = public.read("AndroidManifest.xml")
        if working.read("AndroidManifest.xml") != original:
            raise ValueError("Working base manifest differs from public")
        built = json.loads((paths["current"].parent / "verification.json").read_text(encoding="utf-8"))
        offset = built["manifestVersionCodeByteOffset"]
        modified = current.read("AndroidManifest.xml")
        if original[:offset] != modified[:offset] or original[offset + 4:] != modified[offset + 4:] or \
                struct.unpack_from("<I", original, offset)[0] != 26000001 or \
                struct.unpack_from("<I", modified, offset)[0] != 26000006:
            raise ValueError("Current manifest changes exceed the integer versionCode")
    base = report["dexComparisons"]["public-to-working"]
    if base["changedCommonMethodBodies"] != 2 or len(base["addedClasses"]) != 18 or \
            base["removedClasses"] or base["removedMethodsInCommonClasses"] != 3:
        raise ValueError("Pinned base DEX delta differs from reviewed layout")
    current = report["dexComparisons"]["working-to-current"]
    if current["changedCommonMethodBodies"] != 6 or current["removedClasses"] or \
            current["removedMethodsInCommonClasses"] or current["addedMethodsInCommonClasses"] != 3 or \
            len(current["addedClasses"]) != 20 or any(not n.startswith("LPreface95") for n in current["addedClasses"]):
        raise ValueError("Instrument DEX delta differs from reviewed layout")
    report["verifiedBoundaries"] = {
        "all63NativeEntriesIdenticalAcrossThreeApks": True,
        "publicAndWorkingManifestByteIdentical": True,
        "currentManifestOnlyVersionCode26000001To26000006": True,
        "resourceTableUnchanged": all(inventories[n]["resources.arsc"] == inventories["public"]["resources.arsc"]
                                      for n in ("working", "current")),
        "currentPreservesWorkingBaseNonDexNonManifestNonSignaturePayload":
            all(signature_entry(x["path"]) or x["path"] in ("classes.dex", "AndroidManifest.xml")
                for x in report["zipComparisons"]["working-to-current"]["changed"]),
        "noApkBuiltSignedInstalledOrExecuted": True,
    }


def main():
    OUTPUT.mkdir(parents=True, exist_ok=True)
    report = {"success": False, "purpose": "Read-only reconstruction feasibility and three-way delta audit"}
    try:
        config = json.loads((ROOT / "agent_docs/build-environment.local.json").read_text(encoding="utf-8-sig"))
        java = Path(config["javaHome"]) / "bin/java.exe"
        javac = Path(config["javaHome"]) / "bin/javac.exe"
        jar = ROOT / "agent_docs/firmware/tools/jadx-1.5.6/lib/jadx-1.5.6-all.jar"
        for p in (java, javac, jar):
            if not p.is_file():
                raise ValueError(f"缺少缓存工具，请提供实际路径：{p}")
        def run(argv, label, timeout=60):
            result = common.execute(argv, timeout)
            report.setdefault("commands", []).append({"label": label, **result})
            return common.require_success(result, label)
        paths, inventories = {}, {}
        report["inputs"] = {}
        for label, (relative, expected) in INPUTS.items():
            path = ROOT / relative
            if common.digest(path) != expected:
                raise ValueError("Fixed APK hash mismatch: " + label)
            paths[label] = path
            inventories[label] = inventory(path)
            with zipfile.ZipFile(path) as archive:
                if [n for n in archive.namelist() if n.endswith(".dex")] != ["classes.dex"]:
                    raise ValueError("Expected fixed single-DEX input")
                (OUTPUT / (label + ".dex")).write_bytes(archive.read("classes.dex"))
            report["inputs"][label] = {"path": relative, "sha256": expected,
                                      "bytes": path.stat().st_size, "entryCount": len(inventories[label]),
                                      "dexSha256": common.digest(OUTPUT / (label + ".dex"))}
        tools_dir = OUTPUT / "tools"
        tools_dir.mkdir(exist_ok=True)
        sources = [ROOT / "amap95-instrument/tools/PrepareHintDex.java", ROOT / "amap95-instrument/tools/AuditApkDex.java"]
        run([javac, "-encoding", "UTF-8", "-cp", jar, "-d", tools_dir, *sources], "compile host-only DEX metadata reader")
        report["sourceSha256"] = {p.relative_to(ROOT).as_posix(): common.digest(p)
                                  for p in [*sources, Path(__file__).resolve()]}
        report["zipComparisons"], report["dexComparisons"] = {}, {}
        for a, b in (("public", "working"), ("working", "current"), ("public", "current")):
            key = a + "-to-" + b
            report["zipComparisons"][key] = compare_entries(inventories[a], inventories[b])
            target = OUTPUT / (key + "-dex.json")
            run([java, "-Xmx1g", "-cp", str(jar) + ";" + str(tools_dir), "AuditApkDex",
                 OUTPUT / (a + ".dex"), OUTPUT / (b + ".dex"), target], key + " method comparison")
            report["dexComparisons"][key] = json.loads(target.read_text(encoding="utf-8"))
        verify_boundaries(report, inventories, paths)
        report["inventories"] = inventories
        report["success"] = True
        for key, value in report["dexComparisons"].items():
            print(f"{key}: added classes={len(value['addedClasses'])}, changed common bodies={value['changedCommonMethodBodies']}, removed methods={value['removedMethodsInCommonClasses']}")
        print("Three-way ZIP/DEX audit passed; no APK built, signed, installed or executed.")
    except (KeyError, OSError, RuntimeError, ValueError, zipfile.BadZipFile) as error:
        report["error"] = str(error)
        print(str(error), file=sys.stderr)
    finally:
        (OUTPUT / "verification.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    return 0 if report["success"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
