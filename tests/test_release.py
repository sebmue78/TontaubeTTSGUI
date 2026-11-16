import datetime as dt

import pytest

from tools import release


def test_version_code_steigt():
    assert release.version_code("0.2.0") == 2_000
    assert release.version_code("v1.0.3") == 1_000_003
    assert release.version_code("1.10.0") > release.version_code("1.9.99")


def test_ungueltige_version():
    with pytest.raises(ValueError):
        release.parse("1.2")


def test_vorabversion():
    assert release.parse("1.0.0-beta.1") == (1, 0, 0, "beta.1")


def test_repo_version_ist_gueltig():
    release.parse(release.read_version())
    assert release.section((release.ROOT / "CHANGELOG.md").read_text(encoding="utf-8"), release.read_version())


def test_bump_und_notizen(tmp_path):
    (tmp_path / "VERSION").write_text("0.1.0\n")
    (tmp_path / "CHANGELOG.md").write_text(
        "# Änderungen\n\n## [Unveröffentlicht]\n\n- Neu: X\n\n## [0.1.0] – 2026-01-01\n\n- Alt\n"
    )
    release.bump("0.2.0", tmp_path, today=dt.date(2026, 2, 3))
    assert release.read_version(tmp_path) == "0.2.0"
    text = (tmp_path / "CHANGELOG.md").read_text()
    assert "## [Unveröffentlicht]\n\n## [0.2.0] – 2026-02-03\n\n- Neu: X" in text
    assert release.notes("0.2.0", tmp_path) == "- Neu: X"
    assert release.notes("0.1.0", tmp_path) == "- Alt"
    assert release.check("v0.2.0", tmp_path) == "0.2.0"
    with pytest.raises(ValueError):
        release.check("v0.3.0", tmp_path)
    # Erneutes bump legt keinen zweiten Abschnitt an
    release.bump("0.2.0", tmp_path)
    assert (tmp_path / "CHANGELOG.md").read_text().count("## [0.2.0]") == 1
