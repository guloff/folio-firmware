#include "BleProtocol.h"

#include <ArduinoJson.h>
#include <Logging.h>

#include <cstring>

#include "InkLinkApi.h"
#include "Pairing.h"

namespace inklink::ble {

// ---- BufferResponder --------------------------------------------------------

bool BufferResponder::allocate(size_t bytes) {
  if (buf) return true;
  buf = HalMemory::allocatePsram(bytes);
  if (!buf) {
    LOG_ERR("BLE", "OOM: response buffer %u bytes", static_cast<unsigned>(bytes));
    return false;
  }
  allocatedBytes = bytes;
  limit = bytes;
  reset();
  return true;
}

void BufferResponder::release() {
  buf.reset();
  allocatedBytes = 0;
  limit = 0;
  reset();
}

void BufferResponder::setLimit(size_t bytes) { limit = bytes < allocatedBytes ? bytes : allocatedBytes; }

void BufferResponder::reset() {
  len = 0;
  status = 0;
  overflow = false;
}

void BufferResponder::put(const char* data, size_t n) {
  if (overflow) return;
  if (n > limit - len) {
    overflow = true;
    return;
  }
  memcpy(buf.get() + len, data, n);
  len += n;
}

void BufferResponder::send(int code, const char* json, size_t n) {
  reset();
  put(json, n);
  status = code;
  if (overflow) sendError(507, "response too large");
}

void BufferResponder::sendError(int code, const char* message) {
  reset();
  JsonDocument doc;
  doc["ok"] = false;
  doc["error"] = message;
  doc["status"] = code;
  char tmp[160];
  const size_t n = serializeJson(doc, tmp, sizeof(tmp));
  put(tmp, n);
  status = code;
}

void BufferResponder::beginStream() {
  reset();
  status = 200;
}

void BufferResponder::write(const char* data, size_t n) { put(data, n); }

void BufferResponder::endStream() {
  if (overflow) sendError(507, "response too large");
}

// ---- dispatch ---------------------------------------------------------------

namespace {

// Optional non-negative integer field. False when present but malformed.
bool readCount(const JsonDocument& req, const char* key, size_t& out, bool* present = nullptr) {
  JsonVariantConst v = req[key];
  if (present) *present = !v.isNull();
  if (v.isNull()) return true;
  if (!v.is<uint32_t>()) return false;
  out = v.as<uint32_t>();
  return true;
}

bool readEpoch(const JsonDocument& req, const char* key, int64_t& out) {
  JsonVariantConst v = req[key];
  if (v.isNull()) return true;
  if (!v.is<int64_t>()) return false;
  out = v.as<int64_t>();
  return true;
}

void handleAuth(const JsonDocument& req, Session& session, BufferResponder& out) {
  const char* token = req["token"] | "";
  const size_t n = strlen(token);
  // Same check as the HTTP X-InkLink-Token: SHA-256 of the token compared in
  // constant time against every paired device.
  if (n == 64 && pairing::tokenValid(token, n)) {
    session.authorized = true;
    session.authFailures = 0;
    JsonDocument doc;
    doc["ok"] = true;
    doc["api"] = api::API_VERSION;
    api::sendDoc(out, 200, doc);
    return;
  }
  session.authorized = false;
  if (session.authFailures < MAX_AUTH_FAILURES) session.authFailures++;
  JsonDocument doc;
  doc["ok"] = false;
  doc["error"] = "invalid token";
  doc["status"] = 401;
  doc["attemptsLeft"] = MAX_AUTH_FAILURES - session.authFailures;
  api::sendDoc(out, 401, doc);
  LOG_INF("BLE", "auth rejected (%u/%u)", session.authFailures, MAX_AUTH_FAILURES);
}

}  // namespace

void dispatch(const char* request, size_t len, Session& session, BufferResponder& out) {
  out.reset();
  JsonDocument req;
  if (deserializeJson(req, request, len) != DeserializationError::Ok || !req.is<JsonObject>()) {
    return out.sendError(400, "invalid JSON");
  }
  const char* op = req["op"] | "";
  if (!op[0]) return out.sendError(400, "missing op");
  if (strcmp(op, "auth") == 0) return handleAuth(req, session, out);
  if (!session.authorized) return out.sendError(401, "pairing required");

  size_t offset = 0;
  if (!readCount(req, "offset", offset)) return out.sendError(400, "offset must be a non-negative integer");
  int64_t since = 0;
  if (!readEpoch(req, "since", since)) return out.sendError(400, "since must be an integer");

  if (strcmp(op, "info") == 0) return api::info(out, true);
  if (strcmp(op, "stats") == 0) return api::stats(out);
  if (strcmp(op, "sessions") == 0) return api::sessions(out, since, offset);
  if (strcmp(op, "books") == 0) {
    api::BooksPage page;
    bool hasOffset = false;
    bool hasLimit = false;
    readCount(req, "offset", page.offset, &hasOffset);
    if (!readCount(req, "limit", page.limit, &hasLimit)) {
      return out.sendError(400, "limit must be a non-negative integer");
    }
    page.paged = hasOffset || hasLimit;
    return api::books(out, page);
  }
  if (strcmp(op, "highlights") == 0) {
    const char* book = req["book"].is<const char*>() ? req["book"].as<const char*>() : nullptr;
    return api::highlights(out, book, since, offset);
  }
  if (strcmp(op, "vocab") == 0) return api::vocab(out, offset);
  if (strcmp(op, "screenshots") == 0) return api::screenshots(out, offset);
  if (strcmp(op, "time") == 0) return api::setTime(out, req);
  out.sendError(400, "unknown op");
}

// ---- frames -------------------------------------------------------------------

void FrameEncoder::start(const uint8_t* data, size_t n, bool err, uint16_t mtu) {
  if (mtu < MIN_MTU) mtu = MIN_MTU;
  if (mtu > MAX_MTU) mtu = MAX_MTU;
  body = data;
  len = n;
  pos = 0;
  chunk = static_cast<size_t>(mtu) - 3 - FRAME_HEADER;
  seq = 0;
  error = err;
  running = true;
}

size_t FrameEncoder::peek(uint8_t* frame, size_t cap) const {
  if (!running || cap < chunk + FRAME_HEADER) return 0;
  size_t n = len - pos;
  if (n > chunk) n = chunk;
  const bool last = pos + n >= len;
  frame[0] = static_cast<uint8_t>(seq & 0xFF);
  frame[1] = static_cast<uint8_t>(seq >> 8);
  frame[2] = static_cast<uint8_t>((last ? FLAG_LAST : 0) | (error ? FLAG_ERROR : 0));
  if (n) memcpy(frame + FRAME_HEADER, body + pos, n);
  return n + FRAME_HEADER;
}

void FrameEncoder::advance() {
  if (!running) return;
  size_t n = len - pos;
  if (n > chunk) n = chunk;
  pos += n;
  seq++;
  if (pos >= len) running = false;
}

FrameAssembler::Result FrameAssembler::push(const uint8_t* frame, size_t n) {
  if (n < FRAME_HEADER) return Result::Bad;
  const uint16_t seq = static_cast<uint16_t>(frame[0] | (frame[1] << 8));
  if (seq != expected) return Result::Bad;
  if (seq == 0) {
    body.clear();
    errorFlag = false;
  }
  expected++;
  if (frame[2] & FLAG_ERROR) errorFlag = true;
  body.append(reinterpret_cast<const char*>(frame + FRAME_HEADER), n - FRAME_HEADER);
  return (frame[2] & FLAG_LAST) ? Result::Done : Result::More;
}

void FrameAssembler::reset() {
  body.clear();
  expected = 0;
  errorFlag = false;
}

// ---- Channel ------------------------------------------------------------------

bool Channel::begin() {
  if (!frame) frame = HalMemory::allocatePsram(MAX_FRAME);
  if (!frame) {
    LOG_ERR("BLE", "OOM: frame buffer");
    return false;
  }
  return response.allocate(RESPONSE_CAP);
}

void Channel::end() {
  encoder.clear();
  response.release();
  frame.reset();
  session = Session{};
}

void Channel::resetSession() {
  session = Session{};
  encoder.clear();
  response.reset();
}

void Channel::handle(const char* request, size_t len, uint16_t mtu) {
  if (!ready()) return;
  dispatch(request, len, session, response);
  encoder.start(response.data(), response.size(), response.isError(), mtu);
}

bool Channel::pump(SendFn send, void* ctx, size_t maxFrames) {
  for (size_t i = 0; i < maxFrames && encoder.active(); i++) {
    const size_t n = encoder.peek(frame.get(), MAX_FRAME);
    if (!send(ctx, frame.get(), n)) break;  // congested: same frame next time
    encoder.advance();
  }
  return !encoder.active();
}

}  // namespace inklink::ble
