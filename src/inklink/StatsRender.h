#pragma once

#include <cstdint>
#include <string>
#include <vector>

#include "ReadingStats.h"

#include <EpdFontFamily.h>

class GfxRenderer;

// Drawing helpers shared by the on-device statistics screen and the dashboard
// sleep screen. Pure framebuffer drawing in BW mode: intensity levels are
// dither patterns (white / light gray / dark gray / black).
namespace inklink::render {

// GitHub-style heatmap: `weeks` columns ending with the current week, rows are
// weekdays Monday..Sunday. Cell size is derived from the rect. Returns the
// height actually used.
int drawHeatmap(const GfxRenderer& renderer, int x, int y, int width, int weeks, const std::vector<DayTotal>& days,
                uint32_t today);

// Horizontal progress bar with a 1px frame.
void drawProgressBar(const GfxRenderer& renderer, int x, int y, int width, int height, int percent);

// Book cover thumbnail scaled into w x h (EPUB thumbnails are generated and
// cached on first use). Draws a framed placeholder when there is no cover.
// Returns true when a real cover was drawn.
bool drawBookCover(const GfxRenderer& renderer, const std::string& bookPath, int x, int y, int w, int h);

// Greedy word wrap into at most maxLines lines. Words wider than maxWidth are
// cut by UTF-8 code point with "…"; when text remains after the last line,
// that line ends with "…". Splits at ASCII spaces only.
std::vector<std::string> wrapText(const GfxRenderer& renderer, int fontId, const std::string& text, int maxWidth,
                                  size_t maxLines, EpdFontFamily::Style style = EpdFontFamily::REGULAR);

// "1 ч 25 мин" / "25 мин" style duration into buf (uses translated units).
void formatDuration(uint32_t secs, char* buf, size_t size);

}  // namespace inklink::render
