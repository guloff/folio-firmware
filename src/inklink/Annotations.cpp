#include "Annotations.h"

#include <Arduino.h>
#include <ArduinoJson.h>
#include <Logging.h>
#include <esp_random.h>

#include <cstdio>
#include <cstring>
#include <string>

#include "InkLinkClock.h"
#include "JsonLines.h"

namespace inklink {

namespace {

uint32_t highlightsGeneration = 0;

time_t nowOrZero() {
  time_t t = 0;
  clock::nowUtc(t);
  return t;
}

// Longest prefix of s with at most maxBytes bytes that ends on a UTF-8 boundary.
std::string truncateUtf8(const std::string& s, size_t maxBytes) {
  if (s.size() <= maxBytes) return s;
  size_t cut = maxBytes;
  while (cut > 0 && (static_cast<unsigned char>(s[cut]) & 0xC0) == 0x80) cut--;
  return s.substr(0, cut) + "…";
}

void makeId(char* buf, size_t size) {
  snprintf(buf, size, "h%lx%04lx", static_cast<unsigned long>(nowOrZero()),
           static_cast<unsigned long>(esp_random() & 0xFFFF));
}

struct IdCtx {
  const char* id;
  const char* note;  // nullptr = delete
  bool found;
};

bool rewriteHighlight(JsonObjectConst obj, JsonDocument& out, void* raw) {
  auto* ctx = static_cast<IdCtx*>(raw);
  if (strcmp(obj["id"] | "", ctx->id) != 0) return true;
  ctx->found = true;
  if (!ctx->note) return false;
  out["n"] = ctx->note;
  return true;
}

struct WordCtx {
  const char* word;
  bool found;
};

bool dropWord(JsonObjectConst obj, JsonDocument&, void* raw) {
  auto* ctx = static_cast<WordCtx*>(raw);
  if (strcasecmp(obj["w"] | "", ctx->word) != 0) return true;
  ctx->found = true;
  return false;
}

}  // namespace

bool Annotations::addHighlight(const HighlightRecord& rec, std::string* idOut) {
  char id[24];
  makeId(id, sizeof(id));
  JsonDocument doc;
  doc["id"] = id;
  doc["b"] = rec.book.c_str();
  doc["t"] = rec.title.c_str();
  const std::string text = truncateUtf8(rec.text, MAX_TEXT_BYTES);
  doc["x"] = text.c_str();
  doc["n"] = "";
  doc["ch"] = rec.chapter.c_str();
  doc["c"] = rec.percent;
  doc["sp"] = rec.spine;
  doc["pg"] = rec.page;
  doc["ts"] = static_cast<int64_t>(nowOrZero());
  if (!jsonl::append(HIGHLIGHTS_PATH, doc)) return false;
  highlightsGeneration++;
  if (idOut) *idOut = id;
  LOG_INF("INKLINK", "highlight saved (%u chars)", static_cast<unsigned>(rec.text.size()));
  return true;
}

bool Annotations::updateHighlightNote(const char* id, const char* note) {
  const std::string capped = truncateUtf8(note ? note : "", MAX_NOTE_BYTES);
  IdCtx ctx{id, capped.c_str(), false};
  const bool ok = jsonl::rewrite(HIGHLIGHTS_PATH, rewriteHighlight, &ctx) && ctx.found;
  if (ok) highlightsGeneration++;
  return ok;
}

bool Annotations::deleteHighlight(const char* id) {
  IdCtx ctx{id, nullptr, false};
  const bool ok = jsonl::rewrite(HIGHLIGHTS_PATH, rewriteHighlight, &ctx) && ctx.found;
  if (ok) highlightsGeneration++;
  return ok;
}

uint32_t Annotations::generation() { return highlightsGeneration; }

namespace {
struct PickCtx {
  uint32_t seen;
  std::string* text;
  std::string* title;
};

// Reservoir sampling: one pass, uniform pick, O(1) memory.
bool pickRandom(JsonObjectConst obj, void* raw) {
  auto* ctx = static_cast<PickCtx*>(raw);
  const char* text = obj["x"] | "";
  if (!text[0]) return true;
  ctx->seen++;
  if (esp_random() % ctx->seen == 0) {
    *ctx->text = text;
    *ctx->title = obj["t"] | "";
  }
  return true;
}
}  // namespace

bool Annotations::randomHighlight(std::string& text, std::string& title) {
  PickCtx ctx{0, &text, &title};
  if (!jsonl::forEach(HIGHLIGHTS_PATH, pickRandom, &ctx)) return false;
  return ctx.seen > 0;
}

bool Annotations::addVocabWord(const char* word, const char* context, const char* book) {
  if (!word || !word[0]) return false;
  JsonDocument doc;
  doc["w"] = word;
  doc["cx"] = context ? context : "";
  doc["b"] = book ? book : "";
  doc["ts"] = static_cast<int64_t>(nowOrZero());
  return jsonl::append(VOCAB_PATH, doc);
}

bool Annotations::deleteVocabWord(const char* word) {
  WordCtx ctx{word, false};
  return jsonl::rewrite(VOCAB_PATH, dropWord, &ctx) && ctx.found;
}

}  // namespace inklink
