#pragma once

#include <cstddef>
#include <string>

// Pulls the note a footnote link points at out of a chapter's XHTML, so the
// reader can show it in place instead of jumping there.
//
// The element carrying id="<anchor>" is taken when it is a block (p, li,
// aside, div, ...) or an inline wrapper around blocks (<span id><p>…</p></span>);
// an inline or empty anchor (<a id="n1"/>, <span id>) is widened to its
// nearest block ancestor. Fails (returns false) when the anchor
// is missing, the markup is too broken to find the element's end, or the
// result is longer than maxBytes — a "note" that large is a chapter, and the
// caller jumps there instead.
namespace footnote {

constexpr size_t DEFAULT_MAX_BYTES = 8 * 1024;

enum class Result {
  Found,
  Missing,   // no such id in this file
  NotANote,  // a heading, page marker or chapter-sized element: jump there
  SelfLink,  // the id sits on the link itself (broken book): look in other files
};

Result extract(const char* xhtml, size_t size, const std::string& anchor, std::string& out,
               size_t maxBytes = DEFAULT_MAX_BYTES);

// extract() == Result::Found.
bool extractById(const char* xhtml, size_t size, const std::string& anchor, std::string& out,
                 size_t maxBytes = DEFAULT_MAX_BYTES);

}  // namespace footnote
