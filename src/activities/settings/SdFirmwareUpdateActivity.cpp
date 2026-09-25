#include "SdFirmwareUpdateActivity.h"

#include <Arduino.h>
#include <GfxRenderer.h>
#include <HalStorage.h>
#include <I18n.h>
#include <Logging.h>
#include <esp_ota_ops.h>

#include "MappedInputManager.h"
#include "activities/home/FileBrowserActivity.h"
#include "activities/util/ConfirmationActivity.h"
#include "components/UITheme.h"
#include "fontIds.h"
#include "inklink/FirmwareSignature.h"
#include "network/FirmwareFlasher.h"

void SdFirmwareUpdateActivity::onEnter() {
  Activity::onEnter();
  // Build-identity marker — confirms which firmware build owns the SD update flow.
  LOG_INF("FW", "SdFirmwareUpdateActivity build=%s %s recovery=%d", __DATE__, __TIME__, recoveryMode ? 1 : 0);
  state = State::PICKING;
  if (!preselectedPath.empty()) {
    startWithPath(preselectedPath);
    return;
  }
  launchPicker();
}

void SdFirmwareUpdateActivity::launchPicker() {
  // Reuse the standard file browser, restricted to .bin files only.
  startActivityForResult(
      std::make_unique<FileBrowserActivity>(renderer, mappedInput, "/", FileBrowserActivity::Mode::PickFirmware),
      [this](const ActivityResult& result) { onPickerResult(result); });
}

void SdFirmwareUpdateActivity::onPickerResult(const ActivityResult& result) {
  if (result.isCancelled) {
    if (recoveryMode) {
      // Recovery mode: re-launch the picker so the user cannot escape into a half-initialised UI.
      launchPicker();
      return;
    }
    finish();
    return;
  }

  const auto* path = std::get_if<FilePathResult>(&result.data);
  if (!path) {
    LOG_ERR("FW", "Picker returned no path");
    finish();
    return;
  }
  startWithPath(path->path);
}

void SdFirmwareUpdateActivity::startWithPath(const std::string& path) {
  firmwarePath = path;
  LOG_DBG("FW", "Selected: %s", firmwarePath.c_str());

  {
    RenderLock lock(*this);
    state = State::VALIDATING;
  }
  requestUpdateAndWait();

  if (!validateFirmware()) {
    RenderLock lock(*this);
    state = State::FAILED;
    requestUpdate();
    return;
  }

  promptConfirmation();
}

bool SdFirmwareUpdateActivity::validateFirmware() {
  HalFile file;
  if (!Storage.openFileForRead("FW", firmwarePath.c_str(), file) || !file) {
    errorMessage = tr(STR_FIRMWARE_FILE_OPEN_FAILED);
    return false;
  }
  firmwareSize = file.fileSize();
  file.close();

  // Resolve the next-update partition directly via the OTA API. Previously this
  // probed via Update.begin(firmwareSize)/Update.abort() to learn the partition
  // size, which had the side effect of erasing partition state and was wasted
  // work since we only need the size bound for validation here.
  const esp_partition_t* dest = esp_ota_get_next_update_partition(nullptr);
  if (!dest) {
    LOG_ERR("FW", "no next-update partition available");
    errorMessage = tr(STR_INVALID_FIRMWARE);
    return false;
  }
  const size_t partitionLimit = dest->size;
  if (firmwareSize > partitionLimit) {
    LOG_ERR("FW", "firmware (%u bytes) exceeds partition (%u bytes)", static_cast<unsigned>(firmwareSize),
            static_cast<unsigned>(partitionLimit));
    errorMessage = tr(STR_FIRMWARE_TOO_LARGE);
    return false;
  }

  // Run the same end-to-end integrity check (header / segment table / XOR checksum / SHA256
  // trailer) that the shared firmware-flasher applies right before raw-writing otadata. This
  // catches truncated or corrupted .bin files at confirmation time, before the user ever sees
  // the "Updating…" progress bar.
  const auto vr = firmware_flash::validateImageFile(firmwarePath.c_str(), partitionLimit);
  if (vr != firmware_flash::Result::OK) {
    LOG_ERR("FW", "image validation failed: %s", firmware_flash::resultName(vr));
    if (vr == firmware_flash::Result::TOO_LARGE) {
      errorMessage = tr(STR_FIRMWARE_TOO_LARGE);
    } else if (vr == firmware_flash::Result::TOO_SMALL) {
      errorMessage = tr(STR_FIRMWARE_TOO_SMALL);
    } else if (vr == firmware_flash::Result::BAD_CHIP || vr == firmware_flash::Result::WRONG_BOARD) {
      errorMessage = tr(STR_FIRMWARE_WRONG_DEVICE);
    } else {
      errorMessage = tr(STR_INVALID_FIRMWARE);
    }
    return false;
  }

  inklink::fwsig::AppInfo info;
  if (inklink::fwsig::readAppInfo(firmwarePath.c_str(), info) && info.projectName[0]) {
    imageLabel = std::string(info.projectName) + " " + info.version;
  } else {
    imageLabel = tr(STR_FW_UNKNOWN_IMAGE);
  }

  // A present-but-wrong signature is always fatal; a missing one only when the
  // caller requires it (companion app). Manual SD updates may be unsigned.
  const auto sig = inklink::fwsig::verifyFile(firmwarePath.c_str());
  signatureValid = sig == inklink::fwsig::Status::VALID;
  LOG_INF("FW", "image %s, signature %s", imageLabel.c_str(), inklink::fwsig::statusName(sig));
  if (sig == inklink::fwsig::Status::INVALID || sig == inklink::fwsig::Status::ERROR) {
    errorMessage = tr(STR_FW_SIGNATURE_INVALID);
    return false;
  }
  if (sig == inklink::fwsig::Status::MISSING && requireSignature) {
    errorMessage = tr(STR_FW_SIGNATURE_MISSING);
    return false;
  }
  return true;
}

void SdFirmwareUpdateActivity::promptConfirmation() {
  {
    RenderLock lock(*this);
    state = State::CONFIRMING;
  }
  // Heading and image identity; an unsigned image is announced in the bold
  // heading so it can't be missed.
  std::string heading;
  std::string body;
  if (signatureValid) {
    heading = tr(STR_FIRMWARE_UPDATE_PROMPT);
    body = imageLabel + " \xC2\xB7 " + tr(STR_FW_SIGNATURE_VALID);
  } else {
    heading = std::string(tr(STR_FW_UNSIGNED)) + " \xC2\xB7 " + tr(STR_FIRMWARE_UPDATE_PROMPT);
    body = imageLabel + " \xC2\xB7 " + tr(STR_FW_UNSIGNED_HINT);
  }

  startActivityForResult(std::make_unique<ConfirmationActivity>(renderer, mappedInput, heading, body),
                         [this](const ActivityResult& result) { onConfirmationResult(result); });
}

void SdFirmwareUpdateActivity::onConfirmationResult(const ActivityResult& result) {
  if (result.isCancelled) {
    if (recoveryMode) {
      // Go back to the picker rather than exiting recovery.
      launchPicker();
      return;
    }
    finish();
    return;
  }

  {
    RenderLock lock(*this);
    state = State::UPDATING;
    writtenBytes = 0;
    lastRenderedPercent = 101;
  }
  requestUpdateAndWait();
  performUpdate();
}

void SdFirmwareUpdateActivity::performUpdate() {
  LOG_INF("FW", "SD update: %s (%u bytes)", firmwarePath.c_str(), static_cast<unsigned>(firmwareSize));

  auto progressCb = +[](size_t written, size_t total, void* ctx) {
    auto* self = static_cast<SdFirmwareUpdateActivity*>(ctx);
    self->writtenBytes = written;
    self->firmwareSize = total;
    // immediate=true: wake the render task directly. We're in a tight sync
    // loop so the main loop won't drain the requestedUpdate flag for us.
    self->requestUpdate(true);
  };

  // Re-validate at flash time (TOCTOU): SD is removable, so don't trust the
  // pre-confirmation pass. The alreadyValidated parameter on the API stays
  // for callers (e.g. an OTA staging path) where the same byte stream was
  // just hashed and there's no removable-media gap.
  const auto result = firmware_flash::flashFromSdPath(firmwarePath.c_str(), progressCb, this);
  if (result != firmware_flash::Result::OK) {
    LOG_ERR("FW", "flash failed: %s", firmware_flash::resultName(result));
    // BAD_CHIP / WRONG_BOARD here is the TOCTOU re-validation catching a
    // wrong-device image the pre-confirmation pass missed (e.g. the SD card
    // was swapped).
    errorMessage = result == firmware_flash::Result::BAD_CHIP || result == firmware_flash::Result::WRONG_BOARD
                       ? tr(STR_FIRMWARE_WRONG_DEVICE)
                       : tr(STR_FIRMWARE_WRITE_FAILED);
    RenderLock lock(*this);
    state = State::FAILED;
    requestUpdate();
    return;
  }

  LOG_INF("FW", "SD firmware update complete, restarting");
  {
    RenderLock lock(*this);
    state = State::SUCCESS;
  }
  requestUpdateAndWait();
  delay(1500);
  ESP.restart();
}

void SdFirmwareUpdateActivity::loop() {
  if (state == State::FAILED) {
    int x = 0;
    int y = 0;
    if (mappedInput.wasPressed(MappedInputManager::Button::Back) ||
        mappedInput.wasPressed(MappedInputManager::Button::Confirm) || mappedInput.wasScreenTapped(x, y)) {
      if (recoveryMode) {
        // Go back to picker so user can try a different .bin
        state = State::PICKING;
        launchPicker();
        return;
      }
      finish();
    }
  }
}

void SdFirmwareUpdateActivity::render(RenderLock&&) {
  const auto& metrics = UITheme::getInstance().getMetrics();
  const auto pageWidth = renderer.getScreenWidth();
  const auto pageHeight = renderer.getScreenHeight();

  renderer.clearScreen();

  const char* headerText = recoveryMode ? tr(STR_RECOVERY_MODE) : tr(STR_SD_FIRMWARE_UPDATE);
  GUI.drawHeader(renderer, Rect{0, metrics.topPadding, pageWidth, metrics.headerHeight}, headerText);

  const auto lineHeight = renderer.getLineHeight(UI_10_FONT_ID);
  const auto top = (pageHeight - lineHeight) / 2;

  if (state == State::VALIDATING) {
    renderer.drawCenteredText(UI_10_FONT_ID, top, tr(STR_VALIDATING_FIRMWARE));
  } else if (state == State::UPDATING) {
    // Throttle redraws to once per percent.
    const unsigned int pct = firmwareSize > 0 ? static_cast<unsigned int>((writtenBytes * 100) / firmwareSize) : 0;
    if (pct == lastRenderedPercent) {
      return;
    }
    lastRenderedPercent = pct;

    renderer.drawCenteredText(UI_10_FONT_ID, top, tr(STR_UPDATING), true, EpdFontFamily::BOLD);

    int y = top + lineHeight + metrics.verticalSpacing;
    GUI.drawProgressBar(
        renderer,
        Rect{metrics.contentSidePadding, y, pageWidth - metrics.contentSidePadding * 2, metrics.progressBarHeight},
        static_cast<int>(pct), 100);
    y += metrics.progressBarHeight + metrics.verticalSpacing;
    // Percent label is drawn by BaseTheme::drawProgressBar; this slot is left intentionally empty
    // so the do-not-power-off line below stays at the same Y as before.
    y += lineHeight + metrics.verticalSpacing;
    renderer.drawCenteredText(UI_10_FONT_ID, y, tr(STR_FIRMWARE_UPDATE_DO_NOT_POWER_OFF));
  } else if (state == State::SUCCESS) {
    renderer.drawCenteredText(UI_10_FONT_ID, top, tr(STR_UPDATE_COMPLETE), true, EpdFontFamily::BOLD);
    const int hintY = top + lineHeight + metrics.verticalSpacing;
    const Rect hintBounds{metrics.contentSidePadding, hintY, pageWidth - metrics.contentSidePadding * 2,
                          pageHeight - hintY};
    UITheme::drawCenteredWrappedText(renderer, hintBounds, UI_10_FONT_ID, tr(STR_RESTARTING_HINT), 3, true,
                                     EpdFontFamily::REGULAR, UITheme::TextVerticalAlignment::TOP);
  } else if (state == State::FAILED) {
    renderer.drawCenteredText(UI_10_FONT_ID, top, tr(STR_UPDATE_FAILED), true, EpdFontFamily::BOLD);
    if (!errorMessage.empty()) {
      renderer.drawCenteredText(UI_10_FONT_ID, top + lineHeight + metrics.verticalSpacing, errorMessage.c_str());
    }
    const auto labels = mappedInput.mapLabels(tr(STR_BACK), "", "", "");
    GUI.drawButtonHints(renderer, labels.btn1, labels.btn2, labels.btn3, labels.btn4);
  } else {
    // PICKING / CONFIRMING: a sub-activity is on top, nothing to draw.
    if (recoveryMode) {
      renderer.drawCenteredText(UI_10_FONT_ID, top, tr(STR_RECOVERY_MODE_HINT));
    }
  }

  renderer.displayBuffer();
}
