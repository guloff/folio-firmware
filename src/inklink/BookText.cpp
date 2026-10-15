#include "BookText.h"

#include <ArduinoJson.h>
#include <HalStorage.h>
#include <Logging.h>

#include <cstring>

#include "JsonLines.h"

namespace inklink::booktext {

namespace {

constexpr const char* PATH = "/.crosspoint/inklink/booktext.json";
constexpr size_t MAX_FILE = 32 * 1024;

bool readDoc(JsonDocument& doc) {
  jsonl::recoverTmp(PATH);
  if (!Storage.exists(PATH)) return false;
  HalFile f = Storage.open(PATH, O_RDONLY);
  if (!f) return false;
  const size_t size = f.fileSize();
  if (size == 0 || size > MAX_FILE) return false;
  std::string text(size, '\0');
  if (f.read(text.data(), size) != static_cast<int>(size)) return false;
  return deserializeJson(doc, text) == DeserializationError::Ok;
}

bool writeDoc(const JsonDocument& doc) {
  std::string out;
  serializeJson(doc, out);
  if (out.size() > MAX_FILE) {
    LOG_ERR("BTXT", "booktext.json would exceed %u bytes", static_cast<unsigned>(MAX_FILE));
    return false;
  }
  Storage.ensureDirectoryExists("/.crosspoint/inklink");
  if (!jsonl::writeAtomic(PATH, out)) {
    LOG_ERR("BTXT", "booktext.json write failed");
    return false;
  }
  return true;
}

}  // namespace

bool load(const std::string& bookPath, CrossPointSettings::ReaderText& out) {
  JsonDocument doc;
  if (!readDoc(doc)) return false;
  JsonObjectConst o = doc[bookPath.c_str()];
  if (o.isNull()) return false;
  out.fontFamily = o["ff"] | static_cast<uint8_t>(0);
  out.fontPointSize = o["fs"] | static_cast<uint8_t>(CrossPointSettings::DEFAULT_FONT_POINT_SIZE);
  out.lineSpacing = o["ls"] | static_cast<uint8_t>(CrossPointSettings::NORMAL);
  out.wordSpacing = o["ws"] | static_cast<uint8_t>(0);
  out.characterSpacing = o["cs"] | static_cast<uint8_t>(0);
  out.screenMargin = o["sm"] | static_cast<uint8_t>(CrossPointSettings::SCREEN_MARGIN_MIN);
  out.paragraphAlignment = o["pa"] | static_cast<uint8_t>(CrossPointSettings::JUSTIFIED);
  strlcpy(out.sdFontFamilyName, o["sd"] | "", sizeof(out.sdFontFamilyName));
  return true;
}

bool save(const std::string& bookPath, const CrossPointSettings::ReaderText& text) {
  JsonDocument doc;
  readDoc(doc);  // a missing or damaged file starts over
  JsonObject o = doc[bookPath.c_str()].to<JsonObject>();
  o["ff"] = text.fontFamily;
  o["fs"] = text.fontPointSize;
  o["ls"] = text.lineSpacing;
  o["ws"] = text.wordSpacing;
  o["cs"] = text.characterSpacing;
  o["sm"] = text.screenMargin;
  o["pa"] = text.paragraphAlignment;
  if (text.sdFontFamilyName[0] != '\0') o["sd"] = text.sdFontFamilyName;
  return writeDoc(doc);
}

bool remove(const std::string& bookPath) {
  JsonDocument doc;
  if (!readDoc(doc)) return true;
  doc.remove(bookPath.c_str());
  return writeDoc(doc);
}

}  // namespace inklink::booktext
