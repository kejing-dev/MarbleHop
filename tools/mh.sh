#!/usr/bin/env bash
# tools/mh.sh SERIAL cmd NAME [ARG] : debug broadcast   ·   tools/mh.sh SERIAL log [REGEX] : logcat of tag MarbleHop
ADB="${ADB:-/c/Users/Admin/Android/Sdk/platform-tools/adb.exe}"
PKG=io.embodit.marblehop
D="$1"; shift
case "${1:-}" in
  cmd) "$ADB" -s "$D" shell am broadcast -a $PKG.DEBUG -p $PKG --es cmd "$2" --es a "\"${3:-}\"" >/dev/null ;;
  log) "$ADB" -s "$D" logcat -d -s MarbleHop:V | grep -E "${2:-.}" ;;
  *) sed -n 2p "$0" ;;
esac
