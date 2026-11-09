#!/usr/bin/env bash
set -euo pipefail

if [[ "${GITHUB_ACTIONS:-}" != true ]]; then
  echo 'This installer is intended for disposable GitHub Actions runners.' >&2
  exit 1
fi
case "$(uname -s)" in
  Linux)
    sudo apt-get update
    sudo apt-get install -y build-essential cmake curl python3 pkg-config
    ;;
  Darwin)
    if ! command -v cmake >/dev/null; then brew install cmake; fi
    ;;
  *) exit 1 ;;
esac

rustup toolchain install 1.91.1 --profile minimal
# Maven 3.9 still runs on JDK 8; avoid a future runner upgrade to Maven 4.
maven_version=3.9.12
tools_dir="$RUNNER_TEMP/zksnark-tools"
mkdir -p "$tools_dir"
archive="apache-maven-$maven_version-bin.tar.gz"
url="https://archive.apache.org/dist/maven/maven-3/$maven_version/binaries/$archive"
curl --fail --location --retry 3 "$url" -o "$tools_dir/$archive"
curl --fail --location --retry 3 "$url.sha512" -o "$tools_dir/$archive.sha512"
python3 - "$tools_dir/$archive" <<'PY'
import hashlib
import pathlib
import sys
archive = pathlib.Path(sys.argv[1])
expected = pathlib.Path(str(archive) + '.sha512').read_text().split()[0]
if hashlib.sha512(archive.read_bytes()).hexdigest() != expected:
    raise SystemExit('Maven archive checksum mismatch')
PY
tar -xzf "$tools_dir/$archive" -C "$tools_dir"
echo "$tools_dir/apache-maven-$maven_version/bin" >> "$GITHUB_PATH"
