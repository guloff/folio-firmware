#include "InkLinkClock.h"

#include <HalClock.h>
#include <Logging.h>

#include <cstdio>

#include "CrossPointSettings.h"
#include "util/Timezones.h"

namespace inklink::clock {

bool nowUtc(time_t& out) {
  time_t t = 0;
  if (!halClock.utcNow(t) || t < MIN_VALID_EPOCH) return false;
  out = t;
  return true;
}

uint32_t dayOf(const time_t utc) {
  if (utc <= 0) return 0;
  struct tm local;
  localtime_r(&utc, &local);
  return static_cast<uint32_t>((local.tm_year + 1900) * 10000 + (local.tm_mon + 1) * 100 + local.tm_mday);
}

uint32_t today() {
  time_t now = 0;
  return nowUtc(now) ? dayOf(now) : 0;
}

// Howard Hinnant's days_from_civil / civil_from_days.
int32_t dayToOrdinal(const uint32_t day) {
  int y = static_cast<int>(day / 10000);
  const unsigned m = (day / 100) % 100;
  const unsigned d = day % 100;
  y -= m <= 2;
  const int era = (y >= 0 ? y : y - 399) / 400;
  const unsigned yoe = static_cast<unsigned>(y - era * 400);
  const unsigned doy = (153u * (m + (m > 2 ? -3 : 9)) + 2u) / 5u + d - 1u;
  const unsigned doe = yoe * 365u + yoe / 4u - yoe / 100u + doy;
  return era * 146097 + static_cast<int32_t>(doe) - 719468;
}

uint32_t ordinalToDay(int32_t z) {
  z += 719468;
  const int32_t era = (z >= 0 ? z : z - 146096) / 146097;
  const unsigned doe = static_cast<unsigned>(z - era * 146097);
  const unsigned yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365;
  int y = static_cast<int>(yoe) + era * 400;
  const unsigned doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
  const unsigned mp = (5 * doy + 2) / 153;
  const unsigned d = doy - (153 * mp + 2) / 5 + 1;
  const unsigned m = mp < 10 ? mp + 3 : mp - 9;
  y += m <= 2;
  return static_cast<uint32_t>(y) * 10000 + m * 100 + d;
}

void formatDay(const uint32_t day, char* buf, const size_t bufSize) {
  if (bufSize == 0) return;
  if (day == 0) {
    buf[0] = '\0';
    return;
  }
  snprintf(buf, bufSize, "%04u-%02u-%02u", static_cast<unsigned>(day / 10000), static_cast<unsigned>((day / 100) % 100),
           static_cast<unsigned>(day % 100));
}

bool setFromCompanion(const time_t utc, const int tzOffsetMinutes, const bool applyOffset) {
  if (utc < MIN_VALID_EPOCH) return false;
  if (!halClock.setUtc(utc)) return false;

  bool settingsDirty = false;
  if (!SETTINGS.clockHasBeenSynced) {
    SETTINGS.clockHasBeenSynced = 1;
    settingsDirty = true;
  }
  // A named zone the user picked on the device wins (it knows DST rules); a
  // never-chosen zone follows the phone's current offset.
  // Real-world offsets span UTC-12:00..UTC+14:00; the legacy setting stores
  // quarter hours biased by 48 (0..104).
  const bool offsetValid = tzOffsetMinutes >= -12 * 60 && tzOffsetMinutes <= 14 * 60;
  if (applyOffset && !offsetValid) LOG_ERR("INKLINK", "Ignoring timezone offset %d min", tzOffsetMinutes);
  if (applyOffset && offsetValid && SETTINGS.clockTimezone == 255) {
    const int rounded = tzOffsetMinutes >= 0 ? (tzOffsetMinutes + 7) / 15 : -((-tzOffsetMinutes + 7) / 15);
    const int q = 48 + rounded;
    if (SETTINGS.clockUtcOffsetQ != q) {
      SETTINGS.clockUtcOffsetQ = static_cast<uint8_t>(q);
      settingsDirty = true;
    }
    timezones::applyToClock();
  }
  if (settingsDirty) SETTINGS.saveToFile();
  LOG_INF("INKLINK", "Clock set from companion (offset %d min)", tzOffsetMinutes);
  return true;
}

}  // namespace inklink::clock
