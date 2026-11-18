import io
import shutil
import subprocess
import wave

import numpy as np
import pytest

from tontaube_gui import app
from tontaube_gui.client import wav_duration
from tontaube_gui.reference import filter_chain, max_seconds_for, prepare_reference, probe_duration

needs_ffmpeg = pytest.mark.skipif(not shutil.which("ffmpeg"), reason="ffmpeg fehlt")
SR = 44100


def _wav(*parts):
    """parts: (Sekunden, Ton an?) – Stille/Ton-Abschnitte als 16-bit-WAV, 44,1 kHz."""
    chunks = []
    for seconds, on in parts:
        t = np.arange(int(seconds * SR)) / SR
        chunks.append(0.3 * np.sin(2 * np.pi * 220 * t) if on else np.zeros_like(t))
    buf = io.BytesIO()
    with wave.open(buf, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes((np.concatenate(chunks) * 32767).astype("<i2").tobytes())
    return buf.getvalue()


def test_max_seconds_per_model():
    assert max_seconds_for("chatterbox") == 10
    assert max_seconds_for("qwen3") == 12
    assert max_seconds_for("qwen3", has_ref_text=True) == 20
    assert max_seconds_for(None) == 60
    assert "afftdn" in filter_chain(10, denoise=True) and "afftdn" not in filter_chain(10)


@needs_ffmpeg
def test_prepare_trims_silence_and_long_pauses():
    data = _wav((2, False), (5, True), (3, False), (10, True), (2, False))
    full = prepare_reference(data, 60)
    assert full.changed and full.seconds_in == pytest.approx(22, abs=0.05)
    assert 15.5 < full.seconds_out < 16.5  # Rand-Stille weg, 3-s-Pause → ~0,5 s
    with wave.open(io.BytesIO(full.audio)) as w:
        assert (w.getframerate(), w.getnchannels(), w.getsampwidth()) == (24000, 1, 2)
        first = np.frombuffer(w.readframes(2400), dtype="<i2")
    assert np.abs(first).max() > 3000  # beginnt direkt mit „Sprache“
    assert prepare_reference(data, 10).seconds_out == pytest.approx(10, abs=0.05)


@needs_ffmpeg
def test_prepare_keeps_original_on_silence_or_garbage():
    silent = _wav((5, False))
    assert prepare_reference(silent, 10).audio == silent
    bad = prepare_reference(b"kein audio", 10)
    assert bad.audio == b"kein audio" and not bad.changed and bad.note


@needs_ffmpeg
def test_probe_duration_mp3():
    mp3 = subprocess.run(["ffmpeg", "-v", "error", "-i", "pipe:0", "-f", "mp3", "pipe:1"],
                         input=_wav((4, True)), capture_output=True, check=True).stdout
    assert wav_duration(mp3) is None
    assert probe_duration(mp3) == pytest.approx(4, abs=0.1)


@needs_ffmpeg
def test_collect_sends_prepared_reference(tmp_path):
    ref = tmp_path / "ref.wav"
    ref.write_bytes(_wav((1, False), (15, True)))
    form = [
        "Hallo Welt.", False, "Deutsch", "Konversation", 0.7, True, app.VOICE_CLONE, "", str(ref),
        "wav", 0, app.TRIM_DEFAULT, 250, False, False, "", "", "", "", "", "", "",
        "chatterbox", 0.5, 0.5, app.QWEN_AUTO, "", "", True, False,
    ]
    assert len(form) == app.N_FORM
    import base64

    sent = base64.b64decode(app._collect(*form, [], [])["voice_audio_b64"])
    assert wav_duration(sent) == pytest.approx(10, abs=0.05)
    form[-2] = False  # Aufbereitung aus → Original
    assert base64.b64decode(app._collect(*form, [], [])["voice_audio_b64"]) == ref.read_bytes()
    hint = app.reference_hint(ref.read_bytes(), "qwen3", True, False, "")
    assert "12.0 s" in hint and "Transkript" in hint
