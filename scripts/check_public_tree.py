#!/usr/bin/env python3
"""Allow reviewed runtime/public signing material; reject other credential containers/private-key blocks."""
from pathlib import Path
import hashlib
import re
import subprocess

root = Path(__file__).resolve().parents[1]
names = subprocess.check_output(['git', 'ls-files', '-z', '--cached', '--others', '--exclude-standard'], cwd=root).decode().split('\0')
approved_material = {
    'runtime-assets/offline-mfi/identity.pk8': 'bd50eda2d8dd95a8464440f1aebca7068dcab46a2461ecaf826c7f98f5621a75',
    'runtime-assets/offline-mfi/certificate.p7b': '634a93dd6c4338080524025411752597ecb828591839cd062a8292fd7e9e844e',
    'signing/carplay-release.p12': '75fc1dda3d980e48ab47594ca936b3cf808f6086b62f49e8e38d89c3009efd90',
    'signing/signing.json': 'b68738554f5b39b5f6da645116e7a43263087ba1ec28c9d79f18c643f5e39c13',
}
blocked_suffixes = {'.pk8', '.p7b', '.pem', '.key', '.p12', '.pfx', '.jks', '.keystore', '.apk', '.aab'}
private_block = re.compile(rb'-----BEGIN (?:[A-Z0-9 ]+ )?PRIVATE KEY-----\s+[A-Za-z0-9+/=\r\n]{40,}')
failures = []
for name in sorted(set(filter(None, names))):
    path = root / name
    if not path.is_file():
        continue
    if name in approved_material:
        if hashlib.sha256(path.read_bytes()).hexdigest() != approved_material[name]:
            failures.append(name + ' (does not match the approved published material)')
        continue
    if '.private' in path.relative_to(root).parts or path.suffix.lower() in blocked_suffixes:
        failures.append(name)
    elif private_block.search(path.read_bytes()):
        failures.append(name)
for name in approved_material:
    if not (root / name).is_file():
        failures.append(name + ' (required approved material missing)')
if failures:
    raise SystemExit('Credential or distribution files are forbidden in the public tree:\n' + '\n'.join(failures))
print('Public tree check passed: reviewed runtime and owner-authorized public APK signing material verified; other credential containers remain excluded.')
