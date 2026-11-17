#!/usr/bin/env bash
# ============================================================================
# build-release.sh -- build LC from a clean clone, signed
# ----------------------------------------------------------------------------
# Used by CI and by publish-release.sh. The signing key is generated locally on
# first use (kept out of git by .gitignore) so the same key can be reused to
# ship updates. Set LC_KEYSTORE / LC_STOREPASS to reuse an existing key.
# The keystore and its password are written to dist/ so the owner can publish
# them alongside the APK and rebuild future updates with the same key.
# ============================================================================
set -euo pipefail

cd "$(dirname "$0")/.."
ROOT="$(pwd)"
DIST="$ROOT/dist"
mkdir -p "$DIST"

: "${ANDROID_HOME:=${ANDROID_SDK_ROOT:-/usr/local/lib/android/sdk}}"
export ANDROID_HOME

STORE_FILE="${LC_KEYSTORE:-$DIST/lc.keystore}"
STORE_PASS="${LC_STOREPASS:-}"

if [ -f "$DIST/keystore.properties" ]; then
    . "$DIST/keystore.properties"
    STORE_FILE="${LC_KEYSTORE:-$STORE_FILE}"
    STORE_PASS="${LC_STOREPASS:-$storePassword}"
fi

if [ ! -f "$STORE_FILE" ]; then
    if [ -z "$STORE_PASS" ]; then
        STORE_PASS="$(head -c 24 /dev/urandom | base64 | tr -d '/+=')"
    fi
    echo "==> generating release keystore at $STORE_FILE"
    keytool -genkeypair -v \
        -keystore "$STORE_FILE" -storepass "$STORE_PASS" \
        -alias lc -keyalg RSA -keysize 2048 -validity 10000 \
        -dname "CN=LC console, OU=LC, O=LC, C=XX" >/dev/null 2>&1
    echo "==> keystore password: $STORE_PASS"
fi

printf 'storeFile=%s\nstorePassword=%s\nkeyAlias=lc\nkeyPassword=%s\n' \
    "$STORE_FILE" "$STORE_PASS" "$STORE_PASS" > "$ROOT/keystore.properties"

echo "==> building signed release APK and running the unit tests"
./gradlew --no-daemon :app:assembleRelease :app:test

VERSION="$(grep -oP 'versionName = "\K[^"]+' app/build.gradle.kts)"
APK="$DIST/lc-${VERSION}-arm64.apk"
cp -f "$ROOT/app/build/outputs/apk/release/app-release.apk" "$APK"
echo "==> $APK"
