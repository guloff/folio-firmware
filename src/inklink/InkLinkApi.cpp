#include "InkLinkApi.h"

#include <ArduinoJson.h>
#include <BoardConfig.h>
#include <HalClock.h>
#include <HalGPIO.h>
#include <HalPowerManager.h>
#include <HalStorage.h>
#include <LibraryIndexFile.h>
#include <Logging.h>
#include <WebServer.h>

#include <cstring>
#include <map>
#include <set>
#include <string>
#include <vector>

#include "Annotations.h"
#include "CrossPointSettings.h"
#include "InkLinkClock.h"
#include "JsonLines.h"
#include "ReadingStats.h"
#include "RecentBooksStore.h"
#include "Shelves.h"
#include "util/BookProgress.h"

#ifndef CROSSPOINT_VERSION
#define CROSSPOINT_VERSION "dev"
#endif

namespace inklink::api {

namespace {

WebServer* server = nullptr;
std::string pendingFirmware;

constexpr const char* JSON = "application/json";
constexpr const char* LIBRARY_INDEX = "/.crosspoint/library.idx";
constexpr size_t STREAM_FLUSH_BYTES = 2048;

void sendDoc(int code, const JsonDocument& doc) {
  std::string out;
  serializeJson(doc, out);
  server->send(code, JSON, out.c_str());
}

void sendOk() { server->send(200, JSON, "{\"ok\":true}"); }

void sendError(int code, const char* message) {
  JsonDocument doc;
  doc["ok"] = false;
  doc["error"] = message;
  sendDoc(code, doc);
}

bool parseBody(JsonDocument& doc) {
  if (!server->hasArg("plain")) {
    sendError(400, "missing JSON body");
    return false;
  }
  const String body = server->arg("plain");
  if (deserializeJson(doc, body.c_str(), body.length()) != DeserializationError::Ok || !doc.is<JsonObject>()) {
    sendError(400, "invalid JSON body");
    return false;
  }
  return true;
}

// Streams a JSON document of the form {"<key>":[ ...items... ]} in chunks so a
// long history never has to exist as one string in RAM.
class ArrayStreamer {
 public:
  explicit ArrayStreamer(const char* key) {
    server->setContentLength(CONTENT_LENGTH_UNKNOWN);
    server->send(200, JSON, "");
    buf.reserve(STREAM_FLUSH_BYTES + 512);
    buf = "{\"";
    buf += key;
    buf += "\":[";
  }
  void add(const JsonDocument& item) {
    if (count++ > 0) buf.push_back(',');
    // serializeJson(doc, std::string&) replaces the string, so go via scratch.
    scratch.clear();
    serializeJson(item, scratch);
    buf += scratch;
    if (buf.size() >= STREAM_FLUSH_BYTES) flush();
  }
  void finish(const char* trailer = nullptr) {
    buf += "]";
    if (trailer) buf += trailer;
    buf += "}";
    flush();
    server->sendContent("");
  }

 private:
  void flush() {
    if (buf.empty()) return;
    server->sendContent(buf.c_str(), buf.size());
    buf.clear();
  }
  std::string buf;
  std::string scratch;
  size_t count = 0;
};

// ---- info / time --------------------------------------------------------

void handleInfo() {
  JsonDocument doc;
  doc["api"] = API_VERSION;
  doc["firmware"] = CROSSPOINT_VERSION;
#if FREEINK_DEVICE_X4 || FREEINK_DEVICE_X3
  doc["board"] = gpio.deviceIsX3() ? "X3" : "X4";
#else
  doc["board"] = BoardConfig::ACTIVE.name;
#endif
  doc["battery"] = powerManager.getBatteryPercentage();
  doc["charging"] = gpio.isUsbConnected();
  JsonObject clk = doc["clock"].to<JsonObject>();
  time_t now = 0;
  const bool valid = clock::nowUtc(now);
  clk["available"] = halClock.isAvailable();
  clk["valid"] = valid;
  clk["epoch"] = static_cast<int64_t>(valid ? now : 0);
  JsonArray features = doc["features"].to<JsonArray>();
  for (const char* f : {"stats", "library", "highlights", "vocab", "screenshots", "sleep", "ota"}) features.add(f);
  sendDoc(200, doc);
}

void handleSetTime() {
  JsonDocument doc;
  if (!parseBody(doc)) return;
  const int64_t epoch = doc["epoch"] | static_cast<int64_t>(0);
  // Once the clock is known good, refuse jumps far into the future: a wrong
  // RTC would push every new session past "today" and break streaks.
  time_t current = 0;
  if (SETTINGS.clockHasBeenSynced && clock::nowUtc(current) && epoch > static_cast<int64_t>(current) + 2 * 86400) {
    return sendError(400, "epoch too far in the future");
  }
  const bool hasOffset = doc["tzOffsetMin"].is<int>();
  const int offset = doc["tzOffsetMin"] | 0;
  if (!clock::setFromCompanion(static_cast<time_t>(epoch), offset, hasOffset)) {
    sendError(halClock.isAvailable() ? 400 : 501, halClock.isAvailable() ? "invalid epoch" : "no RTC on this device");
    return;
  }
  sendOk();
}

// ---- stats --------------------------------------------------------------

struct SessionStreamCtx {
  ArrayStreamer* out;
  int64_t since;
  JsonDocument& item;
};

bool streamSession(JsonObjectConst obj, void* raw) {
  auto* ctx = static_cast<SessionStreamCtx*>(raw);
  const int64_t start = obj["s"] | static_cast<int64_t>(0);
  // Undated sessions (start 0) are always sent: they can't be ordered by time.
  if (ctx->since > 0 && start != 0 && start < ctx->since) return true;
  char day[12];
  clock::formatDay(obj["day"] | 0u, day, sizeof(day));
  ctx->item.clear();
  ctx->item["book"] = obj["b"] | "";
  ctx->item["title"] = obj["t"] | "";
  ctx->item["start"] = start;
  ctx->item["secs"] = obj["d"] | 0u;
  ctx->item["pages"] = obj["p"] | 0u;
  ctx->item["pct"] = obj["c"] | -1;
  ctx->item["day"] = day;
  ctx->out->add(ctx->item);
  return true;
}

void handleSessions() {
  const int64_t since = server->hasArg("since") ? atoll(server->arg("since").c_str()) : 0;
  if (!jsonl::readable(ReadingStats::SESSIONS_PATH)) return sendError(500, "sessions unreadable");
  ArrayStreamer out("sessions");
  JsonDocument item;
  SessionStreamCtx ctx{&out, since, item};
  jsonl::forEach(ReadingStats::SESSIONS_PATH, streamSession, &ctx);
  out.finish();
}

void handleStats() {
  StatsSummary s;
  std::vector<DayTotal> days;
  if (!ReadingStats::get().summarize(s, &days)) {
    sendError(500, "failed to read sessions");
    return;
  }
  JsonDocument doc;
  doc["today"]["secs"] = s.todaySecs;
  doc["today"]["pages"] = s.todayPages;
  doc["goalMinutes"] = Shelves::dailyGoalMinutes();
  doc["streak"]["current"] = s.currentStreak;
  doc["streak"]["longest"] = s.longestStreak;
  doc["totals"]["secs"] = s.totalSecs;
  doc["totals"]["pages"] = s.totalPages;
  doc["totals"]["sessions"] = s.sessions;
  doc["totals"]["books"] = s.booksFinished;
  JsonArray arr = doc["days"].to<JsonArray>();
  char day[12];
  for (const auto& d : days) {
    JsonObject o = arr.add<JsonObject>();
    clock::formatDay(d.day, day, sizeof(day));
    o["d"] = day;
    o["s"] = d.secs;
    o["p"] = d.pages;
  }
  sendDoc(200, doc);
}

// ---- books & library ----------------------------------------------------

struct BookRow {
  std::string title;
  std::string author;
};

bool isBookFile(const char* name) {
  const size_t len = strlen(name);
  auto ends = [&](const char* ext) {
    const size_t e = strlen(ext);
    return len > e && strcasecmp(name + len - e, ext) == 0;
  };
  return ends(".epub") || ends(".txt") || ends(".md") || ends(".xtc") || ends(".xtch");
}

// Fallback when the library index hasn't been built yet: walk the card
// (skipping dot-folders) the same depth the index builder uses. `name` is one
// buffer shared by every recursion level.
constexpr size_t MAX_SCANNED_BOOKS = 4096;
void scanBooks(const std::string& dir, int depth, std::map<std::string, BookRow>& out, char (&name)[256]) {
  if (depth > 5 || out.size() >= MAX_SCANNED_BOOKS) return;
  HalFile root = Storage.open(dir.c_str());
  if (!root || !root.isDirectory()) return;
  for (HalFile f = root.openNextFile(); f && out.size() < MAX_SCANNED_BOOKS; f = root.openNextFile()) {
    f.getName(name, sizeof(name));
    if (name[0] == '.') continue;
    const std::string path = (dir == "/" ? "/" : dir + "/") + name;
    if (f.isDirectory()) {
      f.close();
      scanBooks(path, depth + 1, out, name);
    } else if (isBookFile(name)) {
      out[path];
    }
  }
}

// "3_children_of_dune__frank_herbert.epub" -> "3 children of dune  frank herbert"
std::string titleFromFileName(const std::string& path) {
  const size_t slash = path.find_last_of('/');
  std::string name = slash == std::string::npos ? path : path.substr(slash + 1);
  const size_t dot = name.find_last_of('.');
  if (dot != std::string::npos && dot > 0) name.resize(dot);
  for (auto& c : name) {
    if (c == '_') c = ' ';
  }
  return name;
}

void handleBooks() {
  // Union of indexed books, recent books and books with reading history.
  std::map<std::string, BookRow> books;
  {
    library::LibraryIndexFile index;
    if (index.open(LIBRARY_INDEX)) {
      library::ClixRecord rec;
      for (uint16_t i = 0; i < index.bookCount(); i++) {
        if (!index.readRecord(i, rec)) continue;
        std::string path;
        if (!index.readPath(rec, path) || path.empty()) continue;
        BookRow& row = books[path];
        index.readTitle(rec, row.title);
        index.readAuthor(rec, row.author);
      }
    }
  }
  if (books.empty()) {
    char name[256];
    scanBooks("/", 0, books, name);
  }
  for (const auto& rb : RECENT_BOOKS.getBooks()) {
    BookRow& row = books[rb.path];
    if (!rb.title.empty()) row.title = rb.title;
    if (!rb.author.empty()) row.author = rb.author;
  }
  std::vector<BookTotal> totals;
  ReadingStats::get().loadBookTotals(totals);
  std::map<std::string, const BookTotal*> totalByPath;
  for (const auto& t : totals) {
    totalByPath[t.path] = &t;
    books[t.path];  // books read before indexing still show up
  }

  JsonDocument lib;
  {
    std::string libJson;
    if (Shelves::readDocument(libJson) > 0) deserializeJson(lib, libJson);
  }
  std::map<std::string, std::vector<std::string>> shelvesByBook;
  for (JsonObjectConst s : lib["shelves"].as<JsonArrayConst>()) {
    const char* id = s["id"] | "";
    for (JsonVariantConst b : s["books"].as<JsonArrayConst>()) shelvesByBook[b | ""].emplace_back(id);
  }

  std::map<std::string, int> recentRank;
  {
    const auto& recentBooks = RECENT_BOOKS.getBooks();
    for (size_t r = 0; r < recentBooks.size(); r++) recentRank.emplace(recentBooks[r].path, static_cast<int>(r));
  }

  ArrayStreamer out("books");
  JsonDocument item;
  for (const auto& kv : books) {
    const std::string& path = kv.first;
    if (!Storage.exists(path.c_str())) continue;
    item.clear();
    item["path"] = path.c_str();
    const std::string fallbackTitle = kv.second.title.empty() ? titleFromFileName(path) : std::string();
    item["title"] = kv.second.title.empty() ? fallbackTitle.c_str() : kv.second.title.c_str();
    item["author"] = kv.second.author.c_str();
    auto t = totalByPath.find(path);
    auto rr = recentRank.find(path);
    // Reading progress costs a cache-file read per book; only books that were
    // ever opened (history or recents) can have one.
    const bool opened = t != totalByPath.end() || rr != recentRank.end();
    item["progress"] = opened ? loadBookProgress(path) : -1;
    item["lastRead"] = static_cast<int64_t>(t != totalByPath.end() ? t->second->lastRead : 0);
    item["secs"] = t != totalByPath.end() ? t->second->secs : 0u;
    item["status"] = lib["status"][path.c_str()] | "";
    // Position in the device's recent list (0 = last opened), -1 when absent.
    item["recent"] = rr != recentRank.end() ? rr->second : -1;
    JsonArray shelves = item["shelves"].to<JsonArray>();
    auto sb = shelvesByBook.find(path);
    if (sb != shelvesByBook.end()) {
      for (const auto& id : sb->second) shelves.add(id.c_str());
    }
    out.add(item);
  }
  out.finish();
}

void handleGetLibrary() {
  std::string json;
  const int state = Shelves::readDocument(json);
  if (state < 0) {
    // An unreadable document must not look empty: the app would POST it back.
    sendError(500, "library.json unreadable");
    return;
  }
  if (state == 0) {
    JsonDocument doc;
    doc["version"] = 1;
    doc["shelves"].to<JsonArray>();
    doc["status"].to<JsonObject>();
    doc["goals"]["dailyMinutes"] = Shelves::DEFAULT_GOAL_MINUTES;
    sendDoc(200, doc);
    return;
  }
  server->send(200, JSON, json.c_str());
}

void handlePostLibrary() {
  if (!server->hasArg("plain")) {
    sendError(400, "missing JSON body");
    return;
  }
  const String body = server->arg("plain");
  const char* error = nullptr;
  if (!Shelves::replaceDocument(body.c_str(), body.length(), error)) {
    sendError(400, error ? error : "rejected");
    return;
  }
  sendOk();
}

// ---- highlights & vocabulary ---------------------------------------------

struct HighlightStreamCtx {
  ArrayStreamer* out;
  const char* book;
  JsonDocument& item;
};

bool streamHighlight(JsonObjectConst obj, void* raw) {
  auto* ctx = static_cast<HighlightStreamCtx*>(raw);
  const char* book = obj["b"] | "";
  if (ctx->book && strcmp(ctx->book, book) != 0) return true;
  ctx->item.clear();
  ctx->item["id"] = obj["id"] | "";
  ctx->item["book"] = book;
  ctx->item["title"] = obj["t"] | "";
  ctx->item["text"] = obj["x"] | "";
  ctx->item["note"] = obj["n"] | "";
  ctx->item["chapter"] = obj["ch"] | "";
  ctx->item["pct"] = obj["c"] | -1;
  ctx->item["created"] = obj["ts"] | static_cast<int64_t>(0);
  ctx->out->add(ctx->item);
  return true;
}

void handleHighlights() {
  String book;
  const bool filter = server->hasArg("book");
  if (filter) book = server->arg("book");
  if (!jsonl::readable(Annotations::HIGHLIGHTS_PATH)) return sendError(500, "highlights unreadable");
  ArrayStreamer out("highlights");
  JsonDocument item;
  HighlightStreamCtx ctx{&out, filter ? book.c_str() : nullptr, item};
  jsonl::forEach(Annotations::HIGHLIGHTS_PATH, streamHighlight, &ctx);
  out.finish();
}

void handleHighlightUpdate() {
  JsonDocument doc;
  if (!parseBody(doc)) return;
  const char* id = doc["id"] | "";
  if (!id[0]) return sendError(400, "missing id");
  const char* note = doc["note"] | "";
  if (strlen(note) > Annotations::MAX_NOTE_BYTES) return sendError(413, "note too long");
  if (!Annotations::updateHighlightNote(id, note)) return sendError(404, "highlight not found");
  sendOk();
}

void handleHighlightDelete() {
  JsonDocument doc;
  if (!parseBody(doc)) return;
  const char* id = doc["id"] | "";
  if (!id[0]) return sendError(400, "missing id");
  if (!Annotations::deleteHighlight(id)) return sendError(404, "highlight not found");
  sendOk();
}

struct VocabEntry {
  std::string context;
  std::string book;
  int64_t created = 0;
  uint32_t count = 0;
};

bool collectVocab(JsonObjectConst obj, void* raw) {
  auto* words = static_cast<std::map<std::string, VocabEntry>*>(raw);
  std::string w = obj["w"] | "";
  if (w.empty()) return true;
  for (auto& c : w) c = static_cast<char>(tolower(static_cast<unsigned char>(c)));  // ASCII fold only
  VocabEntry& e = (*words)[w];
  if (e.count++ == 0) e.created = obj["ts"] | static_cast<int64_t>(0);
  // Keep the most recent non-empty context: it's the one the reader remembers.
  const char* cx = obj["cx"] | "";
  if (cx[0]) {
    e.context = cx;
    e.book = obj["b"] | "";
  }
  return true;
}

void handleVocab() {
  std::map<std::string, VocabEntry> words;
  if (!jsonl::forEach(Annotations::VOCAB_PATH, collectVocab, &words)) return sendError(500, "vocabulary unreadable");
  ArrayStreamer out("words");
  JsonDocument item;
  for (const auto& kv : words) {
    item.clear();
    item["word"] = kv.first.c_str();
    item["context"] = kv.second.context.c_str();
    item["book"] = kv.second.book.c_str();
    item["created"] = kv.second.created;
    item["count"] = kv.second.count;
    out.add(item);
  }
  out.finish();
}

void handleVocabDelete() {
  JsonDocument doc;
  if (!parseBody(doc)) return;
  const char* word = doc["word"] | "";
  if (!word[0]) return sendError(400, "missing word");
  if (!Annotations::deleteVocabWord(word)) return sendError(404, "word not found");
  sendOk();
}

// ---- files: screenshots, sleep images, firmware ----------------------------

// Lists regular, non-hidden files with the given extension in `dir`.
void streamDir(const char* key, const char* dir, const char* ext, const char* trailer = nullptr) {
  ArrayStreamer out(key);
  JsonDocument item;
  HalFile root = Storage.open(dir);
  if (root && root.isDirectory()) {
    char name[256];
    const size_t extLen = strlen(ext);
    for (HalFile f = root.openNextFile(); f; f = root.openNextFile()) {
      if (f.isDirectory()) continue;
      f.getName(name, sizeof(name));
      const size_t len = strlen(name);
      if (name[0] == '.' || len < extLen || strcasecmp(name + len - extLen, ext) != 0) continue;
      item.clear();
      item["name"] = name;
      item["path"] = (std::string(dir) + "/" + name).c_str();
      item["size"] = static_cast<uint64_t>(f.fileSize64());
      out.add(item);
    }
  }
  out.finish(trailer);
}

void handleScreenshots() { streamDir("screenshots", "/screenshots", ".bmp"); }

void handleSleep() {
  // "/.sleep" wins over "/sleep" in SleepActivity; report whichever is live.
  const char* dir = Storage.exists("/.sleep") ? "/.sleep" : "/sleep";
  Storage.ensureDirectoryExists(dir);
  char trailer[48];
  snprintf(trailer, sizeof(trailer), ",\"dir\":\"%s\",\"mode\":%u", dir, static_cast<unsigned>(SETTINGS.sleepScreen));
  streamDir("images", dir, ".bmp", trailer);
}

void handleFirmwareApply() {
  JsonDocument doc;
  if (!parseBody(doc)) return;
  const char* path = doc["path"] | "";
  const size_t len = strlen(path);
  if (len < 15 || strncmp(path, "/firmware/", 10) != 0 || strcasecmp(path + len - 4, ".bin") != 0 ||
      strstr(path, "..")) {
    return sendError(400, "path must be /firmware/<name>.bin");
  }
  if (!pendingFirmware.empty()) return sendError(409, "an update is already waiting for confirmation");
  if (!Storage.exists(path)) return sendError(404, "file not found");
  pendingFirmware = path;
  JsonDocument resp;
  resp["ok"] = true;
  resp["accepted"] = true;
  resp["confirmOnDevice"] = true;
  sendDoc(200, resp);
}

}  // namespace

void registerRoutes(WebServer& s) {
  server = &s;
  pendingFirmware.clear();
  s.on("/api/inklink/info", HTTP_GET, handleInfo);
  s.on("/api/inklink/time", HTTP_POST, handleSetTime);
  s.on("/api/inklink/sessions", HTTP_GET, handleSessions);
  s.on("/api/inklink/stats", HTTP_GET, handleStats);
  s.on("/api/inklink/books", HTTP_GET, handleBooks);
  s.on("/api/inklink/library", HTTP_GET, handleGetLibrary);
  s.on("/api/inklink/library", HTTP_POST, handlePostLibrary);
  s.on("/api/inklink/highlights", HTTP_GET, handleHighlights);
  s.on("/api/inklink/highlights/update", HTTP_POST, handleHighlightUpdate);
  s.on("/api/inklink/highlights/delete", HTTP_POST, handleHighlightDelete);
  s.on("/api/inklink/vocab", HTTP_GET, handleVocab);
  s.on("/api/inklink/vocab/delete", HTTP_POST, handleVocabDelete);
  s.on("/api/inklink/screenshots", HTTP_GET, handleScreenshots);
  s.on("/api/inklink/sleep", HTTP_GET, handleSleep);
  s.on("/api/inklink/firmware/apply", HTTP_POST, handleFirmwareApply);
  LOG_DBG("INKLINK", "companion API routes registered");
}

void clearPendingFirmware() { pendingFirmware.clear(); }

std::string takePendingFirmware() {
  std::string p;
  p.swap(pendingFirmware);
  return p;
}

}  // namespace inklink::api
