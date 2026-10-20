#!/bin/sh
# Produit l'APK release signé dans dist/PubBornes-<version>.apk
set -e
cd "$(dirname "$0")"
[ -z "$JAVA_HOME" ] && [ -d /opt/homebrew/opt/openjdk@17 ] && export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
[ -f keystore.properties ] || { echo "ERREUR : keystore.properties manquant (voir README)"; exit 1; }
./gradlew --console=plain clean assembleRelease
VERSION=$(grep -E 'versionName *=' app/build.gradle.kts | sed -E 's/.*"(.*)".*/\1/')
mkdir -p dist
cp app/build/outputs/apk/release/app-release.apk "dist/PubBornes-$VERSION.apk"
echo "APK prêt : dist/PubBornes-$VERSION.apk"
