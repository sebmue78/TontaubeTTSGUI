# Änderungen

Alle nennenswerten Änderungen an der macOS-App und der Android-App „Tontaube Vorlesen“.
Beide teilen sich eine Versionsnummer ([Semantic Versioning](https://semver.org/lang/de/)):
`MAJOR.MINOR.PATCH`. Neue Einträge kommen unter **Unveröffentlicht**; beim Release wird
daraus der Abschnitt der neuen Version (siehe [Releases erstellen](README.md#releases-erstellen)).

## [Unveröffentlicht]

### macOS-App (Apple Silicon)
- Die eingeschalteten Zusatzmodelle (Chatterbox, Qwen3-TTS) werden direkt nach dem Serverstart im Hintergrund heruntergeladen, nicht erst bei der ersten Anfrage. Der Fortschritt steht im Status („Zusatzmodelle werden heruntergeladen … 1.2 von 3.8 GB“) und im Gateway-Log. So bricht auch eine erste Anfrage aus der Android-App nicht mehr am langen Download ab.
- „Server neu starten“ übernimmt jetzt auch die Auswahl unter „Weitere Modelle“; vorher startete der Server ohne sie neu, und die Modelle fehlten in der Modellauswahl. Der Status weist darauf hin, wenn sie eingeschaltet, aber noch nicht aktiv sind.
- Die Modellauswahl wird alle 10 Sekunden mit dem Server abgeglichen, damit Chatterbox und Qwen3 auch erscheinen, wenn das Gateway erst nach dem Verbinden antwortet.

## [0.3.0] – 2026-11-17

### macOS-App (Apple Silicon)
- Weitere Modelle neben Tontaube (einschaltbar unter „Weitere Modelle“): **Chatterbox Multilingual** (Deutsch, Emotions-Regler), **Chatterbox Turbo** (nur Englisch, Tags wie `[laugh]` oder `[sigh]`) und **Qwen3-TTS** (Deutsch, fertige Sprecher mit Stil-Anweisung, Stimme per Beschreibung). Sie laufen über MLX auf der Apple-GPU in einer eigenen Umgebung; ein Modell-Gateway stellt sie unter derselben Adresse und mit demselben API-Key bereit.
- Oberfläche: Modellauswahl, Tag-Knöpfe für Chatterbox Turbo, Qwen3-Sprecher und Stil-Anweisung.

### Android-App „Tontaube Vorlesen“
- Modell pro Projekt wählbar, wenn der Server weitere Modelle anbietet. Chatterbox-Tags im Dokumenttext werden bei Chatterbox Turbo mitgelesen und bei anderen Modellen weggelassen.

## [0.2.0] – 2026-11-16

Erste versionierte Veröffentlichung.

### macOS-App (Apple Silicon)
- All-in-one-App: richtet Oberfläche, Tontaube-Server v1.1.0 (mit Apple-Silicon-Patch) und Modelle beim ersten Start selbst ein.
- Rechenwerk wählbar: Apple-GPU (Metal/MPS, float16, etwa 2,7× schneller als CPU) oder CPU, mit automatischem CPU-Rückfall.
- Eingebauten Server im WLAN freigeben (API-Key, QR-Code für die Android-App).
- Versionsnummer in der Oberfläche und im App-Bundle.

### Android-App „Tontaube Vorlesen“
- Dokumente importieren (PDF, EPUB, TXT, Markdown, HTML, DOCX, ODT, RTF, FB2, Text) mit Kapitelerkennung.
- Vorlese-Projekte mit eigener Stimme, Stil-Reglern und Streaming über den Tontaube-Server, Hintergrund-Wiedergabe und Zwischenspeicher.
- Eigene Stimmen aufnehmen oder wählen, 39 mitgelieferte Stimmen mit Hörprobe.
- Verbindung zur Desktop-App per QR-Code.
- Tipp auf „Bibliothek“ öffnet wieder die Bibliothek statt des zuletzt gewählten Tabs.
- Vorlese-Abschnitte enden immer an einem Satzende.
- Vorpuffer passt sich automatisch an die Geschwindigkeit des Servers an oder lässt sich fest einstellen (30 s bis 10 min), damit es keine ständigen kurzen Aussetzer gibt.
