#include "BootHealth.h"

#include <Arduino.h>
#include <Logging.h>

#if !defined(SIMULATOR) && defined(CONFIG_APP_ROLLBACK_ENABLE)
#include <esp_ota_ops.h>
#define INKLINK_ROLLBACK_GUARD 1

// Overrides the Arduino core's weak default (false = confirm during init).
extern "C" bool verifyRollbackLater() { return true; }
#endif

namespace inklink::boot {

namespace {

bool done = false;

void confirmNow(const char* reason) {
  if (done) return;
  done = true;
#ifdef INKLINK_ROLLBACK_GUARD
  const esp_partition_t* running = esp_ota_get_running_partition();
  esp_ota_img_states_t state;
  if (!running || esp_ota_get_state_partition(running, &state) != ESP_OK) {
    LOG_INF("BOOT", "Image state unavailable (no OTA data); rollback guard inactive");
    return;
  }
  if (state != ESP_OTA_IMG_PENDING_VERIFY) {
    LOG_INF("BOOT", "Image state %d, nothing to confirm", static_cast<int>(state));
    return;
  }
  if (esp_ota_mark_app_valid_cancel_rollback() == ESP_OK) {
    LOG_INF("BOOT", "Firmware image confirmed (%s)", reason);
  } else {
    LOG_ERR("BOOT", "Failed to confirm firmware image");
  }
#else
  (void)reason;
#endif
}

}  // namespace

void confirmIfHealthy() {
  static unsigned long firstLoopMs = 0;
  if (done) return;
  const unsigned long now = millis();
  if (firstLoopMs == 0) firstLoopMs = now != 0 ? now : 1;
  if (now - firstLoopMs >= HEALTHY_AFTER_MS) confirmNow("healthy runtime");
}

void confirmBeforeSleep() { confirmNow("entering sleep after boot"); }

}  // namespace inklink::boot
