#!/bin/bash
# Startprogramm in "Tontaube TTS.app": richtet beim ersten Start die GUI-Umgebung ein
# und startet die Oberfläche im All-in-one-Modus (Server + Modelle werden dort geladen).
set -u

RES="$(cd "$(dirname "$0")/../Resources" && pwd)"
DATA="$HOME/Library/Application Support/TontaubeTTS"
mkdir -p "$DATA/logs"
exec >>"$DATA/logs/app.log" 2>&1
echo "=== Start $(date) ==="

export PATH="$RES/bin:/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin"
export TONTAUBE_UV="$RES/bin/uv"
export TONTAUBE_HOME="$DATA"
export TONTAUBE_OUTPUT_DIR="${TONTAUBE_OUTPUT_DIR:-$HOME/Tontaube TTS}"
export GRADIO_ANALYTICS_ENABLED=False

notify() { osascript -e "display notification \"$1\" with title \"Tontaube TTS\"" >/dev/null 2>&1 || true; }
fail() {
  osascript -e "display alert \"Tontaube TTS\" message \"$1\n\nDetails: ~/Library/Application Support/TontaubeTTS/logs/app.log\" as critical" >/dev/null 2>&1
  exit 1
}

# Läuft schon? Dann nur das Fenster wieder öffnen.
if [ -f "$DATA/app.pid" ] && kill -0 "$(cat "$DATA/app.pid")" 2>/dev/null && [ -f "$DATA/app.port" ]; then
  open "http://127.0.0.1:$(cat "$DATA/app.port")"
  exit 0
fi

VENV="$DATA/gui-venv"
STAMP="$(cat "$RES/app/requirements.txt" | shasum | cut -c1-40)"
if [ ! -x "$VENV/bin/python" ] || [ "$(cat "$VENV/.stamp" 2>/dev/null)" != "$STAMP" ]; then
  notify "Erste Einrichtung läuft, das dauert 1–2 Minuten …"
  "$TONTAUBE_UV" venv --allow-existing --python 3.12 "$VENV" || fail "Python 3.12 konnte nicht eingerichtet werden."
  "$TONTAUBE_UV" pip install --python "$VENV/bin/python" -r "$RES/app/requirements.txt" \
    || fail "Die Oberfläche konnte nicht installiert werden (Internetverbindung?)."
  echo "$STAMP" > "$VENV/.stamp"
fi

PORT="$("$VENV/bin/python" - <<'PY'
import socket
for port in range(7860, 7900):
    with socket.socket() as s:
        try:
            s.bind(("127.0.0.1", port)); print(port); break
        except OSError:
            pass
PY
)"
echo "$$" > "$DATA/app.pid"
echo "$PORT" > "$DATA/app.port"
notify "Tontaube startet. Beim ersten Start werden die Modelle geladen."

cd "$DATA"
export PYTHONPATH="$RES/app"
exec "$VENV/bin/python" -m tontaube_gui --managed --inbrowser --port "$PORT"
