#!/usr/bin/env python3
"""Resets a forgotten Folio PIN over USB.

The reader prints a one-time challenge (16 random bytes + its MAC); this
script signs "FOLIO-PINRESET-v1\\n" + challenge with the Folio release key
(the same Ed25519 key that signs firmware) and sends the signature back. On
success the PIN and the wake lock are removed; protected books stay marked and
ask for a new PIN before they open.

    ../.venv/bin/python tools/inklink/reset_pin.py            # auto-detects the port
    ../.venv/bin/python tools/inklink/reset_pin.py --port /dev/cu.usbmodem1101

Key: $FOLIO_SIGNING_KEY_FILE, else ~/Documents/Projects/Crosspoint/secrets/
folio-signing-ed25519.pem. OpenSSL 3 is required (pkeyutl -rawin).

Simulator: --sim-in <fifo> --sim-log <log> talk to a simulator started with
CROSSPOINT_SIM_SERIAL_IN=<fifo> and its output redirected to <log>.
"""

import argparse
import glob
import os
import shutil
import subprocess
import sys
import tempfile
import time

PREFIX = b"FOLIO-PINRESET-v1\n"
DEFAULT_KEY = os.path.expanduser("~/Documents/Projects/Crosspoint/secrets/folio-signing-ed25519.pem")


def die(msg):
    print(f"reset_pin: {msg}", file=sys.stderr)
    sys.exit(1)


def openssl():
    brew = "/opt/homebrew/bin/openssl"
    exe = brew if os.access(brew, os.X_OK) else shutil.which("openssl")
    if not exe:
        die("openssl not found")
    version = subprocess.run([exe, "version"], capture_output=True, text=True).stdout
    if not version.startswith("OpenSSL 3"):
        die(f"OpenSSL 3 required, got: {version.strip()}")
    return exe


def sign(challenge_hex, key):
    challenge = bytes.fromhex(challenge_hex)
    if len(challenge) != 22:
        die(f"unexpected challenge length {len(challenge)}")
    with tempfile.TemporaryDirectory() as tmp:
        msg = os.path.join(tmp, "msg")
        sig = os.path.join(tmp, "sig")
        with open(msg, "wb") as f:
            f.write(PREFIX + challenge)
        subprocess.run([openssl(), "pkeyutl", "-sign", "-inkey", key, "-rawin", "-in", msg, "-out", sig], check=True)
        with open(sig, "rb") as f:
            signature = f.read()
    if len(signature) != 64:
        die("signature is not 64 bytes")
    return signature.hex()


class SerialLink:
    def __init__(self, port):
        import serial  # pyserial, shipped with PlatformIO

        self.port = serial.Serial(port, 115200, timeout=0.2)
        self.port.reset_input_buffer()

    def send(self, line):
        self.port.write((line + "\n").encode())
        self.port.flush()

    def lines(self):
        buf = b""
        while True:
            chunk = self.port.read(256)
            if not chunk:
                yield None
                continue
            buf += chunk
            while b"\n" in buf:
                line, buf = buf.split(b"\n", 1)
                yield line.decode(errors="replace").strip()


class SimLink:
    def __init__(self, fifo, log):
        self.fifo = fifo
        self.log = open(log, "r", errors="replace")
        self.log.seek(0, os.SEEK_END)

    def send(self, line):
        with open(self.fifo, "w") as f:
            f.write(line + "\n")

    def lines(self):
        while True:
            line = self.log.readline()
            if not line:
                time.sleep(0.1)
                yield None
                continue
            yield line.strip()


def wait_for(link, prefixes, timeout):
    deadline = time.time() + timeout
    for line in link.lines():
        if time.time() > deadline:
            return None
        if line:
            for p in prefixes:
                if p in line:
                    return line[line.index(p):]
    return None


def detect_port():
    ports = sorted(glob.glob("/dev/cu.usbmodem*"))
    if len(ports) != 1:
        die(f"pass --port (found: {', '.join(ports) or 'none'}); the reader must be connected by USB")
    return ports[0]


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--port")
    ap.add_argument("--key", default=os.environ.get("FOLIO_SIGNING_KEY_FILE", DEFAULT_KEY))
    ap.add_argument("--sim-in")
    ap.add_argument("--sim-log")
    args = ap.parse_args()
    if not os.access(args.key, os.R_OK):
        die(f"signing key not readable: {args.key}")

    link = SimLink(args.sim_in, args.sim_log) if args.sim_in else SerialLink(args.port or detect_port())
    link.send("CMD:PINRESET")
    line = wait_for(link, ["PINRESET_CHALLENGE:"], 5)
    if not line:
        die("no challenge from the reader (is it awake and running Folio 0.3.0 or newer?)")
    challenge = line.split(":", 1)[1].strip()
    link.send("CMD:PINRESET:" + sign(challenge, args.key))
    result = wait_for(link, ["PINRESET_OK", "PINRESET_FAIL"], 5)
    if result != "PINRESET_OK":
        die(f"reader refused the reset ({result or 'no answer'})")
    print("PIN removed. Set a new one in Settings → System → Protection.")


if __name__ == "__main__":
    main()
