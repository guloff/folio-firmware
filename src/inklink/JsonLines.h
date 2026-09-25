#pragma once

#include <ArduinoJson.h>

#include <cstddef>

// Append-only JSON Lines files on the SD card: one compact JSON object per
// line. Appends never rewrite earlier data, so a crash mid-write loses at most
// the line being written, and a torn trailing line is skipped on read.
namespace inklink::jsonl {

constexpr size_t MAX_LINE = 2048;

// Serializes doc as one line and appends it (creating parent dirs).
bool append(const char* path, const JsonDocument& doc);

// Calls fn(obj, ctx) for every parseable line, in file order. fn returns false
// to stop early. Returns false only when the file exists but can't be opened.
using LineFn = bool (*)(JsonObjectConst obj, void* ctx);
bool forEach(const char* path, LineFn fn, void* ctx);

// Rewrites the file through a .tmp: fn(obj, out, ctx) decides per line whether
// to keep it (return true) and may modify `out` (a copy of obj). Returns false
// on I/O error, leaving the original intact.
using RewriteFn = bool (*)(JsonObjectConst obj, JsonDocument& out, void* ctx);
bool rewrite(const char* path, RewriteFn fn, void* ctx);

}  // namespace inklink::jsonl
