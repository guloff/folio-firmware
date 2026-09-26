#pragma once

// BLE sync channel for the InkLink app (setting "Bluetooth sync", off by
// default). The radio runs only in builds with FOLIO_BLE_SYNC (X4 Pro); on
// other targets these are no-ops, and the simulator additionally offers a
// loopback runner (CROSSPOINT_SIM_BLE_LOOPBACK, see BleSync.cpp).
//
// Threading: NimBLE callbacks run in the NimBLE host task and only copy the
// request into a one-slot mailbox. Requests are executed and answered from
// loop() on the main loop, the same thread that serves the HTTP API, so the SD
// card and the in-RAM stores are never touched from the host task.
namespace inklink::blesync {

// After boot (settings loaded): starts BLE when the setting is on.
void begin();
// Main loop: follows the setting, runs a pending request, sends queued frames.
void loop();
// Radio off until the next reboot (Wi-Fi screens, deep sleep).
void shutdown();
// A request is being answered: keeps the device from auto-sleeping.
bool busy();

}  // namespace inklink::blesync
