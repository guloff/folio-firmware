#pragma once

#include <cstdio>
#include <cstring>

// Release-tag comparison for OTA. Accepts "v0.2.0", "0.2.0", "0.2.0-x4pro",
// "1.6.5-rc+abc": a leading v/V is skipped and only the first three numeric
// segments count. Header-only so the host test can exercise it.
namespace firmware_version {

struct Semver {
  int major = 0;
  int minor = 0;
  int patch = 0;
};

inline bool parse(const char* text, Semver& out) {
  if (!text) return false;
  if (*text == 'v' || *text == 'V') text++;
  out = Semver{};
  return sscanf(text, "%d.%d.%d", &out.major, &out.minor, &out.patch) >= 2;
}

// True when `latest` (release tag) should replace the running `current`.
// Equal versions only update an RC build ("-rc" in current).
inline bool isNewer(const char* latest, const char* current) {
  Semver l;
  Semver c;
  if (!parse(latest, l) || !parse(current, c)) return false;
  if (l.major != c.major) return l.major > c.major;
  if (l.minor != c.minor) return l.minor > c.minor;
  if (l.patch != c.patch) return l.patch > c.patch;
  return strstr(current, "-rc") != nullptr;
}

}  // namespace firmware_version
