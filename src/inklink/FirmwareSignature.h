#pragma once

#include <cstddef>
#include <cstdint>

// Ed25519 signatures over Folio firmware images. The signed message is
// "FOLIO-FW-v1\n" followed by the SHA-256 of the whole .bin (44 bytes); the
// 64-byte raw signature lives next to the image as "<image>.bin.sig".
// Sign with tools/inklink/sign_firmware.sh.
namespace inklink::fwsig {

constexpr size_t SIGNATURE_SIZE = 64;
constexpr size_t DIGEST_SIZE = 32;

enum class Status : uint8_t {
  VALID,
  MISSING,  // no .sig next to the image (or not 64 bytes)
  INVALID,  // signature does not match the image
  ERROR,    // image unreadable / out of memory
};

const char* statusName(Status s);

// Verifies a signature against an image digest with the embedded public key.
bool verifyDigest(const uint8_t digest[DIGEST_SIZE], const uint8_t signature[SIGNATURE_SIZE]);

// Streaming SHA-256 of an image, fed by callers that already read the bytes
// (the OTA download). Opaque storage keeps mbedtls out of this header.
class ImageHasher {
 public:
  ImageHasher();
  ~ImageHasher();
  ImageHasher(const ImageHasher&) = delete;
  ImageHasher& operator=(const ImageHasher&) = delete;
  void update(const uint8_t* data, size_t len);
  void finish(uint8_t digest[DIGEST_SIZE]);

 private:
  alignas(8) uint8_t ctx[128];
};

// Reads "<binPath>.sig" into `signature`. False when absent or not 64 bytes.
bool readSignatureFile(const char* binPath, uint8_t signature[SIGNATURE_SIZE]);

// SHA-256 of an SD file, streamed through a 4 KB heap buffer.
bool hashFile(const char* path, uint8_t digest[DIGEST_SIZE]);

// Full check of an image on the SD card against its .sig file.
Status verifyFile(const char* binPath);

// Project name and version from the image's esp_app_desc_t (right after the
// image and first segment headers). False when the descriptor is missing.
struct AppInfo {
  char projectName[32];
  char version[32];
};
bool readAppInfo(const char* binPath, AppInfo& out);

}  // namespace inklink::fwsig
