#pragma once

#include <cstddef>
#include <cstdint>
#include <string>

// Storage-free helpers shared by the dictionary registry, the multi-dictionary
// lookup and the companion API: .ifo facts, the writing system of a word or of
// a dictionary's headwords, and the stored dictionary selection.
namespace DictionaryMeta {

// SETTINGS.dictionaryName sentinels. Neither can be a dictionary folder: '*'
// is illegal in FAT names and isSafeFolderName() rejects a bare "-".
constexpr const char* SELECT_ALL = "*";
constexpr const char* SELECT_OFF = "-";

enum class Script : uint8_t { Other, Latin, Cyrillic };

const char* scriptName(Script script);

// Majority writing system of the letters in a UTF-8 word.
Script scriptOfWord(const char* word);

// Letter counts per writing system, summed over words.
struct ScriptTally {
  uint32_t latin = 0;
  uint32_t cyrillic = 0;
};

void tallyWord(const char* word, size_t len, ScriptTally& tally);
Script majority(const ScriptTally& tally);

// Tally the headwords of an .idx chunk (entries: NUL-terminated word + 8 bytes
// of offset/size). A chunk read from the start of the file is parsed as is;
// one read from the middle (resync = true) first skips to the earliest offset
// where several consecutive entries parse cleanly, since the binary 8-byte
// tails can hold NULs. A truncated last entry is ignored. Sampling the middle
// matters: ASCII sorts first, so a Russian index often opens with Latin words.
void tallyIdxChunk(const uint8_t* data, size_t size, bool resync, ScriptTally& tally);

// Two-letter language code ("en", "ru-en", "eng") to its writing system.
Script scriptOfLanguage(const char* lang);

struct IfoInfo {
  std::string bookname;
  uint32_t wordcount = 0;
  bool offsets64 = false;  // idxoffsetbits=64 (unsupported)
  bool html = false;       // sametypesequence=h
  Script source = Script::Other;  // from lang= / sourcelang= when present
  std::string target;             // definition language ("ru" of lang=en-ru), lowercase; empty = unknown
};

// Parse the "key=value" lines of an .ifo (NUL-terminated text).
IfoInfo parseIfo(const char* text);

// A folder name the API and settings may address: non-empty, at most
// maxLen bytes, no path separators, no leading dot, not a sentinel.
bool isSafeFolderName(const char* name, size_t maxLen = 31);

enum class Selection : uint8_t { All, Off, One };

// Stored value to mode. Empty (settings from before 1.1) reads as All.
Selection selectionOf(const char* stored);

// Lowercase two-letter prefix of a language code ("RU", "pt_BR", "eng" -> "ru",
// "pt", "en"); empty when it does not start with two letters.
std::string languagePrefix(const char* code);

// Lookup order key, lower first: dictionaries whose headwords share the
// word's script beat the rest; within that, ones that explain in the reader's
// interface language (uiLanguage, two letters) go first — so with a Russian
// interface an English word reaches en-ru before en-en. Unknown scripts and
// languages never outrank a match.
int orderRank(Script dictionary, Script word, const std::string& target, const std::string& uiLanguage);

}  // namespace DictionaryMeta
