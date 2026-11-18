"""Modell-Gateway: Tontaube, Chatterbox und Qwen3-TTS unter einer Adresse.

Läuft in einer eigenen Python-Umgebung (``engines-venv``, mit ``mlx-audio``) vor dem
Tontaube-Server und spricht dieselbe API:

* ``GET  /models``  – welche Modelle es gibt und was sie können (neu)
* ``POST /predict`` / ``POST /stream`` – mit ``"model": "chatterbox" | "chatterbox-turbo" | "qwen3"``
  rechnet das Gateway selbst (MLX auf der Apple-GPU), sonst wird an Tontaube weitergeleitet.
* alles andere (``/readyz``, ``/controls``, ``/docs`` …) geht unverändert an Tontaube.

Das SSE-Format von ``/stream`` entspricht Tontaube (``data: {"index", "audio_b64", "done"}``
mit MP3-Stücken), damit Desktop-Oberfläche und Android-App unverändert streamen können.

Die Datei ist bewusst eigenständig (keine Importe aus ``tontaube_gui``), weil sie mit dem
Python der Engine-Umgebung gestartet wird::

    python model_gateway.py   # Einstellungen über Umgebungsvariablen, siehe Settings
"""

from __future__ import annotations

import asyncio
import base64
import collections
import fnmatch
import gc
import hashlib
import io
import json
import logging
import math
import os
import re
import secrets
import shutil
import subprocess
import threading
import time
import wave
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Callable, Iterator

import numpy as np
from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse, StreamingResponse
from starlette.background import BackgroundTask

log = logging.getLogger("model_gateway")

# ---------------------------------------------------------------------------
# Modellbeschreibungen
# ---------------------------------------------------------------------------

# Sprachnamen wie bei Tontaube (Feld ``language``) → Codes der Modelle.
CHATTERBOX_LANGS = {
    "german": "de", "english": "en", "spanish": "es", "french": "fr", "italian": "it", "dutch": "nl",
    "portuguese": "pt", "danish": "da", "finnish": "fi", "greek": "el", "hebrew": "he", "hindi": "hi",
    "japanese": "ja", "korean": "ko", "malay": "ms", "norwegian": "no", "polish": "pl", "russian": "ru",
    "swedish": "sv", "swahili": "sw", "turkish": "tr", "chinese": "zh", "arabic": "ar",
}
QWEN_LANGS = ["german", "english", "french", "spanish", "italian", "portuguese", "russian", "chinese",
              "japanese", "korean"]
QWEN_SPEAKERS = [
    {"id": "Ryan", "label": "Ryan (männlich, englisch)"},
    {"id": "Aiden", "label": "Aiden (männlich, englisch)"},
    {"id": "Vivian", "label": "Vivian (weiblich, chinesisch)"},
    {"id": "Serena", "label": "Serena (weiblich, chinesisch)"},
    {"id": "Uncle_Fu", "label": "Uncle Fu (männlich, chinesisch)"},
    {"id": "Dylan", "label": "Dylan (männlich, Peking)"},
    {"id": "Eric", "label": "Eric (männlich, Sichuan)"},
    {"id": "Ono_Anna", "label": "Ono Anna (weiblich, japanisch)"},
    {"id": "Sohee", "label": "Sohee (weiblich, koreanisch)"},
]
QWEN_DEFAULT_SPEAKER = "Ryan"

# Paralinguistische Tags von Chatterbox Turbo (nur Englisch).
TURBO_TAGS = ["[laugh]", "[chuckle]", "[sigh]", "[gasp]", "[cough]", "[clear throat]", "[sniff]", "[groan]",
              "[shush]"]
_TAG_RE = re.compile(r"\[(?:" + "|".join(re.escape(t[1:-1]) for t in TURBO_TAGS) + r")\]", re.IGNORECASE)


def strip_tags(text: str) -> str:
    """Entfernt Chatterbox-Tags (für Modelle, die sie sonst vorlesen würden)."""
    return re.sub(r"[ \t]{2,}", " ", _TAG_RE.sub("", text)).strip()


def _param(name: str, label: str, lo: float, hi: float, step: float, default: float, description: str) -> dict:
    return {"name": name, "label": label, "min": lo, "max": hi, "step": step, "default": default,
            "description": description}


MODELS: dict[str, dict[str, Any]] = {
    "chatterbox": {
        "id": "chatterbox",
        "name": "Chatterbox Multilingual",
        "description": "Resemble AI, 23 Sprachen inkl. Deutsch, Stimmen klonen, Emotions-Regler. Keine Tags. "
                       "Ohne gewählte Stimme spricht eine mitgelieferte Stimme der Sprache (z. B. Juri).",
        "license": "MIT",
        "languages": [lang for lang in CHATTERBOX_LANGS],
        "voice_cloning": True,
        "speakers": [],
        "instruct": False,
        "tags": [],
        "params": [
            _param("exaggeration", "Emotion", 0.25, 2.0, 0.05, 0.5, "0,5 = neutral, höher = ausdrucksstärker"),
            _param("cfg_weight", "Führung (CFG)", 0.0, 1.0, 0.05, 0.5,
                   "niedriger = ruhiger/langsamer, bei hoher Emotion etwa 0,3"),
            _param("temperature", "Temperatur", 0.05, 2.0, 0.05, 0.8, "Zufälligkeit"),
        ],
    },
    "chatterbox-turbo": {
        "id": "chatterbox-turbo",
        "name": "Chatterbox Turbo (Englisch, Tags)",
        "description": "Resemble AI, nur Englisch, schnell. Versteht Tags wie [laugh] oder [sigh] im Text.",
        "license": "MIT",
        "languages": ["english"],
        "voice_cloning": True,
        "speakers": [],
        "instruct": False,
        "tags": TURBO_TAGS,
        "params": [
            _param("temperature", "Temperatur", 0.05, 2.0, 0.05, 0.8, "Zufälligkeit"),
        ],
    },
    "qwen3": {
        "id": "qwen3",
        "name": "Qwen3-TTS",
        "description": "Alibaba, 10 Sprachen inkl. Deutsch. Stimme klonen, fertige Sprecher mit Stil-Anweisung "
                       "oder Stimme per Beschreibung.",
        "license": "Apache-2.0",
        "languages": QWEN_LANGS,
        "voice_cloning": True,
        "speakers": QWEN_SPEAKERS,
        "instruct": True,
        "tags": [],
        "params": [
            _param("temperature", "Temperatur", 0.05, 2.0, 0.05, 0.9, "Zufälligkeit"),
        ],
    },
}


# ---------------------------------------------------------------------------
# Einstellungen
# ---------------------------------------------------------------------------

def _env_list(name: str, default: str) -> list[str]:
    return [s.strip() for s in os.environ.get(name, default).split(",") if s.strip()]


@dataclass
class Settings:
    host: str = "127.0.0.1"
    port: int = 8080
    upstream: str = "http://127.0.0.1:8090"
    api_key: str = ""
    voices_dir: Path | None = None
    engines: list[str] = field(default_factory=lambda: list(MODELS))
    max_loaded: int = 2
    fake: bool = False
    qwen_size: str = "1.7B"
    prefetch: bool = True

    @classmethod
    def from_env(cls) -> "Settings":
        require = os.environ.get("REQUIRE_API_KEY", "0") == "1"
        key = os.environ.get("TTS_API_KEY", "") if require else ""
        if require and not key:
            raise RuntimeError("TTS_API_KEY fehlt, obwohl REQUIRE_API_KEY=1 gesetzt ist.")
        voices = os.environ.get("GATEWAY_VOICES_DIR")
        return cls(
            host=os.environ.get("GATEWAY_HOST", "127.0.0.1"),
            port=int(os.environ.get("GATEWAY_PORT", "8080")),
            upstream=os.environ.get("GATEWAY_UPSTREAM", "http://127.0.0.1:8090").rstrip("/"),
            api_key=key,
            voices_dir=Path(voices).resolve() if voices else None,
            engines=[e for e in _env_list("GATEWAY_ENGINES", ",".join(MODELS)) if e in MODELS],
            max_loaded=max(1, int(os.environ.get("GATEWAY_MAX_LOADED", "2"))),
            fake=os.environ.get("GATEWAY_FAKE_ENGINES", "0") == "1",
            qwen_size=os.environ.get("GATEWAY_QWEN_SIZE", "1.7B"),
            prefetch=os.environ.get("GATEWAY_PREFETCH", "1") != "0",
        )


def repo_candidates(settings: Settings, kind: str) -> list[str]:
    """Hugging-Face-Repos (MLX-Gewichte) je Modellvariante; das erste vorhandene wird genommen."""
    env = os.environ.get("GATEWAY_REPO_" + kind.upper().replace("-", "_"))
    if env:
        return [env]
    size = settings.qwen_size
    table = {
        "chatterbox": ["mlx-community/chatterbox-multilingual-v3"],
        "chatterbox-turbo": ["mlx-community/chatterbox-turbo-fp16", "mlx-community/chatterbox-turbo-8bit",
                             "mlx-community/chatterbox-turbo-4bit"],
        "qwen3-base": [f"mlx-community/Qwen3-TTS-12Hz-{size}-Base-bf16"],
        "qwen3-custom": [f"mlx-community/Qwen3-TTS-12Hz-{size}-CustomVoice-bf16"],
        "qwen3-design": ["mlx-community/Qwen3-TTS-12Hz-1.7B-VoiceDesign-bf16"],
    }
    return table[kind]


# ---------------------------------------------------------------------------
# Text, Audio
# ---------------------------------------------------------------------------

_SENT_RE = re.compile(r"(?<=[.!?…:;])\s+|\n+")


def split_sentences(text: str, max_chars: int = 280) -> list[str]:
    """Teilt Text in Sätze und fasst kurze zusammen (Chatterbox hat ein Längenlimit pro Aufruf)."""
    parts = [p.strip() for p in _SENT_RE.split(text) if p and p.strip()]
    out: list[str] = []
    for p in parts:
        while len(p) > max_chars:  # überlange Sätze an Kommas/Leerzeichen trennen
            cut = max(p.rfind(", ", 0, max_chars), p.rfind(" ", 0, max_chars))
            cut = cut if cut > max_chars // 3 else max_chars
            out.append(p[:cut + 1].strip())
            p = p[cut + 1:].strip()
        if out and len(out[-1]) + len(p) + 1 <= max_chars // 2:
            out[-1] = f"{out[-1]} {p}"
        elif p:
            out.append(p)
    return out


def request_text(body: dict) -> str:
    t = body.get("text", "")
    if isinstance(t, list):
        t = " ".join(str(x) for x in t)
    return str(t)


def to_int16(audio: np.ndarray) -> bytes:
    a = np.clip(np.asarray(audio, dtype=np.float32).reshape(-1), -1.0, 1.0)
    return (a * 32767.0).astype("<i2").tobytes()


def wav_bytes(pcm16: bytes, sr: int) -> bytes:
    out = io.BytesIO()
    with wave.open(out, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(sr)
        w.writeframes(pcm16)
    return out.getvalue()


class Mp3Writer:
    """MP3-Kodierung in Stücken (lameenc), wie Tontaubes ``Mp3StreamWriter``."""

    def __init__(self, sr: int, kbps: int = 64):
        import lameenc

        self.enc = lameenc.Encoder()
        self.enc.set_bit_rate(kbps)
        self.enc.set_in_sample_rate(sr)
        self.enc.set_channels(1)
        self.enc.set_quality(2)

    def write(self, pcm16: bytes) -> bytes:
        return bytes(self.enc.encode(pcm16))

    def close(self) -> bytes:
        return bytes(self.enc.flush())


def bitrate_kbps(raw: Any, default: int = 64) -> int:
    if raw in (None, ""):
        return default
    m = re.match(r"^\s*(\d+)\s*k?\s*$", str(raw).lower())
    if not m:
        return default
    v = int(m.group(1))
    v = v // 1000 if v >= 1000 else v
    allowed = [32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192]
    return min(allowed, key=lambda a: abs(a - v))


def decode_audio(data: bytes, sr: int, max_seconds: float = 20.0) -> np.ndarray:
    """Referenzaudio (mp3, wav, m4a …) → float32 mono mit ``sr`` Hz. ffmpeg, sonst miniaudio."""
    ffmpeg = shutil.which("ffmpeg") or next(
        (p for p in ("/opt/homebrew/bin/ffmpeg", "/usr/local/bin/ffmpeg") if os.path.exists(p)), None)
    if ffmpeg:
        proc = subprocess.run(
            [ffmpeg, "-v", "error", "-i", "pipe:0", "-t", str(max_seconds), "-ac", "1", "-ar", str(sr),
             "-f", "f32le", "pipe:1"],
            input=data, capture_output=True, timeout=60,
        )
        if proc.returncode == 0 and proc.stdout:
            return np.frombuffer(proc.stdout, dtype="<f4").copy()
        log.warning("ffmpeg konnte das Referenzaudio nicht lesen: %s", proc.stderr.decode(errors="replace")[-300:])
    pcm = _decode_wav16(data, sr)
    if pcm is not None:
        return pcm[: int(max_seconds * sr)]
    import miniaudio

    dec = miniaudio.decode(data, output_format=miniaudio.SampleFormat.FLOAT32, nchannels=1, sample_rate=sr)
    arr = np.frombuffer(dec.samples, dtype=np.float32).copy()
    return arr[: int(max_seconds * sr)]


def _decode_wav16(data: bytes, sr: int) -> np.ndarray | None:
    """16-bit-PCM-WAV ohne ffmpeg/miniaudio lesen (mono, linear auf ``sr`` umgerechnet); sonst None."""
    import io
    import wave

    try:
        with wave.open(io.BytesIO(data)) as w:
            if w.getsampwidth() != 2:
                return None
            ch, rate = w.getnchannels(), w.getframerate()
            x = np.frombuffer(w.readframes(w.getnframes()), dtype="<i2").astype(np.float32) / 32768.0
    except (wave.Error, EOFError):
        return None
    if ch > 1:
        x = x[: len(x) // ch * ch].reshape(-1, ch).mean(axis=1)
    if rate != sr and x.size:
        n = int(round(x.size * sr / rate))
        x = np.interp(np.arange(n) * (rate / sr), np.arange(x.size), x).astype(np.float32)
    return x


class RequestError(Exception):
    """Fehler in der Anfrage (wird als 422 mit deutscher Meldung beantwortet)."""


# ---------------------------------------------------------------------------
# Engines
# ---------------------------------------------------------------------------

@dataclass
class Job:
    """Eine Synthese-Anfrage für ein Gateway-Modell (bereits geprüft)."""

    model: str
    text: str
    language: str
    ref_audio: bytes | None = None
    ref_key: str = ""
    ref_text: str | None = None
    speaker: str | None = None
    instruct: str | None = None
    params: dict[str, float] = field(default_factory=dict)
    seed: int | None = None

    @property
    def variant(self) -> str:
        """Welche Gewichte gebraucht werden (Qwen hat drei Varianten)."""
        if self.model != "qwen3":
            return self.model
        if self.ref_audio:
            return "qwen3-base"
        if self.speaker or not self.instruct:
            return "qwen3-custom"
        return "qwen3-design"


def _num(body: dict, key: str, lo: float, hi: float) -> float | None:
    v = body.get(key)
    if v is None or v == "":
        return None
    try:
        f = float(v)
    except (TypeError, ValueError) as exc:
        raise RequestError(f"'{key}' muss eine Zahl sein.") from exc
    if not math.isfinite(f):
        raise RequestError(f"'{key}' muss eine Zahl sein.")
    return min(hi, max(lo, f))


def default_voice(voices_dir: Path | None, language: str) -> Path | None:
    """Mitgelieferte Tontaube-Stimme für eine Sprache (``voices/manifest.json``), sonst Marcus."""
    if voices_dir is None:
        return None
    iso = CHATTERBOX_LANGS.get(language, "")
    try:
        manifest = json.loads((voices_dir / "manifest.json").read_text(encoding="utf-8")).get("voices") or {}
    except (OSError, ValueError, AttributeError):
        manifest = {}
    names = sorted(n for n, m in manifest.items() if isinstance(m, dict) and m.get("language") == iso)
    for name in [*names, "Marcus.mp3"]:
        path = voices_dir / "samples" / name
        if path.is_file():
            return path
    return None


def parse_job(body: dict, settings: Settings) -> Job:
    model = str(body.get("model") or "")
    if model not in settings.engines:
        raise RequestError(f"Modell '{model}' ist auf diesem Server nicht eingeschaltet.")
    spec = MODELS[model]
    text = request_text(body)
    text = text if model == "chatterbox-turbo" else strip_tags(text)
    if not text.strip():
        raise RequestError("Bitte einen Text eingeben.")
    language = str(body.get("language") or ("english" if model == "chatterbox-turbo" else "german")).lower()
    if language not in spec["languages"]:
        names = ", ".join(spec["languages"])
        raise RequestError(f"{spec['name']} kann '{language}' nicht. Möglich: {names}.")

    ref: bytes | None = None
    ref_key = ""
    if body.get("voice_audio_b64"):
        try:
            ref = base64.b64decode(str(body["voice_audio_b64"]), validate=False)
        except ValueError as exc:
            raise RequestError("voice_audio_b64 ist kein gültiges Base64.") from exc
        ref_key = "b64:" + hashlib.sha1(ref).hexdigest()
    elif body.get("voice_path"):
        if settings.voices_dir is None:
            raise RequestError("Mitgelieferte Stimmen sind auf diesem Server nicht verfügbar.")
        path = (settings.voices_dir / str(body["voice_path"])).resolve()
        if settings.voices_dir not in path.parents or not path.is_file():
            raise RequestError(f"Stimme '{body['voice_path']}' nicht gefunden.")
        ref = path.read_bytes()
        ref_key = f"path:{path}:{path.stat().st_mtime_ns}"

    if ref is None and model == "chatterbox":
        # Das MLX-Repo von Chatterbox Multilingual bringt keine Standardstimme mit (conds.safetensors fehlt):
        # ohne gewählte Stimme eine mitgelieferte Tontaube-Stimme der passenden Sprache als Referenz nehmen.
        path = default_voice(settings.voices_dir, language)
        if path is None:
            raise RequestError("Chatterbox Multilingual braucht eine Stimme (mitgelieferte oder eigene).")
        ref = path.read_bytes()
        ref_key = f"path:{path}:{path.stat().st_mtime_ns}"

    params: dict[str, float] = {}
    for p in spec["params"]:
        v = _num(body, p["name"], p["min"], p["max"])
        if v is not None:
            params[p["name"]] = v
    seed = body.get("seed")
    speaker = (str(body.get("speaker") or "").strip() or None) if model == "qwen3" else None
    if speaker and speaker not in {s["id"] for s in QWEN_SPEAKERS}:
        raise RequestError(f"Unbekannter Qwen3-Sprecher '{speaker}'.")
    instruct = (str(body.get("instruct") or "").strip() or None) if model == "qwen3" else None
    ref_text = (str(body.get("ref_text") or "").strip() or None) if model == "qwen3" else None
    return Job(model=model, text=text, language=language, ref_audio=ref, ref_key=ref_key, ref_text=ref_text,
               speaker=speaker, instruct=instruct, params=params,
               seed=int(seed) if isinstance(seed, (int, float)) and not isinstance(seed, bool) else None)


def _model_of(variant: str) -> str:
    return "qwen3" if variant.startswith("qwen3") else variant


WHISPER_REPOS = ["mlx-community/whisper-large-v3-turbo-asr-fp16", "mlx-community/whisper-large-v3-turbo"]


def whisper_repos() -> list[str]:
    """Whisper-Gewichte fürs Transkript (``GATEWAY_WHISPER_REPO`` hat Vorrang)."""
    own = os.environ.get("GATEWAY_WHISPER_REPO", "").strip()
    return ([own] if own else []) + [r for r in WHISPER_REPOS if r != own]


class Backend:
    """Rechnet Audio für einen Job. Liefert float32-Stücke (mono) mit :attr:`sample_rate`."""

    sample_rate = 24000

    def loaded(self) -> list[str]:
        return []

    def ensure(self, variant: str) -> None:  # lädt Gewichte (kann dauern)
        pass

    def synthesize(self, job: Job, cancel: threading.Event) -> Iterator[np.ndarray]:
        raise NotImplementedError

    def transcribe(self, audio_16k: np.ndarray, language: str | None) -> str:
        """Transkript eines Referenzaudios (float32 mono 16 kHz), z. B. für den Qwen3-Klon."""
        raise NotImplementedError


class FakeBackend(Backend):
    """Sinustöne statt Sprache (Tests, CI)."""

    def __init__(self) -> None:
        self._loaded: list[str] = []
        self.calls: list[Job] = []

    def loaded(self) -> list[str]:
        return list(self._loaded)

    def ensure(self, variant: str) -> None:
        if variant not in self._loaded:
            self._loaded.append(variant)

    def synthesize(self, job: Job, cancel: threading.Event) -> Iterator[np.ndarray]:
        self.ensure(job.variant)
        self.calls.append(job)
        freq = {"chatterbox": 180, "chatterbox-turbo": 220, "qwen3": 260}[job.model]
        for sentence in split_sentences(job.text):
            if cancel.is_set():
                return
            n = int(self.sample_rate * min(0.05 * len(sentence) + 0.2, 6))
            t = np.arange(n, dtype=np.float32) / self.sample_rate
            yield (0.25 * np.sin(2 * np.pi * freq * t)).astype(np.float32)

    def transcribe(self, audio_16k: np.ndarray, language: str | None) -> str:
        return f"Testtranskript ({audio_16k.size / 16000:.1f} s, {language or 'auto'})."


class MlxBackend(Backend):
    """Echte Modelle über ``mlx-audio`` (Apple-GPU). Lädt Gewichte bei Bedarf, hält höchstens N im Speicher."""

    def __init__(self, settings: Settings):
        self.settings = settings
        self.models: collections.OrderedDict[str, Any] = collections.OrderedDict()
        self.repos: dict[str, str] = {}
        self._refs: collections.OrderedDict[str, Any] = collections.OrderedDict()
        self._whisper: Any = None

    def loaded(self) -> list[str]:
        return list(self.models)

    def transcribe(self, audio_16k: np.ndarray, language: str | None) -> str:
        if self._whisper is None:
            from mlx_audio.stt.utils import load_model as load_stt

            errors = []
            for repo in whisper_repos():
                try:
                    log.info("Lade Whisper (%s) …", repo)
                    self._whisper = load_stt(repo)
                    break
                except Exception as exc:  # noqa: BLE001 - nächsten Kandidaten versuchen
                    log.warning("Laden von %s fehlgeschlagen: %s", repo, exc)
                    errors.append(f"{repo}: {exc}")
            else:
                raise RuntimeError("Whisper konnte nicht geladen werden. " + " | ".join(errors))
        import mlx.core as mx

        res = self._whisper.generate(mx.array(audio_16k), language=language, verbose=False,
                                     condition_on_previous_text=False)
        return " ".join(str(getattr(res, "text", res) or "").split())

    def ensure(self, variant: str) -> Any:
        if variant in self.models:
            self.models.move_to_end(variant)
            return self.models[variant]
        from mlx_audio.tts.utils import load_model

        while len(self.models) >= self.settings.max_loaded:
            old, _ = self.models.popitem(last=False)
            log.info("Entlade %s", old)
            gc.collect()
            self._clear_cache()
        errors = []
        for repo in repo_candidates(self.settings, variant):
            try:
                log.info("Lade %s (%s) …", variant, repo)
                t0 = time.time()
                model = load_model(repo)
                log.info("%s geladen in %.1f s", repo, time.time() - t0)
                self.models[variant] = model
                self.repos[variant] = repo
                return model
            except Exception as exc:  # noqa: BLE001 - nächsten Kandidaten versuchen
                log.warning("Laden von %s fehlgeschlagen: %s", repo, exc)
                errors.append(f"{repo}: {exc}")
        raise RuntimeError("Modell konnte nicht geladen werden. " + " | ".join(errors))

    @staticmethod
    def _clear_cache() -> None:
        try:
            import mlx.core as mx

            (getattr(mx, "clear_cache", None) or mx.metal.clear_cache)()
        except Exception:  # noqa: BLE001
            pass

    def _ref(self, job: Job, sr: int) -> Any:
        if not job.ref_audio:
            return None
        import mlx.core as mx

        key = f"{job.ref_key}@{sr}"
        if key not in self._refs:
            self._refs[key] = mx.array(decode_audio(job.ref_audio, sr))
            while len(self._refs) > 8:
                self._refs.popitem(last=False)
        self._refs.move_to_end(key)
        return self._refs[key]

    def synthesize(self, job: Job, cancel: threading.Event) -> Iterator[np.ndarray]:
        import mlx.core as mx

        model = self.ensure(job.variant)
        sr = int(getattr(model, "sample_rate", 24000))
        self.sample_rate = sr
        if job.seed is not None:
            mx.random.seed(job.seed)
        ref = self._ref(job, sr)
        temp = job.params.get("temperature")

        if job.model == "chatterbox":
            kwargs: dict[str, Any] = {
                "lang_code": CHATTERBOX_LANGS[job.language],
                "exaggeration": job.params.get("exaggeration", 0.5),
                "cfg_weight": job.params.get("cfg_weight", 0.5),
                "verbose": False,
            }
            if temp is not None:
                kwargs["temperature"] = temp
            if ref is not None:
                kwargs["ref_audio"] = ref
            for sentence in split_sentences(job.text):
                if cancel.is_set():
                    return
                for res in model.generate(sentence, **kwargs):
                    yield np.array(res.audio, dtype=np.float32).reshape(-1)
            return

        if job.model == "chatterbox-turbo":
            kwargs = {"stream": True, "streaming_interval": 1.5}
            if temp is not None:
                kwargs["temperature"] = temp
            if ref is not None:
                kwargs.update(ref_audio=ref, sample_rate=sr)
            for sentence in split_sentences(job.text, max_chars=400):
                for res in model.generate(sentence, **kwargs):
                    if cancel.is_set():
                        return
                    yield np.array(res.audio, dtype=np.float32).reshape(-1)
            return

        # Qwen3-TTS
        kwargs = {"lang_code": job.language, "stream": True, "streaming_interval": 1.6, "verbose": False,
                  "split_pattern": ""}
        if temp is not None:
            kwargs["temperature"] = temp
        variant = job.variant
        if variant == "qwen3-base":
            kwargs.update(ref_audio=ref, ref_text=job.ref_text)
        elif variant == "qwen3-custom":
            kwargs.update(voice=job.speaker or QWEN_DEFAULT_SPEAKER, instruct=job.instruct)
        else:
            kwargs.update(instruct=job.instruct)
        for sentence in split_sentences(job.text, max_chars=500):
            for res in model.generate(sentence, **kwargs):
                if cancel.is_set():
                    return
                yield np.array(res.audio, dtype=np.float32).reshape(-1)


class Worker:
    """Ein einziger Rechen-Thread für alle Gateway-Modelle (MLX ist nicht für parallele Aufrufe gedacht)."""

    def __init__(self, backend: Backend):
        self.backend = backend
        self.lock = threading.Lock()
        self.loading: str | None = None

    def run(self, job: Job, cancel: threading.Event, emit: Callable[[str, Any], None]) -> None:
        """Läuft im Thread. ``emit("loading"|"audio"|"error"|"end", wert)``."""
        try:
            with self.lock:
                if job.variant not in self.backend.loaded():
                    self.loading = job.variant
                    emit("loading", job.variant)
                    self.backend.ensure(job.variant)
                    self.loading = None
                for chunk in self.backend.synthesize(job, cancel):
                    if cancel.is_set():
                        break
                    if chunk.size:
                        emit("audio", chunk)
        except Exception as exc:  # noqa: BLE001 - an den Client melden
            log.exception("Synthese fehlgeschlagen")
            emit("error", f"{type(exc).__name__}: {exc}")
        finally:
            self.loading = None
            emit("end", None)


# ---------------------------------------------------------------------------
# Vorladen (Download der Gewichte direkt nach dem Start)
# ---------------------------------------------------------------------------

# Welche Gewichte je Modell vorab geladen werden, in dieser Reihenfolge. Qwen3 braucht je nach Anfrage
# eine von drei Varianten: fertige Sprecher (Standard), Stimme klonen, Stimme per Beschreibung.
PREFETCH_VARIANTS = {
    "chatterbox": ["chatterbox"],
    "chatterbox-turbo": ["chatterbox-turbo"],
    "qwen3": ["qwen3-custom", "qwen3-base", "qwen3-design"],
}
VARIANT_LABELS = {
    "chatterbox": "Chatterbox Multilingual",
    "chatterbox-turbo": "Chatterbox Turbo",
    "qwen3-custom": "Qwen3-TTS (Sprecher)",
    "qwen3-base": "Qwen3-TTS (Stimme klonen)",
    "qwen3-design": "Qwen3-TTS (Stimme per Beschreibung)",
}
# Dieselben Dateimuster wie mlx-audio beim Laden (mlx_audio.utils.DEFAULT_ALLOW_PATTERNS).
ALLOW_PATTERNS = ["*.json", "*.safetensors", "*.py", "*.model", "*.tiktoken", "*.txt", "*.jinja", "*.jsonl",
                  "*.yaml", "*.npz", "*.pth"]


def _allow_patterns() -> list[str]:
    try:
        from mlx_audio.utils import DEFAULT_ALLOW_PATTERNS

        return list(DEFAULT_ALLOW_PATTERNS)
    except Exception:  # noqa: BLE001 - ohne mlx-audio (Tests) die Kopie nehmen
        return ALLOW_PATTERNS


def _hub_cache() -> Path:
    try:
        from huggingface_hub import constants

        return Path(constants.HF_HUB_CACHE)
    except Exception:  # noqa: BLE001
        home = os.environ.get("HF_HOME") or str(Path.home() / ".cache" / "huggingface")
        return Path(os.environ.get("HF_HUB_CACHE") or Path(home) / "hub")


def _tree_size(path: Path) -> int:
    total = 0
    for root, _dirs, files in os.walk(path):
        for f in files:
            try:
                total += os.lstat(os.path.join(root, f)).st_size
            except OSError:
                pass
    return total


def hub_repo_size(repo: str) -> int:
    """Gesamtgröße der Dateien, die mlx-audio von ``repo`` lädt (0, wenn unbekannt)."""
    from huggingface_hub import HfApi

    info = HfApi().model_info(repo, files_metadata=True)
    patterns = _allow_patterns()
    return sum(int(s.size or 0) for s in info.siblings or []
               if any(fnmatch.fnmatch(s.rfilename, p) for p in patterns))


def hub_download(repo: str) -> None:
    from huggingface_hub import snapshot_download

    snapshot_download(repo, allow_patterns=_allow_patterns())


def hub_cached_bytes(repo: str) -> int:
    return _tree_size(_hub_cache() / ("models--" + repo.replace("/", "--")))


def _gb(n: int) -> str:
    return f"{n / 1e9:.1f} GB"


class Prefetcher:
    """Lädt nach dem Start die Gewichte der eingeschalteten Modelle herunter (nur auf die Platte, nicht in den
    Speicher). So muss die erste Anfrage, etwa aus der Android-App, nicht minutenlang auf den Download warten."""

    def __init__(self, settings: Settings, download: Callable[[str], None] = hub_download,
                 size: Callable[[str], int] = hub_repo_size, cached: Callable[[str], int] = hub_cached_bytes,
                 poll: float = 2.0):
        self.settings = settings
        self._download, self._size, self._cached, self._poll = download, size, cached, poll
        self._lock = threading.Lock()
        self.items: list[dict[str, Any]] = []
        for model in settings.engines:
            for variant in PREFETCH_VARIANTS.get(model, []):
                self.items.append({"variant": variant, "model": model, "label": VARIANT_LABELS[variant],
                                   "repo": "", "state": "pending", "done_bytes": 0, "total_bytes": 0, "error": ""})
        self.thread: threading.Thread | None = None

    def start(self) -> None:
        if self.items and self.thread is None:
            self.thread = threading.Thread(target=self.run, daemon=True, name="gateway-prefetch")
            self.thread.start()

    def _update(self, item: dict, **kw: Any) -> None:
        with self._lock:
            item.update(kw)

    def run(self) -> None:
        log.info("Lade die Modelle im Hintergrund herunter: %s", ", ".join(i["label"] for i in self.items))
        for item in self.items:
            self._fetch(item)
        failed = [i["label"] for i in self.items if i["state"] == "error"]
        if failed:
            log.warning("Vorladen beendet, fehlgeschlagen: %s (wird beim ersten Gebrauch erneut versucht)",
                        ", ".join(failed))
        else:
            log.info("Alle Modelle heruntergeladen.")

    def _fetch(self, item: dict) -> None:
        errors = []
        for repo in repo_candidates(self.settings, item["variant"]):
            self._update(item, repo=repo, state="downloading", done_bytes=0, total_bytes=0, error="")
            try:
                total = self._size(repo)
            except Exception as exc:  # noqa: BLE001 - offline o. Ä.: trotzdem versuchen, ohne Prozentangabe
                log.info("Größe von %s unbekannt: %s", repo, exc)
                total = 0
            have = self._cached(repo)
            self._update(item, total_bytes=total, done_bytes=min(have, total) if total else have)
            if total and have >= total:
                log.info("%s ist schon heruntergeladen (%s).", repo, _gb(total))
            else:
                log.info("Lade %s herunter: %s (%s) …", item["label"], repo, _gb(total) if total else "Größe unbekannt")
            stop = threading.Event()
            watcher = threading.Thread(target=self._watch, args=(item, repo, stop), daemon=True)
            watcher.start()
            t0 = time.time()
            try:
                self._download(repo)
            except Exception as exc:  # noqa: BLE001 - nächsten Kandidaten versuchen
                log.warning("Download von %s fehlgeschlagen: %s", repo, exc)
                errors.append(f"{repo}: {exc}")
                continue
            finally:
                stop.set()
                watcher.join(5)
            size = self._cached(repo)
            self._update(item, state="done", done_bytes=max(size, total), total_bytes=max(size, total))
            log.info("%s heruntergeladen (%s, %.0f s).", item["label"], _gb(max(size, total)), time.time() - t0)
            return
        self._update(item, state="error", error=" | ".join(errors)[-500:])

    def _watch(self, item: dict, repo: str, stop: threading.Event) -> None:
        last_log = 0.0
        while not stop.wait(self._poll):
            done = self._cached(repo)
            total = item["total_bytes"]
            self._update(item, done_bytes=min(done, total) if total else done)
            if time.time() - last_log >= 15:
                last_log = time.time()
                if total:
                    log.info("Download %s: %s von %s (%d %%)", item["label"], _gb(done), _gb(total),
                             min(100, int(100 * done / total)))
                else:
                    log.info("Download %s: %s", item["label"], _gb(done))

    def status(self) -> dict[str, Any]:
        with self._lock:
            items = [dict(i) for i in self.items]
        busy = any(i["state"] in ("pending", "downloading") for i in items)
        return {"active": busy, "items": items,
                "done_bytes": sum(i["done_bytes"] for i in items),
                "total_bytes": sum(i["total_bytes"] for i in items)}

    def model_state(self, model: str) -> dict[str, Any]:
        """Download-Stand eines Modells (alle Varianten zusammen) für ``/models``."""
        items = [i for i in self.status()["items"] if i["model"] == model]
        if not items:
            return {"downloaded": None}
        total = sum(i["total_bytes"] for i in items)
        done = sum(i["done_bytes"] for i in items)
        return {
            "downloaded": all(i["state"] == "done" for i in items),
            "downloading": any(i["state"] == "downloading" for i in items),
            "download_progress": round(done / total, 3) if total else None,
        }


# ---------------------------------------------------------------------------
# HTTP
# ---------------------------------------------------------------------------

HOP_HEADERS = {"connection", "keep-alive", "proxy-authenticate", "proxy-authorization", "te", "trailers",
               "transfer-encoding", "upgrade", "host", "content-length"}
OPEN_PATHS = {"/", "/api", "/healthz", "/readyz"}


def create_app(settings: Settings, backend: Backend | None = None, prefetcher: Prefetcher | None = None):
    import httpx

    if backend is None:
        backend = FakeBackend() if settings.fake else MlxBackend(settings)
    if prefetcher is None and settings.prefetch and not settings.fake:
        prefetcher = Prefetcher(settings)
    worker = Worker(backend)
    app = FastAPI(title="Tontaube Modell-Gateway", docs_url=None, redoc_url=None, openapi_url=None)
    app.state.worker = worker
    app.state.settings = settings
    client = httpx.AsyncClient(base_url=settings.upstream, timeout=httpx.Timeout(None, connect=5.0))
    app.state.client = client
    app.state.prefetcher = prefetcher  # startet main() (Tests rufen start() selbst auf)

    @app.middleware("http")
    async def api_key_guard(request: Request, call_next):
        if settings.api_key and request.method != "OPTIONS" and request.url.path not in OPEN_PATHS:
            provided = request.headers.get("x-api-key") or ""
            if not secrets.compare_digest(provided, settings.api_key):
                return JSONResponse({"error": "unauthorized"}, status_code=401)
        return await call_next(request)

    @app.get("/models")
    async def models():
        tontaube_ready = False
        try:
            r = await client.get("/readyz", timeout=3)
            tontaube_ready = r.status_code == 200
        except httpx.HTTPError:
            pass
        loaded = backend.loaded()
        out = [{
            "id": "tontaube", "name": "Tontaube V1.1", "license": "Apache-2.0",
            "description": "Standardmodell, Stil-Regler, mitgelieferte Stimmen, Klonen.",
            "languages": ["german", "english", "spanish", "french", "italian", "dutch", "portuguese"],
            "voice_cloning": True, "speakers": [], "instruct": False, "tags": [], "params": [],
            "controls": True, "ready": tontaube_ready, "loaded": tontaube_ready,
        }]
        for mid in settings.engines:
            d = dict(MODELS[mid])
            d["controls"] = False
            d["ready"] = True
            d["loaded"] = any(_model_of(v) == mid for v in loaded)
            d["loading"] = bool(worker.loading) and _model_of(worker.loading or "") == mid
            if prefetcher is not None:
                d.update(prefetcher.model_state(mid))
            out.append(d)
        return {"models": out, "gateway": 1}

    @app.get("/gateway/status")
    async def gateway_status():
        return {
            "engines": settings.engines,
            "loaded": backend.loaded(),
            "loading": worker.loading,
            "prefetch": prefetcher.status() if prefetcher is not None else None,
        }

    async def _read_json(request: Request) -> tuple[bytes, dict | None]:
        raw = await request.body()
        try:
            body = json.loads(raw) if raw else None
        except ValueError:
            body = None
        return raw, body if isinstance(body, dict) else None

    def _start(job: Job) -> tuple[asyncio.Queue, threading.Event]:
        loop = asyncio.get_running_loop()
        queue: asyncio.Queue = asyncio.Queue()
        cancel = threading.Event()

        def emit(kind: str, value: Any) -> None:
            loop.call_soon_threadsafe(queue.put_nowait, (kind, value))

        threading.Thread(target=worker.run, args=(job, cancel, emit), daemon=True, name="gateway-synth").start()
        return queue, cancel

    async def _stream_local(job: Job, body: dict):
        queue, cancel = _start(job)
        kbps = bitrate_kbps(body.get("bitrate"))

        async def gen():
            writer: Mp3Writer | None = None
            idx = 0
            try:
                while True:
                    try:
                        kind, value = await asyncio.wait_for(queue.get(), timeout=10)
                    except asyncio.TimeoutError:
                        yield ": still working\n\n"  # hält die Verbindung beim Laden der Modelle offen
                        continue
                    if kind == "loading":
                        yield f": loading {value}\n\n"
                    elif kind == "audio":
                        writer = writer or Mp3Writer(backend.sample_rate, kbps)
                        data = writer.write(to_int16(value))
                        if data:
                            yield "data: " + json.dumps(
                                {"index": idx, "audio_b64": base64.b64encode(data).decode(), "done": False}) + "\n\n"
                            idx += 1
                    elif kind == "error":
                        yield "data: " + json.dumps({"error": value, "done": True}) + "\n\n"
                        return
                    elif kind == "end":
                        if writer:
                            tail = writer.close()
                            if tail:
                                yield "data: " + json.dumps(
                                    {"index": idx, "audio_b64": base64.b64encode(tail).decode(),
                                     "done": False}) + "\n\n"
                        yield "data: " + json.dumps({"done": True}) + "\n\n"
                        return
            finally:
                cancel.set()

        return StreamingResponse(gen(), media_type="text/event-stream",
                                 headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"})

    async def _predict_local(job: Job, body: dict):
        fmt = str(body.get("format") or "wav").lower()
        if fmt not in ("wav", "mp3"):
            return JSONResponse({"error": f"Format '{fmt}' wird von {job.model} nicht unterstützt (wav oder mp3)."},
                                status_code=422)
        queue, cancel = _start(job)
        chunks: list[bytes] = []
        t0 = time.time()
        while True:
            kind, value = await queue.get()
            if kind == "audio":
                chunks.append(to_int16(value))
            elif kind == "error":
                return JSONResponse({"error": value}, status_code=500)
            elif kind == "end":
                break
        pcm = b"".join(chunks)
        sr = backend.sample_rate
        if fmt == "mp3":
            w = Mp3Writer(sr, bitrate_kbps(body.get("bitrate"), 128))
            audio = w.write(pcm) + w.close()
        else:
            audio = wav_bytes(pcm, sr)
        seconds = len(pcm) / 2 / sr
        return {"audio_b64": base64.b64encode(audio).decode(), "format": fmt, "sample_rate": sr,
                "model": job.model, "duration_s": round(seconds, 2),
                "generation_s": round(time.time() - t0, 2)}

    async def _local_or_proxy(request: Request, local):
        raw, body = await _read_json(request)
        model = (body or {}).get("model")
        if body is not None and model and model != "tontaube":
            try:
                job = parse_job(body, settings)
            except RequestError as exc:
                return JSONResponse({"error": str(exc)}, status_code=422)
            return await local(job, body)
        if body is not None and "model" in body:  # Tontaube kennt das Feld nicht (extra=forbid)
            body = {k: v for k, v in body.items() if k != "model"}
            raw = json.dumps(body).encode()
        return await _proxy(request, raw)

    @app.post("/transcribe")
    async def transcribe(request: Request):
        """Referenzaudio → Text (Whisper), damit Qwen3 mit Transkript klonen kann."""
        _raw, body = await _read_json(request)
        if not body or not body.get("audio_b64"):
            return JSONResponse({"error": "audio_b64 fehlt."}, status_code=422)
        try:
            data = base64.b64decode(str(body["audio_b64"]), validate=False)
            audio = decode_audio(data, 16000, max_seconds=float(body.get("max_seconds") or 30))
        except Exception as exc:  # noqa: BLE001
            return JSONResponse({"error": f"Audio nicht lesbar: {exc}"}, status_code=422)
        if audio.size < 16000 // 2:
            return JSONResponse({"error": "Audio ist zu kurz für ein Transkript."}, status_code=422)
        lang = str(body.get("language") or "").strip().lower() or None
        code = CHATTERBOX_LANGS.get(lang, lang) if lang else None

        def run() -> str:
            with worker.lock:
                worker.loading = "whisper"
                try:
                    return backend.transcribe(audio, code)
                finally:
                    worker.loading = None

        t0 = time.time()
        try:
            text = await asyncio.to_thread(run)
        except Exception as exc:  # noqa: BLE001
            log.exception("Transkript fehlgeschlagen")
            return JSONResponse({"error": f"{type(exc).__name__}: {exc}"}, status_code=500)
        return {"text": text, "language": code, "duration_s": round(audio.size / 16000, 2),
                "generation_s": round(time.time() - t0, 2)}

    @app.post("/stream")
    async def stream(request: Request):
        return await _local_or_proxy(request, _stream_local)

    @app.post("/predict")
    async def predict(request: Request):
        return await _local_or_proxy(request, _predict_local)

    async def _proxy(request: Request, raw: bytes | None = None):
        if raw is None:
            raw = await request.body()
        headers = {k: v for k, v in request.headers.items() if k.lower() not in HOP_HEADERS | {"x-api-key"}}
        req = client.build_request(request.method, request.url.path, params=request.query_params, headers=headers,
                                   content=raw)
        try:
            resp = await client.send(req, stream=True)
        except httpx.HTTPError as exc:
            return JSONResponse({"error": f"Tontaube-Server nicht erreichbar: {exc}"}, status_code=503)
        out_headers = {k: v for k, v in resp.headers.items() if k.lower() not in HOP_HEADERS}
        return StreamingResponse(resp.aiter_raw(), status_code=resp.status_code, headers=out_headers,
                                 background=BackgroundTask(resp.aclose))

    @app.api_route("/{path:path}", methods=["GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS", "HEAD"])
    async def proxy(request: Request, path: str):  # noqa: ARG001
        return await _proxy(request)

    return app


def main() -> None:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
    settings = Settings.from_env()
    import uvicorn

    log.info("Gateway auf %s:%s → Tontaube %s, Modelle: %s%s", settings.host, settings.port, settings.upstream,
             ", ".join(settings.engines) or "keine", " (Testmodus)" if settings.fake else "")
    app = create_app(settings)
    if app.state.prefetcher is not None:
        app.state.prefetcher.start()
    uvicorn.run(app, host=settings.host, port=settings.port, log_level="info")


if __name__ == "__main__":
    main()
