#include "DictionarySet.h"

#include <I18n.h>
#include <Logging.h>
#include <Memory.h>

#include <algorithm>
#include <cstring>

#include "CrossPointSettings.h"

DictionarySet::State DictionarySet::load() {
  slots.clear();
  const DictionaryMeta::Selection selection = DictionaryMeta::selectionOf(SETTINGS.dictionaryName);
  if (selection == DictionaryMeta::Selection::Off) return State::Off;

  std::vector<DictionaryEntry> found;
  DictionaryRegistry::discover(found);
  if (found.empty()) return State::NoneInstalled;

  if (selection == DictionaryMeta::Selection::One) {
    const auto it = std::find_if(found.begin(), found.end(), [](const DictionaryEntry& e) {
      return strncmp(e.name.c_str(), SETTINGS.dictionaryName, sizeof(SETTINGS.dictionaryName) - 1) == 0;
    });
    if (it != found.end()) {
      DictionaryEntry chosen = *it;
      found.clear();
      found.push_back(std::move(chosen));
    } else {
      LOG_INF("DSET", "Dictionary '%s' is gone, searching all", SETTINGS.dictionaryName);
    }
  }

  uiLanguage = DictionaryMeta::languagePrefix(LANGUAGE_CODES[static_cast<size_t>(I18N.getLanguage())]);
  slots.reserve(found.size());
  for (auto& entry : found) {
    Slot slot;
    slot.entry = std::move(entry);
    slots.push_back(std::move(slot));
  }
  // Order and the header label only matter with several dictionaries; one
  // dictionary skips the extra .ifo/.idx reads.
  if (slots.size() > 1) {
    for (auto& slot : slots) {
      const std::string base = slot.entry.basePath();
      slot.script = DictionaryRegistry::headwordScript(base);
      const DictionaryMeta::IfoInfo facts = DictionaryRegistry::readIfo(base);
      slot.title = facts.bookname.empty() ? slot.entry.name : facts.bookname;
      slot.target = facts.target;
      LOG_DBG("DSET", "%s: %s", slot.entry.name.c_str(), DictionaryMeta::scriptName(slot.script));
    }
  }
  return State::Ready;
}

bool DictionarySet::ensureReady(Slot& slot, const Hooks& hooks, Dictionary::IndexResult& indexResult) {
  if (!slot.openTried) {
    slot.openTried = true;
    slot.dict = makeUniqueNoThrow<Dictionary>();
    if (!slot.dict) {
      LOG_ERR("DSET", "OOM: Dictionary");
      indexResult = Dictionary::IndexResult::LowMemory;
      slot.openTried = false;  // retry once memory frees up
      return false;
    }
    slot.openOk = slot.dict->open(slot.entry.name.c_str());
    // needsIndex() opens and validates the sidecar, so ask once per open: the
    // answer only changes when we build the sidecar ourselves.
    slot.needsIndex = slot.openOk && slot.dict->needsIndex();
  }
  if (!slot.openOk) return false;
  if (!slot.needsIndex) return true;

  if (hooks.onIndexing) hooks.onIndexing(hooks.ctx);
  Dictionary::IndexResult result = Dictionary::IndexResult::Ok;
  const bool built = slot.dict->buildIndex(hooks.yield, hooks.ctx, &result);
  slot.needsIndex = !built;  // a failed build retries on the next lookup
  if (!built) indexResult = result;
  return built;
}

DictionarySet::Outcome DictionarySet::lookup(const char* word, std::string& definitionOut, std::string& headwordOut,
                                             const Hooks& hooks) {
  Outcome outcome;
  // At most a handful of dictionaries: an index array beats sorting slots.
  std::vector<size_t> order(slots.size());
  for (size_t i = 0; i < order.size(); i++) order[i] = i;
  const DictionaryMeta::Script wordScript = DictionaryMeta::scriptOfWord(word);
  std::stable_sort(order.begin(), order.end(), [&](size_t a, size_t b) {
    return DictionaryMeta::orderRank(slots[a].script, wordScript, slots[a].target, uiLanguage) <
           DictionaryMeta::orderRank(slots[b].script, wordScript, slots[b].target, uiLanguage);
  });

  bool haveError = false;
  for (const size_t i : order) {
    Slot& slot = slots[i];
    if (!ensureReady(slot, hooks, outcome.indexResult)) continue;
    outcome.anyUsable = true;

    Dictionary::LookupResult result = Dictionary::LookupResult::NotFound;
    if (slot.dict->lookup(word, definitionOut, headwordOut, &result)) {
      outcome.found = true;
      outcome.lookupResult = Dictionary::LookupResult::Found;
      outcome.html = slot.dict->definitionsAreHtml();
      if (slots.size() > 1) outcome.source = slot.title;
      return outcome;
    }
    // Keep the first real failure; a later dictionary may still find the word.
    if (result != Dictionary::LookupResult::NotFound && !haveError) {
      outcome.lookupResult = result;
      haveError = true;
    }
  }
  return outcome;
}
