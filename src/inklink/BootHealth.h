#pragma once

// OTA rollback guard. The X4 Pro bootloader has app rollback enabled; the
// Arduino core would confirm a freshly flashed image before setup() even runs,
// which makes rollback useless. Folio defers that: the image is confirmed only
// after the main loop has run for HEALTHY_AFTER_MS, so a build that crashes
// during boot is rolled back to the previous slot by the bootloader.
namespace inklink::boot {

constexpr unsigned long HEALTHY_AFTER_MS = 10000;

// Call from loop(); confirms the running image once, when it has proven itself.
void confirmIfHealthy();

}  // namespace inklink::boot
