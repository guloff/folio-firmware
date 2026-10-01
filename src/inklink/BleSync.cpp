#include "BleSync.h"

#include <Logging.h>

#include "BleProtocol.h"
#include "CrossPointSettings.h"

#if defined(FOLIO_BLE_SYNC) && !defined(SIMULATOR)

#include <Arduino.h>
#include <WiFi.h>
#include <esp_heap_caps.h>
#include <nvs.h>

#include <atomic>

#include "freertos/FreeRTOS.h"
#include "freertos/semphr.h"
// ble_hs.h includes ble_hs_stop.h ahead of its own extern "C" block.
extern "C" {
#include "host/ble_hs.h"
}
#include "host/util/util.h"
#include "nimble/nimble_port.h"
#include "nimble/nimble_port_freertos.h"
#include "services/gap/ble_svc_gap.h"
#include "services/gatt/ble_svc_gatt.h"

// The Arduino core hands the BLE controller's static RAM to the heap at boot
// unless BLE is declared in use. The "Bluetooth sync" setting is mirrored into
// NVS because SETTINGS (on the SD card) is not loaded yet at that point: with
// the setting off that RAM stays in the heap, and enabling it takes effect
// from the next restart (every wake from deep sleep is one).
namespace {
constexpr const char* NVS_NAMESPACE = "folio";
constexpr const char* NVS_KEY_BLE = "ble";

uint8_t readBootFlag() {
  nvs_handle_t h;
  if (nvs_open(NVS_NAMESPACE, NVS_READONLY, &h) != ESP_OK) return 0;
  uint8_t v = 0;
  if (nvs_get_u8(h, NVS_KEY_BLE, &v) != ESP_OK) v = 0;
  nvs_close(h);
  return v;
}

void writeBootFlag(uint8_t v) {
  nvs_handle_t h;
  if (nvs_open(NVS_NAMESPACE, NVS_READWRITE, &h) != ESP_OK) {
    LOG_ERR("BLE", "NVS open failed");
    return;
  }
  if (nvs_set_u8(h, NVS_KEY_BLE, v) != ESP_OK || nvs_commit(h) != ESP_OK) LOG_ERR("BLE", "NVS write failed");
  nvs_close(h);
}
}  // namespace

extern "C" bool bleInUse(void) { return readBootFlag() == 1; }

namespace inklink::blesync {

namespace {

using namespace inklink::ble;

// Advertising interval 1022.5 ms (units of 0.625 ms): one of the intervals
// Apple's accessory guidelines list, so iOS discovers it promptly.
constexpr uint16_t ADV_INTERVAL = 1636;
constexpr size_t FRAMES_PER_LOOP = 8;
// A central that hasn't authenticated by then is dropped: the reader serves
// one connection and advertising stops while it lasts, so a silent stranger
// would otherwise lock the owner's phone out.
constexpr uint32_t AUTH_TIMEOUT_MS = 30000;
// ATT application errors returned on a request write.
constexpr int ATT_ERR_BUSY = 0x80;            // previous reply still being sent
constexpr int ATT_ERR_NOT_SUBSCRIBED = 0x81;  // enable notifications first
// ble_hs_stop() waits up to 2 s for a link to drop before giving up on it.
constexpr uint32_t HOST_STOP_WAIT_MS = 5000;

enum Phase : uint8_t { IDLE, READY, PROCESSING };

// Written by the NimBLE host task, read by the main loop. The request slot is
// filled only in IDLE and read only in READY; `phase` publishes it.
struct Link {
  std::atomic<uint16_t> conn{BLE_HS_CONN_HANDLE_NONE};
  std::atomic<uint16_t> mtu{MIN_MTU};
  std::atomic<bool> subscribed{false};
  std::atomic<uint32_t> generation{0};
  std::atomic<uint8_t> phase{IDLE};
  std::atomic<bool> advertise{false};
  uint32_t requestGen = 0;
  uint16_t requestLen = 0;
};

Link link;
Channel channel;
HalMemory::PsramBuffer requestBuf;
bool running = false;
bool suspended = false;  // off until reboot (Wi-Fi screen, deep sleep)
bool failed = false;     // init failed; retried only when the setting changes
bool stalled = false;    // host task never stopped: the stack stays up until reboot
bool memWarned = false;
uint8_t lastSetting = 0xFF;
uint32_t sessionGen = 0;
uint32_t sessionStartMs = 0;
bool authTimeoutSent = false;
uint8_t ownAddrType = 0;
uint16_t requestHandle = 0;
uint16_t responseHandle = 0;

// F0110001-7B3A-4C8E-9F1D-6A2B5C4D3E01 and siblings, little-endian.
const ble_uuid128_t SERVICE = BLE_UUID128_INIT(0x01, 0x3E, 0x4D, 0x5C, 0x2B, 0x6A, 0x1D, 0x9F, 0x8E, 0x4C, 0x3A, 0x7B,
                                               0x01, 0x00, 0x11, 0xF0);
const ble_uuid128_t REQUEST = BLE_UUID128_INIT(0x01, 0x3E, 0x4D, 0x5C, 0x2B, 0x6A, 0x1D, 0x9F, 0x8E, 0x4C, 0x3A, 0x7B,
                                               0x02, 0x00, 0x11, 0xF0);
const ble_uuid128_t RESPONSE = BLE_UUID128_INIT(0x01, 0x3E, 0x4D, 0x5C, 0x2B, 0x6A, 0x1D, 0x9F, 0x8E, 0x4C, 0x3A, 0x7B,
                                                0x03, 0x00, 0x11, 0xF0);

ble_gatt_chr_def characteristics[3] = {};
ble_gatt_svc_def services[2] = {};

size_t internalFree() { return heap_caps_get_free_size(MALLOC_CAP_INTERNAL); }
size_t internalLargest() { return heap_caps_get_largest_free_block(MALLOC_CAP_INTERNAL); }

int onAccess(uint16_t conn, uint16_t attr, ble_gatt_access_ctxt* ctxt, void*) {
  if (ctxt->op != BLE_GATT_ACCESS_OP_WRITE_CHR || attr != requestHandle) return BLE_ATT_ERR_UNLIKELY;
  if (conn != link.conn.load()) return BLE_ATT_ERR_UNLIKELY;
  const uint16_t len = OS_MBUF_PKTLEN(ctxt->om);
  if (len == 0 || len > MAX_REQUEST) return BLE_ATT_ERR_INVALID_ATTR_VALUE_LEN;
  if (!link.subscribed.load()) return ATT_ERR_NOT_SUBSCRIBED;
  if (link.phase.load() != IDLE) return ATT_ERR_BUSY;
  uint16_t copied = 0;
  if (ble_hs_mbuf_to_flat(ctxt->om, requestBuf.get(), MAX_REQUEST, &copied) != 0) return BLE_ATT_ERR_UNLIKELY;
  link.requestLen = copied;
  link.requestGen = link.generation.load();
  link.phase.store(READY);
  return 0;
}

int onGap(ble_gap_event* event, void*);

void startAdvertising() {
  if (!link.advertise.load()) return;
  ble_hs_adv_fields fields = {};
  fields.flags = BLE_HS_ADV_F_DISC_GEN | BLE_HS_ADV_F_BREDR_UNSUP;
  // Flags (3) + 128-bit UUID (18) + name (7) = 28 of 31 bytes: iOS background
  // scans filter on the service UUID in the primary advertisement.
  fields.uuids128 = &SERVICE;
  fields.num_uuids128 = 1;
  fields.uuids128_is_complete = 1;
  static constexpr char NAME[] = "Folio";
  fields.name = reinterpret_cast<const uint8_t*>(NAME);
  fields.name_len = sizeof(NAME) - 1;
  fields.name_is_complete = 1;
  int rc = ble_gap_adv_set_fields(&fields);
  if (rc != 0) {
    LOG_ERR("BLE", "adv fields rc=%d", rc);
    return;
  }
  ble_gap_adv_params params = {};
  params.conn_mode = BLE_GAP_CONN_MODE_UND;
  params.disc_mode = BLE_GAP_DISC_MODE_GEN;
  params.itvl_min = ADV_INTERVAL;
  params.itvl_max = ADV_INTERVAL;
  rc = ble_gap_adv_start(ownAddrType, nullptr, BLE_HS_FOREVER, &params, onGap, nullptr);
  if (rc != 0 && rc != BLE_HS_EALREADY) LOG_ERR("BLE", "adv start rc=%d", rc);
}

int onGap(ble_gap_event* event, void*) {
  switch (event->type) {
    case BLE_GAP_EVENT_CONNECT:
      if (event->connect.status == 0) {
        link.subscribed.store(false);
        link.mtu.store(ble_att_mtu(event->connect.conn_handle));
        link.conn.store(event->connect.conn_handle);
        link.generation.fetch_add(1);
        LOG_INF("BLE", "connected (handle %u)", event->connect.conn_handle);
      } else {
        startAdvertising();
      }
      break;
    case BLE_GAP_EVENT_DISCONNECT:
      link.conn.store(BLE_HS_CONN_HANDLE_NONE);
      link.subscribed.store(false);
      link.generation.fetch_add(1);
      LOG_INF("BLE", "disconnected (reason 0x%x)", event->disconnect.reason);
      startAdvertising();
      break;
    case BLE_GAP_EVENT_ADV_COMPLETE:
      startAdvertising();
      break;
    case BLE_GAP_EVENT_MTU:
      link.mtu.store(event->mtu.value);
      LOG_DBG("BLE", "MTU %u", event->mtu.value);
      break;
    case BLE_GAP_EVENT_SUBSCRIBE:
      if (event->subscribe.attr_handle == responseHandle) link.subscribed.store(event->subscribe.cur_notify != 0);
      break;
    default:
      break;
  }
  return 0;
}

void onSync() {
  if (ble_hs_util_ensure_addr(0) != 0 || ble_hs_id_infer_auto(0, &ownAddrType) != 0) {
    LOG_ERR("BLE", "no usable address");
    return;
  }
  startAdvertising();
  LOG_INF("BLE", "advertising as Folio; internal heap free %u, largest %u", static_cast<unsigned>(internalFree()),
          static_cast<unsigned>(internalLargest()));
}

void onReset(int reason) { LOG_ERR("BLE", "host reset, reason %d", reason); }

// Shutdown runs on the host task. The NPL port removes a fired timer event
// from the host queue under a lock that doesn't span cores, so stopping
// advertising or the host from the main loop (core 1) while the host task
// (core 0) drains that queue panics with "assert failed:
// npl_freertos_eventq_remove". The main loop only posts stopEvent, a plain
// queue send. Notifications and
// ble_gap_terminate() don't touch host timers and stay on the main loop.
ble_npl_event stopEvent = {};
ble_hs_stop_listener stopListener;
bool hostExit = false;  // host task only
StaticSemaphore_t hostDoneBuf;
SemaphoreHandle_t hostDone = nullptr;

void onHostStopped(int status, void*) {
  if (status != 0) LOG_ERR("BLE", "host stop status %d", status);
  hostExit = true;
}

void onStopEvent(ble_npl_event*) {
  // Preempts advertising, drops the link and calls onHostStopped once it is
  // gone; a non-zero rc means there was nothing to stop or wait for.
  const int rc = ble_hs_stop(&stopListener, onHostStopped, nullptr);
  if (rc != 0) hostExit = true;
}

// nimble_port_run() leaves only on nimble_port_stop()'s private event, which
// ble_hs_stop() from the main loop would need; this loop leaves on hostExit.
void hostTask(void*) {
  ble_npl_eventq* queue = nimble_port_get_dflt_eventq();
  while (!hostExit) {
    ble_npl_event* ev = ble_npl_eventq_get(queue, BLE_NPL_TIME_FOREVER);
    if (ev) ble_npl_event_run(ev);
  }
  xSemaphoreGive(hostDone);
  nimble_port_freertos_deinit();
}

bool sendFrame(void*, const uint8_t* frame, size_t len) {
  const uint16_t conn = link.conn.load();
  if (conn == BLE_HS_CONN_HANDLE_NONE) return false;
  os_mbuf* om = ble_hs_mbuf_from_flat(frame, len);
  if (!om) return false;  // mbuf pool exhausted: congested, retry next loop
  // Consumes `om` whatever the outcome; BLE_HS_ENOMEM means the controller
  // queue is full, so the same frame is offered again next loop.
  return ble_gatts_notify_custom(conn, responseHandle, om) == 0;
}

void buildGatt() {
  characteristics[0].uuid = &REQUEST.u;
  characteristics[0].access_cb = onAccess;
  characteristics[0].flags = BLE_GATT_CHR_F_WRITE;
  characteristics[0].val_handle = &requestHandle;
  characteristics[1].uuid = &RESPONSE.u;
  characteristics[1].access_cb = onAccess;
  characteristics[1].flags = BLE_GATT_CHR_F_NOTIFY;
  characteristics[1].val_handle = &responseHandle;
  services[0].type = BLE_GATT_SVC_TYPE_PRIMARY;
  services[0].uuid = &SERVICE.u;
  services[0].characteristics = characteristics;
}

bool start() {
  if (running || suspended || failed || stalled) return running;
  if (btMemReleased(BT_MODE_BLE)) {
    if (!memWarned) LOG_INF("BLE", "controller RAM was released at boot; BLE starts after the next restart");
    memWarned = true;
    return false;
  }
  const size_t freeBefore = internalFree();
  const size_t largestBefore = internalLargest();
  if (!requestBuf) requestBuf = HalMemory::allocatePsram(MAX_REQUEST);
  if (!requestBuf || !channel.begin()) {
    LOG_ERR("BLE", "OOM: PSRAM buffers");
    requestBuf.reset();
    channel.end();
    failed = true;
    return false;
  }
  const esp_err_t err = nimble_port_init();
  if (err != ESP_OK) {
    LOG_ERR("BLE", "nimble_port_init failed: %d", err);
    requestBuf.reset();
    channel.end();
    failed = true;
    return false;
  }
  ble_hs_cfg.sync_cb = onSync;
  ble_hs_cfg.reset_cb = onReset;
  ble_svc_gap_init();
  ble_svc_gatt_init();
  buildGatt();
  int rc = ble_gatts_count_cfg(services);
  if (rc == 0) rc = ble_gatts_add_svcs(services);
  if (rc != 0) {
    LOG_ERR("BLE", "GATT registration rc=%d", rc);
    nimble_port_deinit();
    requestBuf.reset();
    channel.end();
    failed = true;
    return false;
  }
  ble_svc_gap_device_name_set("Folio");
  ble_att_set_preferred_mtu(MAX_MTU);
  if (!hostDone) hostDone = xSemaphoreCreateBinaryStatic(&hostDoneBuf);
  xSemaphoreTake(hostDone, 0);
  ble_npl_event_init(&stopEvent, onStopEvent, nullptr);  // from the NimBLE pool: after nimble_port_init()
  hostExit = false;
  link.phase.store(IDLE);
  link.advertise.store(true);
  running = true;
  nimble_port_freertos_init(hostTask);
  LOG_INF("BLE", "init: internal heap free %u -> %u (-%d), largest block %u -> %u",
          static_cast<unsigned>(freeBefore), static_cast<unsigned>(internalFree()),
          static_cast<int>(freeBefore) - static_cast<int>(internalFree()), static_cast<unsigned>(largestBefore),
          static_cast<unsigned>(internalLargest()));
  return true;
}

void stop() {
  if (!running) return;
  running = false;
  link.advertise.store(false);  // a disconnect during shutdown must not re-advertise
  const size_t freeBefore = internalFree();
  ble_npl_eventq_put(nimble_port_get_dflt_eventq(), &stopEvent);
  if (xSemaphoreTake(hostDone, pdMS_TO_TICKS(HOST_STOP_WAIT_MS)) != pdTRUE) {
    // Deinit under a live host task is the race this sequence avoids; a
    // radio left on until the next restart is the lesser harm.
    stalled = true;
    LOG_ERR("BLE", "host task did not stop within %u ms; BLE stays on until restart",
            static_cast<unsigned>(HOST_STOP_WAIT_MS));
    return;
  }
  ble_npl_event_deinit(&stopEvent);
  nimble_port_deinit();
  channel.end();
  requestBuf.reset();
  link.conn.store(BLE_HS_CONN_HANDLE_NONE);
  link.subscribed.store(false);
  link.phase.store(IDLE);
  LOG_INF("BLE", "stopped: internal heap free %u -> %u", static_cast<unsigned>(freeBefore),
          static_cast<unsigned>(internalFree()));
}

void followSetting() {
  const uint8_t want = SETTINGS.bluetoothSync ? 1 : 0;
  if (want == lastSetting) return;
  lastSetting = want;
  if (readBootFlag() != want) writeBootFlag(want);
  failed = false;
  if (want) {
    start();
  } else {
    stop();
  }
}

}  // namespace

void begin() { followSetting(); }

void loop() {
  followSetting();
  if (!running) return;
  if (WiFi.getMode() != WIFI_MODE_NULL) {
    // Wi-Fi screens reboot on exit; both radios at once would cost ~70 KB of
    // internal RAM the web server needs.
    LOG_INF("BLE", "Wi-Fi active: BLE off until restart");
    shutdown();
    return;
  }
  const uint32_t gen = link.generation.load();
  if (gen != sessionGen) {
    sessionGen = gen;
    sessionStartMs = millis();
    authTimeoutSent = false;
    channel.resetSession();
    uint8_t phase = link.phase.load();
    if (phase == PROCESSING || (phase == READY && link.requestGen != gen)) {
      link.phase.compare_exchange_strong(phase, IDLE);
    }
  }
  const uint16_t conn = link.conn.load();
  if (conn != BLE_HS_CONN_HANDLE_NONE && !channel.state().authorized && !authTimeoutSent &&
      millis() - sessionStartMs > AUTH_TIMEOUT_MS) {
    authTimeoutSent = true;
    LOG_INF("BLE", "no auth within %u s: disconnecting", static_cast<unsigned>(AUTH_TIMEOUT_MS / 1000));
    ble_gap_terminate(conn, BLE_ERR_REM_USER_CONN_TERM);
  }
  if (link.phase.load() == READY) {
    link.phase.store(PROCESSING);
    channel.handle(reinterpret_cast<const char*>(requestBuf.get()), link.requestLen, link.mtu.load());
  }
  if (link.phase.load() == PROCESSING && channel.pump(sendFrame, nullptr, FRAMES_PER_LOOP)) {
    if (channel.shouldDisconnect()) {
      if (conn != BLE_HS_CONN_HANDLE_NONE) ble_gap_terminate(conn, BLE_ERR_REM_USER_CONN_TERM);
      LOG_INF("BLE", "too many failed auth attempts: disconnecting");
    }
    link.phase.store(IDLE);
  }
}

void shutdown() {
  suspended = true;
  stop();
}

bool busy() { return running && link.phase.load() != IDLE; }

}  // namespace inklink::blesync

#else  // no BLE radio in this build

#ifdef SIMULATOR
#include "BleLoopback.h"
#endif

namespace inklink::blesync {

void begin() {}

void loop() {
#ifdef SIMULATOR
  static bool loopbackDone = false;
  if (!loopbackDone) {
    loopbackDone = true;
    inklink::ble::runLoopbackFromEnv();
  }
#endif
}

void shutdown() {}

bool busy() { return false; }

}  // namespace inklink::blesync

#endif
