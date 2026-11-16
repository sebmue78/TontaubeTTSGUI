"""Hilfsprogramm für versionierte Releases.

Die Datei ``VERSION`` im Repository ist die einzige Quelle der Versionsnummer für die
macOS-App und die Android-App. ``CHANGELOG.md`` enthält die Release-Notizen.

    python tools/release.py bump 0.3.0     # VERSION setzen, CHANGELOG-Abschnitt anlegen
    python tools/release.py check v0.3.0   # Tag passt zu VERSION?
    python tools/release.py notes 0.3.0    # Release-Notizen dieser Version ausgeben
    python tools/release.py version-code 0.3.0   # Android-versionCode
"""

from __future__ import annotations

import argparse
import datetime as _dt
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
UNRELEASED = "## [Unveröffentlicht]"
SEMVER = re.compile(r"^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-([0-9A-Za-z.-]+))?$")


def parse(version: str) -> tuple[int, int, int, str | None]:
    m = SEMVER.match(version.strip().removeprefix("v"))
    if not m:
        raise ValueError(f"Keine gültige Version (erwartet z. B. 1.2.3 oder 1.2.3-beta.1): {version!r}")
    return int(m[1]), int(m[2]), int(m[3]), m[4]


def version_code(version: str) -> int:
    """Android-versionCode: MAJOR·1 000 000 + MINOR·1 000 + PATCH (steigt mit jeder Version)."""
    major, minor, patch, _ = parse(version)
    if minor > 999 or patch > 999:
        raise ValueError("MINOR und PATCH dürfen höchstens 999 sein")
    return major * 1_000_000 + minor * 1_000 + patch


def read_version(root: Path = ROOT) -> str:
    return (root / "VERSION").read_text(encoding="utf-8").strip()


def section(changelog: str, version: str) -> str | None:
    """Text des Abschnitts ``## [version]`` (ohne Überschrift) oder None."""
    pattern = re.compile(rf"^## \[{re.escape(version)}\][^\n]*\n(.*?)(?=^## \[|\Z)", re.M | re.S)
    m = pattern.search(changelog)
    return m[1].strip() if m else None


def bump(version: str, root: Path = ROOT, today: _dt.date | None = None) -> None:
    parse(version)
    changelog_path = root / "CHANGELOG.md"
    changelog = changelog_path.read_text(encoding="utf-8")
    if section(changelog, version) is None:
        if UNRELEASED not in changelog:
            raise ValueError(f"CHANGELOG.md enthält keinen Abschnitt {UNRELEASED!r}")
        date = (today or _dt.date.today()).isoformat()
        changelog = changelog.replace(UNRELEASED, f"{UNRELEASED}\n\n## [{version}] – {date}", 1)
        changelog_path.write_text(changelog, encoding="utf-8")
    (root / "VERSION").write_text(version + "\n", encoding="utf-8")


def check(tag: str, root: Path = ROOT) -> str:
    version = tag.removeprefix("refs/tags/").removeprefix("v")
    parse(version)
    current = read_version(root)
    if current != version:
        raise ValueError(f"Tag {tag} passt nicht zu VERSION ({current}). Erst VERSION anpassen (tools/release.py bump).")
    return version


def notes(version: str, root: Path = ROOT) -> str:
    text = section((root / "CHANGELOG.md").read_text(encoding="utf-8"), version)
    return text or "Keine Änderungen notiert."


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="cmd", required=True)
    for name in ("bump", "check", "notes", "version-code"):
        sub.add_parser(name).add_argument("version", nargs="?" if name != "bump" else None)
    args = p.parse_args(argv)
    try:
        v = args.version or read_version()
        if args.cmd == "bump":
            bump(v.removeprefix("v"))
            print(f"VERSION = {v.removeprefix('v')}")
        elif args.cmd == "check":
            print(check(v))
        elif args.cmd == "notes":
            print(notes(v.removeprefix("v")))
        else:
            print(version_code(v))
    except ValueError as e:
        print(f"Fehler: {e}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
