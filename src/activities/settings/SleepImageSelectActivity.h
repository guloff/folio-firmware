#pragma once

#include <GfxRenderer.h>

#include <string>
#include <vector>

#include "activities/UiListActivity.h"

class MappedInputManager;

// Settings → Display → Sleep Image: which picture of the sleep folder the
// "Custom" sleep screen shows every time, or "Random". The same choice
// FolioLink makes (inklink::sleep::selectImage); picking an image also
// switches a sleep screen that never shows pictures to Custom.
class SleepImageSelectActivity final : public UiListActivity {
 public:
  explicit SleepImageSelectActivity(GfxRenderer& renderer, MappedInputManager& mappedInput);

  void onEnter() override;

 private:
  static constexpr size_t MAX_IMAGES = 100;

  int listCount() const override { return static_cast<int>(rows.size()); }
  void buildScreen(UiScreen& screen) override;
  void activateIndex(int index) override;
  const char* headerTitle() const override;
  void rebuildRows();

  // names[0] is "" (random); the rest are file names in the sleep folder.
  std::vector<std::string> names;
  std::vector<freeink::ui::ListItem> rows;
  std::string selected;
};
