#pragma once

#include <cstdint>
#include <cstdlib>

// Bluetooth page turner (setting "Bluetooth remote", off by default): pairs
// with a BLE HID remote — page-turner clickers, camera-shutter buttons, small
// keyboards — and turns its keys into page turns. It runs on the BLE sync
// stack's NimBLE host as a central next to the app's peripheral; BleSync
// starts the stack when either setting is on.
//
// Pairing: with no bonded remote the reader connects to the first HID device
// it sees advertising (put the remote in pairing mode); once bonded it only
// reconnects to bonded remotes. Switching the setting off and on again forgets
// them.
//
// Threading: everything that touches the host runs on the NimBLE host task;
// the main loop only takes the decoded turn.
namespace inklink::bleremote {

// Main loop: +1 next page, -1 previous page, 0 none. Consumes the event.
int8_t takePageTurn();
// A remote is connected and its reports are subscribed.
bool connected();

// Report -> turn, exposed for host tests: an 8-byte boot keyboard report
// (modifiers, reserved, 6 keycodes) or a 1-3 byte consumer-control report
// (16-bit usage, little endian). 0 for no key or an unmapped one.
int8_t decodeReport(const uint8_t* data, size_t len);

}  // namespace inklink::bleremote

// BleSync.cpp only (host task, except where noted).
namespace inklink::bleremote::host {

// Main loop, before the host task starts: `forgetBonds` clears bonded remotes
// on the host's first sync (the setting was just switched on).
void prepare(bool enabled, bool forgetBonds);
// Host synced: start scanning when enabled.
void onSync(uint8_t ownAddrType);
// Host stopping: drop the link state.
void onStop();

}  // namespace inklink::bleremote::host
