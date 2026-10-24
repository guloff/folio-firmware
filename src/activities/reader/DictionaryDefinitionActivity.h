#pragma once

#include <Epub/Page.h>

#include <cstdint>
#include <memory>
#include <string>
#include <vector>

#include "activities/Activity.h"
#include "util/ButtonNavigator.h"

// Paged viewer for one dictionary definition. HTML definitions are laid out
// through the EPUB chapter parser into styled Pages; anything else (plain
// text, or HTML too damaged to parse) is word-wrapped once on entry and each
// page renders spans of the original string, so no per-line copies are held.
//
// Also shows a book's footnote over the page (footnote = true). On touch
// boards, where no button hints are drawn, a band at the bottom leads back
// ("« Back to text" / "« Back"); in a footnote a tap on any link — books end
// notes with their own "Back" link — or on a one-page note returns too.
class DictionaryDefinitionActivity final : public Activity {
 public:
  explicit DictionaryDefinitionActivity(GfxRenderer& renderer, MappedInputManager& mappedInput, std::string headword,
                                        std::string definition, bool htmlDefinition = false, std::string source = {},
                                        bool footnote = false)
      : Activity("DictionaryDefinition", renderer, mappedInput),
        headword(std::move(headword)),
        source(std::move(source)),
        definition(std::move(definition)),
        htmlDefinition(htmlDefinition),
        footnote(footnote) {}

  void onEnter() override;
  void onExit() override;
  void loop() override;
  void render(RenderLock&&) override;

 private:
  // One wrapped display line: a byte span of `definition`. Wrapping keeps
  // lines under the screen width, so uint16_t length is ample.
  struct Line {
    uint32_t start;
    uint16_t len;
  };

  // Usable body-text area between the header and the button hints.
  struct BodyArea {
    int width;
    int height;
  };

  BodyArea bodyArea() const;
  // Top-left corner of the body text, as render() draws it.
  void bodyOrigin(int& x, int& y) const;
  bool tapClosesFootnote(int x, int y) const;
  int backBandHeight() const;
  void drawBackBand() const;
  bool layoutHtmlPages();
  void wrapText();
  int measureSpan(int fontId, const char* text, size_t len) const;
  void drawBody(int fontId, int x, int startY) const;

  const std::string headword;
  // Dictionary the definition came from; shown beside the headword when
  // several dictionaries are searched.
  const std::string source;
  // Not const: onEnter() normalizes embedded NULs (StarDict multi-type
  // separators) to newlines so C-string APIs see the whole text.
  std::string definition;
  const bool htmlDefinition;
  const bool footnote;
  // Styled path: reader-identical Pages laid out from the HTML definition.
  // Empty means the plain-text span path below is active.
  std::vector<std::unique_ptr<Page>> pages;
  std::vector<Line> lines;
  int currentPage = 0;
  int totalPages = 1;
  int linesPerPage = 1;
  ButtonNavigator buttonNavigator;
};
