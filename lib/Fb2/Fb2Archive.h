#pragma once

#include <string>

// FB2 books on the SD card: `.fb2`, or `.fb2.zip` holding one.
namespace Fb2Archive {

// Transcodes the book into an EPUB at `epubPath` (written beside it, then
// renamed into place, so an interrupted run leaves nothing half-written).
bool convert(const std::string& fb2Path, const std::string& epubPath);

// Title and authors (", "-joined) from <description>, without converting.
bool readMetadata(const std::string& fb2Path, std::string& title, std::string& author);

}  // namespace Fb2Archive
