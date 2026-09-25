#pragma once
// Host stand-in for lib/hal/HalStorage.h: plain stdio on host paths, just the
// surface FirmwareSignature.cpp uses.
#include <sys/stat.h>

#include <cstddef>
#include <cstdint>
#include <cstdio>

class HalFile {
 public:
  HalFile() = default;
  ~HalFile() {
    if (f) fclose(f);
  }
  HalFile(const HalFile&) = delete;
  HalFile& operator=(const HalFile&) = delete;
  bool open(const char* path) {
    f = fopen(path, "rb");
    return f != nullptr;
  }
  size_t fileSize() {
    struct stat st {};
    return f && fstat(fileno(f), &st) == 0 ? static_cast<size_t>(st.st_size) : 0;
  }
  bool seek(size_t pos) { return f && fseek(f, static_cast<long>(pos), SEEK_SET) == 0; }
  int read(void* buf, size_t count) { return f ? static_cast<int>(fread(buf, 1, count, f)) : -1; }
  explicit operator bool() const { return f != nullptr; }

 private:
  FILE* f = nullptr;
};

class HalStorage {
 public:
  static HalStorage& getInstance() {
    static HalStorage s;
    return s;
  }
  bool exists(const char* path) {
    struct stat st {};
    return stat(path, &st) == 0;
  }
  bool openFileForRead(const char*, const char* path, HalFile& file) { return file.open(path); }
};

#define Storage HalStorage::getInstance()
