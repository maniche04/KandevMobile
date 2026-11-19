#!/bin/sh
# Copy a signed release APK into an operator-selected download directory and publish it as the in-app update
# (Settings › About › App updates): writes <applicationId>.json with the APK's version and SHA-256.
# Usage: scripts/publish-apk.sh DESTINATION_DIRECTORY [PUBLIC_BASE_URL]   (NOTES="one line" for what's new)
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
D=${1:?Usage: scripts/publish-apk.sh DESTINATION_DIRECTORY [PUBLIC_BASE_URL]}
SRC="$ROOT/app/build/outputs/apk/release/app-release.apk"
[ -f "$SRC" ] || { echo "Build and sign a release APK first: $SRC" >&2; exit 1; }
SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/android-dev/sdk}}
AAPT=$(ls -d "$SDK"/build-tools/*/aapt2 2>/dev/null | sort -V | tail -1)
[ -n "$AAPT" ] || { echo "aapt2 not found; set ANDROID_HOME" >&2; exit 1; }
BADGE=$("$AAPT" dump badging "$SRC" | head -n 1)
PKG=$(echo "$BADGE" | sed -E "s/.* name='([^']+)'.*/\1/")
CODE=$(echo "$BADGE" | sed -E "s/.*versionCode='([0-9]+)'.*/\1/")
VERSION=$(echo "$BADGE" | sed -E "s/.*versionName='([^']+)'.*/\1/")
mkdir -p "$D"
NAME="forge-$VERSION-$(date -u +%Y%m%d-%H%M%S).apk"
cp "$SRC" "$D/$NAME"
cp "$SRC" "$D/forge-latest.apk.tmp"
mv "$D/forge-latest.apk.tmp" "$D/forge-latest.apk"
SHA=$(sha256sum "$SRC" | cut -d' ' -f1)
SIZE=$(wc -c < "$SRC" | tr -d ' ')
# The manifest goes last: a phone never sees a version whose APK isn't there yet.
python3 -c 'import json,sys; print(json.dumps({"version_code": int(sys.argv[1]), "version_name": sys.argv[2], "apk": sys.argv[3], "sha256": sys.argv[4], "size": int(sys.argv[5]), "notes": sys.argv[6]}))' \
    "$CODE" "$VERSION" "$NAME" "$SHA" "$SIZE" "${NOTES:-}" > "$D/$PKG.json.tmp"
mv "$D/$PKG.json.tmp" "$D/$PKG.json"
echo "Copied $D/$NAME and $D/forge-latest.apk; published $PKG $VERSION ($CODE) · sha256 $SHA"
if [ -n "${2:-}" ]; then echo "${2%/}/forge-latest.apk"; fi
