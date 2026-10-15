#pragma once

#include <string>

#include "CrossPointSettings.h"

// Text settings kept for one book ("Text for this book" in the reader menu):
// /.crosspoint/inklink/booktext.json maps a book path to its font, size,
// spacing, margins and alignment. Books without an entry follow the global
// reader settings.
namespace inklink::booktext {

bool load(const std::string& bookPath, CrossPointSettings::ReaderText& out);
bool save(const std::string& bookPath, const CrossPointSettings::ReaderText& text);
bool remove(const std::string& bookPath);

}  // namespace inklink::booktext
