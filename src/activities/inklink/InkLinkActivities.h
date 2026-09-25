#pragma once

#include <GfxRenderer.h>

#include <string>
#include <vector>

#include "activities/Activity.h"
#include "activities/UiListActivity.h"
#include "inklink/ReadingStats.h"
#include "inklink/Shelves.h"

class MappedInputManager;

// Home-menu entry for the InkLink features: phone sync, reading statistics,
// bookshelves and saved highlights.
class InkLinkHubActivity final : public UiListActivity {
 public:
  InkLinkHubActivity(GfxRenderer& renderer, MappedInputManager& mappedInput);
  void onEnter() override;

 private:
  static constexpr int ROW_COUNT = 7;
  freeink::ui::ListItem rows[ROW_COUNT]{};

  int listCount() const override { return ROW_COUNT; }
  const char* headerTitle() const override;
  void buildScreen(UiScreen& screen) override;
  void activateIndex(int index) override;
};

// Today / streak / totals plus a heatmap of the last weeks.
class ReadingStatsActivity final : public Activity {
 public:
  ReadingStatsActivity(GfxRenderer& renderer, MappedInputManager& mappedInput)
      : Activity("ReadingStats", renderer, mappedInput) {}
  void onEnter() override;
  void loop() override;
  void render(RenderLock&&) override;

 private:
  inklink::StatsSummary summary;
  std::vector<inklink::DayTotal> days;
  uint32_t goalMinutes = 0;
  bool loaded = false;
};

// Shelves from the companion app; selecting one lists its books, selecting a
// book opens it.
class ShelvesActivity final : public UiListActivity {
 public:
  ShelvesActivity(GfxRenderer& renderer, MappedInputManager& mappedInput);
  void onEnter() override;

 private:
  std::vector<inklink::Shelf> shelves;
  int openShelf = -1;  // -1 = shelf list
  std::vector<std::string> bookTitles;
  std::vector<freeink::ui::ListItem> rowItems;
  std::vector<std::string> rowValues;  // backing storage for row value text
  freeink::ui::ListItem placeholder{};

  void rebuildRows();
  int listCount() const override;
  const char* headerTitle() const override;
  void buildScreen(UiScreen& screen) override;
  void activateIndex(int index) override;
  void onBackButton() override;
};

// Read-only list of saved highlights, newest first.
class HighlightsActivity final : public UiListActivity {
 public:
  HighlightsActivity(GfxRenderer& renderer, MappedInputManager& mappedInput);
  void onEnter() override;

 private:
  static constexpr size_t MAX_ITEMS = 200;
  std::vector<std::string> texts;
  std::vector<std::string> titles;
  std::vector<std::string> books;
  std::vector<freeink::ui::ListItem> rowItems;
  freeink::ui::ListItem placeholder{};

  int listCount() const override { return static_cast<int>(rowItems.size()); }
  const char* headerTitle() const override;
  void buildScreen(UiScreen& screen) override;
  // Opens the highlighted book (at its saved reading position).
  void activateIndex(int index) override;
};
