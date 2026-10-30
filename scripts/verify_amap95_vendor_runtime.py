"""Compile, then optionally load the pinned real 9.5 libraries in a native probe.

No APK, JNI_OnLoad, application initialization, map creation or ready message.
Only explicitly selected emulators are supported. Generated inputs stay local.
"""
import argparse
import json
from pathlib import Path
import re
import sys
import uuid
import zipfile

import amap95_instrument_analysis as analysis
import verify_amap95_observer as common


ROOT = common.ROOT
OUTPUT = ROOT / "agent_docs/amap-public95-instrument-20261030/vendor-runtime-test"
SOURCE = common.SOURCES / "VendorRuntimeProbe.cpp"
TARGET = "vendor-runtime-probe-arm64"


def extract_closure(archive, root_name):
    pending, found = [root_name], {}
    while pending:
        name = pending.pop()
        if name in found:
            continue
        if re.fullmatch(r"lib[A-Za-z0-9_+.-]+\.so", name) is None:
            raise ValueError("Unsafe ELF dependency name")
        data = archive.read(analysis.LIB_PREFIX + name)
        module = analysis.NativeModule.parse(data)
        path = OUTPUT / name
        path.write_bytes(data)
        found[name] = {"sha256": module.digest, "bytes": len(data),
                       "buildIds": module.build_ids}
        pending.extend(module.needed - analysis.SYSTEM_LIBRARIES)
    return found


def run_probes(adb, serial, closure):
    if not re.fullmatch(r"emulator-[0-9]+", serial):
        raise ValueError("真实库探针目前仅支持明确指定的模拟器，不在实车上加载额外 SDK。")
    commands = []

    def call(*arguments, timeout=35):
        result = common.execute([adb, "-s", serial, *arguments], timeout)
        commands.append({"arguments": [str(value) for value in arguments], **result})
        return common.require_success(result, "ADB " + arguments[0])

    remote = "/data/local/tmp/preface-amap95-vendor-" + uuid.uuid4().hex
    uploaded, created = [], False
    results = []
    try:
        if call("get-state") != "device":
            raise ValueError("指定模拟器未在线。")
        api = int(call("shell", "getprop", "ro.build.version.sdk"))
        if api < 28:
            raise ValueError("本探针最低为 API 28。")
        arch = call("shell", "uname", "-m")
        if arch == "aarch64":
            runner = []
        elif arch == "x86_64":
            call("shell", "test", "-x", "/system/bin/houdini64")
            runner = ["/system/bin/houdini64"]
        else:
            raise ValueError("当前模拟器没有已支持的 ARM64 执行路径。")
        call("shell", "mkdir", remote)
        created = True
        for name in [TARGET, *sorted(closure)]:
            destination = remote + "/" + name
            uploaded.append(destination)
            call("push", OUTPUT / name, destination, timeout=45)
            uploaded_hash = call("shell", "sha256sum", destination).split()[0]
            if uploaded_hash != common.digest(OUTPUT / name):
                raise ValueError("模拟器探针或厂商库哈希不一致。")
        call("shell", "chmod", "700", remote + "/" + TARGET)
        for mode in ("hsl", "tbt"):
            # Fixed library basenames and generated alphanumeric remote path;
            # no user text is interpreted by the remote shell.
            program = remote + "/" + TARGET
            # This Houdini CLI consumes the ELF path without forwarding it as
            # argv[0]. Supply argv[0] explicitly; native ARM keeps normal argv.
            launch = [*runner, program, program] if runner else [program]
            args = ["shell", "LD_LIBRARY_PATH=" + remote, "timeout", "25",
                    *launch, mode]
            result = common.execute([adb, "-s", serial, *args], timeout=35)
            commands.append({"arguments": args, **result})
            results.append({"probe": mode, **result})
        for result in results:
            output = common.require_success(result, "Vendor " + result["probe"])
            required = {"BIND_RESULT: HSL_EXPORTS_BOUND", "BINDING_WRAPPER_RELEASED",
                        "NO_ENGINE_INITIALIZATION_OR_RENDER_EXECUTED"}
            required.add("VENDOR_HSL_LOADED" if result["probe"] == "hsl" else
                         "TBT_ENTRYPOINTS_RESOLVED_NOT_INVOKED")
            if not required.issubset(set(output.splitlines())):
                raise ValueError("探针输出不完整，不能记为真实库绑定通过。")
            if not {"VENDOR_MAP_SERVICE_ABSENT", "VENDOR_MAP_SERVICE_PRESENT_NOT_USED"}.intersection(
                    output.splitlines()):
                raise ValueError("没有真实地图服务 getter 的执行结果。")
        return {"device": serial, "androidApi": api, "kernelArchitecture": arch,
                "runner": runner or ["native-arm64"], "probes": results,
                "realVendorLibrariesLoaded": True, "matchedHslBindingExecuted": True,
                "matchedHslServiceGetterExecuted": True,
                "tbtStartupInvoked": False, "sdkTeardownTested": False,
                "processExitPolicy": "_exit after releasing the binding wrapper"}
    finally:
        try:
            if created:
                # Only known direct children in a newly created task directory.
                if uploaded:
                    call("shell", "rm", "-f", *uploaded)
                call("shell", "rmdir", remote)
        finally:
            (OUTPUT / "run.json").write_text(json.dumps(commands, ensure_ascii=False, indent=2),
                                            encoding="utf-8")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run-device", metavar="EMULATOR_SERIAL")
    args = parser.parse_args()
    OUTPUT.mkdir(parents=True, exist_ok=True)
    report = {"success": False, "portReady": False, "mapCreated": False,
              "rendererImplemented": False, "apkChanged": False,
              "matchedHslBindingExecuted": False, "sdkInitialized": False,
              "readyPublished": False}
    try:
        apk = ROOT / "agent_docs/amap-public95-instrument-20261030/amap95-public-user.apk"
        analysis.verify_apk(apk, analysis.PUBLIC_SHA256)
        report["inputApkSha256"] = analysis.PUBLIC_SHA256
        with zipfile.ZipFile(apk) as archive:
            report["closure"] = extract_closure(archive, "libautonavi.so")
            report["bindingContract"] = analysis.observer_binding_contract(
                archive.read(analysis.LIB_PREFIX + "libhsl.so"))
        compiler, adb = common.environment()
        report["sourceSha256"] = {str(path.relative_to(ROOT)): common.digest(path)
                                  for path in (SOURCE, common.SOURCES / "PinnedHslApi.cpp",
                                               common.SOURCES / "PinnedHslApi.h",
                                               Path(__file__).resolve())}
        result = common.execute([compiler, "--target=aarch64-linux-android28", *common.FLAGS,
                                 "-O2", "-static-libstdc++", SOURCE,
                                 common.SOURCES / "PinnedHslApi.cpp", "-ldl", "-o", OUTPUT / TARGET])
        report["compilation"] = result
        common.require_success(result, "ARM64 vendor probe compilation")
        report["probeSha256"] = common.digest(OUTPUT / TARGET)
        if args.run_device:
            report["runtime"] = run_probes(adb, args.run_device, report["closure"])
            report["matchedHslBindingExecuted"] = True
            report["temporaryDeviceFilesRemoved"] = True
        else:
            report["runtime"] = "NOT_RUN (compile-only)"
        report["success"] = True
        print("Real vendor loading/binding probes passed." if args.run_device else
              "Vendor probe compiled; no vendor code executed.")
        print("Instrument map/startup/render/ready/APK tests remain unimplemented.")
    except (KeyError, OSError, RuntimeError, ValueError, zipfile.BadZipFile) as error:
        report["error"] = str(error)
        print(str(error), file=sys.stderr)
    finally:
        (OUTPUT / "verification.json").write_text(json.dumps(report, ensure_ascii=False, indent=2),
                                                 encoding="utf-8")
    return 0 if report["success"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
