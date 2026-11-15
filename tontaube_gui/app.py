"""Gradio-Oberfläche für TontaubeV1.1.

Start: ``python -m tontaube_gui`` (Optionen siehe ``--help``).
"""

from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import re
import subprocess
import sys
import threading
import time
from pathlib import Path
from typing import Any

import gradio as gr

from .client import (
    DEFAULT_CONTROLS,
    DEFAULT_SERVER_URL,
    DEFAULT_VOICE,
    STREAM_BITRATES,
    LANGUAGES,
    OUTPUT_FORMATS,
    STYLE_TAGS,
    ControlSpec,
    SynthesisResult,
    TontaubeClient,
    TontaubeError,
    build_payload,
    concat_wav,
    load_voice_catalog,
    wav_duration,
)

MAX_CONTROL_SLOTS = 8
N_FORM = 22  # Anzahl der Formularfelder vor den Regler-Slots (siehe build_ui: form)
VOICES = load_voice_catalog()


def voice_choices(language_label: str | None = None) -> list[tuple[str, str]]:
    """Stimmen als (Anzeige, voice_path); passende Sprache zuerst."""
    lang = LANGUAGES.get(language_label or "", language_label or "")
    ordered = sorted(VOICES, key=lambda v: (v.language != lang, v.language, v.name))
    return [(v.label, v.path) for v in ordered] or [(DEFAULT_VOICE, DEFAULT_VOICE)]
OUTPUT_DIR = Path(os.environ.get("TONTAUBE_OUTPUT_DIR", "outputs")).resolve()

VOICE_DEFAULT = "Standardstimme (Marcus)"
VOICE_BUNDLED = "Mitgelieferte Stimme"
VOICE_CLONE = "Eigene Stimme klonen (Referenzaudio)"

TRIM_DEFAULT = "Server-Standard (~250 ms)"
TRIM_OFF = "Nicht trimmen"
TRIM_CUSTOM = "Eigener Wert"

CSS = """
#title {margin-bottom: 0}
.control-row {align-items: center}
footer {display: none !important}
"""


# ---------------------------------------------------------------------------
# Hilfsfunktionen
# ---------------------------------------------------------------------------

def _client(url: str, api_key: str, timeout: float) -> TontaubeClient:
    return TontaubeClient(url.strip() or DEFAULT_SERVER_URL, api_key.strip() or None, float(timeout or 600))


def _slug(text: str, n: int = 32) -> str:
    s = re.sub(r"[^\w]+", "_", text.strip().lower(), flags=re.UNICODE).strip("_")
    return (s[:n] or "audio").rstrip("_")


def _save(result: SynthesisResult, text: str, payload: dict[str, Any], suffix: str = "") -> Path:
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    stamp = dt.datetime.now().strftime("%Y%m%d-%H%M%S")
    path = OUTPUT_DIR / f"{stamp}_{_slug(text)}{suffix}.{result.extension}"
    path.write_bytes(result.audio)
    meta = {k: v for k, v in payload.items() if k not in ("voice_audio_b64", "prefix_tokens")}
    if "voice_audio_b64" in payload:
        meta["voice_audio_b64"] = "<Referenzaudio>"
    path.with_suffix(".json").write_text(json.dumps(meta, ensure_ascii=False, indent=2), encoding="utf-8")
    return path


def _payload_preview(payload: dict[str, Any]) -> str:
    shown = dict(payload)
    for key in ("voice_audio_b64", "prefix_tokens"):
        if key in shown:
            val = shown[key]
            size = len(val) if isinstance(val, str) else len(json.dumps(val))
            shown[key] = f"<{size} Zeichen>"
    return json.dumps(shown, ensure_ascii=False, indent=2)


def _parse_json_field(raw: str, what: str) -> Any:
    raw = (raw or "").strip()
    if not raw:
        return None
    try:
        return json.loads(raw)
    except json.JSONDecodeError as exc:
        raise TontaubeError(f"{what}: ungültiges JSON ({exc.msg}, Zeile {exc.lineno}).") from exc


def _control_slot_updates(specs: list[ControlSpec]) -> list[Any]:
    """Liefert Updates für alle Regler-Slots (Checkbox, Slider) + den State."""
    updates: list[Any] = []
    for i in range(MAX_CONTROL_SLOTS):
        if i < len(specs):
            s = specs[i]
            info = f"{s.name}: Stufen {s.minimum}–{s.maximum}. {s.description}".strip()
            value = s.default if s.default is not None else (s.minimum + s.maximum) // 2
            updates.append(gr.update(visible=True, label=f"{s.display_label} festlegen", value=False))
            updates.append(
                gr.update(
                    visible=True,
                    label=s.display_label,
                    info=info,
                    minimum=s.minimum,
                    maximum=s.maximum,
                    step=1,
                    value=value,
                    interactive=False,
                )
            )
        else:
            updates.append(gr.update(visible=False, value=False))
            updates.append(gr.update(visible=False))
    return updates


# ---------------------------------------------------------------------------
# Callbacks
# ---------------------------------------------------------------------------

def check_server(url: str, api_key: str, timeout: float):
    client = _client(url, api_key, timeout)
    ok, msg = client.ready()
    lines = [f"{'🟢 Bereit' if ok else '🔴 Nicht bereit'} – `{client.base_url}`", "", f"`{msg}`"]
    specs: list[ControlSpec] = []
    voices: list[str] = []
    try:
        specs = client.controls()
        lines.append(f"Stil-Regler vom Server geladen: {', '.join(s.name for s in specs) or '–'}")
    except TontaubeError as exc:
        lines.append(f"⚠️ /controls nicht lesbar, verwende Regler laut Modellkarte. ({exc})")
    if not specs:
        specs = list(DEFAULT_CONTROLS)
    voices = client.voices()
    if voices:
        lines.append(f"Stimmen vom Server: {len(voices)}")
        voice_update = gr.update(choices=voices, value=voices[0])
    else:
        lines.append(f"{len(VOICES)} mitgelieferte Stimmen verfügbar.")
        voice_update = gr.skip()
    if client.openapi():
        lines.append(f"API-Doku: {client.base_url}/docs")
    return ("\n\n".join(lines), specs, voice_update, *_control_slot_updates(specs))


def _collect(
    text: str,
    segments: bool,
    language: str,
    style: str,
    temperature: float,
    system_tag: bool,
    voice_mode: str,
    voice_path: str,
    voice_audio_path: str | None,
    fmt: str,
    prompt_max_tokens: float,
    trim_mode: str,
    trim_ms: float,
    mossformer: bool,
    verbalize: bool,
    segment_controls_raw: str,
    extra_raw: str,
    seed: str,
    top_p: str,
    top_k: str,
    frequency_penalty: str,
    acoustic_temperature: str,
    specs: list[ControlSpec],
    slot_values: list[Any],
    *,
    prefix: dict[str, Any] | None = None,
) -> dict[str, Any]:
    if segments:
        parts = [p.strip() for p in re.split(r"\n\s*\n", text or "") if p.strip()]
        text_value: str | list[str] = parts
    else:
        text_value = (text or "").strip()

    controls: dict[str, int] = {}
    for i, spec in enumerate((specs or [])[:MAX_CONTROL_SLOTS]):
        enabled, value = slot_values[2 * i], slot_values[2 * i + 1]
        if enabled and value is not None:
            controls[spec.name] = int(value)

    voice_audio = None
    vpath = None
    if voice_mode == VOICE_CLONE:
        if not voice_audio_path:
            raise TontaubeError("Bitte ein Referenzaudio hochladen oder aufnehmen (ca. 5–60 s, eine Person).")
        voice_audio = Path(voice_audio_path).read_bytes()
    elif voice_mode == VOICE_BUNDLED:
        vpath = (voice_path or "").strip() or None
        vpath = next((v.path for v in VOICES if vpath in (v.label, v.name)), vpath)

    trim: Any = "default"
    if trim_mode == TRIM_OFF:
        trim = None
    elif trim_mode == TRIM_CUSTOM:
        trim = int(trim_ms or 0)

    segment_controls = _parse_json_field(segment_controls_raw, "Segment-Regler")
    if segment_controls is not None and not isinstance(segment_controls, list):
        raise TontaubeError("Segment-Regler: erwartet wird eine JSON-Liste, z. B. [{\"rate\": 1}, {\"rate\": 3}].")
    extra = _parse_json_field(extra_raw, "Zusätzliche Felder")
    if extra is not None and not isinstance(extra, dict):
        raise TontaubeError("Zusätzliche Felder: erwartet wird ein JSON-Objekt.")

    return build_payload(
        text_value,
        language=LANGUAGES.get(language, language) or None,
        tag=STYLE_TAGS.get(style, style) or None,
        fmt=fmt,
        temperature=float(temperature) if temperature is not None else None,
        controls=controls,
        segment_controls=segment_controls,
        voice_path=vpath,
        voice_audio=voice_audio,
        prompt_max_tokens=int(prompt_max_tokens) if prompt_max_tokens else None,
        use_system_tag=None if system_tag else False,
        trim_silence_padding_ms=trim,
        mossformer2_postprocess=bool(mossformer),
        use_verbalization=True if verbalize else None,
        prefix_text=(prefix or {}).get("text"),
        prefix_tokens=(prefix or {}).get("tokens"),
        sampling={
            "seed": _opt_num(seed, int, "Seed"),
            "top_p": _opt_num(top_p, float, "top_p"),
            "top_k": _opt_num(top_k, int, "top_k"),
            "frequency_penalty": _opt_num(frequency_penalty, float, "frequency_penalty"),
            "acoustic_temperature": _opt_num(acoustic_temperature, float, "Akustik-Temperatur"),
        },
        extra=extra,
    )


def _opt_num(raw: Any, cast, what: str):
    text = str(raw if raw is not None else "").strip().replace(",", ".")
    if not text:
        return None
    try:
        return cast(float(text)) if cast is int else cast(text)
    except ValueError as exc:
        raise TontaubeError(f"{what}: '{raw}' ist keine Zahl.") from exc


def _history_rows(history: list[dict[str, Any]]) -> list[list[Any]]:
    return [[h["time"], h["mode"], h["text"][:80], h["voice"], h["file"]] for h in reversed(history)]


def synthesize(url, api_key, timeout, specs, history, last, *args, continue_mode: bool = False):
    text = args[0]
    try:
        prefix = None
        if continue_mode:
            if not last or not last.get("tokens"):
                raise TontaubeError(
                    "Zum Fortsetzen braucht es ein vorheriges Ergebnis mit 'codebook_tokens'. "
                    "Der Server hat bei der letzten Anfrage keine Tokens zurückgegeben."
                )
            prefix = {"text": last["text"], "tokens": last["tokens"]}
        payload = _collect(*args[:N_FORM], specs, list(args[N_FORM:]), prefix=prefix)
        client = _client(url, api_key, timeout)
        started = dt.datetime.now()
        result = client.predict(payload)
        secs = (dt.datetime.now() - started).total_seconds()
    except TontaubeError as exc:
        raise gr.Error(str(exc)) from exc

    path = _save(result, text, payload, "_fortsetzung" if continue_mode else "")
    files = [str(path), str(path.with_suffix(".json"))]
    info = f"✅ Fertig in {secs:.1f} s · Format: {result.format}"
    dur = wav_duration(result.audio) if result.format == "wav" else None
    if dur:
        info += f" · Dauer: {dur:.1f} s"
    play_path = path

    full_text = (text or "").strip()
    if continue_mode and last:
        full_text = f"{last['text']} {full_text}".strip()
        if result.format == "wav" and last.get("format") == "wav" and last.get("file"):
            joined = concat_wav(Path(last["file"]).read_bytes(), result.audio)
            if joined:
                jpath = path.with_name(path.stem.replace("_fortsetzung", "") + "_komplett.wav")
                jpath.write_bytes(joined)
                files.insert(0, str(jpath))
                play_path = jpath
                info += " · Fortsetzung an vorheriges Audio angehängt"

    if result.codebook_tokens is None:
        info += " · (keine codebook_tokens in der Antwort – Fortsetzen nicht möglich)"

    voice = payload.get("voice_path") or ("Klon" if "voice_audio_b64" in payload else "Standard")
    history = list(history or []) + [
        {
            "time": started.strftime("%H:%M:%S"),
            "mode": "Fortsetzung" if continue_mode else "Synthese",
            "text": full_text,
            "voice": voice,
            "file": str(play_path),
        }
    ]
    new_last = {
        "text": full_text,
        "tokens": result.codebook_tokens if result.codebook_tokens is not None else None,
        "format": result.format,
        "file": str(play_path),
    }
    return (
        str(play_path),
        files,
        info,
        _payload_preview(payload),
        history,
        new_last,
        _history_rows(history),
        [h["file"] for h in reversed(history)],
    )


def synthesize_continue(*args):
    return synthesize(*args, continue_mode=True)


def stream_speech(url, api_key, timeout, specs, history, bitrate, priority, max_new_tokens, initial_seconds, *args):
    text = args[0]
    try:
        payload = _collect(*args[:N_FORM], specs, list(args[N_FORM:]))
    except TontaubeError as exc:
        raise gr.Error(str(exc)) from exc
    payload["format"] = "mp3"
    for key in ("trim_silence_padding_ms", "mossformer2_postprocess", "prefix_text", "prefix_tokens"):
        payload.pop(key, None)  # gelten laut Server-README nicht für Streaming
    if bitrate in STREAM_BITRATES:
        payload["bitrate"] = bitrate
    if priority in ("low", "high"):
        payload["priority"] = priority
    if max_new_tokens:
        payload["max_new_tokens"] = int(max_new_tokens)
    if initial_seconds:
        payload["streaming_initial_seconds"] = round(float(initial_seconds), 1)

    client = _client(url, api_key, timeout)
    buf = bytearray()
    started = dt.datetime.now()
    first_chunk_at: float | None = None
    yield None, None, "⏳ Verbinde …", _payload_preview(payload), history, _history_rows(history or [])
    try:
        for chunk in client.stream(payload):
            if first_chunk_at is None:
                first_chunk_at = (dt.datetime.now() - started).total_seconds()
            buf.extend(chunk)
            yield (
                chunk,
                None,
                f"🔊 Streaming … erster Chunk nach {first_chunk_at:.2f} s · {len(buf) / 1024:.0f} KiB",
                gr.update(),
                history,
                gr.update(),
            )
    except TontaubeError as exc:
        raise gr.Error(str(exc)) from exc

    if not buf:
        raise gr.Error("Der Server hat keine Audiodaten gestreamt.")
    path = _save(SynthesisResult(bytes(buf), "mp3"), text, payload, "_stream")
    total = (dt.datetime.now() - started).total_seconds()
    history = list(history or []) + [
        {
            "time": started.strftime("%H:%M:%S"),
            "mode": "Streaming",
            "text": (text or "").strip(),
            "voice": payload.get("voice_path") or ("Klon" if "voice_audio_b64" in payload else "Standard"),
            "file": str(path),
        }
    ]
    yield (
        None,
        [str(path), str(path.with_suffix(".json"))],
        f"✅ Stream beendet · erster Chunk nach {first_chunk_at or 0:.2f} s · gesamt {total:.1f} s",
        gr.update(),
        history,
        _history_rows(history),
    )


def clear_history():
    return [], None, [], []


# ---------------------------------------------------------------------------
# Oberfläche
# ---------------------------------------------------------------------------

HELP_MD = f"""
### So funktioniert's

Diese Oberfläche ist ein Client für den offiziellen **Tontaube-Inferenzserver**
([craitech/tontaube](https://github.com/craitech/tontaube), Version v1.1.0). Das Modell
selbst läuft im Server; die GUI schickt Anfragen an `/predict` bzw. `/stream`.

1. Server starten (siehe README), z. B. `uv run --python 3.12 --frozen tontaube serve`
   (int4/„lite“, läuft auch auf kleinen GPUs oder CPU) oder mit `TTS_BACKEND=vllm` (≥ 24 GiB GPU).
2. Oben **Verbindung testen** – dabei werden auch die Stil-Regler (`/controls`) geladen.
3. Text eingeben, Sprache/Stil/Stimme wählen, **Sprechen**.

**Stimmen**
* *Standardstimme*: der Server nutzt `DEFAULT_VOICE` (`{DEFAULT_VOICE}`).
* *Stimme vom Server*: ein Pfad unterhalb von `VOICE_PATH` des Servers (39 Stimmen werden mitgeliefert).
* *Klonen*: 5–60 Sekunden saubere Sprache **einer** Person hochladen oder aufnehmen.

**Stil-Regler**: nicht aktivierte Regler überlässt das Modell sich selbst.
Tempo 0–4 · Pausen 0–4 · Tonhöhen-Umfang 0–5 · Stimmlage 0–4 · Artikulation 0–2.

**Sprachen**: Englisch und Deutsch sind die Hauptsprachen. Spanisch, Französisch, Italienisch,
Niederländisch und Portugiesisch werden unterstützt, laut Modellkarte aber nicht von
Muttersprachlern geprüft.

**Temperatur**: niedriger = textgetreuer, höher = lebendigere Betonung (Standard 0.7).

**Segmente**: Ist „Absätze als Segmente senden“ aktiv, wird jeder durch eine Leerzeile
getrennte Absatz als eigenes Segment gesendet. Über *Segment-Regler* (JSON-Liste) lassen sich
pro Segment eigene Regler setzen.

**Fortsetzen**: hängt neuen Text nahtlos an das letzte Ergebnis an (nutzt `prefix_tokens`).
Funktioniert nur, wenn der Server `codebook_tokens` zurückgibt.

**Streaming**: liefert MP3 per Server-Sent Events, Wiedergabe beginnt nach wenigen Sekunden.
Nachbearbeitung (MossFormer2, Trimmen) gilt dabei nicht.

Alle Ergebnisse landen mit den verwendeten Parametern (JSON) in `{OUTPUT_DIR}`.
"""


def build_ui(default_url: str = DEFAULT_SERVER_URL, default_key: str = "", manager=None) -> gr.Blocks:
    """manager: optionaler :class:`tontaube_gui.launcher.ServerManager` (All-in-one-Modus)."""
    with gr.Blocks(title="Tontaube TTS", css=CSS, theme=gr.themes.Soft()) as demo:
        specs_state = gr.State(list(DEFAULT_CONTROLS))
        history_state = gr.State([])
        last_state = gr.State(None)

        gr.Markdown("# 🕊️ Tontaube TTS", elem_id="title")
        gr.Markdown("Oberfläche für **TontaubeV1.1** · mehrsprachige Sprachsynthese mit Voice Cloning")

        if manager is not None:
            with gr.Group():
                managed_status = gr.Markdown(manager.status_markdown())
            with gr.Accordion("Eingebauter Server", open=False):
                with gr.Row():
                    device_choice = gr.Dropdown(
                        [(label, key) for key, label in manager.DEVICE_LABELS.items()],
                        value=manager.device,
                        label="Rechenwerk (gilt nach Neustart)",
                        scale=2,
                    )
                    dtype_choice = gr.Dropdown(
                        [
                            ("bfloat16 – empfohlen", "bfloat16"),
                            ("float16", "float16"),
                            ("float32 – höchste Genauigkeit, doppelter Speicher", "float32"),
                        ],
                        value=manager.dtype,
                        label="Rechengenauigkeit (gilt nach Neustart)",
                        scale=2,
                    )
                    restart_btn = gr.Button("Server neu starten", scale=1)
                    reinstall_btn = gr.Button("Einrichtung wiederholen", scale=1)
                    folder_btn = gr.Button("Ausgabeordner öffnen", scale=1)
                    quit_btn = gr.Button("Tontaube beenden", variant="stop", scale=1)
                managed_log = gr.Code(manager.log_tail(), label="Protokoll", lines=14, interactive=False)
            connected_state = gr.State(False)
            timer = gr.Timer(2.0)

        with gr.Accordion("Server-Verbindung", open=manager is None, visible=True):
            with gr.Row():
                url = gr.Textbox(manager.url if manager else default_url, label="Server-URL", scale=3)
                api_key = gr.Textbox(default_key, label="API-Key (x-api-key, optional)", type="password", scale=2)
                timeout = gr.Number(600, label="Timeout (s)", minimum=10, scale=1)
                check_btn = gr.Button("Verbindung testen", scale=1)
            server_status = gr.Markdown("Noch nicht verbunden. Ohne Test werden die Regler laut Modellkarte genutzt.")

        with gr.Tabs():
            with gr.Tab("Sprechen"):
                with gr.Row():
                    with gr.Column(scale=3):
                        text = gr.Textbox(
                            label="Text",
                            lines=8,
                            placeholder="Willkommen bei Tontaube. Gib hier den Text ein, der gesprochen werden soll.",
                        )
                        segments = gr.Checkbox(False, label="Absätze als Segmente senden (Text als Liste)")
                        with gr.Row():
                            language = gr.Dropdown(
                                list(LANGUAGES), value="Deutsch", label="Sprache", allow_custom_value=True
                            )
                            style = gr.Dropdown(
                                list(STYLE_TAGS), value="Konversation", label="Sprechstil", allow_custom_value=True
                            )
                        with gr.Row():
                            temperature = gr.Slider(0.1, 1.5, value=0.7, step=0.05, label="Temperatur")
                            system_tag = gr.Checkbox(True, label="Sprache/Stil an Modell senden (use_system_tag)")

                        gr.Markdown("#### Stimme")
                        voice_mode = gr.Radio(
                            [VOICE_DEFAULT, VOICE_BUNDLED, VOICE_CLONE], value=VOICE_DEFAULT, show_label=False
                        )
                        voice_path = gr.Dropdown(
                            voice_choices("Deutsch"),
                            value=voice_choices("Deutsch")[0][1],
                            label="Stimme (passende Sprache zuerst)",
                            allow_custom_value=True,
                            visible=False,
                        )
                        voice_audio = gr.Audio(
                            sources=["upload", "microphone"],
                            type="filepath",
                            label="Referenzaudio (5–60 s, eine Person, wenig Hall/Rauschen)",
                            visible=False,
                        )
                        voice_hint = gr.Markdown(visible=False)

                        gr.Markdown("#### Stil-Regler")
                        slot_components: list[gr.components.Component] = []
                        for i in range(MAX_CONTROL_SLOTS):
                            spec = DEFAULT_CONTROLS[i] if i < len(DEFAULT_CONTROLS) else None
                            with gr.Row(elem_classes="control-row"):
                                cb = gr.Checkbox(
                                    False,
                                    label=f"{spec.display_label} festlegen" if spec else "",
                                    visible=spec is not None,
                                    scale=1,
                                    min_width=160,
                                )
                                sl = gr.Slider(
                                    spec.minimum if spec else 0,
                                    spec.maximum if spec else 4,
                                    value=((spec.minimum + spec.maximum) // 2) if spec else 2,
                                    step=1,
                                    label=spec.display_label if spec else "",
                                    info=f"{spec.name}: Stufen {spec.minimum}–{spec.maximum}. {spec.description}"
                                    if spec
                                    else None,
                                    interactive=False,
                                    visible=spec is not None,
                                    scale=4,
                                )
                            cb.change(lambda on: gr.update(interactive=on), cb, sl)
                            slot_components += [cb, sl]

                        with gr.Accordion("Erweitert", open=False):
                            with gr.Row():
                                fmt = gr.Radio(OUTPUT_FORMATS, value="wav", label="Ausgabeformat")
                                prompt_max_tokens = gr.Number(
                                    0,
                                    label="prompt_max_tokens (0 = Standard, max. 750)",
                                    minimum=0,
                                    maximum=750,
                                    precision=0,
                                    info="Begrenzt, wie viel vom Stimmen-Prompt genutzt wird.",
                                )
                            with gr.Row():
                                trim_mode = gr.Radio(
                                    [TRIM_DEFAULT, TRIM_OFF, TRIM_CUSTOM], value=TRIM_DEFAULT, label="Stille an den Rändern"
                                )
                                trim_ms = gr.Number(250, label="Rest-Stille (ms)", minimum=0, precision=0)
                            with gr.Row():
                                mossformer = gr.Checkbox(
                                    False,
                                    label="MossFormer2-Nachbearbeitung (48 kHz, Pausen kürzen; auf dem Mac langsam)",
                                )
                                verbalize = gr.Checkbox(
                                    False,
                                    label="Englischen Verbalizer nutzen (Zahlen, Abkürzungen ausschreiben; "
                                    "Server braucht ENABLE_VERBALIZATION=1)",
                                )
                            segment_controls = gr.Code(
                                "", language="json", label="Segment-Regler (JSON-Liste, optional)", lines=3
                            )
                            gr.Markdown("**Sampling** (leer = Server-Standard)")
                            with gr.Row():
                                seed = gr.Textbox("", label="Seed (für reproduzierbare Ergebnisse)", placeholder="zufällig")
                                top_p = gr.Textbox("", label="top_p (0–1)", placeholder="Standard")
                                top_k = gr.Textbox("", label="top_k", placeholder="Standard")
                                frequency_penalty = gr.Textbox("", label="frequency_penalty (−2–2)", placeholder="0.1")
                                acoustic_temperature = gr.Textbox("", label="Akustik-Temperatur (0–2)", placeholder="Standard")
                            extra = gr.Code(
                                "",
                                language="json",
                                label="Zusätzliche Felder für die Anfrage (JSON-Objekt, optional)",
                                lines=3,
                            )

                        with gr.Row():
                            speak_btn = gr.Button("🔊 Sprechen", variant="primary", scale=2)
                            continue_btn = gr.Button("➕ Fortsetzen", scale=1)

                    with gr.Column(scale=2):
                        audio_out = gr.Audio(label="Ergebnis", type="filepath", interactive=False, autoplay=True)
                        status = gr.Markdown()
                        files_out = gr.Files(label="Download (Audio + Parameter)")
                        with gr.Accordion("Gesendete Anfrage", open=False):
                            request_view = gr.Code(language="json", interactive=False)

            with gr.Tab("Live-Streaming"):
                gr.Markdown(
                    "Nutzt Text, Sprache, Stil, Stimme und Regler aus dem Tab **Sprechen** und streamt MP3 "
                    "über `/stream`. Die Wiedergabe startet, sobald die ersten Daten ankommen. Auf dem Mac rechnet "
                    "das Modell etwas langsamer als Echtzeit, bei längeren Texten kann die Wiedergabe deshalb kurz "
                    "stocken. Ein längerer erster Chunk hilft."
                )
                with gr.Row():
                    bitrate = gr.Dropdown(["Standard", *STREAM_BITRATES], value="Standard", label="Bitrate")
                    priority = gr.Dropdown(["Standard", "low", "high"], value="Standard", label="Priorität")
                    max_new_tokens = gr.Number(
                        0, label="max_new_tokens je Chunk (0 = Standard, 50–400)", minimum=0, maximum=400, precision=0
                    )
                    initial_seconds = gr.Slider(
                        0.4, 6.0, value=2.8, step=0.4, label="Länge des ersten Chunks (s)"
                    )
                stream_btn = gr.Button("📡 Live streamen", variant="primary")
                stream_audio = gr.Audio(label="Live", streaming=True, autoplay=True, interactive=False, format="mp3")
                stream_status = gr.Markdown()
                stream_files = gr.Files(label="Download")
                with gr.Accordion("Gesendete Anfrage", open=False):
                    stream_request = gr.Code(language="json", interactive=False)

            with gr.Tab("Verlauf"):
                history_table = gr.Dataframe(
                    headers=["Zeit", "Art", "Text", "Stimme", "Datei"],
                    datatype=["str", "str", "str", "str", "str"],
                    interactive=False,
                    wrap=True,
                )
                history_files = gr.Files(label="Alle Dateien dieser Sitzung")
                clear_btn = gr.Button("Verlauf leeren (Dateien bleiben erhalten)")

            with gr.Tab("Hilfe"):
                gr.Markdown(HELP_MD)

        # -- Ereignisse -----------------------------------------------------
        def on_voice_mode(mode):
            return (
                gr.update(visible=mode == VOICE_BUNDLED),
                gr.update(visible=mode == VOICE_CLONE),
                gr.update(visible=mode == VOICE_CLONE),
            )

        voice_mode.change(on_voice_mode, voice_mode, [voice_path, voice_audio, voice_hint])
        language.change(lambda lang: gr.update(choices=voice_choices(lang)), language, voice_path)

        def on_voice_audio(path):
            if not path:
                return ""
            data = Path(path).read_bytes()
            dur = wav_duration(data)
            if dur is None:
                return "Referenz geladen."
            if dur < 5:
                return f"⚠️ Referenz ist nur {dur:.1f} s lang. Empfohlen sind 5–60 s."
            if dur > 60:
                return f"⚠️ Referenz ist {dur:.0f} s lang. Empfohlen sind 5–60 s."
            return f"✅ Referenz: {dur:.1f} s"

        voice_audio.change(on_voice_audio, voice_audio, voice_hint)

        check_btn.click(
            check_server,
            [url, api_key, timeout],
            [server_status, specs_state, voice_path, *slot_components],
        )

        form = [
            text,
            segments,
            language,
            style,
            temperature,
            system_tag,
            voice_mode,
            voice_path,
            voice_audio,
            fmt,
            prompt_max_tokens,
            trim_mode,
            trim_ms,
            mossformer,
            verbalize,
            segment_controls,
            extra,
            seed,
            top_p,
            top_k,
            frequency_penalty,
            acoustic_temperature,
            *slot_components,
        ]
        outputs = [
            audio_out,
            files_out,
            status,
            request_view,
            history_state,
            last_state,
            history_table,
            history_files,
        ]
        common = [url, api_key, timeout, specs_state, history_state, last_state]
        speak_btn.click(synthesize, common + form, outputs, concurrency_limit=4)
        text.submit(synthesize, common + form, outputs, concurrency_limit=4)
        continue_btn.click(synthesize_continue, common + form, outputs, concurrency_limit=4)

        stream_btn.click(
            stream_speech,
            [url, api_key, timeout, specs_state, history_state, bitrate, priority, max_new_tokens, initial_seconds]
            + form,
            [stream_audio, stream_files, stream_status, stream_request, history_state, history_table],
            concurrency_limit=4,
        )

        clear_btn.click(clear_history, None, [history_state, last_state, history_table, history_files])

        if manager is not None:
            check_outputs = [server_status, specs_state, voice_path, *slot_components]

            def tick(connected, key, tmo):
                ready = manager.state.phase == "ready"
                base = [manager.status_markdown(), manager.log_tail()]
                if ready and not connected:
                    return [*base, gr.update(value=manager.url), True, *check_server(manager.url, key, tmo)]
                if not ready and connected:
                    return [*base, gr.update(), False, *[gr.skip()] * len(check_outputs)]
                return [*base, gr.update(), connected, *[gr.skip()] * len(check_outputs)]

            timer.tick(
                tick,
                [connected_state, api_key, timeout],
                [managed_status, managed_log, url, connected_state, *check_outputs],
                show_progress="hidden",
            )

            def do_restart(device, dtype):
                threading.Thread(target=manager.restart, kwargs={"dtype": dtype, "device": device}, daemon=True).start()
                return False

            restart_btn.click(do_restart, [device_choice, dtype_choice], connected_state)
            reinstall_btn.click(
                lambda: (threading.Thread(target=manager.reinstall, daemon=True).start(), False)[1],
                None,
                connected_state,
            )

            def open_folder():
                OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
                opener = "open" if sys.platform == "darwin" else "xdg-open"
                subprocess.Popen([opener, str(OUTPUT_DIR)])

            folder_btn.click(open_folder)

            def quit_app():
                def _bye():
                    time.sleep(0.5)
                    manager.stop()
                    os._exit(0)

                threading.Thread(target=_bye, daemon=True).start()
                return "**Tontaube wird beendet.** Dieses Fenster kann geschlossen werden."

            quit_btn.click(quit_app, None, managed_status)

    return demo


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(description="Gradio-Oberfläche für TontaubeV1.1")
    parser.add_argument("--server-url", default=os.environ.get("TONTAUBE_URL", DEFAULT_SERVER_URL))
    parser.add_argument("--api-key", default=os.environ.get("TONTAUBE_API_KEY", ""))
    parser.add_argument("--host", default=os.environ.get("GUI_HOST", "127.0.0.1"))
    parser.add_argument("--port", type=int, default=int(os.environ.get("GUI_PORT", "7860")))
    parser.add_argument("--share", action="store_true", help="Öffentlichen Gradio-Link erzeugen")
    parser.add_argument(
        "--managed",
        action="store_true",
        help="All-in-one-Modus: Tontaube-Server selbst einrichten, Modelle laden und starten",
    )
    parser.add_argument("--inbrowser", action="store_true", help="Browser automatisch öffnen")
    args = parser.parse_args(argv)

    manager = None
    if args.managed:
        from .launcher import ServerManager

        manager = ServerManager()
        manager.start()

    demo = build_ui(args.server_url, args.api_key, manager=manager)
    demo.queue(default_concurrency_limit=4).launch(
        server_name=args.host,
        server_port=args.port,
        share=args.share,
        inbrowser=args.inbrowser,
        allowed_paths=[str(OUTPUT_DIR)],
    )


if __name__ == "__main__":
    main()
