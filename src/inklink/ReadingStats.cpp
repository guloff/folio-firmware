#include "ReadingStats.h"

#include <Arduino.h>
#include <ArduinoJson.h>
#include <Logging.h>

#include <algorithm>
#include <map>
#include <set>

#include "InkLinkClock.h"
#include "JsonLines.h"

namespace inklink {

ReadingStats& ReadingStats::get() {
  static ReadingStats instance;
  return instance;
}

void ReadingStats::beginSession(const std::string& bookPath, const std::string& title) {
  if (open_) endSession(-1);
  open_ = true;
  path_ = bookPath;
  title_ = title;
  startUtc_ = 0;
  clock::nowUtc(startUtc_);
  activeMs_ = 0;
  pages_ = 0;
  lastInteractionMs_ = millis();
  LOG_DBG("INKLINK", "session start: %s", bookPath.c_str());
}

void ReadingStats::accrue() {
  const uint32_t now = millis();
  const uint32_t delta = now - lastInteractionMs_;
  activeMs_ += std::min(delta, IDLE_CAP_MS);
  lastInteractionMs_ = now;
}

void ReadingStats::notePageTurn(const bool forward) {
  if (!open_) return;
  accrue();
  if (forward) pages_++;
}

void ReadingStats::endSession(const int endPercent) {
  if (!open_) return;
  open_ = false;
  accrue();
  const uint32_t secs = activeMs_ / 1000;
  if (secs < MIN_SESSION_SECS && pages_ == 0) {
    LOG_DBG("INKLINK", "session dropped (too short: %us)", static_cast<unsigned>(secs));
    return;
  }
  // A session opened before the phone set the clock can still get a date if
  // the clock became valid meanwhile: back-date from now.
  if (startUtc_ == 0) {
    time_t now = 0;
    if (clock::nowUtc(now)) startUtc_ = now - static_cast<time_t>(secs);
  }
  JsonDocument doc;
  doc["b"] = path_.c_str();
  doc["t"] = title_.c_str();
  doc["s"] = static_cast<int64_t>(startUtc_);
  doc["d"] = secs;
  doc["p"] = pages_;
  doc["c"] = endPercent;
  doc["day"] = clock::dayOf(startUtc_);
  if (!jsonl::append(SESSIONS_PATH, doc)) {
    LOG_ERR("INKLINK", "failed to persist session");
    return;
  }
  LOG_INF("INKLINK", "session saved: %us, %u pages, %d%%", static_cast<unsigned>(secs), static_cast<unsigned>(pages_),
          endPercent);
}

namespace {

struct DayCtx {
  std::map<uint32_t, DayTotal>* days;
  StatsSummary* summary;
  std::set<std::string>* finished;
};

bool collectDay(JsonObjectConst obj, void* raw) {
  auto* ctx = static_cast<DayCtx*>(raw);
  const uint32_t secs = obj["d"] | 0u;
  const uint32_t pages = obj["p"] | 0u;
  const uint32_t day = obj["day"] | 0u;
  if (ctx->summary) {
    ctx->summary->totalSecs += secs;
    ctx->summary->totalPages += pages;
    ctx->summary->sessions++;
    if ((obj["c"] | -1) >= 100 && ctx->finished) ctx->finished->insert(obj["b"] | "");
  }
  if (day != 0 && ctx->days) {
    auto& t = (*ctx->days)[day];
    t.day = day;
    t.secs += secs;
    t.pages += pages;
  }
  return true;
}

}  // namespace

bool ReadingStats::loadDays(std::vector<DayTotal>& out) const {
  std::map<uint32_t, DayTotal> days;
  DayCtx ctx{&days, nullptr, nullptr};
  if (!jsonl::forEach(SESSIONS_PATH, collectDay, &ctx)) return false;
  out.clear();
  out.reserve(days.size());
  for (const auto& kv : days) out.push_back(kv.second);
  return true;
}

bool ReadingStats::summarize(StatsSummary& out, std::vector<DayTotal>* daysOut) const {
  out = StatsSummary{};
  std::map<uint32_t, DayTotal> days;
  std::set<std::string> finished;
  DayCtx ctx{&days, &out, &finished};
  if (!jsonl::forEach(SESSIONS_PATH, collectDay, &ctx)) return false;
  out.booksFinished = static_cast<uint32_t>(finished.size());

  const uint32_t today = clock::today();
  if (today != 0) {
    auto it = days.find(today);
    if (it != days.end()) {
      out.todaySecs = it->second.secs;
      out.todayPages = it->second.pages;
    }
  }

  // Streaks over days that meet the threshold.
  int32_t prevOrd = INT32_MIN;
  uint32_t run = 0;
  for (const auto& kv : days) {
    if (kv.second.secs < STREAK_MIN_SECS) continue;
    const int32_t ord = clock::dayToOrdinal(kv.first);
    run = (prevOrd != INT32_MIN && ord == prevOrd + 1) ? run + 1 : 1;
    out.longestStreak = std::max(out.longestStreak, run);
    prevOrd = ord;
  }
  // The current streak survives until the end of the day after the last read.
  if (today != 0 && prevOrd != INT32_MIN) {
    const int32_t todayOrd = clock::dayToOrdinal(today);
    if (prevOrd == todayOrd || prevOrd == todayOrd - 1) out.currentStreak = run;
  }

  if (daysOut) {
    daysOut->clear();
    daysOut->reserve(days.size());
    for (const auto& kv : days) daysOut->push_back(kv.second);
  }
  return true;
}

namespace {

bool collectBook(JsonObjectConst obj, void* raw) {
  auto* books = static_cast<std::map<std::string, BookTotal>*>(raw);
  const char* path = obj["b"] | "";
  if (!path[0]) return true;
  auto& b = (*books)[path];
  if (b.path.empty()) b.path = path;
  b.secs += obj["d"] | 0u;
  b.pages += obj["p"] | 0u;
  const time_t start = static_cast<time_t>(obj["s"] | static_cast<int64_t>(0));
  if (start >= b.lastRead) {
    b.lastRead = start;
    b.lastPercent = obj["c"] | -1;
  }
  return true;
}

}  // namespace

bool ReadingStats::loadBookTotals(std::vector<BookTotal>& out) const {
  std::map<std::string, BookTotal> books;
  if (!jsonl::forEach(SESSIONS_PATH, collectBook, &books)) return false;
  out.clear();
  out.reserve(books.size());
  for (auto& kv : books) out.push_back(std::move(kv.second));
  return true;
}

}  // namespace inklink
