#!/usr/bin/env python3
"""Create a local release key once. Never commit or upload .secrets/."""
import os
from pathlib import Path
import secrets
import subprocess

root = Path(__file__).resolve().parents[1]
folder = root / '.secrets'
folder.mkdir(mode=0o700, exist_ok=True)
props = folder / 'release-signing.properties'
key = folder / 'libvio-release.jks'
if props.exists() or key.exists():
    raise SystemExit('Signing files already exist; refusing to replace a release identity.')
password = secrets.token_urlsafe(36)
env = {**os.environ, 'LIBVIO_RELEASE_KEY_PASSWORD': password}
subprocess.run(['keytool', '-genkeypair', '-keystore', str(key), '-storetype', 'JKS',
                '-alias', 'libvio-tv', '-keyalg', 'RSA', '-keysize', '3072',
                '-validity', '10000', '-dname', 'CN=LIBVIO TV Personal Release',
                '-storepass:env', 'LIBVIO_RELEASE_KEY_PASSWORD',
                '-keypass:env', 'LIBVIO_RELEASE_KEY_PASSWORD', '-noprompt'], env=env, check=True,
               stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
key.chmod(0o600)
with props.open('x') as stream:
    stream.write('storeFile=.secrets/libvio-release.jks\nkeyAlias=libvio-tv\n'
                 f'storePassword={password}\nkeyPassword={password}\n')
props.chmod(0o600)
print('Created private release signing files in .secrets/. Back up both files securely.')
