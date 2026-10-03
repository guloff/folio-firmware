#pragma once

#include <I18n.h>

#include <functional>
#include <string>

#include "activities/Activity.h"

// Numeric PIN pad (inklink::privacy). Unlock checks the entered PIN against
// the stored one, with the failure lockout; Create asks for a new PIN twice.
//
// With an onSuccess continuation the pad hands control to it (the device lock
// screen and the protected-book gate navigate from there, Create receiving
// the new PIN); without one it
// finishes with a KeyboardResult carrying the PIN, or isCancelled.
class PinEntryActivity final : public Activity {
 public:
  enum class Mode : uint8_t { Unlock, Create };

  PinEntryActivity(GfxRenderer& renderer, MappedInputManager& mappedInput, Mode mode, StrId titleId, bool cancellable,
                   std::function<void(const std::string& pin)> onSuccess = nullptr, bool deviceLock = false);

  void onEnter() override;
  void loop() override;
  void render(RenderLock&&) override;
  // No home or light-panel gestures: they would walk around the lock.
  bool suppressesEdgeGestures() const override { return true; }

 private:
  static constexpr int KEY_COUNT = 12;
  static constexpr int KEY_ERASE = 9;
  static constexpr int KEY_ZERO = 10;
  static constexpr int KEY_OK = 11;

  struct Box {
    int x, y, w, h;
    bool contains(int px, int py) const { return px >= x && px < x + w && py >= y && py < y + h; }
  };

  Mode mode;
  StrId titleId;
  bool cancellable;
  bool deviceLock;
  std::function<void(const std::string& pin)> onSuccess;

  std::string entered;
  std::string firstEntry;  // Create: the first of the two entries
  StrId messageId = StrId::STR_NONE_OPT;
  int focus = -1;  // key under button navigation; -1 until a button is used
  uint32_t shownLockoutSec = 0;
  bool done = false;

  Box keys[KEY_COUNT]{};
  Box cancelBox{};

  void layout();
  void press(int key);
  void submit();
  void succeed();
  void cancel();
  uint32_t lockoutSeconds() const;
};
