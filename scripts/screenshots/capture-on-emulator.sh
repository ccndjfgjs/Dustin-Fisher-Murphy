#!/usr/bin/env bash
# Requires an isolated, already booted emulator, JDK 17 and Android SDK.
# Run from a workspace containing read-only ./before and the current ./after checkout.
set -euo pipefail
root="${GITHUB_WORKSPACE:-$(pwd)}"
fixture="$root/after/scripts/screenshots/ScreenshotCaptureTest.kt"
out="$root/after/docs/screenshots"
mkdir -p "$out"
for version in before after; do
  checkout="$root/$version"
  cp "$fixture" "$checkout/app/src/androidTest/java/dev/opencode/mobile/ScreenshotCaptureTest.kt"
  (
    cd "$checkout"
    buildlog="$(mktemp)"
    if ! ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon 2>&1 | tee "$buildlog"; then
      python3 - "$buildlog" <<'PY'
import re, sys
for line in open(sys.argv[1], errors='replace'):
    if re.search(r'(^e: |^error:|^FAILURE:|^> Task .*FAILED|^\* What went wrong|Execution failed for task)', line):
        print('::error::' + line.strip().replace('%', '%25')[:1200])
PY
      exit 1
    fi
    rm -f "$buildlog"
    adb uninstall dev.opencode.mobile.debug >/dev/null 2>&1 || true
    adb install -r app/build/outputs/apk/debug/app-debug.apk
    adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
    log="$(mktemp)"
    adb shell am instrument -w -r -e class dev.opencode.mobile.ScreenshotCaptureTest dev.opencode.mobile.debug.test/androidx.test.runner.AndroidJUnitRunner | tee "$log"
    if ! grep -q 'OK (1 test)' "$log"; then
      python3 - "$log" <<'PY'
import sys
text = open(sys.argv[1], errors='replace').read()
print('::error::Screenshot instrumentation failed: ' + text[-8000:].replace('%', '%25').replace('\r', '%0D').replace('\n', '%0A'))
PY
      exit 1
    fi
    rm -f "$log"
    for screen in chat files terminal settings; do
      adb exec-out run-as dev.opencode.mobile.debug cat "files/captures/$screen.png" > "$out/$version-$screen.png"
      test -s "$out/$version-$screen.png"
    done
    adb uninstall dev.opencode.mobile.debug.test >/dev/null 2>&1 || true
    adb uninstall dev.opencode.mobile.debug >/dev/null 2>&1 || true
    rm app/src/androidTest/java/dev/opencode/mobile/ScreenshotCaptureTest.kt
  )
done
