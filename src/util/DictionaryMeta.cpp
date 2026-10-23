#include "DictionaryMeta.h"

#include <cctype>
#include <cstdlib>
#include <cstring>

namespace DictionaryMeta {
namespace {

constexpr int RESYNC_CHAIN = 4;     // consecutive clean entries that prove alignment
constexpr size_t MAX_WORD_BYTES = 255;  // StarDict caps headwords at 256 bytes with NUL
constexpr size_t ENTRY_TAIL_BYTES = 8;  // 32-bit offset + 32-bit size

// Decode one UTF-8 code point at s[i]; returns its length (0 on malformed).
size_t decode(const unsigned char* s, size_t len, size_t i, uint32_t& cp) {
  const unsigned char c = s[i];
  if (c < 0x80) {
    cp = c;
    return 1;
  }
  size_t n = 0;
  if ((c & 0xE0) == 0xC0) {
    n = 2;
    cp = c & 0x1F;
  } else if ((c & 0xF0) == 0xE0) {
    n = 3;
    cp = c & 0x0F;
  } else if ((c & 0xF8) == 0xF0) {
    n = 4;
    cp = c & 0x07;
  } else {
    return 0;
  }
  if (i + n > len) return 0;
  for (size_t k = 1; k < n; k++) {
    if ((s[i + k] & 0xC0) != 0x80) return 0;
    cp = (cp << 6) | (s[i + k] & 0x3F);
  }
  return n;
}

bool isLatinLetter(uint32_t cp) {
  return (cp >= 'A' && cp <= 'Z') || (cp >= 'a' && cp <= 'z') || (cp >= 0xC0 && cp <= 0x24F && cp != 0xD7 && cp != 0xF7);
}

bool isCyrillicLetter(uint32_t cp) { return cp >= 0x400 && cp <= 0x52F; }

// Length of a clean headword at data[pos] (valid UTF-8, no control bytes,
// NUL-terminated within MAX_WORD_BYTES and followed by a full 8-byte tail),
// or 0 when the bytes there are not one.
size_t cleanEntryWordLen(const uint8_t* data, size_t size, size_t pos) {
  size_t i = pos;
  while (i < size && data[i] != 0) {
    if (i - pos >= MAX_WORD_BYTES || data[i] < 0x20) return 0;
    uint32_t cp = 0;
    const size_t n = decode(data, size, i, cp);
    if (n == 0) return 0;
    i += n;
  }
  if (i >= size || i == pos) return 0;
  if (i + 1 + ENTRY_TAIL_BYTES > size) return 0;
  return i - pos;
}

}  // namespace

const char* scriptName(const Script script) {
  switch (script) {
    case Script::Latin:
      return "latin";
    case Script::Cyrillic:
      return "cyrillic";
    case Script::Other:
    default:
      return "other";
  }
}

void tallyWord(const char* word, const size_t len, ScriptTally& tally) {
  const auto* s = reinterpret_cast<const unsigned char*>(word);
  size_t i = 0;
  while (i < len) {
    uint32_t cp = 0;
    const size_t n = decode(s, len, i, cp);
    if (n == 0) {
      i++;
      continue;
    }
    if (isLatinLetter(cp)) {
      tally.latin++;
    } else if (isCyrillicLetter(cp)) {
      tally.cyrillic++;
    }
    i += n;
  }
}

Script majority(const ScriptTally& tally) {
  if (tally.latin == 0 && tally.cyrillic == 0) return Script::Other;
  return tally.cyrillic > tally.latin ? Script::Cyrillic : Script::Latin;
}

Script scriptOfWord(const char* word) {
  if (!word) return Script::Other;
  ScriptTally tally;
  tallyWord(word, strlen(word), tally);
  return majority(tally);
}

void tallyIdxChunk(const uint8_t* data, const size_t size, const bool resync, ScriptTally& tally) {
  if (!data || size == 0) return;
  size_t pos = 0;
  if (resync) {
    bool aligned = false;
    for (size_t start = 0; start < size && !aligned; start++) {
      size_t p = start;
      int chain = 0;
      while (chain < RESYNC_CHAIN) {
        const size_t len = cleanEntryWordLen(data, size, p);
        if (len == 0) break;
        p += len + 1 + ENTRY_TAIL_BYTES;
        chain++;
      }
      if (chain == RESYNC_CHAIN) {
        pos = start;
        aligned = true;
      }
    }
    if (!aligned) return;
  }
  while (pos < size) {
    const size_t len = cleanEntryWordLen(data, size, pos);
    if (len == 0) break;
    tallyWord(reinterpret_cast<const char*>(data + pos), len, tally);
    pos += len + 1 + ENTRY_TAIL_BYTES;
  }
}

Script scriptOfLanguage(const char* lang) {
  if (!lang) return Script::Other;
  char code[4] = {};
  size_t n = 0;
  while (lang[n] && n < 3 && isalpha(static_cast<unsigned char>(lang[n]))) {
    code[n] = static_cast<char>(tolower(static_cast<unsigned char>(lang[n])));
    n++;
  }
  if (n < 2) return Script::Other;
  static constexpr const char* CYRILLIC[] = {"ru", "rus", "uk", "ukr", "be", "bel", "bg", "bul", "sr", "srp",
                                             "mk", "mkd", "tg", "tgk", "kk", "kaz", "ky", "kir", "mn", "mon"};
  static constexpr const char* LATIN[] = {"en", "eng", "de", "deu", "ger", "fr", "fra", "fre", "es", "spa",
                                          "it", "ita", "pt", "por", "nl", "nld", "pl", "pol", "cs", "ces",
                                          "sv", "swe", "fi", "fin", "da", "dan", "no", "nor", "tr", "tur",
                                          "la", "lat", "ro", "ron", "hu", "hun", "uz", "uzb", "id", "ind"};
  for (const char* c : CYRILLIC) {
    if (strcmp(code, c) == 0) return Script::Cyrillic;
  }
  for (const char* c : LATIN) {
    if (strcmp(code, c) == 0) return Script::Latin;
  }
  return Script::Other;
}

IfoInfo parseIfo(const char* text) {
  IfoInfo info;
  if (!text) return info;
  const char* line = text;
  while (*line) {
    const char* end = line;
    while (*end && *end != '\n' && *end != '\r') end++;
    const char* eq = static_cast<const char*>(memchr(line, '=', end - line));
    if (eq) {
      const std::string key(line, eq - line);
      const std::string value(eq + 1, end - eq - 1);
      if (key == "bookname") {
        info.bookname = value;
      } else if (key == "wordcount") {
        info.wordcount = static_cast<uint32_t>(strtoul(value.c_str(), nullptr, 10));
      } else if (key == "idxoffsetbits") {
        info.offsets64 = strtol(value.c_str(), nullptr, 10) == 64;
      } else if (key == "sametypesequence") {
        // Only the single-field sequence "h" is HTML; multi-type entries keep
        // the plain-text viewing path.
        info.html = value == "h";
      } else if (key == "lang" || key == "sourcelang" || key == "sourceLang") {
        if (info.source == Script::Other) info.source = scriptOfLanguage(value.c_str());
        // "lang=en-ru": the part after the separator is the definition language.
        const size_t dash = value.find('-');
        if (key == "lang" && dash != std::string::npos && info.target.empty()) {
          info.target = languagePrefix(value.c_str() + dash + 1);
        }
      } else if ((key == "targetlang" || key == "targetLang") && info.target.empty()) {
        info.target = languagePrefix(value.c_str());
      }
    }
    line = end;
    while (*line == '\n' || *line == '\r') line++;
  }
  return info;
}

std::string languagePrefix(const char* code) {
  if (!code || !isalpha(static_cast<unsigned char>(code[0])) || !isalpha(static_cast<unsigned char>(code[1]))) {
    return {};
  }
  return {static_cast<char>(tolower(static_cast<unsigned char>(code[0]))),
          static_cast<char>(tolower(static_cast<unsigned char>(code[1])))};
}

int orderRank(const Script dictionary, const Script word, const std::string& target, const std::string& uiLanguage) {
  const int scriptRank = (word != Script::Other && dictionary == word) ? 0 : 2;
  const int languageRank = (!target.empty() && target == uiLanguage) ? 0 : 1;
  return scriptRank + languageRank;
}

bool isSafeFolderName(const char* name, const size_t maxLen) {
  if (!name || name[0] == '\0' || name[0] == '.') return false;
  const size_t len = strlen(name);
  if (len > maxLen) return false;
  if (strcmp(name, SELECT_ALL) == 0 || strcmp(name, SELECT_OFF) == 0) return false;
  for (size_t i = 0; i < len; i++) {
    const unsigned char c = static_cast<unsigned char>(name[i]);
    if (c < 0x20 || c == '/' || c == '\\' || c == '*' || c == ':' || c == '?' || c == '"' || c == '<' ||
        c == '>' || c == '|') {
      return false;
    }
  }
  return true;
}

Selection selectionOf(const char* stored) {
  if (!stored || stored[0] == '\0' || strcmp(stored, SELECT_ALL) == 0) return Selection::All;
  if (strcmp(stored, SELECT_OFF) == 0) return Selection::Off;
  return Selection::One;
}

}  // namespace DictionaryMeta
