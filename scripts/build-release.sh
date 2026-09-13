#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/android-env.sh
release_tag="${1:?Usage: bash scripts/build-release.sh v0.1.2}"
if [[ ! "$release_tag" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo 'Expected a release tag such as v0.1.2.' >&2
  exit 1
fi
release_dir="artifacts/releases/$release_tag"
if [ -e "$release_dir" ]; then
  echo "Release artifacts already exist: $release_dir. Refusing to overwrite them." >&2
  exit 1
fi
python3 - "$release_tag" <<'CHECK'
import re, sys
from pathlib import Path
version = re.search(r'versionName\s*=\s*"([^"]+)"', Path('app/build.gradle.kts').read_text()).group(1)
if version != sys.argv[1][1:]:
    sys.exit('Release tag does not match app versionName')
CHECK
if [ ! -f .secrets/release-signing.properties ]; then
  echo 'Missing release signing configuration. Read docs/RELEASING.md before creating or restoring a key.' >&2
  exit 1
fi
./gradlew assembleRelease testReleaseUnitTest lintRelease
for release_abi in armeabi-v7a arm64-v8a; do
  "$ANDROID_HOME/build-tools/35.0.0/apksigner" verify --verbose "app/build/outputs/apk/release/app-$release_abi-release.apk"
done
mkdir -p artifacts/releases
release_staging=$(mktemp -d artifacts/releases/.staging.XXXXXX)
trap 'rm -rf "$release_staging"' EXIT
for release_abi in armeabi-v7a arm64-v8a; do
  cp "app/build/outputs/apk/release/app-$release_abi-release.apk" "$release_staging/libvio-tv-$release_tag-$release_abi.apk"
done
python3 - "$release_staging" <<'CHECK'
from pathlib import Path
import hashlib, sys
folder = Path(sys.argv[1])
(folder / 'SHA256SUMS.txt').write_text(''.join(hashlib.sha256(apk.read_bytes()).hexdigest() + '  ' + apk.name + '\n' for apk in sorted(folder.glob('*.apk'))))
CHECK
mv "$release_staging" "$release_dir"
trap - EXIT
echo "Release artifacts: $release_dir"
