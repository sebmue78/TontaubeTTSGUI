# Änderungen

Alle nennenswerten Änderungen an der macOS-App und der Android-App „Tontaube Vorlesen“.
Beide teilen sich eine Versionsnummer ([Semantic Versioning](https://semver.org/lang/de/)):
`MAJOR.MINOR.PATCH`. Neue Einträge kommen unter **Unveröffentlicht**; beim Release wird
daraus der Abschnitt der neuen Version (siehe [Releases erstellen](README.md#releases-erstellen)).

## [Unveröffentlicht]

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
