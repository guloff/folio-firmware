#include <gtest/gtest.h>

#include <string>

#include "FootnoteExtract.h"

namespace {

bool extract(const std::string& html, const std::string& id, std::string& out, size_t max = 8192) {
  return footnote::extractById(html.data(), html.size(), id, out, max);
}

}  // namespace

TEST(FootnoteExtract, BlockWithId) {
  const std::string html =
      "<html><body><p>Text<a href=\"#n1\">1</a></p>"
      "<p id=\"n1\"><a href=\"#r1\">1</a> The note <i>itself</i>.</p><p>After</p></body></html>";
  std::string out;
  ASSERT_TRUE(extract(html, "n1", out));
  EXPECT_EQ(out, "<p id=\"n1\"><a href=\"#r1\">1</a> The note <i>itself</i>.</p>");
}

TEST(FootnoteExtract, EmptyAnchorWidensToParagraph) {
  const std::string html = "<body><div class=\"notes\"><p><a id=\"fn2\"></a>2. Second note.</p></div></body>";
  std::string out;
  ASSERT_TRUE(extract(html, "fn2", out));
  EXPECT_EQ(out, "<p><a id=\"fn2\"></a>2. Second note.</p>");
}

TEST(FootnoteExtract, SelfClosingAnchorAndSingleQuotes) {
  const std::string html = "<body><p class='n'><a id='x'/>Note x</p></body>";
  std::string out;
  ASSERT_TRUE(extract(html, "x", out));
  EXPECT_EQ(out, "<p class='n'><a id='x'/>Note x</p>");
}

TEST(FootnoteExtract, AsideWithNestedBlocks) {
  const std::string html =
      "<body><aside epub:type=\"footnote\" id=\"f\"><p>One</p><div><p>Two</p></div></aside><p>x</p></body>";
  std::string out;
  ASSERT_TRUE(extract(html, "f", out));
  EXPECT_EQ(out, "<aside epub:type=\"footnote\" id=\"f\"><p>One</p><div><p>Two</p></div></aside>");
}

TEST(FootnoteExtract, NestedSameTagDepth) {
  const std::string html = "<body><div id=\"d\"><div>in</div> tail</div><div>other</div></body>";
  std::string out;
  ASSERT_TRUE(extract(html, "d", out));
  EXPECT_EQ(out, "<div id=\"d\"><div>in</div> tail</div>");
}

TEST(FootnoteExtract, ListItem) {
  const std::string html = "<ol><li id=\"fn1\">First<br/>line</li><li id=\"fn2\">Second</li></ol>";
  std::string out;
  ASSERT_TRUE(extract(html, "fn2", out));
  EXPECT_EQ(out, "<li id=\"fn2\">Second</li>");
}

TEST(FootnoteExtract, IgnoresDataIdAndComments) {
  const std::string html = "<body><p data-id=\"n\">wrong</p><!-- <p id=\"n\"> --><p id=\"n\">right</p></body>";
  std::string out;
  ASSERT_TRUE(extract(html, "n", out));
  EXPECT_EQ(out, "<p id=\"n\">right</p>");
}

TEST(FootnoteExtract, HeadingIsNotANote) {
  const std::string html = "<body><h2 id=\"ch2\">Chapter 2</h2><p>Text</p></body>";
  std::string out;
  EXPECT_FALSE(extract(html, "ch2", out));
}

TEST(FootnoteExtract, TooLongFails) {
  std::string html = "<body><div id=\"big\">";
  html += std::string(10000, 'a');
  html += "</div></body>";
  std::string out;
  EXPECT_FALSE(extract(html, "big", out));
}

TEST(FootnoteExtract, MissingAnchorOrUnclosed) {
  std::string out;
  EXPECT_FALSE(extract("<body><p id=\"a\">x</p></body>", "b", out));
  EXPECT_FALSE(extract("<body><p id=\"a\">never closed", "a", out));
}

TEST(FootnoteExtract, SpanWrappingBlocks) {
  // Liu Cixin / Sagan style: the note is a span around a number div and a paragraph.
  const std::string html =
      "<body><span id=\"id6\"><div class=\"t\"><p>27</p></div><p>Note 27.</p></span>"
      "<span id=\"id5\"><div class=\"t\"><p>28</p></div><p>Note 28.</p></span></body>";
  std::string out;
  ASSERT_TRUE(extract(html, "id5", out));
  EXPECT_EQ(out, "<span id=\"id5\"><div class=\"t\"><p>28</p></div><p>Note 28.</p></span>");
}

TEST(FootnoteExtract, InlineSpanInsideParagraphWidens) {
  const std::string html = "<body><p><span id=\"s\">1.</span> Note text.</p></body>";
  std::string out;
  ASSERT_TRUE(extract(html, "s", out));
  EXPECT_EQ(out, "<p><span id=\"s\">1.</span> Note text.</p>");
}

TEST(FootnoteExtract, SelfLinkIsReported) {
  // Sapiens style: the noteref carries the id it links to; the note lives in another file.
  const std::string html = "<body><p>Gold<a class=\"x\" href=\"#note50\" id=\"note50\">50</a>.</p></body>";
  std::string out;
  EXPECT_EQ(footnote::extract(html.data(), html.size(), "note50", out), footnote::Result::SelfLink);
  // The note file itself is fine: the div holds a backlink, not the id.
  const std::string notes = "<body><div id=\"note50\"><p><a href=\"#note50\">50.</a> Source.</p></div></body>";
  ASSERT_TRUE(extract(notes, "note50", out));
  EXPECT_EQ(out, "<div id=\"note50\"><p><a href=\"#note50\">50.</a> Source.</p></div>");
}

TEST(FootnoteExtract, PageBreakIsNotANote) {
  const std::string html =
      "<body><p>Text <span epub:type=\"pagebreak\" id=\"page_9\" role=\"doc-pagebreak\"/>more.</p></body>";
  std::string out;
  EXPECT_EQ(footnote::extract(html.data(), html.size(), "page_9", out), footnote::Result::NotANote);
}

TEST(FootnoteExtract, BacklinkWithSameIdIsANote) {
  const std::string html =
      "<body><p class=\"num2\"><a id=\"linka1\" href=\"../Text/content05.html#linka1\">1.</a> Source.</p></body>";
  std::string out;
  ASSERT_TRUE(extract(html, "linka1", out));
  EXPECT_EQ(out, "<p class=\"num2\"><a id=\"linka1\" href=\"../Text/content05.html#linka1\">1.</a> Source.</p>");
}
