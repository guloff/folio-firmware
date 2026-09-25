#pragma once

#include <cstdint>
#include <string>
#include <vector>

// Bookshelves, reading statuses and goals, edited mainly from the companion
// app and stored as one JSON document at /.crosspoint/inklink/library.json:
//   {"version":1,"shelves":[{"id","name","books":[path...]}],
//    "status":{path:"want|reading|done|dropped"},"goals":{"dailyMinutes":30}}
namespace inklink {

struct Shelf {
  std::string id;
  std::string name;
  std::vector<std::string> books;
};

class Shelves {
 public:
  static constexpr const char* PATH = "/.crosspoint/inklink/library.json";
  static constexpr size_t MAX_DOC_BYTES = 48 * 1024;
  static constexpr uint32_t DEFAULT_GOAL_MINUTES = 30;

  // False when the document exists but can't be read (callers show an error).
  static bool loadShelves(std::vector<Shelf>& out);
  // Raw document for the API: 0 = missing, 1 = ok, -1 = unreadable.
  static int readDocument(std::string& json);
  // Status string for a book ("" when unset).
  static std::string statusOf(const std::string& path);
  static uint32_t dailyGoalMinutes();

  // Validates and stores a full document from the companion app. Returns false
  // with a short reason in `error` when the document is malformed.
  static bool replaceDocument(const char* json, size_t len, const char*& error);
  // Sets one book's status; refuses to write over an unreadable document.
  static bool setStatus(const std::string& path, const char* status);
  // After a reading session: finished books become "done", opened ones become
  // "reading" unless the user already filed them as done/dropped.
  static void autoUpdateStatus(const std::string& path, int percent);
};

}  // namespace inklink
