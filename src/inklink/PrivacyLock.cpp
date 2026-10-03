#include "PrivacyLock.h"

#include <Arduino.h>
#include <ArduinoJson.h>
#include <FsHelpers.h>
#include <HalStorage.h>
#include <KOReaderDocumentId.h>
#include <Logging.h>
#include <esp_mac.h>
#include <esp_random.h>
#include <monocypher-ed25519.h>
#include <nvs.h>

#include <algorithm>
#include <cstring>

#include "FirmwareSignature.h"
#include "JsonLines.h"
#include "Pairing.h"

namespace inklink::privacy {

namespace {

// ---- NVS -------------------------------------------------------------------

constexpr const char* NVS_NAMESPACE = "folio";
constexpr const char* KEY_PIN = "pin";        // PinRecord
constexpr const char* KEY_FAILS = "pinfail";  // u32, consecutive failures
constexpr const char* KEY_DEVLOCK = "devlock";

constexpr uint8_t PIN_RECORD_VERSION = 1;
constexpr uint32_t KDF_ROUNDS = 4096;

struct PinRecord {
  uint8_t version;
  uint8_t salt[16];
  uint8_t hash[32];
};

bool nvsGetBlob(const char* key, void* out, size_t size) {
  nvs_handle_t h;
  if (nvs_open(NVS_NAMESPACE, NVS_READONLY, &h) != ESP_OK) return false;
  size_t len = size;
  const bool ok = nvs_get_blob(h, key, out, &len) == ESP_OK && len == size;
  nvs_close(h);
  return ok;
}

uint32_t nvsGetU32(const char* key) {
  nvs_handle_t h;
  if (nvs_open(NVS_NAMESPACE, NVS_READONLY, &h) != ESP_OK) return 0;
  uint32_t v = 0;
  if (nvs_get_u32(h, key, &v) != ESP_OK) v = 0;
  nvs_close(h);
  return v;
}

// Writes or (data == nullptr) erases one key.
bool nvsPut(const char* key, const void* data, size_t size, bool isU32 = false) {
  nvs_handle_t h;
  if (nvs_open(NVS_NAMESPACE, NVS_READWRITE, &h) != ESP_OK) {
    LOG_ERR("LOCK", "NVS open failed");
    return false;
  }
  esp_err_t err;
  if (!data) {
    err = nvs_erase_key(h, key);
    if (err == ESP_ERR_NVS_NOT_FOUND) err = ESP_OK;
  } else if (isU32) {
    err = nvs_set_u32(h, key, *static_cast<const uint32_t*>(data));
  } else {
    err = nvs_set_blob(h, key, data, size);
  }
  if (err == ESP_OK) err = nvs_commit(h);
  nvs_close(h);
  if (err != ESP_OK) LOG_ERR("LOCK", "NVS write %s failed: %d", key, static_cast<int>(err));
  return err == ESP_OK;
}

// PBKDF2-style stretch over a pre-hashed PIN (hmacSha256 takes a 32-byte key).
// A 4-8 digit PIN can't resist an offline search of a dumped flash; the
// stretch only keeps that from being instant. The real guard is the on-device
// failure lockout.
void derive(const char* pin, const uint8_t salt[16], uint8_t out[32]) {
  uint8_t key[32];
  uint8_t seed[16 + MAX_PIN];
  const size_t len = strlen(pin);
  memcpy(seed, salt, 16);
  memcpy(seed + 16, pin, len);
  pairing::sha256Digest(seed, 16 + len, key);
  uint8_t u[32];
  uint8_t block[20];
  memcpy(block, salt, 16);
  block[16] = 0;
  block[17] = 0;
  block[18] = 0;
  block[19] = 1;
  pairing::hmacSha256(key, block, sizeof(block), u);
  memcpy(out, u, 32);
  for (uint32_t i = 1; i < KDF_ROUNDS; i++) {
    pairing::hmacSha256(key, u, sizeof(u), u);
    for (int j = 0; j < 32; j++) out[j] ^= u[j];
  }
  crypto_wipe(key, sizeof(key));
  crypto_wipe(seed, sizeof(seed));
}

void fillRandom(uint8_t* out, size_t len) {
  for (size_t i = 0; i < len; i += 4) {
    const uint32_t r = esp_random();
    memcpy(out + i, &r, std::min<size_t>(4, len - i));
  }
}

bool validPin(const char* pin) {
  if (!pin) return false;
  const size_t len = strlen(pin);
  if (len < MIN_PIN || len > MAX_PIN) return false;
  return std::all_of(pin, pin + len, [](char c) { return c >= '0' && c <= '9'; });
}

// ---- failure lockout -------------------------------------------------------

constexpr uint32_t FREE_ATTEMPTS = 5;
constexpr uint32_t FIRST_LOCKOUT_MS = 30 * 1000;
constexpr uint32_t MAX_LOCKOUT_MS = 15 * 60 * 1000;

uint32_t failures = 0;
bool failuresLoaded = false;
uint32_t lastFailureMs = 0;  // 0 = none this boot: a restart restarts the wait

uint32_t loadedFailures() {
  if (!failuresLoaded) {
    failures = nvsGetU32(KEY_FAILS);
    failuresLoaded = true;
  }
  return failures;
}

uint32_t lockoutFor(const uint32_t count) {
  if (count < FREE_ATTEMPTS) return 0;
  uint32_t ms = FIRST_LOCKOUT_MS;
  for (uint32_t i = FREE_ATTEMPTS; i < count && ms < MAX_LOCKOUT_MS; i++) ms *= 2;
  return std::min(ms, MAX_LOCKOUT_MS);
}

void storeFailures(const uint32_t count) {
  failures = count;
  failuresLoaded = true;
  nvsPut(KEY_FAILS, &failures, sizeof(failures), true);
}

// ---- session ---------------------------------------------------------------

constexpr uint32_t SESSION_MAGIC = 0x464C4B31;  // "FLK1"
constexpr uint8_t DEVICE_OPEN = 1 << 0;
constexpr uint8_t BOOKS_OPEN = 1 << 1;

// RTC memory survives esp_restart(), which is how silent restarts carry the
// unlocks; beginBoot() discards it after any other kind of boot.
RTC_NOINIT_ATTR uint32_t sessionMagic;
RTC_NOINIT_ATTR uint8_t sessionFlags;

uint8_t flags() { return sessionMagic == SESSION_MAGIC ? sessionFlags : 0; }

void setFlags(const uint8_t f) {
  sessionFlags = f;
  sessionMagic = SESSION_MAGIC;
}

// ---- protected books -------------------------------------------------------

constexpr const char* BOOKS_PATH = "/.crosspoint/inklink/locks.json";

struct Entry {
  std::string path;
  std::string docId;  // content hash: finds the book again after an outside move
  std::string title;  // names its screenshot folder
};

std::vector<Entry> books;

bool saveBooks() {
  JsonDocument doc;
  JsonArray arr = doc["books"].to<JsonArray>();
  for (const auto& e : books) {
    JsonObject o = arr.add<JsonObject>();
    o["p"] = e.path.c_str();
    o["id"] = e.docId.c_str();
    o["t"] = e.title.c_str();
  }
  std::string out;
  serializeJson(doc, out);
  Storage.ensureDirectoryExists("/.crosspoint/inklink");
  if (!jsonl::writeAtomic(BOOKS_PATH, out)) {
    LOG_ERR("LOCK", "locks.json write failed");
    return false;
  }
  return true;
}

Entry* findByPath(const std::string& path) {
  for (auto& e : books) {
    if (e.path == path) return &e;
  }
  return nullptr;
}

bool readFile(const char* path, std::string& out) {
  HalFile f = Storage.open(path, O_RDONLY);
  if (!f) return false;
  const size_t size = f.fileSize();
  if (size > 64 * 1024) return false;
  out.assign(size, '\0');
  return f.read(out.data(), size) == static_cast<int>(size);
}

int hexValue(const char c) {
  if (c >= '0' && c <= '9') return c - '0';
  if (c >= 'a' && c <= 'f') return c - 'a' + 10;
  if (c >= 'A' && c <= 'F') return c - 'A' + 10;
  return -1;
}

// ---- reset challenge -------------------------------------------------------

constexpr char RESET_PREFIX[] = "FOLIO-PINRESET-v1\n";
constexpr size_t RESET_PREFIX_LEN = sizeof(RESET_PREFIX) - 1;
// 16 random bytes + the 6-byte MAC: bound to this device and this request.
uint8_t challenge[22];
bool challengeLive = false;

}  // namespace

// ---- PIN -------------------------------------------------------------------

bool hasPin() {
  PinRecord rec;
  return nvsGetBlob(KEY_PIN, &rec, sizeof(rec)) && rec.version == PIN_RECORD_VERSION;
}

bool setPin(const char* pin) {
  if (!validPin(pin)) return false;
  PinRecord rec{};
  rec.version = PIN_RECORD_VERSION;
  fillRandom(rec.salt, sizeof(rec.salt));
  derive(pin, rec.salt, rec.hash);
  const bool ok = nvsPut(KEY_PIN, &rec, sizeof(rec));
  crypto_wipe(&rec, sizeof(rec));
  if (ok) {
    storeFailures(0);
    lastFailureMs = 0;
  }
  return ok;
}

void clearPin() {
  nvsPut(KEY_PIN, nullptr, 0);
  nvsPut(KEY_DEVLOCK, nullptr, 0);
  storeFailures(0);
  lastFailureMs = 0;
}

uint32_t lockoutRemainingMs() {
  const uint32_t lockout = lockoutFor(loadedFailures());
  if (lockout == 0) return 0;
  // After a restart the wait starts over from boot rather than vanishing.
  const uint32_t since = lastFailureMs ? millis() - lastFailureMs : millis();
  return since >= lockout ? 0 : lockout - since;
}

Verify verifyPin(const char* pin) {
  PinRecord rec;
  if (!nvsGetBlob(KEY_PIN, &rec, sizeof(rec)) || rec.version != PIN_RECORD_VERSION) return Verify::NoPin;
  if (lockoutRemainingMs() > 0) return Verify::LockedOut;
  uint8_t hash[32];
  bool match = false;
  if (validPin(pin)) {
    derive(pin, rec.salt, hash);
    match = crypto_verify32(hash, rec.hash) == 0;
    crypto_wipe(hash, sizeof(hash));
  }
  if (match) {
    if (loadedFailures() != 0) storeFailures(0);
    lastFailureMs = 0;
    return Verify::Ok;
  }
  storeFailures(loadedFailures() + 1);
  lastFailureMs = millis();
  if (lastFailureMs == 0) lastFailureMs = 1;
  LOG_INF("LOCK", "wrong PIN (%u in a row)", static_cast<unsigned>(failures));
  return Verify::Wrong;
}

// ---- device lock -------------------------------------------------------------

bool deviceLockEnabled() {
  uint8_t on = 0;
  return nvsGetBlob(KEY_DEVLOCK, &on, 1) && on == 1 && hasPin();
}

void setDeviceLock(const bool on) {
  const uint8_t v = 1;
  nvsPut(KEY_DEVLOCK, on ? &v : nullptr, 1);
}

// ---- session -----------------------------------------------------------------

void beginBoot(const bool silentRestart) {
  if (!silentRestart || sessionMagic != SESSION_MAGIC) setFlags(0);
}

void relock() {
  setFlags(0);
  challengeLive = false;
}

bool deviceUnlocked() { return flags() & DEVICE_OPEN; }
void unlockDevice() { setFlags(flags() | DEVICE_OPEN); }
bool booksUnlocked() { return flags() & BOOKS_OPEN; }
void unlockBooks() { setFlags(flags() | BOOKS_OPEN); }

// ---- protected books ---------------------------------------------------------

void loadBooks() {
  books.clear();
  jsonl::recoverTmp(BOOKS_PATH);
  if (!Storage.exists(BOOKS_PATH)) return;
  std::string text;
  JsonDocument doc;
  if (!readFile(BOOKS_PATH, text) || deserializeJson(doc, text) != DeserializationError::Ok) {
    LOG_ERR("LOCK", "locks.json unreadable");
    return;
  }
  for (JsonObjectConst o : doc["books"].as<JsonArrayConst>()) {
    Entry e;
    e.path = o["p"] | "";
    e.docId = o["id"] | "";
    e.title = o["t"] | "";
    if (!e.path.empty()) books.push_back(std::move(e));
  }
  LOG_INF("LOCK", "%u protected books", static_cast<unsigned>(books.size()));
}

size_t protectedCount() { return books.size(); }

bool isProtected(const std::string& path) { return findByPath(path) != nullptr; }

bool isHidden(const std::string& path) { return !booksUnlocked() && isProtected(path); }

bool needsPin(const std::string& path) {
  if (booksUnlocked() || books.empty()) return false;
  if (isProtected(path)) return true;
  // A protected book copied or moved over USB keeps its content: catch it
  // here, the one place every opening goes through.
  const std::string id = KOReaderDocumentId::calculate(path);
  if (id.empty()) return false;
  for (auto& e : books) {
    if (e.docId != id) continue;
    if (!Storage.exists(e.path.c_str())) {
      LOG_INF("LOCK", "protected book moved: %s -> %s", e.path.c_str(), path.c_str());
      e.path = path;
      saveBooks();
      return true;
    }
    // A second copy of a protected book is protected too.
    books.push_back(Entry{path, id, e.title});
    saveBooks();
    return true;
  }
  return false;
}

bool protect(const std::string& path, const std::string& title) {
  if (path.empty() || isProtected(path)) return !path.empty();
  books.push_back(Entry{path, KOReaderDocumentId::calculate(path), title});
  return saveBooks();
}

bool unprotect(const std::string& path) {
  const auto before = books.size();
  books.erase(std::remove_if(books.begin(), books.end(), [&](const Entry& e) { return e.path == path; }), books.end());
  return books.size() == before || saveBooks();
}

void onPathChanged(const std::string& from, const std::string& to) {
  bool changed = false;
  for (auto& e : books) {
    // A renamed folder carries every protected book inside it.
    if (e.path == from) {
      e.path = to;
      changed = true;
    } else if (e.path.size() > from.size() && e.path.compare(0, from.size(), from) == 0 && e.path[from.size()] == '/') {
      e.path = to + e.path.substr(from.size());
      changed = true;
    }
  }
  if (changed) saveBooks();
}

void onPathRemoved(const std::string& path) {
  const auto before = books.size();
  books.erase(std::remove_if(books.begin(), books.end(),
                             [&](const Entry& e) {
                               return e.path == path ||
                                      (e.path.size() > path.size() && e.path.compare(0, path.size(), path) == 0 &&
                                       e.path[path.size()] == '/');
                             }),
              books.end());
  if (books.size() != before) saveBooks();
}

bool hiddenFromApp(const char* path) { return path && path[0] && findByPath(path) != nullptr; }

bool hiddenScreenshotFolder(const char* folderName) {
  if (!folderName || !folderName[0]) return false;
  char sanitized[64];
  for (const auto& e : books) {
    if (e.title.empty()) continue;
    FsHelpers::sanitizePathComponentForFat32(e.title.c_str(), sanitized, sizeof(sanitized));
    if (strcmp(sanitized, folderName) == 0) return true;
  }
  return false;
}

void filterLibraryForApp(std::string& json) {
  if (books.empty()) return;
  JsonDocument doc;
  if (deserializeJson(doc, json) != DeserializationError::Ok) return;
  for (JsonObject shelf : doc["shelves"].as<JsonArray>()) {
    JsonArray list = shelf["books"].as<JsonArray>();
    for (size_t i = list.size(); i-- > 0;) {
      if (hiddenFromApp(list[i] | "")) list.remove(i);
    }
  }
  JsonObject status = doc["status"].as<JsonObject>();
  for (const auto& e : books) status.remove(e.path);
  json.clear();
  serializeJson(doc, json);
}

void mergeHiddenLibraryEntries(const char* currentJson, std::string& incomingJson) {
  if (books.empty() || !currentJson || !currentJson[0]) return;
  JsonDocument current;
  JsonDocument incoming;
  if (deserializeJson(current, currentJson) != DeserializationError::Ok) return;
  if (deserializeJson(incoming, incomingJson) != DeserializationError::Ok) return;
  for (const auto& e : books) {
    const char* path = e.path.c_str();
    for (JsonObjectConst shelf : current["shelves"].as<JsonArrayConst>()) {
      bool member = false;
      for (JsonVariantConst b : shelf["books"].as<JsonArrayConst>()) member = member || e.path == (b | "");
      if (!member) continue;
      // Kept only on shelves the app still has: deleting a shelf drops it.
      for (JsonObject target : incoming["shelves"].as<JsonArray>()) {
        if (strcmp(target["id"] | "", shelf["id"] | "") != 0) continue;
        bool present = false;
        for (JsonVariantConst b : target["books"].as<JsonArrayConst>()) present = present || e.path == (b | "");
        if (!present) target["books"].as<JsonArray>().add(path);
      }
    }
    const char* status = current["status"][path] | "";
    if (status[0] && !incoming["status"][path].is<const char*>()) incoming["status"][path] = status;
  }
  incomingJson.clear();
  serializeJson(incoming, incomingJson);
}

// ---- PIN reset -------------------------------------------------------------

std::string resetChallengeHex() {
  fillRandom(challenge, 16);
  esp_read_mac(challenge + 16, ESP_MAC_WIFI_STA);
  challengeLive = true;
  static constexpr char HEX_DIGITS[] = "0123456789abcdef";
  std::string hex;
  hex.reserve(sizeof(challenge) * 2);
  for (const uint8_t b : challenge) {
    hex.push_back(HEX_DIGITS[b >> 4]);
    hex.push_back(HEX_DIGITS[b & 0x0F]);
  }
  return hex;
}

bool resetWithSignatureHex(const char* signatureHex) {
  if (!challengeLive || !signatureHex || strlen(signatureHex) != fwsig::SIGNATURE_SIZE * 2) return false;
  // One signature per challenge, matching or not.
  challengeLive = false;
  uint8_t signature[fwsig::SIGNATURE_SIZE];
  for (size_t i = 0; i < sizeof(signature); i++) {
    const int hi = hexValue(signatureHex[2 * i]);
    const int lo = hexValue(signatureHex[2 * i + 1]);
    if (hi < 0 || lo < 0) return false;
    signature[i] = static_cast<uint8_t>(hi << 4 | lo);
  }
  uint8_t message[RESET_PREFIX_LEN + sizeof(challenge)];
  memcpy(message, RESET_PREFIX, RESET_PREFIX_LEN);
  memcpy(message + RESET_PREFIX_LEN, challenge, sizeof(challenge));
  if (!fwsig::verifyMessage(message, sizeof(message), signature)) {
    LOG_ERR("LOCK", "PIN reset: signature rejected");
    return false;
  }
  clearPin();
  LOG_INF("LOCK", "PIN reset by signed request");
  return true;
}

}  // namespace inklink::privacy
