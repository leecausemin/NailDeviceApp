"""PC side of the NailDeviceApp remote control (app: data/remote/RemoteControlServer.kt).

The app listens on 127.0.0.1:8765 *on the phone*; `adb forward` makes that port reachable as
localhost:8765 on this PC over the USB cable. One text line per command, one line back:

    PING            -> PONG <phone epoch ms>
    STATUS          -> STATUS conn=<..> rec=<..> file=<..>
    START <label>   -> OK <phone epoch ms>
    STOP            -> SAVED <name> <downloads|custom> <phone epoch ms>

Quick check (phone on USB, app open, device connected in the app):
    python phone_remote.py
"""
import os
import re
import shutil
import socket
import subprocess
import time
from pathlib import Path

DEFAULT_PORT = 8765
PHONE_DOWNLOAD_DIR = "/sdcard/Download/NailDeviceApp"


def find_adb() -> str:
    """adb on PATH, else the Android Studio SDK copy."""
    exe = shutil.which("adb")
    if exe:
        return exe
    sdk = Path(os.environ.get("ANDROID_SDK_ROOT")
               or os.environ.get("ANDROID_HOME")
               or Path(os.environ.get("LOCALAPPDATA", "")) / "Android" / "Sdk")
    cand = sdk / "platform-tools" / "adb.exe"
    if cand.exists():
        return str(cand)
    raise FileNotFoundError("adb not found: install Android platform-tools or set ANDROID_SDK_ROOT")


class PhoneError(RuntimeError):
    pass


class PhoneRecorder:
    def __init__(self, port: int = DEFAULT_PORT, serial: str | None = None, timeout_s: float = 5.0):
        self.port = port
        self.serial = serial
        self.timeout_s = timeout_s
        self.adb = find_adb()
        self.sock = None
        self.rfile = None
        self.offset_ms = None   # phone clock - PC clock [ms]
        self.rtt_ms = None

    # ------------------------------------------------------------------ plumbing
    def _adb(self, *args, timeout=60):
        cmd = [self.adb] + (["-s", self.serial] if self.serial else []) + list(args)
        r = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
        if r.returncode != 0:
            raise PhoneError(f"adb {' '.join(args)} failed: {r.stderr.strip() or r.stdout.strip()}")
        return r.stdout

    def connect(self):
        self._adb("forward", f"tcp:{self.port}", f"tcp:{self.port}")
        self.sock = socket.create_connection(("127.0.0.1", self.port), timeout=self.timeout_s)
        self.sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        self.rfile = self.sock.makefile("r", encoding="utf-8", newline="\n")
        st = self.status()   # also proves the app answers (adb forward alone always "connects")
        if st.get("conn") != "Connected":
            raise PhoneError(f"App is up but the nail device is not connected (conn={st.get('conn')})")
        self.sync_clock()
        return st

    def close(self):
        try:
            if self.sock:
                self.sock.close()
        finally:
            self.sock = self.rfile = None

    def _cmd(self, line: str, timeout_s: float | None = None) -> str:
        if self.sock is None:
            raise PhoneError("not connected")
        self.sock.settimeout(timeout_s or self.timeout_s)
        self.sock.sendall((line + "\n").encode("utf-8"))
        reply = self.rfile.readline().strip()
        if not reply:
            raise PhoneError(f"no reply to {line!r} (is the app open?)")
        if reply.startswith("ERR"):
            raise PhoneError(f"{line!r}: {reply}")
        return reply

    # ------------------------------------------------------------------ commands
    def status(self) -> dict:
        reply = self._cmd("STATUS")
        return dict(re.findall(r"(\w+)=(\S+)", reply))

    def sync_clock(self, n: int = 15) -> float:
        """Phone-minus-PC clock offset from the PING with the shortest round trip (NTP style)."""
        best = None
        for _ in range(n):
            t0 = time.time() * 1000
            phone = float(self._cmd("PING").split()[1])
            t1 = time.time() * 1000
            rtt = t1 - t0
            if best is None or rtt < best[0]:
                best = (rtt, phone - (t0 + t1) / 2)
        self.rtt_ms, self.offset_ms = best
        return self.offset_ms

    def phone_ms_to_pc_unix(self, phone_ms: float) -> float:
        return (float(phone_ms) - self.offset_ms) / 1000.0

    def start(self, label: str) -> dict:
        t_send = time.time()
        reply = self._cmd(f"START {label}")
        return dict(pc_send_unix=t_send, phone_ack_ms=float(reply.split()[1]))

    def stop(self) -> dict:
        t_send = time.time()
        reply = self._cmd("STOP", timeout_s=70)   # app waits for both CSVs to be flushed + published
        _, name, where, phone_ms = reply.split()
        return dict(pc_send_unix=t_send, name=name, where=where, phone_stop_ms=float(phone_ms))

    def pull(self, name: str, dest_dir: Path, where: str = "downloads") -> Path:
        """Copies the recording folder nail_<stamp>_<label>/ from the phone into dest_dir."""
        if where != "downloads":
            raise PhoneError("App save location is a custom folder; set it back to the default "
                             "(Downloads/NailDeviceApp) so the PC can adb pull it")
        dest_dir = Path(dest_dir)
        dest_dir.mkdir(parents=True, exist_ok=True)
        self._adb("pull", f"{PHONE_DOWNLOAD_DIR}/{name}", str(dest_dir), timeout=300)
        out = dest_dir / name
        if not (out / f"{name}_imu.csv").exists():
            raise PhoneError(f"pulled {name} but {name}_imu.csv is missing in {out}")
        return out


def read_session_note(folder: Path) -> dict:
    """Phone-clock times the app writes into nail_*_session.txt (epoch ms)."""
    folder = Path(folder)
    txt = (folder / f"{folder.name}_session.txt").read_text(encoding="utf-8", errors="ignore")
    keys = {"started": r"^\s*started\s+(\d+)", "stopped": r"^\s*stopped\s+(\d+)",
            "first_mic": r"first mic packet\s+(\d+)", "first_imu": r"first IMU packet\s+(\d+)"}
    out = {}
    for k, pat in keys.items():
        m = re.search(pat, txt, flags=re.M)
        out[k] = float(m[1]) if m else None
    return out


if __name__ == "__main__":
    p = PhoneRecorder()
    print("status:", p.connect())
    print(f"clock offset {p.offset_ms:+.1f} ms (rtt {p.rtt_ms:.1f} ms)")
    print("start:", p.start("remote_test"))
    time.sleep(3)
    s = p.stop()
    print("stop:", s)
    folder = p.pull(s["name"], Path("phone_test"), s["where"])
    print("pulled:", folder, read_session_note(folder))
    p.close()
