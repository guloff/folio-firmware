#pragma once

#include <ctime>
#include <string>

// Highlights (text passages saved while reading) and the personal vocabulary
// (words looked up in the dictionary), both as JSON Lines under
// /.crosspoint/inklink/. Records are append-only on the device; edits and
// deletes from the companion app rewrite the file.
namespace inklink {

struct HighlightRecord {
  std::string book;     // book file path
  std::string title;    // book title at the time of highlighting
  std::string text;     // highlighted passage
  std::string chapter;  // chapter title, may be empty
  int percent = -1;     // book progress where it was made
  int spine = -1;       // EPUB spine index / TXT-XTC page, for jumping back
  int page = -1;
};

class Annotations {
 public:
  static constexpr const char* HIGHLIGHTS_PATH = "/.crosspoint/inklink/highlights.jsonl";
  static constexpr const char* VOCAB_PATH = "/.crosspoint/inklink/vocab.jsonl";
  // Caps keep every record well under jsonl::MAX_LINE.
  static constexpr size_t MAX_TEXT_BYTES = 4000;
  static constexpr size_t MAX_NOTE_BYTES = 2000;

  // Returns false on I/O failure. `idOut` (optional) receives the new id.
  static bool addHighlight(const HighlightRecord& rec, std::string* idOut = nullptr);
  static bool updateHighlightNote(const char* id, const char* note);
  static bool deleteHighlight(const char* id);
  // Random highlight text for the quote sleep screen; false when none exist.
  static bool randomHighlight(std::string& text, std::string& title);

  static bool addVocabWord(const char* word, const char* context, const char* book);
  static bool deleteVocabWord(const char* word);
};

}  // namespace inklink
