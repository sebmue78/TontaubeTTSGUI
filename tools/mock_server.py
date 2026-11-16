"""Minimaler Mock des Tontaube-Inferenzservers zum Testen der GUI ohne GPU.

Erzeugt statt Sprache Sinustöne. Implementiert die im Server-README beschriebenen
Endpunkte /readyz, /controls, /predict und /stream (SSE mit MP3, benötigt ``lameenc``).

Start:  python tools/mock_server.py  (Port 8080)
"""

from __future__ import annotations

import base64
import io
import json
import math
import os
import struct
import wave

import uvicorn
from fastapi import FastAPI, Header, HTTPException
from fastapi.responses import StreamingResponse

SR = 24000
API_KEY = os.environ.get("TTS_API_KEY")
app = FastAPI(title="Tontaube Mock")

CONTROLS = {"controls": [
    {"name": "rate", "bins": 5, "description": "speaking rate", "low": "much slower", "high": "much faster"},
    {"name": "pause", "bins": 5, "description": "pauses", "low": "fewer shorter pauses", "high": "more longer pauses"},
    {"name": "pitch_range", "bins": 6, "description": "intonation", "low": "flat monotone", "high": "very expressive"},
    {"name": "pitch_level", "bins": 5, "description": "voice pitch", "low": "low deep voice", "high": "high voice"},
    {"name": "legato", "bins": 3, "description": "crisp articulation", "low": "crisp staccato",
     "high": "normal articulation", "default": 2},
]}
BINS = {c["name"]: c["bins"] for c in CONTROLS["controls"]}


def _auth(key: str | None) -> None:
    if API_KEY and key != API_KEY:
        raise HTTPException(401, "invalid api key")


def _pcm(text: str, controls: dict) -> bytes:
    seconds = min(0.06 * len(text) + 0.4, 20) * (1.4 - 0.2 * controls.get("rate", 2))
    freq = 140 + 40 * controls.get("pitch_level", 2)
    n = int(SR * seconds)
    return b"".join(
        struct.pack("<h", int(8000 * math.sin(2 * math.pi * freq * i / SR) * (0.5 + 0.5 * math.sin(i / 2000))))
        for i in range(n)
    )


def _wav(pcm: bytes) -> bytes:
    out = io.BytesIO()
    with wave.open(out, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes(pcm)
    return out.getvalue()


def _text(body: dict) -> str:
    if "model" in body:  # wie der echte Server (pydantic extra="forbid")
        raise HTTPException(422, "extra field 'model' not permitted")
    t = body.get("text", "")
    t = " ".join(t) if isinstance(t, list) else str(t)
    if not t.strip():
        raise HTTPException(422, "no readable characters")
    return t


@app.get("/readyz")
def readyz():
    return {"status": "ready", "backend": "mock"}


@app.get("/controls")
def controls(x_api_key: str | None = Header(None)):
    _auth(x_api_key)
    return CONTROLS


@app.post("/predict")
def predict(body: dict, x_api_key: str | None = Header(None)):
    _auth(x_api_key)
    text = _text(body)
    fmt = body.get("format", "wav")
    if fmt != "wav":
        raise HTTPException(422, "mock supports only wav")
    for name, value in (body.get("controls") or {}).items():
        if name not in BINS or not 0 <= value < BINS[name]:
            raise HTTPException(422, f"invalid control {name}={value}")
    pcm = _pcm(text, body.get("controls") or {})
    return {
        "audio_b64": base64.b64encode(_wav(pcm)).decode(),
        "mime_type": "audio/wav",
        "sample_rate": SR,
        "codebook_tokens": [[1, 2, 3]] * 4,
        "echo": {k: v for k, v in body.items() if k not in ("voice_audio_b64", "prefix_tokens")},
        "had_voice_audio": "voice_audio_b64" in body,
    }


@app.post("/stream")
def stream(body: dict, x_api_key: str | None = Header(None)):
    _auth(x_api_key)
    import lameenc

    text = _text(body)
    pcm = _pcm(text, body.get("controls") or {})
    enc = lameenc.Encoder()
    enc.set_bit_rate(int(str(body.get("bitrate") or "64k").rstrip("k")))
    enc.set_in_sample_rate(SR)
    enc.set_channels(1)
    enc.set_quality(5)

    def gen():
        step = SR * 2 // 2  # 0,5 s
        for n, i in enumerate(range(0, len(pcm), step)):
            data = bytes(enc.encode(pcm[i : i + step]))
            if data:
                yield f"data: {json.dumps({'index': n, 'audio_b64': base64.b64encode(data).decode(), 'done': False})}\n\n"
        tail = base64.b64encode(bytes(enc.flush())).decode()
        yield f"data: {json.dumps({'index': -1, 'audio_b64': tail, 'done': False})}\n\n"
        yield f"data: {json.dumps({'done': True})}\n\n"

    return StreamingResponse(gen(), media_type="text/event-stream")


if __name__ == "__main__":
    uvicorn.run(app, host="127.0.0.1", port=int(os.environ.get("TTS_PORT", "8080")))
