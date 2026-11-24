#!/usr/bin/env python3
import json
import tempfile
import unittest
from pathlib import Path

from generate_update_manifest import build_manifest, main, parse_aapt_badging, sha256_file


class GenerateUpdateManifestTest(unittest.TestCase):
    def test_builds_expected_fields(self):
        with tempfile.TemporaryDirectory() as raw:
            directory = Path(raw)
            apk = directory / "DeepRead-0.2.4.apk"
            payload = b"demo-apk"
            apk.write_bytes(payload)
            manifest = build_manifest(
                apk=apk,
                version_code=9,
                version_name="0.2.4",
                min_supported=8,
                notes=["修复"],
                published_at="2026-11-24T16:00:00+08:00",
                mandatory=False,
                apk_url=None,
            )
            self.assertEqual(manifest["schemaVersion"], "1.0")
            self.assertEqual(manifest["versionCode"], 9)
            self.assertEqual(manifest["apkAssetName"], "DeepRead-0.2.4.apk")
            self.assertEqual(manifest["bytes"], len(payload))
            self.assertEqual(manifest["sha256"], sha256_file(apk))

    def test_cli_and_aapt_mismatch(self):
        with tempfile.TemporaryDirectory() as raw:
            directory = Path(raw)
            apk = directory / "DeepRead-0.2.4.apk"
            apk.write_bytes(b"demo-apk")
            badging = directory / "badging.txt"
            badging.write_text("package: name='com.vibecoding.reader' versionCode='9' versionName='0.2.4'\n")
            self.assertEqual((9, "0.2.4"), parse_aapt_badging(badging.read_text()))
            out = directory / "update.json"
            rc = main([
                "--apk", str(apk),
                "--from-aapt-badging", str(badging),
                "--note", "测试",
                "--published-at", "2026-11-24T16:00:00+08:00",
                "--out", str(out),
            ])
            self.assertEqual(0, rc)
            data = json.loads(out.read_text())
            self.assertEqual(9, data["versionCode"])
            with self.assertRaises(SystemExit):
                main([
                    "--apk", str(apk),
                    "--from-aapt-badging", str(badging),
                    "--version-code", "8",
                    "--published-at", "2026-11-24T16:00:00+08:00",
                    "--out", str(out),
                ])


if __name__ == "__main__":
    unittest.main()
