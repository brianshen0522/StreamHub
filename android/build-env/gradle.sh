#!/usr/bin/env bash
# Run ./gradlew inside the Android build image: ./build-env/gradle.sh :tv:assembleDebug
# Gradle caches and SDK components AGP downloads on demand (build-tools)
# persist in named volumes; the SDK volume is seeded from the image.
set -euo pipefail
android="$(cd "$(dirname "$0")/.." && pwd)"
repo="$(dirname "$android")"
docker image inspect streamhub-android-build >/dev/null 2>&1 || docker build -q -t streamhub-android-build "$android/build-env" >/dev/null
exec docker run --rm -u "$(id -u):$(id -g)" -e HOME=/tmp/home -e GRADLE_USER_HOME=/gradle \
  -v streamhub-android-gradle:/gradle -v streamhub-android-sdk:/opt/android-sdk \
  -v "$repo":/src -w /src/android streamhub-android-build bash -c 'mkdir -p /tmp/home; ./gradlew --console=plain "$@"' _ "$@"
