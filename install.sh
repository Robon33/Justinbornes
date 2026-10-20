#!/bin/sh
# Installe (ou met à jour) l'APK sur toutes les bornes branchées / connectées en ADB.
set -e
cd "$(dirname "$0")"
ADB=$(command -v adb || echo /opt/homebrew/share/android-commandlinetools/platform-tools/adb)
APK=${1:-$(ls -t dist/*.apk 2>/dev/null | head -1)}
[ -f "$APK" ] || { echo "Aucun APK : lancez d'abord ./build-release.sh"; exit 1; }
SERIALS=$($ADB devices | awk 'NR>1 && $2=="device" {print $1}')
[ -n "$SERIALS" ] || { echo "Aucune borne détectée (adb devices)"; exit 1; }
for S in $SERIALS; do
  echo "== $S : installation de $APK"
  $ADB -s "$S" install -r "$APK"
  $ADB -s "$S" shell am start -n com.pubbornes.player/.PlayerActivity
done
