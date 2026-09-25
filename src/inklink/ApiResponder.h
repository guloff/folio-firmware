#pragma once

#include <ArduinoJson.h>

#include <cstddef>
#include <cstdint>

// Transport-neutral core of the companion API: the read-only /api/inklink/*
// documents and the clock sync, written into a Responder. HTTP routes
// (InkLinkApi.cpp) and the BLE dispatcher (BleProtocol.cpp) both call these.
namespace inklink::api {

class Responder {
 public:
  virtual ~Responder() = default;
  // Complete body. `json` is NUL-terminated.
  virtual void send(int status, const char* json, size_t len) = 0;
  // {"ok":false,"error":<message>} (a transport may add fields).
  virtual void sendError(int status, const char* message) = 0;
  // Streamed 200 body: beginStream(), write()..., endStream().
  virtual void beginStream() = 0;
  virtual void write(const char* data, size_t len) = 0;
  virtual void endStream() = 0;
  // Total body bytes a stream may produce. Array streams stop adding items
  // before crossing it and report where to continue ("more", "nextOffset").
  virtual size_t capacity() const { return SIZE_MAX; }
};

void sendDoc(Responder& out, int status, const JsonDocument& doc);
void sendOk(Responder& out);

// `authorized`: the caller presented a valid pairing token.
void info(Responder& out, bool authorized);
void stats(Responder& out);
// Array documents. `skip` drops that many leading items (BLE continuation;
// HTTP always passes 0).
void sessions(Responder& out, int64_t since, size_t skip);
struct BooksPage {
  bool paged = false;  // offset/limit given: one page plus offset/limit in the reply
  size_t offset = 0;
  size_t limit = 200;  // capped at MAX_BOOKS_PAGE
};
constexpr size_t MAX_BOOKS_PAGE = 200;
void books(Responder& out, const BooksPage& page);
// `book` (nullable) filters by path; `since` > 0 keeps highlights created at
// or after it (undated ones are always kept).
void highlights(Responder& out, const char* book, int64_t since, size_t skip);
void vocab(Responder& out, size_t skip);
void screenshots(Responder& out, size_t skip);
// Body {"epoch":<utc>,"tzOffsetMin":<min>} -> {"ok":true} or an error.
void setTime(Responder& out, const JsonDocument& body);

}  // namespace inklink::api
