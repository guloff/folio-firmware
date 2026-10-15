#pragma once

#include <string>
#include <vector>

// Dictionary lookup helpers for Russian. Dictionaries list lemmas (nominative
// singular, masculine adjective, infinitive); a tapped word is usually
// inflected. Instead of a full morphological analyzer (megabytes of tables),
// candidates are produced by swapping common endings, and the dictionary index
// decides which one exists. All strings are UTF-8.
namespace russian {

// Lowercases Cyrillic letters (А-Я, Ё and the Ukrainian/Belarusian Є І Ї Ў)
// in place; other bytes are left alone.
void lowercaseCyrillic(std::string& word);

bool hasCyrillic(const std::string& word);

// Lemma candidates for a lowercase word, most likely first, without the word
// itself and without duplicates: "книгами" -> "книга", "красивого" ->
// "красивый", "читают" -> "читать", "смеялись" -> "смеяться", plus ё -> е
// spellings. Bounded to MAX_CANDIDATES.
constexpr size_t MAX_CANDIDATES = 24;
void lemmaCandidates(const std::string& word, std::vector<std::string>& out);

}  // namespace russian
