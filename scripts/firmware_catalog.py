"""Index an already extracted firmware cache; never execute firmware code.

Usage: python scripts/firmware_catalog.py --cache agent_docs/firmware/ihu519g-327
       --aapt2 D:/Tools/AndroidSdk/build-tools/36.0.0/aapt2.exe
Output stays in the ignored cache. XML comments are deliberately excluded.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import re
import subprocess
from pathlib import Path
import xml.etree.ElementTree as ET


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def xml_record(element):
    return {
        "tag": element.tag.split("}")[-1],
        "attributes": dict(element.attrib),
        "children": [xml_record(child) for child in element],
        **({"text": element.text.strip()} if element.text and element.text.strip() else {}),
    }


def manifest_tree(text):
    """Retain aapt2's decoded attributes and nested intent filters, not defaults."""
    stack = []
    roots = []
    for line in text.splitlines():
        match = re.match(r"(\s*)E: ([\w-]+)", line)
        if match:
            indent = len(match[1])
            while stack and stack[-1][0] >= indent:
                stack.pop()
            node = {"tag": match[2], "attributes": {}, "children": []}
            (stack[-1][1]["children"] if stack else roots).append(node)
            stack.append((indent, node))
        elif stack:
            match = re.match(r"\s*A: (.*?)=(.*)", line)
            if match:
                key = re.sub(r"\(0x[0-9a-f]+\)$", "", match[1]).rsplit(":", 1)[-1]
                raw = re.search(r'\(Raw: "(.*)"\)$', match[2])
                value = raw[1] if raw else match[2].strip('"')
                stack[-1][1]["attributes"][key] = value
    if len(roots) != 1 or roots[0]["tag"] != "manifest":
        raise ValueError("aapt2 output did not contain one manifest")
    return roots[0]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cache", required=True, type=Path)
    parser.add_argument("--aapt2", required=True, type=Path)
    args = parser.parse_args()
    cache = args.cache.resolve()
    if not cache.is_dir() or not args.aapt2.is_file():
        parser.error("cache directory and aapt2 executable must already exist")
    output = cache / "catalog"
    output.mkdir(exist_ok=True)
    errors = []

    def relative(path):
        return path.relative_to(cache).as_posix()

    def save(name, value):
        (output / name).write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    inventory = []
    for partition in ("system", "vendor"):
        for block in (cache / f"{partition}-files.txt").read_text(encoding="utf-8-sig").split("\n\n"):
            fields = dict(line.split(" = ", 1) for line in block.splitlines() if " = " in line)
            if "Path" in fields and "Mode" in fields:
                inventory.append({"partition": partition, **fields})
    save("partition-files.json", inventory)

    artifacts = []
    for directory in ("system", "vendor", "decompiled"):
        for path in sorted((cache / directory).rglob("*")):
            if path.is_file():
                artifacts.append({"path": relative(path), "bytes": path.stat().st_size, "sha256": digest(path)})
    save("extracted-files.json", artifacts)

    manifests = []
    manifest_dir = output / "manifests"
    manifest_dir.mkdir(exist_ok=True)
    for apk in sorted((cache / "system").rglob("*.apk")):
        result = subprocess.run([str(args.aapt2.resolve()), "dump", "xmltree", str(apk), "--file", "AndroidManifest.xml"], capture_output=True)
        if result.returncode:
            errors.append({"path": relative(apk), "error": result.stderr.decode("utf-8", errors="replace")})
            continue
        text = result.stdout.decode("utf-8", errors="replace")
        (manifest_dir / (apk.stem + ".txt")).write_text(text, encoding="utf-8")
        manifests.append({"apk": relative(apk), "sha256": digest(apk), "manifest": manifest_tree(text)})
    save("apk-manifests.json", manifests)

    configurations = []
    features = []
    codecs = []
    for partition in ("system", "vendor"):
        for path in sorted((cache / partition / "etc").rglob("*.xml")):
            try:
                root = ET.parse(path).getroot()
            except ET.ParseError as error:
                errors.append({"path": relative(path), "error": str(error)})
                continue
            # Facts from selected capability/routing XML only; no arbitrary assets.
            if path.name.startswith(("audio", "ecarx", "media_codecs")) or "audio_policy_configuration" in path.name:
                configurations.append({"path": relative(path), "sha256": digest(path), "xml": xml_record(root)})
            if path.parent.name == "permissions":
                for node in root.iter("feature"):
                    features.append({"evidence": relative(path), **node.attrib})
            for section in ("Decoders", "Encoders"):
                for node in root.findall(f"./{section}/MediaCodec"):
                    codecs.append({"evidence": relative(path), "direction": section, **node.attrib,
                                   "details": [xml_record(child) for child in node]})
    save("configuration-trees.json", configurations)
    save("declared-features.json", features)
    save("declared-codecs.json", codecs)

    interfaces = []
    api_constants = []
    for path in sorted((cache / "decompiled" / "adapter" / "sources").rglob("*.java")):
        text = path.read_text(encoding="utf-8")
        package = re.search(r"^package ([\w.]+);", text, re.M)
        declarations = []
        for number, line in enumerate(text.splitlines(), 1):
            value = line.strip()
            constant = re.match(r"public static final (int|long|float|double|boolean) (\w+) = ([0-9a-fA-FxX.+-]+[fFdDlL]?|true|false);$", value)
            if constant:
                api_constants.append({"class": (package[1] + "." if package else "") + path.stem,
                                      "name": constant[2], "type": constant[1], "literal": constant[3],
                                      "evidence": relative(path), "line": number})
            # Method signatures only, not vendor implementation bodies/string literals.
            if re.match(r"(?:public |protected |abstract |static |final |synchronized |native )*[\w<>\[\].,?]+ [\w$]+\([^;{}]*\)(?: throws [\w., ]+)?[;{]$", value) and not value.startswith(("return ", "new ")):
                declarations.append({"line": number, "signature": value.rstrip("{;").strip()})
        interfaces.append({"class": (package[1] + "." if package else "") + path.stem,
                           "evidence": relative(path), "sha256": digest(path),
                           "methods": declarations,
                           "decompilerWarning": "JADX WARN" in text or "Method not decompiled" in text})
    save("adapter-api-index.json", interfaces)
    save("adapter-api-constants.json", api_constants)

    init_services = []
    for partition in ("system", "vendor"):
        for path in sorted((cache / partition / "etc" / "init").rglob("*.rc")):
            for number, line in enumerate(path.read_text(encoding="utf-8", errors="replace").splitlines(), 1):
                if line.startswith("service "):
                    init_services.append({"evidence": relative(path), "line": number, "declaration": line})
    save("init-services.json", init_services)
    summary = {
        "schemaVersion": 1, "generatedAt": datetime.now(timezone.utc).isoformat(),
        "source": json.loads((cache / "source.json").read_text(encoding="utf-8-sig")),
        "scope": "Static declarations; availability and caller permissions require vehicle validation.",
        "counts": {"partitionFiles": len(inventory), "extractedFiles": len(artifacts), "apkManifests": len(manifests),
                   "configurationFiles": len(configurations), "featureDeclarations": len(features),
                   "codecDeclarations": len(codecs), "adapterClasses": len(interfaces),
                   "adapterConstantDeclarations": len(api_constants), "initServices": len(init_services)},
        "tool": {"aapt2": str(args.aapt2.resolve()), "script": "scripts/firmware_catalog.py"},
        "errors": errors,
    }
    save("index.json", summary)
    print(json.dumps({"counts": summary["counts"], "errors": errors}, ensure_ascii=False))


if __name__ == "__main__":
    main()
