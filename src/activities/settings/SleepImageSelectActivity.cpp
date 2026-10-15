#include "SleepImageSelectActivity.h"

#include <FsHelpers.h>
#include <HalStorage.h>
#include <I18n.h>

#include <algorithm>

#include "CrossPointSettings.h"
#include "MappedInputManager.h"
#include "components/UITheme.h"
#include "inklink/SleepScreens.h"

namespace fui = freeink::ui;

SleepImageSelectActivity::SleepImageSelectActivity(GfxRenderer& renderer, MappedInputManager& mappedInput)
    : UiListActivity("SleepImageSelect", renderer, mappedInput) {}

void SleepImageSelectActivity::onEnter() {
  UiListActivity::onEnter();
  names.clear();
  names.reserve(MAX_IMAGES + 1);
  names.emplace_back();  // Random

  const char* dir = Storage.exists("/.sleep") ? "/.sleep" : "/sleep";
  HalFile root = Storage.open(dir);
  if (root && root.isDirectory()) {
    char name[256];
    for (HalFile f = root.openNextFile(); f && names.size() <= MAX_IMAGES; f = root.openNextFile()) {
      if (f.isDirectory()) continue;
      f.getName(name, sizeof(name));
      if (name[0] == '.' || !FsHelpers::hasBmpExtension(name)) continue;
      names.emplace_back(name);
    }
  }
  std::sort(names.begin() + 1, names.end());

  selected = inklink::sleep::selectedImage(dir).empty() ? std::string() : inklink::sleep::selectedName();
  const auto it = std::find(names.begin(), names.end(), selected);
  nav.selected = it != names.end() ? static_cast<int>(std::distance(names.begin(), it)) : 0;
  rebuildRows();
}

void SleepImageSelectActivity::rebuildRows() {
  rows.clear();
  rows.reserve(names.size());
  for (size_t i = 0; i < names.size(); i++) {
    fui::ListItem item;
    item.label = i == 0 ? tr(STR_SLEEP_IMAGE_RANDOM) : names[i].c_str();
    if (names[i] == selected) item.value = tr(STR_SELECTED);
    item.actionValue = static_cast<int16_t>(i);
    rows.push_back(item);
  }
}

const char* SleepImageSelectActivity::headerTitle() const { return tr(STR_SLEEP_IMAGE); }

void SleepImageSelectActivity::activateIndex(const int index) {
  if (index < 0 || index >= static_cast<int>(names.size())) return;
  app.clearTapFlash();
  nav.selected = index;
  if (!inklink::sleep::selectImage(names[index].c_str())) return;
  selected = names[index];
  // A picked picture only shows on screens that draw the sleep folder.
  using Mode = CrossPointSettings::SLEEP_SCREEN_MODE;
  const uint8_t mode = SETTINGS.sleepScreen;
  if (!selected.empty() && mode != Mode::CUSTOM && mode != Mode::COVER_CUSTOM && mode != Mode::INKLINK_ROTATE) {
    SETTINGS.sleepScreen = Mode::CUSTOM;
    SETTINGS.saveToFile();
  }
  rebuildRows();
  requestUpdate();
}

void SleepImageSelectActivity::buildScreen(UiScreen& screen) {
  const auto& metrics = UITheme::getInstance().getMetrics();
  const Rect safe = UITheme::getInstance().getScreenSafeArea(renderer, true, false);
  screen.setContentMarginFromScreen(fui::Insets{
      static_cast<int16_t>(safe.y + metrics.topPadding + metrics.headerHeight),
      static_cast<int16_t>(renderer.getScreenWidth() - (safe.x + safe.width)),
      static_cast<int16_t>(renderer.getScreenHeight() - (safe.y + safe.height)), static_cast<int16_t>(safe.x)});
  screen.spacer(static_cast<int16_t>(metrics.verticalSpacing));

  fui::ListProps props;
  props.items = rows.data();
  props.count = static_cast<uint16_t>(rows.size());
  props.action = ACTION_ROW;
  props.inputMask = fui::InputTouch;
  props.labelText = screen.theme().smallText;
  props.labelText.maxLines = 2;
  syncListViewport(screen, props);
  screen.list(props);
}
