"""Reproducible "pure public" rebuild of the AMap 9.5 Xingrui instrument candidate.

The opaque third-party floating base is no longer required: the reviewed
floating deltas (18 added classes, MapApplicationProxy superclass/onCreate,
security e.c(), UsbFillActivity.onStop removal, kc.onPause/onStop removal) are
re-applied programmatically onto the clean public APK DEX, then the instrument
hint layer is overlaid. Only local DEX artifacts are produced here; this script
never builds, signs, installs or executes an APK.

Stage 1  public.dex + working.dex  -> merged base DEX (asserted semantically
         identical to the verified working DEX, 0 added/removed/changed).
Stage 2  merged base + Preface95 payload -> candidate DEX (instrument hooks).

Run:
    python scripts/prepare_amap95_public_rebuild.py
    python scripts/prepare_amap95_public_rebuild.py --skip-payload-build
"""
import argparse
import json
from pathlib import Path
import subprocess
import sys
import zipfile

import amap95_instrument_analysis as analysis
import verify_amap95_observer as common


ROOT = common.ROOT
BASE = ROOT / "agent_docs/amap-public95-instrument-20261030"
PUBLIC_APK = BASE / "amap95-public-user.apk"
WORKING_APK = analysis.HINT_BASE_APK
PUBLIC_SHA = analysis.PUBLIC_SHA256
WORKING_SHA = analysis.HINT_BASE_SHA256
PAYLOAD = BASE / "working-hints-candidate/payload/classes.dex"
PAYLOAD_VERIFICATION = BASE / "working-hints-candidate/verification.json"
OUTPUT = BASE / "public-rebuild"
TOOLS = OUTPUT / "tools"
JAR = ROOT / "agent_docs/firmware/tools/jadx-1.5.6/lib/jadx-1.5.6-all.jar"

ADDED_FLOATING = 18
ADDED_PREFACE = 20
PUBLIC_CLASSES = 7239
MERGED_CLASSES = 7257
CANDIDATE_CLASSES = 7277


def run(args, report, label, timeout=180):
    result = common.execute(args, timeout)
    report.setdefault("commands", []).append({"label": label, **result})
    return common.require_success(result, label)


def extract_dex(apk, destination):
    analysis.verify_apk(apk, PUBLIC_SHA if apk == PUBLIC_APK else WORKING_SHA)
    with zipfile.ZipFile(apk) as archive:
        dexes = [name for name in archive.namelist() if name.endswith(".dex")]
        if dexes != ["classes.dex"]:
            raise ValueError("Unexpected DEX layout in " + apk.name + ": " + str(dexes))
        destination.write_bytes(archive.read("classes.dex"))
    return destination


def audit(java, jar, tools, before, after, target, report, label):
    run([str(java), "-Xmx2g", "-cp", f"{jar};{tools}", "AuditApkDex", str(before), str(after), str(target)],
        report, label)
    return json.loads(Path(target).read_text(encoding="utf-8"))


def ensure_payload(report):
    sources = sorted(p for p in (ROOT / "amap95-instrument/java").glob("*.java")
                     if p.name not in ("Preface95PackageIdentity.java", "Preface95CertificatePin.java"))
    sources += sorted((ROOT / "amap95-instrument/protocol").glob("*.java"))
    if not PAYLOAD.is_file():
        return False, "payload missing"
    recorded = json.loads(PAYLOAD_VERIFICATION.read_text(encoding="utf-8"))
    recorded_sources = recorded.get("sourceSha256", {})
    for path in sources:
        key = path.relative_to(ROOT).as_posix()
        if key not in recorded_sources or recorded_sources[key] != common.digest(path):
            return False, "payload stale vs " + key
    if common.digest(PAYLOAD) != recorded.get("artifacts", {}).get("payload/classes.dex"):
        return False, "payload hash mismatch"
    report["payload"] = {"path": PAYLOAD.relative_to(ROOT).as_posix(), "sha256": common.digest(PAYLOAD),
                         "sourceMatchesCurrent": True}
    return True, "ok"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--skip-payload-build", action="store_true",
                        help="复用已编译且源哈希一致的 payload，不重新运行 prepare_amap95_hint_dex.py")
    args = parser.parse_args()
    OUTPUT.mkdir(parents=True, exist_ok=True)
    TOOLS.mkdir(exist_ok=True)
    report = {"success": False, "candidateDexBuilt": False, "apkBuilt": False, "apkInstalled": False,
              "scope": "2023 Xingrui E02 AMap 9.5; reproducible public base + reviewed floating deltas + hints"}
    try:
        config = json.loads((ROOT / "agent_docs/build-environment.local.json").read_text(encoding="utf-8-sig"))
        java = Path(config["javaHome"]) / "bin/java.exe"
        javac = Path(config["javaHome"]) / "bin/javac.exe"
        for path in (java, javac, JAR):
            if not path.is_file():
                raise ValueError("缺少缓存工具：" + str(path))

        # Stage 0: payload (Preface95 classes + shared protocol). Rebuild if stale.
        ok, reason = ensure_payload(report)
        if not ok and not args.skip_payload_build:
            run([sys.executable, str(ROOT / "scripts/prepare_amap95_hint_dex.py")], report,
                "rebuild Preface95 payload", timeout=600)
            ok, reason = ensure_payload(report)
        if not ok:
            raise ValueError("payload unavailable/stale (" + reason + "); run prepare_amap95_hint_dex.py")

        # Extract clean public DEX and verified working DEX.
        public_dex = extract_dex(PUBLIC_APK, OUTPUT / "public.dex")
        working_dex = extract_dex(WORKING_APK, OUTPUT / "working.dex")
        report["inputs"] = {"publicApkSha256": PUBLIC_SHA, "workingApkSha256": WORKING_SHA,
                            "publicDexSha256": common.digest(public_dex),
                            "workingDexSha256": common.digest(working_dex)}

        # Compile DEX tools from source. AuditApkDex references PrepareHintDex
        # helpers, so both must be compiled in the same javac invocation.
        tools_src = ROOT / "amap95-instrument/tools"
        run([str(javac), "-encoding", "UTF-8", "-classpath", str(JAR), "-d", str(TOOLS),
             str(tools_src / "PreparePublicBaseDex.java")], report, "compile PreparePublicBaseDex.java")
        run([str(javac), "-encoding", "UTF-8", "-classpath", str(JAR), "-d", str(TOOLS),
             str(tools_src / "PrepareHintDex.java"), str(tools_src / "AuditApkDex.java")],
            report, "compile PrepareHintDex.java + AuditApkDex.java")

        # Stage 1: public + reviewed floating deltas.
        merged = OUTPUT / "merged-public-base.dex"
        run([str(java), "-Xmx2g", "-cp", f"{JAR};{TOOLS}", "PreparePublicBaseDex",
             str(public_dex), str(working_dex), str(merged)], report, "merge floating deltas onto public")
        equality = audit(java, JAR, TOOLS, working_dex, merged, OUTPUT / "merged-vs-working.json",
                         report, "assert merged base == working")
        if (len(equality["addedClasses"]) or len(equality["removedClasses"])
                or len(equality["changedClasses"]) or equality["changedCommonMethodBodies"]):
            raise ValueError("Merged base is not semantically equal to working DEX")
        report["stage1"] = {"mergedDexSha256": common.digest(merged),
                            "semanticallyEqualToWorking": True,
                            "classes": equality["classesAfter"]}

        # Stage 2: overlay the instrument hint layer.
        candidate = OUTPUT / "candidate.dex"
        run([str(java), "-Xmx2g", "-cp", f"{JAR};{TOOLS}", "PrepareHintDex",
             str(merged), str(PAYLOAD), str(candidate)], report, "overlay instrument hints")
        delta = audit(java, JAR, TOOLS, merged, candidate, OUTPUT / "candidate-vs-merged.json",
                      report, "candidate delta vs merged base")
        added = delta["addedClasses"]
        protocol = "Lcom/preface/carplay/instrument/Preface95PreparationProtocol;"
        if not added or any(not (name.startswith("LPreface95") or name == protocol) for name in added):
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
                            "changedClasses": sorted(changed_classes),
                            "addedMethodsInCommonClasses": delta.get("addedMethodsInCommonClasses")}
        report["candidateDexBuilt"] = True
        report["resourcesToTransplant"] = {
            "note": "Packaging the public base into a bootable equivalent still needs these fixed working resources; "
                    "voice is intentionally kept at the public default per current delivery policy.",
            "fromWorking": [
                "assets/res/EngineRes/bl_data/DefaultTheme/libMapAsset.so",
                "assets/res/HmiRes/CarLogo_Style/2/carLogo.dat",
                "assets/res/HmiRes/CarLogo_Style/2/carLogoLow.dat",
                "assets/res/HmiRes/FuncConfig.dat",
            ],
            "voiceRestoredFromPublic": "assets/res/HmiRes/voice_init/tts/voices/nvzhongyin",
        }
        report["success"] = True
        print("Public base rebuilt from clean public APK; semantic equality with working verified.")
        print("Candidate DEX: classes=%d (+%d floating, +%d Preface95 payload). No APK built, signed or executed."
              % (delta["classesAfter"], ADDED_FLOATING, len(added)))
    except (KeyError, OSError, RuntimeError, ValueError, zipfile.BadZipFile, subprocess.SubprocessError) as error:
        report["error"] = str(error)
        print(str(error), file=sys.stderr)
    finally:
        (OUTPUT / "verification.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    return 0 if report["success"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
