#pragma once

#include <cstdint>
#include <ctime>

// InkLink time helpers on top of HalClock. The RTC keeps UTC; "day" values are
// local calendar days encoded as yyyymmdd (e.g. 20260926), which sort and
// compare as plain integers.
namespace inklink::clock {

// Anything before this is an RTC that was never set (PCF8563 resets to 2000).
constexpr time_t MIN_VALID_EPOCH = 1735689600;  // 2025-01-01T00:00:00Z

// Current UTC epoch; false when there is no RTC or it holds an unset time.
bool nowUtc(time_t& out);

// Local calendar day for a UTC epoch (yyyymmdd), 0 for epoch <= 0.
uint32_t dayOf(time_t utc);

// Local calendar day for "now", 0 when the clock is not valid.
uint32_t today();

// yyyymmdd -> days since 1970-01-01 (for streak arithmetic).
int32_t dayToOrdinal(uint32_t day);

// days since 1970-01-01 -> yyyymmdd.
uint32_t ordinalToDay(int32_t ordinal);

// "2026-09-26" into buf (>= 11 bytes); empty string for day 0.
void formatDay(uint32_t day, char* buf, size_t bufSize);

// Sets the RTC from a trusted UTC epoch and applies a fixed UTC offset as the
// timezone when the user hasn't chosen a named zone. Returns false on failure.
bool setFromCompanion(time_t utc, int tzOffsetMinutes, bool applyOffset);

}  // namespace inklink::clock
