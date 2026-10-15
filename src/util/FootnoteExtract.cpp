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

}  // namespace

bool extractById(const char* xhtml, const size_t size, const std::string& anchor, std::string& out,
                 const size_t maxBytes) {
  out.clear();
  if (!xhtml || size == 0 || anchor.empty()) return false;
  const char* s = xhtml;
  const size_t n = size;

  const size_t attrPos = findAnchorAttr(s, n, anchor);
  if (attrPos == NPOS) return false;

  // Walk the tags before the anchor, keeping the open-element stack.
  std::vector<Tag> stack;
  stack.reserve(32);
  Tag target{NPOS, 0, nullptr, 0};
  size_t i = 0;
  size_t anchorPos = attrPos;
  while (i < n) {
    const char* lt = static_cast<const char*>(memchr(s + i, '<', n - i));
    if (!lt) break;
    size_t pos = static_cast<size_t>(lt - s);
    if (pos > anchorPos) break;
    const size_t skipped = skipSpecial(s, n, pos);
    if (skipped != pos) {
      // An id inside a comment or CDATA is not markup: look past it.
      if (anchorPos < skipped) {
        anchorPos = findAnchorAttr(s, n, anchor, skipped);
        if (anchorPos == NPOS) return false;
      }
      i = skipped;
      continue;
    }
    const size_t end = tagEnd(s, n, pos);
    if (end == NPOS) return false;
    if (s[pos + 1] == '/') {
      const size_t len = nameLength(s, n, pos + 2);
      Tag closing{pos, end, s + pos + 2, len};
      for (size_t k = stack.size(); k-- > 0;) {
        if (stack[k].nameLen == closing.nameLen && strncasecmp(stack[k].name, closing.name, len) == 0) {
          stack.resize(k);
          break;
        }
      }
    } else {
      const size_t len = nameLength(s, n, pos + 1);
      Tag open{pos, end, s + pos + 1, len};
      if (anchorPos < end) {
        target = open;
        break;
      }
      if (len > 0 && !nameIsAny(open, VOID_TAGS) && !selfClosing(s, open)) stack.push_back(open);
    }
    i = end;
  }
  if (target.start == NPOS) return false;

  Tag chosen{NPOS, 0, nullptr, 0};
  if (nameIsAny(target, BLOCK_TAGS) && !selfClosing(s, target)) {
    chosen = target;
  } else {
    for (size_t k = stack.size(); k-- > 0;) {
      if (nameIsAny(stack[k], BLOCK_TAGS)) {
        chosen = stack[k];
        break;
      }
    }
  }
  if (chosen.start == NPOS) return false;

  // Find the matching close tag of the chosen element.
  int depth = 1;
  i = chosen.end;
  while (i < n && depth > 0) {
    const char* lt = static_cast<const char*>(memchr(s + i, '<', n - i));
    if (!lt) return false;
    const size_t pos = static_cast<size_t>(lt - s);
    const size_t skipped = skipSpecial(s, n, pos);
    if (skipped != pos) {
      i = skipped;
      continue;
    }
    const size_t end = tagEnd(s, n, pos);
    if (end == NPOS) return false;
    const bool closing = s[pos + 1] == '/';
    const size_t len = nameLength(s, n, pos + (closing ? 2 : 1));
    if (len == chosen.nameLen && strncasecmp(s + pos + (closing ? 2 : 1), chosen.name, len) == 0) {
      if (closing) {
        depth--;
      } else if (s[end - 2] != '/') {
        depth++;
      }
    }
    i = end;
    if (depth == 0) {
      const size_t length = end - chosen.start;
      if (length > maxBytes) return false;
      out.assign(s + chosen.start, length);
      return true;
    }
    if (i - chosen.start > maxBytes) return false;
  }
  return false;
}

}  // namespace footnote
