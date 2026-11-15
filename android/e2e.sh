#!/bin/bash
# Ende-zu-Ende-Test im Emulator (siehe .github/workflows/android-app.yml). Erwartet den Mock-Server auf Port 8080.
set -u
cd "$(dirname "$0")"
./gradlew --no-daemon installDebug installDebugAndroidTest || exit 1
adb shell am instrument -w -e class de.tontaube.vorlesen.EndToEndTest \
  de.tontaube.vorlesen.debug.test/androidx.test.runner.AndroidJUnitRunner | tee instrument.txt
mkdir -p e2e-screens
adb pull /sdcard/Android/data/de.tontaube.vorlesen.debug/files/screens/. e2e-screens/ || true
adb logcat -d -t 400 > e2e-logcat.txt || true
grep -q "^OK (" instrument.txt
