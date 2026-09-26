#pragma once

#include <cstdint>
#include <string>
#include <vector>

class GfxRenderer;
class Page;

// Saved highlights drawn on reader pages: a light-gray (dithered) background
// behind the highlighted words, like a marker. Highlights are found on a page
// by their words (the same tokens the selection screen joins into the saved
// text), so they survive re-pagination after font or margin changes; one that
// spans a page break is marked on both pages.
namespace inklink::marks {

// A token the selection screen treats as a word (not bare punctuation).
bool isHighlightToken(const char* text);

// Loads the book's highlights when the book or the highlights file changed.
// Cheap when nothing changed; runs on the main loop only.
void refresh(const std::string& bookPath);

// Draws the marks for `spine` onto the B/W frame before the page text is
// rendered (text drawn afterwards stays crisp black over the dither).
void drawPage(GfxRenderer& renderer, const Page& page, int spine, int fontId, int marginLeft, int marginTop);

// Frees the cache (reader exit).
void clear();

}  // namespace inklink::marks
