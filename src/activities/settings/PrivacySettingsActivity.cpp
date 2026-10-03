#include "PrivacySettingsActivity.h"

#include <I18n.h>
#include <Memory.h>

#include <string>

#include "activities/util/PinEntryActivity.h"
#include "components/UITheme.h"
#include "inklink/PrivacyLock.h"

namespace fui = freeink::ui;
namespace privacy = inklink::privacy;

namespace {
enum Row { ROW_PIN = 0, ROW_REMOVE = 1, ROW_WAKE_LOCK = 2, ROW_BOOKS = 3 };
const StrId rowNames[PrivacySettingsActivity::MENU_ITEMS] = {
    StrId::STR_PIN_CODE, StrId::STR_PIN_REMOVE, StrId::STR_PIN_LOCK_ON_WAKE, StrId::STR_PIN_PROTECTED_BOOKS};
}  // namespace

PrivacySettingsActivity::PrivacySettingsActivity(GfxRenderer& renderer, MappedInputManager& mappedInput)
    : UiListActivity("PrivacySettings", renderer, mappedInput) {
  for (int i = 0; i < MENU_ITEMS; i++) {
    rowItems_[i].label = I18N.get(rowNames[i]);
    rowItems_[i].actionValue = static_cast<int16_t>(i);
  }
}

const char* PrivacySettingsActivity::headerTitle() const { return tr(STR_PRIVACY); }

void PrivacySettingsActivity::withCurrentPin(void (PrivacySettingsActivity::*then)()) {
  auto pad = makeUniqueNoThrow<PinEntryActivity>(renderer, mappedInput, PinEntryActivity::Mode::Unlock,
                                                 StrId::STR_PIN_CURRENT, /*cancellable=*/true);
  if (!pad) {
    LOG_ERR("SET", "OOM: PIN pad");
    return;
  }
  startActivityForResult(std::move(pad), [this, then](const ActivityResult& result) {
    if (!result.isCancelled) (this->*then)();
    requestUpdate();
  });
}

void PrivacySettingsActivity::createPin(const bool enableLockAfter) {
  auto pad = makeUniqueNoThrow<PinEntryActivity>(renderer, mappedInput, PinEntryActivity::Mode::Create,
                                                 StrId::STR_PIN_NEW, /*cancellable=*/true);
  if (!pad) {
    LOG_ERR("SET", "OOM: PIN pad");
    return;
  }
  startActivityForResult(std::move(pad), [this, enableLockAfter](const ActivityResult& result) {
    if (!result.isCancelled && privacy::setPin(std::get<KeyboardResult>(result.data).text.c_str())) {
      if (enableLockAfter) privacy::setDeviceLock(true);
      // Setting the PIN here is the owner at work: protected books stay open
      // until the next sleep.
      privacy::unlockBooks();
    }
    requestUpdate();
  });
}

void PrivacySettingsActivity::changePin() { createPin(/*enableLockAfter=*/false); }

void PrivacySettingsActivity::removePin() { privacy::clearPin(); }

void PrivacySettingsActivity::disableLock() { privacy::setDeviceLock(false); }

void PrivacySettingsActivity::activateIndex(const int index) {
  app.clearTapFlash();
  const bool hasPin = privacy::hasPin();
  switch (index) {
    case ROW_PIN:
      if (hasPin) {
        withCurrentPin(&PrivacySettingsActivity::changePin);
      } else {
        createPin(/*enableLockAfter=*/false);
      }
      break;
    case ROW_REMOVE:
      if (hasPin) withCurrentPin(&PrivacySettingsActivity::removePin);
      break;
    case ROW_WAKE_LOCK:
      if (privacy::deviceLockEnabled()) {
        withCurrentPin(&PrivacySettingsActivity::disableLock);
      } else if (hasPin) {
        privacy::setDeviceLock(true);
        requestUpdate();
      } else {
        createPin(/*enableLockAfter=*/true);
      }
      break;
    default:
      break;  // the count is informational
  }
}

void PrivacySettingsActivity::buildScreen(UiScreen& screen) {
  const auto& metrics = UITheme::getInstance().getMetrics();
  screen.setContentMarginFromScreen(fui::Insets{static_cast<int16_t>(metrics.topPadding + metrics.headerHeight), 0,
                                                static_cast<int16_t>(metrics.buttonHintsHeight), 0});
  screen.spacer(static_cast<int16_t>(metrics.verticalSpacing));

  const bool hasPin = privacy::hasPin();
  rowValues_[ROW_PIN] = hasPin ? tr(STR_PIN_CHANGE) : tr(STR_PIN_SET);
  rowValues_[ROW_REMOVE] = hasPin ? "" : tr(STR_NOT_SET);
  rowValues_[ROW_WAKE_LOCK] = privacy::deviceLockEnabled() ? tr(STR_STATE_ON) : tr(STR_STATE_OFF);
  rowValues_[ROW_BOOKS] = std::to_string(privacy::protectedCount());
  for (int i = 0; i < MENU_ITEMS; i++) {
    rowItems_[i].value = rowValues_[i].empty() ? nullptr : rowValues_[i].c_str();
  }

  fui::ListProps props;
  props.items = rowItems_;
  props.count = static_cast<uint16_t>(MENU_ITEMS);
  props.action = ACTION_ROW;
  props.inputMask = fui::InputTouch;
  props.valueInset = 8;
  props.labelText = screen.theme().smallText;
  props.labelText.maxLines = 2;
  syncListViewport(screen, props);
  screen.list(props);
}
