#include "Shelves.h"

#include <ArduinoJson.h>
#include <HalStorage.h>
#include <Logging.h>
#include <PersistableStore.h>

#include <cstring>

namespace inklink {

namespace {

bool validStatus(const char* s) {
  return strcmp(s, "want") == 0 || strcmp(s, "reading") == 0 || strcmp(s, "done") == 0 || strcmp(s, "dropped") == 0;
}

bool loadDoc(JsonDocument& doc) { return PersistableStoreBase::readDocFromFile(Shelves::PATH, doc); }

}  // namespace

bool Shelves::loadShelves(std::vector<Shelf>& out) {
  out.clear();
  JsonDocument doc;
  if (!loadDoc(doc)) return true;  // no document yet = no shelves
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
  if (!loadDoc(doc)) return "";
  return doc["status"][path.c_str()] | "";
}

uint32_t Shelves::dailyGoalMinutes() {
  JsonDocument doc;
  if (!loadDoc(doc)) return DEFAULT_GOAL_MINUTES;
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
  Storage.ensureDirectoryExists("/.crosspoint/inklink");
  if (!PersistableStoreBase::writeDocToFile(PATH, doc)) {
    error = "write failed";
    return false;
  }
  return true;
}

bool Shelves::setStatus(const std::string& path, const char* status) {
  JsonDocument doc;
  loadDoc(doc);
  if (!doc.is<JsonObject>()) doc.to<JsonObject>();
  if (!status || !status[0]) {
    doc["status"].as<JsonObject>().remove(path.c_str());
  } else {
    if (!validStatus(status)) return false;
    doc["status"][path.c_str()] = status;
  }
  doc["version"] = 1;
  Storage.ensureDirectoryExists("/.crosspoint/inklink");
  return PersistableStoreBase::writeDocToFile(PATH, doc);
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
