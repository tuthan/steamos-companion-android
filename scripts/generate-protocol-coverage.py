#!/usr/bin/env python3
"""Regenerate the case table in docs/protocol-coverage.md from the vendored fixture index.

Run after every deliberate protocol pin update, review the diff, then run
verify-protocol-coverage.py. Prose outside the generated markers is kept as written.
"""

import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PROTOCOL = ROOT / "protocol"
INVENTORY = ROOT / "docs" / "protocol-coverage.md"
BEGIN, END = "<!-- cases:begin -->", "<!-- cases:end -->"

# Contract test class per fixture area; shared classes prefix the method with the sub-area.
TEST_CLASS = {
    "discovery": ("DiscoveryContractTest", ""),
    "pair-request": ("PairingRequestContractTest", ""),
    "pair-cancel": ("PairingCancelContractTest", ""),
    "pair-revoke-self": ("RevokeSelfContractTest", ""),
    "status": ("StatusContractTest", ""),
    "display-outputs": ("DisplayOutputsContractTest", ""),
    "display-order-get": ("DisplayOrderContractTest", "get"),
    "display-order-set": ("DisplayOrderContractTest", "set"),
    "display-order-automatic": ("DisplayOrderContractTest", "reset"),
    "display-preview": ("DisplayRecoveryContractTest", "preview"),
    "display-confirm": ("DisplayRecoveryContractTest", "confirm"),
    "display-restore": ("DisplayRecoveryContractTest", "restore"),
    "display-save-current": ("DisplayRecoveryContractTest", "saveCurrent"),
    "power": ("PowerContractTest", ""),
    "sunshine-restart": ("SunshineContractTest", ""),
    "operations": ("OperationContractTest", ""),
    "unsupported": ("RouteContractTest", ""),
}

# Body-only fixtures from the original v0.5.16 bundle.
LEGACY = [
    ("GET /v1/status", "body fixture: Sunshine disabled", 200, "fixtures/status-sunshine-disabled.json", "StatusContractTest.legacyBodySunshineDisabled"),
    ("GET /v1/status", "body fixture: Sunshine running", 200, "fixtures/status-sunshine-running.json", "StatusContractTest.legacyBodySunshineRunning"),
    ("GET /v1/status", "body fixture: Sunshine stopped", 200, "fixtures/status-sunshine-stopped.json", "StatusContractTest.legacyBodySunshineStopped"),
    ("GET /v1/status", "body fixture: Sunshine unknown", 200, "fixtures/status-sunshine-unknown.json", "StatusContractTest.legacyBodySunshineUnknown"),
    ("GET /v1/status", "body fixture: Steam unavailable", 200, "fixtures/status-steam-unavailable.json", "StatusContractTest.legacyBodySteamUnavailable"),
    ("GET /v1/display/outputs", "body fixture: working inventory", 200, "fixtures/outputs-working.json", "DisplayOutputsContractTest.legacyBodyWorking"),
]


def camel(*parts: str) -> str:
    words = [w for p in parts for w in re.split(r"[^A-Za-z0-9]+", p) if w]
    return words[0].lower() + "".join(w[:1].upper() + w[1:] for w in words[1:])


def route(area: str, method: str, path: str) -> str:
    if area == "unsupported":
        return "unsupported"
    if path.startswith("/v1/operations/"):
        path = "/v1/operations/{id}"
    return f"{method} {path}"


def main() -> None:
    index = json.loads((PROTOCOL / "fixtures/contract/index.json").read_text(encoding="utf-8"))["cases"]
    rows = []
    for case in index:
        area, name = case["case"].split("/", 1)
        test_class, prefix = TEST_CLASS[area]
        fixture = json.loads((PROTOCOL / "fixtures/contract" / case["file"]).read_text(encoding="utf-8"))
        outcome = case["error"] or "success"
        detail = fixture.get("description", "").replace("|", "\\|")
        rows.append((route(area, case["method"], case["path"]), f"{name} ({outcome}) — {detail}", case["status"],
                     f"fixtures/contract/{case['file']}", f"{test_class}.{camel(prefix, name) if prefix else camel(name)}"))
    rows += LEGACY
    rows.sort(key=lambda r: (r[0] == "unsupported", r[0], r[2], r[1]))

    table = ["| Route | Case | Status | Fixture | Kotlin contract test |", "| --- | --- | --- | --- | --- |"]
    table += [f"| `{r}` | {c} | {s} | `{f}` | `{t}` |" for r, c, s, f, t in rows]
    text = INVENTORY.read_text(encoding="utf-8")
    head, rest = text.split(BEGIN, 1)
    _, tail = rest.split(END, 1)
    INVENTORY.write_text(head + BEGIN + "\n" + "\n".join(table) + "\n" + END + tail, encoding="utf-8")
    print(f"wrote {len(rows)} case rows")


if __name__ == "__main__":
    main()
