"""Referenzaudio fürs Stimmklonen aufbereiten.

Alle drei Modelle nutzen für die Klangfarbe nur den Anfang der Referenz:

* Tontaube: CB0 (Rhythmus/Betonung) bis 60 s, die Klangfarben-Codebücher nur die ersten 24/12/8 s.
* Chatterbox: 10 s für den Decoder, 6 s (Turbo 15 s) für den Stil-Encoder.
* Qwen3 (Base): ohne Transkript nur ein Sprecher-Vektor; mit Transkript die Audiocodes (Gateway kappt bei 20 s).

Stille, Brummen oder lange Pausen am Anfang kosten deshalb direkt Ähnlichkeit. ``prepare_reference``
schneidet Stille an den Rändern ab, kürzt lange Pausen, filtert tiefes Brummen, entrauscht auf Wunsch leicht
und kürzt auf die Länge, die das Modell tatsächlich auswertet. Ergebnis: WAV, 24 kHz, mono, 16 bit.
"""

from __future__ import annotations

import os
import shutil
import subprocess
from dataclasses import dataclass

from .client import wav_duration

#: Länge, die das jeweilige Modell (höchstens) auswertet, in Sekunden.
MODEL_REF_SECONDS = {
    "tontaube": 60.0,
    "chatterbox": 10.0,
    "chatterbox-turbo": 15.0,
    "qwen3": 12.0,
}
#: Qwen3 mit Transkript: Audio und Text müssen zusammenpassen, daher nur am Gateway-Limit kappen.
QWEN_WITH_TEXT_SECONDS = 20.0
#: Empfohlene Länge je Modell (für Hinweise in der Oberfläche).
MODEL_REF_HINT = {
    "tontaube": "Tontaube: 10–30 s; die Klangfarbe kommt vor allem aus den ersten ~10 s.",
    "chatterbox": "Chatterbox: nur die ersten 10 s zählen.",
    "chatterbox-turbo": "Chatterbox Turbo: nur die ersten 10–15 s zählen.",
    "qwen3": "Qwen3: 6–12 s plus wortgenaues Transkript genau dieses Ausschnitts (max. 20 s).",
}

TARGET_SR = 24000


def _tool(name: str) -> str | None:
    return shutil.which(name) or next(
        (p for p in (f"/opt/homebrew/bin/{name}", f"/usr/local/bin/{name}") if os.path.exists(p)), None)


def max_seconds_for(model: str | None, has_ref_text: bool = False) -> float:
    model = model or "tontaube"
    if model == "qwen3" and has_ref_text:
        return QWEN_WITH_TEXT_SECONDS
    return MODEL_REF_SECONDS.get(model, 60.0)


def filter_chain(max_seconds: float | None, denoise: bool = False) -> str:
    """ffmpeg-Filter: Brummen weg, Stille an den Rändern weg, lange Pausen auf ca. 0,5 s, ggf. entrauschen, kürzen."""
    parts = ["highpass=f=70"]
    if denoise:
        # Leichtes FFT-Entrauschen mit automatischer Rauschschätzung; stärker würde die Klangfarbe verfärben.
        parts.append("afftdn=nr=10:nf=-40:tn=1")
    parts += [
        # Anfang: alles bis zur ersten Sprache weg. Danach: Pausen auf ca. 0,5 s kürzen, Ende abschneiden.
        "silenceremove=start_periods=1:start_duration=0.05:start_threshold=-45dB:start_silence=0.05"
        ":stop_periods=-1:stop_duration=0.5:stop_threshold=-45dB:stop_silence=0:detection=rms",
    ]
    if max_seconds:
        parts.append(f"atrim=end={max_seconds:g}")
        # weiches Ausblenden statt hartem Schnitt mitten im Wort
        parts.append(f"afade=t=out:st={max(0.0, max_seconds - 0.15):g}:d=0.15")
    return ",".join(parts)


@dataclass
class PreparedReference:
    audio: bytes
    seconds_in: float | None
    seconds_out: float | None
    changed: bool
    note: str = ""


def probe_duration(data: bytes) -> float | None:
    """Dauer beliebiger Audiodaten (WAV direkt, sonst über ffprobe)."""
    dur = wav_duration(data)
    if dur is not None:
        return dur
    ffmpeg = _tool("ffmpeg")
    if not ffmpeg:
        return None
    # ffprobe kennt bei gepipten MP3/M4A die Länge oft nicht; dekodieren und Samples zählen ist verlässlich.
    try:
        proc = subprocess.run(
            [ffmpeg, "-v", "error", "-i", "pipe:0", "-ac", "1", "-ar", "8000", "-f", "u8", "pipe:1"],
            input=data, capture_output=True, timeout=60,
        )
    except (subprocess.SubprocessError, OSError):
        return None
    if proc.returncode != 0 or not proc.stdout:
        return None
    return len(proc.stdout) / 8000.0


def prepare_reference(data: bytes, max_seconds: float | None, denoise: bool = False) -> PreparedReference:
    """Referenz aufbereiten (siehe Modul-Docstring). Ohne ffmpeg oder bei Fehlern: Original zurück."""
    seconds_in = probe_duration(data)
    ffmpeg = _tool("ffmpeg")
    if not ffmpeg:
        return PreparedReference(data, seconds_in, seconds_in, False, "ffmpeg fehlt, Referenz unverändert gesendet.")
    try:
        proc = subprocess.run(
            [ffmpeg, "-v", "error", "-i", "pipe:0", "-af", filter_chain(max_seconds, denoise),
             "-ac", "1", "-ar", str(TARGET_SR), "-c:a", "pcm_s16le", "-f", "wav", "pipe:1"],
            input=data, capture_output=True, timeout=120,
        )
    except (subprocess.SubprocessError, OSError) as exc:
        return PreparedReference(data, seconds_in, seconds_in, False, f"Aufbereitung fehlgeschlagen ({exc}).")
    out = proc.stdout
    if proc.returncode != 0 or not out:
        err = proc.stderr.decode(errors="replace").strip()[-200:]
        return PreparedReference(data, seconds_in, seconds_in, False, f"Aufbereitung fehlgeschlagen ({err}).")
    # ffmpeg schreibt beim Pipen keine gültige Länge in den WAV-Header; Dauer aus der Datenmenge.
    out = _fix_wav_sizes(out)
    seconds_out = wav_duration(out)
    if seconds_out is not None and seconds_out < 1.0:
        # Alles als Stille erkannt (z. B. sehr leise Aufnahme): lieber das Original schicken.
        return PreparedReference(data, seconds_in, seconds_in, False,
                                 "Referenz ist fast nur Stille/sehr leise, unverändert gesendet.")
    return PreparedReference(out, seconds_in, seconds_out, True)


def _fix_wav_sizes(wav: bytes) -> bytes:
    """RIFF-/data-Größen eines von ffmpeg gepipten WAV korrigieren (dort oft 0xFFFFFFFF)."""
    if len(wav) < 44 or wav[:4] != b"RIFF" or wav[8:12] != b"WAVE":
        return wav
    buf = bytearray(wav)
    pos = 12
    while pos + 8 <= len(buf):
        cid = bytes(buf[pos:pos + 4])
        size = int.from_bytes(buf[pos + 4:pos + 8], "little")
        if cid == b"data":
            buf[pos + 4:pos + 8] = (len(buf) - pos - 8).to_bytes(4, "little")
            break
        pos += 8 + size + (size & 1)
    buf[4:8] = (len(buf) - 8).to_bytes(4, "little")
    return bytes(buf)
