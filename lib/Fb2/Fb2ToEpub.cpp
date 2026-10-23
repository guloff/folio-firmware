#include "Fb2ToEpub.h"

#include <Memory.h>
#include <expat.h>

#include <cstdio>
#include <cstring>

namespace {

// Single-byte encodings expat lacks, upper half (0x80-0xFF) as code points.
// Russian FB2 files are often windows-1251, older ones KOI8-R.
constexpr uint16_t CP1251_HIGH[128] = {
    0x0402, 0x0403, 0x201A, 0x0453, 0x201E, 0x2026, 0x2020, 0x2021, 0x20AC, 0x2030, 0x0409, 0x2039, 0x040A,
    0x040C, 0x040B, 0x040F, 0x0452, 0x2018, 0x2019, 0x201C, 0x201D, 0x2022, 0x2013, 0x2014, 0xFFFD, 0x2122,
    0x0459, 0x203A, 0x045A, 0x045C, 0x045B, 0x045F, 0x00A0, 0x040E, 0x045E, 0x0408, 0x00A4, 0x0490, 0x00A6,
    0x00A7, 0x0401, 0x00A9, 0x0404, 0x00AB, 0x00AC, 0x00AD, 0x00AE, 0x0407, 0x00B0, 0x00B1, 0x0406, 0x0456,
    0x0491, 0x00B5, 0x00B6, 0x00B7, 0x0451, 0x2116, 0x0454, 0x00BB, 0x0458, 0x0405, 0x0455, 0x0457, 0x0410,
    0x0411, 0x0412, 0x0413, 0x0414, 0x0415, 0x0416, 0x0417, 0x0418, 0x0419, 0x041A, 0x041B, 0x041C, 0x041D,
    0x041E, 0x041F, 0x0420, 0x0421, 0x0422, 0x0423, 0x0424, 0x0425, 0x0426, 0x0427, 0x0428, 0x0429, 0x042A,
    0x042B, 0x042C, 0x042D, 0x042E, 0x042F, 0x0430, 0x0431, 0x0432, 0x0433, 0x0434, 0x0435, 0x0436, 0x0437,
    0x0438, 0x0439, 0x043A, 0x043B, 0x043C, 0x043D, 0x043E, 0x043F, 0x0440, 0x0441, 0x0442, 0x0443, 0x0444,
    0x0445, 0x0446, 0x0447, 0x0448, 0x0449, 0x044A, 0x044B, 0x044C, 0x044D, 0x044E, 0x044F};

constexpr uint16_t KOI8R_HIGH[128] = {
    0x2500, 0x2502, 0x250C, 0x2510, 0x2514, 0x2518, 0x251C, 0x2524, 0x252C, 0x2534, 0x253C, 0x2580, 0x2584,
    0x2588, 0x258C, 0x2590, 0x2591, 0x2592, 0x2593, 0x2320, 0x25A0, 0x2219, 0x221A, 0x2248, 0x2264, 0x2265,
    0x00A0, 0x2321, 0x00B0, 0x00B2, 0x00B7, 0x00F7, 0x2550, 0x2551, 0x2552, 0x0451, 0x2553, 0x2554, 0x2555,
    0x2556, 0x2557, 0x2558, 0x2559, 0x255A, 0x255B, 0x255C, 0x255D, 0x255E, 0x255F, 0x2560, 0x2561, 0x0401,
    0x2562, 0x2563, 0x2564, 0x2565, 0x2566, 0x2567, 0x2568, 0x2569, 0x256A, 0x256B, 0x256C, 0x00A9, 0x044E,
    0x0430, 0x0431, 0x0446, 0x0434, 0x0435, 0x0444, 0x0433, 0x0445, 0x0438, 0x0439, 0x043A, 0x043B, 0x043C,
    0x043D, 0x043E, 0x043F, 0x044F, 0x0440, 0x0441, 0x0442, 0x0443, 0x0436, 0x0432, 0x044C, 0x044B, 0x0437,
    0x0448, 0x044D, 0x0449, 0x0447, 0x044A, 0x042E, 0x0410, 0x0411, 0x0426, 0x0414, 0x0415, 0x0424, 0x0413,
    0x0425, 0x0418, 0x0419, 0x041A, 0x041B, 0x041C, 0x041D, 0x041E, 0x041F, 0x042F, 0x0420, 0x0421, 0x0422,
    0x0423, 0x0416, 0x0412, 0x042C, 0x042B, 0x0417, 0x0428, 0x042D, 0x0429, 0x0427, 0x042A};

// windows-1252 differs from ISO-8859-1 only in 0x80-0x9F.
constexpr uint16_t CP1252_C1[32] = {0x20AC, 0xFFFD, 0x201A, 0x0192, 0x201E, 0x2026, 0x2020, 0x2021,
                                    0x02C6, 0x2030, 0x0160, 0x2039, 0x0152, 0xFFFD, 0x017D, 0xFFFD,
                                    0xFFFD, 0x2018, 0x2019, 0x201C, 0x201D, 0x2022, 0x2013, 0x2014,
                                    0x02DC, 0x2122, 0x0161, 0x203A, 0x0153, 0xFFFD, 0x017E, 0x0178};

bool iequals(const char* a, const char* b) {
  for (; *a && *b; a++, b++) {
    char x = *a, y = *b;
    if (x >= 'A' && x <= 'Z') x += 'a' - 'A';
    if (y >= 'A' && y <= 'Z') y += 'a' - 'A';
    if (x != y) return false;
  }
  return *a == *b;
}

int XMLCALL unknownEncoding(void*, const XML_Char* name, XML_Encoding* info) {
  const uint16_t* high = nullptr;
  bool cp1252 = false;
  if (iequals(name, "windows-1251") || iequals(name, "cp1251")) {
    high = CP1251_HIGH;
  } else if (iequals(name, "koi8-r")) {
    high = KOI8R_HIGH;
  } else if (iequals(name, "windows-1252") || iequals(name, "cp1252")) {
    cp1252 = true;
  } else {
    return XML_STATUS_ERROR;
  }
  for (int i = 0; i < 128; i++) info->map[i] = i;
  for (int i = 128; i < 256; i++) {
    if (high) {
      info->map[i] = high[i - 128];
    } else {
      info->map[i] = (cp1252 && i < 0xA0) ? CP1252_C1[i - 0x80] : i;
    }
  }
  info->data = nullptr;
  info->convert = nullptr;
  info->release = nullptr;
  return XML_STATUS_OK;
}

const char* localName(const char* name) {
  const char* colon = strrchr(name, ':');
  return colon ? colon + 1 : name;
}

// Attribute by local name, so l:href, xlink:href and href all match.
const char* attribute(const XML_Char** atts, const char* name) {
  if (!atts) return nullptr;
  for (int i = 0; atts[i]; i += 2) {
    if (strcmp(localName(atts[i]), name) == 0) return atts[i + 1];
  }
  return nullptr;
}

const char* imageExtension(const char* contentType) {
  if (!contentType) return nullptr;
  if (iequals(contentType, "image/jpeg") || iequals(contentType, "image/jpg")) return "jpg";
  if (iequals(contentType, "image/png")) return "png";
  if (iequals(contentType, "image/gif")) return "gif";
  return nullptr;
}

const char* mediaType(const char* extension) {
  if (strcmp(extension, "jpg") == 0) return "image/jpeg";
  if (strcmp(extension, "png") == 0) return "image/png";
  return "image/gif";
}

void appendEscaped(std::string& out, const char* s, size_t len, bool attr = false) {
  for (size_t i = 0; i < len; i++) {
    switch (s[i]) {
      case '&':
        out += "&amp;";
        break;
      case '<':
        out += "&lt;";
        break;
      case '>':
        out += "&gt;";
        break;
      case '"':
        if (attr) {
          out += "&quot;";
          break;
        }
        [[fallthrough]];
      default:
        out += s[i];
    }
  }
}

void appendEscaped(std::string& out, const std::string& s, bool attr = false) {
  appendEscaped(out, s.data(), s.size(), attr);
}

void collapseSpaces(std::string& s) {
  std::string out;
  out.reserve(s.size());
  bool space = false;
  for (const char c : s) {
    if (c == ' ' || c == '\n' || c == '\r' || c == '\t') {
      space = !out.empty();
    } else {
      if (space) out += ' ';
      space = false;
      out += c;
    }
  }
  s.swap(out);
}

constexpr const char* XHTML_HEAD =
    "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
    "<html xmlns=\"http://www.w3.org/1999/xhtml\"><head><title></title>"
    "<link rel=\"stylesheet\" type=\"text/css\" href=\"style.css\"/></head><body>";
constexpr const char* XHTML_TAIL = "</body></html>";
constexpr const char* SECTION_OPEN = "<div class=\"section\">";

constexpr const char* STYLE_CSS =
    "p{text-indent:1.5em;margin:0}"
    "h1,h2,h3,h4,h5,h6{text-align:center;text-indent:0}"
    ".subtitle{text-align:center;font-weight:bold;text-indent:0}"
    "p.epigraph{margin-left:30%;font-style:italic;text-indent:0}"
    "p.cite{margin-left:1.5em;margin-right:1.5em}"
    "p.annotation{font-style:italic}"
    ".v{margin-left:1.5em;text-indent:0}"
    ".text-author{text-align:right;font-style:italic;text-indent:0}"
    ".date{text-align:right;text-indent:0}"
    ".empty-line{text-indent:0}"
    ".note-title{font-weight:bold;text-indent:0}"
    ".image,.cover{text-align:center;text-indent:0}";

constexpr const char* CONTAINER_XML =
    "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
    "<container version=\"1.0\" xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\"><rootfiles>"
    "<rootfile full-path=\"OEBPS/content.opf\" media-type=\"application/oebps-package+xml\"/>"
    "</rootfiles></container>";

bool writeWhole(Fb2ToEpub::Output& out, const char* name, const char* data, size_t len) {
  return out.beginFile(name) && out.write(data, len) && out.endFile();
}

bool writeWhole(Fb2ToEpub::Output& out, const char* name, const std::string& data) {
  return writeWhole(out, name, data.data(), data.size());
}

enum class Kind : uint8_t {
  Other,
  Description,
  TitleInfo,
  Author,
  CoverPage,
  Body,
  Section,
  Title,
  TitleLine,
  Wrapper,    // epigraph, cite, poem, ... : blocks splitting while open
  TextBlock,  // p, v, subtitle, td, ... : character data is content
  Inline,
  Binary,
};

enum class Capture : uint8_t { None, Title, Language, AuthorPart };

struct Frame {
  Kind kind;
  const char* close;      // markup emitted at the end, or nullptr
  const char* paraClass;  // Wrapper: class for its plain paragraphs (the reader does not inherit div styles)
};

}  // namespace

struct Fb2ToEpub::Pass {
  Fb2ToEpub& self;
  Output* out;  // nullptr: measuring pass
  bool metadataOnly = false;

  XML_Parser parser = nullptr;
  bool sawRoot = false;
  bool notRoot = false;
  bool stopped = false;
  bool xmlError = false;
  bool outputError = false;

  std::vector<Frame> stack;
  uint16_t openKinds[static_cast<int>(Kind::Binary) + 1] = {};

  // Description.
  Capture capture = Capture::None;
  std::string captured;
  std::string authorParts[4];  // first, middle, last, nickname
  int authorPart = 0;

  // Bodies.
  bool notesBody = false;
  uint8_t sectionDepth = 0;
  uint8_t sectionDivs = 0;  // section <div>s open in the output
  uint16_t sectionSerial = 0;
  int textDepth = 0;
  int titleLines = 0;
  bool tocPending = false;
  uint8_t tocDepth = 0;
  std::string tocAnchor;
  std::string tocLabel;

  // Current content file.
  bool fileOpen = false;
  uint16_t fileNo = 0;
  size_t fileText = 0;  // character data only, so both passes split alike

  // Binary being decoded.
  bool decoding = false;
  uint32_t b64 = 0;
  int b64Bits = 0;

  std::string buf;

  Pass(Fb2ToEpub& self, Output* out) : self(self), out(out) {
    stack.reserve(32);
    buf.reserve(256);
  }

  bool measuring() const { return out == nullptr; }
  bool open(Kind k) const { return openKinds[static_cast<int>(k)] > 0; }

  void writeRaw(const char* s, size_t len) {
    if (out && !outputError && !out->write(s, len)) outputError = true;
  }
  void writeRaw(const char* s) { writeRaw(s, strlen(s)); }

  void openFile() {
    fileNo = ++self.fileCount;
    fileOpen = true;
    fileText = 0;
    if (out) {
      char name[32];
      snprintf(name, sizeof(name), "OEBPS/s%u.xhtml", static_cast<unsigned>(fileNo));
      if (!out->beginFile(name)) outputError = true;
    }
    writeRaw(XHTML_HEAD);
    for (int i = 0; i < sectionDivs; i++) writeRaw(SECTION_OPEN);
  }

  void closeFile() {
    if (!fileOpen) return;
    for (int i = 0; i < sectionDivs; i++) writeRaw("</div>");
    writeRaw(XHTML_TAIL);
    if (out && !outputError && !out->endFile()) outputError = true;
    fileOpen = false;
  }

  void emit(const char* s, size_t len) {
    if (!fileOpen) openFile();
    writeRaw(s, len);
  }
  void emit(const char* s) { emit(s, strlen(s)); }
  void emit(const std::string& s) { emit(s.data(), s.size()); }

  bool canSplit() const { return fileOpen && textDepth == 0 && !open(Kind::Wrapper) && !open(Kind::Title); }

  void recordId(const char* id) {
    if (measuring() && id && *id)
      self.idFile.emplace(id, fileOpen ? fileNo : static_cast<uint16_t>(self.fileCount + 1));
  }

  // "<tag class="cls" id="id">", recording the id against the file it lands in.
  void emitOpen(const char* tag, const char* cls, const char* id) {
    buf = "<";
    buf += tag;
    if (cls) {
      buf += " class=\"";
      buf += cls;
      buf += '"';
    }
    if (id && *id) {
      buf += " id=\"";
      appendEscaped(buf, id, strlen(id), true);
      buf += '"';
    }
    buf += '>';
    if (!fileOpen) openFile();
    recordId(id);
    writeRaw(buf.data(), buf.size());
  }

  void push(Kind kind, const char* close = nullptr, const char* paraClass = nullptr) {
    stack.push_back({kind, close, paraClass});
    openKinds[static_cast<int>(kind)]++;
  }

  void startElement(const char* rawName, const XML_Char** atts) {
    const char* name = localName(rawName);
    if (!sawRoot) {
      sawRoot = true;
      if (strcmp(name, "FictionBook") != 0) {
        notRoot = true;
        XML_StopParser(parser, XML_FALSE);
        return;
      }
      push(Kind::Other);
      return;
    }

    if (open(Kind::Description)) {
      startDescription(name, atts);
      return;
    }
    if (open(Kind::Binary)) {
      push(Kind::Other);
      return;
    }
    if (stack.size() == 1) {
      if (strcmp(name, "description") == 0) {
        push(Kind::Description);
      } else if (strcmp(name, "body") == 0) {
        startBody(atts);
      } else if (strcmp(name, "binary") == 0) {
        startBinary(atts);
      } else {
        push(Kind::Other);
      }
      return;
    }
    if (open(Kind::Body)) {
      startContent(name, atts);
      return;
    }
    push(Kind::Other);
  }

  void startDescription(const char* name, const XML_Char** atts) {
    if (strcmp(name, "title-info") == 0 && !open(Kind::TitleInfo)) {
      push(Kind::TitleInfo);
      return;
    }
    if (!open(Kind::TitleInfo) || !measuring()) {
      push(Kind::Other);
      return;
    }
    if (strcmp(name, "author") == 0) {
      for (auto& part : authorParts) part.clear();
      push(Kind::Author);
    } else if (strcmp(name, "coverpage") == 0) {
      push(Kind::CoverPage);
    } else if (open(Kind::CoverPage) && strcmp(name, "image") == 0) {
      const char* href = attribute(atts, "href");
      if (href && href[0] == '#' && self.meta.coverId.empty()) self.meta.coverId = href + 1;
      push(Kind::Other);
    } else if (open(Kind::Author)) {
      static constexpr const char* PARTS[] = {"first-name", "middle-name", "last-name", "nickname"};
      authorPart = -1;
      for (int i = 0; i < 4; i++) {
        if (strcmp(name, PARTS[i]) == 0) authorPart = i;
      }
      if (authorPart >= 0) beginCapture(Capture::AuthorPart);
      push(Kind::Other);
    } else if (strcmp(name, "book-title") == 0) {
      beginCapture(Capture::Title);
      push(Kind::Other);
    } else if (strcmp(name, "lang") == 0) {
      beginCapture(Capture::Language);
      push(Kind::Other);
    } else {
      push(Kind::Other);
    }
  }

  void beginCapture(const Capture c) {
    capture = c;
    captured.clear();
  }

  void endCapture() {
    collapseSpaces(captured);
    switch (capture) {
      case Capture::Title:
        if (self.meta.title.empty()) self.meta.title = captured;
        break;
      case Capture::Language:
        if (self.meta.language.empty()) self.meta.language = captured;
        break;
      case Capture::AuthorPart:
        authorParts[authorPart] = captured;
        break;
      case Capture::None:
        break;
    }
    capture = Capture::None;
  }

  void endAuthor() {
    std::string author;
    for (int i = 0; i < 3; i++) {
      if (authorParts[i].empty()) continue;
      if (!author.empty()) author += ' ';
      author += authorParts[i];
    }
    if (author.empty()) author = authorParts[3];
    if (!author.empty()) self.meta.authors.push_back(std::move(author));
  }

  void startBody(const XML_Char** atts) {
    closeFile();
    const char* bodyName = attribute(atts, "name");
    notesBody = bodyName && *bodyName;
    sectionDepth = 0;
    sectionDivs = 0;
    textDepth = 0;
    // A notes body's title is its TOC entry; the main body's is the book title.
    tocPending = notesBody;
    tocDepth = 1;
    tocAnchor.clear();
    push(Kind::Body);
  }

  void startBinary(const XML_Char** atts) {
    closeFile();
    push(Kind::Binary);
    const char* id = attribute(atts, "id");
    if (!id || !*id) return;
    if (measuring()) {
      const Binary binary{static_cast<uint16_t>(self.binaries.size()), imageExtension(attribute(atts, "content-type"))};
      self.binaries.emplace(id, binary);
      return;
    }
    const auto it = self.binaries.find(id);
    if (it == self.binaries.end() || !it->second.extension) return;
    char name[40];
    snprintf(name, sizeof(name), "OEBPS/img%u.%s", static_cast<unsigned>(it->second.index), it->second.extension);
    if (!out->beginFile(name)) {
      outputError = true;
      return;
    }
    decoding = true;
    b64 = 0;
    b64Bits = 0;
  }

  void startSection(const char* id) {
    const size_t threshold = notesBody ? NOTES_SPLIT_BYTES : MAIN_SPLIT_BYTES;
    if (canSplit() && fileText >= threshold) closeFile();
    const bool startsFile = !fileOpen;
    char synthetic[16];
    if (!id || !*id) {
      snprintf(synthetic, sizeof(synthetic), "fb2s%u", static_cast<unsigned>(++sectionSerial));
      id = synthetic;
    }
    emitOpen("div", "section", id);
    sectionDivs++;
    sectionDepth++;
    push(Kind::Section, "</div>");
    if (!notesBody) {
      tocPending = true;
      tocDepth = sectionDepth;
      tocAnchor = startsFile ? "" : id;
    }
  }

  void startTitle(const char* id) {
    char tag[4] = "h1";
    if (notesBody && sectionDepth > 0) {
      emitOpen("p", "note-title", id);
      push(Kind::Title, "</p>");
    } else {
      // A poem's or stanza's title sits a level below its section's.
      const int level = sectionDepth + 1 + (open(Kind::Wrapper) ? 1 : 0);
      tag[1] = static_cast<char>('0' + (level > 6 ? 6 : level));
      emitOpen(tag, "title", id);
      static constexpr const char* CLOSE[] = {"</h1>", "</h2>", "</h3>", "</h4>", "</h5>", "</h6>"};
      push(Kind::Title, CLOSE[tag[1] - '1']);
    }
    titleLines = 0;
    tocLabel.clear();
  }

  void endTitle() {
    if (!tocPending) return;
    tocPending = false;
    collapseSpaces(tocLabel);
    if (!measuring() || tocLabel.empty()) return;
    if (tocLabel.size() > 200) tocLabel.resize(200);
    self.toc.push_back({fileNo, tocDepth, tocAnchor, tocLabel});
  }

  void startImage(const XML_Char** atts) {
    // Open the file whether or not the image resolves, so both passes number files alike.
    if (!fileOpen) openFile();
    push(Kind::Other);
    const char* href = attribute(atts, "href");
    const char* id = attribute(atts, "id");
    if (!href || href[0] != '#') return;
    const auto it = self.binaries.find(href + 1);
    if (measuring() || it == self.binaries.end() || !it->second.extension) {
      recordId(id);
      return;
    }
    char src[96];
    snprintf(src, sizeof(src), "<img src=\"img%u.%s\" alt=\"\"/>", static_cast<unsigned>(it->second.index),
             it->second.extension);
    if (textDepth > 0) {
      emit(src);
    } else {
      emitOpen("div", "image", id);
      emit(src);
      emit("</div>");
    }
  }

  void startLink(const XML_Char** atts) {
    if (!fileOpen) openFile();
    const char* href = attribute(atts, "href");
    if (!href || !*href) {
      push(Kind::Inline);
      return;
    }
    buf.clear();
    if (href[0] == '#') {
      const auto it = self.idFile.find(href + 1);
      if (it != self.idFile.end()) {
        char file[24];
        snprintf(file, sizeof(file), "s%u.xhtml", static_cast<unsigned>(it->second));
        buf = "<a href=\"";
        buf += file;
        appendEscaped(buf, href, strlen(href), true);
        buf += "\">";
      }
    } else {
      buf = "<a href=\"";
      appendEscaped(buf, href, strlen(href), true);
      buf += "\">";
    }
    if (buf.empty()) {
      push(Kind::Inline);
      return;
    }
    emit(buf);
    push(Kind::Inline, "</a>");
  }

  void startText(const char* tag, const char* cls, const char* id, const char* close) {
    tocPending = false;  // a section's title comes first; a later one belongs to a poem or stanza
    for (auto it = stack.rbegin(); !cls && it != stack.rend(); ++it) {
      if (it->kind == Kind::Wrapper) cls = it->paraClass;
    }
    emitOpen(tag, cls, id);
    textDepth++;
    push(Kind::TextBlock, close);
  }

  void startWrapper(const char* tag, const char* cls, const char* id, const char* close,
                    const char* paraClass = nullptr) {
    tocPending = false;
    emitOpen(tag, cls, id);
    push(Kind::Wrapper, close, paraClass);
  }

  void startInline(const char* tag, const char* close) {
    emit(tag);
    push(Kind::Inline, close);
  }

  void startContent(const char* name, const XML_Char** atts) {
    const char* id = attribute(atts, "id");
    if (open(Kind::Title)) {
      if (strcmp(name, "p") == 0) {
        if (titleLines++ > 0) {
          emit("<br/>");
          tocLabel += ' ';
        }
        recordId(id);
        textDepth++;
        push(Kind::TitleLine);
        return;
      }
      if (strcmp(name, "empty-line") == 0) {
        push(Kind::Other);
        return;
      }
    }

    if (strcmp(name, "section") == 0) {
      startSection(id);
    } else if (strcmp(name, "title") == 0) {
      startTitle(id);
    } else if (strcmp(name, "p") == 0) {
      startText("p", nullptr, id, "</p>");
    } else if (strcmp(name, "v") == 0) {
      startText("p", "v", id, "</p>");
    } else if (strcmp(name, "subtitle") == 0) {
      startText("p", "subtitle", id, "</p>");
    } else if (strcmp(name, "text-author") == 0) {
      startText("p", "text-author", id, "</p>");
    } else if (strcmp(name, "date") == 0) {
      startText("p", "date", id, "</p>");
    } else if (strcmp(name, "empty-line") == 0) {
      emit("<p class=\"empty-line\">&#160;</p>");
      push(Kind::Other);
    } else if (strcmp(name, "epigraph") == 0) {
      startWrapper("div", "epigraph", id, "</div>", "epigraph");
    } else if (strcmp(name, "cite") == 0) {
      startWrapper("blockquote", "cite", id, "</blockquote>", "cite");
    } else if (strcmp(name, "poem") == 0) {
      startWrapper("div", "poem", id, "</div>");
    } else if (strcmp(name, "stanza") == 0) {
      startWrapper("div", "stanza", id, "</div>");
    } else if (strcmp(name, "annotation") == 0) {
      startWrapper("div", "annotation", id, "</div>", "annotation");
    } else if (strcmp(name, "table") == 0) {
      startWrapper("table", nullptr, id, "</table>");
    } else if (strcmp(name, "tr") == 0) {
      startWrapper("tr", nullptr, id, "</tr>");
    } else if (strcmp(name, "td") == 0 || strcmp(name, "th") == 0) {
      startText(name, nullptr, id, name[1] == 'd' ? "</td>" : "</th>");
    } else if (strcmp(name, "image") == 0) {
      startImage(atts);
    } else if (strcmp(name, "a") == 0) {
      startLink(atts);
    } else if (strcmp(name, "emphasis") == 0) {
      startInline("<em>", "</em>");
    } else if (strcmp(name, "strong") == 0) {
      startInline("<strong>", "</strong>");
    } else if (strcmp(name, "strikethrough") == 0) {
      startInline("<s>", "</s>");
    } else if (strcmp(name, "sub") == 0) {
      startInline("<sub>", "</sub>");
    } else if (strcmp(name, "sup") == 0) {
      startInline("<sup>", "</sup>");
    } else if (strcmp(name, "code") == 0) {
      startInline("<code>", "</code>");
    } else {
      recordId(id);
      push(Kind::Other);
    }
  }

  void endElement() {
    if (stack.empty()) return;
    const Frame frame = stack.back();
    stack.pop_back();
    openKinds[static_cast<int>(frame.kind)]--;

    switch (frame.kind) {
      case Kind::Description:
        if (metadataOnly) {
          stopped = true;
          XML_StopParser(parser, XML_FALSE);
        }
        return;
      case Kind::Author:
        endAuthor();
        return;
      case Kind::Other:
        if (capture != Capture::None && open(Kind::Description)) endCapture();
        return;
      case Kind::Binary:
        endBinary();
        return;
      case Kind::Body:
        closeFile();
        sectionDivs = 0;
        return;
      case Kind::Section:
        sectionDepth--;
        sectionDivs--;
        // The file may have been split after this section's last paragraph.
        if (fileOpen) writeRaw(frame.close);
        return;
      case Kind::TitleLine:
        textDepth--;
        return;
      case Kind::Title:
        emit(frame.close);
        endTitle();
        return;
      case Kind::TextBlock:
        emit(frame.close);
        textDepth--;
        if (canSplit() && fileText >= MAX_FILE_BYTES) closeFile();
        return;
      default:
        if (frame.close) emit(frame.close);
        return;
    }
  }

  void endBinary() {
    if (!decoding) return;
    decoding = false;
    if (!outputError && !out->endFile()) outputError = true;
  }

  void decodeBase64(const char* s, const int len) {
    char chunk[128];
    size_t n = 0;
    for (int i = 0; i < len; i++) {
      const char c = s[i];
      int v;
      if (c >= 'A' && c <= 'Z') {
        v = c - 'A';
      } else if (c >= 'a' && c <= 'z') {
        v = c - 'a' + 26;
      } else if (c >= '0' && c <= '9') {
        v = c - '0' + 52;
      } else if (c == '+' || c == '-') {
        v = 62;
      } else if (c == '/' || c == '_') {
        v = 63;
      } else {
        continue;  // whitespace and '=' padding
      }
      b64 = (b64 << 6) | static_cast<uint32_t>(v);
      b64Bits += 6;
      if (b64Bits >= 8) {
        b64Bits -= 8;
        chunk[n++] = static_cast<char>((b64 >> b64Bits) & 0xFF);
        if (n == sizeof(chunk)) {
          writeRaw(chunk, n);
          n = 0;
        }
      }
    }
    if (n > 0) writeRaw(chunk, n);
  }

  void characters(const XML_Char* s, const int len) {
    if (capture != Capture::None) {
      captured.append(s, len);
      return;
    }
    if (decoding) {
      decodeBase64(s, len);
      return;
    }
    if (textDepth <= 0 || !open(Kind::Body)) return;
    buf.clear();
    appendEscaped(buf, s, static_cast<size_t>(len));
    emit(buf);
    fileText += static_cast<size_t>(len);
    if (open(Kind::Title) && tocPending) tocLabel.append(s, len);
  }

  static void XMLCALL onStart(void* ud, const XML_Char* name, const XML_Char** atts) {
    static_cast<Pass*>(ud)->startElement(name, atts);
  }
  static void XMLCALL onEnd(void* ud, const XML_Char*) { static_cast<Pass*>(ud)->endElement(); }
  static void XMLCALL onText(void* ud, const XML_Char* s, int len) { static_cast<Pass*>(ud)->characters(s, len); }

  static bool feed(void* ctx, const char* data, size_t len) {
    auto* pass = static_cast<Pass*>(ctx);
    if (pass->stopped || pass->xmlError || pass->notRoot || pass->outputError) return false;
    const XML_Status status = XML_Parse(pass->parser, data, static_cast<int>(len), XML_FALSE);
    if (status == XML_STATUS_ERROR) {
      if (!pass->stopped && !pass->notRoot) pass->xmlError = true;
      return false;
    }
    return !pass->stopped && !pass->notRoot && !pass->outputError;
  }

  // Runs the document through this pass. False only when it could not be read.
  bool run(Input& input) {
    parser = XML_ParserCreate(nullptr);
    if (!parser) return false;
    XML_SetUserData(parser, this);
    XML_SetElementHandler(parser, onStart, onEnd);
    XML_SetCharacterDataHandler(parser, onText);
    XML_SetUnknownEncodingHandler(parser, unknownEncoding, nullptr);
    const bool streamed = input.stream(feed, this);
    if (streamed && !stopped && !xmlError && !notRoot && !outputError) {
      if (XML_Parse(parser, nullptr, 0, XML_TRUE) == XML_STATUS_ERROR) xmlError = true;
    }
    XML_ParserFree(parser);
    parser = nullptr;
    // A book cut short by an XML error keeps what came before it: close
    // whatever was open so every written file is well-formed.
    if (xmlError && !metadataOnly) {
      while (!stack.empty()) endElement();
    }
    return streamed;
  }
};

bool Fb2ToEpub::readMetadata(Input& input, Metadata& out) {
  Fb2ToEpub converter;
  // Passes hold several strings and the element stack: heap, not the reader task's stack.
  auto pass = makeUniqueNoThrow<Pass>(converter, nullptr);
  if (!pass) return false;
  pass->metadataOnly = true;
  if (!pass->run(input) || pass->notRoot || !pass->sawRoot) return false;
  out = std::move(converter.meta);
  return true;
}

bool Fb2ToEpub::convert(Input& input, Output& out) {
  meta = Metadata{};
  idFile.clear();
  binaries.clear();
  toc.clear();
  cover = nullptr;

  {
    fileCount = 0;
    auto measure = makeUniqueNoThrow<Pass>(*this, nullptr);
    if (!measure || !measure->run(input) || measure->notRoot || !measure->sawRoot || fileCount == 0) return false;
  }
  const uint16_t measuredFiles = fileCount;
  if (!meta.coverId.empty()) {
    const auto it = binaries.find(meta.coverId);
    if (it != binaries.end() && it->second.extension) cover = &it->second;
  }

  if (!writeWhole(out, "mimetype", "application/epub+zip", 20) ||
      !writeWhole(out, "META-INF/container.xml", CONTAINER_XML, strlen(CONTAINER_XML)) ||
      !writeWhole(out, "OEBPS/style.css", STYLE_CSS, strlen(STYLE_CSS))) {
    return false;
  }
  if (cover) {
    std::string page = XHTML_HEAD;
    char img[64];
    snprintf(img, sizeof(img), "<div class=\"cover\"><img src=\"img%u.%s\" alt=\"\"/></div>",
             static_cast<unsigned>(cover->index), cover->extension);
    page += img;
    page += XHTML_TAIL;
    if (!writeWhole(out, "OEBPS/cover.xhtml", page)) return false;
  }

  fileCount = 0;
  auto write = makeUniqueNoThrow<Pass>(*this, &out);
  if (!write || !write->run(input) || write->outputError || fileCount != measuredFiles) return false;

  return writeOpf(out) && writeNcx(out);
}

bool Fb2ToEpub::writeOpf(Output& out) const {
  std::string opf;
  opf.reserve(1024 + fileCount * 96u + binaries.size() * 80u);
  opf +=
      "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
      "<package xmlns=\"http://www.idpf.org/2007/opf\" version=\"2.0\" unique-identifier=\"uid\">"
      "<metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:opf=\"http://www.idpf.org/2007/opf\">"
      "<dc:title>";
  appendEscaped(opf, meta.title);
  opf += "</dc:title>";
  for (const auto& author : meta.authors) {
    opf += "<dc:creator opf:role=\"aut\">";
    appendEscaped(opf, author);
    opf += "</dc:creator>";
  }
  opf += "<dc:language>";
  appendEscaped(opf, meta.language.empty() ? std::string("und") : meta.language);
  opf += "</dc:language><dc:identifier id=\"uid\">fb2</dc:identifier>";
  char line[160];
  if (cover) {
    snprintf(line, sizeof(line), "<meta name=\"cover\" content=\"img%u\"/>", static_cast<unsigned>(cover->index));
    opf += line;
  }
  opf +=
      "</metadata><manifest>"
      "<item id=\"ncx\" href=\"toc.ncx\" media-type=\"application/x-dtbncx+xml\"/>"
      "<item id=\"css\" href=\"style.css\" media-type=\"text/css\"/>";
  if (cover) opf += "<item id=\"cover\" href=\"cover.xhtml\" media-type=\"application/xhtml+xml\"/>";
  for (unsigned i = 1; i <= fileCount; i++) {
    snprintf(line, sizeof(line), "<item id=\"s%u\" href=\"s%u.xhtml\" media-type=\"application/xhtml+xml\"/>", i, i);
    opf += line;
  }
  for (const auto& entry : binaries) {
    const Binary& b = entry.second;
    if (!b.extension) continue;
    snprintf(line, sizeof(line), "<item id=\"img%u\" href=\"img%u.%s\" media-type=\"%s\"/>",
             static_cast<unsigned>(b.index), static_cast<unsigned>(b.index), b.extension, mediaType(b.extension));
    opf += line;
  }
  opf += "</manifest><spine toc=\"ncx\">";
  if (cover) opf += "<itemref idref=\"cover\"/>";
  for (unsigned i = 1; i <= fileCount; i++) {
    snprintf(line, sizeof(line), "<itemref idref=\"s%u\"/>", i);
    opf += line;
  }
  opf += "</spine></package>";
  return writeWhole(out, "OEBPS/content.opf", opf);
}

bool Fb2ToEpub::writeNcx(Output& out) const {
  std::string ncx;
  ncx.reserve(512 + toc.size() * 128u);
  ncx +=
      "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
      "<ncx xmlns=\"http://www.daisy.org/z3986/2005/ncx/\" version=\"2005-1\">"
      "<head><meta name=\"dtb:uid\" content=\"fb2\"/></head><docTitle><text>";
  appendEscaped(ncx, meta.title);
  ncx += "</text></docTitle><navMap>";

  // File 0 is the cover page, titled with the book so the status bar has a name for it.
  const std::string bookLabel = meta.title.empty() ? std::string("1") : meta.title;
  std::vector<TocEntry> entries;
  entries.reserve(toc.size() + 2);
  if (cover) entries.push_back({0, 1, "", bookLabel});
  if (toc.empty()) {
    entries.push_back({1, 1, "", bookLabel});
  } else {
    entries.insert(entries.end(), toc.begin(), toc.end());
  }
  const size_t count = entries.size();
  int openDepth = 0;
  char line[96];
  for (size_t i = 0; i < count; i++) {
    const TocEntry& e = entries[i];
    // A level with no titled parent hangs off the nearest open one.
    int depth = e.depth < 1 ? 1 : e.depth;
    if (depth > openDepth + 1) depth = openDepth + 1;
    while (openDepth >= depth) {
      ncx += "</navPoint>";
      openDepth--;
    }
    snprintf(line, sizeof(line), "<navPoint id=\"n%u\" playOrder=\"%u\"><navLabel><text>", static_cast<unsigned>(i + 1),
             static_cast<unsigned>(i + 1));
    ncx += line;
    appendEscaped(ncx, e.label);
    if (e.file == 0) {
      ncx += "</text></navLabel><content src=\"cover.xhtml";
    } else {
      snprintf(line, sizeof(line), "</text></navLabel><content src=\"s%u.xhtml", static_cast<unsigned>(e.file));
      ncx += line;
    }
    if (!e.anchor.empty()) {
      ncx += '#';
      appendEscaped(ncx, e.anchor, true);
    }
    ncx += "\"/>";
    openDepth = depth;
  }
  while (openDepth-- > 0) ncx += "</navPoint>";
  ncx += "</navMap></ncx>";
  return writeWhole(out, "OEBPS/toc.ncx", ncx);
}
