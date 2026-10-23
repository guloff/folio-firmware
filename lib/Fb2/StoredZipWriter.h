#pragma once

#include <cstdint>
#include <memory>
#include <string>
#include <vector>

#include "Fb2ToEpub.h"

// Writes a ZIP archive of uncompressed (stored) entries, streaming: each
// entry's CRC and size are patched into its local header once it ends, so no
// entry is held in memory. Enough for an EPUB the reader itself unpacks.
class StoredZipWriter : public Fb2ToEpub::Output {
 public:
  class File {
   public:
    virtual ~File() = default;
    virtual bool write(const uint8_t* data, size_t len) = 0;
    virtual bool seek(uint32_t pos) = 0;
  };

  explicit StoredZipWriter(File& file) : file(file) {}

  bool beginFile(const char* name) override;
  bool write(const char* data, size_t len) override;
  bool endFile() override;
  // Writes the central directory; the archive is complete after this.
  bool finish();

 private:
  struct Entry {
    std::string name;
    uint32_t crc;
    uint32_t size;
    uint32_t offset;
  };

  // Small writes (tags, words) are batched so the SD sees sector-sized ones.
  static constexpr size_t BUFFER_SIZE = 4096;

  File& file;
  std::unique_ptr<uint8_t[]> buffer;
  size_t buffered = 0;
  uint32_t offset = 0;  // archive bytes written, buffered ones included
  std::vector<Entry> entries;
  Entry current{};
  bool inEntry = false;
  bool failed = false;

  bool put(const void* data, size_t len);
  bool flush();
};
