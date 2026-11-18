"""HTTP-Client für den Tontaube-Inferenzserver (github.com/craitech/tontaube, v1.1.0).

Der Client kapselt die Endpunkte, die im README des Inferenzservers beschrieben sind:

* ``GET  /readyz``   – Bereitschaft
* ``GET  /controls`` – Stil-Regler des geladenen Modells
* ``POST /predict``  – Synthese (JSON mit ``audio_b64``)
* ``POST /stream``   – Streaming als Server-Sent Events (MP3-Chunks)

Felder und Antwortformate, die im README nicht exakt dokumentiert sind (z. B. das
JSON-Format von ``/controls`` oder der SSE-Events), werden tolerant ausgewertet.
"""

from __future__ import annotations

import base64
import binascii
import io
import json
import wave
from dataclasses import dataclass, field
from typing import Any, Iterator

import requests

DEFAULT_SERVER_URL = "http://127.0.0.1:8080"
DEFAULT_VOICE = "samples/Marcus.mp3"

# Laut Modellkarte: Englisch und Deutsch primär, weitere fünf Sprachen gelistet.
# Die exakten Strings sind nur für "english" belegt; die übrigen folgen demselben Muster.
LANGUAGES: dict[str, str] = {
    "Englisch": "english",
    "Deutsch": "german",
    "Spanisch": "spanish",
    "Französisch": "french",
    "Italienisch": "italian",
    "Niederländisch": "dutch",
    "Portugiesisch": "portuguese",
}

STYLE_TAGS: dict[str, str] = {
    "Konversation": "conversational",
    "Hörbuch": "audiobook",
    "Agentisch (Assistent)": "agentic",
}

OUTPUT_FORMATS = ["wav", "mp3", "opus"]

# Modelle hinter dem Modell-Gateway (Anzeige → Feld ``model``). "tontaube" wird nicht mitgeschickt.
MODELS: dict[str, str] = {
    "Tontaube V1.1": "tontaube",
    "Chatterbox Multilingual (Deutsch, Emotion)": "chatterbox",
    "Chatterbox Turbo (nur Englisch, Tags)": "chatterbox-turbo",
    "Qwen3-TTS (Deutsch, Stil-Anweisung)": "qwen3",
}
TURBO_TAGS = ["[laugh]", "[chuckle]", "[sigh]", "[gasp]", "[cough]", "[clear throat]", "[sniff]", "[groan]",
              "[shush]"]
QWEN_SPEAKERS = ["Ryan", "Aiden", "Vivian", "Serena", "Uncle_Fu", "Dylan", "Eric", "Ono_Anna", "Sohee"]


def build_model_payload(
    model: str,
    text: str | list[str],
    *,
    language: str | None = None,
    fmt: str | None = "wav",
    temperature: float | None = None,
    voice_path: str | None = None,
    voice_audio: bytes | None = None,
    seed: int | None = None,
    exaggeration: float | None = None,
    cfg_weight: float | None = None,
    speaker: str | None = None,
    instruct: str | None = None,
    ref_text: str | None = None,
    extra: dict[str, Any] | None = None,
) -> dict[str, Any]:
    """Body für Chatterbox/Qwen3 über das Modell-Gateway (nur die Felder, die diese Modelle kennen)."""
    if isinstance(text, list):
        text = "\n\n".join(t for t in text if t.strip())
    if not (text or "").strip():
        raise TontaubeError("Bitte einen Text eingeben.")
    payload: dict[str, Any] = {"model": model, "text": text}
    optional: dict[str, Any] = {"language": language, "format": fmt, "temperature": temperature, "seed": seed}
    if model.startswith("chatterbox"):
        optional.update(exaggeration=exaggeration, cfg_weight=cfg_weight)
    if model == "qwen3":
        optional.update(speaker=speaker or None, instruct=(instruct or "").strip() or None,
                        ref_text=(ref_text or "").strip() or None)
    payload.update({k: v for k, v in optional.items() if v is not None})
    if voice_audio:
        payload["voice_audio_b64"] = base64.b64encode(voice_audio).decode("ascii")
    elif voice_path:
        payload["voice_path"] = voice_path
    if extra:
        payload.update(extra)
    return payload
STREAM_BITRATES = ["32k", "64k", "96k", "128k"]

_LANG_CODES = {"en": "english", "de": "german", "es": "spanish", "fr": "french", "it": "italian",
               "nl": "dutch", "pt": "portuguese"}
_LANG_LABELS = {v: k for k, v in LANGUAGES.items()}
_STYLE_LABELS = {v: k for k, v in STYLE_TAGS.items()}


@dataclass
class Voice:
    path: str  # relativ zu VOICE_PATH, z. B. "samples/Juri.mp3"
    name: str
    language: str  # z. B. "german"
    style: str = ""

    @property
    def label(self) -> str:
        parts = [_LANG_LABELS.get(self.language, self.language)]
        if self.style:
            parts.append(_STYLE_LABELS.get(self.style, self.style))
        return f"{self.name} ({', '.join(parts)})"


def load_voice_catalog(manifest: str | Path | None = None) -> list[Voice]:
    """Stimmen aus ``voices/manifest.json`` des Servers (Standard: mitgelieferte Kopie von v1.1.0)."""
    from pathlib import Path as _P

    path = _P(manifest) if manifest else _P(__file__).resolve().parent / "server_assets" / "voices.json"
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return []
    voices = []
    for filename, meta in (data.get("voices") or {}).items():
        meta = meta if isinstance(meta, dict) else {}
        lang = str(meta.get("language", ""))
        voices.append(
            Voice(
                f"samples/{filename}",
                filename.rsplit(".", 1)[0],
                _LANG_CODES.get(lang, lang),
                str(meta.get("style", "")),
            )
        )
    return sorted(voices, key=lambda v: (v.language != "german", v.language, v.name))


@dataclass
class ControlSpec:
    """Ein Stil-Regler, z. B. ``rate`` mit den Stufen 0–4."""

    name: str
    minimum: int
    maximum: int
    label: str = ""
    description: str = ""
    default: int | None = None

    @property
    def display_label(self) -> str:
        return self.label or self.name


# Werte aus der Modellkarte von TontaubeV1.1 (Fallback, falls /controls nicht erreichbar ist).
DEFAULT_CONTROLS: list[ControlSpec] = [
    ControlSpec("rate", 0, 4, "Tempo", "0 = deutlich langsamer … 4 = deutlich schneller"),
    ControlSpec("pause", 0, 4, "Pausen", "0 = weniger/kürzere … 4 = mehr/längere Pausen"),
    ControlSpec("pitch_range", 0, 5, "Tonhöhen-Umfang", "0 = monoton … 5 = sehr expressiv"),
    ControlSpec("pitch_level", 0, 4, "Stimmlage", "0 = tief … 4 = hoch"),
    ControlSpec("legato", 0, 2, "Artikulation", "0 = knackig/staccato … 2 = normal"),
]

_KNOWN_LABELS = {c.name: (c.label, c.description) for c in DEFAULT_CONTROLS}


class TontaubeError(RuntimeError):
    """Fehler, der dem Benutzer verständlich angezeigt werden kann."""


@dataclass
class SynthesisResult:
    audio: bytes
    format: str
    raw: dict[str, Any] = field(default_factory=dict)

    @property
    def codebook_tokens(self) -> Any:
        return self.raw.get("codebook_tokens")

    @property
    def extension(self) -> str:
        return {"opus": "ogg"}.get(self.format, self.format)


def _first_int(d: dict[str, Any], *keys: str) -> int | None:
    for k in keys:
        v = d.get(k)
        if isinstance(v, (int, float)) and not isinstance(v, bool):
            return int(v)
    return None


def _control_from_dict(name: str, d: dict[str, Any]) -> ControlSpec | None:
    lo = _first_int(d, "min", "minimum", "min_step", "low")
    hi = _first_int(d, "max", "maximum", "max_step", "high")
    steps = d.get("bins") or d.get("steps") or d.get("levels") or d.get("labels")
    if hi is None and isinstance(steps, int):
        lo, hi = 0, steps - 1
    elif hi is None and isinstance(steps, list) and steps:
        lo, hi = 0, len(steps) - 1
    if lo is None:
        lo = 0
    if hi is None:
        known = next((c for c in DEFAULT_CONTROLS if c.name == name), None)
        if known is None:
            return None
        lo, hi = known.minimum, known.maximum
    label, desc = _KNOWN_LABELS.get(name, ("", ""))
    if not desc:
        low, high = d.get("low"), d.get("high")
        desc = f"{lo} = {low} … {hi} = {high}" if low and high else str(d.get("description") or "")
    return ControlSpec(name, lo, hi, label or str(d.get("description") or ""), desc, _first_int(d, "default", "neutral"))


def parse_controls(payload: Any) -> list[ControlSpec]:
    """Wertet die Antwort von ``GET /controls`` aus.

    Unterstützt u. a. ``{"rate": {"min": 0, "max": 4}}``, ``[{"name": "rate", "max": 4}]``,
    ``{"controls": ...}`` und ``{"rate": 5}`` (Anzahl der Stufen).
    """
    if isinstance(payload, dict) and "controls" in payload:
        payload = payload["controls"]
    specs: list[ControlSpec] = []
    if isinstance(payload, dict):
        for name, value in payload.items():
            if isinstance(value, dict):
                spec = _control_from_dict(name, value)
            elif isinstance(value, int) and not isinstance(value, bool):
                label, desc = _KNOWN_LABELS.get(name, ("", ""))
                spec = ControlSpec(name, 0, value - 1, label, desc)
            elif isinstance(value, list) and value:
                spec = _control_from_dict(name, {"labels": value})
            else:
                spec = None
            if spec:
                specs.append(spec)
    elif isinstance(payload, list):
        for item in payload:
            if isinstance(item, dict) and item.get("name"):
                spec = _control_from_dict(str(item["name"]), item)
                if spec:
                    specs.append(spec)
            elif isinstance(item, str):
                known = next((c for c in DEFAULT_CONTROLS if c.name == item), None)
                if known:
                    specs.append(known)
    return specs


def parse_voices(payload: Any) -> list[str]:
    """Wertet eine Stimmenliste aus (Strings oder Objekte mit ``path``/``name``)."""
    if isinstance(payload, dict):
        for key in ("voices", "items", "data"):
            if key in payload:
                return parse_voices(payload[key])
        return [str(k) for k in payload]
    voices: list[str] = []
    if isinstance(payload, list):
        for item in payload:
            if isinstance(item, str):
                voices.append(item)
            elif isinstance(item, dict):
                v = item.get("voice_path") or item.get("path") or item.get("file") or item.get("name")
                if v:
                    voices.append(str(v))
    return voices


def _decode_b64(data: str) -> bytes:
    if "," in data and data.strip().startswith("data:"):
        data = data.split(",", 1)[1]
    try:
        return base64.b64decode(data, validate=False)
    except (binascii.Error, ValueError) as exc:
        raise TontaubeError(f"Audio konnte nicht dekodiert werden: {exc}") from exc


def _extract_audio_field(obj: dict[str, Any]) -> str | None:
    for key in ("audio_b64", "audio", "chunk", "chunk_b64", "data"):
        v = obj.get(key)
        if isinstance(v, str) and v:
            return v
    return None


def build_payload(
    text: str | list[str],
    *,
    language: str | None = None,
    tag: str | None = None,
    fmt: str | None = "wav",
    temperature: float | None = None,
    controls: dict[str, int] | None = None,
    segment_controls: list[dict[str, int]] | None = None,
    voice_path: str | None = None,
    voice_audio: bytes | None = None,
    prompt_max_tokens: int | None = None,
    use_system_tag: bool | None = None,
    trim_silence_padding_ms: int | None | str = "default",
    mossformer2_postprocess: bool | None = None,
    use_verbalization: bool | None = None,
    prefix_text: str | None = None,
    prefix_tokens: Any = None,
    sampling: dict[str, Any] | None = None,
    extra: dict[str, Any] | None = None,
) -> dict[str, Any]:
    """Erzeugt den JSON-Body für ``/predict`` bzw. ``/stream``.

    Felder mit ``None`` werden weggelassen, damit der Server seine Defaults nutzt.
    ``trim_silence_padding_ms`` ist eine Ausnahme: ``None`` bedeutet dort explizit
    "nicht trimmen" (wird als JSON ``null`` gesendet), ``"default"`` lässt das Feld weg.
    """
    if isinstance(text, str):
        if not text.strip():
            raise TontaubeError("Bitte einen Text eingeben.")
    elif not text or not any(t.strip() for t in text):
        raise TontaubeError("Bitte einen Text eingeben.")

    payload: dict[str, Any] = {"text": text}
    optional = {
        "language": language,
        "tag": tag,
        "format": fmt,
        "temperature": temperature,
        "controls": controls or None,
        "segment_controls": segment_controls or None,
        "prompt_max_tokens": prompt_max_tokens,
        "use_system_tag": use_system_tag,
        "mossformer2_postprocess": mossformer2_postprocess,
        "use_verbalization": use_verbalization,
        "prefix_text": prefix_text or None,
        "prefix_tokens": prefix_tokens,
    }
    payload.update({k: v for k, v in optional.items() if v is not None})
    if trim_silence_padding_ms != "default":
        payload["trim_silence_padding_ms"] = trim_silence_padding_ms

    if voice_audio:
        payload["voice_audio_b64"] = base64.b64encode(voice_audio).decode("ascii")
    elif voice_path:
        payload["voice_path"] = voice_path

    if sampling:
        payload.update({k: v for k, v in sampling.items() if v is not None})
    if extra:
        payload.update(extra)
    return payload


class TontaubeClient:
    def __init__(self, base_url: str = DEFAULT_SERVER_URL, api_key: str | None = None, timeout: float = 600):
        self.base_url = (base_url or DEFAULT_SERVER_URL).rstrip("/")
        self.api_key = api_key or None
        self.timeout = timeout
        self.session = requests.Session()

    # -- Hilfsfunktionen ---------------------------------------------------
    @property
    def headers(self) -> dict[str, str]:
        h = {"Content-Type": "application/json"}
        if self.api_key:
            h["x-api-key"] = self.api_key
        return h

    def _url(self, path: str) -> str:
        return f"{self.base_url}{path}"

    @staticmethod
    def _raise_for(resp: requests.Response) -> None:
        if resp.ok:
            return
        detail: Any = resp.text
        try:
            body = resp.json()
            detail = body.get("detail", body) if isinstance(body, dict) else body
        except ValueError:
            pass
        if isinstance(detail, (dict, list)):
            detail = json.dumps(detail, ensure_ascii=False)
        hints = {
            401: "API-Key fehlt oder ist falsch.",
            403: "Zugriff verweigert (API-Key prüfen).",
            404: "Endpunkt nicht gefunden (Serverversion? Streaming deaktiviert?).",
            422: "Ungültige Anfrage (z. B. keine lesbaren Zeichen im Text oder ungültiger Parameter).",
            503: "Server ist noch nicht bereit oder ausgelastet.",
        }
        hint = hints.get(resp.status_code, "")
        raise TontaubeError(f"HTTP {resp.status_code}: {hint} {str(detail)[:800]}".strip())

    def _get(self, path: str, timeout: float = 10) -> requests.Response:
        try:
            return self.session.get(self._url(path), headers=self.headers, timeout=timeout)
        except requests.RequestException as exc:
            raise TontaubeError(f"Server unter {self.base_url} nicht erreichbar: {exc}") from exc

    # -- Endpunkte ---------------------------------------------------------
    def ready(self) -> tuple[bool, str]:
        try:
            resp = self._get("/readyz")
        except TontaubeError as exc:
            return False, str(exc)
        if resp.ok:
            return True, resp.text.strip()[:300] or "bereit"
        return False, f"HTTP {resp.status_code}: {resp.text.strip()[:300]}"

    def controls(self) -> list[ControlSpec]:
        resp = self._get("/controls")
        self._raise_for(resp)
        try:
            specs = parse_controls(resp.json())
        except ValueError as exc:
            raise TontaubeError("Antwort von /controls ist kein JSON.") from exc
        return specs

    def models(self) -> list[dict[str, Any]]:
        """Modelle laut ``GET /models`` (nur mit Modell-Gateway); leer bei reinem Tontaube-Server."""
        try:
            resp = self._get("/models", timeout=5)
        except TontaubeError:
            return []
        if not resp.ok:
            return []
        try:
            data = resp.json()
        except ValueError:
            return []
        models = data.get("models") if isinstance(data, dict) else None
        return [m for m in models or [] if isinstance(m, dict) and m.get("id")]

    def voices(self) -> list[str]:
        """Versucht, die mitgelieferten Stimmen vom Server zu lesen.

        Das Server-README dokumentiert keinen Stimmen-Endpunkt; deshalb werden
        gängige Pfade probiert. Gibt eine leere Liste zurück, wenn keiner antwortet.
        """
        for path in ("/voices", "/v1/voices", "/samples"):
            try:
                resp = self._get(path, timeout=5)
            except TontaubeError:
                return []
            if resp.ok:
                try:
                    voices = parse_voices(resp.json())
                except ValueError:
                    continue
                if voices:
                    return voices
        return []

    def openapi(self) -> dict[str, Any] | None:
        try:
            resp = self._get("/openapi.json", timeout=5)
        except TontaubeError:
            return None
        if not resp.ok:
            return None
        try:
            return resp.json()
        except ValueError:
            return None

    def predict(self, payload: dict[str, Any]) -> SynthesisResult:
        try:
            resp = self.session.post(
                self._url("/predict"), headers=self.headers, json=payload, timeout=self.timeout
            )
        except requests.Timeout as exc:
            raise TontaubeError(f"Zeitüberschreitung nach {self.timeout:.0f} s.") from exc
        except requests.RequestException as exc:
            raise TontaubeError(f"Server unter {self.base_url} nicht erreichbar: {exc}") from exc
        self._raise_for(resp)

        ctype = resp.headers.get("content-type", "")
        fmt = str(payload.get("format") or "wav")
        if ctype.startswith("audio/"):
            return SynthesisResult(resp.content, fmt, {})
        try:
            body = resp.json()
        except ValueError as exc:
            raise TontaubeError("Antwort von /predict ist weder JSON noch Audio.") from exc
        audio_field = _extract_audio_field(body) if isinstance(body, dict) else None
        if not audio_field:
            raise TontaubeError("Antwort enthält kein Feld 'audio_b64'.")
        fmt = str(body.get("format") or fmt)
        return SynthesisResult(_decode_b64(audio_field), fmt, body)

    def transcribe(self, audio: bytes, language: str | None = None) -> str:
        """Transkript eines Referenzaudios über ``POST /transcribe`` des Modell-Gateways (Whisper)."""
        body: dict[str, Any] = {"audio_b64": base64.b64encode(audio).decode("ascii")}
        if language:
            body["language"] = language
        try:
            resp = self.session.post(self._url("/transcribe"), headers=self.headers, json=body,
                                     timeout=max(self.timeout, 900))
        except requests.Timeout as exc:
            raise TontaubeError("Zeitüberschreitung beim Transkribieren.") from exc
        except requests.RequestException as exc:
            raise TontaubeError(f"Server unter {self.base_url} nicht erreichbar: {exc}") from exc
        if resp.status_code in (404, 405):
            raise TontaubeError("Automatisches Transkript gibt es nur mit „Weitere Modelle“ (Modell-Gateway).")
        self._raise_for(resp)
        try:
            text = str(resp.json().get("text") or "").strip()
        except (ValueError, AttributeError) as exc:
            raise TontaubeError("Antwort von /transcribe ist kein JSON.") from exc
        if not text:
            raise TontaubeError("Whisper hat in der Referenz keine Sprache erkannt.")
        return text

    def stream(self, payload: dict[str, Any]) -> Iterator[bytes]:
        """Streamt MP3-Chunks von ``POST /stream`` (Server-Sent Events)."""
        body = dict(payload)
        body["format"] = "mp3"
        headers = dict(self.headers)
        headers["Accept"] = "text/event-stream"
        try:
            resp = self.session.post(
                self._url("/stream"), headers=headers, json=body, stream=True, timeout=self.timeout
            )
        except requests.RequestException as exc:
            raise TontaubeError(f"Server unter {self.base_url} nicht erreichbar: {exc}") from exc
        with resp:
            self._raise_for(resp)
            ctype = resp.headers.get("content-type", "")
            if ctype.startswith("audio/"):
                for chunk in resp.iter_content(chunk_size=4096):
                    if chunk:
                        yield chunk
                return
            yield from iter_sse_audio(resp.iter_lines(decode_unicode=True))


def iter_sse_audio(lines: Iterator[str]) -> Iterator[bytes]:
    """Parst SSE-Zeilen und liefert die enthaltenen Audio-Bytes.

    Akzeptiert ``data:``-Zeilen mit JSON (``{"audio_b64": ...}`` o. ä.) oder rohem Base64.
    Events vom Typ ``error`` werden als :class:`TontaubeError` gemeldet, ``done``/``end``
    beenden den Stream.
    """
    event = "message"
    data_lines: list[str] = []

    def flush() -> Iterator[bytes]:
        if not data_lines:
            return
        data = "\n".join(data_lines)
        if event in ("done", "end", "complete"):
            return
        if data.strip() in ("[DONE]", ""):
            return
        try:
            obj = json.loads(data)
        except ValueError:
            obj = None
        if event == "error" or (isinstance(obj, dict) and obj.get("error")):
            msg = obj.get("error") if isinstance(obj, dict) else data
            raise TontaubeError(f"Streaming-Fehler: {msg}")
        if isinstance(obj, dict):
            if obj.get("done") and not _extract_audio_field(obj):
                return
            audio = _extract_audio_field(obj)
            if audio:
                yield _decode_b64(audio)
        elif isinstance(obj, str):
            yield _decode_b64(obj)
        elif obj is None:
            yield _decode_b64(data)

    for raw in lines:
        if raw is None:
            continue
        line = raw.rstrip("\r")
        if line == "":
            yield from flush()
            if event in ("done", "end", "complete"):
                return
            event, data_lines = "message", []
            continue
        if line.startswith(":"):
            continue
        name, _, value = line.partition(":")
        value = value[1:] if value.startswith(" ") else value
        if name == "event":
            event = value.strip()
        elif name == "data":
            data_lines.append(value)
    yield from flush()


def concat_wav(first: bytes, second: bytes) -> bytes | None:
    """Hängt zwei WAV-Dateien gleichen Formats aneinander (sonst ``None``)."""
    try:
        with wave.open(io.BytesIO(first)) as a, wave.open(io.BytesIO(second)) as b:
            if a.getparams()[:3] != b.getparams()[:3]:
                return None
            params = a.getparams()
            frames = a.readframes(a.getnframes()) + b.readframes(b.getnframes())
    except (wave.Error, EOFError):
        return None
    out = io.BytesIO()
    with wave.open(out, "wb") as w:
        w.setparams(params)
        w.writeframes(frames)
    return out.getvalue()


def wav_duration(data: bytes) -> float | None:
    try:
        with wave.open(io.BytesIO(data)) as w:
            return w.getnframes() / float(w.getframerate())
    except (wave.Error, EOFError, ZeroDivisionError):
        return None
