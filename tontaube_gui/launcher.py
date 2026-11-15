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

SERVER_VERSION = "v1.1.0"
SERVER_TARBALL = f"https://codeload.github.com/craitech/tontaube/tar.gz/refs/tags/{SERVER_VERSION}"
ASSETS = Path(__file__).resolve().parent / "server_assets"
PATCH_FILE = ASSETS / "tontaube-v1.1.0-apple-silicon.patch"
REQUIREMENTS_FILE = ASSETS / "requirements-server-macos.txt"

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


def _free_port(preferred: int) -> int:
    for port in [preferred, *range(preferred + 1, preferred + 50)]:
        with socket.socket() as s:
            try:
                s.bind(("127.0.0.1", port))
                return port
            except OSError:
                continue
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
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


@dataclass
class ServerState:
    phase: str = "idle"  # idle, setup, starting, ready, error, stopped
    message: str = "Noch nicht gestartet."
    detail: str = ""
    progress: float | None = None
    url: str = ""
    started_at: float = field(default_factory=time.time)


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
        self.logs = self.home / "logs"
        self.log_file = self.logs / "server.log"
        self.preferred_port = port
        self.dtype = dtype or os.environ.get("LITE_CPU_DTYPE", "bfloat16")
        self.device = device or os.environ.get("LITE_DEVICE") or default_device()
        self._fell_back = False
        self.state = ServerState()
        self.proc: subprocess.Popen | None = None
        self._lines: deque[str] = deque(maxlen=400)
        self._lock = threading.Lock()
        self._thread: threading.Thread | None = None
        self._stop = threading.Event()
        self._hf_cache = Path(os.environ.get("HF_HOME", Path.home() / ".cache" / "huggingface")) / "hub"
        self._cache_baseline = 0
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
        if self.log_file.exists():
            try:
                with open(self.log_file, "rb") as fh:
                    fh.seek(0, os.SEEK_END)
                    size = fh.tell()
                    fh.seek(max(0, size - 20000))
                    lines += ["— Server-Log —"] + fh.read().decode("utf-8", "replace").splitlines()[-n:]
            except OSError:
                pass
        return "\n".join(lines[-n:])

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
            lines.append(
                f"Server: `{s.url}` · Rechenwerk: {self.DEVICE_LABELS.get(self.device, self.device)} · "
                f"Genauigkeit: `{self.dtype}`"
            )
        return "\n\n".join(lines)

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
        if dtype:
            self.dtype = dtype
        if device:
            self.device = device
            self._fell_back = False
        self.start()

    def reinstall(self) -> None:
        self.stop()
        for marker in (self.src / ".tontaube-patched", self.venv / ".tontaube-installed"):
            marker.unlink(missing_ok=True)
        self.start()

    def stop(self) -> None:
        self._stop.set()
        proc = self.proc
        if proc and proc.poll() is None:
            self._log("Server wird beendet …")
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
        self.proc = None
        if self.state.phase in ("ready", "starting", "setup"):
            self._set("stopped", "Server gestoppt.", "", None)

    def _run(self) -> None:
        try:
            self.home.mkdir(parents=True, exist_ok=True)
            self._ensure_source()
            self._ensure_venv()
            tools_path = self._ensure_system_tools()
            if self._stop.is_set():
                return
            self._start_server(tools_path)
            try:
                self._wait_ready()
            except RuntimeError:
                if self.device != "mps" or self._stop.is_set():
                    raise
                # Metal-Pfad gescheitert: einmal automatisch auf der CPU versuchen.
                self._log("Start auf der Apple-GPU fehlgeschlagen, versuche es auf der CPU …")
                self.device, self._fell_back = "cpu", True
                self._start_server(tools_path)
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

    def _server_env(self, tools_path: list[str], port: int) -> dict[str, str]:
        env = dict(os.environ)
        env.update(
            {
                "TTS_BACKEND": "lite",
                "LITE_DEVICE": self.device,
                "PYTORCH_ENABLE_MPS_FALLBACK": "1",
                "LITE_CPU_DTYPE": self.dtype,
                "TTS_HOST": "127.0.0.1",
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
        return env

    def _start_server(self, tools_path: list[str]) -> None:
        port = _free_port(self.preferred_port)
        self.state.url = f"http://127.0.0.1:{port}"
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
            env=self._server_env(tools_path, port),
            stdout=log,
            stderr=subprocess.STDOUT,
            start_new_session=True,
        )

    def _wait_ready(self) -> None:
        while not self._stop.is_set():
            proc = self.proc
            if proc is None:
                return
            code = proc.poll()
            if code is not None:
                raise RuntimeError(f"Server wurde beendet (Code {code}). Details im Server-Log unten.")
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
