#!/usr/bin/env bash
# Download the latest Lucid Nuvio TV IPTV APK.
set -euo pipefail

REPO="lucidconviction/Lucid-Nuvio-TV-IPTV"
ASSET="${1:-app-full-universal-debug.apk}"
OUT="${2:-LucidNuvioTV.apk}"
URL="https://github.com/${REPO}/releases/latest/download/${ASSET}"

echo "Downloading ${ASSET}…"
curl -L --fail --progress-bar -o "${OUT}" "${URL}"
echo "Saved to ${OUT}"
echo "Install with: adb install -r ${OUT}"
