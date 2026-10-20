#!/usr/bin/env python3
"""Import the user-selected QQ media license into ignored local build inputs."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_DIRECTORY = ROOT / 'agent_docs/runtime-ecarx-media'
SOURCE_SHA256 = 'e67a0e548fc9ca0e54f0a75cd5b1bcbaaa207f4f935e25b9898805e69e38ce9c'
LICENSE_SHA256 = '4290f880caa28d5535897c9a365c13a7394b93ae06ca9bec919918b90b0dbd98'
METADATA_SHA256 = '175ef703f749677edc67d130a45bc234493afaecc11eb566cd3a3a17d33dbf1f'
METADATA_NAMES = ('eCarX_OpenAPI_AppId', 'eCarX_OpenAPI_AppKey')


def digest(data):
    return hashlib.sha256(data).hexdigest()


def metadata_bytes(values):
    if not isinstance(values, dict) or set(values) != set(METADATA_NAMES):
        raise ValueError('Expected exactly the two ECARX media metadata entries')
    if not all(isinstance(value, str) and re.fullmatch(r'[A-Za-z0-9._-]{1,256}', value)
               for value in values.values()):
        raise ValueError('Invalid ECARX media metadata format')
    return json.dumps(values, sort_keys=True, separators=(',', ':'), ensure_ascii=True).encode('utf-8')


def manifest_values(xmltree):
    values = {}
    current = None
    for line in xmltree.splitlines():
        if line.lstrip().startswith('E:'):
            current = None
        name = re.search(r':name(?:\([^)]*\))?="([^"]*)"', line)
        if name:
            current = name[1]
        value = re.search(r':value(?:\([^)]*\))?="([^"]*)"', line)
        if current in METADATA_NAMES and value:
            if current in values:
                raise ValueError('Duplicate ECARX media metadata entry')
            values[current] = value[1]
    metadata_bytes(values)
    return values


def write_changed(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    if not path.is_file() or path.read_bytes() != data:
        path.write_bytes(data)


def import_inputs(apk, aapt2, directory):
    if digest(apk.read_bytes()) != SOURCE_SHA256:
        raise ValueError('QQ source APK does not match the user-selected reference')
    with zipfile.ZipFile(apk) as archive:
        license_data = archive.read('assets/license.txt')
    if digest(license_data) != LICENSE_SHA256:
        raise ValueError('QQ media license does not match the reference')
    result = subprocess.run([str(aapt2), 'dump', 'xmltree', str(apk), '--file', 'AndroidManifest.xml'],
                            capture_output=True, timeout=30)
    if result.returncode:
        # Tool output may contain metadata values; never forward it to logs.
        raise ValueError('Could not read the source APK manifest')
    values = manifest_values(result.stdout.decode('utf-8'))
    encoded = metadata_bytes(values)
    if digest(encoded) != METADATA_SHA256:
        raise ValueError('QQ media metadata does not match the reference')
    provenance = {
        'schemaVersion': 1,
        'sourceApkSha256': SOURCE_SHA256,
        'licenseSha256': LICENSE_SHA256,
        'licenseBytes': len(license_data),
        'metadataSha256': METADATA_SHA256,
        'preserveApplicationPackageAndSigning': True,
        'admissionConfirmed': False,
    }
    write_changed(directory / 'assets/license.txt', license_data)
    write_changed(directory / 'manifest-values.json', encoded)
    write_changed(directory / 'provenance.json',
                  (json.dumps(provenance, indent=2) + '\n').encode('utf-8'))
    verify_inputs(directory)


def verify_inputs(directory):
    try:
        license_data = (directory / 'assets/license.txt').read_bytes()
        values = json.loads((directory / 'manifest-values.json').read_text(encoding='utf-8'))
        provenance = json.loads((directory / 'provenance.json').read_text(encoding='utf-8'))
    except (OSError, ValueError):
        raise ValueError('Local ECARX media inputs missing or unreadable; import the selected QQ APK first') from None
    if {p.relative_to(directory / 'assets').as_posix() for p in (directory / 'assets').rglob('*')
            if p.is_file()} != {'license.txt'}:
        raise ValueError('Unexpected files in the local ECARX media assets directory')
    if digest(license_data) != LICENSE_SHA256 or digest(metadata_bytes(values)) != METADATA_SHA256:
        raise ValueError('Local ECARX media inputs changed from the selected QQ reference')
    if (provenance.get('schemaVersion') != 1 or provenance.get('sourceApkSha256') != SOURCE_SHA256 or
            provenance.get('licenseSha256') != LICENSE_SHA256 or
            provenance.get('metadataSha256') != METADATA_SHA256 or
            provenance.get('licenseBytes') != len(license_data) or
            provenance.get('preserveApplicationPackageAndSigning') is not True or
            provenance.get('admissionConfirmed') is not False):
        raise ValueError('Local ECARX media provenance is inconsistent')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument('--import-source', action='store_true')
    mode.add_argument('--verify', action='store_true')
    parser.add_argument('--apk', type=Path)
    parser.add_argument('--aapt2', type=Path)
    parser.add_argument('--directory', type=Path, default=DEFAULT_DIRECTORY)
    args = parser.parse_args()
    try:
        if args.import_source:
            if args.apk is None or args.aapt2 is None:
                parser.error('--import-source requires --apk and --aapt2')
            import_inputs(args.apk, args.aapt2, args.directory)
        else:
            verify_inputs(args.directory)
    except (OSError, ValueError, zipfile.BadZipFile, KeyError, subprocess.SubprocessError):
        # Source/JSON/tool exceptions can include input contents. Print only a fixed message.
        parser.exit(1, 'ECARX media input verification failed; check local files and source hashes.\n')
    print('QQ media build inputs verified; OEM admission unconfirmed.')


if __name__ == '__main__':
    main()
