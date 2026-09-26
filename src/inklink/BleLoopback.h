#pragma once

#ifdef SIMULATOR

// Simulator-only test driver for the BLE protocol (no radio on the host). With
// CROSSPOINT_SIM_BLE_LOOPBACK=<script> the first main-loop pass runs the script
// through the same Channel the device uses, with a loopback transport that
// reassembles the notification frames, and writes a report to
// CROSSPOINT_SIM_BLE_LOOPBACK_OUT (default: stdout).
// CROSSPOINT_SIM_BLE_LOOPBACK_EXIT=1 quits the simulator afterwards.
//
// Script lines:
//   {"op":...}          one request
//   follow {"op":...}   repeat with "offset":nextOffset while "more" is true
//   connect             new connection (authorization forgotten)
//   mtu <n>             ATT MTU used for the next replies (23..517)
//   cap <n>             response capacity in bytes (default 65536)
//   congest <n>         the transport refuses every n-th frame once (0 = off)
//   hex on|off          dump every frame in hex
//   selftest            frame split/reassembly checks at MTU 23, 185, 517
//   # comment
namespace inklink::ble {
void runLoopbackFromEnv();
}

#endif
