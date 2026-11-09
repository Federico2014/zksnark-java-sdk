import json
from pathlib import Path
import struct
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import native_artifacts as artifacts


class ArtifactValidationTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        for platform, relative in artifacts.LIBRARIES.items():
            header = bytearray(64)
            arm = platform.endswith("arm64")
            if platform.startswith("linux"):
                header[:6] = b"\x7fELF\x02\x01"
                struct.pack_into("<H", header, 18, 183 if arm else 62)
            else:
                header[:4] = b"\xcf\xfa\xed\xfe"
                struct.pack_into("<I", header, 4, 0x0100000C if arm else 0x01000007)
            base = self.root / ("native-" + platform)
            library = base / "resources" / relative
            library.parent.mkdir(parents=True)
            library.write_bytes(header)
            manifest = dict(schema=1, platform=platform, path=relative,
                            sdk_commit="sdk", rust_commit="rust", source_dirty=False,
                            sha256=artifacts.digest(header), rustc="1.91.1",
                            libsodium_version="1.0.15", libsodium_archive_sha256="sodium",
                            java_tests={name: 1 for name in artifacts.TEST_CLASSES})
            (base / "manifest.json").write_text(json.dumps(manifest))

    def change_manifest(self, **updates):
        path = self.root / "native-linux-arm64/manifest.json"
        value = json.loads(path.read_text())
        value.update(updates)
        path.write_text(json.dumps(value))

    def validate(self):
        return artifacts.validate_artifacts(self.root, "sdk", "rust")

    def test_accepts_complete_matching_set(self):
        self.assertEqual(set(self.validate()), set(artifacts.LIBRARIES))

    def test_rejects_missing_platform(self):
        (self.root / "native-linux-arm64/manifest.json").unlink()
        with self.assertRaisesRegex(ValueError, "Missing platforms"):
            self.validate()

    def test_rejects_mixed_revisions(self):
        for field in ("sdk_commit", "rust_commit"):
            with self.subTest(field=field):
                self.change_manifest(**{field: "different"})
                with self.assertRaisesRegex(ValueError, "Mixed source"):
                    self.validate()
                self.change_manifest(sdk_commit="sdk", rust_commit="rust")

    def test_rejects_wrong_architecture_even_with_matching_checksum(self):
        path = self.root / "native-linux-arm64/resources" / artifacts.LIBRARIES["linux-arm64"]
        data = bytearray(path.read_bytes())
        struct.pack_into("<H", data, 18, 62)
        path.write_bytes(data)
        self.change_manifest(sha256=artifacts.digest(data))
        with self.assertRaisesRegex(ValueError, "architecture mismatch"):
            self.validate()

    def test_rejects_changed_library(self):
        self.change_manifest(sha256="incorrect")
        with self.assertRaisesRegex(ValueError, "checksum"):
            self.validate()

    def test_rejects_dirty_source_or_missing_tests(self):
        self.change_manifest(source_dirty=True)
        with self.assertRaisesRegex(ValueError, "uncommitted source"):
            self.validate()
        self.change_manifest(source_dirty=False, java_tests={})
        with self.assertRaisesRegex(ValueError, "JNI tests"):
            self.validate()

    def test_rejects_unexpected_path(self):
        self.change_manifest(path="../../elsewhere")
        with self.assertRaisesRegex(ValueError, "Unexpected library path"):
            self.validate()


if __name__ == "__main__":
    unittest.main()
