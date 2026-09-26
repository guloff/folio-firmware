#include "Pairing.h"

#include <Arduino.h>
#include <ArduinoJson.h>
#include <HalStorage.h>
#include <Logging.h>
#include <WebServer.h>
#include <esp_random.h>
#include <mbedtls/sha256.h>

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>

#include "InkLinkClock.h"
#include "JsonLines.h"

namespace inklink::pairing {

namespace {

constexpr const char* JSON = "application/json";
constexpr size_t TOKEN_BYTES = 32;
constexpr size_t TOKEN_HEX = TOKEN_BYTES * 2;
constexpr size_t MAX_FILE_BYTES = 4096;
// A burnt PIN (5 wrong guesses) locks out pair/start for 30 s, doubling with
// each further burn up to 8 min; a correct PIN clears the streak.
constexpr uint32_t LOCKOUT_BASE_MS = 30000;
constexpr uint8_t LOCKOUT_MAX_SHIFT = 4;
constexpr uint32_t START_MIN_INTERVAL_MS = 1000;

struct Device {
  char name[NAME_BYTES];
  uint8_t hash[32];
  int64_t created;
};

WebServer* server = nullptr;

Device devices[MAX_DEVICES];
size_t deviceCount = 0;
bool loaded = false;

// PIN session. `gone` = the last PIN burnt or expired (410 until a new start).
bool pinActive = false;
bool pinGone = false;
uint32_t pin = 0;
uint32_t pinIssuedAt = 0;
uint8_t attemptsLeft = 0;
char pinName[NAME_BYTES] = {};
uint32_t gen = 1;

uint32_t failStreak = 0;
uint32_t lastFailAt = 0;
uint32_t lastStartAt = 0;
bool startedOnce = false;

void sha256(const uint8_t* data, size_t len, uint8_t out[32]) {
  mbedtls_sha256_context ctx;
  mbedtls_sha256_init(&ctx);
  mbedtls_sha256_starts(&ctx, 0);
  mbedtls_sha256_update(&ctx, data, len);
  mbedtls_sha256_finish(&ctx, out);
  mbedtls_sha256_free(&ctx);
}

void toHex(const uint8_t* in, size_t len, char* out) {
  static constexpr char HEX_DIGITS[] = "0123456789abcdef";
  for (size_t i = 0; i < len; i++) {
    out[i * 2] = HEX_DIGITS[in[i] >> 4];
    out[i * 2 + 1] = HEX_DIGITS[in[i] & 0x0F];
  }
  out[len * 2] = '\0';
}

int hexVal(char c) {
  if (c >= '0' && c <= '9') return c - '0';
  if (c >= 'a' && c <= 'f') return c - 'a' + 10;
  if (c >= 'A' && c <= 'F') return c - 'A' + 10;
  return -1;
}

bool fromHex(const char* in, size_t len, uint8_t* out, size_t outLen) {
  if (len != outLen * 2) return false;
  for (size_t i = 0; i < outLen; i++) {
    const int hi = hexVal(in[i * 2]);
    const int lo = hexVal(in[i * 2 + 1]);
    if (hi < 0 || lo < 0) return false;
    out[i] = static_cast<uint8_t>((hi << 4) | lo);
  }
  return true;
}

// Copies a display name: at most NAME_BYTES-1 bytes, never splitting a UTF-8
// sequence, control characters dropped. Empty names become "Phone".
void copyName(char (&dst)[NAME_BYTES], const char* src) {
  size_t n = 0;
  for (const char* p = src; *p && n < NAME_BYTES - 1; p++) {
    const auto c = static_cast<unsigned char>(*p);
    if (c < 0x20 || c == 0x7f) continue;
    dst[n++] = static_cast<char>(c);
  }
  if (n > 0) {
    size_t lead = n - 1;
    while (lead > 0 && (static_cast<unsigned char>(dst[lead]) & 0xC0) == 0x80) lead--;
    const auto c = static_cast<unsigned char>(dst[lead]);
    const size_t len = c < 0x80 ? 1 : c >= 0xF0 ? 4 : c >= 0xE0 ? 3 : 2;
    if (lead + len > n) n = lead;
  }
  dst[n] = '\0';
  if (n == 0) snprintf(dst, NAME_BYTES, "%s", "Phone");
}

// A read error leaves `loaded` false so the next call retries: treating it as
// "no devices" would let the next pairing overwrite every other phone's
// entry. A file that reads fine but is malformed counts as empty (logged).
void load() {
  if (loaded) return;
  deviceCount = 0;
  jsonl::recoverTmp(PATH);
  if (!Storage.exists(PATH)) {
    loaded = true;
    return;
  }
  std::string text;
  {
    HalFile f;
    if (!Storage.openFileForRead("PAIR", PATH, f)) {
      LOG_ERR("PAIR", "pairing.json unreadable");
      return;
    }
    const size_t size = f.fileSize();
    if (size == 0 || size > MAX_FILE_BYTES) {
      LOG_ERR("PAIR", "pairing.json has bad size %u, ignoring it", static_cast<unsigned>(size));
      loaded = true;
      return;
    }
    text.resize(size);
    if (f.read(&text[0], size) != static_cast<int>(size)) {
      LOG_ERR("PAIR", "pairing.json short read");
      return;
    }
  }
  loaded = true;
  JsonDocument doc;
  if (deserializeJson(doc, text) != DeserializationError::Ok) {
    LOG_ERR("PAIR", "pairing.json is not valid JSON, ignoring it");
    return;
  }
  for (JsonObjectConst d : doc["devices"].as<JsonArrayConst>()) {
    if (deviceCount >= MAX_DEVICES) break;
    const char* hash = d["tokenSha256"] | "";
    Device& dev = devices[deviceCount];
    if (!fromHex(hash, strlen(hash), dev.hash, sizeof(dev.hash))) continue;
    copyName(dev.name, d["name"] | "");
    dev.created = d["created"] | static_cast<int64_t>(0);
    deviceCount++;
  }
  LOG_INF("PAIR", "%u paired device(s)", static_cast<unsigned>(deviceCount));
}

bool save() {
  JsonDocument doc;
  doc["version"] = 1;
  JsonArray arr = doc["devices"].to<JsonArray>();
  char hex[65];
  for (size_t i = 0; i < deviceCount; i++) {
    JsonObject o = arr.add<JsonObject>();
    o["name"] = devices[i].name;
    toHex(devices[i].hash, sizeof(devices[i].hash), hex);
    o["tokenSha256"] = hex;
    o["created"] = devices[i].created;
  }
  std::string out;
  serializeJson(doc, out);
  return jsonl::writeAtomic(PATH, out);
}

bool matchesDigest(const uint8_t digest[32]) {
  // Every stored digest is compared in full so timing does not reveal which
  // device (or how many bytes) matched.
  uint8_t found = 0;
  for (size_t i = 0; i < deviceCount; i++) {
    uint8_t diff = 0;
    for (size_t b = 0; b < 32; b++) diff |= static_cast<uint8_t>(devices[i].hash[b] ^ digest[b]);
    found |= static_cast<uint8_t>(diff == 0);
  }
  return found != 0;
}

// Expires the PIN after its TTL; returns true while one is on offer.
bool pinLive() {
  if (pinActive && millis() - pinIssuedAt >= PIN_TTL_MS) {
    pinActive = false;
    pinGone = true;
    gen++;
    LOG_INF("PAIR", "PIN expired");
  }
  return pinActive;
}

// Pulls the token out of X-InkLink-Token or, for WebDAV only, the Basic-auth
// password: browsers replay cached Basic credentials on cross-site form
// posts, a custom header they never add on their own.
bool requestToken(WebServer& s, bool allowBasic, char (&out)[TOKEN_HEX + 1]) {
  String value = s.header(TOKEN_HEADER);
  if (value.length() == 0) {
    if (!allowBasic) return false;
    const String auth = s.header("Authorization");
    if (!auth.startsWith("Basic ")) return false;
    // base64("name:token") of a 64-char token is at most ~130 chars.
    char decoded[160];
    size_t n = 0;
    uint32_t acc = 0;
    int bits = 0;
    for (size_t i = 6; i < auth.length() && n < sizeof(decoded) - 1; i++) {
      const char c = auth[i];
      int v;
      if (c >= 'A' && c <= 'Z') v = c - 'A';
      else if (c >= 'a' && c <= 'z') v = c - 'a' + 26;
      else if (c >= '0' && c <= '9') v = c - '0' + 52;
      else if (c == '+') v = 62;
      else if (c == '/') v = 63;
      else if (c == '=' || c == ' ') continue;
      else return false;
      acc = (acc << 6) | static_cast<uint32_t>(v);
      bits += 6;
      if (bits >= 8) {
        bits -= 8;
        decoded[n++] = static_cast<char>((acc >> bits) & 0xFF);
      }
    }
    decoded[n] = '\0';
    const char* colon = strchr(decoded, ':');
    if (!colon) return false;
    value = colon + 1;
  }
  value.trim();
  if (value.length() != TOKEN_HEX) return false;
  memcpy(out, value.c_str(), TOKEN_HEX);
  out[TOKEN_HEX] = '\0';
  return true;
}

void sendJson(int code, const JsonDocument& doc) {
  std::string out;
  serializeJson(doc, out);
  server->send(code, JSON, out.c_str());
}

void sendError(int code, const char* message) {
  JsonDocument doc;
  doc["ok"] = false;
  doc["error"] = message;
  sendJson(code, doc);
}

bool parseBody(JsonDocument& doc) {
  if (!server->hasArg("plain")) return true;  // body is optional for pair/start
  const String body = server->arg("plain");
  if (body.length() == 0) return true;
  if (deserializeJson(doc, body.c_str(), body.length()) != DeserializationError::Ok || !doc.is<JsonObject>()) {
    sendError(400, "invalid JSON body");
    return false;
  }
  return true;
}

uint32_t lockoutRemainingMs() {
  if (failStreak < PIN_ATTEMPTS) return 0;
  uint32_t shift = failStreak / PIN_ATTEMPTS - 1;
  if (shift > LOCKOUT_MAX_SHIFT) shift = LOCKOUT_MAX_SHIFT;
  const uint32_t lockout = LOCKOUT_BASE_MS << shift;
  const uint32_t since = millis() - lastFailAt;
  return since >= lockout ? 0 : lockout - since;
}

void sendTooMany(uint32_t waitMs) {
  JsonDocument doc;
  doc["ok"] = false;
  doc["error"] = "too many attempts";
  doc["retryIn"] = (waitMs + 999) / 1000;
  sendJson(429, doc);
}

void handleStart() {
  JsonDocument body;
  if (!parseBody(body)) return;
  const uint32_t lockout = lockoutRemainingMs();
  if (lockout > 0) return sendTooMany(lockout);
  if (startedOnce && millis() - lastStartAt < START_MIN_INTERVAL_MS) {
    return sendTooMany(START_MIN_INTERVAL_MS - (millis() - lastStartAt));
  }
  startedOnce = true;
  lastStartAt = millis();

  pin = esp_random() % 1000000;
#ifdef SIMULATOR
  if (const char* fixed = getenv("CROSSPOINT_SIM_PAIR_PIN")) {
    if (strlen(fixed) == 6) pin = static_cast<uint32_t>(strtoul(fixed, nullptr, 10)) % 1000000;
  }
#endif
  copyName(pinName, body["name"] | "");
  pinActive = true;
  pinGone = false;
  pinIssuedAt = millis();
  attemptsLeft = PIN_ATTEMPTS;
  gen++;
#ifdef SIMULATOR
  LOG_INF("INKLINK", "pairing PIN: %06u", static_cast<unsigned>(pin));
#endif
  LOG_INF("PAIR", "pairing requested by \"%s\"", pinName);

  JsonDocument doc;
  doc["ok"] = true;
  doc["digits"] = 6;
  doc["expiresIn"] = PIN_TTL_MS / 1000;
  sendJson(200, doc);
}

void handleFinish() {
  JsonDocument body;
  if (!parseBody(body)) return;
  if (!pinLive()) {
    if (pinGone) return sendError(410, "pin expired, request a new one");
    return sendError(409, "no pairing in progress");
  }
  const char* given = body["pin"] | "";
  bool wellFormed = strlen(given) == 6;
  uint32_t value = 0;
  for (size_t i = 0; wellFormed && i < 6; i++) {
    if (given[i] < '0' || given[i] > '9') wellFormed = false;
    value = value * 10 + static_cast<uint32_t>(given[i] - '0');
  }
  // Branch-free equality on the number itself.
  const bool match = wellFormed && ((value ^ pin) == 0);
  if (!match) {
    failStreak++;
    lastFailAt = millis();
    attemptsLeft = attemptsLeft > 0 ? attemptsLeft - 1 : 0;
    LOG_INF("PAIR", "wrong PIN, %u attempt(s) left", static_cast<unsigned>(attemptsLeft));
    JsonDocument doc;
    doc["ok"] = false;
    doc["attemptsLeft"] = attemptsLeft;
    if (attemptsLeft == 0) {
      pinActive = false;
      pinGone = true;
      gen++;
      doc["error"] = "pin burned, request a new one";
      return sendJson(410, doc);
    }
    doc["error"] = "bad pin";
    return sendJson(403, doc);
  }

  load();
  if (!loaded) return sendError(500, "could not read pairings");
  uint8_t token[TOKEN_BYTES];
  for (size_t i = 0; i < TOKEN_BYTES; i += 4) {
    const uint32_t r = esp_random();
    memcpy(token + i, &r, 4);
  }
  // Put back if the new list can't be stored.
  Device evicted{};
  size_t evictedAt = MAX_DEVICES;
  if (deviceCount == MAX_DEVICES) {
    // Evict the oldest pairing (smallest creation time; undated ones first).
    size_t oldest = 0;
    for (size_t i = 1; i < deviceCount; i++) {
      if (devices[i].created < devices[oldest].created) oldest = i;
    }
    evicted = devices[oldest];
    evictedAt = oldest;
    for (size_t i = oldest; i + 1 < deviceCount; i++) devices[i] = devices[i + 1];
    deviceCount--;
  }
  Device& dev = devices[deviceCount];
  const char* name = body["name"] | "";
  copyName(dev.name, name[0] ? name : pinName);
  sha256(token, sizeof(token), dev.hash);
  time_t now = 0;
  dev.created = clock::nowUtc(now) ? static_cast<int64_t>(now) : 0;
  deviceCount++;
  if (!save()) {
    deviceCount--;
    if (evictedAt < MAX_DEVICES) {
      for (size_t i = deviceCount; i > evictedAt; i--) devices[i] = devices[i - 1];
      devices[evictedAt] = evicted;
      deviceCount++;
    }
    return sendError(500, "could not store pairing");
  }
  pinActive = false;
  pinGone = false;
  failStreak = 0;
  gen++;
  LOG_INF("PAIR", "paired \"%s\" (%u device(s))", dev.name, static_cast<unsigned>(deviceCount));

  char hex[TOKEN_HEX + 1];
  toHex(token, sizeof(token), hex);
  JsonDocument doc;
  doc["ok"] = true;
  doc["token"] = hex;
  sendJson(200, doc);
}

}  // namespace

void registerRoutes(WebServer& s) {
  server = &s;
  loaded = false;
  load();
  pinActive = false;
  pinGone = false;
  gen++;
  s.on("/api/inklink/pair/start", HTTP_POST, handleStart);
  s.on("/api/inklink/pair/finish", HTTP_POST, handleFinish);
}

bool tokenValid(const char* tokenHex, size_t len) {
  uint8_t token[TOKEN_BYTES];
  if (!fromHex(tokenHex, len, token, sizeof(token))) return false;
  load();
  uint8_t digest[32];
  sha256(token, sizeof(token), digest);
  return matchesDigest(digest);
}

bool authorized(WebServer& s, bool allowBasic) {
  char token[TOKEN_HEX + 1];
  if (!requestToken(s, allowBasic, token)) return false;
  return tokenValid(token, TOKEN_HEX);
}

void sendPairingRequired(WebServer& s, bool webdav) {
  if (webdav) s.sendHeader("WWW-Authenticate", "Basic realm=\"Folio\"");
  s.send(401, JSON, "{\"ok\":false,\"error\":\"pairing required\"}");
}

bool requireAuth(WebServer& s, bool webdav) {
  if (authorized(s, webdav)) return true;
  LOG_INF("PAIR", "401 %s", s.uri().c_str());
  sendPairingRequired(s, webdav);
  return false;
}

bool isPaired() {
  load();
  return deviceCount > 0;
}

bool reset() {
  deviceCount = 0;
  loaded = true;
  pinActive = false;
  pinGone = false;
  gen++;
  const std::string tmp = std::string(PATH) + ".tmp";
  if (Storage.exists(tmp.c_str())) Storage.remove(tmp.c_str());
  if (Storage.exists(PATH) && !Storage.remove(PATH)) {
    LOG_ERR("PAIR", "could not remove %s", PATH);
    return false;
  }
  LOG_INF("PAIR", "all pairings removed");
  return true;
}

uint32_t generation() {
  pinLive();
  return gen;
}

PinView currentPin() {
  PinView v;
  if (!pinLive()) return v;
  v.active = true;
  snprintf(v.pin, sizeof(v.pin), "%06u", static_cast<unsigned>(pin));
  memcpy(v.name, pinName, sizeof(v.name));
  v.secondsLeft = (PIN_TTL_MS - (millis() - pinIssuedAt)) / 1000;
  return v;
}

}  // namespace inklink::pairing
