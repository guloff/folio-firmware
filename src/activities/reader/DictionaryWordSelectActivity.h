#pragma once

#include <Epub/Page.h>
#include <I18n.h>

#include <climits>
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
// touch-down moves the highlight and a tap on a word looks it up directly
// (a tap between words picks the nearest word on that line). In highlight
// mode a tap is the touch Confirm (first tap anchors, second saves), and a
// drag selects from the word under the finger's start to the one under it
// now. startX/startY (a reader long-press) anchor the word there on entry.
class DictionaryWordSelectActivity final : public Activity {
 public:
  using BookContext = WordSelectBookContext;

  explicit DictionaryWordSelectActivity(GfxRenderer& renderer, MappedInputManager& mappedInput,
                                        std::unique_ptr<Page> page, int marginLeft, int marginTop,
                                        BookContext context = {}, bool highlightMode = false,
                                        int startX = -1, int startY = -1)
      : Activity("DictionaryWordSelect", renderer, mappedInput),
        page(std::move(page)),
        marginLeft(marginLeft),
        marginTop(marginTop),
        context(std::move(context)),
        highlightMode(highlightMode),
        startX(startX),
        startY(startY) {}

  void onEnter() override;
  void loop() override;
  void render(RenderLock&&) override;
  // A highlight drag must not double as the system edge swipes (Back from the
  // left quarter, Home from the bottom, light panel from the top).
  bool suppressesEdgeGestures() const override { return highlightMode; }

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
  int nearestWord(int x, int y, int maxDx = INT_MAX) const;
  bool handleDrag();
  void setRangeEnd(int index);
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
  const int startX;
  const int startY;
  // Drag selection (highlight mode): contact start and whether it has moved
  // past DRAG_SLOP. A drag's release is swallowed so it cannot also tap-save.
  bool touchTracking = false;
  bool dragging = false;
  int16_t touchStartX = 0;
  int16_t touchStartY = 0;
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
