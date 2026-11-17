"""Verwalteter Tontaube-Server für die All-in-one-App.

Richtet beim ersten Start alles selbst ein und startet den Server im Hintergrund:

1. Quellcode des Inferenzservers (craitech/tontaube v1.1.0) herunterladen und den
   Apple-Silicon-Patch anwenden (ohne vLLM, ohne x86-AVX2-Kernel).
2. Eine eigene Python-3.12-Umgebung mit ``uv`` anlegen und die Pakete installieren.
3. ffmpeg/opus prüfen (bei Bedarf über Homebrew installieren).
4. Server starten. Beim ersten Start lädt er die Modelle (mehrere GB) selbst herunter.

Der Fortschritt steht in :attr:`ServerManager.state` und wird in der GUI angezeigt.
"""

from __future__ import annotations

import atexit
import hashlib
import io
import json
import os
import platform
import shutil
import signal
import socket
import subprocess
import tarfile
import threading
import time
import urllib.request
from collections import deque
from dataclasses import dataclass, field
from pathlib import Path

import requests

from .network import NetworkSettings, lan_addresses

SERVER_VERSION = "v1.1.0"
SERVER_TARBALL = f"https://codeload.github.com/craitech/tontaube/tar.gz/refs/tags/{SERVER_VERSION}"
ASSETS = Path(__file__).resolve().parent / "server_assets"
PATCH_FILE = ASSETS / "tontaube-v1.1.0-apple-silicon.patch"
REQUIREMENTS_FILE = ASSETS / "requirements-server-macos.txt"
ENGINES_REQUIREMENTS_FILE = ASSETS / "requirements-engines-macos.txt"
GATEWAY_SCRIPT = ASSETS / "model_gateway.py"

# Zusatzmodelle hinter dem Gateway (Kennung → Anzeigename).
EXTRA_MODELS = {
    "chatterbox": "Chatterbox Multilingual (Deutsch, Emotions-Regler)",
    "chatterbox-turbo": "Chatterbox Turbo (nur Englisch, Tags wie [laugh])",
    "qwen3": "Qwen3-TTS (Deutsch, Stil-Anweisungen, Stimme per Beschreibung)",
}

# Ungefähre Downloadgröße beim ersten Start (int4-Modell, DualCodec, W2V-BERT, VibeVoice).
EXPECTED_DOWNLOAD_GB = 8.0

EXTRA_PATHS = ["/opt/homebrew/bin", "/usr/local/bin"]
EXTRA_LIB_PATHS = ["/opt/homebrew/lib", "/usr/local/lib"]


def default_home() -> Path:
    if env := os.environ.get("TONTAUBE_HOME"):
        return Path(env).expanduser()
    if platform.system() == "Darwin":
        return Path.home() / "Library" / "Application Support" / "TontaubeTTS"
    return Path(os.environ.get("XDG_DATA_HOME", Path.home() / ".local" / "share")) / "TontaubeTTS"


def default_device() -> str:
    """Metal (MPS) auf Apple Silicon, sonst CPU."""
    if platform.system() == "Darwin" and platform.machine() == "arm64":
        return "mps"
    return "cpu"


def _free_port(preferred: int, host: str = "127.0.0.1") -> int:
    for port in [preferred, *range(preferred + 1, preferred + 50)]:
        with socket.socket() as s:
            try:
                s.bind((host, port))
                return port
            except OSError:
                continue
    with socket.socket() as s:
        s.bind((host, 0))
        return s.getsockname()[1]


def _dir_size(path: Path) -> int:
    total = 0
    if not path.exists():
        return 0
    for root, _dirs, files in os.walk(path):
        for f in files:
            try:
                total += os.lstat(os.path.join(root, f)).st_size
            except OSError:
                pass
    return total


def _which(name: str) -> str | None:
    path = os.pathsep.join([os.environ.get("PATH", ""), *EXTRA_PATHS])
    return shutil.which(name, path=path)


class GatewayError(RuntimeError):
    """Das Modell-Gateway ist abgestürzt (Tontaube selbst läuft womöglich)."""


@dataclass
class ModelSettings:
    """Zusatzmodelle (Chatterbox, Qwen3-TTS) über das Modell-Gateway."""

    enabled: bool = False
    engines: list[str] = field(default_factory=lambda: list(EXTRA_MODELS))
    qwen_size: str = "1.7B"
    path: Path | None = field(default=None, repr=False, compare=False)

    @classmethod
    def load(cls, path: Path) -> "ModelSettings":
        try:
            data = json.loads(Path(path).read_text(encoding="utf-8"))
        except (OSError, ValueError):
            data = {}
        engines = [e for e in data.get("engines", list(EXTRA_MODELS)) if e in EXTRA_MODELS]
        size = data.get("qwen_size") if data.get("qwen_size") in ("0.6B", "1.7B") else "1.7B"
        return cls(enabled=bool(data.get("enabled", False)), engines=engines, qwen_size=size, path=Path(path))

    @property
    def active(self) -> bool:
        return self.enabled and bool(self.engines)

    def save(self) -> None:
        if self.path is None:
            return
        self.path.parent.mkdir(parents=True, exist_ok=True)
        data = {"enabled": self.enabled, "engines": self.engines, "qwen_size": self.qwen_size}
        self.path.write_text(json.dumps(data, indent=2), encoding="utf-8")


@dataclass
class ServerState:
    phase: str = "idle"  # idle, setup, starting, ready, error, stopped
    message: str = "Noch nicht gestartet."
    detail: str = ""
    progress: float | None = None
    url: str = ""
    started_at: float = field(default_factory=time.time)


def default_dtype(device: str) -> str:
    """float16 ist auf der Apple-GPU genauer und gleich schnell, bfloat16 spart auf der CPU Speicher."""
    return "float16" if device == "mps" else "bfloat16"


class ServerManager:
    PHASE_LABELS = {
        "idle": "⚪ Nicht gestartet",
        "setup": "🛠️ Einrichtung",
        "starting": "⏳ Server startet",
        "ready": "🟢 Bereit",
        "error": "🔴 Fehler",
        "stopped": "⚪ Gestoppt",
    }

    DEVICE_LABELS = {"mps": "Apple-GPU (Metal)", "cpu": "CPU"}

    def __init__(self, home: Path | None = None, port: int = 8080, dtype: str | None = None,
                 device: str | None = None):
        self.home = (home or default_home()).resolve()
        self.src = self.home / f"server-{SERVER_VERSION}"
        self.venv = self.home / "server-venv"
        self.engines_venv = self.home / "engines-venv"
        self.logs = self.home / "logs"
        self.log_file = self.logs / "server.log"
        self.gateway_log = self.logs / "gateway.log"
        self.preferred_port = port
        self.device = device or os.environ.get("LITE_DEVICE") or default_device()
        self._dtype_auto = not (dtype or os.environ.get("LITE_CPU_DTYPE"))
        self.dtype = dtype or os.environ.get("LITE_CPU_DTYPE") or default_dtype(self.device)
        self._fell_back = False
        self.state = ServerState()
        self.proc: subprocess.Popen | None = None
        self.gateway_proc: subprocess.Popen | None = None
        self.models = ModelSettings.load(self.home / "models.json")
        self._gateway_active = False  # läuft das Gateway gerade (nicht die gespeicherte Einstellung)?
        self._gateway_error = ""
        self._lines: deque[str] = deque(maxlen=400)
        self._lock = threading.Lock()
        self._thread: threading.Thread | None = None
        self._stop = threading.Event()
        self.network = NetworkSettings.load(self.home / "network.json")
        self._lan_active = False  # Stand des laufenden Servers (nicht der gespeicherten Einstellung)
        self._hf_cache = Path(os.environ.get("HF_HOME", Path.home() / ".cache" / "huggingface")) / "hub"
        self._cache_baseline = 0
        self._gw_status: dict | None = None
        self._gw_status_at = 0.0
        atexit.register(self.stop)

    # -- Status ----------------------------------------------------------
    def _set(self, phase: str | None = None, message: str | None = None, detail: str | None = None,
             progress: float | None | str = "keep") -> None:
        with self._lock:
            if phase:
                self.state.phase = phase
            if message is not None:
                self.state.message = message
            if detail is not None:
                self.state.detail = detail
            if progress != "keep":
                self.state.progress = progress  # type: ignore[assignment]
        self._log(f"[{phase or self.state.phase}] {message or ''} {detail or ''}".strip())

    def _log(self, line: str) -> None:
        self._lines.append(line.rstrip())
        try:
            self.logs.mkdir(parents=True, exist_ok=True)
            with open(self.logs / "launcher.log", "a", encoding="utf-8") as fh:
                fh.write(time.strftime("%H:%M:%S ") + line.rstrip() + "\n")
        except OSError:
            pass

    def log_tail(self, n: int = 40) -> str:
        lines = list(self._lines)
        files = [("Server-Log", self.log_file)]
        if self._gateway_active:
            files.append(("Gateway-Log (Chatterbox/Qwen3)", self.gateway_log))
        for title, path in files:
            if not path.exists():
                continue
            try:
                with open(path, "rb") as fh:
                    fh.seek(0, os.SEEK_END)
                    size = fh.tell()
                    fh.seek(max(0, size - 20000))
                    lines += [f"— {title} —"] + fh.read().decode("utf-8", "replace").splitlines()[-n:]
            except OSError:
                pass
        return "\n".join(lines[-2 * n:])

    def downloaded_gb(self) -> float:
        return max(0, _dir_size(self._hf_cache) - self._cache_baseline) / 1e9

    def status_markdown(self) -> str:
        s = self.state
        lines = [f"**{self.PHASE_LABELS.get(s.phase, s.phase)}** – {s.message}"]
        if s.phase == "starting":
            gb = self.downloaded_gb()
            elapsed = int(time.time() - s.started_at)
            if gb > 0.05:
                lines.append(
                    f"Modelle werden geladen: {gb:.1f} GB heruntergeladen "
                    f"(beim ersten Start insgesamt ca. {EXPECTED_DOWNLOAD_GB:.0f} GB) · {elapsed // 60}:{elapsed % 60:02d} min"
                )
            else:
                lines.append(f"Läuft seit {elapsed // 60}:{elapsed % 60:02d} min.")
        if s.detail:
            lines.append(s.detail)
        if s.url and s.phase == "ready":
            lan = " · 📶 im Netzwerk freigegeben" if self._lan_active else ""
            lines.append(
                f"Server: `{s.url}` · Rechenwerk: {self.DEVICE_LABELS.get(self.device, self.device)} · "
                f"Genauigkeit: `{self.dtype}`{lan}"
            )
            if self._gateway_active:
                lines.append(self.prefetch_markdown())
            elif self.models.active and not self._gateway_error:
                lines.append("Weitere Modelle sind eingeschaltet, laufen aber noch nicht: „Übernehmen“ oder "
                             "„Server neu starten“ klicken.")
        if self._gateway_error:
            lines.append(f"⚠️ Zusatzmodelle nicht verfügbar: {self._gateway_error}")
        return "\n\n".join(lines)

    def gateway_status(self, max_age: float = 3.0) -> dict | None:
        """``GET /gateway/status`` des laufenden Gateways (kurz zwischengespeichert), sonst None."""
        if not self._gateway_active or self.state.phase != "ready":
            return None
        now = time.time()
        if now - self._gw_status_at < max_age:
            return self._gw_status
        self._gw_status_at = now
        try:
            headers = {"x-api-key": self.client_key} if self.client_key else {}
            r = requests.get(self.state.url + "/gateway/status", headers=headers, timeout=2)
            self._gw_status = r.json() if r.ok else None
        except (requests.RequestException, ValueError):
            self._gw_status = None
        return self._gw_status

    def prefetch_markdown(self) -> str:
        """Eine Zeile zu den Zusatzmodellen: Download-Fortschritt oder bereit."""
        names = ", ".join(EXTRA_MODELS[e].split(" (")[0] for e in self.models.engines)
        status = self.gateway_status()
        pre = (status or {}).get("prefetch") or None
        if not pre or not pre.get("items"):
            return f"Zusatzmodelle: {names} (werden beim ersten Gebrauch geladen)"
        items = pre["items"]
        done = sum(1 for i in items if i.get("state") == "done")
        failed = [i for i in items if i.get("state") == "error"]
        current = next((i for i in items if i.get("state") == "downloading"), None)
        if current is not None:
            got, total = current.get("done_bytes", 0) / 1e9, current.get("total_bytes", 0) / 1e9
            size = f"{got:.1f} von {total:.1f} GB ({min(100, int(100 * got / total))} %)" if total else f"{got:.1f} GB"
            rest = len(items) - done - 1
            more = f", danach noch {rest}" if rest > 0 else ""
            return (f"⬇️ Zusatzmodelle werden heruntergeladen ({done + 1} von {len(items)}{more}): "
                    f"{current.get('label', '')} · {size}. Auswählen kannst du sie schon, die erste Anfrage "
                    "wartet dann auf den Download.")
        if any(i.get("state") == "pending" for i in items):
            return f"⬇️ Zusatzmodelle: Download startet gleich ({names})."
        if failed:
            labels = ", ".join(i.get("label", "") for i in failed)
            return (f"⚠️ Zusatzmodelle: Download fehlgeschlagen für {labels} (siehe Gateway-Log). "
                    "Wird beim ersten Gebrauch erneut versucht.")
        return f"✅ Zusatzmodelle heruntergeladen und bereit: {names}"

    # -- Netzwerk-Freigabe ----------------------------------------------
    @property
    def lan_active(self) -> bool:
        """Läuft der Server gerade im LAN-Modus (an alle Schnittstellen gebunden, mit API-Key)?"""
        return self._lan_active

    @property
    def client_key(self) -> str:
        """API-Key, den Clients (auch die eigene Oberfläche) gerade mitschicken müssen."""
        return self.network.api_key if self._lan_active else ""

    @property
    def port(self) -> int | None:
        try:
            return int(self.state.url.rsplit(":", 1)[1]) if self.state.url else None
        except (IndexError, ValueError):
            return None

    def lan_urls(self) -> list[str]:
        port = self.port or self.network.port
        return [f"http://{addr}:{port}" for addr in lan_addresses()]

    def set_network(self, enabled: bool, regenerate_key: bool = False) -> bool:
        """Speichert die Freigabe. Gibt True zurück, wenn der Server dafür neu starten muss."""
        changed = self.network.enabled != enabled or (regenerate_key and enabled)
        self.network.enabled = enabled
        if regenerate_key or not self.network.api_key:
            self.network.regenerate_key()
        self.network.save()
        return changed or (enabled != self._lan_active)

    def set_models(self, enabled: bool, engines: list[str] | None = None, qwen_size: str | None = None) -> bool:
        """Speichert die Zusatzmodell-Einstellung. True, wenn der Server dafür neu starten muss."""
        before = (self.models.active, tuple(self.models.engines), self.models.qwen_size)
        self.models.enabled = enabled
        if engines is not None:
            self.models.engines = [e for e in engines if e in EXTRA_MODELS]
        if qwen_size in ("0.6B", "1.7B"):
            self.models.qwen_size = qwen_size
        self.models.save()
        after = (self.models.active, tuple(self.models.engines), self.models.qwen_size)
        # Neustart nur, wenn sich am laufenden Gateway etwas ändert (bei ausgeschalteten Modellen nie).
        return self.models.active != self._gateway_active or (self.models.active and before != after)

    @property
    def gateway_active(self) -> bool:
        return self._gateway_active

    @property
    def url(self) -> str:
        return self.state.url

    # -- Ablauf ----------------------------------------------------------
    def start(self) -> None:
        if self._thread and self._thread.is_alive():
            return
        self._stop.clear()
        self.state.started_at = time.time()
        self._thread = threading.Thread(target=self._run, name="tontaube-server-manager", daemon=True)
        self._thread.start()

    def restart(self, dtype: str | None = None, device: str | None = None) -> None:
        self.stop()
        if self._thread:
            self._thread.join(timeout=15)
        if device:
            self.device = device
            self._fell_back = False
        if dtype:
            self.dtype = dtype
            self._dtype_auto = False
        elif self._dtype_auto:
            self.dtype = default_dtype(self.device)
        self.start()

    def reinstall(self) -> None:
        self.stop()
        for marker in (self.src / ".tontaube-patched", self.venv / ".tontaube-installed",
                       self.engines_venv / ".tontaube-installed"):
            marker.unlink(missing_ok=True)
        self.start()

    def _kill(self, proc: subprocess.Popen | None, what: str) -> None:
        if proc and proc.poll() is None:
            self._log(f"{what} wird beendet …")
            try:
                os.killpg(proc.pid, signal.SIGTERM)
            except (ProcessLookupError, PermissionError, AttributeError):
                proc.terminate()
            try:
                proc.wait(timeout=20)
            except subprocess.TimeoutExpired:
                try:
                    os.killpg(proc.pid, signal.SIGKILL)
                except (ProcessLookupError, PermissionError, AttributeError):
                    proc.kill()

    def _kill_all(self) -> None:
        self._kill(self.gateway_proc, "Modell-Gateway")
        self._kill(self.proc, "Server")
        self.gateway_proc = None
        self.proc = None

    def stop(self) -> None:
        self._stop.set()
        self._kill_all()
        if self.state.phase in ("ready", "starting", "setup"):
            self._set("stopped", "Server gestoppt.", "", None)

    def _run(self) -> None:
        try:
            self.home.mkdir(parents=True, exist_ok=True)
            self._ensure_source()
            self._ensure_venv()
            tools_path = self._ensure_system_tools()
            gateway = self._ensure_engines()
            if self._stop.is_set():
                return
            self._start_server(tools_path, gateway)
            try:
                try:
                    self._wait_ready()
                except GatewayError as exc:
                    # Ohne Zusatzmodelle weitermachen, damit Tontaube nutzbar bleibt.
                    self._log(str(exc))
                    self._kill_all()
                    gateway = False
                    self._start_server(tools_path, gateway)
                    self._gateway_error = "Das Gateway ist beim Start abgestürzt (siehe Gateway-Log). Tontaube läuft ohne Zusatzmodelle."
                    self._wait_ready()
            except RuntimeError:
                if self.device != "mps" or self._stop.is_set():
                    raise
                # Metal-Pfad gescheitert: einmal automatisch auf der CPU versuchen.
                self._log("Start auf der Apple-GPU fehlgeschlagen, versuche es auf der CPU …")
                self.device, self._fell_back = "cpu", True
                if self._dtype_auto:
                    self.dtype = default_dtype("cpu")
                self._kill_all()
                self._start_server(tools_path, gateway)
                self._wait_ready()
        except Exception as exc:  # noqa: BLE001 - alles soll in der GUI landen
            self._set("error", "Einrichtung oder Start fehlgeschlagen.", f"`{type(exc).__name__}: {exc}`", None)

    # -- Schritte --------------------------------------------------------
    def _uv(self) -> str:
        candidates = [os.environ.get("TONTAUBE_UV"), _which("uv"), str(Path.home() / ".local" / "bin" / "uv")]
        for c in candidates:
            if c and Path(c).is_file() and os.access(c, os.X_OK):
                return c
        raise RuntimeError("Das Werkzeug 'uv' wurde nicht gefunden (wird normalerweise mit der App ausgeliefert).")

    def _ensure_source(self) -> None:
        marker = self.src / ".tontaube-patched"
        patch_hash = hashlib.sha256(PATCH_FILE.read_bytes()).hexdigest()
        if marker.exists() and marker.read_text().strip() == patch_hash:
            return
        self._set("setup", "Lade den Tontaube-Server (Quellcode) herunter …", "", None)
        if self.src.exists():
            shutil.rmtree(self.src)
        tmp = self.home / "server-download"
        shutil.rmtree(tmp, ignore_errors=True)
        tmp.mkdir(parents=True)
        with urllib.request.urlopen(SERVER_TARBALL, timeout=120) as resp:  # noqa: S310 - feste URL
            data = resp.read()
        with tarfile.open(fileobj=io.BytesIO(data), mode="r:gz") as tar:
            tar.extractall(tmp, filter="data")
        top = next(p for p in tmp.iterdir() if p.is_dir())
        top.rename(self.src)
        shutil.rmtree(tmp, ignore_errors=True)
        self._set("setup", "Passe den Server für Apple Silicon an …")
        result = subprocess.run(
            ["patch", "-p1", "--forward", "-i", str(PATCH_FILE)],
            cwd=self.src, capture_output=True, text=True,
        )
        if result.returncode != 0:
            raise RuntimeError(f"Patch fehlgeschlagen: {result.stdout}\n{result.stderr}")
        marker.write_text(patch_hash)
        (self.venv / ".tontaube-installed").unlink(missing_ok=True)

    def _ensure_venv(self) -> None:
        marker = self.venv / ".tontaube-installed"
        want = hashlib.sha256(REQUIREMENTS_FILE.read_bytes() + PATCH_FILE.read_bytes()).hexdigest()
        if marker.exists() and marker.read_text().strip() == want:
            return
        uv = self._uv()
        py = self.venv / "bin" / "python"
        self._set("setup", "Lege die Python-Umgebung an (Python 3.12) …")
        self._stream_cmd([uv, "venv", "--allow-existing", "--python", "3.12", str(self.venv)])
        self._set("setup", "Installiere PyTorch und die Server-Pakete (ca. 1–2 GB, einige Minuten) …")
        self._stream_cmd([uv, "pip", "install", "--python", str(py), "-r", str(REQUIREMENTS_FILE)])
        self._set("setup", "Installiere den Tontaube-Server …")
        self._stream_cmd([uv, "pip", "install", "--python", str(py), "--no-deps", "-e", str(self.src)])
        marker.write_text(want)

    def _ensure_engines(self) -> bool:
        """Umgebung für Chatterbox/Qwen3 (mlx-audio). False, wenn aus oder nicht installierbar."""
        self._gateway_error = ""
        if not self.models.active:
            return False
        if platform.system() == "Darwin" and platform.machine() != "arm64":
            self._gateway_error = "Chatterbox und Qwen3-TTS laufen hier nur auf Apple Silicon (MLX)."
            return False
        marker = self.engines_venv / ".tontaube-installed"
        want = hashlib.sha256(ENGINES_REQUIREMENTS_FILE.read_bytes()).hexdigest()
        if marker.exists() and marker.read_text().strip() == want:
            return True
        try:
            uv = self._uv()
            py = self.engines_venv / "bin" / "python"
            self._set("setup", "Lege die Umgebung für Chatterbox und Qwen3-TTS an …")
            self._stream_cmd([uv, "venv", "--allow-existing", "--python", "3.12", str(self.engines_venv)])
            self._set("setup", "Installiere mlx-audio für Chatterbox und Qwen3-TTS (einige Minuten) …")
            self._stream_cmd([uv, "pip", "install", "--python", str(py), "-r", str(ENGINES_REQUIREMENTS_FILE)])
            marker.write_text(want)
            return True
        except Exception as exc:  # noqa: BLE001 - Tontaube soll trotzdem starten
            self._gateway_error = f"Installation fehlgeschlagen ({exc}). Tontaube läuft trotzdem."
            self._log(self._gateway_error)
            return False

    def _ensure_system_tools(self) -> list[str]:
        """ffmpeg/ffprobe (Audio-Konvertierung) und libopus (Opus-Streaming)."""
        missing = [t for t in ("ffmpeg", "ffprobe") if not _which(t)]
        has_opus = any(Path(p, "libopus.dylib").exists() or Path(p, "libopus.so").exists() for p in EXTRA_LIB_PATHS)
        brew = _which("brew")
        if (missing or not has_opus) and brew and platform.system() == "Darwin":
            pkgs = (["ffmpeg"] if missing else []) + ([] if has_opus else ["opus"])
            self._set("setup", f"Installiere {', '.join(pkgs)} über Homebrew …")
            self._stream_cmd([brew, "install", *pkgs], env={**os.environ, "HOMEBREW_NO_AUTO_UPDATE": "1"})
            missing = [t for t in ("ffmpeg", "ffprobe") if not _which(t)]
        if missing:
            raise RuntimeError(
                "ffmpeg wird benötigt, wurde aber nicht gefunden. Bitte Homebrew installieren (https://brew.sh) "
                "und danach im Terminal `brew install ffmpeg opus` ausführen."
            )
        return EXTRA_PATHS

    def _stream_cmd(self, cmd: list[str], env: dict[str, str] | None = None) -> None:
        self._log("$ " + " ".join(cmd))
        proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, env=env)
        assert proc.stdout
        for line in proc.stdout:
            self._log(line)
            if self._stop.is_set():
                proc.terminate()
                break
        if proc.wait() != 0 and not self._stop.is_set():
            raise RuntimeError(f"Befehl fehlgeschlagen ({proc.returncode}): {' '.join(cmd[:4])} …")

    def _server_env(self, tools_path: list[str], port: int, lan: bool = False) -> dict[str, str]:
        env = dict(os.environ)
        env.update(
            {
                "TTS_BACKEND": "lite",
                "LITE_DEVICE": self.device,
                "PYTORCH_ENABLE_MPS_FALLBACK": "1",
                "LITE_CPU_DTYPE": self.dtype,
                "TTS_HOST": "0.0.0.0" if lan else "127.0.0.1",
                "TTS_PORT": str(port),
                "ENABLE_STREAMING": "1",
                "PATH": os.pathsep.join([str(self.venv / "bin"), *tools_path, env.get("PATH", "")]),
                "DYLD_FALLBACK_LIBRARY_PATH": os.pathsep.join(
                    [*EXTRA_LIB_PATHS, env.get("DYLD_FALLBACK_LIBRARY_PATH", "/usr/lib")]
                ),
                "PYTHONUNBUFFERED": "1",
                "TOKENIZERS_PARALLELISM": "false",
            }
        )
        if lan:
            # Im LAN gilt immer API-Key-Pflicht (Header x-api-key). /readyz und /healthz bleiben offen.
            env.update({"REQUIRE_API_KEY": "1", "TTS_API_KEY": self.network.api_key})
        else:
            env.pop("REQUIRE_API_KEY", None)
            env.pop("TTS_API_KEY", None)
        return env

    def _gateway_env(self, tools_path: list[str], port: int, upstream_port: int, lan: bool) -> dict[str, str]:
        env = dict(os.environ)
        env.update(
            {
                "GATEWAY_HOST": "0.0.0.0" if lan else "127.0.0.1",
                "GATEWAY_PORT": str(port),
                "GATEWAY_UPSTREAM": f"http://127.0.0.1:{upstream_port}",
                "GATEWAY_VOICES_DIR": str(self.src / "voices"),
                "GATEWAY_ENGINES": ",".join(self.models.engines),
                "GATEWAY_QWEN_SIZE": self.models.qwen_size,
                "PATH": os.pathsep.join([str(self.engines_venv / "bin"), *tools_path, env.get("PATH", "")]),
                "PYTHONUNBUFFERED": "1",
                "TOKENIZERS_PARALLELISM": "false",
            }
        )
        env.pop("PYTHONPATH", None)
        if lan:
            env.update({"REQUIRE_API_KEY": "1", "TTS_API_KEY": self.network.api_key})
        else:
            env.pop("REQUIRE_API_KEY", None)
            env.pop("TTS_API_KEY", None)
        return env

    def _start_gateway(self, tools_path: list[str], port: int, upstream_port: int, lan: bool) -> None:
        if self.gateway_log.exists():
            self.gateway_log.replace(self.logs / "gateway.previous.log")
        log = open(self.gateway_log, "wb")  # noqa: SIM115 - bleibt für die Laufzeit offen
        self.gateway_proc = subprocess.Popen(
            [str(self.engines_venv / "bin" / "python"), "-I", str(GATEWAY_SCRIPT)],
            cwd=self.home,
            env=self._gateway_env(tools_path, port, upstream_port, lan),
            stdout=log,
            stderr=subprocess.STDOUT,
            start_new_session=True,
        )

    def _start_server(self, tools_path: list[str], gateway: bool = False) -> None:
        lan = self.network.enabled and bool(self.network.api_key)
        port = _free_port(self.network.port if lan else self.preferred_port, "0.0.0.0" if lan else "127.0.0.1")
        self._lan_active = lan
        self._gateway_active = gateway
        self.state.url = f"http://127.0.0.1:{port}"
        public_port = port
        if gateway:
            # Tontaube nur lokal und ohne Key; das Gateway übernimmt Adresse, Port und API-Key.
            port = next(p for p in range(public_port + 100, public_port + 200)
                        if p != public_port and _free_port(p) == p)
            lan_tontaube = False
        else:
            lan_tontaube = lan
        self._cache_baseline = _dir_size(self._hf_cache)
        self.logs.mkdir(parents=True, exist_ok=True)
        if self.log_file.exists():
            self.log_file.replace(self.logs / "server.previous.log")
        self._set("starting", "Server startet. Beim ersten Start werden die Modelle heruntergeladen …", "", None)
        exe = self.venv / "bin" / "tontaube"
        log = open(self.log_file, "wb")  # noqa: SIM115 - bleibt für die Laufzeit offen
        self.proc = subprocess.Popen(
            [str(exe), "serve"],
            cwd=self.src,
            env=self._server_env(tools_path, port, lan_tontaube),
            stdout=log,
            stderr=subprocess.STDOUT,
            start_new_session=True,
        )
        if gateway:
            self._start_gateway(tools_path, public_port, port, lan)

    def _wait_ready(self) -> None:
        while not self._stop.is_set():
            proc = self.proc
            if proc is None:
                return
            code = proc.poll()
            if code is not None:
                raise RuntimeError(f"Server wurde beendet (Code {code}). Details im Server-Log unten.")
            gw = self.gateway_proc
            if gw is not None and gw.poll() is not None:
                raise GatewayError(
                    f"Das Modell-Gateway (Chatterbox/Qwen3) wurde beendet (Code {gw.returncode}). "
                    "Details im Gateway-Log unten; zum Umgehen die Zusatzmodelle ausschalten."
                )
            try:
                r = requests.get(self.state.url + "/readyz", timeout=3)
                if r.ok:
                    secs = int(time.time() - self.state.started_at)
                    note = " Die Apple-GPU ließ sich nicht nutzen, er läuft auf der CPU." if self._fell_back else ""
                    self._set("ready", f"Server bereit (Start in {secs // 60}:{secs % 60:02d} min).{note}", "", None)
                    return
            except requests.RequestException:
                pass
            time.sleep(2)
