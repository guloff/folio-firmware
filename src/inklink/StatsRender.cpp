#include "StatsRender.h"

#include <Bitmap.h>
#include <Epub.h>
#include <FsHelpers.h>
#include <GfxRenderer.h>
#include <HalStorage.h>
#include <Memory.h>
#include <I18n.h>

#include <algorithm>
#include <cstdio>

#include "InkLinkClock.h"

namespace inklink::render {

namespace {

// Minutes thresholds for the 4 filled intensity levels.
constexpr uint32_t LEVEL_MINUTES[] = {1, 15, 30, 60};

int levelFor(uint32_t secs) {
  const uint32_t minutes = secs / 60;
  int level = 0;
  for (int i = 0; i < 4; i++) {
    if (minutes >= LEVEL_MINUTES[i]) level = i + 1;
  }
  return level;
}

void drawCell(const GfxRenderer& r, int x, int y, int size, int level) {
  switch (level) {
    case 0:
      r.drawRect(x, y, size, size, true);
      break;
    case 1:
      r.fillRectDither(x, y, size, size, Color::LightGray);
      r.drawRect(x, y, size, size, true);
      break;
    case 2:
      r.fillRectDither(x, y, size, size, Color::DarkGray);
      break;
    case 3:
      // Black with a light core: distinct from the solid top level.
      r.fillRect(x, y, size, size, true);
      if (size >= 5) r.fillRectDither(x + 2, y + 2, size - 4, size - 4, Color::LightGray);
      break;
    default:
      r.fillRect(x, y, size, size, true);
      break;
  }
}

// ISO weekday 0=Monday..6=Sunday for a days-since-epoch ordinal (1970-01-01 was a Thursday).
int weekdayMon0(int32_t ordinal) { return static_cast<int>(((ordinal % 7) + 7 + 3) % 7); }

}  // namespace

int drawHeatmap(const GfxRenderer& renderer, const int x, const int y, const int width, const int weeks,
                const std::vector<DayTotal>& days, const uint32_t today) {
  if (weeks <= 0 || width <= 0) return 0;
  const int pitch = width / weeks;
  const int gap = std::max(1, pitch / 6);
  const int cell = std::max(2, pitch - gap);

  if (today == 0) {
    // Clock not set: draw an empty grid so the layout stays stable.
    for (int w = 0; w < weeks; w++)
      for (int d = 0; d < 7; d++) drawCell(renderer, x + w * pitch, y + d * pitch, cell, 0);
    return pitch * 7;
  }

  const int32_t todayOrd = clock::dayToOrdinal(today);
  const int todayRow = weekdayMon0(todayOrd);
  // First cell = Monday of the oldest displayed week.
  const int32_t firstOrd = todayOrd - todayRow - (weeks - 1) * 7;

  // days is sorted ascending; walk it alongside the grid.
  auto it = std::lower_bound(days.begin(), days.end(), clock::ordinalToDay(firstOrd),
                             [](const DayTotal& d, uint32_t v) { return d.day < v; });
  for (int w = 0; w < weeks; w++) {
    for (int d = 0; d < 7; d++) {
      const int32_t ord = firstOrd + w * 7 + d;
      if (ord > todayOrd) break;
      const uint32_t day = clock::ordinalToDay(ord);
      uint32_t secs = 0;
      while (it != days.end() && it->day < day) ++it;
      if (it != days.end() && it->day == day) secs = it->secs;
      drawCell(renderer, x + w * pitch, y + d * pitch, cell, levelFor(secs));
    }
  }
  // Mark today with an outer frame.
  const int tx = x + (weeks - 1) * pitch - 1;
  const int ty = y + todayRow * pitch - 1;
  renderer.drawRect(tx, ty, cell + 2, cell + 2, true);
  return pitch * 7;
}

void drawProgressBar(const GfxRenderer& renderer, const int x, const int y, const int width, const int height,
                     int percent) {
  percent = std::clamp(percent, 0, 100);
  renderer.drawRect(x, y, width, height, true);
  const int fill = (width - 4) * percent / 100;
  if (fill > 0) renderer.fillRect(x + 2, y + 2, fill, height - 4, true);
}

bool drawBookCover(const GfxRenderer& renderer, const std::string& bookPath, const int x, const int y, const int w,
                   const int h) {
  bool drawn = false;
  if (FsHelpers::hasEpubExtension(bookPath)) {
    // Large parser object: heap, one at a time.
    auto epub = makeUniqueNoThrow<Epub>(bookPath, "/.crosspoint");
    if (epub) {
      const std::string thumb = epub->getThumbBmpPath(h);
      bool ready = Storage.exists(thumb.c_str());
      if (!ready) {
        epub->load(false, true);
        ready = epub->generateThumbBmp(h);
      }
      HalFile file;
      if (ready && Storage.openFileForRead("INKLINK", thumb, file)) {
        Bitmap bitmap(file);
        if (bitmap.parseHeaders() == BmpReaderError::Ok) drawn = renderer.drawBitmap(bitmap, x, y, w, h);
      }
    }
  }
  if (!drawn) {
    renderer.fillRectDither(x, y, w, h, Color::LightGray);
    renderer.drawRect(x, y, w, h, true);
  }
  return drawn;
}

namespace {

// Drops the last UTF-8 code point.
void popCodePoint(std::string& s) {
  while (!s.empty()) {
    const unsigned char c = static_cast<unsigned char>(s.back());
    s.pop_back();
    if ((c & 0xC0) != 0x80) break;
  }
}

std::string fitWithEllipsis(const GfxRenderer& r, int fontId, std::string s, int maxWidth,
                            EpdFontFamily::Style style) {
  while (!s.empty() && r.getTextWidth(fontId, (s + "…").c_str(), style) > maxWidth) popCodePoint(s);
  return s + "…";
}

}  // namespace

std::vector<std::string> wrapText(const GfxRenderer& r, const int fontId, const std::string& text, const int maxWidth,
                                  const size_t maxLines, const EpdFontFamily::Style style) {
  std::vector<std::string> lines;
  if (maxLines == 0) return lines;
  lines.reserve(maxLines);
  std::string line;
  size_t pos = 0;
  bool truncated = false;
  while (pos < text.size()) {
    size_t next = text.find(' ', pos);
    if (next == std::string::npos) next = text.size();
    std::string word = text.substr(pos, next - pos);
    pos = next + 1;
    if (word.empty()) continue;
    if (r.getTextWidth(fontId, word.c_str(), style) > maxWidth) word = fitWithEllipsis(r, fontId, word, maxWidth, style);
    const std::string candidate = line.empty() ? word : line + " " + word;
    if (line.empty() || r.getTextWidth(fontId, candidate.c_str(), style) <= maxWidth) {
      line = candidate;
      continue;
    }
    if (lines.size() + 1 == maxLines) {
      truncated = true;
      break;
    }
    lines.push_back(line);
    line = word;
  }
  if (!line.empty()) {
    if (truncated) line = fitWithEllipsis(r, fontId, line, maxWidth, style);
    lines.push_back(line);
  }
  return lines;
}

void formatDuration(const uint32_t secs, char* buf, const size_t size) {
  const uint32_t minutes = secs / 60;
  if (minutes >= 60) {
    snprintf(buf, size, "%u %s %u %s", static_cast<unsigned>(minutes / 60), tr(STR_INKLINK_HOURS_SHORT),
             static_cast<unsigned>(minutes % 60), tr(STR_INKLINK_MINUTES_SHORT));
  } else {
    snprintf(buf, size, "%u %s", static_cast<unsigned>(minutes), tr(STR_INKLINK_MINUTES_SHORT));
  }
}

}  // namespace inklink::render
