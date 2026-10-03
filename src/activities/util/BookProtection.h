#pragma once

#include <functional>
#include <string>

class Activity;
class GfxRenderer;
class MappedInputManager;

// Protect / Remove protection for one book (inklink::privacy), shared by the
// file browser's long-press menu and the reader menu. Protecting needs a PIN
// to exist (the pad creates one first); removing protection asks for it.
// `changed` runs after the list changed.
void toggleBookProtection(Activity& host, GfxRenderer& renderer, MappedInputManager& mappedInput,
                          const std::string& path, const std::string& title, std::function<void()> changed);
