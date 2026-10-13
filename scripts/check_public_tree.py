#!/usr/bin/env python3
"""Allow the two reviewed runtime assets; reject other credential containers/private-key blocks."""
from pathlib import Path
import hashlib
import re
import subprocess

root = Path(__file__).resolve().parents[1]
names = subprocess.check_output(['git', 'ls-files', '-z', '--cached', '--others', '--exclude-standard'], cwd=root).decode().split('\0')
approved_runtime_assets = {
    'runtime-assets/offline-mfi/identity.pk8': 'bd50eda2d8dd95a8464440f1aebca7068dcab46a2461ecaf826c7f98f5621a75',
    'runtime-assets/offline-mfi/certificate.p7b': '634a93dd6c4338080524025411752597ecb828591839cd062a8292fd7e9e844e',
}
blocked_suffixes = {'.pk8', '.p7b', '.pem', '.key', '.p12', '.pfx', '.jks', '.keystore', '.apk', '.aab'}
private_block = re.compile(rb'-----BEGIN (?:[A-Z0-9 ]+ )?PRIVATE KEY-----\s+[A-Za-z0-9+/=\r\n]{40,}')
failures = []
for name in sorted(set(filter(None, names))):
    path = root / name
    if not path.is_file():
        continue
    if name in approved_runtime_assets:
        if hashlib.sha256(path.read_bytes()).hexdigest() != approved_runtime_assets[name]:
            failures.append(name + ' (does not match the reviewed upstream resource)')
        continue
    if '.private' in path.relative_to(root).parts or path.suffix.lower() in blocked_suffixes:
        failures.append(name)
    elif private_block.search(path.read_bytes()):
        failures.append(name)
for name in approved_runtime_assets:
    if not (root / name).is_file():
        failures.append(name + ' (required runtime resource missing)')
if failures:
    raise SystemExit('Credential or distribution files are forbidden in the public tree:\n' + '\n'.join(failures))
print('Public tree check passed: approved upstream runtime pair verified; no other credential containers or private-key blocks.')
