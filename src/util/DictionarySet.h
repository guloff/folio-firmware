#pragma once

#include <memory>
#include <string>
#include <vector>

#include "Dictionary.h"
#include "DictionaryMeta.h"
#include "DictionaryRegistry.h"

// The dictionaries one lookup screen searches, per SETTINGS.dictionaryName:
// one chosen folder, or every installed dictionary ("*", the default) tried
// in order of how well its headword script matches the word. Each dictionary
// is opened and indexed lazily on its first lookup and stays open for the
// screen's lifetime. Among dictionaries of the word's script, those that
// explain in the interface language go first.
class DictionarySet {
 public:
  enum class State : uint8_t { Off, NoneInstalled, Ready };

  // Read the setting and discover dictionaries. A chosen folder that no
  // longer exists falls back to all dictionaries.
  State load();

  struct Hooks {
    void* ctx = nullptr;
    void (*onIndexing)(void* ctx) = nullptr;  // before a slow first-time index build
    void (*yield)(void* ctx) = nullptr;       // during the build (watchdog / repaint)
  };

  struct Outcome {
    bool found = false;
    // At least one dictionary opened and had a usable index; when false the
    // failure is about the dictionaries themselves, named by indexResult.
    bool anyUsable = false;
    Dictionary::LookupResult lookupResult = Dictionary::LookupResult::NotFound;
    Dictionary::IndexResult indexResult = Dictionary::IndexResult::Ok;
    bool html = false;
    std::string source;  // dictionary title, set only when several are searched
  };

  Outcome lookup(const char* word, std::string& definitionOut, std::string& headwordOut, const Hooks& hooks);

  size_t size() const { return slots.size(); }

 private:
  struct Slot {
    DictionaryEntry entry;
    std::string title;
    DictionaryMeta::Script script = DictionaryMeta::Script::Other;
    std::string target;  // definition language from the .ifo, e.g. "ru"
    std::unique_ptr<Dictionary> dict;
    bool openTried = false;
    bool openOk = false;
    bool needsIndex = false;
  };

  bool ensureReady(Slot& slot, const Hooks& hooks, Dictionary::IndexResult& indexResult);

  std::vector<Slot> slots;
  std::string uiLanguage;  // two-letter interface language at load()
};
