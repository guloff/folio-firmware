#!/usr/bin/env bash
# Signs a Folio firmware image: writes <image>.bin.sig, the 64-byte raw Ed25519
# signature of "FOLIO-FW-v1\n" + SHA-256(<image>) (44 bytes). The reader and the
# InkLink app verify it with the public key embedded in
# src/inklink/FirmwareSignature.cpp.
#
#   tools/inklink/sign_firmware.sh .pio/build/x4pro/firmware.bin
#
# Key: $FOLIO_SIGNING_KEY_FILE, else ~/Documents/Projects/Crosspoint/secrets/
# folio-signing-ed25519.pem (PEM, never committed). OpenSSL 3 is required
# (pkeyutl -rawin); Homebrew's is preferred on macOS, PATH otherwise (CI).
set -euo pipefail

die() { echo "sign_firmware: $*" >&2; exit 1; }

[[ $# -eq 1 ]] || die "usage: $0 <firmware.bin>"
BIN="$1"
[[ -f "$BIN" ]] || die "no such file: $BIN"

KEY="${FOLIO_SIGNING_KEY_FILE:-/Users/rustamguloff/Documents/Projects/Crosspoint/secrets/folio-signing-ed25519.pem}"
[[ -r "$KEY" ]] || die "signing key not readable: $KEY (set FOLIO_SIGNING_KEY_FILE)"

if [[ -x /opt/homebrew/bin/openssl ]]; then
  OPENSSL=/opt/homebrew/bin/openssl
else
  OPENSSL="$(command -v openssl)" || die "openssl not found"
fi
"$OPENSSL" version | grep -q '^OpenSSL 3' || die "OpenSSL 3 required, got: $("$OPENSSL" version)"

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

# Message = 12-byte prefix + 32-byte binary SHA-256 of the whole image.
printf 'FOLIO-FW-v1\n' > "$TMP/msg"
"$OPENSSL" dgst -sha256 -binary "$BIN" >> "$TMP/msg"
[[ "$(wc -c < "$TMP/msg" | tr -d ' ')" == 44 ]] || die "message is not 44 bytes"

"$OPENSSL" pkeyutl -sign -inkey "$KEY" -rawin -in "$TMP/msg" -out "$TMP/sig"
[[ "$(wc -c < "$TMP/sig" | tr -d ' ')" == 64 ]] || die "signature is not 64 bytes"

# Self-check against the key's own public half before publishing.
"$OPENSSL" pkey -in "$KEY" -pubout -out "$TMP/pub.pem"
"$OPENSSL" pkeyutl -verify -pubin -inkey "$TMP/pub.pem" -rawin -in "$TMP/msg" -sigfile "$TMP/sig" >/dev/null ||
  die "signature does not verify"

mv "$TMP/sig" "$BIN.sig"
echo "signed: $BIN.sig ($(xxd -p -l 8 "$BIN.sig")…)"
