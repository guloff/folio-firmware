#pragma once

#include <cstddef>
#include <cstdint>
#include <string>
#include <unordered_map>
#include <vector>

// Transcodes a FictionBook 2 document into an EPUB 2 package, streaming.
//
// FB2 is one XML file: <description> metadata, a main <body> of nested
// <section>s, optional note bodies (<body name="notes">) and images as base64
// <binary> elements. Rather than a second reader, the book is rewritten into
// the EPUB the existing pipeline lays out, so FB2 gets the EPUB reader's TOC,
// footnote popups, highlights, dictionary and statistics as they are.
//
// The input is read twice. The first pass writes nothing but runs the same
// layout decisions, recording which file every id lands in, the TOC and the
// image types; the second writes the files, so links into later files (the
// notes) resolve without holding the book in memory. Memory is the expat
// parser plus those tables, independent of book size.
class Fb2ToEpub {
 public:
  // Receives the EPUB's files one at a time, in package order.
  class Output {
   public:
    virtual ~Output() = default;
    virtual bool beginFile(const char* name) = 0;
    virtual bool write(const char* data, size_t len) = 0;
    virtual bool endFile() = 0;
  };

  // Returns false to stop the stream early (not an error).
  using Sink = bool (*)(void* ctx, const char* data, size_t len);

  // The FB2 document, streamable from its start more than once.
  class Input {
   public:
    virtual ~Input() = default;
    virtual bool stream(Sink sink, void* ctx) = 0;
  };

  struct Metadata {
    std::string title;
    std::vector<std::string> authors;
    std::string language;
    std::string coverId;  // <binary> id from <coverpage>, without '#'
  };

  // Reads <description> only and stops after it.
  static bool readMetadata(Input& input, Metadata& out);

  // Writes the whole EPUB into `out`. False when the input is not a
  // FictionBook with content, or when reading or `out` fails. A document cut
  // short by an XML error converts up to the error.
  bool convert(Input& input, Output& out);

  const Metadata& metadata() const { return meta; }

  // A <section> starts a new file once the current one holds this much text.
  static constexpr size_t MAIN_SPLIT_BYTES = 2 * 1024;
  static constexpr size_t NOTES_SPLIT_BYTES = 32 * 1024;
  // Past this, a file is also split between top-level paragraphs.
  static constexpr size_t MAX_FILE_BYTES = 96 * 1024;

  struct Binary {
    uint16_t index;
    const char* extension;  // nullptr: not an image the reader can show
  };
  struct TocEntry {
    uint16_t file;
    uint8_t depth;
    std::string anchor;  // empty: the entry starts its file
    std::string label;
  };

 private:
  struct Pass;
  friend struct Pass;

  Metadata meta;
  std::unordered_map<std::string, uint16_t> idFile;  // element id -> content file number
  std::unordered_map<std::string, Binary> binaries;
  std::vector<TocEntry> toc;
  uint16_t fileCount = 0;
  const Binary* cover = nullptr;

  bool writeOpf(Output& out) const;
  bool writeNcx(Output& out) const;
};
