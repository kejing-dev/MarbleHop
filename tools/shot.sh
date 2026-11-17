#!/usr/bin/env bash
# tools/shot.sh SERIAL OUT.png : full-frame emulator screenshot through the emulator console (kj-alienware)
ADB="${ADB:-/c/Users/Admin/Android/Sdk/platform-tools/adb.exe}"
tmp="$(mktemp -d)"
"$ADB" -s "$1" emu screenrecord screenshot "$(cygpath -w "$tmp" 2>/dev/null || echo "$tmp")" >/dev/null
f="$(ls -t "$tmp"/*.png | head -1)"
mkdir -p "$(dirname "$2")"
mv "$f" "$2" && rm -rf "$tmp" && echo "$2"
