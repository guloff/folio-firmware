#include "FootnoteExtract.h"

#include <strings.h>

#include <cstring>
#include <vector>

namespace footnote {

namespace {

constexpr size_t NPOS = static_cast<size_t>(-1);

struct Tag {
  size_t start;  // index of '<'
  size_t end;    // index after '>'
  const char* name;
  size_t nameLen;
};

bool nameIs(const Tag& t, const char* name) {
  return t.nameLen == strlen(name) && strncasecmp(t.name, name, t.nameLen) == 0;
}

bool nameIsAny(const Tag& t, const char* const* names) {
  for (; *names; names++) {
    if (nameIs(t, *names)) return true;
  }
  return false;
}

constexpr const char* VOID_TAGS[] = {"br",   "hr",   "img", "meta",   "link",  "input", "col",
                                     "area", "base", "wbr", "source", "param", "embed", nullptr};
// Elements that hold a whole note. Headings are left out on purpose: a link to
// a heading is a cross-reference, not a footnote.
constexpr const char* BLOCK_TAGS[] = {"p",  "li",    "aside", "div", "section", "dd",       "dt",   "blockquote",
                                      "td", "table", "ol",    "ul",  "dl",      "footnote", "note", nullptr};

size_t nameLength(const char* s, size_t n, size_t pos) {
  size_t i = pos;
  while (i < n) {
    const char c = s[i];
    const bool ok =
        (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == ':' || c == '-' || c == '_';
    if (!ok) break;
    i++;
  }
  return i - pos;
}

// Index after the '>' closing the tag that starts at `pos`, honoring quotes.
size_t tagEnd(const char* s, size_t n, size_t pos) {
  char quote = 0;
  for (size_t i = pos + 1; i < n; i++) {
    const char c = s[i];
    if (quote) {
      if (c == quote) quote = 0;
    } else if (c == '"' || c == '\'') {
      quote = c;
    } else if (c == '>') {
      return i + 1;
    }
  }
  return NPOS;
}

// Skips comments, CDATA, doctype and processing instructions starting at
// `pos`; returns the index after them, or `pos` when there is none.
size_t skipSpecial(const char* s, size_t n, size_t pos) {
  if (pos + 3 < n && strncmp(s + pos, "<!--", 4) == 0) {
    const char* end = static_cast<const char*>(memmem(s + pos + 4, n - pos - 4, "-->", 3));
    return end ? static_cast<size_t>(end - s) + 3 : n;
  }
  if (pos + 8 < n && strncmp(s + pos, "<![CDATA[", 9) == 0) {
    const char* end = static_cast<const char*>(memmem(s + pos + 9, n - pos - 9, "]]>", 3));
    return end ? static_cast<size_t>(end - s) + 3 : n;
  }
  if (pos + 1 < n && (s[pos + 1] == '!' || s[pos + 1] == '?')) {
    const size_t end = tagEnd(s, n, pos);
    return end == NPOS ? n : end;
  }
  return pos;
}

bool selfClosing(const char* s, const Tag& t) { return t.end >= 2 && s[t.end - 2] == '/'; }

// First position at or after `from` of id="anchor" / name="anchor" (either
// quote style) that starts an attribute, or NPOS.
size_t findAnchorAttr(const char* s, size_t n, const std::string& anchor, size_t from0 = 0) {
  size_t best = NPOS;
  const char* attrs[] = {"id=", "name="};
  const char quotes[] = {'"', '\''};
  for (const char* attr : attrs) {
    for (const char q : quotes) {
      std::string needle = attr;
      needle += q;
      needle += anchor;
      needle += q;
      size_t from = from0;
      while (from < n) {
        const char* hit = static_cast<const char*>(memmem(s + from, n - from, needle.data(), needle.size()));
        if (!hit) break;
        const size_t at = static_cast<size_t>(hit - s);
        if (at > 0 && (s[at - 1] == ' ' || s[at - 1] == '\t' || s[at - 1] == '\n' || s[at - 1] == '\r')) {
          if (at < best) best = at;
          break;
        }
        from = at + 1;
      }
    }
  }
  return best;
}

// Index after the close tag matching `open`, or NPOS (unclosed, or longer
// than `limit` bytes from its start).
size_t elementEnd(const char* s, const size_t n, const Tag& open, const size_t limit) {
  int depth = 1;
  size_t i = open.end;
  while (i < n) {
    const char* lt = static_cast<const char*>(memchr(s + i, '<', n - i));
    if (!lt) return NPOS;
    const size_t pos = static_cast<size_t>(lt - s);
    const size_t skipped = skipSpecial(s, n, pos);
    if (skipped != pos) {
      i = skipped;
      continue;
    }
    const size_t end = tagEnd(s, n, pos);
    if (end == NPOS) return NPOS;
    const bool closing = s[pos + 1] == '/';
    const size_t len = nameLength(s, n, pos + (closing ? 2 : 1));
    if (len == open.nameLen && strncasecmp(s + pos + (closing ? 2 : 1), open.name, len) == 0) {
      if (closing) {
        depth--;
      } else if (s[end - 2] != '/') {
        depth++;
      }
    }
    i = end;
    if (depth == 0) return end;
    if (i - open.start > limit) return NPOS;
  }
  return NPOS;
}

bool tagHas(const char* s, const Tag& t, const char* needle) {
  return memmem(s + t.start, t.end - t.start, needle, strlen(needle)) != nullptr;
}

// href="#anchor" on the tag itself. A backlink to the same id in another file
// (href="text.html#n1" on the note's own anchor) is a normal note, not this.
bool linksToItself(const char* s, const Tag& t, const std::string& anchor) {
  for (const char q : {'"', '\''}) {
    std::string needle = "href=";
    needle += q;
    needle += '#';
    needle += anchor;
    needle += q;
    if (memmem(s + t.start, t.end - t.start, needle.data(), needle.size())) return true;
  }
  return false;
}

// Block-level start tag anywhere in s[from, to).
bool containsBlock(const char* s, size_t from, const size_t to) {
  while (from < to) {
    const char* lt = static_cast<const char*>(memchr(s + from, '<', to - from));
    if (!lt) return false;
    const size_t pos = static_cast<size_t>(lt - s);
    const size_t len = nameLength(s, to, pos + 1);
    if (len > 0) {
      const Tag t{pos, pos + 1 + len, s + pos + 1, len};
      if (nameIsAny(t, BLOCK_TAGS)) return true;
    }
    from = pos + 1;
  }
  return false;
}

}  // namespace

Result extract(const char* xhtml, const size_t size, const std::string& anchor, std::string& out,
               const size_t maxBytes) {
  out.clear();
  if (!xhtml || size == 0 || anchor.empty()) return Result::Missing;
  const char* s = xhtml;
  const size_t n = size;

  size_t anchorPos = findAnchorAttr(s, n, anchor);
  if (anchorPos == NPOS) return Result::Missing;

  // Walk the tags before the anchor, keeping the open-element stack.
  std::vector<Tag> stack;
  stack.reserve(32);
  Tag target{NPOS, 0, nullptr, 0};
  size_t i = 0;
  while (i < n) {
    const char* lt = static_cast<const char*>(memchr(s + i, '<', n - i));
    if (!lt) break;
    const size_t pos = static_cast<size_t>(lt - s);
    if (pos > anchorPos) break;
    const size_t skipped = skipSpecial(s, n, pos);
    if (skipped != pos) {
      // An id inside a comment or CDATA is not markup: look past it.
      if (anchorPos < skipped) {
        anchorPos = findAnchorAttr(s, n, anchor, skipped);
        if (anchorPos == NPOS) return Result::Missing;
      }
      i = skipped;
      continue;
    }
    const size_t end = tagEnd(s, n, pos);
    if (end == NPOS) return Result::Missing;
    if (s[pos + 1] == '/') {
      const size_t len = nameLength(s, n, pos + 2);
      for (size_t k = stack.size(); k-- > 0;) {
        if (stack[k].nameLen == len && strncasecmp(stack[k].name, s + pos + 2, len) == 0) {
          stack.resize(k);
          break;
        }
      }
    } else {
      const size_t len = nameLength(s, n, pos + 1);
      const Tag open{pos, end, s + pos + 1, len};
      if (anchorPos < end) {
        target = open;
        break;
      }
      if (len > 0 && !nameIsAny(open, VOID_TAGS) && !selfClosing(s, open)) stack.push_back(open);
    }
    i = end;
  }
  if (target.start == NPOS) return Result::Missing;

  // Page-list targets and cross-references are places, not notes.
  if (tagHas(s, target, "pagebreak")) return Result::NotANote;
  // A noteref whose id is also its own target (broken books): the note is elsewhere.
  if (nameIs(target, "a") && linksToItself(s, target, anchor)) return Result::SelfLink;

  Tag chosen{NPOS, 0, nullptr, 0};
  const bool closed = !selfClosing(s, target) && !nameIsAny(target, VOID_TAGS);
  if (closed && nameIsAny(target, BLOCK_TAGS)) {
    chosen = target;
  } else if (closed) {
    // <span id="n5"><div>5</div><p>Note…</p></span>: an inline wrapper around blocks is the note.
    const size_t end = elementEnd(s, n, target, maxBytes);
    if (end != NPOS && containsBlock(s, target.end, end)) chosen = target;
  }
  if (chosen.start == NPOS) {
    for (size_t k = stack.size(); k-- > 0;) {
      if (nameIsAny(stack[k], BLOCK_TAGS)) {
        chosen = stack[k];
        break;
      }
    }
  }
  if (chosen.start == NPOS) return Result::NotANote;

  const size_t end = elementEnd(s, n, chosen, maxBytes);
  if (end == NPOS || end - chosen.start > maxBytes) return Result::NotANote;
  out.assign(s + chosen.start, end - chosen.start);
  return Result::Found;
}

bool extractById(const char* xhtml, const size_t size, const std::string& anchor, std::string& out,
                 const size_t maxBytes) {
  return extract(xhtml, size, anchor, out, maxBytes) == Result::Found;
}

}  // namespace footnote
