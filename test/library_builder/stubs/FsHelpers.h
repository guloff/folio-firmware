#pragma once

#include <string>

namespace FsHelpers {
inline bool checkFileExtension(const std::string& path, const char* extension) { return path.ends_with(extension); }
inline bool hasEpubExtension(const std::string& path) { return checkFileExtension(path, ".epub"); }
inline bool hasFb2Extension(const std::string& path) {
  return checkFileExtension(path, ".fb2") || checkFileExtension(path, ".fb2.zip");
}
inline bool opensAsEpub(const std::string& path) { return hasEpubExtension(path) || hasFb2Extension(path); }
}  // namespace FsHelpers
