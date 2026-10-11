#!/usr/bin/env bash
# Stage the native payloads that upstream's verified-native.gradle demands, from the official
# Aether v2.3.0 release archives, and check every one against the hashes pinned in
# native-inputs/android/. Nothing here trusts a downloaded file: the archive digest, each
# payload digest, and the payload size all have to agree with the manifest.
set -euo pipefail

# Resolve the repo root from this script's own location rather than $HOME: the checkout path
# differs between a developer machine and a CI runner, and a hardcoded one silently resolves
# to a directory that exists in exactly one of them.
REPO=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
EVID="$REPO/native-inputs/android"
JNI="$EVID/verified-jni"
HELPERS="$EVID/verified-helpers"
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

cd "$REPO"
pin() { node -e "process.stdout.write(require('./scripts/aether-pins.json').android.$1 || '')"; }
helper_pin() { node -e "process.stdout.write(require('./native-inputs/android/verified-helper-inputs.json')['$1/$2'].sha256 || '')"; }
helper_bytes() { node -e "process.stdout.write(String(require('./native-inputs/android/verified-helper-inputs.json')['$1/$2'].bytes || ''))"; }

BASE=$(pin downloadBaseUrl)
echo "base=$BASE"

ok=0; fail=0
for abi in arm64-v8a armeabi-v7a x86_64; do
  case "$abi" in
    arm64-v8a)   target=arm64 ;;
    armeabi-v7a) target=armv7 ;;
    x86_64)      target=x86_64 ;;
  esac
  archive="aether-android-${target}.tar.gz"
  expect_archive=$(pin "archives['$archive']")
  expect_aether=$(pin "binary['$archive']")

  echo "=== $abi ($archive)"
  if [ ! -f "$TMP/$archive" ]; then
    curl -sL --fail "$BASE/$archive" -o "$TMP/$archive"
  fi
  got_archive=$(sha256sum "$TMP/$archive" | cut -d' ' -f1)
  [ "$got_archive" = "$expect_archive" ] || { echo "  ARCHIVE MISMATCH"; fail=$((fail+1)); continue; }
  echo "  archive digest ok"

  rm -rf "$TMP/x"; mkdir -p "$TMP/x"
  tar -xzf "$TMP/$archive" -C "$TMP/x"

  # libaether.so: the manifest's sha256 for this ABI
  core=$(find "$TMP/x" -type f -name aether | head -1)
  [ -n "$core" ] || { echo "  no 'aether' payload in archive"; fail=$((fail+1)); continue; }
  got_core=$(sha256sum "$core" | cut -d' ' -f1)
  if [ "$got_core" != "$expect_aether" ]; then
    echo "  AETHER PAYLOAD MISMATCH"
    fail=$((fail+1))
  else
    mkdir -p "$JNI/$abi"
    cp "$core" "$JNI/$abi/libaether.so"
    echo "  libaether.so ok ($(stat -c%s "$JNI/$abi/libaether.so")B)"
    ok=$((ok+1))
  fi

  # pt/ helpers
  for h in psiphon-tunnel-core lyrebird; do
    src="$TMP/x/pt/$h"
    [ -f "$src" ] || src=$(find "$TMP/x" -type f -path "*/pt/$h" | head -1)
    if [ -z "$src" ] || [ ! -f "$src" ]; then echo "  $h: NOT IN ARCHIVE"; fail=$((fail+1)); continue; fi
    want=$(helper_pin "$abi" "$h"); want_bytes=$(helper_bytes "$abi" "$h")
    got=$(sha256sum "$src" | cut -d' ' -f1)
    got_bytes=$(stat -c%s "$src")
    if [ "$got" != "$want" ] || [ "$got_bytes" != "$want_bytes" ]; then
      echo "  $h: MISMATCH (sha $got vs $want, bytes $got_bytes vs $want_bytes)"
      fail=$((fail+1))
    else
      mkdir -p "$HELPERS/$abi"
      cp "$src" "$HELPERS/$abi/$h"
      chmod +x "$HELPERS/$abi/$h"
      echo "  $h ok ($got_bytes B)"
      ok=$((ok+1))
    fi
  done
done

echo
echo "staged ok=$ok  problems=$fail"