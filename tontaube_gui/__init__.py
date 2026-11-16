"""Gradio-Oberfläche für das TTS-Modell TontaubeV1.1."""

from pathlib import Path


def _read_version() -> str:
    # In der macOS-App liegt VERSION im Paket (von build_app.sh kopiert, ggf. mit Build-Zusatz),
    # im Repository eine Ebene darüber. Die Datei VERSION im Repository ist die einzige Quelle.
    here = Path(__file__).resolve().parent
    for candidate in (here / "VERSION", here.parent / "VERSION"):
        try:
            text = candidate.read_text(encoding="utf-8").strip()
        except OSError:
            continue
        if text:
            return text
    return "0.0.0"


__version__ = _read_version()
