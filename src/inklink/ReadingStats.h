#pragma once

#include <ctime>
#include <cstdint>
#include <string>
#include <vector>

// Reading-session journal: every time a book is open in a reader, one session
// accumulates active reading time and page turns; on reader exit (including
// the exit that precedes deep sleep) it is appended to
// /.crosspoint/inklink/sessions.jsonl. Everything else — per-day totals,
// streaks, per-book time — is derived from that file, so there is exactly one
// source of truth and no aggregate that can drift.
namespace inklink {

struct DayTotal {
  uint32_t day;  // yyyymmdd local
  uint32_t secs;
  uint32_t pages;
};

struct StatsSummary {
  uint32_t todaySecs = 0;
  uint32_t todayPages = 0;
  uint32_t currentStreak = 0;  // consecutive days (ending today or yesterday) with reading
  uint32_t longestStreak = 0;
  uint32_t totalSecs = 0;
  uint32_t totalPages = 0;
  uint32_t sessions = 0;
  uint32_t booksFinished = 0;  // distinct books whose session ended at >= 100 %
};

struct BookTotal {
  std::string path;
  uint32_t secs = 0;
  uint32_t pages = 0;
  time_t lastRead = 0;
  int lastPercent = -1;
};

class ReadingStats {
 public:
  static constexpr const char* SESSIONS_PATH = "/.crosspoint/inklink/sessions.jsonl";
  // A pause longer than this between page turns counts only this much: the
  // reader put the device down without sleeping it.
  static constexpr uint32_t IDLE_CAP_MS = 3UL * 60UL * 1000UL;
  // Sessions shorter than both of these are noise (opened by mistake).
  static constexpr uint32_t MIN_SESSION_SECS = 20;
  // A day counts toward a streak once this much reading happened.
  static constexpr uint32_t STREAK_MIN_SECS = 60;

  static ReadingStats& get();

  void beginSession(const std::string& bookPath, const std::string& title);
  void notePageTurn(bool forward);
  // Closes and persists the open session (no-op when none is open).
  void endSession(int endPercent);
  bool sessionOpen() const { return open_; }

  // Per-day totals, ascending by day, only days with activity. Sessions with an
  // unknown date (clock never set) are excluded here but still count in totals.
  bool loadDays(std::vector<DayTotal>& out) const;
  bool summarize(StatsSummary& out, std::vector<DayTotal>* daysOut = nullptr) const;
  bool loadBookTotals(std::vector<BookTotal>& out) const;

 private:
  ReadingStats() = default;
  void accrue();

  bool open_ = false;
  std::string path_;
  std::string title_;
  time_t startUtc_ = 0;
  uint32_t activeMs_ = 0;
  uint32_t lastInteractionMs_ = 0;
  uint32_t pages_ = 0;
};

}  // namespace inklink
