#!/usr/bin/env bash
# Host test for firmware signatures: signs a real image with sign_firmware.sh,
# then checks that the firmware's own verification code (src/inklink/
# FirmwareSignature.cpp + vendored Monocypher) accepts it, rejects a copy with
# one flipped bit and reports a missing .sig. Also checks OTA tag comparison.
#
#   tools/inklink/test_signature.sh [image.bin]   (default: .pio/build/x4pro/firmware.bin)
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
IMAGE="${1:-$ROOT/.pio/build/x4pro/firmware.bin}"
[[ -f "$IMAGE" ]] || { echo "no image: $IMAGE (build with: pio run -e x4pro)" >&2; exit 1; }
CXX="${CXX:-c++}"
CC="${CC:-cc}"

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

cp "$IMAGE" "$TMP/signed.bin"
"$ROOT/tools/inklink/sign_firmware.sh" "$TMP/signed.bin"
cp "$TMP/signed.bin" "$TMP/tampered.bin"
cp "$TMP/signed.bin.sig" "$TMP/tampered.bin.sig"
# Flip one bit in the middle of the image.
python3 - "$TMP/tampered.bin" <<'PY'
import sys
p = sys.argv[1]
b = bytearray(open(p, "rb").read())
b[len(b) // 2] ^= 0x01
open(p, "wb").write(b)
PY
cp "$TMP/signed.bin" "$TMP/unsigned.bin"

"$CC" -O2 -c "$ROOT/lib/Monocypher/monocypher.c" -o "$TMP/monocypher.o"
"$CC" -O2 -c "$ROOT/lib/Monocypher/monocypher-ed25519.c" -I "$ROOT/lib/Monocypher" -o "$TMP/monocypher-ed25519.o"
# Stubs first (HalStorage/Logging on stdio); the simulator provides the host
# mbedtls SHA-256.
"$CXX" -std=c++20 -O2 \
  -I "$ROOT/test/inklink_signature/stubs" -I "$ROOT/src" -I "$ROOT/lib/Memory" -I "$ROOT/lib/Monocypher" \
  -I "$ROOT/../simulator/src" \
  "$ROOT/test/inklink_signature/signature_test.cpp" "$ROOT/src/inklink/FirmwareSignature.cpp" \
  "$TMP/monocypher.o" "$TMP/monocypher-ed25519.o" -o "$TMP/signature_test"

"$TMP/signature_test" "$TMP/signed.bin" "$TMP/tampered.bin" "$TMP/unsigned.bin"
