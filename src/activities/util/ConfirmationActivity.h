#pragma once
#include <functional>
#include <string>

#include "activities/Activity.h"
#include "components/OptionPopup.h"

class ConfirmationActivity : public Activity {
 private:
  // Input data
  std::string heading;
  std::string body;
  const char* confirmLabel;    // tr() text for the affirmative option
  const bool acknowledgeOnly;  // one option: a notice the reader dismisses

  OptionPopup confirmPopup;

 public:
  // `confirmLabel` names the action ("Install", "Reset"); null = generic Confirm.
  // `acknowledgeOnly` drops Cancel, for a notice with a single "Got it".
  ConfirmationActivity(GfxRenderer& renderer, MappedInputManager& mappedInput, const std::string& heading,
                       const std::string& body, const char* confirmLabel = nullptr, bool acknowledgeOnly = false);

  void onEnter() override;
  void loop() override;
  void render(RenderLock&& lock) override;
};