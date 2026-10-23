#include <gtest/gtest.h>

#include <cstdint>
#include <string>
#include <vector>

#include "DictionaryMeta.h"

using DictionaryMeta::Script;

namespace {

// Build .idx bytes: word, NUL, 8 tail bytes (tails full of NULs on purpose).
std::vector<uint8_t> idx(const std::vector<std::string>& words) {
  std::vector<uint8_t> out;
  uint32_t offset = 0;
  for (const auto& w : words) {
    out.insert(out.end(), w.begin(), w.end());
    out.push_back(0);
    for (int i = 3; i >= 0; i--) out.push_back(static_cast<uint8_t>(offset >> (i * 8)));
    for (int i = 0; i < 3; i++) out.push_back(0);
    out.push_back(16);
    offset += 16;
  }
  return out;
}

}  // namespace

TEST(DictionaryMeta, ScriptOfWord) {
  EXPECT_EQ(DictionaryMeta::scriptOfWord("book"), Script::Latin);
  EXPECT_EQ(DictionaryMeta::scriptOfWord("книгами"), Script::Cyrillic);
  EXPECT_EQ(DictionaryMeta::scriptOfWord("«Ёлка»,"), Script::Cyrillic);
  EXPECT_EQ(DictionaryMeta::scriptOfWord("café"), Script::Latin);
  EXPECT_EQ(DictionaryMeta::scriptOfWord("1984"), Script::Other);
  EXPECT_EQ(DictionaryMeta::scriptOfWord(nullptr), Script::Other);
}

TEST(DictionaryMeta, IdxHeadTally) {
  const auto data = idx({"a", "aback", "abandon", "abandoned", "abase"});
  DictionaryMeta::ScriptTally tally;
  DictionaryMeta::tallyIdxChunk(data.data(), data.size(), false, tally);
  EXPECT_EQ(DictionaryMeta::majority(tally), Script::Latin);
  EXPECT_EQ(tally.cyrillic, 0u);
}

TEST(DictionaryMeta, IdxMiddleResyncsPastBinaryTails) {
  const auto data = idx({"абажур", "абзац", "абонемент", "абрикос", "август", "авария", "автобус"});
  // Start mid-entry, inside the second word's tail.
  const size_t cut = 6 * 2 + 1 + 3;
  DictionaryMeta::ScriptTally tally;
  DictionaryMeta::tallyIdxChunk(data.data() + cut, data.size() - cut, true, tally);
  EXPECT_EQ(DictionaryMeta::majority(tally), Script::Cyrillic);
  EXPECT_GT(tally.cyrillic, 20u);
}

TEST(DictionaryMeta, RussianIndexOpeningWithAsciiStillReadsCyrillic) {
  // ASCII sorts first, so the head is Latin; the middle sample decides.
  const auto head = idx({"AC/DC", "CD", "DVD"});
  const auto middle = idx({"дом", "дорога", "друг", "думать", "дуб", "дым"});
  DictionaryMeta::ScriptTally tally;
  DictionaryMeta::tallyIdxChunk(head.data(), head.size(), false, tally);
  DictionaryMeta::tallyIdxChunk(middle.data() + 5, middle.size() - 5, true, tally);
  EXPECT_EQ(DictionaryMeta::majority(tally), Script::Cyrillic);
}

TEST(DictionaryMeta, TruncatedLastEntryIgnored) {
  auto data = idx({"book", "books"});
  data.resize(data.size() - 3);
  DictionaryMeta::ScriptTally tally;
  DictionaryMeta::tallyIdxChunk(data.data(), data.size(), false, tally);
  EXPECT_EQ(tally.latin, 4u);
}

TEST(DictionaryMeta, ParseIfo) {
  const auto info = DictionaryMeta::parseIfo(
      "StarDict's dict ifo file\nversion=3.0.0\nbookname=English-Russian (WikDict)\r\nwordcount=123456\n"
      "idxfilesize=999\nsametypesequence=h\nlang=en-ru\n");
  EXPECT_EQ(info.bookname, "English-Russian (WikDict)");
  EXPECT_EQ(info.wordcount, 123456u);
  EXPECT_TRUE(info.html);
  EXPECT_FALSE(info.offsets64);
  EXPECT_EQ(info.source, Script::Latin);

  const auto other = DictionaryMeta::parseIfo("bookname=X\nsametypesequence=hm\nidxoffsetbits=64\n");
  EXPECT_FALSE(other.html);
  EXPECT_TRUE(other.offsets64);
  EXPECT_EQ(other.source, Script::Other);
  EXPECT_EQ(DictionaryMeta::scriptOfLanguage("rus"), Script::Cyrillic);
  EXPECT_EQ(DictionaryMeta::scriptOfLanguage("tg-ru"), Script::Cyrillic);
  EXPECT_EQ(DictionaryMeta::scriptOfLanguage("ja"), Script::Other);
}

TEST(DictionaryMeta, SafeFolderNames) {
  EXPECT_TRUE(DictionaryMeta::isSafeFolderName("en-ru-wikdict"));
  EXPECT_TRUE(DictionaryMeta::isSafeFolderName("Ожегов"));
  EXPECT_FALSE(DictionaryMeta::isSafeFolderName(""));
  EXPECT_FALSE(DictionaryMeta::isSafeFolderName(".hidden"));
  EXPECT_FALSE(DictionaryMeta::isSafeFolderName("../etc"));
  EXPECT_FALSE(DictionaryMeta::isSafeFolderName("a/b"));
  EXPECT_FALSE(DictionaryMeta::isSafeFolderName("a\\b"));
  EXPECT_FALSE(DictionaryMeta::isSafeFolderName("*"));
  EXPECT_FALSE(DictionaryMeta::isSafeFolderName("-"));
  EXPECT_FALSE(DictionaryMeta::isSafeFolderName("0123456789012345678901234567890123"));
}

TEST(DictionaryMeta, Selection) {
  using DictionaryMeta::Selection;
  EXPECT_EQ(DictionaryMeta::selectionOf(""), Selection::All);
  EXPECT_EQ(DictionaryMeta::selectionOf("*"), Selection::All);
  EXPECT_EQ(DictionaryMeta::selectionOf("-"), Selection::Off);
  EXPECT_EQ(DictionaryMeta::selectionOf("webster"), Selection::One);
}

TEST(DictionaryMeta, OrderRank) {
  using DictionaryMeta::orderRank;
  // Russian interface, English word: en-ru, then en-en, then the rest.
  const int enRu = orderRank(Script::Latin, Script::Latin, "ru", "ru");
  const int enEn = orderRank(Script::Latin, Script::Latin, "en", "ru");
  const int ruRu = orderRank(Script::Cyrillic, Script::Latin, "ru", "ru");
  EXPECT_LT(enRu, enEn);
  EXPECT_LT(enEn, ruRu);
  // English interface flips the pair.
  EXPECT_LT(orderRank(Script::Latin, Script::Latin, "en", "en"), orderRank(Script::Latin, Script::Latin, "ru", "en"));
  // Unknown target never outranks a known match; unknown word script matches nothing.
  EXPECT_LT(enRu, orderRank(Script::Latin, Script::Latin, "", "ru"));
  EXPECT_EQ(orderRank(Script::Other, Script::Other, "", "ru"), orderRank(Script::Latin, Script::Other, "", "ru"));
}

TEST(DictionaryMeta, LanguageTarget) {
  EXPECT_EQ(DictionaryMeta::parseIfo("lang=en-ru\n").target, "ru");
  EXPECT_EQ(DictionaryMeta::parseIfo("lang=ru_RU-en\n").target, "en");
  EXPECT_EQ(DictionaryMeta::parseIfo("sourcelang=tg\ntargetlang=Russian\n").target, "ru");
  EXPECT_EQ(DictionaryMeta::parseIfo("lang=en\n").target, "");
  EXPECT_EQ(DictionaryMeta::languagePrefix("PT_BR"), "pt");
  EXPECT_EQ(DictionaryMeta::languagePrefix("x"), "");
}
