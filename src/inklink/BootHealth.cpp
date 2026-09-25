#include "BootHealth.h"

#include <Arduino.h>
#include <Logging.h>

#if !defined(SIMULATOR) && defined(CONFIG_APP_ROLLBACK_ENABLE)
#include <esp_ota_ops.h>

// Overrides the Arduino core's weak default (false = confirm during init).
extern "C" bool verifyRollbackLater() { return true; }
#endif

namespace inklink::boot {

void confirmIfHealthy() {
  static bool done = false;
  if (done || millis() < HEALTHY_AFTER_MS) return;
  done = true;
#if !defined(SIMULATOR) && defined(CONFIG_APP_ROLLBACK_ENABLE)
  const esp_partition_t* running = esp_ota_get_running_partition();
  esp_ota_img_states_t state;
  if (running && esp_ota_get_state_partition(running, &state) == ESP_OK && state == ESP_OTA_IMG_PENDING_VERIFY) {
    if (esp_ota_mark_app_valid_cancel_rollback() == ESP_OK) {
      LOG_INF("BOOT", "Firmware image confirmed after %lu ms", millis());
    } else {
      LOG_ERR("BOOT", "Failed to confirm firmware image");
    }
  }
#endif
}

}  // namespace inklink::boot
