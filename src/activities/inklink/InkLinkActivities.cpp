#include "InkLinkActivities.h"

#include <ArduinoJson.h>
#include <GfxRenderer.h>
#include <HalStorage.h>
#include <I18n.h>
#include <Logging.h>

#include <algorithm>
#include <cstdio>
#include <cstring>

#include "MappedInputManager.h"
#include "RecentBooksStore.h"
#include "activities/ActivityManager.h"
#include "activities/network/CrossPointWebServerActivity.h"
#include "activities/util/ConfirmationActivity.h"
#include "components/UITheme.h"
#include "fontIds.h"
#include "inklink/Annotations.h"
#include "inklink/InkLinkClock.h"
#include "inklink/JsonLines.h"
#include "inklink/Pairing.h"
#include "inklink/StatsRender.h"
#include "util/BookProgress.h"

namespace fui = freeink::ui;

namespace {

// Shared content-margin setup for the InkLink list screens (below the header).
template <typename Screen>
void applyListMargins(GfxRenderer& renderer, Screen& screen) {
  const auto& metrics = UITheme::getInstance().getMetrics();
  const Rect safe = UITheme::getInstance().getScreenSafeArea(renderer, true, false);
  screen.setContentMarginFromScreen(fui::Insets{
      static_cast<int16_t>(safe.y + metrics.topPadding + metrics.headerHeight),
      static_cast<int16_t>(renderer.getScreenWidth() - (safe.x + safe.width)),
      static_cast<int16_t>(renderer.getScreenHeight() - (safe.y + safe.height)), static_cast<int16_t>(safe.x)});
  screen.spacer(static_cast<int16_t>(metrics.verticalSpacing));
}

std::string titleForPath(const std::string& path) {
  for (const auto& rb : RECENT_BOOKS.getBooks()) {
    if (rb.path == path && !rb.title.empty()) return rb.title;
  }
  const size_t slash = path.find_last_of('/');
  std::string name = slash == std::string::npos ? path : path.substr(slash + 1);
  const size_t dot = name.find_last_of('.');
  if (dot != std::string::npos && dot > 0) name.resize(dot);
  return name;
}

}  // namespace

// ---- Hub ------------------------------------------------------------------

InkLinkHubActivity::InkLinkHubActivity(GfxRenderer& renderer, MappedInputManager& mappedInput)
    : UiListActivity("InkLinkHub", renderer, mappedInput) {}

void InkLinkHubActivity::onEnter() {
  UiListActivity::onEnter();
  const char* labels[ROW_COUNT] = {tr(STR_INKLINK_PHONE_SYNC), tr(STR_INKLINK_STATS), tr(STR_INKLINK_SHELVES),
                                   tr(STR_INKLINK_HIGHLIGHTS),  tr(STR_BROWSE_FILES),   tr(STR_FILE_TRANSFER),
                                   tr(STR_SETTINGS_TITLE),      tr(STR_RESET_PAIRING)};
  for (int i = 0; i < ROW_COUNT; i++) {
    rows[i] = fui::ListItem{};
    rows[i].label = labels[i];
    rows[i].actionValue = static_cast<int16_t>(i);
  }
}

const char* InkLinkHubActivity::headerTitle() const { return tr(STR_FOLIO_MENU); }

void InkLinkHubActivity::buildScreen(UiScreen& screen) {
  applyListMargins(renderer, screen);
  fui::ListProps props;
  props.items = rows;
  props.count = ROW_COUNT;
  props.action = ACTION_ROW;
  props.inputMask = fui::InputTouch;
  syncListViewport(screen, props);
  screen.list(props);
}

void InkLinkHubActivity::activateIndex(const int index) {
  app.clearTapFlash();
  switch (index) {
    case 0:
      activityManager.replaceActivity(makeUniqueNoThrow<CrossPointWebServerActivity>(renderer, mappedInput, true));
      break;
    case 1:
      activityManager.pushActivity(makeUniqueNoThrow<ReadingStatsActivity>(renderer, mappedInput));
      break;
    case 2:
      activityManager.pushActivity(makeUniqueNoThrow<ShelvesActivity>(renderer, mappedInput));
      break;
    case 3:
      activityManager.pushActivity(makeUniqueNoThrow<HighlightsActivity>(renderer, mappedInput));
      break;
    case 4:
      activityManager.goToFileBrowser();
      break;
    case 5:
      activityManager.goToFileTransfer();
      break;
    case 6:
      activityManager.goToSettings();
      break;
    case 7:
      confirmResetPairing();
      break;
    default:
      break;
  }
}

void InkLinkHubActivity::confirmResetPairing() {
  auto confirm =
      makeUniqueNoThrow<ConfirmationActivity>(renderer, mappedInput, tr(STR_RESET_PAIRING), tr(STR_RESET_PAIRING_CONFIRM));
  if (!confirm) {
    LOG_ERR("INKLINK", "OOM: reset pairing confirmation");
    return;
  }
  startActivityForResult(std::move(confirm), [this](const ActivityResult& result) {
    if (!result.isCancelled) inklink::pairing::reset();
    requestUpdate();
  });
}

// ---- Reading stats -----------------------------------------------------------

void ReadingStatsActivity::onEnter() {
  Activity::onEnter();
  loaded = inklink::ReadingStats::get().summarize(summary, &days);
  goalMinutes = inklink::Shelves::dailyGoalMinutes();
  requestUpdate();
}

void ReadingStatsActivity::loop() {
  int x = 0;
  int y = 0;
  if (mappedInput.wasReleased(MappedInputManager::Button::Back) ||
      mappedInput.wasReleased(MappedInputManager::Button::Confirm) || mappedInput.wasScreenTapped(x, y) ||
      mappedInput.wasHomeGesture()) {
    finish();
  }
}

void ReadingStatsActivity::render(RenderLock&&) {
  renderer.clearScreen();
  const auto& metrics = UITheme::getInstance().getMetrics();
  const int pageWidth = renderer.getScreenWidth();
  const int margin = 24;
  GUI.drawHeader(renderer, Rect{0, metrics.topPadding, pageWidth, metrics.headerHeight}, tr(STR_INKLINK_STATS),
                 nullptr);

  int y = metrics.topPadding + metrics.headerHeight + metrics.verticalSpacing + 8;
  char buf[96];
  char dur[40];
  if (!loaded) {
    // Zeros would look like real data; say what happened instead.
    renderer.drawText(UI_12_FONT_ID, margin, y, tr(STR_INKLINK_STATS_ERROR), true, EpdFontFamily::BOLD);
    const auto labels = mappedInput.mapLabels(tr(STR_BACK), "", "", "");
    GUI.drawButtonHints(renderer, labels.btn1, labels.btn2, labels.btn3, labels.btn4);
    renderer.displayBuffer();
    return;
  }

  // Today vs goal.
  inklink::render::formatDuration(summary.todaySecs, dur, sizeof(dur));
  snprintf(buf, sizeof(buf), "%s: %s / %u %s", tr(STR_INKLINK_TODAY), dur, static_cast<unsigned>(goalMinutes),
           tr(STR_INKLINK_MINUTES_SHORT));
  renderer.drawText(UI_12_FONT_ID, margin, y, buf, true, EpdFontFamily::BOLD);
  y += 30;
  const int goalPct = goalMinutes ? static_cast<int>(summary.todaySecs / 60 * 100 / goalMinutes) : 0;
  inklink::render::drawProgressBar(renderer, margin, y, pageWidth - 2 * margin, 14, goalPct);
  y += 34;

  // Streaks.
  snprintf(buf, sizeof(buf), "%s: %u %s    %s: %u %s", tr(STR_INKLINK_STREAK),
           static_cast<unsigned>(summary.currentStreak), tr(STR_INKLINK_DAYS), tr(STR_INKLINK_LONGEST),
           static_cast<unsigned>(summary.longestStreak), tr(STR_INKLINK_DAYS));
  renderer.drawText(UI_12_FONT_ID, margin, y, buf);
  y += 40;

  // Heatmap.
  renderer.drawText(UI_10_FONT_ID, margin, y, tr(STR_INKLINK_LAST_WEEKS));
  y += 24;
  const uint32_t today = inklink::clock::today();
  constexpr int WEEKS = 20;
  y += inklink::render::drawHeatmap(renderer, margin, y, pageWidth - 2 * margin, WEEKS, days, today) + 12;
  if (today == 0) {
    renderer.drawText(UI_10_FONT_ID, margin, y, tr(STR_INKLINK_CLOCK_NOT_SET));
    y += 26;
  }
  y += 14;

  // Totals.
  inklink::render::formatDuration(summary.totalSecs, dur, sizeof(dur));
  snprintf(buf, sizeof(buf), "%s: %s", tr(STR_INKLINK_TOTAL), dur);
  renderer.drawText(UI_12_FONT_ID, margin, y, buf, true, EpdFontFamily::BOLD);
  y += 30;
  snprintf(buf, sizeof(buf), "%u %s  ·  %u %s", static_cast<unsigned>(summary.totalPages), tr(STR_INKLINK_PAGES),
           static_cast<unsigned>(summary.sessions), tr(STR_INKLINK_SESSIONS));
  renderer.drawText(UI_12_FONT_ID, margin, y, buf);
  y += 30;
  snprintf(buf, sizeof(buf), "%s: %u", tr(STR_INKLINK_BOOKS_FINISHED), static_cast<unsigned>(summary.booksFinished));
  renderer.drawText(UI_12_FONT_ID, margin, y, buf);

  const auto labels = mappedInput.mapLabels(tr(STR_BACK), "", "", "");
  GUI.drawButtonHints(renderer, labels.btn1, labels.btn2, labels.btn3, labels.btn4);
  renderer.displayBuffer();
}

// ---- Shelves -------------------------------------------------------------------

ShelvesActivity::ShelvesActivity(GfxRenderer& renderer, MappedInputManager& mappedInput)
    : UiListActivity("Shelves", renderer, mappedInput) {}

void ShelvesActivity::onEnter() {
  UiListActivity::onEnter();
  inklink::Shelves::loadShelves(shelves);
  // Books deleted or moved on the card would be dead rows.
  for (auto& shelf : shelves) {
    shelf.books.erase(std::remove_if(shelf.books.begin(), shelf.books.end(),
                                     [](const std::string& p) { return !Storage.exists(p.c_str()); }),
                      shelf.books.end());
  }
  openShelf = -1;
  rebuildRows();
}

void ShelvesActivity::rebuildRows() {
  rowItems.clear();
  rowValues.clear();
  bookTitles.clear();
  if (openShelf < 0) {
    rowItems.reserve(shelves.size());
    rowValues.reserve(shelves.size());
    for (size_t i = 0; i < shelves.size(); i++) rowValues.push_back(std::to_string(shelves[i].books.size()));
    for (size_t i = 0; i < shelves.size(); i++) {
      fui::ListItem item;
      item.label = shelves[i].name.c_str();
      item.value = rowValues[i].c_str();
      item.actionValue = static_cast<int16_t>(i);
      rowItems.push_back(item);
    }
  } else {
    const auto& books = shelves[openShelf].books;
    bookTitles.reserve(books.size());
    rowValues.reserve(books.size());
    for (const auto& path : books) {
      bookTitles.push_back(titleForPath(path));
      const int pct = loadBookProgress(path);
      rowValues.push_back(pct >= 0 ? std::to_string(pct) + "%" : std::string());
    }
    rowItems.reserve(books.size());
    for (size_t i = 0; i < books.size(); i++) {
      fui::ListItem item;
      item.label = bookTitles[i].c_str();
      item.value = rowValues[i].c_str();
      item.actionValue = static_cast<int16_t>(i);
      rowItems.push_back(item);
    }
  }
  moveSelectionTo(0);
  requestUpdate();
}

int ShelvesActivity::listCount() const { return static_cast<int>(rowItems.size()); }

const char* ShelvesActivity::headerTitle() const {
  return openShelf < 0 ? tr(STR_INKLINK_SHELVES) : shelves[openShelf].name.c_str();
}

void ShelvesActivity::buildScreen(UiScreen& screen) {
  applyListMargins(renderer, screen);
  fui::ListProps props;
  if (rowItems.empty()) {
    // Non-interactive placeholder row explaining the empty state.
    placeholder = fui::ListItem{};
    placeholder.label = openShelf < 0 ? tr(STR_INKLINK_NO_SHELVES) : tr(STR_INKLINK_EMPTY_SHELF);
    placeholder.enabled = false;
    props.items = &placeholder;
    props.count = 1;
    props.labelText = screen.theme().smallText;
    props.labelText.maxLines = 3;
  } else {
    props.items = rowItems.data();
    props.count = static_cast<uint16_t>(rowItems.size());
  }
  props.action = ACTION_ROW;
  props.inputMask = fui::InputTouch;
  syncListViewport(screen, props);
  screen.list(props);
}

void ShelvesActivity::activateIndex(const int index) {
  if (index < 0 || index >= static_cast<int>(rowItems.size())) return;
  app.clearTapFlash();
  if (openShelf < 0) {
    openShelf = index;
    rebuildRows();
    return;
  }
  activityManager.goToReader(shelves[openShelf].books[index]);
}

void ShelvesActivity::onBackButton() {
  if (openShelf >= 0) {
    openShelf = -1;
    rebuildRows();
    return;
  }
  finish();
}

// ---- Highlights ----------------------------------------------------------------

HighlightsActivity::HighlightsActivity(GfxRenderer& renderer, MappedInputManager& mappedInput)
    : UiListActivity("Highlights", renderer, mappedInput) {}

namespace {
struct CollectCtx {
  std::vector<std::string>* texts;
  std::vector<std::string>* titles;
  std::vector<std::string>* books;
  std::vector<HighlightsActivity::Location>* locations;
  size_t max;
};

int16_t clampedInt(JsonObjectConst obj, const char* key, const int lo, const int hi) {
  const int v = obj[key] | -1;
  return static_cast<int16_t>(v < lo || v > hi ? -1 : v);
}

bool collectHighlight(JsonObjectConst obj, void* raw) {
  auto* ctx = static_cast<CollectCtx*>(raw);
  ctx->texts->emplace_back(obj["x"] | "");
  ctx->titles->emplace_back(obj["t"] | "");
  ctx->books->emplace_back(obj["b"] | "");
  ctx->locations->push_back({clampedInt(obj, "sp", 0, INT16_MAX), clampedInt(obj, "pg", 0, INT16_MAX),
                             static_cast<int8_t>(clampedInt(obj, "c", 0, 100))});
  // Keep only the newest `max` (file order is oldest first).
  if (ctx->texts->size() > ctx->max) {
    ctx->texts->erase(ctx->texts->begin());
    ctx->titles->erase(ctx->titles->begin());
    ctx->books->erase(ctx->books->begin());
    ctx->locations->erase(ctx->locations->begin());
  }
  return true;
}
}  // namespace

void HighlightsActivity::onEnter() {
  UiListActivity::onEnter();
  texts.clear();
  titles.clear();
  books.clear();
  locations.clear();
  texts.reserve(MAX_ITEMS + 1);
  titles.reserve(MAX_ITEMS + 1);
  books.reserve(MAX_ITEMS + 1);
  locations.reserve(MAX_ITEMS + 1);
  CollectCtx ctx{&texts, &titles, &books, &locations, MAX_ITEMS};
  inklink::jsonl::forEach(inklink::Annotations::HIGHLIGHTS_PATH, collectHighlight, &ctx);
  std::reverse(texts.begin(), texts.end());
  std::reverse(titles.begin(), titles.end());
  std::reverse(books.begin(), books.end());
  std::reverse(locations.begin(), locations.end());
  // A highlight outlives its book file: such rows stay readable but are
  // disabled and say why. One existence check per distinct book.
  subtitles.clear();
  subtitles.reserve(texts.size());
  std::string lastPath;
  bool lastExists = false;
  rowItems.clear();
  rowItems.reserve(texts.size());
  for (size_t i = 0; i < texts.size(); i++) {
    if (i == 0 || books[i] != lastPath) {
      lastPath = books[i];
      lastExists = !lastPath.empty() && Storage.exists(lastPath.c_str());
    }
    subtitles.push_back(titles[i]);
    if (!lastExists) {
      if (!subtitles.back().empty()) subtitles.back() += " \xC2\xB7 ";
      subtitles.back() += tr(STR_INKLINK_BOOK_MISSING);
    }
    fui::ListItem item;
    item.label = texts[i].c_str();
    item.actionValue = static_cast<int16_t>(i);
    item.enabled = lastExists;
    rowItems.push_back(item);
  }
  // subtitles is complete: its c_str() pointers are stable from here on.
  for (size_t i = 0; i < rowItems.size(); i++) rowItems[i].subtitle = subtitles[i].c_str();
}

const char* HighlightsActivity::headerTitle() const { return tr(STR_INKLINK_HIGHLIGHTS); }

void HighlightsActivity::activateIndex(const int index) {
  if (index < 0 || index >= static_cast<int>(books.size())) return;
  const std::string& path = books[index];
  if (path.empty() || !Storage.exists(path.c_str())) {
    LOG_ERR("INKLINK", "highlight book missing: %s", path.c_str());
    return;  // the row is shown disabled with "book not found"
  }
  const Location& at = locations[index];
  app.clearTapFlash();
  activityManager.goToReaderAt(path, at.spine, at.page, at.percent);
}

void HighlightsActivity::buildScreen(UiScreen& screen) {
  applyListMargins(renderer, screen);
  fui::ListProps props;
  if (rowItems.empty()) {
    placeholder = fui::ListItem{};
    placeholder.label = tr(STR_INKLINK_NO_HIGHLIGHTS);
    placeholder.enabled = false;
    props.items = &placeholder;
    props.count = 1;
  } else {
    props.items = rowItems.data();
    props.count = static_cast<uint16_t>(rowItems.size());
  }
  props.action = ACTION_ROW;
  props.inputMask = fui::InputTouch;
  props.labelText = screen.theme().smallText;
  props.labelText.maxLines = 3;
  syncListViewport(screen, props);
  screen.list(props);
}
