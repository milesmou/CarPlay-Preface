"""Reproducible "pure public" rebuild of the AMap 9.5 Xingrui instrument candidate.

Uses ONLY the clean public APK plus the small frozen floating-delta bundle
(agent_docs/.../floating-delta) and the compiled Preface95 payload. The large
third-party floating APK is no longer required. Only local DEX artifacts are
produced here; this script never builds, signs, installs or executes an APK.

Stage 0  compile Preface95 payload (base-agnostic, scripts/amap95_payload.py)
Stage 1  public.dex + floating-delta/delta.dex -> merged base DEX
         (asserted to reproduce the previously verified merged hash)
Stage 2  merged base + payload -> candidate DEX (instrument hooks)

Run:
    python scripts/prepare_amap95_public_rebuild.py
"""
import argparse
import json
from pathlib import Path
import subprocess
import sys
import zipfile

import amap95_instrument_analysis as analysis
import amap95_payload
import verify_amap95_observer as common


ROOT = common.ROOT
BASE = ROOT / "agent_docs/amap-public95-instrument-20261030"
PUBLIC_APK = BASE / "amap95-public-user.apk"
DELTA_DIR = BASE / "floating-delta"
DELTA_DEX = DELTA_DIR / "delta.dex"
DELTA_META = DELTA_DIR / "metadata.json"
OUTPUT = BASE / "public-rebuild"
TOOLS = OUTPUT / "tools"
JAR = ROOT / "agent_docs/firmware/tools/jadx-1.5.6/lib/jadx-1.5.6-all.jar"

PUBLIC_CLASSES = 7239
MERGED_CLASSES = 7257
ADDED_FLOATING = 18
PROTOCOL = "Lcom/preface/carplay/instrument/Preface95PreparationProtocol;"


def run(args, report, label, timeout=300):
    result = common.execute(args, timeout)
    report.setdefault("commands", []).append({"label": label, **result})
    return common.require_success(result, label)


def audit(java, tools, before, after, target, report, label):
    run([str(java), "-Xmx2g", "-cp", f"{JAR};{tools}", "AuditApkDex", str(before), str(after), str(target)],
        report, label)
    return json.loads(Path(target).read_text(encoding="utf-8"))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--skip-payload-build", action="store_true",
                        help="复用 public-rebuild/payload/classes.dex，不重新编译 payload")
    args = parser.parse_args()
    OUTPUT.mkdir(parents=True, exist_ok=True)
    TOOLS.mkdir(exist_ok=True)
    report = {"success": False, "candidateDexBuilt": False, "apkBuilt": False, "apkInstalled": False,
              "scope": "2023 Xingrui E02 AMap 9.5; public base + frozen floating delta + hints", "usesFloatingApk": False}
    try:
        config = json.loads((ROOT / "agent_docs/build-environment.local.json").read_text(encoding="utf-8-sig"))
        java = Path(config["javaHome"]) / "bin/java.exe"
        javac = Path(config["javaHome"]) / "bin/javac.exe"
        platform = Path(config["androidHome"]) / "platforms/android-34/android.jar"
        for path in (java, javac, platform, JAR, DELTA_DEX, DELTA_META):
            if not path.is_file():
                raise ValueError("缺少缓存工具或冻结增量：%s（先运行 freeze_floating_delta.py）" % path)

        meta = json.loads(DELTA_META.read_text(encoding="utf-8"))
        if common.digest(DELTA_DEX) != meta["deltaDexSha256"]:
            raise ValueError("Frozen delta DEX hash mismatch")
        analysis.verify_apk(PUBLIC_APK, analysis.PUBLIC_SHA256)
        public_dex = OUTPUT / "public.dex"
        with zipfile.ZipFile(PUBLIC_APK) as archive:
            if [n for n in archive.namelist() if n.endswith(".dex")] != ["classes.dex"]:
                raise ValueError("Unexpected DEX layout in public APK")
            public_dex.write_bytes(archive.read("classes.dex"))
        report["inputs"] = {"publicApkSha256": analysis.PUBLIC_SHA256,
                            "publicDexSha256": common.digest(public_dex),
                            "floatingDeltaDexSha256": common.digest(DELTA_DEX)}

        tools_src = ROOT / "amap95-instrument/tools"
        run([str(javac), "-encoding", "UTF-8", "-classpath", str(JAR), "-d", str(TOOLS),
             str(tools_src / "PreparePublicBaseDex.java")], report, "compile PreparePublicBaseDex.java")
        run([str(javac), "-encoding", "UTF-8", "-classpath", str(JAR), "-d", str(TOOLS),
             str(tools_src / "PrepareHintDex.java"), str(tools_src / "AuditApkDex.java")],
            report, "compile PrepareHintDex.java + AuditApkDex.java")

        # Stage 1: public + frozen floating delta.
        merged = OUTPUT / "merged-public-base.dex"
        run([str(java), "-Xmx2g", "-cp", f"{JAR};{TOOLS}", "PreparePublicBaseDex",
             str(public_dex), str(DELTA_DEX), str(merged)], report, "merge frozen floating delta onto public")
        merged_sha = common.digest(merged)
        if merged_sha != meta["expectedMergedDexSha256"]:
            raise ValueError("Merged base hash %s != frozen expected %s" % (merged_sha, meta["expectedMergedDexSha256"]))
        report["stage1"] = {"mergedDexSha256": merged_sha, "classes": MERGED_CLASSES,
                            "matchesFrozenExpected": True}

        # Stage 0: payload (base-agnostic).
        payload_dir = OUTPUT / "payload"
        if args.skip_payload_build and (payload_dir / "payload" / "classes.dex").is_file():
            payload = payload_dir / "payload" / "classes.dex"
        else:
            payload = amap95_payload.build_payload(java, javac, platform, JAR, payload_dir,
                                                   lambda argv, label: run(argv, report, label, 600))
        report["payload"] = {"sha256": common.digest(payload), "sourceSha256": amap95_payload.source_hashes()}

        # Stage 2: overlay instrument hint layer.
        candidate = OUTPUT / "candidate.dex"
        run([str(java), "-Xmx2g", "-cp", f"{JAR};{TOOLS}", "PrepareHintDex",
             str(merged), str(payload), str(candidate)], report, "overlay instrument hints")
        delta = audit(java, TOOLS, merged, candidate, OUTPUT / "candidate-vs-merged.json", report,
                      "candidate delta vs merged base")
        added = delta["addedClasses"]
        if not added or any(not (n.startswith("LPreface95") or n == PROTOCOL) for n in added):
            raise ValueError("Candidate added classes are not all Preface95 payload classes")
        if delta["removedClasses"]:
            raise ValueError("Candidate removed classes")
        if delta["classesAfter"] != MERGED_CLASSES + len(added):
            raise ValueError("Candidate class count %d != merged + payload" % delta["classesAfter"])
        changed_classes = {c["class"] for c in delta.get("changedClasses", [])}
        for required in ("La20;", "Lcom/autonavi/amapauto/jni/protocol/AndroidProtocolExe;", "Lcz;", "Loo;"):
            if required not in changed_classes:
                raise ValueError("Expected instrument hook missing on " + required)
        if delta["changedCommonMethodBodies"] < 4:
            raise ValueError("Too few instrument hook bodies: %d" % delta["changedCommonMethodBodies"])
        report["stage2"] = {"candidateDexSha256": common.digest(candidate),
                            "classCount": delta["classesAfter"],
                            "addedPreface95Classes": len(added),
                            "addedFloatingClasses": ADDED_FLOATING,
                            "changedBodies": delta["changedCommonMethodBodies"],
                            "changedClasses": sorted(changed_classes)}
        report["resourcesFromDelta"] = ["assets/res/EngineRes/bl_data/DefaultTheme/libMapAsset.so",
                                        "assets/res/HmiRes/CarLogo_Style/2/carLogo.dat",
                                        "assets/res/HmiRes/CarLogo_Style/2/carLogoLow.dat",
                                        "assets/res/HmiRes/FuncConfig.dat"]
        report["candidateDexBuilt"] = True
        report["success"] = True
        print("Public base rebuilt from clean public APK + frozen floating delta (no floating APK needed).")
        print("Candidate DEX: classes=%d (+%d floating, +%d payload). No APK built, signed or executed."
              % (delta["classesAfter"], ADDED_FLOATING, len(added)))
    except (KeyError, OSError, RuntimeError, ValueError, zipfile.BadZipFile, subprocess.SubprocessError) as error:
        report["error"] = str(error)
        print(str(error), file=sys.stderr)
    finally:
        (OUTPUT / "verification.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    return 0 if report["success"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
