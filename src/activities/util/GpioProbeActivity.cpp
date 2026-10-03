#ifdef FOLIO_GPIO_PROBE

#include "GpioProbeActivity.h"

#include <Arduino.h>
#include <GfxRenderer.h>
#include <HalDisplay.h>
#include <HalStorage.h>
#include <driver/gpio.h>

#include <cstdio>

#include "fontIds.h"

namespace {

constexpr const char* LOG_PATH = "/gpio_probe.txt";
constexpr uint32_t POLL_MS = 25;
// A fast e-ink refresh takes ~300 ms; repainting more often only queues frames.
constexpr uint32_t RENDER_MIN_MS = 500;

// Unassigned on the X4 Pro board map (freeink-sdk BoardConfig XTEINK_X4_PRO):
// 15-17 and 45-48 are free, 43/44 are the UART0 pads, 45 is the unused SPI
// view of the SD slot. Flash and octal PSRAM own 26-37; USB owns 19/20.
GpioProbeActivity::Pin pins[] = {
    {15, true, "GPIO15"},
    {16, true, "GPIO16"},
    {17, true, "GPIO17"},
    {43, true, "GPIO43"},
    {44, true, "GPIO44"},
    {45, true, "GPIO45"},
    {46, true, "GPIO46"},
    {47, true, "GPIO47"},
    {48, true, "GPIO48"},
    // Assigned inputs, read as configured: a cover magnet could also be wired
    // onto the power key, like on some readers.
    {0, false, "GPIO0 Left"},
    {7, false, "GPIO7 Right"},
    {3, false, "GPIO3 Power"},
    {10, false, "GPIO10 Touch INT"},
    {21, false, "GPIO21 Charge"},
};

// H/L: driven high/low (follows neither pull); Z: floating (follows the pull).
char sample(const GpioProbeActivity::Pin& pin) {
  const auto g = static_cast<gpio_num_t>(pin.gpio);
  if (!pin.probePulls) return gpio_get_level(g) ? 'H' : 'L';
  gpio_set_pull_mode(g, GPIO_PULLUP_ONLY);
  delayMicroseconds(40);
  const int up = gpio_get_level(g);
  gpio_set_pull_mode(g, GPIO_PULLDOWN_ONLY);
  delayMicroseconds(40);
  const int down = gpio_get_level(g);
  if (up && down) return 'H';
  if (!up && !down) return 'L';
  return up ? 'Z' : '?';
}

void appendLog(const char* line) {
  HalFile f = Storage.open(LOG_PATH, O_RDWR | O_CREAT | O_APPEND);
  if (!f) {
    LOG_ERR("PROBE", "log open failed");
    return;
  }
  f.write(line, strlen(line));
  f.flush();
}

}  // namespace

void GpioProbeActivity::onEnter() {
  Activity::onEnter();
  for (auto& pin : pins) {
    if (!pin.probePulls) continue;
    const auto g = static_cast<gpio_num_t>(pin.gpio);
    gpio_reset_pin(g);
    gpio_set_direction(g, GPIO_MODE_INPUT);
  }
  poll(false);
  char line[48];
  snprintf(line, sizeof(line), "--- boot %s\n", CROSSPOINT_VERSION);
  appendLog(line);
  for (const auto& pin : pins) {
    snprintf(line, sizeof(line), "%lu %s start %c\n", static_cast<unsigned long>(millis()), pin.label, pin.state);
    appendLog(line);
  }
  requestUpdate(true);
}

void GpioProbeActivity::poll(const bool logChanges) {
  for (auto& pin : pins) {
    const char now = sample(pin);
    if (now == pin.state) continue;
    if (logChanges) {
      pin.changes++;
      totalChanges++;
      char line[48];
      snprintf(line, sizeof(line), "%lu %s %c->%c\n", static_cast<unsigned long>(millis()), pin.label, pin.state, now);
      LOG_INF("PROBE", "%s", line);
      appendLog(line);
    }
    pin.state = now;
    dirty = true;
  }
}

void GpioProbeActivity::loop() {
  const uint32_t now = millis();
  if (now - lastPollMs >= POLL_MS) {
    lastPollMs = now;
    poll(true);
  }
  if (dirty && now - lastRenderMs >= RENDER_MIN_MS) {
    lastRenderMs = now;
    dirty = false;
    requestUpdate();
  }
}

void GpioProbeActivity::render(RenderLock&&) {
  renderer.clearScreen();
  const int lineH = renderer.getLineHeight(UI_12_FONT_ID);
  const int smallH = renderer.getLineHeight(UI_10_FONT_ID);
  const int left = 24;
  int y = 20;
  renderer.drawText(UI_12_FONT_ID, left, y, "Диагностика крышки", true, EpdFontFamily::BOLD);
  y += lineH + 4;
  renderer.drawText(UI_10_FONT_ID, left, y, "Закройте и откройте крышку 3-4 раза,", true);
  y += smallH;
  renderer.drawText(UI_10_FONT_ID, left, y, "по 2-3 секунды в каждом положении.", true);
  y += smallH;
  renderer.drawText(UI_10_FONT_ID, left, y, "H/L - уровень, Z - вывод не подключён.", true);
  y += smallH + 10;

  char buf[48];
  for (const auto& pin : pins) {
    renderer.drawText(UI_12_FONT_ID, left, y, pin.label, true);
    snprintf(buf, sizeof(buf), "%c", pin.state);
    renderer.drawText(UI_12_FONT_ID, left + 230, y, buf, true, EpdFontFamily::BOLD);
    snprintf(buf, sizeof(buf), "смен: %u", pin.changes);
    renderer.drawText(UI_12_FONT_ID, left + 280, y, buf, true,
                      pin.changes ? EpdFontFamily::BOLD : EpdFontFamily::REGULAR);
    y += lineH;
  }
  y += 10;
  snprintf(buf, sizeof(buf), "Всего смен: %lu", static_cast<unsigned long>(totalChanges));
  renderer.drawText(UI_10_FONT_ID, left, y, buf, true);
  y += smallH;
  renderer.drawText(UI_10_FONT_ID, left, y, "Журнал: /gpio_probe.txt на SD-карте", true);
  renderer.displayBuffer(HalDisplay::RefreshMode::FAST_REFRESH);
}

#endif  // FOLIO_GPIO_PROBE
