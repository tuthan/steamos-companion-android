#!/usr/bin/env python3
"""Verify the checked-in host protocol bundle against its source manifest."""

import hashlib
import json
import sys
from pathlib import Path, PurePosixPath

ROOT = Path(__file__).resolve().parents[1]
PROTOCOL = ROOT / "protocol"
MANIFEST = PROTOCOL / "SOURCE.json"


def fail(message: str) -> None:
    print(f"protocol verification failed: {message}", file=sys.stderr)
    raise SystemExit(1)


try:
    manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
except (OSError, json.JSONDecodeError) as exc:
    fail(f"cannot read {MANIFEST.relative_to(ROOT)}: {exc}")

expected = manifest.get("files")
if not isinstance(expected, dict) or not expected:
    fail("SOURCE.json files must be a non-empty path-to-SHA-256 object")

actual_paths = {
    path.relative_to(PROTOCOL).as_posix()
    for path in PROTOCOL.rglob("*")
    if path.is_file() and path != MANIFEST
}
if actual_paths != set(expected):
    missing = sorted(set(expected) - actual_paths)
    extra = sorted(actual_paths - set(expected))
    fail(f"file set differs; missing={missing}, extra={extra}")

for relative, expected_hash in sorted(expected.items()):
    posix_path = PurePosixPath(relative)
    if posix_path.is_absolute() or ".." in posix_path.parts:
        fail(f"unsafe path in SOURCE.json: {relative!r}")
    path = PROTOCOL.joinpath(*posix_path.parts)
    digest = hashlib.sha256(path.read_bytes()).hexdigest()
    if digest != expected_hash:
        fail(f"SHA-256 mismatch for protocol/{relative}: {digest} != {expected_hash}")

print(f"protocol bundle verified: {len(expected)} files")
