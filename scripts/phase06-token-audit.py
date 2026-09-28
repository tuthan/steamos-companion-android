#!/usr/bin/env python3
"""Check Phase 06 composables for literal colours and report palette-token usage."""

from __future__ import annotations

import argparse
from collections import Counter
from pathlib import Path
import re
import sys


ROOT = Path(__file__).resolve().parents[1]
UI = ROOT / "app/src/main/kotlin/io/github/tuthan/steamoscompanion/android/ui"
TOKENS = UI / "CompanionTokens.kt"
PALETTE_FIELD = re.compile(r"^\s*val\s+([a-z][A-Za-z0-9]*)\s*:\s*Long\s*,?\s*$", re.MULTILINE)
TOKEN_USE = re.compile(r"\bpalette\.([a-z][A-Za-z0-9]*)\b")
HEX_COLOUR = re.compile(r"(?i)(?<![A-Za-z0-9_])(?:0x[0-9a-f]{6,8}|#[0-9a-f]{6,8})\b")
NAMED_COLOUR = re.compile(r"\bColor\.(?:Black|White|Red|Blue|Green|Yellow|Gray|Magenta|Cyan|DarkGray|LightGray)\b")


def audit() -> tuple[list[str], Counter[str], dict[str, set[str]]]:
    declared = set(PALETTE_FIELD.findall(TOKENS.read_text(encoding="utf-8")))
    problems: list[str] = []
    counts: Counter[str] = Counter()
    files: dict[str, set[str]] = {}
    for path in sorted(UI.rglob("*.kt")):
        if path == TOKENS:
            continue
        relative = str(path.relative_to(ROOT))
        used: set[str] = set()
        for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
            if HEX_COLOUR.search(line) or NAMED_COLOUR.search(line):
                problems.append(f"{relative}:{number}: literal colour outside CompanionTokens.kt")
            for token in TOKEN_USE.findall(line):
                if token not in declared:
                    problems.append(f"{relative}:{number}: unknown palette token {token}")
                counts[token] += 1
                used.add(token)
        if used:
            files[relative] = used
    if not declared:
        problems.append("CompanionPalette has no declared colour tokens")
    return problems, counts, files


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--report", type=Path, help="write the token-usage report here")
    args = parser.parse_args()
    problems, counts, files = audit()
    lines = ["Phase 06 palette token usage", "", "Counts by token:"]
    lines.extend(f"  {token}: {count}" for token, count in sorted(counts.items()))
    lines += ["", "Tokens by UI source:"]
    lines.extend(f"  {path}: {', '.join(sorted(tokens))}" for path, tokens in files.items())
    lines += ["", f"Literal/unknown-token findings: {len(problems)}"]
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text("\n".join(lines) + "\n", encoding="utf-8")
    if problems:
        print("\n".join(problems), file=sys.stderr)
        return 1
    print(f"Phase 06 token audit passed: {len(files)} UI files, {sum(counts.values())} token references")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
