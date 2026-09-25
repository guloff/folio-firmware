// Host test for Folio firmware signatures and OTA version comparison.
// Built and run by tools/inklink/test_signature.sh against a real image
// signed with tools/inklink/sign_firmware.sh.
//
//   signature_test <signed.bin> <tampered.bin> <unsigned.bin>
#include <cstdio>
#include <cstring>

#include "inklink/FirmwareSignature.h"
#include "network/FirmwareVersion.h"

using inklink::fwsig::Status;

static int failures = 0;

static void expect(bool ok, const char* what) {
  printf("%s  %s\n", ok ? "PASS" : "FAIL", what);
  if (!ok) failures++;
}

int main(int argc, char** argv) {
  if (argc != 4) {
    fprintf(stderr, "usage: %s <signed.bin> <tampered.bin> <unsigned.bin>\n", argv[0]);
    return 2;
  }
  const char* signedBin = argv[1];
  const char* tamperedBin = argv[2];
  const char* unsignedBin = argv[3];

  expect(inklink::fwsig::verifyFile(signedBin) == Status::VALID, "signed image verifies");
  expect(inklink::fwsig::verifyFile(tamperedBin) == Status::INVALID, "image with one flipped bit is rejected");
  expect(inklink::fwsig::verifyFile(unsignedBin) == Status::MISSING, "image without .sig reports MISSING");

  uint8_t digest[inklink::fwsig::DIGEST_SIZE];
  uint8_t sig[inklink::fwsig::SIGNATURE_SIZE];
  const bool read = inklink::fwsig::hashFile(signedBin, digest) && inklink::fwsig::readSignatureFile(signedBin, sig);
  expect(read, "digest and signature readable");
  sig[10] ^= 0x80;
  expect(read && !inklink::fwsig::verifyDigest(digest, sig), "corrupted signature is rejected");

  inklink::fwsig::AppInfo info;
  const bool hasInfo = inklink::fwsig::readAppInfo(signedBin, info);
  printf("      app descriptor: project=\"%s\" version=\"%s\"\n", info.projectName, info.version);
  expect(hasInfo && strcmp(info.projectName, "Folio") == 0, "app descriptor names the project Folio");

  using firmware_version::isNewer;
  expect(isNewer("v0.2.0", "0.1.0-x4pro"), "v0.2.0 > 0.1.0-x4pro (env x4pro)");
  expect(isNewer("v0.2.0", "0.1.0"), "v0.2.0 > 0.1.0 (env x4pro-gh_release)");
  expect(!isNewer("v0.1.0", "0.1.0-x4pro"), "v0.1.0 == 0.1.0-x4pro: no update");
  expect(!isNewer("v0.1.0", "0.1.0"), "v0.1.0 == 0.1.0: no update");
  expect(!isNewer("v0.0.9", "0.1.0"), "v0.0.9 < 0.1.0: no downgrade");
  expect(isNewer("0.1.1", "0.1.0-x4pro"), "tag without v still compares");
  expect(isNewer("v1.0.0", "0.9.9"), "major bump");
  expect(isNewer("v0.1.0", "0.1.0-rc+abc"), "release replaces its RC");
  expect(!isNewer("garbage", "0.1.0"), "unparsable tag never updates");

  printf("%s (%d failure%s)\n", failures ? "FAILED" : "OK", failures, failures == 1 ? "" : "s");
  return failures ? 1 : 0;
}
