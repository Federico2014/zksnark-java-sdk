#!/usr/bin/env python3
"""Validate and assemble the native artifacts produced by one CI run."""

import argparse
import hashlib
import json
import platform as host_platform
from pathlib import Path
import re
import shutil
import struct
import subprocess
import sys
import xml.etree.ElementTree as ET
from zipfile import ZipFile


ROOT = Path(__file__).resolve().parents[1]
LIBRARIES = {
    "linux-x86_64": "META-INF/native/linux64/libzksnarkjni.so",
    "linux-arm64": "META-INF/native/linux64/aarch64/libzksnarkjni.so",
    "macos-x86_64": "META-INF/native/osx64/libzksnarkjni.jnilib",
    "macos-arm64": "META-INF/native/osx64/aarch64/libzksnarkjni.jnilib",
}
TEST_CLASSES = {"LibrustzcashTest", "LibsodiumTest", "NativeBoundaryTest", "ProofFailureTest"}


def command(*args, cwd=ROOT):
    return subprocess.check_output(args, cwd=cwd, text=True).strip()


def digest(data):
    return hashlib.sha256(data).hexdigest()


def binary_platform(data):
    if len(data) >= 20 and data[:6] == b"\x7fELF\x02\x01":
        machine = struct.unpack_from("<H", data, 18)[0]
        return {62: "linux-x86_64", 183: "linux-arm64"}.get(machine)
    if len(data) >= 8 and data[:4] == b"\xcf\xfa\xed\xfe":
        cpu = struct.unpack_from("<I", data, 4)[0]
        return {0x01000007: "macos-x86_64", 0x0100000C: "macos-arm64"}.get(cpu)
    return None


def test_results(directory):
    results = {}
    for path in Path(directory).glob("TEST-*.xml"):
        suite = ET.parse(path).getroot()
        name = suite.attrib["name"].rsplit(".", 1)[-1]
        counts = {key: int(suite.get(key, "0")) for key in ("tests", "failures", "errors", "skipped")}
        if counts["failures"] or counts["errors"] or counts["skipped"] or not counts["tests"]:
            raise ValueError(f"Tests did not all pass: {name}: {counts}")
        results[name] = counts["tests"]
    if not TEST_CLASSES.issubset(results):
        raise ValueError("Required JNI test suites are missing")
    return results


def record(platform, build_root):
    relative = LIBRARIES[platform]
    source = build_root / "resources" / relative
    data = source.read_bytes()
    if binary_platform(data) != platform:
        raise ValueError(f"Wrong architecture for {source}")
    nm_args = ("nm", "-D", "--defined-only") if platform.startswith("linux") else ("nm", "-gU")
    symbols = {line.split()[-1].lstrip("_") for line in command(*nm_args, str(source)).splitlines() if line.split()}
    expected = set()
    for header in (build_root / "include").glob("org_tron_common_zksnark_*.h"):
        expected.update(re.findall(r"JNICALL\s+(Java_\w+)", header.read_text()))
    if not expected or not expected.issubset(symbols):
        raise ValueError(f"Missing JNI exports: {sorted(expected - symbols)}")
    for method in ("librustzcashSaplingComputeNfChecked", "librustzcashZip32XskDeriveChecked"):
        if not any(s.startswith("Java_") and s.endswith("_" + method) for s in symbols):
            raise ValueError(f"Missing checked JNI export: {method}")
    rust_repo = ROOT / "rust/librustzcash"
    dirty = subprocess.call(["git", "diff", "--quiet", "HEAD", "--ignore-submodules=none"], cwd=ROOT) != 0
    metadata = {
        "schema": 1,
        "platform": platform,
        "sdk_commit": command("git", "rev-parse", "HEAD"),
        "rust_commit": command("git", "rev-parse", "HEAD", cwd=rust_repo),
        "source_dirty": dirty,
        "path": relative,
        "sha256": digest(data),
        "rustc": command("rustc", "--version"),
        "cmake": command("cmake", "--version").splitlines()[0],
        "build_host": host_platform.platform(),
        "java_runtime": subprocess.run(["java", "-version"], check=True,
                                       capture_output=True, text=True).stderr.strip(),
        "libsodium_version": "1.0.15",
        "libsodium_archive_sha256": "fb6a9e879a2f674592e4328c5d9f79f082405ee4bb05cb6e679b90afe9e178f4",
        "java_tests": test_results(ROOT / "target/surefire-reports"),
        "jni_exports": sorted(expected),
    }
    artifact = build_root / "artifact"
    if artifact.exists():
        shutil.rmtree(artifact)
    destination = artifact / "resources" / relative
    destination.parent.mkdir(parents=True)
    shutil.copy2(source, destination)
    (artifact / "manifest.json").write_text(json.dumps(metadata, indent=2) + "\n")
    print(json.dumps(metadata, indent=2))


def validate_artifacts(directory, sdk_commit, rust_commit):
    validated = {}
    for path in sorted(directory.glob("*/manifest.json")):
        metadata = json.loads(path.read_text())
        platform = metadata.get("platform")
        if platform not in LIBRARIES or platform in validated:
            raise ValueError(f"Unexpected or duplicate platform: {platform}")
        if metadata.get("schema") != 1 or metadata.get("source_dirty") is not False:
            raise ValueError(f"Invalid manifest or uncommitted source: {platform}")
        if metadata.get("sdk_commit") != sdk_commit or metadata.get("rust_commit") != rust_commit:
            raise ValueError(f"Mixed source revisions: {platform}")
        if metadata.get("path") != LIBRARIES[platform]:
            raise ValueError(f"Unexpected library path: {platform}")
        source = path.parent / "resources" / LIBRARIES[platform]
        data = source.read_bytes()
        if source.is_symlink() or digest(data) != metadata.get("sha256") or binary_platform(data) != platform:
            raise ValueError(f"Library checksum or architecture mismatch: {platform}")
        tests = metadata.get("java_tests", {})
        if not TEST_CLASSES.issubset(tests) or not all(isinstance(n, int) and n > 0 for n in tests.values()):
            raise ValueError(f"Missing successful JNI tests: {platform}")
        validated[platform] = (metadata, source)
    if set(validated) != set(LIBRARIES):
        raise ValueError(f"Missing platforms: {sorted(set(LIBRARIES) - set(validated))}")
    dependency_versions = {(m["rustc"], m["libsodium_version"], m["libsodium_archive_sha256"]) for m, _ in validated.values()}
    if len(dependency_versions) != 1:
        raise ValueError("Mixed Rust or libsodium versions")
    return validated


def assemble(directory, destination):
    sdk_commit = command("git", "rev-parse", "HEAD")
    rust_commit = command("git", "rev-parse", "HEAD:rust/librustzcash")
    artifacts = validate_artifacts(directory, sdk_commit, rust_commit)
    # Validation finishes before writing anything into the packaging directory.
    resources = destination / "resources"
    if resources.exists():
        shutil.rmtree(resources)
    for metadata, source in artifacts.values():
        output = resources / metadata["path"]
        output.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, output)
    (destination / "native-manifest.json").write_text(json.dumps(
        [metadata for metadata, _ in artifacts.values()], indent=2) + "\n")
    print(f"Validated and assembled {len(artifacts)} platforms")


def verify_jar(directory, jar_path):
    manifests = json.loads((directory / "native-manifest.json").read_text())
    with ZipFile(jar_path) as jar:
        libraries = {n for n in jar.namelist() if n.startswith("META-INF/native/") and not n.endswith("/")}
        if libraries != set(LIBRARIES.values()):
            raise ValueError("Packaged JAR does not contain exactly the four expected libraries")
        for metadata in manifests:
            if digest(jar.read(metadata["path"])) != metadata["sha256"]:
                raise ValueError(f"Packaged JAR checksum mismatch: {metadata['platform']}")
    print(f"Verified all four native libraries in {jar_path}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    rec = commands.add_parser("record")
    rec.add_argument("platform", choices=LIBRARIES)
    rec.add_argument("build_root", type=Path)
    for name in ("assemble", "verify-jar"):
        sub = commands.add_parser(name)
        sub.add_argument("source", type=Path)
        sub.add_argument("destination", type=Path)
    args = parser.parse_args()
    if args.command == "record":
        record(args.platform, args.build_root)
    elif args.command == "assemble":
        assemble(args.source, args.destination)
    else:
        verify_jar(args.source, args.destination)


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, KeyError) as error:
        sys.exit(str(error))
