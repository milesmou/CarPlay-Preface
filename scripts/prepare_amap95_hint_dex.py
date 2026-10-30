"""Compile and prepare a local navigation-hints candidate DEX; never build an APK.

The compile-only API declarations are not program inputs to D8. The DEX tool
checks vendor method/field references against the fixed original classes.dex.
No signing, installation, SDK initialization or ready notification occurs.
"""
import argparse
import json
from pathlib import Path
import sys
import re
import hashlib
import ssl
import uuid
import zipfile

import amap95_instrument_analysis as analysis
import verify_amap95_observer as common


ROOT = common.ROOT
SOURCE = ROOT / "amap95-instrument"
OUTPUT = ROOT / "agent_docs/amap-public95-instrument-20261030/working-hints-candidate"
STUBS = {
    "hl.java": """import android.content.Context; import android.view.MotionEvent;
import com.autonavi.amapauto.jni.ViewInfo;
public abstract class hl { public Context b; public hl(Context c) { throw new UnsupportedOperationException(); }
public void o(int x) {} public void r(ViewInfo x) {} public abstract d20 f(); public abstract void i();
public abstract void n(MotionEvent e); public abstract void t(int x); public abstract void s();
public abstract void u(); public abstract void c(); public void b(int x, boolean y) {} }
""",
    "a20.java": """import java.util.List; public class a20 { public List c;
public static a20 l(){throw new UnsupportedOperationException();}
public hl k(int x){throw new UnsupportedOperationException();} }
""",
    "d20.java": "public enum d20 { NO_MUTIL_SCREEN, SYSTEM_BUFFER, SYSTEM_PRESENTATION }\n",
    "rv.java": """import android.content.Context; public class rv {
public static rv H(){throw new UnsupportedOperationException();}
public Context z(){throw new UnsupportedOperationException();} }
""",
    "ob.java": """import android.graphics.Bitmap; public class ob {
public static int[] b(byte[] x){throw new UnsupportedOperationException();}
public static Bitmap h(int x,int y,boolean z,boolean w){throw new UnsupportedOperationException();} }
""",
    "com/autonavi/amapauto/jni/ViewInfo.java": """package com.autonavi.amapauto.jni;
public class ViewInfo { public String a,b; public int c,i,n; }
""",
    "com/autonavi/amapauto/jni/protocol/AndroidProtocolExe.java": """package com.autonavi.amapauto.jni.protocol;
public class AndroidProtocolExe { public static void sendExScreenStatus(int a,int b,int c) {} }
""",
    "com/autonavi/amapauto/jni/protocol/data/GuideInfoProtocolData.java": """package com.autonavi.amapauto.jni.protocol.data;
import android.graphics.Bitmap; public class GuideInfoProtocolData {
public int getType(){return -1;} public int getIcon(){return -1;} public int getRoundAboutNum(){return -1;}
public int getSegRemainDis(){return -1;} public String getSegRemainDisAuto(){return null;}
public String getNextRoadName(){return null;} public Bitmap getTurnIconBitmap(){return null;}
public int getOnlineIconWidth(){return 0;} public int getOnlineIconHeight(){return 0;} public byte[] getOnlineIcon(){return null;} }
""",
    "com/autonavi/extscreen/dto/PresentationConfig.java": """package com.autonavi.extscreen.dto;
public class PresentationConfig { public int deviceId; public String pluginName; }
""",
}


def run(args, report, label, timeout=45):
    result = common.execute(args, timeout)
    report.setdefault("commands", []).append({"label": label, **result})
    return common.require_success(result, label)


def art_link_test(adb, serial, report):
    if not re.fullmatch(r"emulator-[0-9]+", serial):
        raise ValueError("ART 接入核对只接受明确指定的模拟器。")
    remote = "/data/local/tmp/preface95-hint-link-" + uuid.uuid4().hex
    paths = [remote + "/candidate.dex", remote + "/link.dex"]
    created = False
    try:
        if run([adb, "-s", serial, "get-state"], report, "emulator state") != "device":
            raise ValueError("模拟器未在线")
        run([adb, "-s", serial, "shell", "mkdir", remote], report, "create isolated ART directory")
        created = True
        for source, target in zip([OUTPUT / "candidate.dex", OUTPUT / "link/classes.dex"], paths):
            run([adb, "-s", serial, "push", source, target], report, "upload ART input")
            actual = run([adb, "-s", serial, "shell", "sha256sum", target], report, "verify ART input hash").split()[0]
            if actual != common.digest(source): raise ValueError("ART 输入哈希不匹配")
        output = run([adb, "-s", serial, "shell", "CLASSPATH=" + ":".join(paths), "timeout", "25",
                      "/system/bin/app_process64", "/system/bin", "Preface95ArtLinkTest"],
                     report, "ART candidate linkage", timeout=35)
        if "ART_LINKAGE_PASSED" not in output or "NO_WINDOW_SURFACE_OR_READY_PROTOCOL_EXECUTED" not in output:
            raise ValueError("ART 输出不完整")
        return {"device": serial, "output": output, "uiExecuted": False, "protocolExecuted": False}
    finally:
        if created:
            run([adb, "-s", serial, "shell", "rm", "-f", *paths], report, "remove ART inputs")
            run([adb, "-s", serial, "shell", "rmdir", remote], report, "remove ART directory")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run-device", metavar="EMULATOR_SERIAL", help="在模拟器核对 ART 类链接，不安装 APK 或执行准备协议")
    args = parser.parse_args()
    OUTPUT.mkdir(parents=True, exist_ok=True)
    report = {"success": False, "candidateDexPrepared": False, "portReady": False,
              "androidUiExecuted": False, "actualVehicleDisplayVerified": False,
              "readyProtocolExecuted": False, "apkBuilt": False, "apkInstalled": False}
    try:
        config = json.loads((ROOT / "agent_docs/build-environment.local.json").read_text(encoding="utf-8-sig"))
        java = Path(config["javaHome"]) / "bin/java.exe"
        javac = Path(config["javaHome"]) / "bin/javac.exe"
        platform = Path(config["androidHome"]) / "platforms/android-34/android.jar"
        tools = ROOT / "agent_docs/firmware/tools/jadx-1.5.6/lib/jadx-1.5.6-all.jar"
        for path in (java, javac, platform, tools):
            if not path.is_file():
                raise ValueError(f"缺少缓存工具，请提供路径：{path}")
        apk = analysis.HINT_BASE_APK
        analysis.verify_apk(apk, analysis.HINT_BASE_SHA256)
        report["inputApkSha256"] = analysis.HINT_BASE_SHA256
        report["baseStartupAndSignatureHandlingPreserved"] = True
        with zipfile.ZipFile(apk) as archive:
            if [name for name in archive.namelist() if name.endswith(".dex")] != ["classes.dex"]:
                raise ValueError("固定输入的 DEX 条目布局变化")
            (OUTPUT / "original.dex").write_bytes(archive.read("classes.dex"))
        aapt = Path(config["androidHome"]) / "build-tools" / config["buildToolsVersion"] / "aapt2.exe"
        if not aapt.is_file(): raise ValueError("缺少缓存 AAPT2 路径")
        # Whitelist arrow resource metadata; never store the complete dump.
        dumped = common.execute([aapt, "dump", "resources", apk])
        resources = common.require_success(dumped, "arrow resource table")
        lines = resources.splitlines()
        arrows = []
        with zipfile.ZipFile(apk) as archive:
            for i, line in enumerate(lines):
                match = re.fullmatch(r"\s*resource (0x[0-9a-f]+) drawable/(sou[0-9]+_night_a530)", line)
                if not match: continue
                path_match = re.search(r"\(file\) (res/[^ ]+\.png) type=PNG", lines[i + 1])
                if not path_match: raise ValueError("转向图标未映射到固定 PNG")
                path = path_match[1]
                arrows.append({"id": match[1], "name": match[2], "path": path,
                               "sha256": hashlib.sha256(archive.read(path)).hexdigest()})
        if len(arrows) < 40: raise ValueError("预期的 9.5 转向资源集合缺失")
        report["arrowResources"] = arrows
        # The working user APK retains its own existing startup/signature path.
        # Do not layer the public-base replacement identity check over it.
        sources = sorted(p for p in (SOURCE / "java").glob("*.java")
                         if p.name not in ("Preface95PackageIdentity.java", "Preface95CertificatePin.java"))
        report["sourceSha256"] = {p.relative_to(ROOT).as_posix(): common.digest(p)
                                  for p in [*sources, *sorted((SOURCE / "tests").glob("*.java")),
                                            SOURCE / "tools/PrepareHintDex.java", Path(__file__).resolve(),
                                            ROOT / "scripts/amap95_instrument_analysis.py"]}
        stub_dir, api_dir, code_dir, test_dir, tool_dir, dex_dir, link_dir = (
            OUTPUT / name for name in ("compile-api-source", "compile-api", "program", "tests", "tools", "payload", "link"))
        for path in (stub_dir, api_dir, code_dir, test_dir, tool_dir, dex_dir, link_dir):
            path.mkdir(exist_ok=True)
        # Direct generated class files only, with checked parents; never keep
        # orphaned anonymous classes from an earlier source revision.
        for directory in (code_dir, test_dir, tool_dir):
            for path in directory.glob("*.class"):
                if path.resolve().parent != directory.resolve() or not path.name.startswith(("Preface95", "PrepareHintDex")):
                    raise ValueError("Unexpected generated class path")
                path.unlink()
        for name, text in STUBS.items():
            path = stub_dir / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(text, encoding="utf-8")
        run([javac, "--release", "8", "-encoding", "UTF-8", "-classpath", platform,
             "-d", api_dir, *(stub_dir / name for name in STUBS)], report, "compile-only API signatures")
        run([javac, "--release", "8", "-encoding", "UTF-8", "-classpath", str(platform) + ";" + str(api_dir),
             "-d", code_dir, *sources], report, "native hints Android source compilation")
        run([javac, "--release", "8", "-encoding", "UTF-8", "-d", test_dir,
             SOURCE / "java/Preface95FrameGate.java", SOURCE / "tests/Preface95FrameGateTest.java"],
            report, "frame generation test compilation")
        report["frameGateTests"] = run([java, "-cp", test_dir, "Preface95FrameGateTest"], report, "frame generation tests")
        der = ssl.PEM_cert_to_DER_cert((ROOT / "signing/platform-test-key/platform.x509.pem").read_text())
        (OUTPUT / "reviewed-certificate.der").write_bytes(der)
        run([javac, "--release", "8", "-encoding", "UTF-8", "-d", test_dir,
             SOURCE / "java/Preface95CertificatePin.java", SOURCE / "tests/Preface95CertificatePinTest.java"],
            report, "local certificate identity test compilation")
        report["certificatePinTests"] = run([java, "-cp", test_dir, "Preface95CertificatePinTest",
                                              OUTPUT / "reviewed-certificate.der"], report, "local certificate identity tests")
        program = sorted(code_dir.glob("*.class"))
        if not program or any(not p.name.startswith("Preface95") for p in program):
            raise ValueError("Program classes contain non-payload types")
        run([java, "-cp", tools, "com.android.tools.r8.D8", "--min-api", "28", "--lib", platform,
             "--classpath", api_dir, "--output", dex_dir, *program], report, "payload DEX compilation")
        run([javac, "-encoding", "UTF-8", "-classpath", tools, "-d", tool_dir,
             SOURCE / "tools/PrepareHintDex.java"], report, "DEX preparation tool compilation")
        report["hookVerification"] = run([java, "-cp", str(tools) + ";" + str(tool_dir), "PrepareHintDex",
                                           OUTPUT / "original.dex", dex_dir / "classes.dex", OUTPUT / "candidate.dex"],
                                          report, "pinned DEX reference and hook verification")
        report["artifacts"] = {name: common.digest(OUTPUT / name) for name in
                               ("original.dex", "payload/classes.dex", "candidate.dex")}
        report["candidateDexPrepared"] = True
        if args.run_device:
            run([javac, "--release", "8", "-encoding", "UTF-8", "-classpath", code_dir, "-d", test_dir,
                 SOURCE / "tests/Preface95ArtLinkTest.java"], report, "ART probe compilation")
            run([java, "-cp", tools, "com.android.tools.r8.D8", "--min-api", "28", "--lib", platform,
                 "--classpath", code_dir, "--output", link_dir, test_dir / "Preface95ArtLinkTest.class"],
                report, "ART probe DEX compilation")
            report["artLinkTest"] = art_link_test(Path(config["adbPath"]), args.run_device, report)
        report["success"] = True
        print("Android hints source, frame-lease tests and pinned DEX hooks verified.")
        print("Candidate DEX prepared locally; Android UI/protocol/vehicle execution NOT verified. No APK built.")
    except (KeyError, OSError, RuntimeError, ValueError, zipfile.BadZipFile) as error:
        report["error"] = str(error)
        print(str(error), file=sys.stderr)
    finally:
        (OUTPUT / "verification.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    return 0 if report["success"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
