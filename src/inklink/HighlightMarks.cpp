#include "HighlightMarks.h"

#include <ArduinoJson.h>
#include <GfxRenderer.h>
#include <Logging.h>

#include <algorithm>
#include <cctype>
#include <cstring>

#include "Annotations.h"
#include "CrossPointSettings.h"
#include <Epub/Page.h>
#include "JsonLines.h"

namespace inklink::marks {

namespace {

struct Mark {
  int spine = -1;
  std::vector<std::string> words;
};

// At most this many highlights of one book are kept for drawing.
constexpr size_t MAX_MARKS = 256;
// Words compared per highlight; longer ones are matched on this prefix only.
constexpr size_t MAX_WORDS = 400;

std::vector<Mark> marks;
std::string loadedBook;
uint32_t loadedGeneration = UINT32_MAX;

void splitWords(const char* text, std::vector<std::string>& out) {
  out.clear();
  std::string word;
  for (const char* p = text;; p++) {
    if (*p == ' ' || *p == '\0') {
      if (!word.empty() && out.size() < MAX_WORDS) out.push_back(word);
      word.clear();
      if (*p == '\0') break;
    } else {
      word.push_back(*p);
    }
  }
  // A text cut at Annotations::MAX_TEXT_BYTES ends in "…" mid-word: drop it.
  if (!out.empty()) {
    const std::string& last = out.back();
    if (last.size() >= 3 && last.compare(last.size() - 3, 3, "\xE2\x80\xA6") == 0) out.pop_back();
  }
}

struct LoadCtx {
  const char* book;
};

bool collect(JsonObjectConst obj, void* raw) {
  const auto* ctx = static_cast<LoadCtx*>(raw);
  if (strcmp(obj["b"] | "", ctx->book) != 0) return true;
  if (marks.size() >= MAX_MARKS) return false;
  Mark m;
  m.spine = obj["sp"] | -1;
  splitWords(obj["x"] | "", m.words);
  if (!m.words.empty()) marks.push_back(std::move(m));
  return true;
}

struct WordBox {
  int x;
  int y;
  int width;
  const char* text;
  uint8_t style;
};

}  // namespace

bool isHighlightToken(const char* text) {
  for (const uint8_t* p = reinterpret_cast<const uint8_t*>(text); *p != 0; p++) {
    if (*p < 0x80) {
      if (std::isalnum(*p)) return true;
    } else if (*p == 0xE2 && (p[1] == 0x80 || p[1] == 0x81)) {
      if (p[2] == 0) break;  // truncated sequence: skipping would step past the NUL
      p += 2;                // skip the 3-byte General Punctuation codepoint
    } else {
      return true;
    }
  }
  return false;
}

void refresh(const std::string& bookPath) {
  const uint32_t gen = Annotations::generation();
  if (bookPath == loadedBook && gen == loadedGeneration) return;
  marks.clear();
  loadedBook = bookPath;
  loadedGeneration = gen;
  LoadCtx ctx{bookPath.c_str()};
  if (!jsonl::forEach(Annotations::HIGHLIGHTS_PATH, collect, &ctx)) {
    LOG_DBG("MARKS", "no highlights file");
  }
  LOG_DBG("MARKS", "%u highlights for this book", static_cast<unsigned>(marks.size()));
}

void clear() {
  marks.clear();
  marks.shrink_to_fit();
  loadedBook.clear();
  loadedGeneration = UINT32_MAX;
}

void drawPage(GfxRenderer& renderer, const Page& page, const int spine, const int fontId, const int marginLeft,
              const int marginTop) {
  if (!SETTINGS.showHighlights || marks.empty()) return;
  bool any = false;
  for (const auto& m : marks) any |= m.spine == spine;
  if (!any) return;

  // The page's selectable words, in reading order.
  std::vector<WordBox> words;
  words.reserve(128);
  const int ascender = renderer.getFontAscenderSize(fontId);
  for (const auto& element : page.elements) {
    if (element->getTag() != TAG_PageLine) continue;
    const auto* line = static_cast<const PageLine*>(element.get());
    const auto* block = line->getBlock();
    if (!block || !block->valid()) continue;
    const int rubyShift = block->getRubyShift(ascender);
    for (uint16_t i = 0; i < block->wordCount(); i++) {
      const char* text = block->wordText(i);
      if (!isHighlightToken(text)) continue;
      words.push_back({line->xPos + block->wordXpos(i) + marginLeft, line->yPos + marginTop + rubyShift, 0, text,
                       static_cast<uint8_t>(block->wordStyle(i))});
    }
  }
  if (words.empty()) return;

  const size_t n = words.size();
  std::vector<uint8_t> marked(n, 0);
  const auto eq = [&](size_t pageIdx, const std::string& w) { return w == words[pageIdx].text; };
  for (const auto& m : marks) {
    if (m.spine != spine) continue;
    const size_t k = m.words.size();
    // 1) The whole highlight on this page.
    bool found = false;
    for (size_t s = 0; k <= n && s + k <= n && !found; s++) {
      size_t j = 0;
      while (j < k && eq(s + j, m.words[j])) j++;
      if (j == k) {
        for (size_t t = 0; t < k; t++) marked[s + t] = 1;
        found = true;
      }
    }
    if (found) continue;
    // 2) It started on an earlier page: the page opens with its tail.
    //    3) It continues on the next page: the page ends with its head.
    //    Require two words (or the whole page) so a single common word does not match.
    for (size_t len = std::min(k - 1, n); len >= 1; len--) {
      if (len < 2 && len < n) break;
      bool head = true;
      for (size_t j = 0; j < len && head; j++) head = eq(j, m.words[k - len + j]);
      if (head) {
        for (size_t t = 0; t < len; t++) marked[t] = 1;
        break;
      }
    }
    for (size_t len = std::min(k - 1, n); len >= 1; len--) {
      if (len < 2 && len < n) break;
      bool tail = true;
      for (size_t j = 0; j < len && tail; j++) tail = eq(n - len + j, m.words[j]);
      if (tail) {
        for (size_t t = 0; t < len; t++) marked[n - len + t] = 1;
        break;
      }
    }
  }

  const int lineHeight = renderer.getLineHeight(fontId);
  for (size_t i = 0; i < n; i++) {
    if (!marked[i]) continue;
    const WordBox& w = words[i];
    int right = w.x + renderer.getTextAdvanceX(fontId, w.text, static_cast<EpdFontFamily::Style>(w.style));
    // Bridge the gap to the next marked word on the same line.
    if (i + 1 < n && marked[i + 1] && words[i + 1].y == w.y && words[i + 1].x > right) right = words[i + 1].x;
    renderer.fillRectDither(w.x - 1, w.y, right - w.x + 2, lineHeight, LightGray);
  }
}

}  // namespace inklink::marks
