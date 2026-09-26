#!/usr/bin/env python3
"""Verify docs/protocol-coverage.md against the vendored protocol bundle, offline.

Checks that every pinned route has at least one case row, every row names an HTTP status,
a vendored fixture that exists and is hash-pinned in SOURCE.json, and a Kotlin contract test,
and that every vendored contract fixture is claimed by some row.
"""

import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PROTOCOL = ROOT / "protocol"
INVENTORY = ROOT / "docs" / "protocol-coverage.md"
KOTLIN_TESTS = ROOT / "core" / "src" / "test" / "kotlin"
INDEX = "fixtures/contract/index.json"  # the fixture catalogue itself, not a case
STANDALONE_FIXTURES = {
    "fixtures/pairing-payload.json": ("PairingModelsTest", "hostBundledQrPayloadFixtureParsesWithTheAndroidClientValidator"),
}

# The closed route set dispatched by host/backend/service.py handle_http at the pinned commit.
PINNED_ROUTES = {
    "GET /v1/discovery",
    "POST /v1/pair/request",
    "POST /v1/pair/cancel",
    "POST /v1/pair/revoke-self",
    "GET /v1/status",
    "GET /v1/display/outputs",
    "GET /v1/display/order",
    "GET /v1/operations/{id}",
    "POST /v1/power",
    "POST /v1/display/order",
    "POST /v1/display/order/automatic",
    "POST /v1/display/preview",
    "POST /v1/display/confirm",
    "POST /v1/display/restore",
    "POST /v1/display/save-current",
    "POST /v1/sunshine/restart",
    "unsupported",
}
ROW = re.compile(r"^\|\s*`(?P<route>[^`]+)`\s*\|(?P<case>[^|]+)\|\s*(?P<status>\d{3})\s*\|(?P<fixture>[^|]+)\|\s*`(?P<test>[A-Za-z]+Test\.[A-Za-z0-9_]+)`\s*\|\s*$")


def fail(errors: list[str]) -> None:
    for error in errors:
        print(f"coverage verification failed: {error}", file=sys.stderr)
    raise SystemExit(1)


def main() -> None:
    pinned = set(json.loads((PROTOCOL / "SOURCE.json").read_text(encoding="utf-8"))["files"])
    declared_tests: set[tuple[str, str]] = set()
    test_class = re.compile(r"\bclass\s+([A-Za-z]+Test)\s*\{([\s\S]*?)^}", re.MULTILINE)
    test_method = re.compile(r"@Test\s+fun\s+([A-Za-z0-9_]+)\s*\(")
    for source in KOTLIN_TESTS.rglob("*.kt"):
        source_text = source.read_text(encoding="utf-8")
        for match in test_class.finditer(source_text):
            declared_tests.update((match.group(1), method) for method in test_method.findall(match.group(2)))
    errors: list[str] = []
    rows = []
    for number, line in enumerate(INVENTORY.read_text(encoding="utf-8").splitlines(), 1):
        if not line.startswith("| `"):
            continue
        match = ROW.match(line)
        if not match:
            errors.append(f"line {number}: malformed case row")
            continue
        rows.append((number, match))

    covered_routes = set()
    claimed = set(STANDALONE_FIXTURES)
    for fixture, (test_class_name, method_name) in STANDALONE_FIXTURES.items():
        if fixture not in pinned:
            errors.append(f"standalone fixture {fixture} is not hash-pinned in SOURCE.json")
        if not (PROTOCOL / fixture).is_file():
            errors.append(f"standalone fixture {fixture} does not exist")
        if (test_class_name, method_name) not in declared_tests:
            errors.append(f"standalone fixture {fixture} has no Kotlin test {test_class_name}.{method_name}")
    for number, row in rows:
        route = row["route"]
        if route not in PINNED_ROUTES:
            errors.append(f"line {number}: {route!r} is not a pinned route")
        test_name = row["test"]
        test_class_name, method_name = test_name.split(".", 1)
        if (test_class_name, method_name) not in declared_tests:
            errors.append(f"line {number}: Kotlin test {test_name} is not declared")
        covered_routes.add(route)
        fixtures = re.findall(r"`([^`]+)`", row["fixture"])
        if not fixtures:
            errors.append(f"line {number}: no fixture path")
        for fixture in fixtures:
            claimed.add(fixture)
            if fixture not in pinned:
                errors.append(f"line {number}: {fixture} is not hash-pinned in SOURCE.json")
            elif not (PROTOCOL / fixture).is_file():
                errors.append(f"line {number}: {fixture} does not exist")
            elif fixture.startswith("fixtures/contract/"):
                recorded = json.loads((PROTOCOL / fixture).read_text(encoding="utf-8"))["response"]["status"]
                if recorded != int(row["status"]):
                    errors.append(f"line {number}: {fixture} records {recorded}, row says {row['status']}")

    for route in sorted(PINNED_ROUTES - covered_routes):
        errors.append(f"no case row for {route}")
    for fixture in sorted(f for f in pinned if f.startswith("fixtures/") and f not in claimed and f != INDEX):
        errors.append(f"{fixture} is not claimed by any case row")

    if errors:
        fail(errors)
    print(f"coverage inventory verified: {len(rows)} cases, {len(covered_routes)} routes, {len(claimed)} fixtures, {len(declared_tests)} Kotlin tests")


if __name__ == "__main__":
    main()
