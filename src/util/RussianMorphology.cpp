#include "RussianMorphology.h"

#include <algorithm>
#include <cstring>

namespace russian {

namespace {

struct Rule {
  const char* suffix;
  const char* lemmaEndings[5];  // nullptr-terminated
};

// Longest suffix first; within a rule the more frequent lemma first. Covers
// noun and adjective cases and present/past verb forms of the common
// conjugation classes; irregular stems are left to the dictionary's .syn.
constexpr Rule RULES[] = {
    {"аешь", {"ать"}},
    {"яешь", {"ять"}},
    {"аете", {"ать"}},
    {"яете", {"ять"}},
    {"ями", {"я", "ь", "й"}},
    {"ами", {"а", "", "о"}},
    {"ого", {"ый", "ой", "ий"}},
    {"его", {"ий"}},
    {"ому", {"ый", "ой"}},
    {"ему", {"ий"}},
    {"ыми", {"ый", "ой"}},
    {"ими", {"ий", "ой"}},
    {"ают", {"ать"}},
    {"яют", {"ять"}},
    {"уют", {"овать"}},
    {"ует", {"овать"}},
    {"ает", {"ать"}},
    {"яет", {"ять"}},
    {"аем", {"ать"}},
    {"яем", {"ять"}},
    {"ала", {"ать"}},
    {"али", {"ать"}},
    {"ало", {"ать"}},
    {"яла", {"ять"}},
    {"яли", {"ять"}},
    {"яло", {"ять"}},
    {"ила", {"ить"}},
    {"или", {"ить"}},
    {"ило", {"ить"}},
    {"ела", {"еть"}},
    {"ели", {"еть"}},
    {"ело", {"еть"}},
    {"ишь", {"ить"}},
    {"ией", {"ия"}},
    {"ием", {"ие", "ий"}},
    {"ах", {"а", ""}},
    {"ях", {"я", "ь", "й"}},
    {"ам", {"а", ""}},
    {"ям", {"я", "ь", "й"}},
    {"ов", {""}},
    {"ев", {"й", "ь"}},
    {"ей", {"ь", "я", "ий"}},
    {"ой", {"а", "ый", ""}},
    {"ом", {"", "о"}},
    {"ем", {"ь", "е", "й"}},
    {"ою", {"а"}},
    {"ею", {"я"}},
    {"ью", {"ь"}},
    {"ые", {"ый"}},
    {"ие", {"ий"}},
    {"ое", {"ый", "ой"}},
    {"ее", {"ий"}},
    {"ая", {"ый", "ой"}},
    {"яя", {"ий"}},
    {"ую", {"ый", "ой", "овать"}},
    {"юю", {"ий"}},
    {"ых", {"ый", "ой"}},
    {"их", {"ий", "ой"}},
    {"ым", {"ый", "ой"}},
    {"им", {"ий", "ить", "ой"}},
    {"ии", {"ия", "ие", "ий"}},
    {"аю", {"ать"}},
    {"яю", {"ять"}},
    {"ит", {"ить", "еть"}},
    {"ят", {"ить", "ять", "еть"}},
    {"ат", {"ать"}},
    {"ал", {"ать"}},
    {"ял", {"ять"}},
    {"ил", {"ить"}},
    {"ел", {"еть"}},
    {"ул", {"уть"}},
    {"а", {"", "о"}},
    {"у", {"", "а", "о"}},
    {"ы", {"", "а"}},
    {"и", {"а", "я", "ь", "й", ""}},
    {"е", {"", "а", "о", "я", "ь"}},
    {"ю", {"я", "ь", "й"}},
    {"я", {"ь", "й", "е"}},
};

// Every Cyrillic letter is two UTF-8 bytes; keep at least two letters of stem.
constexpr size_t MIN_STEM_BYTES = 4;

bool endsWith(const std::string& word, const char* suffix, size_t& suffixLen) {
  suffixLen = strlen(suffix);
  return word.size() >= suffixLen + MIN_STEM_BYTES && word.compare(word.size() - suffixLen, suffixLen, suffix) == 0;
}

void add(std::vector<std::string>& out, const std::string& original, std::string candidate) {
  if (out.size() >= MAX_CANDIDATES || candidate == original) return;
  if (std::find(out.begin(), out.end(), candidate) == out.end()) out.push_back(std::move(candidate));
}

void applyRules(const std::string& word, const std::string& original, const char* append,
                std::vector<std::string>& out) {
  for (const Rule& rule : RULES) {
    size_t len = 0;
    if (!endsWith(word, rule.suffix, len)) continue;
    const std::string stem = word.substr(0, word.size() - len);
    for (const char* ending : rule.lemmaEndings) {
      if (!ending) break;
      std::string lemma = stem + ending;
      // Reflexive forms only make sense as reflexive infinitives.
      if (append) {
        if (lemma.size() < 4 || lemma.compare(lemma.size() - 4, 4, "ть") != 0) continue;
        lemma += append;
      }
      add(out, original, std::move(lemma));
    }
  }
}

// "ё" (D1 91) -> "е" (D0 B5): most dictionaries spell headwords without ё.
bool replaceYo(std::string& word) {
  bool changed = false;
  for (size_t i = 0; i + 1 < word.size(); i++) {
    if (static_cast<unsigned char>(word[i]) == 0xD1 && static_cast<unsigned char>(word[i + 1]) == 0x91) {
      word[i] = static_cast<char>(0xD0);
      word[i + 1] = static_cast<char>(0xB5);
      changed = true;
    }
  }
  return changed;
}

}  // namespace

void lowercaseCyrillic(std::string& word) {
  for (size_t i = 0; i + 1 < word.size(); i++) {
    auto* b = reinterpret_cast<unsigned char*>(&word[i]);
    if (b[0] != 0xD0) continue;
    if (b[1] >= 0x90 && b[1] <= 0x9F) {  // А-П -> а-п
      b[1] = static_cast<unsigned char>(b[1] + 0x20);
    } else if (b[1] >= 0xA0 && b[1] <= 0xAF) {  // Р-Я -> р-я
      b[0] = 0xD1;
      b[1] = static_cast<unsigned char>(b[1] - 0x20);
    } else if (b[1] >= 0x80 && b[1] <= 0x8F) {  // Ѐ-Џ (Ё Є І Ї Ў ...) -> ѐ-џ
      b[0] = 0xD1;
      b[1] = static_cast<unsigned char>(b[1] + 0x10);
    }
    i++;
  }
}

bool hasCyrillic(const std::string& word) {
  for (size_t i = 0; i + 1 < word.size(); i++) {
    const auto lead = static_cast<unsigned char>(word[i]);
    if (lead == 0xD0 || lead == 0xD1) return true;
  }
  return false;
}

void lemmaCandidates(const std::string& word, std::vector<std::string>& out) {
  out.clear();
  out.reserve(MAX_CANDIDATES);
  std::string plain = word;
  if (replaceYo(plain)) add(out, word, plain);

  size_t len = 0;
  const bool reflexive = endsWith(word, "ся", len) || endsWith(word, "сь", len);
  if (reflexive) {
    const std::string base = word.substr(0, word.size() - len);
    applyRules(base, word, "ся", out);
  }
  applyRules(word, word, nullptr, out);

  // ё -> е spellings of what the rules produced.
  const size_t produced = out.size();
  for (size_t i = 0; i < produced && out.size() < MAX_CANDIDATES; i++) {
    std::string candidate = out[i];
    if (replaceYo(candidate)) add(out, word, std::move(candidate));
  }
}

}  // namespace russian
