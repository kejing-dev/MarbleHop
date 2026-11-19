#!/usr/bin/env bash
# tools/shot.sh SERIAL OUT.png : full-frame emulator screenshot through the emulator console (macOS / Windows Git Bash)
if [ -z "${ADB:-}" ]; then
  if command -v adb >/dev/null 2>&1; then ADB=adb
  elif [ -x "$HOME/Library/Android/sdk/platform-tools/adb" ]; then ADB="$HOME/Library/Android/sdk/platform-tools/adb"
  elif [ -n "${LOCALAPPDATA:-}" ]; then ADB="$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"
  else ADB=adb; fi
fi
tmp="$(mktemp -d)"
"$ADB" -s "$1" emu screenrecord screenshot "$(cygpath -w "$tmp" 2>/dev/null || echo "$tmp")" >/dev/null
f="$(ls -t "$tmp"/*.png | head -1)"
mkdir -p "$(dirname "$2")"
mv "$f" "$2" && rm -rf "$tmp" && echo "$2"
