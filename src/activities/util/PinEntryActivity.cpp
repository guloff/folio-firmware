#include "PinEntryActivity.h"

#include <GfxRenderer.h>
#include <HalDisplay.h>

#include <algorithm>
#include <cstdio>
#include <utility>

#include "fontIds.h"
#include "inklink/PrivacyLock.h"

namespace privacy = inklink::privacy;

PinEntryActivity::PinEntryActivity(GfxRenderer& renderer, MappedInputManager& mappedInput, const Mode mode,
                                   const StrId titleId, const bool cancellable,
                                   std::function<void(const std::string& pin)> onSuccess, const bool deviceLock)
    : Activity("PinEntry", renderer, mappedInput),
      mode(mode),
      titleId(titleId),
      cancellable(cancellable),
      deviceLock(deviceLock),
      onSuccess(std::move(onSuccess)) {}

void PinEntryActivity::onEnter() {
  Activity::onEnter();
  layout();
  shownLockoutSec = lockoutSeconds();
  if (mode == Mode::Create) messageId = StrId::STR_PIN_LENGTH_HINT;
  requestUpdate(true);
}

uint32_t PinEntryActivity::lockoutSeconds() const {
  if (mode != Mode::Unlock) return 0;
  return (privacy::lockoutRemainingMs() + 999) / 1000;
}

void PinEntryActivity::layout() {
  const int w = renderer.getScreenWidth();
  const int h = renderer.getScreenHeight();
  const int margin = w / 10;
  const int gap = 14;
  const int top = h * 34 / 100;
  const int bottom = h - (cancellable ? 110 : 80);
  const int keyW = (w - 2 * margin - 2 * gap) / 3;
  const int keyH = std::min(110, (bottom - top - 3 * gap) / 4);
  for (int i = 0; i < KEY_COUNT; i++) {
    const int row = i / 3;
    const int col = i % 3;
    keys[i] = {margin + col * (keyW + gap), top + row * (keyH + gap), keyW, keyH};
  }
  const int cancelY = top + 4 * keyH + 3 * gap + 18;
  cancelBox = {margin, cancelY, w - 2 * margin, 56};
}

void PinEntryActivity::press(const int key) {
  if (key == KEY_ERASE) {
    if (!entered.empty()) entered.pop_back();
  } else if (key == KEY_OK) {
    submit();
    return;
  } else if (entered.size() < privacy::MAX_PIN && lockoutSeconds() == 0) {
    entered.push_back(static_cast<char>('0' + (key == KEY_ZERO ? 0 : key + 1)));
    if (messageId == StrId::STR_PIN_WRONG || messageId == StrId::STR_PIN_MISMATCH) messageId = StrId::STR_NONE_OPT;
  }
  requestUpdate();
}

void PinEntryActivity::submit() {
  if (entered.size() < privacy::MIN_PIN) {
    messageId = StrId::STR_PIN_LENGTH_HINT;
    requestUpdate();
    return;
  }
  if (mode == Mode::Create) {
    if (firstEntry.empty()) {
      firstEntry = entered;
      entered.clear();
      titleId = StrId::STR_PIN_REPEAT;
      messageId = StrId::STR_NONE_OPT;
    } else if (entered == firstEntry) {
      succeed();
      return;
    } else {
      firstEntry.clear();
      entered.clear();
      titleId = StrId::STR_PIN_NEW;
      messageId = StrId::STR_PIN_MISMATCH;
    }
    requestUpdate();
    return;
  }
  switch (privacy::verifyPin(entered.c_str())) {
    case privacy::Verify::Ok:
      succeed();
      return;
    case privacy::Verify::NoPin:
      // Reset while the pad was open: nothing left to check against.
      succeed();
      return;
    case privacy::Verify::Wrong:
    case privacy::Verify::LockedOut:
      entered.clear();
      messageId = StrId::STR_PIN_WRONG;
      shownLockoutSec = lockoutSeconds();
      requestUpdate();
      return;
  }
}

void PinEntryActivity::succeed() {
  if (done) return;
  done = true;
  if (onSuccess) {
    onSuccess(entered);
    return;
  }
  ActivityResult result{KeyboardResult{entered}};
  result.isCancelled = false;
  setResult(std::move(result));
  finish();
}

void PinEntryActivity::cancel() {
  if (!cancellable || done) return;
  done = true;
  ActivityResult result;
  result.isCancelled = true;
  setResult(std::move(result));
  finish();
}

void PinEntryActivity::loop() {
  if (done) return;
  // The PIN was reset over USB while the wake lock waited here.
  if (deviceLock && !privacy::deviceLockEnabled()) {
    succeed();
    return;
  }
  const uint32_t lockout = lockoutSeconds();
  if (lockout != shownLockoutSec) {
    shownLockoutSec = lockout;
    if (lockout == 0 && messageId == StrId::STR_PIN_WRONG) messageId = StrId::STR_NONE_OPT;
    requestUpdate();
  }

  int x = 0;
  int y = 0;
  if (mappedInput.wasScreenTapped(x, y)) {
    for (int i = 0; i < KEY_COUNT; i++) {
      if (keys[i].contains(x, y)) {
        focus = -1;
        press(i);
        return;
      }
    }
    if (cancellable && cancelBox.contains(x, y)) cancel();
    return;
  }

  using Button = MappedInputManager::Button;
  if (mappedInput.wasReleased(Button::Back)) {
    if (cancellable) {
      cancel();
    } else {
      press(KEY_ERASE);
    }
    return;
  }
  if (mappedInput.wasReleased(Button::Confirm)) {
    if (focus >= 0) {
      press(focus);
    } else {
      submit();
    }
    return;
  }
  // Side buttons walk the pad: 1 2 3 ... Erase 0 OK, wrapping.
  if (mappedInput.wasPressed(Button::Up) || mappedInput.wasPressed(Button::Left)) {
    focus = focus <= 0 ? KEY_COUNT - 1 : focus - 1;
    requestUpdate();
  } else if (mappedInput.wasPressed(Button::Down) || mappedInput.wasPressed(Button::Right)) {
    focus = focus < 0 || focus >= KEY_COUNT - 1 ? 0 : focus + 1;
    requestUpdate();
  }
}

void PinEntryActivity::render(RenderLock&&) {
  renderer.clearScreen();
  const int w = renderer.getScreenWidth();
  const int h = renderer.getScreenHeight();

  int y = h * 7 / 100;
  renderer.drawCenteredText(UI_12_FONT_ID, y, I18N.get(titleId), true, EpdFontFamily::BOLD);
  y += renderer.getLineHeight(UI_12_FONT_ID) + 12;

  char message[64] = "";
  if (shownLockoutSec > 0) {
    snprintf(message, sizeof(message), I18N.get(StrId::STR_PIN_WAIT), static_cast<unsigned>(shownLockoutSec));
  } else if (messageId != StrId::STR_NONE_OPT) {
    snprintf(message, sizeof(message), "%s", I18N.get(messageId));
  }
  if (message[0]) renderer.drawCenteredText(UI_10_FONT_ID, y, message, true);

  // One square per entered digit, nothing for the empty ones: the length is
  // all the screen gives away.
  const int dotsY = h * 22 / 100;
  constexpr int DOT = 22;
  constexpr int DOT_GAP = 18;
  const int count = static_cast<int>(entered.size());
  if (count > 0) {
    const int rowW = count * DOT + (count - 1) * DOT_GAP;
    int x = (w - rowW) / 2;
    for (int i = 0; i < count; i++, x += DOT + DOT_GAP) renderer.fillRoundedRect(x, dotsY, DOT, DOT, 6, Color::Black);
  } else {
    const int lineW = 4 * DOT + 3 * DOT_GAP;
    renderer.drawLine((w - lineW) / 2, dotsY + DOT, (w + lineW) / 2, dotsY + DOT, 2, true);
  }

  const bool locked = shownLockoutSec > 0;
  for (int i = 0; i < KEY_COUNT; i++) {
    const Box& k = keys[i];
    const bool focused = i == focus;
    if (focused) {
      renderer.fillRoundedRect(k.x, k.y, k.w, k.h, 14, Color::Black);
    } else {
      renderer.drawRoundedRect(k.x, k.y, k.w, k.h, 2, 14, true);
    }
    char label[24];
    int font = NOTOSANS_18_FONT_ID;
    if (i == KEY_ERASE) {
      snprintf(label, sizeof(label), "%s", I18N.get(StrId::STR_PIN_ERASE));
      font = UI_10_FONT_ID;
    } else if (i == KEY_OK) {
      snprintf(label, sizeof(label), "OK");
      font = UI_12_FONT_ID;
    } else {
      snprintf(label, sizeof(label), "%d", i == KEY_ZERO ? 0 : i + 1);
    }
    const int tw = renderer.getTextWidth(font, label, EpdFontFamily::BOLD);
    const int th = renderer.getLineHeight(font);
    const bool dim = locked && i != KEY_ERASE;
    renderer.drawText(font, k.x + (k.w - tw) / 2, k.y + (k.h - th) / 2, label, !focused && !dim, EpdFontFamily::BOLD);
    if (dim && !focused) {
      // A struck-through key reads as disabled without needing gray text.
      renderer.drawLine(k.x + k.w / 2 - 14, k.y + k.h / 2, k.x + k.w / 2 + 14, k.y + k.h / 2, 2, true);
    }
  }

  if (cancellable) {
    const char* label = I18N.get(StrId::STR_CANCEL);
    const int tw = renderer.getTextWidth(UI_12_FONT_ID, label);
    renderer.drawText(UI_12_FONT_ID, cancelBox.x + (cancelBox.w - tw) / 2, cancelBox.y + 12, label, true);
  } else if (deviceLock) {
    renderer.drawCenteredText(UI_10_FONT_ID, h - 56, I18N.get(StrId::STR_PIN_FORGOT_HINT), true);
  }
  renderer.displayBuffer(HalDisplay::RefreshMode::FAST_REFRESH);
}
