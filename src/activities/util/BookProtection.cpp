#include "BookProtection.h"

#include <Logging.h>
#include <Memory.h>

#include <utility>

#include "PinEntryActivity.h"
#include "activities/Activity.h"
#include "inklink/PrivacyLock.h"

namespace privacy = inklink::privacy;

void toggleBookProtection(Activity& host, GfxRenderer& renderer, MappedInputManager& mappedInput,
                          const std::string& path, const std::string& title, std::function<void()> changed) {
  const bool isProtected = privacy::isProtected(path);
  const bool hasPin = privacy::hasPin();
  if (!isProtected && hasPin) {
    if (privacy::protect(path, title) && changed) changed();
    return;
  }
  if (isProtected && !hasPin) {
    // Only the owner removes a PIN (in settings with it, or by a signed reset).
    if (privacy::unprotect(path) && changed) changed();
    return;
  }
  // Protecting without a PIN creates one; removing protection checks it.
  auto pad = makeUniqueNoThrow<PinEntryActivity>(
      renderer, mappedInput, isProtected ? PinEntryActivity::Mode::Unlock : PinEntryActivity::Mode::Create,
      isProtected ? StrId::STR_PIN_CURRENT : StrId::STR_PIN_NEW, /*cancellable=*/true);
  if (!pad) {
    LOG_ERR("LOCK", "OOM: PIN pad");
    return;
  }
  host.startActivityForResult(
      std::move(pad), [isProtected, path, title, changed = std::move(changed)](const ActivityResult& result) {
        if (result.isCancelled) return;
        bool ok;
        if (isProtected) {
          ok = privacy::unprotect(path);
        } else {
          ok = privacy::setPin(std::get<KeyboardResult>(result.data).text.c_str()) && privacy::protect(path, title);
          // The owner just set the PIN: leave the session's books open.
          privacy::unlockBooks();
        }
        if (ok && changed) changed();
      });
}
