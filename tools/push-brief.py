#!/usr/bin/env python3
"""Send short lines to the TV screensaver (shown one at a time under the clock).

Usage:
    push-brief.py "Rain from 4 pm" "Dentist 6:30" "Mom's birthday tomorrow"
    echo "one line per row" | push-brief.py
    push-brief.py --clear

Lines go to the screensaver's own folder on the TV. Keep them short (max 12 lines, 16 KB).
The screensaver re-reads the file every minute and each time it starts.
"""
import base64
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import tv  # noqa: E402

PKG = "com.gathin.tvhome"
DIR = f"/sdcard/Android/data/{PKG}/files"
FILE = f"{DIR}/brief.txt"


def main():
    args = sys.argv[1:]
    if args == ["--clear"]:
        lines = []
    elif args:
        lines = args
    else:
        lines = [ln.rstrip("\n") for ln in sys.stdin]
    lines = [ln.strip() for ln in lines if ln.strip()][:12]
    payload = base64.b64encode("\n".join(lines).encode("utf-8")).decode()
    dev = tv.connect()
    dev.shell(f"mkdir -p {DIR}")
    out = dev.shell(f"echo '{payload}' | base64 -d > {FILE} && echo ok", read_timeout_s=30.0)
    dev.close()
    if "ok" not in out:
        sys.exit(f"write failed: {out.strip()}")
    print(f"sent {len(lines)} line(s) to the TV")


if __name__ == "__main__":
    main()
