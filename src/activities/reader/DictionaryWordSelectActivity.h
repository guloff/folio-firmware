#pragma once

#include <Epub/Page.h>
#include <I18n.h>

#include <memory>
#include <string>
#include <vector>

#include "activities/Activity.h"
#include "util/Dictionary.h"

// InkLink: where the page comes from. In highlight mode the first Confirm /
// tap anchors the range, the second saves words[anchor..selected] as a
// highlight; in lookup mode a found word is logged to the vocabulary.
struct WordSelectBookContext {
  std::string bookPath;
  std::string title;
  std::string chapter;
  int percent = -1;
  int spine = -1;
  int page = -1;
};

// Word selection over the current reader page: Left/Right step through words
// in reading order, Up/Down jump rows, Confirm looks the word up and opens
// DictionaryDefinitionActivity, Back returns to the reader. On touch devices a
// touch-down moves the highlight and a tap on a word looks it up directly.
class DictionaryWordSelectActivity final : public Activity {
 public:
  using BookContext = WordSelectBookContext;

  explicit DictionaryWordSelectActivity(GfxRenderer& renderer, MappedInputManager& mappedInput,
                                        std::unique_ptr<Page> page, int marginLeft, int marginTop,
                                        BookContext context = {}, bool highlightMode = false)
      : Activity("DictionaryWordSelect", renderer, mappedInput),
        page(std::move(page)),
        marginLeft(marginLeft),
        marginTop(marginTop),
        context(std::move(context)),
        highlightMode(highlightMode) {}

  void onEnter() override;
  void loop() override;
  void render(RenderLock&&) override;

 private:
  // Screen box of one selectable word. `text` points into the owned Page's
  // TextBlock arena (NUL-terminated), valid for this activity's lifetime.
  struct WordBox {
    int16_t x;
    int16_t y;
    int16_t width;
    uint16_t row;
    const char* text;
    EpdFontFamily::Style style;
  };

  enum class Popup : uint8_t { None, Busy, NotFound, Error, Saved };

  void extractWords();
  int closestInRow(uint16_t row, int centerX) const;
  int wordAt(int x, int y) const;
  void moveVertical(int direction);
  void performLookup();
  bool drawHighlightWithSnapshot();
  void drawRangeHighlight();
  void saveHighlight();
  std::string contextAround(int index) const;
  void drawHints() const;

  std::unique_ptr<Page> page;
  const int marginLeft;
  const int marginTop;
  BookContext context;
  bool highlightMode = false;
  int anchor = -1;  // highlight range start, -1 until the first Confirm
  int fontId = 0;
  int lineHeight = 0;

  std::vector<WordBox> words;
  int selected = 0;
  uint16_t rowCount = 0;
  unsigned long lastHorizontalMoveTime = 0;

  Dictionary dict;
  bool dictOpenAttempted = false;
  bool dictOpenOk = false;
  bool dictNeedsIndex = false;

  Popup popup = Popup::None;
  StrId popupMsg = StrId::STR_DICT_NOT_FOUND;
  unsigned long popupTime = 0;

  // Differential highlight repaint: the pixels under the current highlight
  // box, so a cursor move restores them and repaints only the two affected
  // boxes instead of re-running the full two-pass page render (which also
  // reloads every SD-font glyph on the page). snapshotIdx is the word whose
  // under-pixels are saved; -1 means the framebuffer no longer holds a clean
  // page (popup drawn, sub-activity shown) and the next render must be full.
  static constexpr size_t SNAPSHOT_CAPACITY = 4096;
  std::unique_ptr<uint8_t[]> snapshot;
  int16_t snapshotX = 0;
  int16_t snapshotY = 0;
  int16_t snapshotW = 0;
  int16_t snapshotH = 0;
  int snapshotIdx = -1;
};
