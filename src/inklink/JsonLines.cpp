#include "JsonLines.h"

#include <HalStorage.h>
#include <Logging.h>
#include <Memory.h>

#include <cstring>
#include <memory>
#include <string>

namespace inklink::jsonl {

namespace {

void ensureParentDir(const char* path) {
  const char* slash = strrchr(path, '/');
  if (!slash || slash == path) return;
  std::string dir(path, static_cast<size_t>(slash - path));
  Storage.ensureDirectoryExists(dir.c_str());
}

// Reads the file line by line into a fixed buffer; overlong lines are skipped.
template <typename OnLine>
bool readLines(const char* path, OnLine&& onLine) {
  if (!Storage.exists(path)) return true;
  HalFile f = Storage.open(path, O_RDONLY);
  if (!f) {
    LOG_ERR("INKLINK", "open failed: %s", path);
    return false;
  }
  auto line = makeUniqueNoThrow<char[]>(MAX_LINE);
  auto chunk = makeUniqueNoThrow<char[]>(512);
  if (!line || !chunk) {
    LOG_ERR("INKLINK", "OOM reading %s", path);
    return false;
  }
  size_t len = 0;
  bool overflow = false;
  bool keepGoing = true;
  int n;
  while (keepGoing && (n = f.read(chunk.get(), 512)) > 0) {
    for (int i = 0; i < n && keepGoing; i++) {
      const char c = chunk[i];
      if (c == '\n') {
        if (!overflow && len > 0) {
          line[len] = '\0';
          keepGoing = onLine(line.get(), len);
        }
        len = 0;
        overflow = false;
      } else if (len + 1 < MAX_LINE) {
        line[len++] = c;
      } else {
        overflow = true;
      }
    }
  }
  // Trailing line without newline (torn write or hand-edited file).
  if (keepGoing && !overflow && len > 0) {
    line[len] = '\0';
    onLine(line.get(), len);
  }
  return true;
}

}  // namespace

bool append(const char* path, const JsonDocument& doc) {
  ensureParentDir(path);
  std::string out;
  serializeJson(doc, out);
  out.push_back('\n');
  HalFile f = Storage.open(path, O_WRONLY | O_CREAT | O_APPEND);
  if (!f) {
    LOG_ERR("INKLINK", "append open failed: %s", path);
    return false;
  }
  const size_t written = f.write(out.data(), out.size());
  f.flush();
  if (written != out.size()) {
    LOG_ERR("INKLINK", "short append to %s", path);
    return false;
  }
  return true;
}

bool forEach(const char* path, LineFn fn, void* ctx) {
  JsonDocument doc;
  return readLines(path, [&](const char* line, size_t len) {
    doc.clear();
    if (deserializeJson(doc, line, len) != DeserializationError::Ok) return true;  // skip torn line
    if (!doc.is<JsonObject>()) return true;
    return fn(doc.as<JsonObjectConst>(), ctx);
  });
}

bool rewrite(const char* path, RewriteFn fn, void* ctx) {
  if (!Storage.exists(path)) return true;
  std::string tmpPath = std::string(path) + ".tmp";
  Storage.remove(tmpPath.c_str());
  bool ok = true;
  {
    HalFile out = Storage.open(tmpPath.c_str(), O_WRONLY | O_CREAT | O_TRUNC);
    if (!out) {
      LOG_ERR("INKLINK", "tmp open failed: %s", tmpPath.c_str());
      return false;
    }
    JsonDocument in;
    JsonDocument next;
    std::string buf;
    ok = readLines(path, [&](const char* line, size_t len) {
      in.clear();
      if (deserializeJson(in, line, len) != DeserializationError::Ok || !in.is<JsonObject>()) return true;
      next.clear();
      next.set(in.as<JsonObjectConst>());
      if (!fn(in.as<JsonObjectConst>(), next, ctx)) return true;
      buf.clear();
      serializeJson(next, buf);
      buf.push_back('\n');
      if (out.write(buf.data(), buf.size()) != buf.size()) {
        ok = false;
        return false;
      }
      return true;
    }) && ok;
    out.flush();
    out.close();
  }
  if (!ok) {
    Storage.remove(tmpPath.c_str());
    return false;
  }
  // SdFat rename does not overwrite: drop the original first (same crash
  // window as ProgressFile — the .tmp survives and is the newer copy).
  Storage.remove(path);
  if (!Storage.rename(tmpPath.c_str(), path)) {
    LOG_ERR("INKLINK", "rename failed: %s", path);
    return false;
  }
  return true;
}

}  // namespace inklink::jsonl
