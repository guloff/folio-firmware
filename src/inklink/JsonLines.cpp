#include "JsonLines.h"

#include <HalStorage.h>
#include <Logging.h>
#include <Memory.h>

#include <cstring>
#include <memory>
#include <string>

namespace inklink::jsonl {

namespace {

constexpr size_t CHUNK = 512;

void ensureParentDir(const char* path) {
  const char* slash = strrchr(path, '/');
  if (!slash || slash == path) return;
  std::string dir(path, static_cast<size_t>(slash - path));
  Storage.ensureDirectoryExists(dir.c_str());
}

std::string tmpPathFor(const char* path) { return std::string(path) + ".tmp"; }

// Streams the file line by line. onLine(line, len, parseable) gets every line;
// lines longer than MAX_LINE arrive with parseable=false and are delivered in
// pieces through onRaw so callers can copy them through unchanged.
template <typename OnLine, typename OnRaw>
bool readLines(const char* path, OnLine&& onLine, OnRaw&& onRaw) {
  recoverTmp(path);
  if (!Storage.exists(path)) return true;
  HalFile f = Storage.open(path, O_RDONLY);
  if (!f) {
    LOG_ERR("INKLINK", "open failed: %s", path);
    return false;
  }
  auto chunk = makeUniqueNoThrow<char[]>(CHUNK);
  std::string line;
  if (!chunk) {
    LOG_ERR("INKLINK", "OOM reading %s", path);
    return false;
  }
  line.reserve(1024);
  bool overflow = false;
  bool keepGoing = true;
  int n;
  while (keepGoing && (n = f.read(chunk.get(), CHUNK)) > 0) {
    for (int i = 0; i < n && keepGoing; i++) {
      const char c = chunk[i];
      if (c == '\n') {
        if (overflow) {
          onRaw(line.data(), line.size(), true);
          LOG_ERR("INKLINK", "skipped overlong line in %s", path);
        } else if (!line.empty()) {
          keepGoing = onLine(line.c_str(), line.size());
        }
        line.clear();
        overflow = false;
      } else {
        line.push_back(c);
        if (line.size() >= MAX_LINE) {
          // Hand the piece to the raw sink and keep going; the line is never parsed.
          onRaw(line.data(), line.size(), false);
          line.clear();
          overflow = true;
        }
      }
    }
  }
  // Trailing line without newline (torn write or hand-edited file).
  if (keepGoing) {
    if (overflow) {
      onRaw(line.data(), line.size(), true);
    } else if (!line.empty()) {
      onLine(line.c_str(), line.size());
    }
  }
  return true;
}

}  // namespace

void recoverTmp(const char* path) {
  const std::string tmp = tmpPathFor(path);
  if (!Storage.exists(path) && Storage.exists(tmp.c_str())) {
    if (Storage.rename(tmp.c_str(), path)) {
      LOG_INF("INKLINK", "recovered %s from interrupted rewrite", path);
    } else {
      LOG_ERR("INKLINK", "could not recover %s", path);
    }
  }
}

bool readable(const char* path) {
  recoverTmp(path);
  if (!Storage.exists(path)) return true;
  HalFile f = Storage.open(path, O_RDONLY);
  return static_cast<bool>(f);
}

bool append(const char* path, const JsonDocument& doc) {
  ensureParentDir(path);
  recoverTmp(path);
  std::string out;
  serializeJson(doc, out);
  out.push_back('\n');
  HalFile f = Storage.open(path, O_RDWR | O_CREAT | O_APPEND);
  if (!f) {
    LOG_ERR("INKLINK", "append open failed: %s", path);
    return false;
  }
  // Terminate a torn last line so this record stays on its own line.
  const size_t size = f.fileSize();
  if (size > 0 && f.seekSet(size - 1)) {
    char last = 0;
    if (f.read(&last, 1) == 1 && last != '\n') {
      f.write("\n", 1);
      LOG_ERR("INKLINK", "terminated torn line in %s", path);
    }
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
  return readLines(
      path,
      [&](const char* line, size_t len) {
        doc.clear();
        if (deserializeJson(doc, line, len) != DeserializationError::Ok || !doc.is<JsonObject>()) {
          LOG_ERR("INKLINK", "skipped unparseable line in %s", path);
          return true;
        }
        return fn(doc.as<JsonObjectConst>(), ctx);
      },
      [](const char*, size_t, bool) {});
}

bool rewrite(const char* path, RewriteFn fn, void* ctx) {
  recoverTmp(path);
  if (!Storage.exists(path)) return true;
  const std::string tmpPath = tmpPathFor(path);
  Storage.remove(tmpPath.c_str());  // stale: the original exists, so this .tmp is older
  bool ok = true;
  {
    HalFile out = Storage.open(tmpPath.c_str(), O_WRONLY | O_CREAT | O_TRUNC);
    if (!out) {
      LOG_ERR("INKLINK", "tmp open failed: %s", tmpPath.c_str());
      return false;
    }
    auto writeAll = [&](const char* data, size_t len) {
      if (ok && out.write(data, len) != len) ok = false;
    };
    JsonDocument in;
    JsonDocument next;
    std::string buf;
    const bool readOk = readLines(
        path,
        [&](const char* line, size_t len) {
          in.clear();
          if (deserializeJson(in, line, len) != DeserializationError::Ok || !in.is<JsonObject>()) {
            // Keep what we can't understand rather than deleting it.
            writeAll(line, len);
            writeAll("\n", 1);
            return ok;
          }
          next.clear();
          next.set(in.as<JsonObjectConst>());
          if (!fn(in.as<JsonObjectConst>(), next, ctx)) return true;
          buf.clear();
          serializeJson(next, buf);
          buf.push_back('\n');
          writeAll(buf.data(), buf.size());
          return ok;
        },
        [&](const char* data, size_t len, bool end) {
          writeAll(data, len);
          if (end) writeAll("\n", 1);
        });
    ok = ok && readOk;
    out.flush();
    out.close();
  }
  if (!ok) {
    Storage.remove(tmpPath.c_str());
    LOG_ERR("INKLINK", "rewrite failed, original kept: %s", path);
    return false;
  }
  // SdFat rename does not overwrite: drop the original, then promote the .tmp.
  // Power loss in between leaves only the .tmp, which recoverTmp() promotes.
  Storage.remove(path);
  if (!Storage.rename(tmpPath.c_str(), path)) {
    LOG_ERR("INKLINK", "rename failed (data kept in %s)", tmpPath.c_str());
    return false;
  }
  return true;
}

bool writeAtomic(const char* path, const std::string& content) {
  ensureParentDir(path);
  recoverTmp(path);
  const std::string tmpPath = tmpPathFor(path);
  Storage.remove(tmpPath.c_str());
  {
    HalFile out = Storage.open(tmpPath.c_str(), O_WRONLY | O_CREAT | O_TRUNC);
    if (!out) {
      LOG_ERR("INKLINK", "tmp open failed: %s", tmpPath.c_str());
      return false;
    }
    const size_t written = out.write(content.data(), content.size());
    out.flush();
    out.close();
    if (written != content.size()) {
      Storage.remove(tmpPath.c_str());
      LOG_ERR("INKLINK", "short write: %s", tmpPath.c_str());
      return false;
    }
  }
  Storage.remove(path);
  if (!Storage.rename(tmpPath.c_str(), path)) {
    LOG_ERR("INKLINK", "rename failed (data kept in %s)", tmpPath.c_str());
    return false;
  }
  return true;
}

}  // namespace inklink::jsonl
