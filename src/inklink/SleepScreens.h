#pragma once

#include <string>

class GfxRenderer;

// InkLink sleep screens, drawn into the framebuffer in BW mode. Each returns
// false when it has nothing to show so the caller can fall back.
namespace inklink::sleep {

// Current book (cover, title, progress), today vs goal, streak and a heatmap.
bool renderDashboard(const GfxRenderer& renderer);

// A random saved highlight, typeset as a quote.
bool renderQuote(const GfxRenderer& renderer);

// The image "Custom" shows on every sleep instead of a random one (picked in
// FolioLink). Kept as a file name in /.crosspoint/inklink/sleep-selected.txt;
// a name whose file is gone counts as no selection.
// Full path of the selected image in `dir`, or "" for random.
std::string selectedImage(const char* dir);
// Bare file name (no '/'), or "" to go back to random. False on a bad name or write error.
bool selectImage(const char* name);
// The stored name, valid or not ("" when none).
std::string selectedName();

}  // namespace inklink::sleep
