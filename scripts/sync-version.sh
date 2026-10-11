#!/usr/bin/env bash
# Keeps every version declaration site in agreement, in both directions: with CHECK_ONLY=1 it
# reports what is out of date and exits non-zero, otherwise it rewrites. The version comes from
# android/app/build.gradle, the one the shipped APK actually carries.
#
# Five files have to agree here and each was bumped by hand at some point, which is how
# package.json stayed at 2.5.4 while the APK shipped 2.5.5 -- and the About page then showed a
# version that was not the one installed. The frontend test asserts agreement across all of them;
# this script is what makes that assertion cheap to satisfy.
set -euo pipefail

REPO=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
cd "$REPO"

VERSION=$(sed -n "s/.*versionName '\([^']*\)'.*/\1/p" android/app/build.gradle | head -1)
if ! printf '%s' "$VERSION" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+$'; then
  echo "could not read a plain three-component versionName from android/app/build.gradle" >&2
  exit 1
fi

# Escaped for grep -E: a literal dot has to become \. or 2.5.5 also matches 2x5y5.
DOT=$(printf '%s' "$VERSION" | sed 's/\./\\./g')
status=0

# JSON sites: package.json, tauri.conf.json
for file in package.json src-tauri/tauri.conf.json; do
  if grep -Eq "\"version\":[[:space:]]*\"$DOT\"" "$file"; then
    continue
  fi
  if [ "${CHECK_ONLY:-0}" = "1" ]; then
    echo "out of date: version \"$VERSION\" in $file" >&2
    status=1
  else
    sed -i -E "s#(\"version\":[[:space:]]*)\"[0-9]+\.[0-9]+\.[0-9]+\"#\1\"$VERSION\"#" "$file"
    echo "updated: version \"$VERSION\" in $file"
  fi
done

# Cargo.toml
if ! grep -Eq "^version = \"$DOT\"" src-tauri/Cargo.toml; then
  if [ "${CHECK_ONLY:-0}" = "1" ]; then
    echo "out of date: version \"$VERSION\" in src-tauri/Cargo.toml" >&2
    status=1
  else
    sed -i -E "s#^version = \"[0-9]+\.[0-9]+\.[0-9]+\"#version = \"$VERSION\"#" src-tauri/Cargo.toml
    echo "updated: version \"$VERSION\" in src-tauri/Cargo.toml"
  fi
fi

# The About header in the web frontend.
if ! grep -Eq "v$DOT" src/index.html; then
  if [ "${CHECK_ONLY:-0}" = "1" ]; then
    echo "out of date: v$VERSION in src/index.html" >&2
    status=1
  else
    sed -i -E "s#v[0-9]+\.[0-9]+\.[0-9]+#v$VERSION#" src/index.html
    echo "updated: v$VERSION in src/index.html"
  fi
fi

if [ "$status" = "0" ]; then
  echo "every declaration site agrees on $VERSION"
fi
exit "$status"