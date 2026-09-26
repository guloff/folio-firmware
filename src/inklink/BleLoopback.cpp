#ifdef SIMULATOR

#include "BleLoopback.h"

#include <ArduinoJson.h>
#include <Logging.h>

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <iostream>
#include <sstream>
#include <string>

#include "BleProtocol.h"

namespace inklink::ble {

namespace {

struct Loop {
  FrameAssembler assembler;
  std::ostream* out = nullptr;
  uint16_t mtu = MIN_MTU;
  size_t congestEvery = 0;
  size_t attempts = 0;
  size_t refusals = 0;
  size_t frames = 0;
  size_t maxFrame = 0;
  bool hex = false;
  bool bad = false;
  bool done = false;
};

bool loopSend(void* ctx, const uint8_t* frame, size_t len) {
  auto* l = static_cast<Loop*>(ctx);
  l->attempts++;
  if (l->congestEvery && l->attempts % l->congestEvery == 0) {
    l->refusals++;
    return false;
  }
  if (len > static_cast<size_t>(l->mtu) - 3) l->bad = true;
  if (len > l->maxFrame) l->maxFrame = len;
  if (l->hex) {
    char line[16];
    *l->out << "    frame";
    for (size_t i = 0; i < len; i++) {
      snprintf(line, sizeof(line), " %02x", frame[i]);
      *l->out << line;
    }
    *l->out << "\n";
  }
  l->frames++;
  const FrameAssembler::Result r = l->assembler.push(frame, len);
  if (r == FrameAssembler::Result::Bad) l->bad = true;
  if (r == FrameAssembler::Result::Done) l->done = true;
  return true;
}

// Sends one request through the channel and reassembles the reply.
std::string exchange(Channel& ch, Loop& l, const std::string& request, bool print) {
  l.assembler.reset();
  l.attempts = l.refusals = l.frames = l.maxFrame = 0;
  l.bad = l.done = false;
  ch.handle(request.data(), request.size(), l.mtu);
  for (int guard = 0; guard < 100000 && !ch.pump(loopSend, &l, 8); guard++) {
  }
  const BufferResponder& r = ch.responder();
  const bool same = l.assembler.payload().size() == r.size() &&
                    memcmp(l.assembler.payload().data(), r.data(), r.size()) == 0;
  const bool ok = l.done && !l.bad && same && l.assembler.error() == r.isError();
  if (print) {
    *l.out << ">>> " << request << "\n";
    *l.out << "    mtu=" << l.mtu << " frames=" << l.frames << " maxFrame=" << l.maxFrame << " bytes=" << r.size()
           << " status=" << r.statusCode() << " errorFlag=" << (l.assembler.error() ? 1 : 0)
           << " congestionRetries=" << l.refusals << " reassembly=" << (ok ? "OK" : "FAIL") << "\n";
    *l.out << "<<< " << l.assembler.payload() << "\n";
  }
  return l.assembler.payload();
}

// Plays the phone side of the challenge-response: hello -> nonce, then
// auth{proof = HMAC-SHA256(SHA-256(token), "FOLIO-BLE-v1\n" + nonce)}.
void login(Channel& ch, Loop& l, const std::string& tokenHex) {
  const std::string hello = exchange(ch, l, "{\"op\":\"hello\"}", true);
  JsonDocument resp;
  if (deserializeJson(resp, hello) != DeserializationError::Ok) {
    *l.out << "!!! unparsable hello reply\n";
    return;
  }
  const std::string nonceHex = resp["nonce"] | "";
  uint8_t msg[13 + 16];
  memcpy(msg, "FOLIO-BLE-v1\n", 13);
  uint8_t token[32] = {};
  for (size_t i = 0; i < 16 && nonceHex.size() == 32; i++) {
    msg[13 + i] = static_cast<uint8_t>(std::strtoul(nonceHex.substr(2 * i, 2).c_str(), nullptr, 16));
  }
  for (size_t i = 0; i < 32 && tokenHex.size() == 64; i++) {
    token[i] = static_cast<uint8_t>(std::strtoul(tokenHex.substr(2 * i, 2).c_str(), nullptr, 16));
  }
  uint8_t key[32];
  pairing::sha256Digest(token, sizeof(token), key);
  uint8_t mac[32];
  pairing::hmacSha256(key, msg, sizeof(msg), mac);
  char proof[65];
  for (size_t i = 0; i < 32; i++) snprintf(proof + 2 * i, 3, "%02x", mac[i]);
  *l.out << "=== login (token redacted)\n";
  exchange(ch, l, std::string("{\"op\":\"auth\",\"proof\":\"") + proof + "\"}", true);
}

// Repeats a list request with offset = nextOffset until "more" is absent.
void follow(Channel& ch, Loop& l, const std::string& request) {
  JsonDocument req;
  if (deserializeJson(req, request) != DeserializationError::Ok) {
    *l.out << "!!! bad follow request\n";
    return;
  }
  size_t total = 0;
  int pages = 0;
  std::string payload;
  *l.out << "=== follow " << request << " (cap " << ch.responder().capacity() << ")\n";
  for (;;) {
    std::string body;
    serializeJson(req, body);
    payload = exchange(ch, l, body, false);
    JsonDocument resp;
    if (deserializeJson(resp, payload) != DeserializationError::Ok) {
      *l.out << "!!! unparsable reply: " << payload.substr(0, 200) << "\n";
      return;
    }
    size_t items = 0;
    for (JsonPairConst kv : resp.as<JsonObjectConst>()) {
      if (kv.value().is<JsonArrayConst>()) items = kv.value().as<JsonArrayConst>().size();
    }
    pages++;
    total += items;
    const bool more = resp["more"] | false;
    *l.out << "    page " << pages << ": offset=" << (req["offset"] | 0) << " items=" << items
           << " bytes=" << payload.size() << " frames=" << l.frames << " more=" << (more ? "true" : "false");
    if (more) *l.out << " nextOffset=" << (resp["nextOffset"] | 0);
    *l.out << (l.done && !l.bad ? " reassembly=OK" : " reassembly=FAIL") << "\n";
    if (!more || pages > 1000) break;
    req["offset"] = resp["nextOffset"] | 0;
  }
  *l.out << "    total items=" << total << " in " << pages << " requests\n";
}

// Encoder/assembler round trips over payload sizes around the frame limits.
void selftest(std::ostream& out) {
  out << "=== selftest: frame split/reassembly\n";
  int failures = 0;
  int cases = 0;
  static uint8_t data[RESPONSE_CAP];
  for (size_t i = 0; i < sizeof(data); i++) data[i] = static_cast<uint8_t>((i * 31 + 7) & 0xFF);
  uint8_t frame[MAX_FRAME];
  for (const uint16_t mtu : {static_cast<uint16_t>(23), static_cast<uint16_t>(185), static_cast<uint16_t>(517)}) {
    const size_t chunk = mtu - 3 - FRAME_HEADER;
    const size_t sizes[] = {0, 1, chunk - 1, chunk, chunk + 1, 2 * chunk, 2 * chunk + 1, 1000, 4096, RESPONSE_CAP};
    for (const size_t n : sizes) {
      for (const bool err : {false, true}) {
        cases++;
        FrameEncoder enc;
        FrameAssembler as;
        enc.start(data, n, err, mtu);
        size_t frames = 0;
        bool ok = true;
        FrameAssembler::Result r = FrameAssembler::Result::More;
        while (enc.active()) {
          const size_t len = enc.peek(frame, sizeof(frame));
          if (len > static_cast<size_t>(mtu) - 3 || len < FRAME_HEADER) ok = false;
          const uint16_t seq = static_cast<uint16_t>(frame[0] | (frame[1] << 8));
          if (seq != frames) ok = false;
          if (((frame[2] & FLAG_ERROR) != 0) != err) ok = false;
          r = as.push(frame, len);
          enc.advance();
          frames++;
          if ((r == FrameAssembler::Result::Done) != !enc.active()) ok = false;
        }
        const size_t expectFrames = n == 0 ? 1 : (n + chunk - 1) / chunk;
        if (frames != expectFrames || r != FrameAssembler::Result::Done || as.payload().size() != n ||
            memcmp(as.payload().data(), data, n) != 0 || as.error() != err) {
          ok = false;
        }
        if (!ok) failures++;
        out << "    mtu=" << mtu << " payload=" << n << " error=" << err << " frames=" << frames
            << " (expected " << expectFrames << ") " << (ok ? "OK" : "FAIL") << "\n";
      }
    }
  }
  // The assembler must reject a lost frame, a replayed frame and a runt.
  {
    FrameEncoder enc;
    enc.start(data, 100, false, 23);
    uint8_t f0[MAX_FRAME];
    uint8_t f2[MAX_FRAME];
    const size_t n0 = enc.peek(f0, sizeof(f0));
    enc.advance();
    enc.advance();
    const size_t n2 = enc.peek(f2, sizeof(f2));
    FrameAssembler as;
    const bool gap = as.push(f0, n0) == FrameAssembler::Result::More && as.push(f2, n2) == FrameAssembler::Result::Bad;
    FrameAssembler as2;
    const bool replay =
        as2.push(f0, n0) == FrameAssembler::Result::More && as2.push(f0, n0) == FrameAssembler::Result::Bad;
    FrameAssembler as3;
    const bool runt = as3.push(f0, 2) == FrameAssembler::Result::Bad;
    cases += 3;
    failures += !gap + !replay + !runt;
    out << "    lost frame rejected: " << (gap ? "OK" : "FAIL") << "\n";
    out << "    replayed frame rejected: " << (replay ? "OK" : "FAIL") << "\n";
    out << "    runt frame rejected: " << (runt ? "OK" : "FAIL") << "\n";
  }
  out << "    selftest: " << (cases - failures) << "/" << cases << " passed\n";
}

std::string trim(const std::string& s) {
  const size_t a = s.find_first_not_of(" \t\r\n");
  if (a == std::string::npos) return "";
  const size_t b = s.find_last_not_of(" \t\r\n");
  return s.substr(a, b - a + 1);
}

}  // namespace

void runLoopbackFromEnv() {
  const char* scriptPath = std::getenv("CROSSPOINT_SIM_BLE_LOOPBACK");
  if (!scriptPath || !scriptPath[0]) return;
  std::ifstream script(scriptPath);
  if (!script) {
    LOG_ERR("BLE", "loopback script not readable: %s", scriptPath);
    return;
  }
  std::ofstream file;
  const char* outPath = std::getenv("CROSSPOINT_SIM_BLE_LOOPBACK_OUT");
  if (outPath && outPath[0]) file.open(outPath);
  std::ostream& out = file.is_open() ? static_cast<std::ostream&>(file) : std::cout;

  Channel channel;
  if (!channel.begin()) {
    out << "!!! channel allocation failed\n";
    return;
  }
  Loop l;
  l.out = &out;
  out << "BLE loopback: service " << SERVICE_UUID << ", request " << REQUEST_UUID << ", response " << RESPONSE_UUID
      << "\n";
  std::string line;
  while (std::getline(script, line)) {
    line = trim(line);
    if (line.empty() || line[0] == '#') continue;
    if (line == "connect") {
      channel.resetSession();
      out << "=== connect (new session)\n";
    } else if (line.rfind("mtu ", 0) == 0) {
      l.mtu = static_cast<uint16_t>(std::atoi(line.c_str() + 4));
      out << "=== mtu " << l.mtu << "\n";
    } else if (line.rfind("cap ", 0) == 0) {
      channel.responder().setLimit(static_cast<size_t>(std::atol(line.c_str() + 4)));
      out << "=== cap " << channel.responder().capacity() << "\n";
    } else if (line.rfind("congest ", 0) == 0) {
      l.congestEvery = static_cast<size_t>(std::atol(line.c_str() + 8));
      out << "=== congest every " << l.congestEvery << "\n";
    } else if (line == "hex on" || line == "hex off") {
      l.hex = line == "hex on";
    } else if (line == "selftest") {
      selftest(out);
    } else if (line.rfind("login ", 0) == 0) {
      login(channel, l, trim(line.substr(6)));
      if (channel.shouldDisconnect()) {
        out << "=== transport drops the connection (too many failed auth attempts)\n";
        channel.resetSession();
      }
    } else if (line.rfind("follow ", 0) == 0) {
      follow(channel, l, line.substr(7));
    } else {
      exchange(channel, l, line, true);
      if (channel.shouldDisconnect()) {
        out << "=== transport drops the connection (too many failed auth attempts)\n";
        channel.resetSession();
      }
    }
  }
  out.flush();
  if (file.is_open()) file.close();
  channel.end();
  LOG_INF("BLE", "loopback script done");
  const char* quit = std::getenv("CROSSPOINT_SIM_BLE_LOOPBACK_EXIT");
  // _Exit: static destructors would race the SDL and web server threads.
  if (quit && quit[0] == '1') std::_Exit(0);
}

}  // namespace inklink::ble

#endif
