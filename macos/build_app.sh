#!/bin/bash
# Baut "Tontaube TTS.app" (und optional ein DMG) für macOS auf Apple Silicon.
#
#   ./macos/build_app.sh            -> dist/Tontaube TTS.app
#   ./macos/build_app.sh --dmg      -> zusätzlich dist/Tontaube-TTS.dmg
#   ./macos/build_app.sh --install  -> zusätzlich nach /Applications kopieren
#
# Voraussetzung: uv (https://docs.astral.sh/uv/) ist installiert, z. B. `brew install uv`.
# Das uv-Programm wird in die App kopiert, damit sie auf dem Ziel-Mac ohne weitere
# Werkzeuge läuft.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APP_NAME="Tontaube TTS"
DIST="$ROOT/dist"
APP="$DIST/$APP_NAME.app"
VERSION="$(sed -n 's/^__version__ = "\(.*\)"/\1/p' "$ROOT/tontaube_gui/__init__.py")"

MAKE_DMG=0; INSTALL=0
for arg in "$@"; do
  case "$arg" in
    --dmg) MAKE_DMG=1 ;;
    --install) INSTALL=1 ;;
    *) echo "Unbekannte Option: $arg" >&2; exit 2 ;;
  esac
done

if [ "$(uname -s)" != "Darwin" ]; then echo "Dieses Skript läuft nur unter macOS." >&2; exit 1; fi
UV_BIN="${UV_BIN:-$(command -v uv || true)}"
if [ -z "$UV_BIN" ]; then echo "uv nicht gefunden. Bitte zuerst 'brew install uv' ausführen." >&2; exit 1; fi

echo "==> Baue $APP (Version $VERSION)"
rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources/bin" "$APP/Contents/Resources/app"

# App-Code
rsync -a --exclude '__pycache__' "$ROOT/tontaube_gui" "$APP/Contents/Resources/app/"
cp "$ROOT/requirements.txt" "$APP/Contents/Resources/app/requirements.txt"
cp "$UV_BIN" "$APP/Contents/Resources/bin/uv"
chmod +x "$APP/Contents/Resources/bin/uv"

# Symbol
if [ -f "$ROOT/macos/icon.png" ]; then
  ICONSET="$DIST/AppIcon.iconset"
  rm -rf "$ICONSET"; mkdir -p "$ICONSET"
  for size in 16 32 128 256 512; do
    sips -z $size $size "$ROOT/macos/icon.png" --out "$ICONSET/icon_${size}x${size}.png" >/dev/null
    sips -z $((size * 2)) $((size * 2)) "$ROOT/macos/icon.png" --out "$ICONSET/icon_${size}x${size}@2x.png" >/dev/null
  done
  iconutil -c icns "$ICONSET" -o "$APP/Contents/Resources/AppIcon.icns" || echo "Hinweis: Symbol konnte nicht erzeugt werden"
  rm -rf "$ICONSET"
fi

cat > "$APP/Contents/Info.plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>CFBundleName</key><string>$APP_NAME</string>
  <key>CFBundleDisplayName</key><string>$APP_NAME</string>
  <key>CFBundleIdentifier</key><string>io.github.sebmue78.tontaube-tts</string>
  <key>CFBundleVersion</key><string>$VERSION</string>
  <key>CFBundleShortVersionString</key><string>$VERSION</string>
  <key>CFBundlePackageType</key><string>APPL</string>
  <key>CFBundleExecutable</key><string>TontaubeTTS</string>
  <key>CFBundleIconFile</key><string>AppIcon</string>
  <key>LSMinimumSystemVersion</key><string>13.0</string>
  <key>LSArchitecturePriority</key><array><string>arm64</string></array>
  <key>NSHighResolutionCapable</key><true/>
</dict>
</plist>
PLIST

cp "$ROOT/macos/launcher.sh" "$APP/Contents/MacOS/TontaubeTTS"
chmod +x "$APP/Contents/MacOS/TontaubeTTS"

# Ad-hoc-Signatur, damit macOS das Bundle als Einheit behandelt.
codesign --force --deep --sign - "$APP" >/dev/null 2>&1 || echo "Hinweis: ad-hoc-Signatur übersprungen"

if [ "$MAKE_DMG" = 1 ]; then
  echo "==> Erzeuge DMG"
  STAGE="$DIST/dmg"
  rm -rf "$STAGE" "$DIST/Tontaube-TTS.dmg"
  mkdir -p "$STAGE"
  cp -R "$APP" "$STAGE/"
  ln -s /Applications "$STAGE/Programme"
  hdiutil create -volname "$APP_NAME" -srcfolder "$STAGE" -ov -format UDZO "$DIST/Tontaube-TTS.dmg" >/dev/null
  rm -rf "$STAGE"
  echo "    $DIST/Tontaube-TTS.dmg"
fi

if [ "$INSTALL" = 1 ]; then
  echo "==> Installiere nach /Applications"
  rm -rf "/Applications/$APP_NAME.app"
  cp -R "$APP" /Applications/
  xattr -dr com.apple.quarantine "/Applications/$APP_NAME.app" 2>/dev/null || true
fi

echo "Fertig: $APP"
