#pragma once

// Diagnostic screen for the x4pro-gpio-probe build (FOLIO_GPIO_PROBE): shows the
// level of every GPIO the X4 Pro board map leaves unexplained, plus the known
// button/status inputs for reference, and counts changes. Closing and opening a
// magnetic cover reveals a hall sensor as the pin that toggles. Transitions are
// appended to /gpio_probe.txt on the SD card.

#include <cstdint>

#include "activities/Activity.h"

class GpioProbeActivity final : public Activity {
 public:
  struct Pin {
    uint8_t gpio;
    bool probePulls;  // unassigned pin: tell driven from floating by flipping the pulls
    const char* label;
    char state = '-';
    uint16_t changes = 0;
  };

  GpioProbeActivity(GfxRenderer& renderer, MappedInputManager& mappedInput)
      : Activity("GpioProbe", renderer, mappedInput) {}
  void onEnter() override;
  void loop() override;
  void render(RenderLock&&) override;
  bool preventAutoSleep() override { return true; }

 private:
  uint32_t lastPollMs = 0;
  uint32_t lastRenderMs = 0;
  bool dirty = true;
  uint32_t totalChanges = 0;

  void poll(bool logChanges);
};
