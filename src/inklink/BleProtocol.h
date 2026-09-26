#pragma once

#include <HalMemory.h>

#include <cstddef>
#include <cstdint>
#include <string>

#include "ApiResponder.h"

// Transport-independent part of the BLE sync channel (see PROJECT/notes/api.md,
// "BLE"): request dispatch, the bounded response buffer and the notification
// frame format. Compiled on every target so the simulator can exercise it
// through a loopback transport; the radio side lives in BleSync.cpp.
namespace inklink::ble {

// 128-bit UUIDs, big-endian text form (what CoreBluetooth prints).
constexpr const char* SERVICE_UUID = "F0110001-7B3A-4C8E-9F1D-6A2B5C4D3E01";
constexpr const char* REQUEST_UUID = "F0110002-7B3A-4C8E-9F1D-6A2B5C4D3E01";
constexpr const char* RESPONSE_UUID = "F0110003-7B3A-4C8E-9F1D-6A2B5C4D3E01";

constexpr size_t MAX_REQUEST = 512;            // ATT attribute value limit
constexpr size_t RESPONSE_CAP = 64 * 1024;     // JSON body bytes per response
constexpr size_t FRAME_HEADER = 3;             // seq u16 LE + flags u8
constexpr uint16_t MIN_MTU = 23;
constexpr uint16_t MAX_MTU = 517;
constexpr size_t MAX_FRAME = MAX_MTU - 3;      // notification value = ATT_MTU - 3
constexpr uint8_t FLAG_LAST = 0x01;
constexpr uint8_t FLAG_ERROR = 0x02;
constexpr uint8_t MAX_AUTH_FAILURES = 5;       // then the connection is dropped

// One response body in a PSRAM buffer of fixed capacity. Error replies carry
// "status" (the HTTP code the same failure would have) next to "error".
class BufferResponder final : public api::Responder {
 public:
  // Allocates the buffer once. False on OOM.
  bool allocate(size_t bytes);
  void release();
  bool allocated() const { return buf != nullptr; }
  // Lowers the usable capacity (tests); clamped to the allocation.
  void setLimit(size_t bytes);
  void reset();

  void send(int status, const char* json, size_t len) override;
  void sendError(int status, const char* message) override;
  void beginStream() override;
  void write(const char* data, size_t len) override;
  void endStream() override;
  size_t capacity() const override { return limit; }

  const uint8_t* data() const { return buf.get(); }
  size_t size() const { return len; }
  bool isError() const { return status >= 400; }
  int statusCode() const { return status; }

 private:
  void put(const char* data, size_t n);
  HalMemory::PsramBuffer buf;
  size_t allocatedBytes = 0;
  size_t limit = 0;
  size_t len = 0;
  int status = 0;
  bool overflow = false;
};

// Per-connection state. Everything but "auth" needs authorized == true.
struct Session {
  bool authorized = false;
  uint8_t authFailures = 0;
};

// Runs one JSON request ({"op":"...", ...}) and leaves the reply in `out`.
// Must run on the main loop: the operations read the SD card and in-RAM stores.
void dispatch(const char* request, size_t len, Session& session, BufferResponder& out);

// Splits a response into notification frames [seq u16 LE][flags][payload].
// peek() builds the current frame into `frame` (at least MTU-3 bytes; 0 when
// smaller); advance() moves on once the transport took it, so a congested send
// is simply retried with the same frame.
class FrameEncoder {
 public:
  void start(const uint8_t* body, size_t len, bool error, uint16_t mtu);
  void clear() { running = false; }
  bool active() const { return running; }
  size_t peek(uint8_t* frame, size_t cap) const;
  void advance();
  uint16_t framesSent() const { return seq; }

 private:
  const uint8_t* body = nullptr;
  size_t len = 0;
  size_t pos = 0;
  size_t chunk = 0;
  uint16_t seq = 0;
  bool error = false;
  bool running = false;
};

// Reassembles frames (what the phone does). Used by the loopback tests.
class FrameAssembler {
 public:
  enum class Result { More, Done, Bad };
  Result push(const uint8_t* frame, size_t len);
  void reset();
  const std::string& payload() const { return body; }
  bool error() const { return errorFlag; }
  uint16_t frames() const { return expected; }

 private:
  std::string body;
  uint16_t expected = 0;
  bool errorFlag = false;
};

// Request/response engine shared by the radio and the loopback transport.
// The caller owns threading: every method runs on the main loop.
class Channel {
 public:
  using SendFn = bool (*)(void* ctx, const uint8_t* frame, size_t len);

  bool begin();  // allocates the response and frame buffers in PSRAM
  void end();
  bool ready() const { return response.allocated(); }
  // New connection: forget authorization and any unsent reply.
  void resetSession();
  // Runs a request and queues its reply for sending at `mtu`.
  void handle(const char* request, size_t len, uint16_t mtu);
  // Sends queued frames until done, `maxFrames` went out or the transport
  // refuses one (congestion). True when nothing is left to send.
  bool pump(SendFn send, void* ctx, size_t maxFrames);
  bool sending() const { return encoder.active(); }
  // Too many failed auth attempts: drop the link once the reply is out.
  bool shouldDisconnect() const { return session.authFailures >= MAX_AUTH_FAILURES; }
  BufferResponder& responder() { return response; }
  const Session& state() const { return session; }

 private:
  Session session;
  BufferResponder response;
  FrameEncoder encoder;
  HalMemory::PsramBuffer frame;
};

}  // namespace inklink::ble
