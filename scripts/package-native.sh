#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "$0")/.." && pwd)
cd "$repo_root"
artifacts=${1:?Usage: package-native.sh <downloaded-artifacts-directory>}
package_root="$repo_root/cpp/build/ci-package"
python3 scripts/native_artifacts.py assemble "$artifacts" "$package_root"
mvn -B "-Dnative.resource.directory=$package_root/resources" clean package
python3 scripts/native_artifacts.py verify-jar "$package_root" target/zksnark-java-sdk-1.0.0.jar
cp "$package_root/native-manifest.json" target/native-manifest.json
