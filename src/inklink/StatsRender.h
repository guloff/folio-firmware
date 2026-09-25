#pragma once

#include <cstdint>
#include <vector>

#include "ReadingStats.h"

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

// "1 ч 25 мин" / "25 мин" style duration into buf (uses translated units).
void formatDuration(uint32_t secs, char* buf, size_t size);

}  // namespace inklink::render
