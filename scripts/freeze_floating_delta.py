"""Freeze the reviewed floating-version deltas into a small local bundle.

One-time step: extract the 18 added classes plus the two donor classes from the
working (floating) DEX into a compact delta.dex, copy the four fixed resources,
and record metadata. After this runs successfully the large floating APK is no
longer needed: PreparePublicBaseDex reads from delta.dex and the APK builder
reads resources from this bundle. The bundle lives in the git-ignored
agent_docs directory; no third-party bytes are committed.

Run (once):
    python scripts/freeze_floating_delta.py
"""
import json
from pathlib import Path
import sys
import zipfile

import amap95_instrument_analysis as analysis
import verify_amap95_observer as common

ROOT = common.ROOT
BASE = ROOT / "agent_docs/amap-public95-instrument-20261030"
PUBLIC_APK = BASE / "amap95-public-user.apk"
WORKING_APK = analysis.HINT_BASE_APK
OUT = BASE / "floating-delta"
JAR = ROOT / "agent_docs/firmware/tools/jadx-1.5.6/lib/jadx-1.5.6-all.jar"
RESOURCES = [
    "assets/res/EngineRes/bl_data/DefaultTheme/libMapAsset.so",
    "assets/res/HmiRes/CarLogo_Style/2/carLogo.dat",
    "assets/res/HmiRes/CarLogo_Style/2/carLogoLow.dat",
    "assets/res/HmiRes/FuncConfig.dat",
]
# Verified earlier: the delta merge must reproduce this exact base DEX.
EXPECTED_MERGED = "af21d8d56e57d65df1409e955830b0f0391c96e2d249fd7ce18b3d05adcc64fe"


def run(args, report, label, timeout=180):
    result = common.execute(args, timeout)
    report.setdefault("commands", []).append({"label": label, **result})
    return common.require_success(result, label)


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    tools = OUT / "tools"
    tools.mkdir(exist_ok=True)
    report = {"success": False, "deltaFrozen": False}
    try:
        config = json.loads((ROOT / "agent_docs/build-environment.local.json").read_text(encoding="utf-8-sig"))
        java = Path(config["javaHome"]) / "bin/java.exe"
        javac = Path(config["javaHome"]) / "bin/javac.exe"
        for p in (java, javac, JAR):
            if not p.is_file():
                raise ValueError("缺少缓存工具：" + str(p))
        analysis.verify_apk(PUBLIC_APK, analysis.PUBLIC_SHA256)
        analysis.verify_apk(WORKING_APK, analysis.HINT_BASE_SHA256)

        def extract(apk, dest, sha):
            with zipfile.ZipFile(apk) as a:
                if [n for n in a.namelist() if n.endswith(".dex")] != ["classes.dex"]:
                    raise ValueError("Unexpected DEX layout in " + apk.name)
                dest.write_bytes(a.read("classes.dex"))
        public_dex, working_dex = OUT / "public.dex", OUT / "working.dex"
        extract(PUBLIC_APK, public_dex, analysis.PUBLIC_SHA256)
        extract(WORKING_APK, working_dex, analysis.HINT_BASE_SHA256)

        for tool in ("ExtractFloatingDelta.java", "PreparePublicBaseDex.java"):
            run([str(javac), "-encoding", "UTF-8", "-classpath", str(JAR), "-d", str(tools),
                 str(ROOT / "amap95-instrument/tools" / tool)], report, "compile " + tool)

        delta = OUT / "delta.dex"
        run([str(java), "-Xmx2g", "-cp", f"{JAR};{tools}", "ExtractFloatingDelta",
             str(working_dex), str(delta)], report, "extract floating delta")

        # Prove the frozen delta reproduces the same merged base DEX as before.
        check = OUT / "check-merged.dex"
        run([str(java), "-Xmx2g", "-cp", f"{JAR};{tools}", "PreparePublicBaseDex",
             str(public_dex), str(delta), str(check)], report, "verify delta reproduces merged base")
        merged_sha = common.digest(check)
        if merged_sha != EXPECTED_MERGED:
            raise ValueError("Delta merge hash %s != expected %s; refusing to freeze" % (merged_sha, EXPECTED_MERGED))

        res_dir = OUT / "resources"
        resources = {}
        with zipfile.ZipFile(WORKING_APK) as working:
            for path in RESOURCES:
                data = working.read(path)
                target = res_dir / path
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(data)
                resources[path] = {"bytes": len(data), "sha256": __import__("hashlib").sha256(data).hexdigest()}

        metadata = {
            "purpose": "Frozen reviewed floating-version deltas so the public-base rebuild no longer needs the floating APK.",
            "sourceWorkingApkSha256": analysis.HINT_BASE_SHA256,
            "deltaDexSha256": common.digest(delta),
            "deltaClasses": 20,
            "expectedMergedDexSha256": EXPECTED_MERGED,
            "resources": resources,
        }
        (OUT / "metadata.json").write_text(json.dumps(metadata, ensure_ascii=False, indent=2), encoding="utf-8")
        report.update(success=True, deltaFrozen=True, output=str(OUT.relative_to(ROOT)),
                      deltaDexSha256=metadata["deltaDexSha256"], mergedDexSha256=merged_sha,
                      resources=resources)
        print("Frozen floating delta at " + str(OUT.relative_to(ROOT)))
        print("delta.dex sha256=" + metadata["deltaDexSha256"] + "; merged reproduces " + merged_sha[:16])
    except (KeyError, OSError, RuntimeError, ValueError, zipfile.BadZipFile) as error:
        report["error"] = str(error)
        print(str(error), file=sys.stderr)
    finally:
        (OUT / "freeze-verification.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    return 0 if report["success"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
