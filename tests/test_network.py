from urllib.parse import parse_qs, urlparse

from tontaube_gui.launcher import ServerManager
from tontaube_gui.network import NetworkSettings, connect_link, generate_key, lan_addresses, qr_svg_data_uri


def test_settings_roundtrip(tmp_path):
    path = tmp_path / "network.json"
    s = NetworkSettings.load(path)
    assert not s.enabled and s.api_key == "" and s.port == 8080
    s.enabled = True
    key = s.regenerate_key()
    s.save()
    loaded = NetworkSettings.load(path)
    assert loaded.enabled and loaded.api_key == key
    assert (path.stat().st_mode & 0o777) == 0o600


def test_generate_key_is_urlsafe():
    key = generate_key()
    assert len(key) >= 32 and all(c.isalnum() or c in "-_" for c in key)


def test_connect_link_roundtrip():
    link = connect_link("http://192.168.1.20:8080", "ab-c_d", "Mein Mac")
    parsed = urlparse(link)
    assert parsed.scheme == "tontaube" and parsed.netloc == "connect"
    q = parse_qs(parsed.query)
    assert q == {"url": ["http://192.168.1.20:8080"], "key": ["ab-c_d"], "name": ["Mein Mac"]}


def test_qr_code():
    uri = qr_svg_data_uri("tontaube://connect?url=x&key=y")
    assert uri and uri.startswith("data:image/svg+xml") and "xmlns" in uri


def test_lan_addresses_no_loopback():
    assert all(not ip.startswith("127.") for ip in lan_addresses())


def test_server_env_lan(tmp_path):
    m = ServerManager(home=tmp_path)
    env = m._server_env([], 8080, lan=False)
    assert env["TTS_HOST"] == "127.0.0.1" and "REQUIRE_API_KEY" not in env
    assert m.set_network(True) is True
    env = m._server_env([], 8080, lan=True)
    assert env["TTS_HOST"] == "0.0.0.0"
    assert env["REQUIRE_API_KEY"] == "1" and env["TTS_API_KEY"] == m.network.api_key
    assert NetworkSettings.load(tmp_path / "network.json").enabled
    assert m.client_key == ""  # Server läuft noch nicht im LAN-Modus


def test_model_settings_and_restart_flag(tmp_path):
    from tontaube_gui.launcher import EXTRA_MODELS, ModelSettings

    m = ServerManager(home=tmp_path)
    assert not m.models.enabled and m.models.engines == list(EXTRA_MODELS)
    assert m.set_models(True, ["qwen3", "bogus"], "0.6B")
    loaded = ModelSettings.load(tmp_path / "models.json")
    assert loaded.enabled and loaded.engines == ["qwen3"] and loaded.qwen_size == "0.6B"
    env = m._gateway_env([], 8080, 8180, lan=False)
    assert env["GATEWAY_UPSTREAM"] == "http://127.0.0.1:8180" and env["GATEWAY_ENGINES"] == "qwen3"
    assert "TTS_API_KEY" not in env


def test_prefetch_status_line(tmp_path):
    m = ServerManager(home=tmp_path)
    m._gateway_active = True
    m.state.phase = "ready"
    m.models.engines = ["qwen3"]
    m._gw_status_at = 1e12  # Zwischenspeicher gilt, kein HTTP
    m._gw_status = {"prefetch": {"items": [
        {"label": "Qwen3-TTS (Sprecher)", "state": "done", "done_bytes": 1e9, "total_bytes": 1e9},
        {"label": "Qwen3-TTS (Stimme klonen)", "state": "downloading", "done_bytes": 1e9, "total_bytes": 4e9},
        {"label": "Qwen3-TTS (Stimme per Beschreibung)", "state": "pending", "done_bytes": 0, "total_bytes": 0},
    ]}}
    line = m.prefetch_markdown()
    assert "2 von 3" in line and "1.0 von 4.0 GB (25 %)" in line
    for item in m._gw_status["prefetch"]["items"]:
        item["state"] = "done"
    assert m.prefetch_markdown().startswith("✅")
    m._gw_status = None
    assert "beim ersten Gebrauch" in m.prefetch_markdown()
