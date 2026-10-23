#include "DictionaryRegistry.h"

#include <HalStorage.h>
#include <Logging.h>
#include <Memory.h>

#include <algorithm>
#include <cstring>

#include "StringUtils.h"

namespace DictionaryRegistry {
namespace {

// Dictionaries are looked up in both roots, in order. The hidden variant
// lets users keep the folder out of the file browser (hidden by default,
// see FileBrowserActivity's showHiddenFiles check).
constexpr const char* DICT_ROOTS[] = {"/dictionaries", "/.dictionaries"};

// Find the single .idx stem inside one dictionary folder. Returns false when
// the folder holds no .idx or more than one distinct stem (ambiguous).
bool findStem(const char* folderPath, std::string& stemOut) {
  auto dir = Storage.open(folderPath);
  if (!dir || !dir.isDirectory()) return false;

  dir.rewindDirectory();
  char name[128];
  char foundStem[128];
  foundStem[0] = '\0';
  for (auto entry = dir.openNextFile(); entry; entry = dir.openNextFile()) {
    entry.getName(name, sizeof(name));
    // Skip macOS metadata files (AppleDouble resource forks)
    if (entry.isDirectory() || strncmp(name, "._", 2) == 0) continue;

    const size_t len = strlen(name);
    if (len <= 4 || strcmp(name + len - 4, ".idx") != 0) continue;

    name[len - 4] = '\0';
    if (foundStem[0] != '\0' && strcmp(foundStem, name) != 0) {
      LOG_DBG("DREG", "Skipping %s: multiple index stems found", folderPath);
      return false;
    }
    strncpy(foundStem, name, sizeof(foundStem) - 1);
    foundStem[sizeof(foundStem) - 1] = '\0';
  }

  if (foundStem[0] == '\0') return false;

  // Require dictionary data next to the index, so folders holding only an
  // .idx never surface as selectable dictionaries that fail at lookup time.
  const std::string base = std::string(folderPath) + "/" + foundStem;
  if (!Storage.exists((base + ".dict").c_str()) && !Storage.exists((base + ".dict.dz").c_str())) {
    LOG_DBG("DREG", "Skipping %s: no .dict or .dict.dz", folderPath);
    return false;
  }

  stemOut = foundStem;
  return true;
}

}  // namespace

void discover(std::vector<DictionaryEntry>& out) {
  out.clear();
  out.reserve(8);

  for (const char* dictRoot : DICT_ROOTS) {
    auto rootDir = Storage.open(dictRoot);
    if (!rootDir || !rootDir.isDirectory()) {
      LOG_DBG("DREG", "No %s directory on SD card", dictRoot);
      continue;
    }

    rootDir.rewindDirectory();
    char name[128];
    for (auto entry = rootDir.openNextFile(); entry; entry = rootDir.openNextFile()) {
      entry.getName(name, sizeof(name));
      if (!entry.isDirectory() || name[0] == '.') continue;

      std::string folderPath = std::string(dictRoot) + "/" + name;
      std::string stem;
      if (!findStem(folderPath.c_str(), stem)) continue;

      DictionaryEntry e;
      e.name = name;
      e.stem = std::move(stem);
      e.root = dictRoot;
      out.push_back(std::move(e));
      LOG_DBG("DREG", "Found dictionary: %s", name);
    }
  }

  // Case-insensitive sort by folder name (matches FileBrowserActivity ordering).
  std::sort(out.begin(), out.end(), [](const DictionaryEntry& a, const DictionaryEntry& b) {
    return StringUtils::asciiCaseCmp(a.name.c_str(), b.name.c_str()) < 0;
  });
}

bool resolveBasePath(const char* folderName, std::string& basePathOut) {
  // folderName is persisted in the settings JSON and arrives from the
  // companion API: reject separators and dot prefixes so a crafted value
  // cannot escape the dictionary roots.
  if (!DictionaryMeta::isSafeFolderName(folderName, 127)) return false;

  for (const char* dictRoot : DICT_ROOTS) {
    std::string folderPath = std::string(dictRoot) + "/" + folderName;
    std::string stem;
    if (!findStem(folderPath.c_str(), stem)) continue;
    basePathOut = folderPath + "/" + stem;
    return true;
  }
  return false;
}

namespace {

constexpr size_t SAMPLE_BYTES = 2048;

}  // namespace

DictionaryMeta::IfoInfo readIfo(const std::string& basePath) {
  // Heap, not stack: 2KB is past the 256-byte stack-local guideline, and this
  // also runs on the web server task. Freed on return.
  auto buf = makeUniqueNoThrow<char[]>(SAMPLE_BYTES);
  if (!buf) {
    LOG_ERR("DREG", "OOM: %u bytes for .ifo", static_cast<unsigned>(SAMPLE_BYTES));
    return {};
  }
  // A missing .ifo leaves the buffer untouched on some HALs: start empty.
  buf[0] = '\0';
  Storage.readFileToBuffer((basePath + ".ifo").c_str(), buf.get(), SAMPLE_BYTES);
  return DictionaryMeta::parseIfo(buf.get());
}

DictionaryMeta::Script headwordScript(const std::string& basePath) {
  const DictionaryMeta::IfoInfo ifo = readIfo(basePath);
  if (ifo.source != DictionaryMeta::Script::Other) return ifo.source;

  HalFile idx;
  if (!Storage.openFileForRead("DREG", basePath + ".idx", idx)) return DictionaryMeta::Script::Other;
  // Heap, not stack: 2KB is past the 256-byte stack-local guideline. Freed on return.
  auto buf = makeUniqueNoThrow<uint8_t[]>(SAMPLE_BYTES);
  if (!buf) {
    LOG_ERR("DREG", "OOM: %u bytes for idx sample", static_cast<unsigned>(SAMPLE_BYTES));
    return DictionaryMeta::Script::Other;
  }
  DictionaryMeta::ScriptTally tally;
  const size_t size = idx.fileSize();
  int n = idx.read(buf.get(), SAMPLE_BYTES);
  if (n > 0) DictionaryMeta::tallyIdxChunk(buf.get(), static_cast<size_t>(n), false, tally);
  if (size > SAMPLE_BYTES * 2 && idx.seek(size / 2)) {
    n = idx.read(buf.get(), SAMPLE_BYTES);
    if (n > 0) DictionaryMeta::tallyIdxChunk(buf.get(), static_cast<size_t>(n), true, tally);
  }
  return DictionaryMeta::majority(tally);
}

void describe(const DictionaryEntry& entry, DictionaryInfo& out) {
  const std::string base = entry.basePath();
  const DictionaryMeta::IfoInfo ifo = readIfo(base);
  out.title = ifo.bookname.empty() ? entry.name : ifo.bookname;
  out.words = ifo.wordcount;
  out.html = ifo.html;
  out.synonyms = Storage.exists((base + ".syn").c_str());
  out.indexed = Storage.exists((base + ".qidx").c_str());
  out.script = headwordScript(base);

  uint64_t bytes = 0;
  const std::string folder = std::string(entry.root) + "/" + entry.name;
  auto dir = Storage.open(folder.c_str());
  if (dir && dir.isDirectory()) {
    dir.rewindDirectory();
    for (auto file = dir.openNextFile(); file; file = dir.openNextFile()) {
      if (!file.isDirectory()) bytes += file.fileSize64();
    }
  }
  out.sizeKB = static_cast<uint32_t>((bytes + 1023) / 1024);
}

bool remove(const char* folderName) {
  if (!DictionaryMeta::isSafeFolderName(folderName, 127)) return false;
  bool removed = false;
  for (const char* dictRoot : DICT_ROOTS) {
    const std::string folder = std::string(dictRoot) + "/" + folderName;
    auto dir = Storage.open(folder.c_str());
    if (!dir || !dir.isDirectory()) continue;
    dir.close();
    if (Storage.removeDir(folder.c_str())) {
      LOG_INF("DREG", "Removed dictionary %s", folder.c_str());
      removed = true;
    } else {
      LOG_ERR("DREG", "Failed to remove %s", folder.c_str());
    }
  }
  return removed;
}

}  // namespace DictionaryRegistry
