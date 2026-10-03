#include "SleepScreens.h"

#include <Bitmap.h>
#include <GfxRenderer.h>
#include <HalStorage.h>
#include <I18n.h>
#include <Logging.h>

#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

#include "Annotations.h"
#include "InkLinkClock.h"
#include "PrivacyLock.h"
#include "ReadingStats.h"
#include "RecentBooksStore.h"
#include "Shelves.h"
#include "StatsRender.h"
#include "fontIds.h"
#include "util/BookProgress.h"

namespace inklink::sleep {

bool renderDashboard(const GfxRenderer& renderer) {
  const int W = renderer.getScreenWidth();
  const int margin = 28;
  int y = 40;
  char buf[96];
  char dur[40];

  renderer.clearScreen();

  // Current book.
  const RecentBook* current = nullptr;
  for (const auto& rb : RECENT_BOOKS.getBooks()) {
    if (!privacy::isProtected(rb.path)) {
      current = &rb;
      break;
    }
  }
  if (current) {
    const RecentBook& book = *current;
    renderer.drawText(UI_10_FONT_ID, margin, y, tr(STR_INKLINK_NOW_READING));
    y += 26;
    constexpr int COVER_W = 150;
    constexpr int COVER_H = 225;
    render::drawBookCover(renderer, book.path, margin, y, COVER_W, COVER_H);
    const int tx = margin + COVER_W + 20;
    const int tw = W - tx - margin;
    int ty = y + 6;
    for (const auto& l : render::wrapText(renderer, UI_12_FONT_ID, book.title, tw, 3, EpdFontFamily::BOLD)) {
      renderer.drawText(UI_12_FONT_ID, tx, ty, l.c_str(), true, EpdFontFamily::BOLD);
      ty += 28;
    }
    for (const auto& l : render::wrapText(renderer, UI_10_FONT_ID, book.author, tw, 2)) {
      renderer.drawText(UI_10_FONT_ID, tx, ty, l.c_str());
      ty += 24;
    }
    const int pct = loadBookProgress(book.path);
    if (pct >= 0) {
      ty += 10;
      render::drawProgressBar(renderer, tx, ty, tw, 12, pct);
      snprintf(buf, sizeof(buf), "%d%%", pct);
      renderer.drawText(UI_10_FONT_ID, tx, ty + 20, buf);
    }
    y += COVER_H + 30;
  }

  // Today and streak.
  StatsSummary s;
  std::vector<DayTotal> days;
  if (!ReadingStats::get().summarize(s, &days) && !current) {
    // Nothing trustworthy to show: let the caller draw the default screen.
    return false;
  }
  const uint32_t goal = Shelves::dailyGoalMinutes();
  render::formatDuration(s.todaySecs, dur, sizeof(dur));
  snprintf(buf, sizeof(buf), "%s: %s / %u %s", tr(STR_INKLINK_TODAY), dur, static_cast<unsigned>(goal),
           tr(STR_INKLINK_MINUTES_SHORT));
  renderer.drawText(UI_12_FONT_ID, margin, y, buf, true, EpdFontFamily::BOLD);
  y += 30;
  render::drawProgressBar(renderer, margin, y, W - 2 * margin, 12,
                          goal ? static_cast<int>(s.todaySecs / 60 * 100 / goal) : 0);
  y += 30;
  snprintf(buf, sizeof(buf), "%s: %u %s  ·  %s: %u", tr(STR_INKLINK_STREAK), static_cast<unsigned>(s.currentStreak),
           tr(STR_INKLINK_DAYS), tr(STR_INKLINK_BOOKS_FINISHED), static_cast<unsigned>(s.booksFinished));
  renderer.drawText(UI_10_FONT_ID, margin, y, buf);
  y += 36;

  // Heatmap.
  y += render::drawHeatmap(renderer, margin, y, W - 2 * margin, 20, days, clock::today()) + 24;

  // Totals.
  render::formatDuration(s.totalSecs, dur, sizeof(dur));
  snprintf(buf, sizeof(buf), "%s: %s  ·  %u %s", tr(STR_INKLINK_TOTAL), dur, static_cast<unsigned>(s.totalPages),
           tr(STR_INKLINK_PAGES));
  renderer.drawText(UI_10_FONT_ID, margin, y, buf);

  // Footer: sleeping marker.
  renderer.drawCenteredText(UI_10_FONT_ID, renderer.getScreenHeight() - 40, tr(STR_SLEEPING));
  return true;
}

bool renderQuote(const GfxRenderer& renderer) {
  std::string text;
  std::string title;
  if (!Annotations::randomHighlight(text, title)) return false;

  const int W = renderer.getScreenWidth();
  const int H = renderer.getScreenHeight();
  const int margin = 44;
  renderer.clearScreen();

  const auto lines = render::wrapText(renderer, NOTOSERIF_16_FONT_ID, text, W - 2 * margin, 16);
  constexpr int LINE_H = 34;
  const int blockH = static_cast<int>(lines.size()) * LINE_H;
  int y = (H - blockH) / 2 - 20;
  if (y < 60) y = 60;

  // Opening quote mark and rule.
  renderer.drawText(NOTOSERIF_18_FONT_ID, margin - 8, y - 44, "«", true, EpdFontFamily::BOLD);
  for (const auto& l : lines) {
    renderer.drawText(NOTOSERIF_16_FONT_ID, margin, y, l.c_str());
    y += LINE_H;
  }
  y += 16;
  renderer.drawLine(W - margin - 120, y, W - margin, y, 2, true);
  y += 16;
  if (!title.empty()) {
    for (const auto& l : render::wrapText(renderer, UI_10_FONT_ID, title, W - 2 * margin, 2, EpdFontFamily::ITALIC)) {
      const int tw = renderer.getTextWidth(UI_10_FONT_ID, l.c_str(), EpdFontFamily::ITALIC);
      renderer.drawText(UI_10_FONT_ID, W - margin - tw, y, l.c_str(), true, EpdFontFamily::ITALIC);
      y += 24;
    }
  }
  return true;
}

}  // namespace inklink::sleep
