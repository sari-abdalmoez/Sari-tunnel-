#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$ROOT/app/libs/xray.aar"
mkdir -p "$(dirname "$OUT")"

command -v go >/dev/null 2>&1 || { echo "Go 1.26+ is required to build the Xray Android library." >&2; exit 1; }
if [[ -z "${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}" ]]; then
  echo "ANDROID_HOME or ANDROID_SDK_ROOT must point to an installed Android SDK." >&2
  exit 1
fi
export ANDROID_HOME="${ANDROID_HOME:-$ANDROID_SDK_ROOT}"
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$ANDROID_HOME}"
export ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-$ANDROID_HOME/ndk/26.1.10909125}"
export ANDROID_NDK_ROOT="${ANDROID_NDK_ROOT:-$ANDROID_NDK_HOME}"
export PATH="$PATH:$(go env GOPATH)/bin"
export GONOSUMDB="${GONOSUMDB:-github.com/xtls/xray-core}"

cd "$ROOT/xray-go"
echo "Resolving pinned Xray-core dependencies..."
go mod tidy
MOBILE_VERSION="v0.0.0-20260709172247-6129f5bee9d5"
echo "Installing gomobile/gobind $MOBILE_VERSION..."
go install "golang.org/x/mobile/cmd/gomobile@$MOBILE_VERSION"
go install "golang.org/x/mobile/cmd/gobind@$MOBILE_VERSION"
gomobile init
echo "Building Xray AAR (arm64-v8a + armeabi-v7a)..."
gomobile bind \
  -target=android/arm64,android/arm \
  -androidapi=24 \
  -trimpath \
  -ldflags='-s -w -buildid= -checklinkname=0' \
  -o "$OUT" \
  .
test -s "$OUT"
echo "Built $OUT"
