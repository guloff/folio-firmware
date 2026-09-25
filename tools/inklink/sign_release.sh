#!/usr/bin/env bash
# Adds .sig files to an existing GitHub release of guloff/folio-firmware:
# downloads every folio-*-x4pro.bin asset of <tag>, signs it with
# sign_firmware.sh and uploads <asset>.sig next to it (replacing an old one).
#
#   tools/inklink/sign_release.sh v0.2.0
#
# Needs an authenticated `gh` and the signing key (see sign_firmware.sh).
set -euo pipefail

die() { echo "sign_release: $*" >&2; exit 1; }

[[ $# -eq 1 ]] || die "usage: $0 <tag>"
TAG="$1"
REPO="guloff/folio-firmware"
HERE="$(cd "$(dirname "$0")" && pwd)"

command -v gh >/dev/null || die "gh CLI not found"

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

gh release download "$TAG" -R "$REPO" -p 'folio-*-x4pro.bin' -D "$TMP"
shopt -s nullglob
BINS=("$TMP"/folio-*-x4pro.bin)
[[ ${#BINS[@]} -gt 0 ]] || die "release $TAG has no folio-*-x4pro.bin asset"

for BIN in "${BINS[@]}"; do
  "$HERE/sign_firmware.sh" "$BIN"
  gh release upload "$TAG" "$BIN.sig" -R "$REPO" --clobber
  echo "uploaded $(basename "$BIN").sig to $REPO@$TAG"
done
