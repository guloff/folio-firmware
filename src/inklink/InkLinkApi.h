#pragma once

#include <string>

class WebServer;

// HTTP routes for the InkLink companion app (/api/inklink/*), registered on the
// same WebServer as the upstream file-transfer API. See PROJECT/notes/api.md.
namespace inklink::api {

constexpr int API_VERSION = 1;

void registerRoutes(WebServer& server);

// Firmware image the companion asked to install; the web server activity picks
// it up after the HTTP response went out. Empty when nothing is pending.
std::string takePendingFirmware();
// Drops a request that was never picked up (web server screen closed).
void clearPendingFirmware();

}  // namespace inklink::api
