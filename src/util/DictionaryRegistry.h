#pragma once

#include <cstdint>
#include <string>
#include <vector>

#include "DictionaryMeta.h"

// One StarDict dictionary found under /dictionaries or /.dictionaries: a
// subfolder holding <stem>.idx plus <stem>.dict or <stem>.dict.dz.
struct DictionaryEntry {
  std::string name;  // subfolder name (shown to the user, stored in settings)
  std::string stem;  // index basename without .idx
  const char* root = "/dictionaries";

  std::string basePath() const { return std::string(root) + "/" + name + "/" + stem; }
};

// What the companion app lists for an installed dictionary.
struct DictionaryInfo {
  std::string title;  // .ifo bookname, else the folder name
  uint32_t words = 0;
  uint32_t sizeKB = 0;  // all files in the folder, sidecars included
  DictionaryMeta::Script script = DictionaryMeta::Script::Other;
  bool html = false;
  bool synonyms = false;
  bool indexed = false;  // .qidx sidecar present
};

namespace DictionaryRegistry {

// Scan /dictionaries/*/ and /.dictionaries/*/ for dictionaries. Folders with
// multiple index stems are ambiguous and skipped. Result is sorted
// case-insensitively by name.
void discover(std::vector<DictionaryEntry>& out);

// Resolve a folder name to its extensionless base path
// ("/dictionaries/<folder>/<stem>" or "/.dictionaries/<folder>/<stem>").
// Returns false if the folder holds no usable dictionary in either root.
bool resolveBasePath(const char* folderName, std::string& basePathOut);

// Facts from <basePath>.ifo (first 2KB); defaults when it is missing.
DictionaryMeta::IfoInfo readIfo(const std::string& basePath);

// Headword writing system: the .ifo language when it names one, else letters
// sampled from the head and the middle of the .idx (2KB each).
DictionaryMeta::Script headwordScript(const std::string& basePath);

// Read .ifo facts, folder size and the headword script.
void describe(const DictionaryEntry& entry, DictionaryInfo& out);

// Delete a dictionary folder with everything in it (sidecars included).
// False when the name is unsafe or no such dictionary exists.
bool remove(const char* folderName);

}  // namespace DictionaryRegistry
