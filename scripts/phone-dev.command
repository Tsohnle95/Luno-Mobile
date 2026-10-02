#!/bin/bash
# Keep signing credentials in this Terminal session, never in the repository.
set -euo pipefail
set +x
umask 077

REPO_DIR="$(cd "$(dirname "$0")/.." && pwd)"
SDK_DIR="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
KEYSTORE_PATH="${LUNO_DEV_KEYSTORE:-$HOME/Documents/Luno-Mobile-Signing/luno-release.jks}"
KEY_ALIAS="${LUNO_DEV_KEY_ALIAS:-luno}"
BUILD_TOOLS_DIR="$SDK_DIR/build-tools/35.0.0"
DEV_DIR="$REPO_DIR/app/build/phone-dev"
mkdir -p "$DEV_DIR"
printf 'waiting-for-password\n' > "$DEV_DIR/status"
trap 'unset LUNO_DEV_STORE_PASSWORD LUNO_DEV_KEY_PASSWORD; printf "stopped\n" > "$DEV_DIR/status"' EXIT

printf 'Luno phone development installer\nKeystore: %s\n' "$KEYSTORE_PATH"
printf 'Keystore password (hidden): '
IFS= read -r -s LUNO_DEV_STORE_PASSWORD
printf '\nKey password (Return if the same, hidden): '
IFS= read -r -s LUNO_DEV_KEY_PASSWORD
printf '\n'
LUNO_DEV_KEY_PASSWORD="${LUNO_DEV_KEY_PASSWORD:-$LUNO_DEV_STORE_PASSWORD}"
export LUNO_DEV_STORE_PASSWORD LUNO_DEV_KEY_PASSWORD

printf 'Ready. Leave this window open; new build requests install automatically. Ctrl+C stops it.\n'
printf 'ready\n' > "$DEV_DIR/status"
last_request=''
if [[ -f "$DEV_DIR/request" ]]; then last_request="$(cat "$DEV_DIR/request")"; fi
while true; do
    request=''
    if [[ -f "$DEV_DIR/request" ]]; then request="$(cat "$DEV_DIR/request")"; fi
    if [[ -n "$request" && "$request" != "$last_request" ]]; then
        last_request="$request"
        printf 'signing\n' > "$DEV_DIR/status"
        if "$BUILD_TOOLS_DIR/apksigner" sign --ks "$KEYSTORE_PATH" --ks-key-alias "$KEY_ALIAS" \
            --ks-pass env:LUNO_DEV_STORE_PASSWORD --key-pass env:LUNO_DEV_KEY_PASSWORD \
            --out "$DEV_DIR/Luno-phone-dev.apk" "$REPO_DIR/app/build/outputs/apk/debug/app-debug.apk" \
            && "$BUILD_TOOLS_DIR/apksigner" verify "$DEV_DIR/Luno-phone-dev.apk" \
            && "$SDK_DIR/platform-tools/adb" install -r "$DEV_DIR/Luno-phone-dev.apk" \
            && "$SDK_DIR/platform-tools/adb" shell am start -n com.luno.mobile/.MainActivity; then
            printf 'installed %s\n' "$request" > "$DEV_DIR/status"
            printf 'Installed the development build. Library data retained.\n'
        else
            printf 'failed %s\n' "$request" > "$DEV_DIR/status"
            printf 'Install failed. No uninstall or data clearing was performed.\n' >&2
        fi
    fi
    sleep 1
done
