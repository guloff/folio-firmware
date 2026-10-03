#pragma once

// PIN protection: one PIN guards both the device (asked on every wake while
// "Lock on wake" is on) and the books the owner marks as protected (asked
// when such a book is opened). The two unlocks are separate, so a device
// handed over after wake still keeps protected books closed, and both relock
// on sleep.
//
// The PIN's salted hash, the failure counter and the device-lock flag live in
// NVS (internal flash), so swapping the SD card neither resets nor reveals
// them. The protected-book list lives on the card: book protection hides a
// book on the device and from the companion app; the file itself stays on the
// card, reachable over USB or file transfer.
//
// A forgotten PIN is reset over USB serial with a challenge signed by the
// Folio release key (tools/inklink/reset_pin.py): only its holder can do it.

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

namespace inklink::privacy {

constexpr size_t MIN_PIN = 4;
constexpr size_t MAX_PIN = 8;

// ---- PIN -------------------------------------------------------------------

bool hasPin();
// Digits only, MIN_PIN..MAX_PIN long; resets the failure counter.
bool setPin(const char* pin);
// Removes the PIN and turns the wake lock off; protected books stay marked
// and ask for a new PIN to be set before they open.
void clearPin();

enum class Verify : uint8_t { Ok, Wrong, LockedOut, NoPin };
Verify verifyPin(const char* pin);
// While > 0, verifyPin() refuses without checking: 5 failures in a row cost
// 30 s, each further one doubles it up to 15 min. The counter survives
// restarts.
uint32_t lockoutRemainingMs();

// ---- device lock -------------------------------------------------------------

bool deviceLockEnabled();  // set and a PIN exists
void setDeviceLock(bool on);

// ---- session -----------------------------------------------------------------

// setup(): a silent restart (Wi-Fi exit, storage handoff) keeps the unlocks;
// any other boot, a wake from sleep included, starts locked.
void beginBoot(bool silentRestart);
// enterDeepSleep(): relock before the sleep screen is drawn.
void relock();
bool deviceUnlocked();
void unlockDevice();
bool booksUnlocked();
void unlockBooks();

// ---- protected books ---------------------------------------------------------

// After the SD card is up.
void loadBooks();
size_t protectedCount();
bool isProtected(const std::string& path);
// Protected and not unlocked this session: kept off home, recents, sleep
// screens and on-device highlight lists.
bool isHidden(const std::string& path);
// Opening `path` needs the PIN first. Also recognizes a protected book moved
// outside the device (matched by content) and follows it to its new path.
bool needsPin(const std::string& path);
bool protect(const std::string& path, const std::string& title);
bool unprotect(const std::string& path);
// File browser rename/move/delete keep the list pointing at the right files.
void onPathChanged(const std::string& from, const std::string& to);
void onPathRemoved(const std::string& path);

// Companion app (HTTP and BLE): protected books are never listed, whether or
// not this session unlocked them.
bool hiddenFromApp(const char* path);
// /screenshots/<sanitized title>/ folder of a protected book.
bool hiddenScreenshotFolder(const char* folderName);
// Merges the protected books' shelf memberships and statuses from `current`
// into an app-supplied library document that never saw them.
void mergeHiddenLibraryEntries(const char* currentJson, std::string& incomingJson);
// Library document with the protected books removed, for the app.
void filterLibraryForApp(std::string& json);

// ---- PIN reset -------------------------------------------------------------

// "CMD:PINRESET" prints a fresh challenge; "CMD:PINRESET:<signature hex>"
// clears the PIN if the signature over it verifies.
std::string resetChallengeHex();
bool resetWithSignatureHex(const char* signatureHex);

}  // namespace inklink::privacy
