#include "FirmwareSignature.h"

#include <HalStorage.h>
#include <Logging.h>
#include <Memory.h>
#include <mbedtls/sha256.h>
#include <monocypher-ed25519.h>

#include <cstdio>
#include <cstring>

namespace inklink::fwsig {

namespace {

// Folio release signing key (Ed25519, raw 32-byte public key). The private
// half never enters the repository.
constexpr uint8_t PUBLIC_KEY[32] = {0x26, 0xc0, 0x83, 0x50, 0x26, 0x3f, 0x19, 0x09, 0x22, 0x01, 0x48,
                                    0xc4, 0xc7, 0xd5, 0x98, 0x3b, 0xb0, 0xb2, 0x49, 0x34, 0x13, 0xf6,
                                    0x4c, 0x29, 0x57, 0x93, 0x60, 0xf0, 0x8d, 0x6c, 0x23, 0x3b};

constexpr char MESSAGE_PREFIX[] = "FOLIO-FW-v1\n";
constexpr size_t PREFIX_LEN = sizeof(MESSAGE_PREFIX) - 1;
constexpr size_t CHUNK = 4096;

// Image header (24) + first segment header (8): the app descriptor opens the
// first (DROM) segment.
constexpr size_t APP_DESC_OFFSET = 32;
constexpr uint32_t APP_DESC_MAGIC = 0xABCD5432;
constexpr size_t APP_DESC_VERSION = 16;
constexpr size_t APP_DESC_PROJECT = 48;

static_assert(sizeof(mbedtls_sha256_context) <= 128, "ImageHasher storage too small");

mbedtls_sha256_context* shaCtx(uint8_t* storage) { return reinterpret_cast<mbedtls_sha256_context*>(storage); }

void copyField(char* dst, const uint8_t* src, size_t len) {
  size_t n = 0;
  while (n < len - 1 && src[n] >= 0x20 && src[n] < 0x7f) {
    dst[n] = static_cast<char>(src[n]);
    n++;
  }
  dst[n] = '\0';
}

}  // namespace

const char* statusName(const Status s) {
  switch (s) {
    case Status::VALID:
      return "valid";
    case Status::MISSING:
      return "missing";
    case Status::INVALID:
      return "invalid";
    case Status::ERROR:
      return "error";
  }
  return "?";
}

bool verifyDigest(const uint8_t digest[DIGEST_SIZE], const uint8_t signature[SIGNATURE_SIZE]) {
  uint8_t message[PREFIX_LEN + DIGEST_SIZE];
  memcpy(message, MESSAGE_PREFIX, PREFIX_LEN);
  memcpy(message + PREFIX_LEN, digest, DIGEST_SIZE);
  return crypto_ed25519_check(signature, PUBLIC_KEY, message, sizeof(message)) == 0;
}

bool verifyMessage(const uint8_t* message, const size_t len, const uint8_t signature[SIGNATURE_SIZE]) {
  return crypto_ed25519_check(signature, PUBLIC_KEY, message, len) == 0;
}

ImageHasher::ImageHasher() {
  mbedtls_sha256_init(shaCtx(ctx));
  mbedtls_sha256_starts(shaCtx(ctx), /*is224=*/0);
}

ImageHasher::~ImageHasher() { mbedtls_sha256_free(shaCtx(ctx)); }

void ImageHasher::update(const uint8_t* data, const size_t len) { mbedtls_sha256_update(shaCtx(ctx), data, len); }

void ImageHasher::finish(uint8_t digest[DIGEST_SIZE]) { mbedtls_sha256_finish(shaCtx(ctx), digest); }

bool readSignatureFile(const char* binPath, uint8_t signature[SIGNATURE_SIZE]) {
  char sigPath[256];
  if (snprintf(sigPath, sizeof(sigPath), "%s.sig", binPath) >= static_cast<int>(sizeof(sigPath))) return false;
  if (!Storage.exists(sigPath)) return false;
  HalFile f;
  if (!Storage.openFileForRead("FWSIG", sigPath, f)) return false;
  if (f.fileSize() != SIGNATURE_SIZE) {
    LOG_ERR("FWSIG", "%s: expected 64 bytes, got %u", sigPath, static_cast<unsigned>(f.fileSize()));
    return false;
  }
  return f.read(signature, SIGNATURE_SIZE) == static_cast<int>(SIGNATURE_SIZE);
}

bool hashFile(const char* path, uint8_t digest[DIGEST_SIZE]) {
  HalFile f;
  if (!Storage.openFileForRead("FWSIG", path, f)) return false;
  auto buf = makeUniqueNoThrow<uint8_t[]>(CHUNK);
  if (!buf) {
    LOG_ERR("FWSIG", "OOM: %u bytes", static_cast<unsigned>(CHUNK));
    return false;
  }
  ImageHasher hasher;
  size_t remaining = f.fileSize();
  while (remaining > 0) {
    const size_t want = remaining < CHUNK ? remaining : CHUNK;
    const int got = f.read(buf.get(), want);
    if (got != static_cast<int>(want)) {
      LOG_ERR("FWSIG", "read failed at %u", static_cast<unsigned>(f.fileSize() - remaining));
      return false;
    }
    hasher.update(buf.get(), want);
    remaining -= want;
  }
  hasher.finish(digest);
  return true;
}

Status verifyFile(const char* binPath) {
  uint8_t signature[SIGNATURE_SIZE];
  if (!readSignatureFile(binPath, signature)) return Status::MISSING;
  uint8_t digest[DIGEST_SIZE];
  if (!hashFile(binPath, digest)) return Status::ERROR;
  const bool ok = verifyDigest(digest, signature);
  LOG_INF("FWSIG", "%s: signature %s", binPath, ok ? "valid" : "INVALID");
  return ok ? Status::VALID : Status::INVALID;
}

bool readAppInfo(const char* binPath, AppInfo& out) {
  out.projectName[0] = '\0';
  out.version[0] = '\0';
  HalFile f;
  if (!Storage.openFileForRead("FWSIG", binPath, f)) return false;
  uint8_t desc[APP_DESC_PROJECT + sizeof(out.projectName)];
  if (!f.seek(APP_DESC_OFFSET) || f.read(desc, sizeof(desc)) != static_cast<int>(sizeof(desc))) return false;
  uint32_t magic;
  memcpy(&magic, desc, sizeof(magic));
  if (magic != APP_DESC_MAGIC) return false;
  copyField(out.version, desc + APP_DESC_VERSION, sizeof(out.version));
  copyField(out.projectName, desc + APP_DESC_PROJECT, sizeof(out.projectName));
  return true;
}

}  // namespace inklink::fwsig
