#pragma once

#include <string>

#include "activities/UiListActivity.h"

// Settings → System → Protection: the PIN, the wake lock and the count of
// protected books (books are protected from the file browser or reader menu).
// Changing or removing the PIN and turning the wake lock off ask for the
// current PIN.
class PrivacySettingsActivity final : public UiListActivity {
 public:
  explicit PrivacySettingsActivity(GfxRenderer& renderer, MappedInputManager& mappedInput);

  static constexpr int MENU_ITEMS = 4;

 private:
  int listCount() const override { return MENU_ITEMS; }
  void buildScreen(UiScreen& screen) override;
  void activateIndex(int index) override;
  const char* headerTitle() const override;

  // Runs `then` once the current PIN is entered.
  void withCurrentPin(void (PrivacySettingsActivity::*then)());
  void createPin(bool enableLockAfter);
  void changePin();
  void removePin();
  void disableLock();

  std::string rowValues_[MENU_ITEMS];
  freeink::ui::ListItem rowItems_[MENU_ITEMS]{};
};
