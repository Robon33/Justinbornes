#!/bin/sh
# Construit l'APK signé et le publie en Release GitHub (vX.Y = versionName).
# Lien permanent vers la dernière version, à ouvrir sur les bornes :
#   https://github.com/<compte>/<depot>/releases/latest/download/PubBornes.apk
set -e
cd "$(dirname "$0")"
./build-release.sh
VERSION=$(grep -E 'versionName *=' app/build.gradle.kts | sed -E 's/.*"(.*)".*/\1/')
cp "dist/PubBornes-$VERSION.apk" dist/PubBornes.apk
if gh release view "v$VERSION" >/dev/null 2>&1; then
  echo "ERREUR : la release v$VERSION existe déjà. Incrémentez versionCode/versionName."; exit 1
fi
gh release create "v$VERSION" dist/PubBornes.apk --title "Pub Bornes $VERSION" --notes "APK signé, à installer sur les bornes."
REPO=$(gh repo view --json nameWithOwner -q .nameWithOwner)
echo "Publié. Lien de téléchargement pour les bornes :"
echo "  https://github.com/$REPO/releases/latest/download/PubBornes.apk"
