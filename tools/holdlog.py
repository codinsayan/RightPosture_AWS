"""Turns the phone's RPHold log into a readable test record.

A hold session is performed across the room, facing a wall, with nobody at the
phone -- so there is nobody to watch the screen while it happens. This reads the
log the app leaves behind instead, and appends one dated block per run to
docs/hold-test-log.md.

    python tools/holdlog.py start   # before a run: clears the device buffer
    python tools/holdlog.py stop    # after a run: parses it into the log

`stop` prints only a one-line summary; the detail goes to the file.
"""

import os
import re
import subprocess
import sys
from datetime import datetime

ADB = os.path.join(
    os.environ.get("LOCALAPPDATA", ""), "Android", "Sdk", "platform-tools", "adb.exe"
)
LOG = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                   "docs", "hold-test-log.md")

# "09-05 14:16:03.123 I/RPHold  ( 1234): recording started - ..."
LINE = re.compile(r"^(\d\d-\d\d \d\d:\d\d:\d\d)\.\d+\s+I/RPHold\s*\(\s*\d+\):\s*(.*)$")


def adb(*args):
    return subprocess.run([ADB, *args], capture_output=True, text=True, timeout=60)


def require_device():
    out = adb("devices").stdout.strip().splitlines()[1:]
    live = [l.split("\t")[0] for l in out if l.strip().endswith("device")]
    if not live:
        sys.exit("no device: " + (" / ".join(out) or "adb devices listed nothing"))
    return live[0]


def start():
    serial = require_device()
    adb("logcat", "-c")
    print("buffer cleared on %s - run the test, then: python tools/holdlog.py stop" % serial)


def parse(events):
    """Group the flat event list into reference recordings and live attempts."""
    ref, live, other = [], [], []
    for when, text in events:
        if text.startswith(("recording started", "restart ", "finishing", "authored")):
            ref.append((when, text))
        elif text.startswith(("live hold broke", "back in position", "live session")):
            live.append((when, text))
        else:
            other.append((when, text))
    return ref, live, other


def render(events):
    ref, live, other = parse(events)
    out = ["", "## Run %s - %d events" % (datetime.now().strftime("%Y-%m-%d %H:%M"), len(events))]

    if ref:
        out += ["", "### Reference recording", ""]
        for when, text in ref:
            out.append("- `%s` %s" % (when, text))

    breaks = [(w, t) for w, t in live if t.startswith("live hold broke")]
    if breaks:
        out += ["", "### Live holds", "",
                "| # | broke at | quality | drift | joint |",
                "|---|---|---|---|---|"]
        for i, (_, text) in enumerate(breaks, 1):
            m = re.search(r"broke at (\d+)s .*?quality=(\S+) drift=(\S+) on (\S+)", text)
            if m:
                out.append("| %d | %s s | %s | %s sigma/s | %s |" % (i, *m.groups()))
            else:
                # An older build, before the break line carried the numbers.
                s = re.search(r"broke at (\d+)s", text)
                out.append("| %d | %s s | - | - | - |" % (i, s.group(1) if s else "?"))

    rest = [(w, t) for w, t in live if not t.startswith("live hold broke")]
    if rest:
        out += ["", "### Live session events", ""]
        for when, text in rest:
            out.append("- `%s` %s" % (when, text))

    if other:
        out += ["", "### Unrecognised", ""]
        for when, text in other:
            out.append("- `%s` %s" % (when, text))

    if not events:
        out += ["", "_Nothing logged. The hold flow was not reached, or the buffer was not cleared._"]
    return "\n".join(out) + "\n"


def stop():
    require_device()
    raw = adb("logcat", "-d", "-v", "time", "-s", "RPHold:I").stdout
    events = [(m.group(1), m.group(2).strip())
              for m in (LINE.match(l) for l in raw.splitlines()) if m]

    os.makedirs(os.path.dirname(LOG), exist_ok=True)
    if not os.path.exists(LOG):
        with open(LOG, "w", encoding="utf-8", newline="\n") as f:
            f.write("# Hold test log\n\nAppended by `tools/holdlog.py`. "
                    "Newest run at the bottom.\n")
    with open(LOG, "a", encoding="utf-8", newline="\n") as f:
        f.write(render(events))

    breaks = sum(1 for _, t in events if t.startswith("live hold broke"))
    print("%d events (%d live breaks) appended to docs/hold-test-log.md" % (len(events), breaks))


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else ""
    if cmd == "start":
        start()
    elif cmd == "stop":
        stop()
    else:
        sys.exit(__doc__)
