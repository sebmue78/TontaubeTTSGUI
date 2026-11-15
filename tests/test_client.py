import base64
import io
import json
import socket
import threading
import time
import wave

import pytest
import uvicorn

from tontaube_gui.client import (
    TontaubeClient,
    TontaubeError,
    build_payload,
    concat_wav,
    iter_sse_audio,
    parse_controls,
    parse_voices,
    wav_duration,
)


def _wav(seconds=1.0, sr=24000):
    out = io.BytesIO()
    with wave.open(out, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(sr)
        w.writeframes(b"\0\0" * int(sr * seconds))
    return out.getvalue()


def test_build_payload_omits_defaults():
    p = build_payload("Hallo", language="german", tag="audiobook")
    assert p == {"text": "Hallo", "language": "german", "tag": "audiobook", "format": "wav"}


def test_build_payload_trim_none_is_explicit_null():
    p = build_payload("Hallo", trim_silence_padding_ms=None)
    assert "trim_silence_padding_ms" in p and p["trim_silence_padding_ms"] is None


def test_build_payload_voice_audio_wins_over_path():
    p = build_payload("Hi", voice_audio=b"abc", voice_path="samples/Marcus.mp3")
    assert p["voice_audio_b64"] == base64.b64encode(b"abc").decode()
    assert "voice_path" not in p


def test_build_payload_rejects_empty():
    with pytest.raises(TontaubeError):
        build_payload("   ")
    with pytest.raises(TontaubeError):
        build_payload(["", " "])


@pytest.mark.parametrize(
    "payload",
    [
        {"rate": {"min": 0, "max": 4}, "legato": {"min": 0, "max": 2}},
        {"controls": [{"name": "rate", "max": 4}, {"name": "legato", "steps": 3}]},
        {"rate": 5, "legato": 3},
    ],
)
def test_parse_controls_shapes(payload):
    specs = {s.name: s for s in parse_controls(payload)}
    assert (specs["rate"].minimum, specs["rate"].maximum) == (0, 4)
    assert specs["legato"].maximum == 2
    assert specs["rate"].label == "Tempo"


def test_parse_controls_real_server_format():
    payload = {"controls": [{"name": "rate", "bins": 5, "low": "much slower", "high": "much faster"},
                            {"name": "legato", "bins": 3, "default": 2}, {"name": "breath", "bins": 4,
                            "description": "breathing", "low": "none", "high": "audible"}]}
    specs = {s.name: s for s in parse_controls(payload)}
    assert specs["rate"].maximum == 4 and specs["legato"].default == 2
    assert specs["breath"].maximum == 3 and "none" in specs["breath"].description


def test_voice_catalog():
    from tontaube_gui.client import load_voice_catalog

    voices = load_voice_catalog()
    assert len(voices) == 46
    assert voices[0].language == "german" and voices[0].path.startswith("samples/")
    assert any(v.name == "Marcus" for v in voices)


def test_parse_voices():
    assert parse_voices(["a.mp3", {"path": "b.mp3"}, {"name": "c"}]) == ["a.mp3", "b.mp3", "c"]
    assert parse_voices({"voices": ["x"]}) == ["x"]


def test_sse_parser():
    a, b = base64.b64encode(b"AAA").decode(), base64.b64encode(b"BBB").decode()
    lines = [": ping", "event: audio", f"data: {json.dumps({'audio_b64': a})}", "", f"data: {b}", "",
             "event: done", "data: {}", "", "data: ignored"]
    assert list(iter_sse_audio(iter(lines))) == [b"AAA", b"BBB"]
    lines = [f'data: {json.dumps({"index": 0, "audio_b64": a, "done": False})}', "",
             f'data: {json.dumps({"done": True})}', ""]
    assert list(iter_sse_audio(iter(lines))) == [b"AAA"]


def test_sse_error_event():
    with pytest.raises(TontaubeError):
        list(iter_sse_audio(iter(["event: error", 'data: {"error": "boom"}', ""])))


def test_concat_wav():
    joined = concat_wav(_wav(1), _wav(0.5))
    assert abs(wav_duration(joined) - 1.5) < 1e-3
    assert concat_wav(_wav(1, 24000), _wav(1, 48000)) is None


# -- Integration gegen den Mock-Server ---------------------------------------

@pytest.fixture(scope="module")
def mock_url():
    import tools.mock_server as mock

    sock = socket.socket()
    sock.bind(("127.0.0.1", 0))
    port = sock.getsockname()[1]
    sock.close()
    server = uvicorn.Server(uvicorn.Config(mock.app, host="127.0.0.1", port=port, log_level="warning"))
    t = threading.Thread(target=server.run, daemon=True)
    t.start()
    for _ in range(100):
        if server.started:
            break
        time.sleep(0.05)
    yield f"http://127.0.0.1:{port}"
    server.should_exit = True
    t.join(5)


def test_client_against_mock(mock_url):
    c = TontaubeClient(mock_url)
    ok, _ = c.ready()
    assert ok
    assert {s.name for s in c.controls()} == {"rate", "pause", "pitch_range", "pitch_level", "legato"}
    assert c.voices() == []
    r = c.predict(build_payload("Hallo Welt", language="german", controls={"rate": 1}, voice_audio=_wav(6),
                                sampling={"seed": 3, "top_p": None}))
    assert r.raw["echo"]["seed"] == 3 and "top_p" not in r.raw["echo"]
    assert r.format == "wav" and wav_duration(r.audio) > 0.5
    assert r.raw["had_voice_audio"] is True
    assert r.codebook_tokens is not None


def test_client_422(mock_url):
    c = TontaubeClient(mock_url)
    with pytest.raises(TontaubeError, match="422"):
        c.predict(build_payload("Hi", controls={"rate": 9}))


def test_stream_against_mock(mock_url):
    pytest.importorskip("lameenc")
    chunks = list(TontaubeClient(mock_url).stream(build_payload("Ein etwas längerer Satz zum Streamen.")))
    assert len(chunks) > 1
    assert b"".join(chunks)[:3] in (b"ID3", b"\xff\xfb", b"\xff\xf3") or b"".join(chunks)[0] == 0xFF


def test_unreachable_server():
    ok, msg = TontaubeClient("http://127.0.0.1:1").ready()
    assert not ok and "nicht erreichbar" in msg
