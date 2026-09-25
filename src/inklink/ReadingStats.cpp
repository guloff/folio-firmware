#include "ReadingStats.h"

#include <Arduino.h>
#include <ArduinoJson.h>
#include <HalStorage.h>
#include <Logging.h>

#include <algorithm>
#include <climits>
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

void ReadingStats::accrue(const bool closing) {
  const uint32_t now = millis();
  const uint32_t delta = now - lastInteractionMs_;
  if (delta <= IDLE_CAP_MS) {
    activeMs_ += delta;
  } else if (!closing) {
    // A long pause before a page turn: count one capped stretch of reading.
    activeMs_ += IDLE_CAP_MS;
  }
  // A long pause before closing (e.g. auto-sleep) was the device lying idle.
  lastInteractionMs_ = now;
}

void ReadingStats::notePageTurn(const bool forward) {
  if (!open_) return;
  accrue(false);
  if (forward) pages_++;
}

void ReadingStats::endSession(const int endPercent) {
  if (!open_) return;
  open_ = false;
  accrue(true);
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

struct AggCtx {
  std::map<uint32_t, DayTotal>* days;
  std::map<std::string, BookTotal>* books;
  StatsSummary* totals;
  std::set<std::string>* finished;
};

bool collect(JsonObjectConst obj, void* raw) {
  auto* ctx = static_cast<AggCtx*>(raw);
  const uint32_t secs = obj["d"] | 0u;
  const uint32_t pages = obj["p"] | 0u;
  const uint32_t day = obj["day"] | 0u;
  const char* path = obj["b"] | "";
  const int percent = obj["c"] | -1;
  ctx->totals->totalSecs += secs;
  ctx->totals->totalPages += pages;
  ctx->totals->sessions++;
  if (percent >= 100 && path[0]) ctx->finished->insert(path);
  if (day != 0) {
    auto& t = (*ctx->days)[day];
    t.day = day;
    t.secs += secs;
    t.pages += pages;
  }
  if (path[0]) {
    auto& b = (*ctx->books)[path];
    if (b.path.empty()) b.path = path;
    b.secs += secs;
    b.pages += pages;
    const time_t start = static_cast<time_t>(obj["s"] | static_cast<int64_t>(0));
    if (start >= b.lastRead) {
      b.lastRead = start;
      b.lastPercent = percent;
    }
  }
  return true;
}

}  // namespace

bool ReadingStats::refreshCache() const {
  int64_t size = 0;
  jsonl::recoverTmp(SESSIONS_PATH);
  if (Storage.exists(SESSIONS_PATH)) {
    HalFile f = Storage.open(SESSIONS_PATH, O_RDONLY);
    if (!f) {
      LOG_ERR("INKLINK", "sessions journal unreadable");
      return false;
    }
    size = static_cast<int64_t>(f.fileSize());
  }
  if (size == cachedSize) return true;

  std::map<uint32_t, DayTotal> days;
  std::map<std::string, BookTotal> books;
  std::set<std::string> finished;
  StatsSummary totals;
  AggCtx ctx{&days, &books, &totals, &finished};
  if (!jsonl::forEach(SESSIONS_PATH, collect, &ctx)) return false;
  totals.booksFinished = static_cast<uint32_t>(finished.size());

  cachedDays.clear();
  cachedDays.reserve(days.size());
  for (const auto& kv : days) cachedDays.push_back(kv.second);
  cachedBooks.clear();
  cachedBooks.reserve(books.size());
  for (auto& kv : books) cachedBooks.push_back(std::move(kv.second));
  cachedTotals = totals;
  cachedSize = size;
  return true;
}

bool ReadingStats::summarize(StatsSummary& out, std::vector<DayTotal>* daysOut) const {
  out = StatsSummary{};
  if (!refreshCache()) return false;
  out.totalSecs = cachedTotals.totalSecs;
  out.totalPages = cachedTotals.totalPages;
  out.sessions = cachedTotals.sessions;
  out.booksFinished = cachedTotals.booksFinished;

  const uint32_t today = clock::today();
  if (today != 0) {
    auto it = std::lower_bound(cachedDays.begin(), cachedDays.end(), today,
                               [](const DayTotal& d, uint32_t v) { return d.day < v; });
    if (it != cachedDays.end() && it->day == today) {
      out.todaySecs = it->secs;
      out.todayPages = it->pages;
    }
  }

  // Streaks over days that meet the threshold; days after "today" (a clock
  // that was wrong for a while) are ignored.
  int32_t prevOrd = INT32_MIN;
  uint32_t run = 0;
  for (const auto& d : cachedDays) {
    if (d.secs < STREAK_MIN_SECS || (today != 0 && d.day > today)) continue;
    const int32_t ord = clock::dayToOrdinal(d.day);
    run = (prevOrd != INT32_MIN && ord == prevOrd + 1) ? run + 1 : 1;
    out.longestStreak = std::max(out.longestStreak, run);
    prevOrd = ord;
  }
  // The current streak survives until the end of the day after the last read.
  if (today != 0 && prevOrd != INT32_MIN) {
    const int32_t todayOrd = clock::dayToOrdinal(today);
    if (prevOrd == todayOrd || prevOrd == todayOrd - 1) out.currentStreak = run;
  }

  if (daysOut) *daysOut = cachedDays;
  return true;
}

bool ReadingStats::loadBookTotals(std::vector<BookTotal>& out) const {
  if (!refreshCache()) return false;
  out = cachedBooks;
  return true;
}

BookTotal ReadingStats::bookTotal(const std::string& path) const {
  if (refreshCache()) {
    for (const auto& b : cachedBooks) {
      if (b.path == path) return b;
    }
  }
  BookTotal none;
  none.path = path;
  return none;
}

}  // namespace inklink
