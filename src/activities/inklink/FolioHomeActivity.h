#pragma once

#include <string>
#include <vector>

#include "RecentBooksStore.h"
#include "activities/Activity.h"
#include "inklink/ReadingStats.h"

// Folio "pocket library" home screen: a large continue-reading card (cover,
// title, progress, today's minutes against the goal, streak), a strip of
// recent covers and a bottom bar (Library / Shelves / Stats / Menu).
// Touch-first; physical buttons move a focus ring through the same targets.
class FolioHomeActivity final : public Activity {
 public:
  FolioHomeActivity(GfxRenderer& renderer, MappedInputManager& mappedInput)
      : Activity("FolioHome", renderer, mappedInput) {}

  void onEnter() override;
  void loop() override;
  void render(RenderLock&&) override;
  bool isHomeActivity() const override { return true; }

 private:
  struct Target {
    int x, y, w, h;
    int kind;   // Kind below
    int index;  // recent book index / nav slot
  };
  enum Kind { CONTINUE = 0, RECENT = 1, NAV = 2 };
  static constexpr int NAV_COUNT = 4;
  static constexpr int MAX_RECENT_STRIP = 3;

  std::vector<RecentBook> recent;
  inklink::StatsSummary summary;
  uint32_t goalMinutes = 0;
  int currentPercent = -1;
  std::vector<int> recentPercents;  // parallel to `recent`
  uint32_t secsLeftEstimate = 0;  // 0 = not enough history for an estimate
  std::vector<Target> targets;
  int focus = 0;
  // Touch boards hide the focus ring until a physical button moves it.
  bool focusVisible = false;
  std::vector<inklink::DayTotal> days;

  void layoutTargets();
  void activate(const Target& t);
  // Draws the book's cover thumbnail at height h, generating it on first use.
  void drawBookCover(const std::string& bookPath, int x, int y, int w, int h) const;
};
