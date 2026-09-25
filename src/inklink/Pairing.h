#pragma once

#include <cstddef>
#include <cstdint>

class WebServer;

// Phone pairing for the HTTP API. A phone asks for a PIN (pair/start), the
// reader shows it on screen, the phone sends it back (pair/finish) and gets a
// random 32-byte token. Only SHA-256 digests of issued tokens are stored, in
// /.crosspoint/inklink/pairing.json. Every mutating request must carry the
// token in X-InkLink-Token (or as the HTTP Basic password, for WebDAV).
namespace inklink::pairing {

constexpr const char* PATH = "/.crosspoint/inklink/pairing.json";
constexpr const char* TOKEN_HEADER = "X-InkLink-Token";
constexpr size_t MAX_DEVICES = 4;
constexpr size_t NAME_BYTES = 48;
constexpr uint32_t PIN_TTL_MS = 120000;
constexpr uint8_t PIN_ATTEMPTS = 5;

// Registers pair/start and pair/finish and resets the per-session PIN state.
void registerRoutes(WebServer& server);

// True when the request carries a token of a paired device.
bool authorized(WebServer& server);
// Sends 401 {"ok":false,"error":"pairing required"}; WebDAV requests also get
// a Basic challenge so clients prompt for the token as a password.
void sendPairingRequired(WebServer& server, bool webdav = false);
// authorized() or send the 401. Returns whether the handler may proceed.
bool requireAuth(WebServer& server, bool webdav = false);
// Checks a raw 64-hex-char token (WebSocket AUTH message).
bool tokenValid(const char* tokenHex, size_t len);

// At least one device is paired.
bool isPaired();
// Forgets every paired device (removes pairing.json). False on I/O error.
bool reset();

// PIN currently offered for pairing, for the on-screen overlay. `generation`
// changes whenever the overlay has to be redrawn (new PIN, success, expiry).
struct PinView {
  bool active = false;
  char pin[7] = {};
  char name[NAME_BYTES] = {};
  uint32_t secondsLeft = 0;
};
uint32_t generation();
PinView currentPin();

}  // namespace inklink::pairing
