"""Modell-Gateway (Chatterbox/Qwen3 neben Tontaube) im Testmodus mit Sinustönen."""

import base64
import importlib.util
import socket
import sys
import threading
import time
from pathlib import Path

import pytest
import uvicorn

from tontaube_gui.client import TontaubeClient, TontaubeError, build_payload

GW_PATH = Path(__file__).resolve().parents[1] / "tontaube_gui" / "server_assets" / "model_gateway.py"
spec = importlib.util.spec_from_file_location("model_gateway", GW_PATH)
gw = importlib.util.module_from_spec(spec)
sys.modules["model_gateway"] = gw
spec.loader.exec_module(gw)


def _serve(app):
    sock = socket.socket()
    sock.bind(("127.0.0.1", 0))
    port = sock.getsockname()[1]
    sock.close()
    server = uvicorn.Server(uvicorn.Config(app, host="127.0.0.1", port=port, log_level="warning"))
    t = threading.Thread(target=server.run, daemon=True)
    t.start()
    for _ in range(200):
        if server.started:
            break
        time.sleep(0.05)
    return server, t, f"http://127.0.0.1:{port}"


@pytest.fixture(scope="module")
def gateway(tmp_path_factory):
    import tools.mock_server as mock

    up, up_t, up_url = _serve(mock.app)
    voices = tmp_path_factory.mktemp("voices")
    (voices / "samples").mkdir()
    (voices / "samples" / "Juri.wav").write_bytes(gw.wav_bytes(b"\0\0" * 24000, 24000))
    settings = gw.Settings(upstream=up_url, api_key="secret", voices_dir=voices.resolve(), fake=True)
    backend = gw.FakeBackend()
    gs, gs_t, gs_url = _serve(gw.create_app(settings, backend))
    yield gs_url, backend
    gs.should_exit = up.should_exit = True
    gs_t.join(5)
    up_t.join(5)


def test_split_sentences():
    parts = gw.split_sentences("Hallo Welt. Wie geht's? Gut!\nNeue Zeile " + "lang " * 100)
    assert parts[0].startswith("Hallo Welt.")
    assert all(len(p) <= 280 for p in parts)
    assert " ".join(parts).split() == ("Hallo Welt. Wie geht's? Gut! Neue Zeile " + "lang " * 100).split()


def test_strip_tags_only_known():
    assert gw.strip_tags("Na gut [laugh] dann [Kapitel 3].") == "Na gut dann [Kapitel 3]."


def test_job_variants():
    s = gw.Settings(fake=True)
    assert gw.parse_job({"model": "qwen3", "text": "Hallo"}, s).variant == "qwen3-custom"
    assert gw.parse_job({"model": "qwen3", "text": "Hallo", "instruct": "tiefe Stimme"}, s).variant == "qwen3-design"
    assert gw.parse_job({"model": "qwen3", "text": "Hallo", "speaker": "Ryan", "instruct": "froh"},
                        s).variant == "qwen3-custom"
    b64 = base64.b64encode(b"RIFF").decode()
    assert gw.parse_job({"model": "qwen3", "text": "Hallo", "voice_audio_b64": b64}, s).variant == "qwen3-base"
    with pytest.raises(gw.RequestError):
        gw.parse_job({"model": "chatterbox-turbo", "text": "Hallo", "language": "german"}, s)
    with pytest.raises(gw.RequestError):
        gw.parse_job({"model": "qwen3", "text": "Hallo", "language": "dutch"}, s)
    job = gw.parse_job({"model": "chatterbox", "text": "Hi", "exaggeration": 9}, s)
    assert job.params["exaggeration"] == 2.0 and job.language == "german"


def test_api_key_required(gateway):
    url, _ = gateway
    assert TontaubeClient(url).ready()[0]  # /readyz bleibt offen
    with pytest.raises(TontaubeError):
        TontaubeClient(url).controls()
    assert TontaubeClient(url, api_key="secret").controls()


def test_models_listing(gateway):
    import requests

    url, _ = gateway
    data = requests.get(url + "/models", headers={"x-api-key": "secret"}, timeout=5).json()
    ids = [m["id"] for m in data["models"]]
    assert ids == ["tontaube", "chatterbox", "chatterbox-turbo", "qwen3"]
    turbo = data["models"][2]
    assert "[laugh]" in turbo["tags"] and turbo["languages"] == ["english"]


def test_tontaube_is_proxied_and_model_field_removed(gateway):
    url, backend = gateway
    c = TontaubeClient(url, api_key="secret")
    n = len(backend.calls)
    res = c.predict(build_payload("Hallo", extra={"model": "tontaube"}))
    assert res.raw.get("codebook_tokens")  # kommt vom Mock-Tontaube
    assert len(backend.calls) == n
    assert b"".join(c.stream(build_payload("Hallo Welt.")))


@pytest.mark.parametrize("model,lang", [("chatterbox", "german"), ("chatterbox-turbo", "english"),
                                        ("qwen3", "german")])
def test_local_models_stream_and_predict(gateway, model, lang):
    url, backend = gateway
    c = TontaubeClient(url, api_key="secret")
    payload = build_payload("Erster Satz [laugh]. Zweiter Satz!", language=lang, voice_path="samples/Juri.wav",
                            extra={"model": model, "exaggeration": 0.7})
    mp3 = b"".join(c.stream(payload))
    assert len(mp3) > 1000 and (mp3[:3] == b"ID3" or mp3[0] == 0xFF)
    job = backend.calls[-1]
    assert job.model == model and job.ref_audio
    assert ("[laugh]" in job.text) == (model == "chatterbox-turbo")
    res = c.predict(payload)
    assert res.format == "wav" and res.audio[:4] == b"RIFF"


def test_local_model_errors(gateway):
    url, _ = gateway
    c = TontaubeClient(url, api_key="secret")
    with pytest.raises(TontaubeError, match="nicht gefunden"):
        c.predict(build_payload("Hallo", voice_path="../../etc/passwd", extra={"model": "chatterbox"}))
    with pytest.raises(TontaubeError):
        c.predict(build_payload("Hallo", extra={"model": "gibtsnicht"}))


def test_client_models_and_gui_payload(gateway):
    from tontaube_gui import app

    url, backend = gateway
    assert [m["id"] for m in TontaubeClient(url, api_key="secret").models()][:2] == ["tontaube", "chatterbox"]
    form = [
        "Hallo [sigh] Welt.", False, "Deutsch", "Konversation", 0.7, True, app.VOICE_BUNDLED, "samples/Juri.wav",
        None, "opus", 0, app.TRIM_DEFAULT, 250, False, False, "", "", "7", "", "", "", "",
        "qwen3", 0.5, 0.5, "Ryan", "freundlich", "",
    ]
    assert len(form) == app.N_FORM
    payload = app._collect(*form, [], [])
    assert payload == {"model": "qwen3", "text": "Hallo [sigh] Welt.", "language": "german", "format": "wav",
                       "temperature": 0.7, "seed": 7, "speaker": "Ryan", "instruct": "freundlich",
                       "voice_path": "samples/Juri.wav"}
    res = TontaubeClient(url, api_key="secret").predict(payload)
    assert res.audio[:4] == b"RIFF"
    assert backend.calls[-1].variant == "qwen3-base" and backend.calls[-1].text == "Hallo Welt."


def test_tontaube_only_server_has_no_models():
    import tools.mock_server as mock

    server, t, url = _serve(mock.app)
    try:
        assert TontaubeClient(url).models() == []
    finally:
        server.should_exit = True
        t.join(5)
