#!/usr/bin/env bash
# tools/mh.sh SERIAL cmd NAME [ARG] : debug broadcast   ·   tools/mh.sh SERIAL log [REGEX] : logcat of tag MarbleHop
# adb: $ADB, else adb on PATH, else the Android SDK default for macOS / Windows (Git Bash).
if [ -z "${ADB:-}" ]; then
  if command -v adb >/dev/null 2>&1; then ADB=adb
  elif [ -x "$HOME/Library/Android/sdk/platform-tools/adb" ]; then ADB="$HOME/Library/Android/sdk/platform-tools/adb"
  elif [ -n "${LOCALAPPDATA:-}" ]; then ADB="$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"
  else ADB=adb; fi
fi
PKG=io.embodit.marblehop
D="$1"; shift
case "${1:-}" in
  cmd) "$ADB" -s "$D" shell am broadcast -a $PKG.DEBUG -p $PKG --es cmd "$2" --es a "\"${3:-}\"" >/dev/null ;;
  log) "$ADB" -s "$D" logcat -d -s MarbleHop:V | grep -E "${2:-.}" ;;
  *) sed -n 2p "$0" ;;
esac
