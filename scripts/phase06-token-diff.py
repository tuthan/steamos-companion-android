#!/usr/bin/env python3
"""Diff the app's dark palette against the Decky site stylesheet (design profile deliverable 5).

The site's `site/styles.css` lives in the Decky repository, so this script reads it only when you point it at a
checkout, read-only. The build and the JVM tests never do: they use the checked-in extract this script writes
(`app/src/test/fixtures/phase06/site-tokens.json`), which `SiteTokenDiffTest` compares with `CompanionColors.dark`.

  scripts/phase06-token-diff.py                       diff against ../steamos-companion-decky/site/styles.css
  scripts/phase06-token-diff.py --styles PATH         diff against another copy of the stylesheet
  scripts/phase06-token-diff.py --write-extract       also refresh the checked-in extract from that stylesheet
  scripts/phase06-token-diff.py --require             fail instead of skipping when the stylesheet is missing

Exit status: 0 when every token matches or is a documented divergence (or the stylesheet is missing and --require is not
given), 1 on an undocumented difference, 2 when --require is given and the stylesheet cannot be read.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
TOKENS = ROOT / "app/src/main/kotlin/io/github/tuthan/steamoscompanion/android/ui/CompanionTokens.kt"
EXTRACT = ROOT / "app/src/test/fixtures/phase06/site-tokens.json"
DEFAULT_STYLES = ROOT.parent / "steamos-companion-decky/site/styles.css"
DECKY_PIN = "9c213b31cdce36e67784355be21e2fc1b2b7570f"

# Android token -> where the site defines it. `var` reads a `:root` custom property; `rule` reads a property of the
# first declaration of a selector, for the colours the site hard-codes outside `:root`.
SITE_SOURCES: dict[str, tuple[str, ...]] = {
    "bg": ("var", "--bg"),
    "bgDeep": ("var", "--bg-deep"),
    "panel": ("var", "--panel"),
    "panel2": ("var", "--panel-2"),
    "line": ("var", "--line"),
    "lineStrong": ("var", "--line-strong"),
    "text": ("var", "--text"),
    "textSoft": ("var", "--text-soft"),
    "textDim": ("var", "--text-dim"),
    "blue": ("var", "--blue"),
    "blueBright": ("var", "--blue-bright"),
    "blueDeep": ("var", "--blue-deep"),
    "green": ("var", "--green"),
    "red": ("var", "--red"),
    "amber": ("var", "--amber"),
    "field": ("rule", ".q-btn", "background"),
    "slab": ("rule", ".wire", "background"),
    "mono": ("rule", ".q-mono", "color"),
    "navy": ("rule", ".hero", "background"),
}

# Gradient stops: (Android token, site source, site stop index). The app's primary and green gradients are deliberately
# darker than the marketing ones (design profile, "Label contrast on gradients", rule R10).
GRADIENT_SOURCES = {
    "gradPrimaryStart": ("--grad-btn", 0),
    "gradPrimaryEnd": ("--grad-btn", 1),
    "gradGreenStart": ("--grad-green", 0),
    "gradGreenEnd": ("--grad-green", 1),
    "gradProgressStart": ("--grad-btn", 0),
}

# Differences the design profile documents. Anything else is a failure.
DOCUMENTED_DIVERGENCES = {
    "gradPrimaryStart": "app stop #3579AB, not the site's #67C1F5: white label needs 4.7:1 at the light stop (R10)",
    "gradPrimaryEnd": "app stop #254F6E, not the site's #417A9B (R10)",
    "gradGreenEnd": "app stop #7FA82A, not the site's #5C7E10: the dark label needs 5.4:1 at the dark stop (R10)",
}

HEX = re.compile(r"#([0-9a-fA-F]{6}|[0-9a-fA-F]{3})\b")
RGBA = re.compile(r"rgba?\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)\s*(?:,\s*([0-9.]+)\s*)?\)")


def parse_color(text: str) -> int | None:
    """Returns 0xAARRGGBB for the first colour in [text], or None."""
    rgba = RGBA.search(text)
    hexa = HEX.search(text)
    if rgba and (not hexa or rgba.start() < hexa.start()):
        red, green, blue = (int(rgba.group(i)) for i in (1, 2, 3))
        alpha = round(float(rgba.group(4)) * 255) if rgba.group(4) else 255
        return (alpha << 24) | (red << 16) | (green << 8) | blue
    if hexa:
        digits = hexa.group(1)
        if len(digits) == 3:
            digits = "".join(ch * 2 for ch in digits)
        return 0xFF000000 | int(digits, 16)
    return None


def site_colors(css: str) -> tuple[dict[str, int], dict[str, str]]:
    """Every token the app derives from the site, as 0xAARRGGBB, plus the gradient stops by token."""
    root = re.search(r":root\s*\{(.*?)\}", css, re.DOTALL)
    if not root:
        raise ValueError("no :root block")
    variables = dict(re.findall(r"(--[a-z0-9-]+)\s*:\s*([^;]+);", root.group(1)))
    found: dict[str, int] = {}
    where: dict[str, str] = {}
    for token, source in SITE_SOURCES.items():
        if source[0] == "var":
            value = variables.get(source[1])
            origin = source[1]
        else:
            block = re.search(re.escape(source[1]) + r"\s*\{([^}]*)\}", css)
            prop = re.search(r"(?<![a-z-])" + source[2] + r"\s*:\s*([^;]+);", block.group(1)) if block else None
            value = prop.group(1) if prop else None
            origin = f"{source[1]} {{ {source[2]} }}"
        color = parse_color(value) if value else None
        if color is None:
            raise ValueError(f"site colour for {token} ({origin}) not found")
        found[token] = color
        where[token] = origin
    for token, (name, index) in GRADIENT_SOURCES.items():
        stops = HEX.findall(variables.get(name, ""))
        if len(stops) <= index:
            raise ValueError(f"site gradient {name} has no stop {index}")
        found[token] = 0xFF000000 | int(stops[index], 16)
        where[token] = f"{name} stop {index}"
    return found, where


def app_dark_palette() -> dict[str, int]:
    text = TOKENS.read_text(encoding="utf-8")
    block = re.search(r"val dark = CompanionPalette\((.*?)\n    \)", text, re.DOTALL)
    if not block:
        raise ValueError("CompanionColors.dark not found")
    return {name: int(value, 16) for name, value in re.findall(r"([a-zA-Z0-9]+)\s*=\s*0x([0-9A-Fa-f]{8})", block.group(1))}


def hexa(value: int) -> str:
    return f"#{value & 0xFFFFFF:06X}" + ("" if value >> 24 == 0xFF else f"@{(value >> 24) / 255:.2f}")


def close(site: int, app: int) -> bool:
    """Equal, allowing one step of alpha rounding (the site writes .08, the app stores 0x14)."""
    return (site & 0xFFFFFF) == (app & 0xFFFFFF) and abs((site >> 24) - (app >> 24)) <= 1


def git_output(repo: Path, *args: str) -> str | None:
    try:
        done = subprocess.run(["git", "-C", str(repo), *args], capture_output=True, text=True, check=True)
        return done.stdout.strip() or None
    except (OSError, subprocess.CalledProcessError):
        return None


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--styles", type=Path, default=Path(os.environ.get("COMPANION_SITE_STYLES", DEFAULT_STYLES)))
    parser.add_argument("--write-extract", action="store_true", help="refresh the checked-in extract from --styles")
    parser.add_argument("--require", action="store_true", help="fail when the stylesheet cannot be read")
    args = parser.parse_args()

    if not args.styles.is_file():
        print(f"SKIPPED: site stylesheet not readable at {args.styles}; the JVM test still checks the checked-in extract")
        return 2 if args.require else 0
    css = args.styles.read_text(encoding="utf-8")
    site, where = site_colors(css)
    app = app_dark_palette()

    failures: list[str] = []
    print(f"{'token':<18} {'site':<16} {'app':<16} status")
    for token in list(SITE_SOURCES) + list(GRADIENT_SOURCES):
        site_value, app_value = site[token], app.get(token)
        if app_value is None:
            failures.append(f"{token}: missing from CompanionColors.dark")
            status = "MISSING IN APP"
        elif close(site_value, app_value):
            status = "match"
            if token in DOCUMENTED_DIVERGENCES:
                failures.append(f"{token}: documented as a divergence but now equals the site; drop it from the list")
                status = "STALE DIVERGENCE NOTE"
        elif token in DOCUMENTED_DIVERGENCES:
            status = "documented divergence"
        else:
            failures.append(f"{token}: site {hexa(site_value)} ({where[token]}) but app {hexa(app_value)}")
            status = "DIFFERS"
        print(f"{token:<18} {hexa(site_value):<16} {hexa(app_value) if app_value is not None else '-':<16} {status}")
    for token, reason in DOCUMENTED_DIVERGENCES.items():
        print(f"  note {token}: {reason}")

    if args.write_extract:
        repo = args.styles.resolve().parents[1]
        extract = {
            "source": "site/styles.css in the Decky repository, read-only",
            "deckyPin": DECKY_PIN,
            "stylesSha256": hashlib.sha256(args.styles.read_bytes()).hexdigest(),
            "stylesLastChangedIn": git_output(repo, "log", "-1", "--format=%H", "--", "site/styles.css"),
            "stylesIdenticalToPin": (
                git_output(repo, "show", f"{DECKY_PIN}:site/styles.css") == css.strip() if
                git_output(repo, "cat-file", "-t", DECKY_PIN) else None),
            "documentedDivergences": DOCUMENTED_DIVERGENCES,
            "tokens": {token: {"site": f"0x{site[token]:08X}", "from": where[token]} for token in
                       list(SITE_SOURCES) + list(GRADIENT_SOURCES)},
        }
        EXTRACT.parent.mkdir(parents=True, exist_ok=True)
        EXTRACT.write_text(json.dumps(extract, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
        print(f"wrote {EXTRACT.relative_to(ROOT)}")

    if failures:
        print("\n".join(failures), file=sys.stderr)
        return 1
    print("Phase 06 token diff passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
