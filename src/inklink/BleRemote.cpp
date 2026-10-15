#include "BleRemote.h"

#include <atomic>

namespace inklink::bleremote {

namespace {

std::atomic<int8_t> pendingTurn{0};
std::atomic<bool> linkUp{false};

// HID keyboard usages (page 0x07).
int8_t keyboardTurn(const uint8_t key) {
  switch (key) {
    case 0x4F:  // Right arrow
    case 0x51:  // Down arrow
    case 0x4E:  // Page Down
    case 0x2C:  // Space
    case 0x28:  // Enter
    case 0x58:  // Keypad Enter
    case 0x80:  // Volume Up (keyboard page)
      return 1;
    case 0x50:  // Left arrow
    case 0x52:  // Up arrow
    case 0x4B:  // Page Up
    case 0x2A:  // Backspace
    case 0x81:  // Volume Down (keyboard page)
      return -1;
    default:
      return 0;
  }
}

// HID consumer-control usages (page 0x0C). Camera-shutter remotes send Volume Up.
int8_t consumerTurn(const uint16_t usage) {
  switch (usage) {
    case 0x00E9:  // Volume Increment
    case 0x00B5:  // Scan Next Track
    case 0x00B3:  // Fast Forward
    case 0x00CD:  // Play/Pause
      return 1;
    case 0x00EA:  // Volume Decrement
    case 0x00B6:  // Scan Previous Track
    case 0x00B4:  // Rewind
      return -1;
    default:
      return 0;
  }
}

}  // namespace

int8_t decodeReport(const uint8_t* data, const size_t len) {
  if (!data || len == 0) return 0;
  if (len >= 8) {
    for (size_t i = 2; i < 8; i++) {
      const int8_t turn = keyboardTurn(data[i]);
      if (turn != 0) return turn;
    }
    return 0;
  }
  const uint16_t usage = len >= 2 ? static_cast<uint16_t>(data[0] | (data[1] << 8)) : data[0];
  return consumerTurn(usage);
}

int8_t takePageTurn() { return pendingTurn.exchange(0); }

bool connected() { return linkUp.load(); }

}  // namespace inklink::bleremote

#if defined(FOLIO_BLE_SYNC) && !defined(SIMULATOR)

#include <Logging.h>

#include <cstring>

// ble_hs.h includes ble_hs_stop.h ahead of its own extern "C" block.
extern "C" {
#include "host/ble_hs.h"
}
#include "host/util/util.h"

// NimBLE's NVS-backed bond store (store/config); its header has no C++ guard.
extern "C" void ble_store_config_init(void);

namespace inklink::bleremote {

namespace {

constexpr uint16_t HID_SERVICE = 0x1812;
constexpr uint16_t HID_REPORT = 0x2A4D;
constexpr uint16_t BOOT_KEYBOARD_INPUT = 0x2A22;
constexpr uint16_t CCCD = 0x2902;
constexpr size_t MAX_REPORTS = 8;
constexpr size_t MAX_REPORT_BYTES = 16;
constexpr int MAX_BONDS = 4;
constexpr int32_t CONNECT_TIMEOUT_MS = 10000;
// Low duty scan (30 ms every 1.28 s, units of 0.625 ms): a remote in pairing
// or reconnect mode advertises fast, and the reader stays on battery.
constexpr uint16_t SCAN_INTERVAL = 0x0800;
constexpr uint16_t SCAN_WINDOW = 0x0030;

struct Report {
  uint16_t defHandle;
  uint16_t valHandle;
};

// Main loop writes these before the host task starts; the host task reads.
bool enabled = false;
bool forgetOnSync = false;

// Host task only.
uint8_t addrType = 0;
uint16_t conn = BLE_HS_CONN_HANDLE_NONE;
uint16_t svcStart = 0;
uint16_t svcEnd = 0;
Report reports[MAX_REPORTS];
size_t reportCount = 0;
size_t reportIndex = 0;
uint16_t cccdHandle = 0;
int8_t lastTurn = 0;

int onGap(ble_gap_event* event, void*);

void startScan() {
  if (!enabled || conn != BLE_HS_CONN_HANDLE_NONE || ble_gap_disc_active()) return;
  ble_gap_disc_params params = {};
  params.itvl = SCAN_INTERVAL;
  params.window = SCAN_WINDOW;
  params.passive = 1;
  params.filter_duplicates = 1;
  const int rc = ble_gap_disc(addrType, BLE_HS_FOREVER, &params, onGap, nullptr);
  if (rc != 0 && rc != BLE_HS_EALREADY) LOG_ERR("BLR", "scan start rc=%d", rc);
}

bool advertisesHid(const ble_hs_adv_fields& fields) {
  for (int i = 0; i < fields.num_uuids16; i++) {
    if (ble_uuid_u16(&fields.uuids16[i].u) == HID_SERVICE) return true;
  }
  // Appearance category 0x03C0..0x03FF: HID devices.
  return fields.appearance_is_present && (fields.appearance & 0xFFC0) == 0x03C0;
}

int bondedPeers(ble_addr_t* peers) {
  int count = 0;
  if (ble_store_util_bonded_peers(peers, &count, MAX_BONDS) != 0) return 0;
  return count;
}

// Bonded remotes reconnect with their identity or a resolvable address the
// controller resolves; either way the scan reports the bonded address.
bool shouldConnect(const ble_gap_disc_desc& disc) {
  ble_addr_t peers[MAX_BONDS];
  const int bonded = bondedPeers(peers);
  if (bonded > 0) {
    for (int i = 0; i < bonded; i++) {
      if (ble_addr_cmp(&peers[i], &disc.addr) == 0) return true;
    }
    return false;
  }
  ble_hs_adv_fields fields = {};
  if (ble_hs_adv_parse_fields(&fields, disc.data, disc.length_data) != 0) return false;
  return advertisesHid(fields);
}

void resetLink() {
  conn = BLE_HS_CONN_HANDLE_NONE;
  reportCount = 0;
  reportIndex = 0;
  lastTurn = 0;
  linkUp.store(false);
}

void subscribeNext();

int onWrite(uint16_t, const ble_gatt_error* error, ble_gatt_attr*, void*) {
  if (error->status != 0) LOG_ERR("BLR", "CCCD write status %d", error->status);
  reportIndex++;
  subscribeNext();
  return 0;
}

int onDescriptor(uint16_t connHandle, const ble_gatt_error* error, uint16_t, const ble_gatt_dsc* dsc, void*) {
  if (error->status == 0) {
    if (ble_uuid_u16(&dsc->uuid.u) == CCCD) cccdHandle = dsc->handle;
    return 0;
  }
  if (error->status != BLE_HS_EDONE) LOG_ERR("BLR", "descriptor discovery status %d", error->status);
  if (cccdHandle != 0) {
    static constexpr uint8_t NOTIFY_ON[2] = {0x01, 0x00};
    if (ble_gattc_write_flat(connHandle, cccdHandle, NOTIFY_ON, sizeof(NOTIFY_ON), onWrite, nullptr) == 0) return 0;
  }
  reportIndex++;
  subscribeNext();
  return 0;
}

void subscribeNext() {
  if (conn == BLE_HS_CONN_HANDLE_NONE) return;
  if (reportIndex >= reportCount) {
    linkUp.store(reportCount > 0);
    LOG_INF("BLR", "remote ready: %u input report(s)", static_cast<unsigned>(reportCount));
    return;
  }
  const Report& r = reports[reportIndex];
  const uint16_t end = reportIndex + 1 < reportCount ? reports[reportIndex + 1].defHandle - 1 : svcEnd;
  cccdHandle = 0;
  if (r.valHandle >= end || ble_gattc_disc_all_dscs(conn, r.valHandle, end, onDescriptor, nullptr) != 0) {
    reportIndex++;
    subscribeNext();
  }
}

int onCharacteristic(uint16_t, const ble_gatt_error* error, const ble_gatt_chr* chr, void*) {
  if (error->status == 0) {
    const uint16_t uuid = ble_uuid_u16(&chr->uuid.u);
    if ((uuid == HID_REPORT || uuid == BOOT_KEYBOARD_INPUT) && (chr->properties & BLE_GATT_CHR_PROP_NOTIFY) &&
        reportCount < MAX_REPORTS) {
      reports[reportCount++] = {chr->def_handle, chr->val_handle};
    }
    return 0;
  }
  if (error->status != BLE_HS_EDONE) LOG_ERR("BLR", "characteristic discovery status %d", error->status);
  reportIndex = 0;
  subscribeNext();
  return 0;
}

int onService(uint16_t connHandle, const ble_gatt_error* error, const ble_gatt_svc* svc, void*) {
  if (error->status == 0) {
    svcStart = svc->start_handle;
    svcEnd = svc->end_handle;
    return 0;
  }
  if (svcStart == 0) {
    LOG_ERR("BLR", "no HID service (status %d): disconnecting", error->status);
    ble_gap_terminate(connHandle, BLE_ERR_REM_USER_CONN_TERM);
    return 0;
  }
  if (ble_gattc_disc_all_chrs(connHandle, svcStart, svcEnd, onCharacteristic, nullptr) != 0) {
    ble_gap_terminate(connHandle, BLE_ERR_REM_USER_CONN_TERM);
  }
  return 0;
}

void discover() {
  if (conn == BLE_HS_CONN_HANDLE_NONE || svcStart != 0) return;
  static const ble_uuid16_t HID_UUID = BLE_UUID16_INIT(HID_SERVICE);
  if (ble_gattc_disc_svc_by_uuid(conn, &HID_UUID.u, onService, nullptr) != 0) {
    ble_gap_terminate(conn, BLE_ERR_REM_USER_CONN_TERM);
  }
}

void onNotify(const ble_gap_event* event) {
  uint8_t data[MAX_REPORT_BYTES];
  uint16_t len = 0;
  const uint16_t total = OS_MBUF_PKTLEN(event->notify_rx.om);
  if (ble_hs_mbuf_to_flat(event->notify_rx.om, data, total < sizeof(data) ? total : sizeof(data), &len) != 0) return;
  const int8_t turn = decodeReport(data, len);
  // A held key repeats the same report; turn on the press, not on every repeat.
  if (turn != 0 && lastTurn == 0) pendingTurn.store(turn);
  lastTurn = turn;
}

int onGap(ble_gap_event* event, void*) {
  switch (event->type) {
    case BLE_GAP_EVENT_DISC:
      if (conn == BLE_HS_CONN_HANDLE_NONE && shouldConnect(event->disc)) {
        ble_gap_disc_cancel();
        const int rc = ble_gap_connect(addrType, &event->disc.addr, CONNECT_TIMEOUT_MS, nullptr, onGap, nullptr);
        if (rc != 0) {
          LOG_ERR("BLR", "connect rc=%d", rc);
          startScan();
        }
      }
      return 0;
    case BLE_GAP_EVENT_DISC_COMPLETE:
      startScan();
      return 0;
    case BLE_GAP_EVENT_CONNECT:
      if (event->connect.status != 0) {
        startScan();
        return 0;
      }
      conn = event->connect.conn_handle;
      svcStart = 0;
      svcEnd = 0;
      LOG_INF("BLR", "remote connected (handle %u)", conn);
      // HID reports are readable only over an encrypted link; discovery
      // waits for ENC_CHANGE unless security can't even be started.
      if (ble_gap_security_initiate(conn) != 0) discover();
      return 0;
    case BLE_GAP_EVENT_ENC_CHANGE:
      if (event->enc_change.conn_handle == conn) {
        if (event->enc_change.status != 0) LOG_ERR("BLR", "encryption status %d", event->enc_change.status);
        discover();
      }
      return 0;
    case BLE_GAP_EVENT_REPEAT_PAIRING: {
      // The remote lost its bond (reset, re-paired elsewhere): forget ours and pair again.
      ble_gap_conn_desc desc;
      if (ble_gap_conn_find(event->repeat_pairing.conn_handle, &desc) == 0) {
        ble_store_util_delete_peer(&desc.peer_id_addr);
      }
      return BLE_GAP_REPEAT_PAIRING_RETRY;
    }
    case BLE_GAP_EVENT_NOTIFY_RX:
      if (event->notify_rx.conn_handle == conn) onNotify(event);
      return 0;
    case BLE_GAP_EVENT_DISCONNECT:
      if (event->disconnect.conn.conn_handle == conn) {
        LOG_INF("BLR", "remote disconnected (reason 0x%x)", event->disconnect.reason);
        resetLink();
        startScan();
      }
      return 0;
    default:
      return 0;
  }
}

}  // namespace

namespace host {

void prepare(const bool on, const bool forgetBonds) {
  enabled = on;
  forgetOnSync = on && forgetBonds;
  if (!on) return;
  // Bonding with a remote that has no display or keys: "just works" pairing.
  ble_hs_cfg.sm_io_cap = BLE_HS_IO_NO_INPUT_OUTPUT;
  ble_hs_cfg.sm_bonding = 1;
  ble_hs_cfg.sm_sc = 1;
  ble_hs_cfg.sm_our_key_dist = BLE_SM_PAIR_KEY_DIST_ENC | BLE_SM_PAIR_KEY_DIST_ID;
  ble_hs_cfg.sm_their_key_dist = BLE_SM_PAIR_KEY_DIST_ENC | BLE_SM_PAIR_KEY_DIST_ID;
  ble_hs_cfg.store_status_cb = ble_store_util_status_rr;
  ble_store_config_init();
}

void onSync(const uint8_t ownAddrType) {
  addrType = ownAddrType;
  resetLink();
  if (!enabled) return;
  if (forgetOnSync) {
    forgetOnSync = false;
    ble_store_clear();
    LOG_INF("BLR", "bonded remotes forgotten");
  }
  startScan();
  LOG_INF("BLR", "looking for a remote");
}

void onStop() { resetLink(); }

}  // namespace host

}  // namespace inklink::bleremote

#else

namespace inklink::bleremote::host {
void prepare(bool, bool) {}
void onSync(uint8_t) {}
void onStop() {}
}  // namespace inklink::bleremote::host

#endif
