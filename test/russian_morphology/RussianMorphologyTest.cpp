#include <gtest/gtest.h>

#include <algorithm>
#include <string>
#include <vector>

#include "RussianMorphology.h"

namespace {

bool hasCandidate(const std::string& word, const std::string& lemma) {
  std::vector<std::string> out;
  russian::lemmaCandidates(word, out);
  return std::find(out.begin(), out.end(), lemma) != out.end();
}

}  // namespace

TEST(RussianMorphology, LowercasesCyrillic) {
  std::string word = "КНИГАМИ Ёлка Їжак Abc";
  russian::lowercaseCyrillic(word);
  EXPECT_EQ(word, "книгами ёлка їжак Abc");
}

TEST(RussianMorphology, DetectsCyrillic) {
  EXPECT_TRUE(russian::hasCyrillic("слово"));
  EXPECT_FALSE(russian::hasCyrillic("word"));
}

TEST(RussianMorphology, Nouns) {
  EXPECT_TRUE(hasCandidate("книгами", "книга"));
  EXPECT_TRUE(hasCandidate("книгу", "книга"));
  EXPECT_TRUE(hasCandidate("книге", "книга"));
  EXPECT_TRUE(hasCandidate("столами", "стол"));
  EXPECT_TRUE(hasCandidate("столом", "стол"));
  EXPECT_TRUE(hasCandidate("окнами", "окно"));
  EXPECT_TRUE(hasCandidate("лошадью", "лошадь"));
  EXPECT_TRUE(hasCandidate("историей", "история"));
  EXPECT_TRUE(hasCandidate("музеями", "музей"));
}

TEST(RussianMorphology, Adjectives) {
  EXPECT_TRUE(hasCandidate("красивого", "красивый"));
  EXPECT_TRUE(hasCandidate("красивая", "красивый"));
  EXPECT_TRUE(hasCandidate("синему", "синий"));
  EXPECT_TRUE(hasCandidate("большими", "большой"));
}

TEST(RussianMorphology, Verbs) {
  EXPECT_TRUE(hasCandidate("читают", "читать"));
  EXPECT_TRUE(hasCandidate("читаешь", "читать"));
  EXPECT_TRUE(hasCandidate("читала", "читать"));
  EXPECT_TRUE(hasCandidate("говорит", "говорить"));
  EXPECT_TRUE(hasCandidate("говорил", "говорить"));
  EXPECT_TRUE(hasCandidate("рисуют", "рисовать"));
  EXPECT_TRUE(hasCandidate("смеялись", "смеяться"));
  EXPECT_TRUE(hasCandidate("улыбается", "улыбаться"));
}

TEST(RussianMorphology, YoSpelling) {
  EXPECT_TRUE(hasCandidate("ёлки", "елки"));
  EXPECT_TRUE(hasCandidate("ёлки", "ёлка"));
  EXPECT_TRUE(hasCandidate("ёлки", "елка"));
}

TEST(RussianMorphology, Bounded) {
  std::vector<std::string> out;
  russian::lemmaCandidates("переписывающимися", out);
  EXPECT_LE(out.size(), russian::MAX_CANDIDATES);
  for (const auto& c : out) EXPECT_FALSE(c.empty());
}

TEST(RussianMorphology, ShortWordsKeepStem) {
  std::vector<std::string> out;
  russian::lemmaCandidates("он", out);
  EXPECT_TRUE(out.empty());
}

TEST(RussianMorphology, NeverReturnsTheWordItself) {
  std::vector<std::string> out;
  russian::lemmaCandidates("стол", out);
  EXPECT_EQ(std::find(out.begin(), out.end(), "стол"), out.end());
}
