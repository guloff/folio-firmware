#pragma once

class GfxRenderer;

// InkLink sleep screens, drawn into the framebuffer in BW mode. Each returns
// false when it has nothing to show so the caller can fall back.
namespace inklink::sleep {

// Current book (cover, title, progress), today vs goal, streak and a heatmap.
bool renderDashboard(const GfxRenderer& renderer);

// A random saved highlight, typeset as a quote.
bool renderQuote(const GfxRenderer& renderer);

}  // namespace inklink::sleep
