#include "FolioHomeActivity.h"

#include <Bitmap.h>
#include <Epub.h>
#include <FsHelpers.h>
#include <BoardConfig.h>
#include <GfxRenderer.h>
#include <HalClock.h>
#include <HalPowerManager.h>
#include <HalStorage.h>
#include <I18n.h>
#include <Memory.h>

#include <algorithm>
#include <cstdio>

#include "CrossPointSettings.h"
#include "InkLinkActivities.h"
#include "MappedInputManager.h"
#include "activities/ActivityManager.h"
#include "components/UITheme.h"
#include "components/icons/book.h"
#include "components/icons/library.h"
#include "components/icons/settings2.h"
#include "fontIds.h"
#include "inklink/InkLinkClock.h"
#include "inklink/Shelves.h"
#include "inklink/StatsRender.h"
#include "util/BookProgress.h"

namespace {

constexpr int MARGIN = 20;
constexpr int HEADER_H = 44;
constexpr int CARD_Y = 56;
constexpr int CARD_H = 300;
constexpr int COVER_W = 170;
constexpr int COVER_H = 255;
constexpr int STRIP_LABEL_Y = CARD_Y + CARD_H + 18;
constexpr int STRIP_Y = STRIP_LABEL_Y + 28;
constexpr int STRIP_COVER_W = 110;
constexpr int STRIP_COVER_H = 165;
constexpr int NAV_H = 86;

// Greedy word wrap (splits at ASCII spaces only, safe for UTF-8).
int drawWrapped(const GfxRenderer& r, int fontId, const std::string& text, int x, int y, int maxWidth, int lineH,
                int maxLines, EpdFontFamily::Style style) {
  std::string line;
  int lines = 0;
  size_t pos = 0;
  while (pos <= text.size() && lines < maxLines) {
    size_t next = text.find(' ', pos);
    if (next == std::string::npos) next = text.size();
    const std::string word = text.substr(pos, next - pos);
    pos = next + 1;
    const std::string candidate = line.empty() ? word : line + " " + word;
    if (!line.empty() && r.getTextWidth(fontId, candidate.c_str(), style) > maxWidth) {
      const bool last = lines == maxLines - 1;
      r.drawText(fontId, x, y + lines * lineH, last ? (line + "…").c_str() : line.c_str(), true, style);
      lines++;
      line = word;
      if (last) return lines;
    } else {
      line = candidate;
    }
    if (next == text.size()) break;
  }
  if (!line.empty() && lines < maxLines) {
    r.drawText(fontId, x, y + lines * lineH, line.c_str(), true, style);
    lines++;
  }
  return lines;
}

}  // namespace

void FolioHomeActivity::onEnter() {
  Activity::onEnter();
  recent.clear();
  const auto& books = RECENT_BOOKS.getBooks();
  recent.reserve(std::min<size_t>(books.size(), MAX_RECENT_STRIP + 1));
  for (const auto& b : books) {
    if (RecentBooksStore::isMissing(b)) continue;
    recent.push_back(b);
    if (recent.size() > MAX_RECENT_STRIP) break;  // 1 on the card + strip
  }
  currentPercent = recent.empty() ? -1 : loadBookProgress(recent.front().path);
  // Personal pace: time spent in this book so far per percent read. Needs a
  // few percent and ten minutes of history to be more than noise.
  secsLeftEstimate = 0;
  if (!recent.empty() && currentPercent >= 3 && currentPercent < 100) {
    std::vector<inklink::BookTotal> totals;
    if (inklink::ReadingStats::get().loadBookTotals(totals)) {
      for (const auto& t : totals) {
        if (t.path == recent.front().path && t.secs >= 600) {
          secsLeftEstimate = static_cast<uint32_t>(static_cast<uint64_t>(t.secs) * (100 - currentPercent) /
                                                   static_cast<uint32_t>(currentPercent));
          break;
        }
      }
    }
  }
  inklink::ReadingStats::get().summarize(summary, &days);
  goalMinutes = inklink::Shelves::dailyGoalMinutes();
  layoutTargets();
  focus = 0;
  focusVisible = !BoardConfig::hasTouch();
  requestUpdate();
}

void FolioHomeActivity::layoutTargets() {
  targets.clear();
  targets.reserve(1 + MAX_RECENT_STRIP + NAV_COUNT);
  const int W = renderer.getScreenWidth();
  const int H = renderer.getScreenHeight();
  targets.push_back({MARGIN, CARD_Y, W - 2 * MARGIN, CARD_H, CONTINUE, 0});
  const int stripCount = std::min<int>(MAX_RECENT_STRIP, std::max<int>(0, static_cast<int>(recent.size()) - 1));
  const int gap = (W - 2 * MARGIN - MAX_RECENT_STRIP * STRIP_COVER_W) / (MAX_RECENT_STRIP - 1);
  for (int i = 0; i < stripCount; i++) {
    targets.push_back({MARGIN + i * (STRIP_COVER_W + gap), STRIP_Y, STRIP_COVER_W, STRIP_COVER_H, RECENT, i + 1});
  }
  const int slotW = W / NAV_COUNT;
  for (int i = 0; i < NAV_COUNT; i++) {
    targets.push_back({i * slotW, H - NAV_H, slotW, NAV_H, NAV, i});
  }
}

void FolioHomeActivity::activate(const Target& t) {
  switch (t.kind) {
    case CONTINUE:
      if (!recent.empty()) {
        activityManager.goToReader(recent.front().path);
      } else {
        activityManager.goToLibrary();
      }
      return;
    case RECENT:
      if (t.index < static_cast<int>(recent.size())) activityManager.goToReader(recent[t.index].path);
      return;
    case NAV:
      switch (t.index) {
        case 0:
          activityManager.goToLibrary();
          return;
        case 1:
          activityManager.pushActivity(makeUniqueNoThrow<ShelvesActivity>(renderer, mappedInput));
          return;
        case 2:
          activityManager.pushActivity(makeUniqueNoThrow<ReadingStatsActivity>(renderer, mappedInput));
          return;
        default:
          activityManager.pushActivity(makeUniqueNoThrow<InkLinkHubActivity>(renderer, mappedInput));
          return;
      }
    default:
      return;
  }
}

void FolioHomeActivity::loop() {
  int x = 0;
  int y = 0;
  if (mappedInput.wasScreenTapped(x, y)) {
    for (const auto& t : targets) {
      if (x >= t.x && x < t.x + t.w && y >= t.y && y < t.y + t.h) {
        activate(t);
        return;
      }
    }
    return;
  }
  const int n = static_cast<int>(targets.size());
  if (n == 0) return;
  if (mappedInput.wasReleased(MappedInputManager::Button::Confirm)) {
    activate(targets[focus]);
    return;
  }
  const bool next = mappedInput.wasReleased(MappedInputManager::Button::Right) ||
                    mappedInput.wasReleased(MappedInputManager::Button::Down) ||
                    mappedInput.wasReleased(MappedInputManager::Button::PageForward);
  const bool prev = mappedInput.wasReleased(MappedInputManager::Button::Left) ||
                    mappedInput.wasReleased(MappedInputManager::Button::Up) ||
                    mappedInput.wasReleased(MappedInputManager::Button::PageBack);
  if (next || prev) {
    if (focusVisible) focus = (focus + (next ? 1 : n - 1)) % n;
    focusVisible = true;  // the first press only reveals the ring
    requestUpdate();
  }
}

void FolioHomeActivity::drawBookCover(const std::string& bookPath, const int x, const int y, const int w,
                                      const int h) const {
  std::string thumb;
  if (FsHelpers::hasEpubExtension(bookPath)) {
    // Large parser object: heap, one at a time.
    auto epub = makeUniqueNoThrow<Epub>(bookPath, "/.crosspoint");
    if (epub) {
      thumb = epub->getThumbBmpPath(h);
      if (!Storage.exists(thumb.c_str())) {
        epub->load(false, true);
        if (!epub->generateThumbBmp(h)) thumb.clear();
      }
    }
  }
  drawCover(thumb, x, y, w, h);
}

void FolioHomeActivity::drawCover(const std::string& path, const int x, const int y, const int w, const int h) const {
  bool drawn = false;
  if (!path.empty()) {
    HalFile file;
    if (Storage.openFileForRead("FOLIO", path, file)) {
      Bitmap bitmap(file);
      if (bitmap.parseHeaders() == BmpReaderError::Ok) drawn = renderer.drawBitmap(bitmap, x, y, w, h);
    }
  }
  if (!drawn) {
    // Placeholder: a framed "book" with the Folio mark.
    renderer.fillRectDither(x, y, w, h, Color::LightGray);
    renderer.drawIcon(BookIcon, x + (w - 32) / 2, y + (h - 32) / 2, 32);
    renderer.drawRect(x, y, w, h, true);
  }
}

void FolioHomeActivity::render(RenderLock&&) {
  renderer.clearScreen();
  const int W = renderer.getScreenWidth();
  const int H = renderer.getScreenHeight();
  char buf[96];
  char dur[40];

  // Header: brand, clock, battery.
  renderer.drawText(UI_12_FONT_ID, MARGIN, 12, tr(STR_CROSSPOINT), true, EpdFontFamily::BOLD);
  snprintf(buf, sizeof(buf), "%u%%", static_cast<unsigned>(powerManager.getBatteryPercentage()));
  int rightX = W - MARGIN - renderer.getTextWidth(UI_10_FONT_ID, buf);
  renderer.drawText(UI_10_FONT_ID, rightX, 16, buf);
  char clockBuf[12];
  if (halClock.formatTime(clockBuf, sizeof(clockBuf), SETTINGS.clockFormat == 1)) {
    rightX -= renderer.getTextWidth(UI_10_FONT_ID, clockBuf) + 16;
    renderer.drawText(UI_10_FONT_ID, rightX, 16, clockBuf);
  }
  renderer.drawLine(MARGIN, HEADER_H, W - MARGIN, HEADER_H, true);

  // Continue-reading card.
  const int textX = MARGIN + COVER_W + 18;
  const int textW = W - MARGIN - textX;
  if (!recent.empty()) {
    const RecentBook& book = recent.front();
    drawBookCover(book.path, MARGIN, CARD_Y + 10, COVER_W, COVER_H);
    int ty = CARD_Y + 12;
    renderer.drawText(UI_10_FONT_ID, textX, ty, tr(STR_CONTINUE_READING));
    ty += 26;
    ty += drawWrapped(renderer, UI_12_FONT_ID, book.title, textX, ty, textW, 28, 3, EpdFontFamily::BOLD) * 28 + 4;
    ty += drawWrapped(renderer, UI_10_FONT_ID, book.author, textX, ty, textW, 24, 2, EpdFontFamily::REGULAR) * 24 + 12;
    if (currentPercent >= 0) {
      inklink::render::drawProgressBar(renderer, textX, ty, textW, 12, currentPercent);
      snprintf(buf, sizeof(buf), "%d%%", currentPercent);
      renderer.drawText(UI_10_FONT_ID, textX, ty + 18, buf);
      if (secsLeftEstimate > 0) {
        char left[40];
        inklink::render::formatDuration(secsLeftEstimate, left, sizeof(left));
        char line[64];
        snprintf(line, sizeof(line), tr(STR_FOLIO_TIME_LEFT), left);
        renderer.drawText(UI_10_FONT_ID, textX + 56, ty + 18, line);
      }
      ty += 48;
    }
  } else {
    renderer.drawRect(MARGIN, CARD_Y + 10, COVER_W, COVER_H, true);
    renderer.drawIcon(LibraryIcon, MARGIN + (COVER_W - 32) / 2, CARD_Y + 10 + (COVER_H - 32) / 2, 32);
    drawWrapped(renderer, UI_12_FONT_ID, tr(STR_FOLIO_PICK_BOOK), textX, CARD_Y + 40, textW, 28, 4,
                EpdFontFamily::BOLD);
  }
  // Today vs goal and streak at the card's foot.
  const int footY = CARD_Y + CARD_H - 74;
  inklink::render::formatDuration(summary.todaySecs, dur, sizeof(dur));
  snprintf(buf, sizeof(buf), "%s %s / %u %s", tr(STR_INKLINK_TODAY), dur, static_cast<unsigned>(goalMinutes),
           tr(STR_INKLINK_MINUTES_SHORT));
  renderer.drawText(UI_10_FONT_ID, textX, footY, buf, true, EpdFontFamily::BOLD);
  inklink::render::drawProgressBar(renderer, textX, footY + 24, textW, 8,
                                   goalMinutes ? static_cast<int>(summary.todaySecs / 60 * 100 / goalMinutes) : 0);
  snprintf(buf, sizeof(buf), "%s: %u %s", tr(STR_INKLINK_STREAK), static_cast<unsigned>(summary.currentStreak),
           tr(STR_INKLINK_DAYS));
  renderer.drawText(UI_10_FONT_ID, textX, footY + 38, buf);

  // Recent strip.
  if (recent.size() > 1) {
    renderer.drawText(UI_10_FONT_ID, MARGIN, STRIP_LABEL_Y, tr(STR_FOLIO_RECENT), true, EpdFontFamily::BOLD);
    for (const auto& t : targets) {
      if (t.kind != RECENT) continue;
      drawBookCover(recent[t.index].path, t.x, t.y, t.w, t.h);
      const int pct = loadBookProgress(recent[t.index].path);
      if (pct >= 0) inklink::render::drawProgressBar(renderer, t.x, t.y + t.h + 6, t.w, 8, pct);
    }
  }

  // Mini reading calendar between the covers and the navigation bar.
  {
    constexpr int PITCH = 11;
    const int areaTop = recent.size() > 1 ? STRIP_Y + STRIP_COVER_H + 26 : STRIP_LABEL_Y;
    const int labelY = areaTop;
    const int gridY = labelY + 24;
    const int weeks = (W - 2 * MARGIN) / PITCH;
    if (gridY + 7 * PITCH <= H - NAV_H - 8) {
      renderer.drawText(UI_10_FONT_ID, MARGIN, labelY, tr(STR_INKLINK_LAST_WEEKS), true, EpdFontFamily::BOLD);
      inklink::render::drawHeatmap(renderer, MARGIN, gridY, weeks * PITCH, weeks, days, inklink::clock::today());
    }
  }

  // Bottom navigation bar.
  static constexpr const uint8_t* NAV_ICONS[NAV_COUNT] = {LibraryIcon, BookIcon, BookIcon, Settings2Icon};
  const char* navLabels[NAV_COUNT] = {tr(STR_LIBRARY), tr(STR_INKLINK_SHELVES), tr(STR_FOLIO_STATS_SHORT),
                                      tr(STR_FOLIO_MENU)};
  renderer.drawLine(0, H - NAV_H, W - 1, H - NAV_H, true);
  for (const auto& t : targets) {
    if (t.kind != NAV) continue;
    const int cx = t.x + t.w / 2;
    renderer.drawIcon(NAV_ICONS[t.index], cx - 16, t.y + 12, 32);
    const int lw = renderer.getTextWidth(SMALL_FONT_ID, navLabels[t.index]);
    renderer.drawText(SMALL_FONT_ID, cx - lw / 2, t.y + 52, navLabels[t.index]);
  }
  // Stats slot gets a tiny bar-chart glyph instead of a second book icon.
  if (targets.size() >= NAV_COUNT) {
    const Target& s = targets[targets.size() - 2];
    renderer.fillRect(s.x + s.w / 2 - 16, s.y + 12, 32, 32, false);
    const int bx = s.x + s.w / 2 - 14;
    const int base = s.y + 42;
    const int heights[4] = {10, 22, 16, 28};
    for (int i = 0; i < 4; i++) renderer.fillRect(bx + i * 8, base - heights[i], 5, heights[i], true);
  }

  // Focus ring for button navigation (touch users never see it move).
  if (focusVisible && focus >= 0 && focus < static_cast<int>(targets.size())) {
    const Target& f = targets[focus];
    renderer.drawRoundedRect(f.x + 2, f.y + 2, f.w - 4, f.h - 4, 3, 8, true);
  }

  renderer.displayBuffer();
}
