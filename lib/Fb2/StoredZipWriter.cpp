#include "StoredZipWriter.h"

#include <Memory.h>
#include <MinizConfig.h>

#include <cstring>

namespace {

constexpr uint16_t VERSION_NEEDED = 10;  // 1.0: stored entries only
constexpr uint16_t FLAG_UTF8_NAMES = 0x0800;
constexpr uint16_t DOS_DATE_1980_01_01 = 0x21;
constexpr uint32_t LOCAL_CRC_OFFSET = 14;  // CRC, then compressed and uncompressed size

void le16(uint8_t* p, uint16_t v) {
  p[0] = v & 0xFF;
  p[1] = v >> 8;
}

void le32(uint8_t* p, uint32_t v) {
  for (int i = 0; i < 4; i++) p[i] = (v >> (8 * i)) & 0xFF;
}

}  // namespace

bool StoredZipWriter::put(const void* data, size_t len) {
  if (failed) return false;
  if (!buffer) {
    buffer = makeUniqueNoThrow<uint8_t[]>(BUFFER_SIZE);
    if (!buffer) {
      failed = true;
      return false;
    }
  }
  const auto* bytes = static_cast<const uint8_t*>(data);
  offset += len;
  while (len > 0) {
    if (buffered == BUFFER_SIZE && !flush()) return false;
    const size_t n = len < BUFFER_SIZE - buffered ? len : BUFFER_SIZE - buffered;
    memcpy(buffer.get() + buffered, bytes, n);
    buffered += n;
    bytes += n;
    len -= n;
  }
  return true;
}

bool StoredZipWriter::flush() {
  if (failed) return false;
  if (buffered > 0 && !file.write(buffer.get(), buffered)) failed = true;
  buffered = 0;
  return !failed;
}

bool StoredZipWriter::beginFile(const char* name) {
  if (inEntry || failed) return false;
  if (entries.empty()) entries.reserve(64);
  current = Entry{name, 0, 0, offset};
  uint8_t header[30] = {};
  le32(header, 0x04034b50);
  le16(header + 4, VERSION_NEEDED);
  le16(header + 6, FLAG_UTF8_NAMES);
  le16(header + 12, DOS_DATE_1980_01_01);
  le16(header + 26, static_cast<uint16_t>(current.name.size()));
  inEntry = true;
  return put(header, sizeof(header)) && put(current.name.data(), current.name.size());
}

bool StoredZipWriter::write(const char* data, size_t len) {
  if (!inEntry) return false;
  current.crc = static_cast<uint32_t>(mz_crc32(current.crc, reinterpret_cast<const uint8_t*>(data), len));
  current.size += len;
  return put(data, len);
}

bool StoredZipWriter::endFile() {
  if (!inEntry || !flush()) return false;
  inEntry = false;
  uint8_t sizes[12];
  le32(sizes, current.crc);
  le32(sizes + 4, current.size);
  le32(sizes + 8, current.size);
  if (!file.seek(current.offset + LOCAL_CRC_OFFSET) || !file.write(sizes, sizeof(sizes)) || !file.seek(offset)) {
    failed = true;
    return false;
  }
  entries.push_back(std::move(current));
  return true;
}

bool StoredZipWriter::finish() {
  if (inEntry || failed) return false;
  const uint32_t directoryStart = offset;
  for (const Entry& e : entries) {
    uint8_t header[46] = {};
    le32(header, 0x02014b50);
    le16(header + 4, 20);  // made by: MS-DOS, spec 2.0
    le16(header + 6, VERSION_NEEDED);
    le16(header + 8, FLAG_UTF8_NAMES);
    le16(header + 14, DOS_DATE_1980_01_01);
    le32(header + 16, e.crc);
    le32(header + 20, e.size);
    le32(header + 24, e.size);
    le16(header + 28, static_cast<uint16_t>(e.name.size()));
    le32(header + 42, e.offset);
    if (!put(header, sizeof(header)) || !put(e.name.data(), e.name.size())) return false;
  }
  uint8_t end[22] = {};
  le32(end, 0x06054b50);
  le16(end + 8, static_cast<uint16_t>(entries.size()));
  le16(end + 10, static_cast<uint16_t>(entries.size()));
  le32(end + 12, offset - directoryStart);
  le32(end + 16, directoryStart);
  return put(end, sizeof(end)) && flush();
}
