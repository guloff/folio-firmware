#pragma once
#include <string>

#include "activities/Activity.h"

class Bitmap;
class HalFile;

class SleepActivity final : public Activity {
 public:
  explicit SleepActivity(GfxRenderer& renderer, MappedInputManager& mappedInput, bool fromTimeout = false)
      : Activity("Sleep", renderer, mappedInput), fromTimeout(fromTimeout) {}
  void onEnter() override;

  // The sleep screen this sleep draws (SLEEP_SCREEN_MODE; QUICK_RESUME also
  // for a timeout under "Quick Resume after timeout"). PIN protection swaps
  // out what would reveal reading content: a protected book's cover, and with
  // the wake lock on the page left on screen (the chosen screen is drawn
  // instead) and highlight quotes. Call before the session relocks.
  static uint8_t effectiveMode(bool fromTimeout);
  // INKLINK_ROTATE: moves to the next screen when this sleep should change
  // it. Call once per sleep, before effectiveMode().
  static void advanceRotation();
  // Builds the sleep-screen cover of `bookPath` ahead of time (book open), so
  // sleep only reads a cached image instead of decoding a JPEG/PNG on a heap
  // that hours of reading have fragmented. No-op unless the sleep screen can
  // show the book cover.
  static void prepareCover(const GfxRenderer& renderer, const std::string& bookPath);

 private:
  void renderDefaultSleepScreen() const;
  void renderCustomSleepScreen() const;
  void renderCoverSleepScreen() const;
  // Book without a cover image: title and author set as a plain cover.
  void renderTitleCover(const std::string& title, const std::string& author) const;
  void renderBitmapSleepScreen(const Bitmap& bitmap, bool preserveBackground = false) const;
  bool renderSleepOverlayFile(HalFile& file, const char* pathForLog) const;
  bool renderTransparentOverlayPng(const std::string& path) const;
  bool renderSleepOverlayPath(const std::string& path) const;
  void renderLastScreenSleepScreen() const;
  void renderTransparentCustomSleepScreen() const;
  void renderBlankSleepScreen() const;

  bool fromTimeout = false;
};
