"""Probelauf der Zusatzmodelle (Chatterbox, Qwen3-TTS) auf dem Mac, ohne Tontaube und ohne Oberfläche.

Lädt jedes Modell über das Modell-Gateway (MLX), erzeugt einen Probesatz, misst Ladezeit,
Echtzeitfaktor und Zeit bis zum ersten Audio und speichert die WAV-Dateien.

Aufruf mit dem Python der Engine-Umgebung:
    engines-venv/bin/python tools/engine_bench.py [--out bench] [--models chatterbox,qwen3] [--ref stimme.mp3]
"""

from __future__ import annotations

import argparse
import importlib.util
import json
import resource
import sys
import threading
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
GW = HERE.parent / "tontaube_gui" / "server_assets" / "model_gateway.py"
if not GW.exists():
    GW = HERE / "model_gateway.py"
spec = importlib.util.spec_from_file_location("model_gateway", GW)
gw = importlib.util.module_from_spec(spec)
sys.modules["model_gateway"] = gw
spec.loader.exec_module(gw)

DE = ("Es war einmal ein kleiner Leuchtturm am Rand der Welt. Jede Nacht schickte er sein Licht über das Meer, "
      "damit die Schiffe sicher nach Hause fanden. Die Fischer liebten ihn, und die Möwen auch.")
EN = ("Well, that was close [laugh]. I honestly thought the boat would never make it back [sigh]. "
      "But here we are, safe and sound.")

CASES = {
    "chatterbox": [dict(text=DE, language="german", label="chatterbox-de")],
    "chatterbox-turbo": [dict(text=EN, language="english", label="turbo-en-tags")],
    "qwen3": [
        dict(text=DE, language="german", speaker="Ryan", instruct="Ruhig und warm, wie ein Hörbuchsprecher.",
             label="qwen3-ryan-de"),
        dict(text=DE, language="german", instruct="Eine tiefe, ruhige deutsche Männerstimme, etwa 50 Jahre.",
             label="qwen3-design-de"),
    ],
}


def rss_gb() -> float:
    return resource.getrusage(resource.RUSAGE_SELF).ru_maxrss / 1e9  # macOS: Bytes


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="bench")
    ap.add_argument("--models", default="chatterbox,chatterbox-turbo,qwen3")
    ap.add_argument("--ref", help="Referenzaudio für einen Klon-Durchlauf (mp3/wav/m4a)")
    ap.add_argument("--qwen-size", default="1.7B")
    args = ap.parse_args()
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    settings = gw.Settings(max_loaded=1, qwen_size=args.qwen_size)
    backend = gw.MlxBackend(settings)
    results = []
    ref = Path(args.ref).read_bytes() if args.ref else None
    for model in [m.strip() for m in args.models.split(",") if m.strip()]:
        cases = list(CASES[model])
        if ref:
            text, lang = (EN, "english") if model == "chatterbox-turbo" else (DE, "german")
            cases.append(dict(text=text, language=lang, label=f"{model}-klon", ref=True))
        for case in cases:
            body = {"model": model, "text": case["text"], "language": case["language"]}
            for k in ("speaker", "instruct"):
                if case.get(k):
                    body[k] = case[k]
            job = gw.parse_job(body, settings)
            if case.get("ref"):
                job.ref_audio, job.ref_key = ref, "bench"
            row = {"case": case["label"], "variant": job.variant}
            try:
                t0 = time.time()
                backend.ensure(job.variant)
                row["load_s"] = round(time.time() - t0, 1)
                row["repo"] = backend.repos.get(job.variant)
                t1 = time.time()
                first = None
                chunks = []
                for chunk in backend.synthesize(job, threading.Event()):
                    first = first or time.time() - t1
                    chunks.append(gw.to_int16(chunk))
                gen = time.time() - t1
                pcm = b"".join(chunks)
                secs = len(pcm) / 2 / backend.sample_rate
                (out / f"{case['label']}.wav").write_bytes(gw.wav_bytes(pcm, backend.sample_rate))
                row.update(audio_s=round(secs, 1), gen_s=round(gen, 1), rtf=round(gen / secs, 2) if secs else None,
                           first_audio_s=round(first or 0, 2), peak_rss_gb=round(rss_gb(), 1))
            except Exception as exc:  # noqa: BLE001
                row["error"] = f"{type(exc).__name__}: {exc}"[:500]
            print(json.dumps(row, ensure_ascii=False), flush=True)
            results.append(row)
    (out / "results.json").write_text(json.dumps(results, indent=2, ensure_ascii=False))


if __name__ == "__main__":
    main()
