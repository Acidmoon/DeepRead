#!/usr/bin/env python3
"""Generate a DeepRead GitHub Release update.json (schemaVersion 1.0).

This writes the manifest the app verifies at update time. It does not publish
a release or upload an APK. Official signing is the caller's responsibility.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from pathlib import Path

SCHEMA_VERSION = "1.0"
MAX_APK_BYTES = 200 * 1024 * 1024
ASSET_NAME = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._() +-]{0,191}\.apk$")
ISO = re.compile(
    r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?(?:Z|[+-]\d{2}:\d{2})$"
)


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as fh:
        for chunk in iter(lambda: fh.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def parse_aapt_badging(text: str) -> tuple[int, str]:
    code = re.search(r"versionCode='(\d+)'", text)
    name = re.search(r"versionName='([^']+)'", text)
    if not code or not name:
        raise SystemExit("无法从 aapt badging 中读取 versionCode / versionName")
    return int(code.group(1)), name.group(1)


def build_manifest(
    apk: Path,
    version_code: int,
    version_name: str,
    min_supported: int,
    notes: list[str],
    published_at: str,
    mandatory: bool,
    apk_url: str | None,
) -> dict:
    if not apk.is_file():
        raise SystemExit(f"找不到 APK：{apk}")
    size = apk.stat().st_size
    if size <= 0 or size > MAX_APK_BYTES:
        raise SystemExit(f"APK 体积不合法：{size}")
    name = apk.name
    if not ASSET_NAME.fullmatch(name):
        raise SystemExit(f"apkAssetName 非法：{name}")
    if version_code < 1 or min_supported < 1 or min_supported > version_code:
        raise SystemExit("versionCode / minSupportedVersionCode 不合法")
    if not ISO.fullmatch(published_at):
        raise SystemExit("publishedAt 必须是带时区的 ISO 8601 时间")
    if any(not n.strip() or len(n) > 500 for n in notes):
        raise SystemExit("releaseNotes 无效")
    manifest = {
        "schemaVersion": SCHEMA_VERSION,
        "versionCode": version_code,
        "versionName": version_name,
        "minSupportedVersionCode": min_supported,
        "apkAssetName": name,
        "sha256": sha256_file(apk),
        "bytes": size,
        "releaseNotes": [n.strip() for n in notes],
        "publishedAt": published_at,
        "mandatory": mandatory,
    }
    if apk_url:
        if not apk_url.startswith("https://"):
            raise SystemExit("apkUrl 仅允许 HTTPS")
        manifest["apkUrl"] = apk_url
    return manifest


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", required=True, type=Path)
    parser.add_argument("--version-code", type=int)
    parser.add_argument("--version-name")
    parser.add_argument("--from-aapt-badging", type=Path, help="aapt dump badging 的输出文件")
    parser.add_argument("--min-supported-version-code", type=int)
    parser.add_argument("--note", action="append", default=[])
    parser.add_argument("--published-at", required=True)
    parser.add_argument("--mandatory", action="store_true")
    parser.add_argument("--apk-url")
    parser.add_argument("--out", required=True, type=Path)
    args = parser.parse_args(argv)

    if args.from_aapt_badging:
        code, name = parse_aapt_badging(args.from_aapt_badging.read_text(encoding="utf-8"))
        version_code = args.version_code or code
        version_name = args.version_name or name
        if args.version_code and args.version_code != code:
            raise SystemExit("传入的 versionCode 与 APK badging 不一致")
        if args.version_name and args.version_name != name:
            raise SystemExit("传入的 versionName 与 APK badging 不一致")
    else:
        if args.version_code is None or not args.version_name:
            raise SystemExit("必须提供 --version-code/--version-name，或 --from-aapt-badging")
        version_code = args.version_code
        version_name = args.version_name

    min_supported = args.min_supported_version_code or 1
    manifest = build_manifest(
        apk=args.apk,
        version_code=version_code,
        version_name=version_name,
        min_supported=min_supported,
        notes=args.note or ["更新说明待填写"],
        published_at=args.published_at,
        mandatory=args.mandatory,
        apk_url=args.apk_url,
    )
    args.out.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"wrote {args.out} sha256={manifest['sha256']} bytes={manifest['bytes']}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
