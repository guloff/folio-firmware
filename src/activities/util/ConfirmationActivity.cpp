#include "ConfirmationActivity.h"

#include <I18n.h>

#include "HalDisplay.h"
#include "components/UITheme.h"

ConfirmationActivity::ConfirmationActivity(GfxRenderer& renderer, MappedInputManager& mappedInput,
                                           const std::string& heading, const std::string& body,
                                           const char* confirmLabel, const bool acknowledgeOnly)
    : Activity("Confirmation", renderer, mappedInput),
      heading(heading),
      body(body),
      confirmLabel(confirmLabel ? confirmLabel : I18N.get(StrId::STR_CONFIRM)),
      acknowledgeOnly(acknowledgeOnly) {}

void ConfirmationActivity::onEnter() {
  Activity::onEnter();

  // Both texts live inside the dialog: the heading as its caption and the
  // subject (a book title) as the wrapping headline beneath it. No
  // pre-truncation — the dialog wraps both to its own width.
  const char* options[] = {I18N.get(StrId::STR_CANCEL), confirmLabel};
  const char* const* shown = acknowledgeOnly ? options + 1 : options;
  const int confirmIndex = acknowledgeOnly ? 0 : 1;
  confirmPopup.show(heading.c_str(), body.c_str(), shown, acknowledgeOnly ? 1 : 2, 0, [this, confirmIndex](int idx) {
    ActivityResult res;
    res.isCancelled = (idx != confirmIndex);
    setResult(std::move(res));
    finish();
  });

  requestUpdate(true);
}

void ConfirmationActivity::render(RenderLock&& lock) {
  renderer.clearScreen();

  if (confirmPopup.processRender(renderer, mappedInput)) return;

  renderer.displayBuffer(HalDisplay::RefreshMode::FAST_REFRESH);
}

void ConfirmationActivity::loop() {
  if (confirmPopup.handleInput(mappedInput, [this] { requestUpdate(); })) return;

  // Popup dismissed without a selection (Back button or tap outside): cancel.
  ActivityResult res;
  res.isCancelled = true;
  setResult(std::move(res));
  finish();
}
