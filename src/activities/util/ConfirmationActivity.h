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
  const char* confirmLabel;  // tr() text for the affirmative option

  OptionPopup confirmPopup;

 public:
  // `confirmLabel` names the action ("Install", "Reset"); null = generic Confirm.
  ConfirmationActivity(GfxRenderer& renderer, MappedInputManager& mappedInput, const std::string& heading,
                       const std::string& body, const char* confirmLabel = nullptr);

  void onEnter() override;
  void loop() override;
  void render(RenderLock&& lock) override;
};