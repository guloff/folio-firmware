#include <gtest/gtest.h>

#include "FsHelpers.h"

namespace {

using namespace std::string_view_literals;

TEST(IsSafePathComponent, AcceptsNamesWithRepeatedDots) {
  EXPECT_TRUE(FsHelpers::isSafePathComponent("volume..2.epub"sv));
  EXPECT_TRUE(FsHelpers::isSafePathComponent("notes...txt"sv));
  EXPECT_TRUE(FsHelpers::isSafePathComponent(".hidden"sv));
  EXPECT_TRUE(FsHelpers::isSafePathComponent("a.b"sv));
  EXPECT_TRUE(FsHelpers::isSafePathComponent("book.epub"sv));
}

TEST(IsSafePathComponent, RejectsEmptyAndExactDotComponents) {
  EXPECT_FALSE(FsHelpers::isSafePathComponent(""sv));
  EXPECT_FALSE(FsHelpers::isSafePathComponent("."sv));
  EXPECT_FALSE(FsHelpers::isSafePathComponent(".."sv));
}

TEST(IsSafePathComponent, RejectsPathSeparatorsAnywhereInTheComponent) {
  EXPECT_FALSE(FsHelpers::isSafePathComponent("a/b"sv));
  EXPECT_FALSE(FsHelpers::isSafePathComponent("a\\b"sv));
  EXPECT_FALSE(FsHelpers::isSafePathComponent("../x"sv));
  EXPECT_FALSE(FsHelpers::isSafePathComponent("x/.."sv));
}

TEST(NormalisePath, CollapsesParentReferenceWithinPath) {
  EXPECT_EQ(FsHelpers::normalisePath("/Books/../.crosspoint/x"), ".crosspoint/x");
}

TEST(Fb2Extension, MatchesPlainAndZippedFb2) {
  EXPECT_TRUE(FsHelpers::hasFb2Extension("Book.fb2"sv));
  EXPECT_TRUE(FsHelpers::hasFb2Extension("Book.FB2.ZIP"sv));
  EXPECT_FALSE(FsHelpers::hasFb2Extension("Book.zip"sv));
  EXPECT_FALSE(FsHelpers::hasFb2Extension("Book.epub"sv));
}

TEST(OpensAsEpub, CoversEpubAndFb2Only) {
  EXPECT_TRUE(FsHelpers::opensAsEpub("a.epub"sv));
  EXPECT_TRUE(FsHelpers::opensAsEpub("a.fb2"sv));
  EXPECT_TRUE(FsHelpers::opensAsEpub("a.fb2.zip"sv));
  EXPECT_FALSE(FsHelpers::opensAsEpub("a.txt"sv));
  EXPECT_FALSE(FsHelpers::opensAsEpub("a.xtc"sv));
}

TEST(NormalisePath, DropsLeadingParentReferencesPastRoot) { EXPECT_EQ(FsHelpers::normalisePath("/../../etc"), "etc"); }

}  // namespace
