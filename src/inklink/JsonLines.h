#pragma once

#include <ArduinoJson.h>

#include <cstddef>
#include <string>

// Append-only JSON Lines files on the SD card: one compact JSON object per
// line. Appends never rewrite earlier data, so a crash mid-write loses at most
// the line being written; a torn trailing line is terminated before the next
// append so it cannot swallow the following record.
namespace inklink::jsonl {

// Longest line that is parsed. Longer lines are never dropped: reads skip
// them (logged) and rewrites copy them through byte for byte.
constexpr size_t MAX_LINE = 16 * 1024;

// Serializes doc as one line and appends it (creating parent dirs).
bool append(const char* path, const JsonDocument& doc);

// Calls fn(obj, ctx) for every parseable line, in file order. fn returns false
// to stop early. Returns false when the file exists but can't be read.
using LineFn = bool (*)(JsonObjectConst obj, void* ctx);
bool forEach(const char* path, LineFn fn, void* ctx);

// True when the file is absent (nothing to read) or can be opened.
bool readable(const char* path);

// Rewrites the file through a .tmp: fn(obj, out, ctx) decides per line whether
// to keep it (return true) and may modify `out` (a copy of obj). Lines that
// can't be parsed are kept unchanged. Returns false on I/O error, leaving the
// original intact.
using RewriteFn = bool (*)(JsonObjectConst obj, JsonDocument& out, void* ctx);
bool rewrite(const char* path, RewriteFn fn, void* ctx);

// Crash-safe whole-file replace: write <path>.tmp, then swap it in. A swap
// interrupted by power loss is completed by recoverTmp() on the next access.
bool writeAtomic(const char* path, const std::string& content);

// If <path> is missing but <path>.tmp exists (interrupted swap), promotes the
// .tmp. Call before reading a file maintained with rewrite()/writeAtomic().
void recoverTmp(const char* path);

}  // namespace inklink::jsonl
