#!/usr/bin/env python3
"""Capture an installed APK without changing display settings or app preferences."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import struct
import subprocess
import sys
from datetime import datetime, timezone
import uuid
import xml.etree.ElementTree as ET


def capture(args):
    def adb(*command):
        return subprocess.run(
            [args.adb, "-s", args.serial, *command], check=True,
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=30,
        ).stdout

    def shell(*command):
        return adb("shell", *command).decode("utf-8", errors="replace").strip()

    def require_page(tree):
        nodes = list(tree.iter("node"))
        app_nodes = [node for node in nodes if node.get("package") == args.package]
        if not app_nodes:
            raise ValueError("The requested app is not visible; no preview saved")
        for text in args.require_text:
            if not any(node.get("text", "").startswith(text) for node in app_nodes):
                raise ValueError(f"Expected visible text missing: {text}; no preview saved")
        return [(node.get("text"), node.get("content-desc"), node.get("bounds")) for node in app_nodes]

    def focus():
        window = shell("dumpsys", "window", "displays")
        line = next((line.strip() for line in window.splitlines() if "mCurrentFocus=" in line), "")
        if not re.search(r"\s" + re.escape(args.package) + r"/", line):
            raise ValueError("The requested app does not own the focused window; no preview saved")
        return line

    def tree():
        shell("uiautomator", "dump", remote)
        return adb("exec-out", "cat", remote)

    args.output.parent.mkdir(parents=True, exist_ok=True)
    if args.output.suffix.lower() != ".png":
        raise ValueError("Output must be a PNG file")
    metadata_path = args.output.with_suffix(".json")
    hierarchy_path = args.output.with_suffix(".xml")
    if any(path.exists() for path in (args.output, metadata_path, hierarchy_path)):
        raise ValueError("Output already exists; choose a new filename to avoid confusing old and new captures")
    state = adb("get-state").decode().strip()
    if state != "device":
        raise ValueError(f"Device is not installable/online: {state}")

    remote = f"/sdcard/diplay-preview-{uuid.uuid4().hex}.xml"
    try:
        focused = focus()
        before = require_page(ET.fromstring(tree()))
        png = adb("exec-out", "screencap", "-p")
        if png[:8] != b"\x89PNG\r\n\x1a\n":
            raise ValueError("Device did not return a PNG screenshot")
        width, height = struct.unpack(">II", png[16:24])
        if (width, height) != (1920, 720):
            raise ValueError(f"Expected 1920x720 landscape; received {width}x{height}. Display settings were not changed")
        after_xml = tree()
        if before != require_page(ET.fromstring(after_xml)) or focused != focus():
            raise ValueError("Page changed while capturing; retry on a stable screen")

        package = shell("dumpsys", "package", args.package)
        activity = shell("dumpsys", "activity", "top")
        section = next((part for part in activity.split("  ACTIVITY ")
                        if part.startswith(args.package + "/")), "")
        configuration = next((line.strip() for line in section.splitlines() if "mCurrentConfig=" in line), "")
        resource_configuration = next((line.strip() for line in section.splitlines()
                                       if "mLastConfigurationFromResources=" in line), "unavailable")
        if not configuration:
            raise ValueError("Cannot record the running activity configuration; no preview saved")
        preferences = {}
        for filename, keys in {
            "diplay": ["interface_size", "settings_force_full_layout"],
            "xcertplay_airplay": ["hide_top_bar", "hide_bottom_bar"],
        }.items():
            try:
                prefs = ET.fromstring(shell("run-as", args.package, "cat", f"shared_prefs/{filename}.xml"))
                preferences[filename] = {node.get("name"): node.get("value", node.text)
                                         for node in prefs if node.get("name") in keys}
            except (subprocess.CalledProcessError, ET.ParseError):
                preferences[filename] = "unavailable (run-as requires a debuggable APK)"

        metadata = {
            "source": "installed-apk-adb-screencap",
            "capturedAtUtc": datetime.now(timezone.utc).isoformat(),
            "serial": args.serial,
            "package": args.package,
            "version": [line.strip() for line in package.splitlines()
                        if re.match(r"\s*version(?:Code|Name)=", line)],
            "androidApi": shell("getprop", "ro.build.version.sdk"),
            "physicalAndOverrideSize": shell("wm", "size"),
            "physicalAndOverrideDensity": shell("wm", "density"),
            "systemFontScaleSetting": shell("settings", "get", "system", "font_scale"),
            "frameworkActivityConfiguration": configuration,
            "windowResourceConfiguration": resource_configuration,
            "focusedWindow": focused,
            "appDisplayPreferences": preferences,
            "requiredVisibleText": args.require_text,
            "pixels": [width, height],
            "pngSha256": hashlib.sha256(png).hexdigest(),
            "uiHierarchy": hierarchy_path.name,
        }
        # Save only after validating both screen snapshots and all required metadata.
        hierarchy_path.write_bytes(after_xml)
        metadata_path.write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        args.output.write_bytes(png)
        print(str(args.output))
        print(str(metadata_path))
    finally:
        try:
            adb("shell", "rm", remote)
        except (subprocess.SubprocessError, OSError):
            pass


def main():
    root = Path(__file__).resolve().parents[1]
    cache = root / "agent_docs/build-environment.local.json"
    environment = json.loads(cache.read_text(encoding="utf-8-sig")) if cache.exists() else {}
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default=environment.get("adbPath") or "adb")
    parser.add_argument("--serial", required=True)
    parser.add_argument("--package", default="com.preface.carplay")
    parser.add_argument("--require-text", action="append", required=True,
                        help="Visible text prefix identifying the expected page; repeat as needed")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        capture(args)
    except (ValueError, OSError, subprocess.SubprocessError, ET.ParseError) as error:
        print(f"Capture failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
