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
#include "ApiResponder.h"
#include "CrossPointSettings.h"
#include "FirmwareSignature.h"
#include "InkLinkClock.h"
#include "JsonLines.h"
#include "Pairing.h"
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

// Card size and free space, measured once per server session: the free-cluster
// count walks the whole FAT on FAT32 cards.
bool sdMeasured = false;
bool sdKnown = false;
uint64_t sdTotalBytes = 0;
uint64_t sdFreeBytes = 0;

constexpr const char* JSON = "application/json";
constexpr const char* LIBRARY_INDEX = "/.crosspoint/library.idx";
constexpr size_t STREAM_FLUSH_BYTES = 2048;

// WebServer transport: whole documents with server->send(), streams as
// chunked transfer (unknown length, empty chunk at the end).
class HttpResponder final : public Responder {
 public:
  void send(int status, const char* json, size_t) override { server->send(status, JSON, json); }
  void sendError(int status, const char* message) override {
    JsonDocument doc;
    doc["ok"] = false;
    doc["error"] = message;
    std::string out;
    serializeJson(doc, out);
    server->send(status, JSON, out.c_str());
  }
  void beginStream() override {
    server->setContentLength(CONTENT_LENGTH_UNKNOWN);
    server->send(200, JSON, "");
  }
  void write(const char* data, size_t len) override { server->sendContent(data, len); }
  void endStream() override { server->sendContent(""); }
};

HttpResponder http;

void sendError(int code, const char* message) { http.sendError(code, message); }

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

// Room kept for "]", the caller's trailer, the continuation fields and "}".
constexpr size_t TRAILER_RESERVE = 160;

// Writes a JSON document of the form {"<key>":[ ...items... ]} in chunks so a
// long history never has to exist as one string in RAM. A bounded responder
// (BLE) gets as many whole items as fit plus "more":true and "nextOffset".
class ArrayStreamer {
 public:
  // `skip` leading items are dropped; `base` is the list index of the first
  // item this stream sees (books pages), so nextOffset = base + skip + sent.
  ArrayStreamer(Responder& out, const char* key, size_t skip = 0, size_t base = 0)
      : out(out), skip(skip), next(base + skip), cap(out.capacity()) {
    out.beginStream();
    buf.reserve(STREAM_FLUSH_BYTES + 512);
    buf = "{\"";
    buf += key;
    buf += "\":[";
  }
  // False once the response is full; callers stop producing items.
  bool add(const JsonDocument& item) {
    if (full) return false;
    if (seen < skip) {
      seen++;
      return true;
    }
    // serializeJson(doc, std::string&) replaces the string, so go via scratch.
    scratch.clear();
    serializeJson(item, scratch);
    // The first item always goes in: a lone oversized item surfaces as the
    // responder's overflow error instead of an endless empty continuation.
    if (count > 0 && cap != SIZE_MAX && produced + buf.size() + 1 + scratch.size() + TRAILER_RESERVE > cap) {
      full = true;
      return false;
    }
    if (count++ > 0) buf.push_back(',');
    buf += scratch;
    next++;
    if (buf.size() >= STREAM_FLUSH_BYTES) flush();
    return true;
  }
  void finish(const char* trailer = nullptr) {
    buf += "]";
    if (trailer) buf += trailer;
    if (full) {
      char more[48];
      snprintf(more, sizeof(more), ",\"more\":true,\"nextOffset\":%u", static_cast<unsigned>(next));
      buf += more;
    }
    buf += "}";
    flush();
    out.endStream();
  }

 private:
  void flush() {
    if (buf.empty()) return;
    out.write(buf.c_str(), buf.size());
    produced += buf.size();
    buf.clear();
  }
  Responder& out;
  std::string buf;
  std::string scratch;
  size_t count = 0;
  size_t seen = 0;
  size_t skip;
  size_t next;
  size_t cap;
  size_t produced = 0;
  bool full = false;
};

// ---- info / time --------------------------------------------------------

}  // namespace

void info(Responder& out, bool authorized) {
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
  if (!sdMeasured) {
    sdMeasured = true;
    sdKnown = Storage.volumeSpace(sdTotalBytes, sdFreeBytes);
  }
  if (sdKnown) {
    doc["sdTotalMB"] = static_cast<uint32_t>(sdTotalBytes / (1024 * 1024));
    doc["sdFreeMB"] = static_cast<uint32_t>(sdFreeBytes / (1024 * 1024));
  }
  JsonObject pair = doc["pairing"].to<JsonObject>();
  pair["required"] = true;
  pair["paired"] = pairing::isPaired();
  pair["authorized"] = authorized;
  JsonArray features = doc["features"].to<JsonArray>();
  for (const char* f : {"stats", "library", "highlights", "vocab", "screenshots", "sleep", "ota", "pairing"}) {
    features.add(f);
  }
  sendDoc(out, 200, doc);
}

void setTime(Responder& out, const JsonDocument& doc) {
  const int64_t epoch = doc["epoch"] | static_cast<int64_t>(0);
  // Once the clock is known good, refuse jumps far into the future: a wrong
  // RTC would push every new session past "today" and break streaks.
  time_t current = 0;
  if (SETTINGS.clockHasBeenSynced && clock::nowUtc(current) && epoch > static_cast<int64_t>(current) + 2 * 86400) {
    return out.sendError(400, "epoch too far in the future");
  }
  const bool hasOffset = doc["tzOffsetMin"].is<int>();
  const int offset = doc["tzOffsetMin"] | 0;
  if (!clock::setFromCompanion(static_cast<time_t>(epoch), offset, hasOffset)) {
    const bool rtc = halClock.isAvailable();
    out.sendError(rtc ? 400 : 501, rtc ? "invalid epoch" : "no RTC on this device");
    return;
  }
  sendOk(out);
}

namespace {

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
  return ctx->out->add(ctx->item);
}

}  // namespace

void sessions(Responder& out, int64_t since, size_t skip) {
  if (!jsonl::readable(ReadingStats::SESSIONS_PATH)) return out.sendError(500, "sessions unreadable");
  ArrayStreamer arr(out, "sessions", skip);
  JsonDocument item;
  SessionStreamCtx ctx{&arr, since, item};
  jsonl::forEach(ReadingStats::SESSIONS_PATH, streamSession, &ctx);
  arr.finish();
}

void stats(Responder& out) {
  StatsSummary s;
  std::vector<DayTotal> days;
  if (!ReadingStats::get().summarize(s, &days)) {
    out.sendError(500, "failed to read sessions");
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
  sendDoc(out, 200, doc);
}

namespace {

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

// Parses an optional non-negative integer query argument.
bool queryUint(const char* name, size_t& out, bool& present) {
  present = server->hasArg(name);
  if (!present) return true;
  const String v = server->arg(name);
  if (v.length() == 0 || v.length() > 9) return false;
  size_t n = 0;
  for (size_t i = 0; i < v.length(); i++) {
    if (v[i] < '0' || v[i] > '9') return false;
    n = n * 10 + static_cast<size_t>(v[i] - '0');
  }
  out = n;
  return true;
}

void handleBooks() {
  // ?offset=&limit= pages the list (limit capped at 200); without them every
  // book is sent. Both forms carry "total".
  BooksPage page;
  bool hasOffset = false;
  bool hasLimit = false;
  if (!queryUint("offset", page.offset, hasOffset) || !queryUint("limit", page.limit, hasLimit)) {
    return sendError(400, "offset and limit must be non-negative integers");
  }
  page.paged = hasOffset || hasLimit;
  books(http, page);
}

}  // namespace

void books(Responder& out, const BooksPage& page) {
  const bool paged = page.paged;
  const size_t offset = page.offset;
  const size_t limit = page.limit > MAX_BOOKS_PAGE ? MAX_BOOKS_PAGE : page.limit;

  // Union of indexed books, recent books and books with reading history.
  std::map<std::string, BookRow> rows;
  {
    library::LibraryIndexFile index;
    if (index.open(LIBRARY_INDEX)) {
      library::ClixRecord rec;
      for (uint16_t i = 0; i < index.bookCount(); i++) {
        if (!index.readRecord(i, rec)) continue;
        std::string path;
        if (!index.readPath(rec, path) || path.empty()) continue;
        BookRow& row = rows[path];
        index.readTitle(rec, row.title);
        index.readAuthor(rec, row.author);
      }
    }
  }
  if (rows.empty()) {
    char name[256];
    scanBooks("/", 0, rows, name);
  }
  for (const auto& rb : RECENT_BOOKS.getBooks()) {
    BookRow& row = rows[rb.path];
    if (!rb.title.empty()) row.title = rb.title;
    if (!rb.author.empty()) row.author = rb.author;
  }
  std::vector<BookTotal> totals;
  ReadingStats::get().loadBookTotals(totals);
  std::map<std::string, const BookTotal*> totalByPath;
  for (const auto& t : totals) {
    totalByPath[t.path] = &t;
    rows[t.path];  // books read before indexing still show up
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

  // Page selection over the books still on the card, in path order.
  std::vector<const std::pair<const std::string, BookRow>*> present;
  present.reserve(rows.size());
  for (const auto& kv : rows) {
    if (Storage.exists(kv.first.c_str())) present.push_back(&kv);
  }
  const size_t total = present.size();
  size_t first = 0;
  size_t count = total;
  if (paged) {
    first = offset < total ? offset : total;
    count = limit < total - first ? limit : total - first;
  }
  char trailer[64];
  if (paged) {
    snprintf(trailer, sizeof(trailer), ",\"total\":%u,\"offset\":%u,\"limit\":%u", static_cast<unsigned>(total),
             static_cast<unsigned>(offset), static_cast<unsigned>(limit));
  } else {
    snprintf(trailer, sizeof(trailer), ",\"total\":%u", static_cast<unsigned>(total));
  }

  ArrayStreamer arr(out, "books", 0, first);
  JsonDocument item;
  for (size_t i = first; i < first + count; i++) {
    const auto& kv = *present[i];
    const std::string& path = kv.first;
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
    if (!arr.add(item)) break;
  }
  arr.finish(trailer);
}

namespace {

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
    sendDoc(http, 200, doc);
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
  sendOk(http);
}

// ---- highlights & vocabulary ---------------------------------------------

struct HighlightStreamCtx {
  ArrayStreamer* out;
  const char* book;
  int64_t since;
  JsonDocument& item;
};

bool streamHighlight(JsonObjectConst obj, void* raw) {
  auto* ctx = static_cast<HighlightStreamCtx*>(raw);
  const char* book = obj["b"] | "";
  if (ctx->book && strcmp(ctx->book, book) != 0) return true;
  const int64_t created = obj["ts"] | static_cast<int64_t>(0);
  if (ctx->since > 0 && created != 0 && created < ctx->since) return true;
  ctx->item.clear();
  ctx->item["id"] = obj["id"] | "";
  ctx->item["book"] = book;
  ctx->item["title"] = obj["t"] | "";
  ctx->item["text"] = obj["x"] | "";
  ctx->item["note"] = obj["n"] | "";
  ctx->item["chapter"] = obj["ch"] | "";
  ctx->item["pct"] = obj["c"] | -1;
  ctx->item["created"] = created;
  return ctx->out->add(ctx->item);
}

void handleHighlights() {
  String book;
  const bool filter = server->hasArg("book");
  if (filter) book = server->arg("book");
  const int64_t since = server->hasArg("since") ? atoll(server->arg("since").c_str()) : 0;
  highlights(http, filter ? book.c_str() : nullptr, since, 0);
}

}  // namespace

void highlights(Responder& out, const char* book, int64_t since, size_t skip) {
  if (!jsonl::readable(Annotations::HIGHLIGHTS_PATH)) return out.sendError(500, "highlights unreadable");
  ArrayStreamer arr(out, "highlights", skip);
  JsonDocument item;
  HighlightStreamCtx ctx{&arr, book, since, item};
  jsonl::forEach(Annotations::HIGHLIGHTS_PATH, streamHighlight, &ctx);
  arr.finish();
}

namespace {

void handleHighlightUpdate() {
  JsonDocument doc;
  if (!parseBody(doc)) return;
  const char* id = doc["id"] | "";
  if (!id[0]) return sendError(400, "missing id");
  const char* note = doc["note"] | "";
  if (strlen(note) > Annotations::MAX_NOTE_BYTES) return sendError(413, "note too long");
  if (!Annotations::updateHighlightNote(id, note)) return sendError(404, "highlight not found");
  sendOk(http);
}

void handleHighlightDelete() {
  JsonDocument doc;
  if (!parseBody(doc)) return;
  const char* id = doc["id"] | "";
  if (!id[0]) return sendError(400, "missing id");
  if (!Annotations::deleteHighlight(id)) return sendError(404, "highlight not found");
  sendOk(http);
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

}  // namespace

void vocab(Responder& out, size_t skip) {
  std::map<std::string, VocabEntry> words;
  if (!jsonl::forEach(Annotations::VOCAB_PATH, collectVocab, &words)) {
    return out.sendError(500, "vocabulary unreadable");
  }
  ArrayStreamer arr(out, "words", skip);
  JsonDocument item;
  for (const auto& kv : words) {
    item.clear();
    item["word"] = kv.first.c_str();
    item["context"] = kv.second.context.c_str();
    item["book"] = kv.second.book.c_str();
    item["created"] = kv.second.created;
    item["count"] = kv.second.count;
    if (!arr.add(item)) break;
  }
  arr.finish();
}

namespace {

void handleVocabDelete() {
  JsonDocument doc;
  if (!parseBody(doc)) return;
  const char* word = doc["word"] | "";
  if (!word[0]) return sendError(400, "missing word");
  if (!Annotations::deleteVocabWord(word)) return sendError(404, "word not found");
  sendOk(http);
}

// ---- files: screenshots, sleep images, firmware ----------------------------

// Lists regular, non-hidden files with the given extension in `dir`.
void streamDir(Responder& out, const char* key, const char* dir, const char* ext, size_t skip = 0,
               const char* trailer = nullptr) {
  ArrayStreamer arr(out, key, skip);
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
      if (!arr.add(item)) break;
    }
  }
  arr.finish(trailer);
}

void handleScreenshots() { streamDir(http, "screenshots", "/screenshots", ".bmp"); }

void handleSleep() {
  // "/.sleep" wins over "/sleep" in SleepActivity; report whichever is live.
  const char* dir = Storage.exists("/.sleep") ? "/.sleep" : "/sleep";
  Storage.ensureDirectoryExists(dir);
  char trailer[48];
  snprintf(trailer, sizeof(trailer), ",\"dir\":\"%s\",\"mode\":%u", dir, static_cast<unsigned>(SETTINGS.sleepScreen));
  streamDir(http, "images", dir, ".bmp", 0, trailer);
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
  // Companion updates must be signed with the Folio release key. The update
  // screen checks again before flashing.
  switch (fwsig::verifyFile(path)) {
    case fwsig::Status::VALID:
      break;
    case fwsig::Status::MISSING:
      return sendError(422, "signature missing");
    case fwsig::Status::INVALID:
      return sendError(422, "signature invalid");
    case fwsig::Status::ERROR:
      return sendError(500, "firmware unreadable");
  }
  pendingFirmware = path;
  JsonDocument resp;
  resp["ok"] = true;
  resp["accepted"] = true;
  resp["confirmOnDevice"] = true;
  sendDoc(http, 200, resp);
}

void handleInfo() { info(http, pairing::authorized(*server)); }

void handleSetTime() {
  JsonDocument doc;
  if (!parseBody(doc)) return;
  setTime(http, doc);
}

void handleSessions() {
  const int64_t since = server->hasArg("since") ? atoll(server->arg("since").c_str()) : 0;
  sessions(http, since, 0);
}

void handleStats() { stats(http); }

void handleVocab() { vocab(http, 0); }

// Mutating routes run only for a paired phone (X-InkLink-Token).
template <void (*Handler)()>
void authorized() {
  if (pairing::requireAuth(*server)) Handler();
}

}  // namespace

void sendDoc(Responder& out, int status, const JsonDocument& doc) {
  std::string json;
  serializeJson(doc, json);
  out.send(status, json.c_str(), json.size());
}

void sendOk(Responder& out) {
  static constexpr char OK[] = "{\"ok\":true}";
  out.send(200, OK, sizeof(OK) - 1);
}

void screenshots(Responder& out, size_t skip) { streamDir(out, "screenshots", "/screenshots", ".bmp", skip); }

void registerRoutes(WebServer& s) {
  server = &s;
  pendingFirmware.clear();
  sdMeasured = false;
  pairing::registerRoutes(s);
  s.on("/api/inklink/info", HTTP_GET, handleInfo);
  s.on("/api/inklink/time", HTTP_POST, authorized<handleSetTime>);
  s.on("/api/inklink/sessions", HTTP_GET, handleSessions);
  s.on("/api/inklink/stats", HTTP_GET, handleStats);
  s.on("/api/inklink/books", HTTP_GET, handleBooks);
  s.on("/api/inklink/library", HTTP_GET, handleGetLibrary);
  s.on("/api/inklink/library", HTTP_POST, authorized<handlePostLibrary>);
  s.on("/api/inklink/highlights", HTTP_GET, handleHighlights);
  s.on("/api/inklink/highlights/update", HTTP_POST, authorized<handleHighlightUpdate>);
  s.on("/api/inklink/highlights/delete", HTTP_POST, authorized<handleHighlightDelete>);
  s.on("/api/inklink/vocab", HTTP_GET, handleVocab);
  s.on("/api/inklink/vocab/delete", HTTP_POST, authorized<handleVocabDelete>);
  s.on("/api/inklink/screenshots", HTTP_GET, handleScreenshots);
  s.on("/api/inklink/sleep", HTTP_GET, handleSleep);
  s.on("/api/inklink/firmware/apply", HTTP_POST, authorized<handleFirmwareApply>);
  LOG_DBG("INKLINK", "companion API routes registered");
}

void clearPendingFirmware() { pendingFirmware.clear(); }

std::string takePendingFirmware() {
  std::string p;
  p.swap(pendingFirmware);
  return p;
}

}  // namespace inklink::api
