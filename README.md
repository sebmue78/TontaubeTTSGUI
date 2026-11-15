# Tontaube TTS GUI

Eine Weboberfläche (Gradio) für das Sprachsynthese-Modell
[**TontaubeV1.1**](https://huggingface.co/TontaubeAI/TontaubeV1.1).

![Screenshot](docs/screenshot.png)

## Funktionen

| Bereich | Was die GUI kann |
|---|---|
| **Sprachen** | Deutsch, Englisch (Hauptsprachen) sowie Spanisch, Französisch, Italienisch, Niederländisch, Portugiesisch |
| **Sprechstile** | Konversation, Hörbuch, Agentisch (`tag`) |
| **Stil-Regler** | Tempo (0–4), Pausen (0–4), Tonhöhen-Umfang (0–5), Stimmlage (0–4), Artikulation/Legato (0–2). Jeder Regler einzeln aktivierbar, sonst entscheidet das Modell. Die Regler werden beim Verbinden live von `GET /controls` geladen. |
| **Stimmen** | Standardstimme (Marcus), mitgelieferte Stimmen per `voice_path`, **Voice Cloning** mit eigenem Referenzaudio (Upload oder Mikrofon, 5–60 s, mit Längenprüfung) |
| **Sampling** | Temperatur (Standard 0.7) |
| **Lange Texte** | Absätze als Segmente senden, optional mit eigenen Reglern pro Segment (`segment_controls`) |
| **Fortsetzen** | Neuen Text nahtlos an das letzte Ergebnis anhängen (`prefix_text` / `prefix_tokens`); WAV-Teile werden automatisch zusammengefügt |
| **Live-Streaming** | MP3-Streaming über `/stream` (SSE) mit sofortiger Wiedergabe, einstellbar: Bitrate, Priorität, `max_new_tokens`, Länge des ersten Chunks |
| **Nachbearbeitung** | MossFormer2-Verbesserung (48 kHz) an/aus, Rand-Stille trimmen (Standard / aus / eigener Wert) |
| **Verbalizer** | Optional englischen Verbalizer nutzen (Zahlen, Abkürzungen ausschreiben) |
| **Export** | WAV, MP3 oder Opus. Jede Datei wird mit einer JSON-Datei der verwendeten Parameter in `outputs/` gespeichert und ist in der GUI herunterladbar |
| **Verlauf** | Alle Ergebnisse der Sitzung mit Download |
| **Server** | Frei wählbare Server-URL, API-Key (`x-api-key`), Verbindungstest (`/readyz`), Anzeige der gesendeten Anfrage, freie Zusatzfelder als JSON |

## All-in-one-App für macOS (Apple Silicon)

> **Experimentell.** Der offizielle Tontaube-Server unterstützt macOS nicht. Diese App bringt
> einen kleinen Patch mit, der den Server auf Apple Silicon lauffähig macht (siehe unten).

Die App richtet beim ersten Start alles selbst ein:

1. Oberfläche installieren (ca. 1–2 Minuten)
2. Tontaube-Server v1.1.0 herunterladen und für Apple Silicon anpassen
3. Python-Umgebung mit PyTorch einrichten (einige Minuten)
4. Modelle herunterladen (ca. 8 GB, nur beim ersten Start)

Den Fortschritt zeigt die Oberfläche oben an. Ab dem zweiten Start ist sie nach kurzer Zeit bereit.

### App bauen und installieren

```bash
brew install uv            # einmalig
git clone -b macos-app https://github.com/sebmue78/TontaubeTTSGUI.git
cd TontaubeTTSGUI
./macos/build_app.sh --install      # baut "Tontaube TTS.app" und kopiert sie nach /Programme
# oder: ./macos/build_app.sh --dmg  # erzeugt dist/Tontaube-TTS.dmg zum Weitergeben
```

Die App ist nicht von Apple notarisiert. Auf einem anderen Mac beim ersten Öffnen deshalb
Rechtsklick → **Öffnen** wählen.

### Wo liegt was?

| Was | Ort |
|---|---|
| Erzeugte Audios | `~/Tontaube TTS/` |
| Server, Python-Umgebungen, Protokolle | `~/Library/Application Support/TontaubeTTS/` |
| Modelle | `~/.cache/huggingface/` und `~/.cache/tontaube/` |

Beenden: in der Oberfläche unter **Eingebauter Server → Tontaube beenden**. Zum vollständigen
Entfernen die App und die drei Ordner oben löschen.

### Was der Mac-Patch ändert

`tontaube_gui/server_assets/tontaube-v1.1.0-apple-silicon.patch`:

* Die x86-AVX2-Kernel des CPU-Backends werden auf anderen CPUs übersprungen; stattdessen rechnen
  die Modelle mit normalen PyTorch-Operationen (int4-Gewichte werden beim Laden entpackt).
  `LITE_CPU_DTYPE` wählt `bfloat16` (Standard, halber Speicher) oder `float32`.
* vLLM wird nicht installiert (gibt es für macOS nicht; das „lite“-Backend braucht es nicht).
* libopus ist optional (nur für den WebSocket-Opus-Stream nötig).

ffmpeg und opus werden bei Bedarf über Homebrew installiert.

## Wie es aufgebaut ist

TontaubeV1.1 ist kein normales `transformers`-Modell. Laut Modellkarte läuft es nur über den
offiziellen Inferenzserver [craitech/tontaube](https://github.com/craitech/tontaube)
(Release **v1.1.0**). Diese GUI ist deshalb ein schlanker Client für dessen HTTP-API:

```
Browser ──► Gradio-GUI (diese App, Port 7860) ──► Tontaube-Server (Port 8080) ──► Modell (GPU/CPU)
```

Vorteil: Die GUI braucht weder GPU noch PyTorch und kann auch auf einem anderen Rechner
laufen als der Server.

## Installation

### 1. Tontaube-Server starten

Siehe README des Inferenzservers. Kurzfassung:

```bash
git clone --branch v1.1.0 https://github.com/craitech/tontaube.git
cd tontaube

# Variante A (empfohlen für die meisten): int4 / "lite", ca. 4–6,5 GiB GPU oder CPU
uv run --python 3.12 --frozen tontaube serve

# Variante B: volle Präzision mit vLLM, NVIDIA-GPU mit ≥ 24 GiB
TTS_BACKEND=vllm uv run --python 3.12 --frozen tontaube serve
```

Nützliche Server-Variablen: `ENABLE_VERBALIZATION=1` (Verbalizer), `REQUIRE_API_KEY=1` +
`TTS_API_KEY=…` (Absicherung), `LITE_DEVICE=cpu` (CPU-Betrieb).

### 2. GUI starten

Python 3.10 oder neuer:

```bash
git clone https://github.com/sebmue78/TontaubeTTSGUI.git
cd TontaubeTTSGUI
python -m venv .venv
source .venv/bin/activate          # Windows: .venv\Scripts\activate
pip install -r requirements.txt
python -m tontaube_gui
```

Dann <http://127.0.0.1:7860> öffnen und oben auf **Verbindung testen** klicken.

Optionen:

```bash
python -m tontaube_gui --server-url http://gpu-rechner:8080 --api-key GEHEIM --port 7860
python -m tontaube_gui --host 0.0.0.0     # im LAN erreichbar
python -m tontaube_gui --share            # öffentlicher Gradio-Link
python -m tontaube_gui --managed          # All-in-one: Server selbst einrichten und starten
```

Auch per Umgebungsvariable: `TONTAUBE_URL`, `TONTAUBE_API_KEY`, `GUI_HOST`, `GUI_PORT`,
`TONTAUBE_OUTPUT_DIR` (Standard `./outputs`).

## Ohne GPU ausprobieren (Mock-Server)

`tools/mock_server.py` imitiert die Server-API und liefert Sinustöne statt Sprache. Damit lässt
sich die Oberfläche testen:

```bash
pip install -r requirements-dev.txt
python tools/mock_server.py        # Terminal 1, Port 8080
python -m tontaube_gui             # Terminal 2
```

Tests: `python -m pytest`

## Annahmen und offene Punkte

Die Modellkarte und das Server-README beschreiben die API nicht vollständig. Wo etwas fehlte,
habe ich Folgendes angenommen. Die genaue API zeigt der laufende Server unter
`http://127.0.0.1:8080/docs`.

* **Sprach-Strings:** belegt ist nur `"english"`. Für die anderen Sprachen wird dasselbe Muster
  verwendet (`german`, `spanish`, `french`, `italian`, `dutch`, `portuguese`). Das Feld erlaubt
  auch eigene Werte, falls der Server andere erwartet.
* **Stimmenliste:** Der Server liefert 39 Stimmen mit, dokumentiert aber keinen Endpunkt, der sie
  auflistet. Die GUI probiert `/voices`; antwortet der Server nicht, kann der Pfad (z. B.
  `samples/Marcus.mp3`) frei eingetragen werden.
* **`/controls`-Format:** nicht dokumentiert. Die GUI versteht mehrere gängige Formen und fällt
  sonst auf die Werte aus der Modellkarte zurück.
* **SSE-Format von `/stream`:** nicht dokumentiert. Die GUI erwartet Base64-Audio in `data:`-Zeilen
  (als JSON mit `audio_b64`/`audio`/`chunk` oder als roher Base64-Text).
* **Fortsetzen** funktioniert nur, wenn `/predict` `codebook_tokens` zurückgibt. Sonst zeigt die
  GUI einen Hinweis.
* **Nicht umgesetzt:** der WebSocket-Stream `/ws/stream` (Opus; Frame-Format nicht dokumentiert,
  der SSE-Stream deckt Live-Wiedergabe ab) sowie `voice_tokens` (vorberechnete Stimmen-Tokens).
  Beides lässt sich über *Erweitert → Zusätzliche Felder* trotzdem an den Server schicken.

## Lizenz

Der Code dieser GUI gehört dem Repository-Inhaber. Das Modell TontaubeV1.1 steht unter der
*Tontaube Community Model License 1.0* (keine Open-Source-Lizenz). Bitte deren Bedingungen
beachten, insbesondere bei geklonten Stimmen und der Weitergabe erzeugter Audios.
