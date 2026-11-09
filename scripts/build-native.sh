#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "$0")/.." && pwd)
cd "$repo_root"
platform=${1:?Usage: build-native.sh <linux-x86_64|linux-arm64|macos-x86_64|macos-arm64>}
case "$(uname -s)/$(uname -m)" in
  Linux/x86_64) host_platform=linux-x86_64 ;;
  Linux/aarch64) host_platform=linux-arm64 ;;
  Darwin/x86_64) host_platform=macos-x86_64 ;;
  Darwin/arm64) host_platform=macos-arm64 ;;
  *) echo 'Unsupported build host' >&2; exit 1 ;;
esac
if [[ "$platform" != "$host_platform" ]]; then
  echo "This script requires a native $platform runner; host is $host_platform" >&2
  exit 1
fi
build_root="$repo_root/cpp/build/ci-$platform"
mkdir -p "$build_root"
cmake_args=(-DCMAKE_BUILD_TYPE=Release
  "-DNATIVE_INSTALL_ROOT=$build_root/resources/META-INF/native")
if [[ "$platform" == macos-* ]]; then
  export MACOSX_DEPLOYMENT_TARGET=${MACOSX_DEPLOYMENT_TARGET:-11.0}
  cmake_args+=("-DCMAKE_OSX_DEPLOYMENT_TARGET=$MACOSX_DEPLOYMENT_TARGET")
fi
if [[ -n "${LIBSODIUM_ARCHIVE:-}" ]]; then
  cmake_args+=("-DLIBSODIUM_ARCHIVE=$LIBSODIUM_ARCHIVE")
fi

cmake -S cpp -B "$build_root" "${cmake_args[@]}"
cmake --build "$build_root" --parallel 2 2>&1 | tee "$build_root/build.log"
cmake --install "$build_root"
cargo test --locked --manifest-path rust/librustzcash/Cargo.toml \
  --package librustzcash --lib --target-dir "$build_root/cargo" \
  2>&1 | tee "$build_root/rust-tests.log"

# Maven uses only this run's resources, never the checked-in native binaries.
mvn -B "-Dnative.resource.directory=$build_root/resources" clean test \
  2>&1 | tee "$build_root/java-tests.log"
python3 scripts/native_artifacts.py record "$platform" "$build_root"
