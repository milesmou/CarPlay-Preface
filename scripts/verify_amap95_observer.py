"""Compile and optionally run the original observer component's native fixtures.

No Gradle/APK task, signing, installation, SDK startup, map creation, or vehicle
protocol is performed. All generated binaries and reports stay in agent_docs.
"""
import argparse
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import uuid
import zipfile

import amap95_instrument_analysis as analysis


ROOT = Path(__file__).resolve().parents[1]
SOURCES = ROOT / "amap95-instrument/native"
OUTPUT = ROOT / "agent_docs/amap-public95-instrument-20261030/lifecycle-test"
PRODUCTION = ("MapServiceObserverBridge", "PinnedHslApi", "ObserverSession")
FLAGS = ("-std=c++17", "-fno-exceptions", "-fno-rtti", "-Wall", "-Wextra", "-Werror")


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def execute(arguments, timeout=45):
    try:
        result = subprocess.run([str(value) for value in arguments], cwd=ROOT,
                                capture_output=True, text=True, encoding="utf-8",
                                errors="replace", timeout=timeout)
    except subprocess.TimeoutExpired as error:
        raise RuntimeError(f"Timed out after {timeout}s: {Path(str(arguments[0])).name}") from error
    return {"exitCode": result.returncode, "stdout": result.stdout, "stderr": result.stderr}


def require_success(result, action):
    if result["exitCode"] != 0:
        raise RuntimeError(f"{action} failed ({result['exitCode']}): "
                           f"{result['stdout']}{result['stderr']}")
    return result["stdout"].strip()


def environment():
    path = ROOT / "agent_docs/build-environment.local.json"
    if not path.is_file():
        raise ValueError("缺少本机构建环境缓存；请提供已安装的 Android SDK／NDK 路径。")
    config = json.loads(path.read_text(encoding="utf-8-sig"))
    sdk = Path(config["androidHome"])
    ndk = sdk / "ndk" / config["ndkVersion"]
    compiler = ndk / "toolchains/llvm/prebuilt/windows-x86_64/bin/clang++.exe"
    adb = Path(config["adbPath"])
    if not compiler.is_file() or not adb.is_file():
        raise ValueError("缓存中的 NDK 编译器或 ADB 路径失效；请提供实际工具路径。")
    return compiler, adb


def compile_fixtures(compiler):
    jobs = []
    for name in PRODUCTION:
        jobs.append(([compiler, "--target=aarch64-linux-android28", *FLAGS, "-O2", "-c",
                      SOURCES / f"{name}.cpp", "-o", OUTPUT / f"{name}-arm64.o"],
                     f"{name}-arm64.o"))
    jobs.append(([compiler, "--target=x86_64-linux-android28", *FLAGS, "-O0",
                  "-DPREFACE_AMAP95_TESTING", "-static-libstdc++",
                  *(SOURCES / f"{name}.cpp" for name in (*PRODUCTION, "ObserverSessionTest")),
                  "-ldl", "-o", OUTPUT / "observer-session-unit-x86_64"],
                 "observer-session-unit-x86_64"))
    jobs.append(([compiler, "--target=x86_64-linux-android28", *FLAGS, "-O0",
                  "-static-libstdc++", SOURCES / "MapServiceObserverBridge.cpp",
                  SOURCES / "MapServiceObserverBridgeTest.cpp", "-o",
                  OUTPUT / "observer-bridge-unit-x86_64"], "observer-bridge-unit-x86_64"))
    jobs.append(([compiler, "--target=x86_64-linux-android28", *FLAGS, "-O2", "-shared",
                  "-fPIC", "-nostdlib++", SOURCES / "ForeignHslFixture.cpp",
                  "-Wl,-soname,libhsl.so", "-Wl,--build-id=sha1", "-o", OUTPUT / "libhsl.so"],
                 "libhsl.so (incompatible rejection fixture)"))

    def build(job):
        arguments, target = job
        return {"target": target, **execute(arguments)}

    with ThreadPoolExecutor(max_workers=3) as pool:
        results = list(pool.map(build, jobs))
    (OUTPUT / "compilation.json").write_text(json.dumps(results, indent=2), encoding="utf-8")
    for result in results:
        require_success(result, result["target"])
    return results


def run_on_emulator(adb, serial):
    if not serial.startswith("emulator-") or not serial[9:].isdigit():
        raise ValueError("此命令只接受明确指定的模拟器序列号；不对实车运行测试替身。")
    remote = "/data/local/tmp/preface-amap95-observer-" + uuid.uuid4().hex
    names = ("observer-bridge-unit-x86_64", "observer-session-unit-x86_64", "libhsl.so")
    commands = []

    def adb_call(*arguments, timeout=30):
        result = execute([adb, "-s", serial, *arguments], timeout)
        commands.append({"arguments": [str(value) for value in arguments], **result})
        return require_success(result, "ADB " + arguments[0])

    created = False
    try:
        if adb_call("get-state") != "device":
            raise ValueError("指定模拟器没有在线。")
        api = adb_call("shell", "getprop", "ro.build.version.sdk")
        arch = adb_call("shell", "uname", "-m")
        if arch != "x86_64":
            raise ValueError("当前原生替身可执行文件仅针对 x86_64 模拟器。")
        adb_call("shell", "mkdir", remote)
        created = True
        for name in names:
            adb_call("push", OUTPUT / name, remote + "/" + name)
            actual = adb_call("shell", "sha256sum", remote + "/" + name).split()[0]
            if actual != digest(OUTPUT / name):
                raise ValueError("模拟器上的测试输入哈希不一致。")
        adb_call("shell", "chmod", "700", *(remote + "/" + name for name in names[:2]))
        bridge = adb_call("shell", "timeout", "20", remote + "/" + names[0])
        session = adb_call("shell", "timeout", "20", remote + "/" + names[1],
                           remote + "/" + names[2])
        return {"device": serial, "androidApi": api, "architecture": arch,
                "bridgeOutput": bridge, "sessionOutput": session,
                "bridgeGroups": 6, "sessionGroups": 8,
                "vendorSdkExecuted": False, "mapCreated": False}
    finally:
        try:
            if created:
                # Fixed task directory and explicit children only; no recursive
                # deletion or application/data removal is used.
                adb_call("shell", "rm", "-f", *(remote + "/" + name for name in names))
                adb_call("shell", "rmdir", remote)
        finally:
            (OUTPUT / "run.json").write_text(json.dumps(commands, ensure_ascii=False, indent=2),
                                             encoding="utf-8")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run-device", metavar="EMULATOR_SERIAL",
                        help="运行原生替身并删除临时文件，不安装 APK")
    args = parser.parse_args()
    OUTPUT.mkdir(parents=True, exist_ok=True)
    report = {"component": "9.5 observer registration/lifetime only", "success": False,
              "portReady": False, "rendererImplemented": False, "apkChanged": False}
    try:
        # Validate the chosen APK before compiling its binary-specific adapter.
        public_apk = ROOT / "agent_docs/amap-public95-instrument-20261030/amap95-public-user.apk"
        analysis.verify_apk(public_apk, analysis.PUBLIC_SHA256)
        report["inputApkSha256"] = analysis.PUBLIC_SHA256
        with zipfile.ZipFile(public_apk) as archive:
            report["observerBindingContract"] = analysis.observer_binding_contract(
                archive.read(analysis.LIB_PREFIX + "libhsl.so"))
        compiler, adb = environment()
        report["sourceSha256"] = {str(path.relative_to(ROOT)): digest(path)
                                   for path in sorted(SOURCES.iterdir())
                                   if path.suffix in (".cpp", ".h")}
        report["compiler"] = require_success(execute([compiler, "--version"]), "NDK compiler")
        report["compilation"] = compile_fixtures(compiler)
        report["artifacts"] = {name: digest(OUTPUT / name) for name in (
            *(f"{value}-arm64.o" for value in PRODUCTION),
            "observer-bridge-unit-x86_64", "observer-session-unit-x86_64", "libhsl.so")}
        if args.run_device:
            report["nativeUnitTests"] = run_on_emulator(adb, args.run_device)
            report["temporaryDeviceFilesRemoved"] = True
        else:
            report["nativeUnitTests"] = "NOT_RUN (compile-only)"
        report["success"] = True
        print("Observer component compiled" + ("; native fixture tests passed" if args.run_device else " (tests not run)"))
        print("Instrument port incomplete: renderer/map creation/APK integration remain unimplemented.")
    except (KeyError, OSError, RuntimeError, ValueError) as error:
        report["error"] = str(error)
        print(str(error), file=sys.stderr)
    finally:
        (OUTPUT / "verification.json").write_text(json.dumps(report, ensure_ascii=False, indent=2),
                                                  encoding="utf-8")
    return 0 if report["success"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
