#include "Shelves.h"

#include <ArduinoJson.h>
#include <HalStorage.h>
#include <Logging.h>

#include <cstring>

#include "JsonLines.h"

namespace inklink {

namespace {

bool validStatus(const char* s) {
  return strcmp(s, "want") == 0 || strcmp(s, "reading") == 0 || strcmp(s, "done") == 0 || strcmp(s, "dropped") == 0;
}

enum class Load { Missing, Ok, Corrupt };

// Reads the whole document (size-checked, so it never meets a truncating reader).
Load loadDoc(JsonDocument& doc) {
  jsonl::recoverTmp(Shelves::PATH);
  if (!Storage.exists(Shelves::PATH)) return Load::Missing;
  HalFile f = Storage.open(Shelves::PATH, O_RDONLY);
  if (!f) {
    LOG_ERR("INKLINK", "library.json unreadable");
    return Load::Corrupt;
  }
  const size_t size = f.fileSize();
  if (size == 0 || size > Shelves::MAX_DOC_BYTES) {
    LOG_ERR("INKLINK", "library.json has bad size %u", static_cast<unsigned>(size));
    return Load::Corrupt;
  }
  std::string text(size, '\0');
  if (f.read(text.data(), size) != static_cast<int>(size) ||
      deserializeJson(doc, text) != DeserializationError::Ok || !doc.is<JsonObject>()) {
    LOG_ERR("INKLINK", "library.json corrupt");
    return Load::Corrupt;
  }
  return Load::Ok;
}

bool saveDoc(const JsonDocument& doc) {
  std::string out;
  serializeJson(doc, out);
  return jsonl::writeAtomic(Shelves::PATH, out);
}

}  // namespace

bool Shelves::loadShelves(std::vector<Shelf>& out) {
  out.clear();
  JsonDocument doc;
  const Load state = loadDoc(doc);
  if (state == Load::Missing) return true;  // no document yet = no shelves
  if (state == Load::Corrupt) return false;
  JsonArrayConst shelves = doc["shelves"].as<JsonArrayConst>();
  out.reserve(shelves.size());
  for (JsonObjectConst s : shelves) {
    Shelf shelf;
    shelf.id = s["id"] | "";
    shelf.name = s["name"] | "";
    JsonArrayConst books = s["books"].as<JsonArrayConst>();
    shelf.books.reserve(books.size());
    for (JsonVariantConst b : books) {
      const char* p = b | "";
      if (p[0]) shelf.books.emplace_back(p);
    }
    if (!shelf.name.empty()) out.push_back(std::move(shelf));
  }
  return true;
}

std::string Shelves::statusOf(const std::string& path) {
  JsonDocument doc;
  if (loadDoc(doc) != Load::Ok) return "";
  return doc["status"][path.c_str()] | "";
}

uint32_t Shelves::dailyGoalMinutes() {
  JsonDocument doc;
  if (loadDoc(doc) != Load::Ok) return DEFAULT_GOAL_MINUTES;
  const uint32_t goal = doc["goals"]["dailyMinutes"] | DEFAULT_GOAL_MINUTES;
  return goal > 0 && goal <= 24 * 60 ? goal : DEFAULT_GOAL_MINUTES;
}

bool Shelves::replaceDocument(const char* json, const size_t len, const char*& error) {
  if (len == 0 || len > MAX_DOC_BYTES) {
    error = "document size out of range";
    return false;
  }
  JsonDocument doc;
  if (deserializeJson(doc, json, len) != DeserializationError::Ok || !doc.is<JsonObject>()) {
    error = "invalid JSON";
    return false;
  }
  if (!doc["shelves"].isNull() && !doc["shelves"].is<JsonArray>()) {
    error = "shelves must be an array";
    return false;
  }
  for (JsonObjectConst s : doc["shelves"].as<JsonArrayConst>()) {
    if (!s["name"].is<const char*>() || !s["books"].is<JsonArrayConst>()) {
      error = "each shelf needs name and books";
      return false;
    }
  }
  for (JsonPairConst kv : doc["status"].as<JsonObjectConst>()) {
    if (!validStatus(kv.value() | "")) {
      error = "unknown status value";
      return false;
    }
  }
  doc["version"] = 1;
  if (!saveDoc(doc)) {
    error = "write failed";
    return false;
  }
  return true;
}

bool Shelves::setStatus(const std::string& path, const char* status) {
  if (status && status[0] && !validStatus(status)) return false;
  JsonDocument doc;
  const Load state = loadDoc(doc);
  if (state == Load::Corrupt) {
    // Never replace a document we couldn't read with a near-empty one.
    LOG_ERR("INKLINK", "status not saved: library.json unreadable");
    return false;
  }
  if (state == Load::Missing) doc.to<JsonObject>();
  if (!status || !status[0]) {
    doc["status"].as<JsonObject>().remove(path.c_str());
  } else {
    doc["status"][path.c_str()] = status;
  }
  doc["version"] = 1;
  return saveDoc(doc);
}

int Shelves::readDocument(std::string& json) {
  JsonDocument doc;
  switch (loadDoc(doc)) {
    case Load::Missing:
      return 0;
    case Load::Corrupt:
      return -1;
    case Load::Ok:
      break;
  }
  json.clear();
  serializeJson(doc, json);
  return 1;
}

void Shelves::autoUpdateStatus(const std::string& path, const int percent) {
  const std::string current = statusOf(path);
  const char* next = nullptr;
  if (percent >= 100) {
    if (current != "done") next = "done";
  } else if (current.empty() || current == "want") {
    next = "reading";
  }
  if (next) setStatus(path, next);
}

}  // namespace inklink
