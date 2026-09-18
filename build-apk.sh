#!/bin/bash
set -e
echo "Building MehmanshahrVpn..."
cd "$(dirname "$0")/android"
./gradlew assembleDebug
echo "Build complete. APK location:"
ls -lh app/build/outputs/apk/debug/*.apk 2>/dev/null || true
