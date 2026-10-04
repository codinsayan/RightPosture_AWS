"""Captures a device run into docs/device-run-log.md instead of onto the terminal.

Reading logcat live costs an enormous amount of context for a handful of useful
lines, and most of a run is irrelevant until something goes wrong. This writes
the whole filtered run to a file and prints one line, so the detail is on disk
and searchable when it is actually needed.

    python tools/devicelog.py start          # before a run: starts streaming to build/
    python tools/devicelog.py stop           # after a run: ends the stream, parses it into the log
    python tools/devicelog.py stop --show    # ...and prints problems to the terminal

Generalises tools/holdlog.py, which does the same thing for RPHold alone.

Each `stop` appends one dated block, newest at the bottom, headed by a VERDICT
line -- so the first few lines of a block answer "did it work" without reading
the rest.
"""

import os
import re
import subprocess
import sys
from datetime import datetime

ADB = os.path.join(
    os.environ.get("LOCALAPPDATA", ""), "Android", "Sdk", "platform-tools", "adb.exe"
)
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LOG = os.path.join(ROOT, "docs", "device-run-log.md")
PACKAGE = "com.example.rightposture"

# The app's own tags. Add one here when a new subsystem starts logging.
TAGS = {
    "RPGhost": "Ghost",
    "RPRep": "Rep acceptance",
    "RPCalib": "Calibration",
    "RPHold": "Hold",
    "RPSummary": "Summary",
    "RPStore": "Storage",
}
ORDER = ["RPGhost", "RPRep", "RPCalib", "RPHold", "RPSummary", "RPStore"]

# "09-05 14:16:03.123 I/RPHold  ( 1234): text"
LINE = re.compile(
    r"^(\d\d-\d\d \d\d:\d\d:\d\d)\.\d+\s+([VDIWEF])/([\w.$]+)\s*\(\s*(\d+)\):\s*(.*)$"
)

# A real crash, as opposed to the AndroidRuntime tag's ordinary startup chatter
# ("Using default boot image", "VM exiting with result code 0"). The -b crash
# buffer is the authority; this catches the same thing in the main buffer.
CRASH_TEXT = re.compile(r"FATAL EXCEPTION|ANR in |Force finishing|signal \d+ \(SIG")

# Vendor noise this phone emits at E level constantly and none of it ours. Kept
# as a denylist rather than an allowlist so a genuine new error is never hidden;
# add to it only after confirming a line is not the app's doing.
NOISE = re.compile(
    r"Access denied finding property"
    r"|Unknown bits set in runtime_flags"
    r"|SchedAssist|ashmem|Oplus|OPM-|Osense|UAH_CLIENT|QosScene|GameManagerService"
    # ColorOS's scroll-jank tracker, logged at E into our process on every fling. Not ours.
    r"|IJankManager"
    # MediaPipe's TFLite runtime, logged at E from inside the pose landmarker on this device. A
    # library warning about its own tensor reuse, not something the app does. Seen 2026-09-30.
    r"|Tensors are designed for single writes"
    r"|Pinning is deprecated"
)


STATE = os.path.join(ROOT, "build", "devicelog-start.txt")

# The run is streamed to disk as it happens. This phone's main log buffer is 256 KiB and ColorOS
# fills it in minutes, so a `logcat -d` at stop time had already lost every app line by the time it
# ran - every "nothing logged" verdict before 2026-09-30 was that, not a quiet app.
STREAM = os.path.join(ROOT, "build", "devicelog-stream.txt")
STREAM_PID = os.path.join(ROOT, "build", "devicelog-stream.pid")

# Every adb call is bounded. This phone's ROM hangs `logcat -c` indefinitely and
# wedges the whole connection when a read is killed mid-stream, so a call that
# does not answer quickly is treated as a failure rather than waited on.
TIMEOUT = 25


def adb(*args, timeout=TIMEOUT):
    """Returns the completed process, or None when adb hung or is not answering."""
    try:
        return subprocess.run([ADB, *args], capture_output=True, text=True,
                              timeout=timeout, errors="replace")
    except subprocess.TimeoutExpired:
        return None


def out_of(result):
    return result.stdout if result else ""


def require_device():
    result = adb("devices")
    if result is None:
        sys.exit("adb is not answering. Unplug and replug the phone, then retry.")
    rows = result.stdout.strip().splitlines()[1:]
    live = [r.split("\t")[0] for r in rows if r.strip().endswith("device")]
    if live:
        return live[0]
    detail = " / ".join(r.strip() for r in rows if r.strip())
    if "offline" in detail:
        sys.exit("device is offline. Unplug and replug the USB cable.")
    if "unauthorized" in detail:
        sys.exit("device is unauthorized. Accept the USB debugging prompt on the phone.")
    sys.exit("no device: " + (detail or "adb devices listed nothing"))


def app_pids():
    """PIDs of the app, so its own crashes are caught whatever they are tagged."""
    # Both the regular build and the side-by-side "ryt" build (com.example.rightposture.ryt).
    return {p for p in out_of(adb("shell", "pidof", PACKAGE, PACKAGE + ".ryt")).split() if p.isdigit()}


def since():
    """The `MM-DD HH:MM:SS` recorded by `start`, or None if there was none."""
    try:
        with open(STATE, encoding="utf-8") as f:
            return f.read().strip() or None
    except OSError:
        return None


def after(when, floor):
    """Log timestamps sort lexically within a year, which is all this needs."""
    return not floor or not when or when >= floor


def crash_buffer(floor):
    """The dedicated crash buffer, which holds crashes and nothing else."""
    raw = out_of(adb("logcat", "-b", "crash", "-d", "-v", "time", "-t", "500"))
    out = []
    keeping = True
    for line in raw.splitlines():
        if not line.strip() or line.startswith("---------"):
            continue
        m = LINE.match(line)
        if m:
            when, _, tag, _, text = m.groups()
            keeping = after(when, floor)
            if keeping:
                out.append((when, tag, text))
        elif keeping:
            # Stack-trace continuation: no tag of its own, belongs to the frame above.
            out.append(("", "trace", line.strip()))
    # The crash buffer is device-wide. On this ROM it carries other processes' crashes and libc
    # unwinder chatter, which read as our app crashing. A crash of ours names the package - Java as
    # "Process: <pkg>", native as ">>> <pkg> <<<" - so keep only entries from those moments.
    ours = {when for when, _, text in out if when and PACKAGE in text}
    kept, keeping = [], False
    for entry in out:
        if entry[0]:
            keeping = entry[0] in ours
        if keeping:
            kept.append(entry)
    return kept


def parsed(raw):
    for line in raw.splitlines():
        m = LINE.match(line)
        if m:
            yield m.groups()


def read_stream():
    """What the background stream captured, or None when no stream ran."""
    try:
        with open(STREAM, encoding="utf-8", errors="replace") as f:
            return f.read()
    except OSError:
        return None


def collect(floor, streamed=None):
    """Everything worth keeping, as (when, level, tag, text).

    From the streamed file when `start` launched one - the only reliable source on
    this phone. Otherwise two narrow, line-bounded reads of whatever the buffer
    still holds, which on a busy ROM may be nothing of ours.
    """
    kept = []

    if streamed is not None:
        tagged_raw = streamed
        error_raw = streamed
    else:
        spec = ["%s:I" % t for t in ORDER] + ["*:S"]
        tagged_raw = out_of(adb("logcat", "-d", "-v", "time", "-t", "4000", *spec))
        error_raw = out_of(adb("logcat", "-d", "-v", "time", "-t", "4000", "*:E"))

    # 1. The app's own tags.
    for when, level, tag, _pid, text in parsed(tagged_raw):
        if tag in TAGS and level in "IWEF" and after(when, floor):
            kept.append((when, level, tag, text))

    # 2. Errors from anywhere, then denoised here. Kept broad on purpose: an
    #    allowlist would hide the error nobody predicted.
    pids = app_pids()
    for when, level, tag, pid, text in parsed(error_raw):
        if tag in TAGS or level not in "EF" or not after(when, floor):
            continue
        if CRASH_TEXT.search(text):
            kept.append((when, "F", tag, text))
        elif NOISE.search(tag) or NOISE.search(text):
            continue
        # The app's own process-name tag, which Android truncates from the left.
        elif pid in pids or tag in PACKAGE or PACKAGE.endswith(tag):
            kept.append((when, level, tag, text))
    return kept


def split(events):
    crashes, errors, tagged, other = [], [], {t: [] for t in TAGS}, []
    for when, level, tag, text in events:
        if level == "F":
            crashes.append((when, tag, text))
        elif tag in TAGS:
            tagged[tag].append((when, level, text))
        elif level == "E":
            errors.append((when, tag, text))
        else:
            other.append((when, tag, text))
    return crashes, errors, tagged, other


def render(events, fatals, alive, note):
    crashes, errors, tagged, other = split(events)
    crashes = [(w, t, x) for w, t, x in crashes] + fatals
    stamp = datetime.now().strftime("%Y-%m-%d %H:%M")

    if crashes:
        verdict = "CRASHED - %d fatal line(s)" % len(crashes)
    elif events and errors:
        verdict = "RAN, with %d error(s)" % len(errors)
    elif events:
        verdict = "RAN CLEAN - %d events, no errors" % len(events)
    elif alive:
        # Not a failure. Most screens log nothing, so an empty run with a live
        # process means the app is up and quiet, not that it never started.
        verdict = "RAN CLEAN - app is running, nothing logged this run"
    else:
        verdict = "NOTHING LOGGED and the app is not running - was it launched?"

    out = ["", "## Run %s" % stamp, "", "**VERDICT: %s**" % verdict]
    if note:
        out += ["", "Note: %s" % note]

    counts = ["%s %d" % (TAGS[t], len(tagged[t])) for t in ORDER if tagged[t]]
    if counts:
        out += ["", "Events: " + ", ".join(counts)]

    if crashes:
        out += ["", "### Crashes", "", "```"]
        out += ["%s %s: %s" % (w, t, x) if w else x for w, t, x in crashes[:80]]
        if len(crashes) > 80:
            out.append("... %d more" % (len(crashes) - 80))
        out += ["```"]

    if errors:
        out += ["", "### Errors", ""]
        for when, tag, text in errors[:40]:
            out.append("- `%s` **%s** %s" % (when, tag, text))
        if len(errors) > 40:
            out.append("- ... %d more" % (len(errors) - 40))

    for tag in ORDER:
        rows = tagged[tag]
        if not rows:
            continue
        out += ["", "### %s" % TAGS[tag], ""]
        for when, level, text in rows:
            mark = "**!** " if level in ("W", "E") else ""
            out.append("- `%s` %s%s" % (when, mark, text))

    if other:
        out += ["", "### Other warnings", ""]
        for when, tag, text in other[:20]:
            out.append("- `%s` %s: %s" % (when, tag, text))
        if len(other) > 20:
            out.append("- ... %d more" % (len(other) - 20))

    return "\n".join(out) + "\n", verdict, crashes, errors


def start():
    """Marks where this run begins.

    Clearing the buffer would be tidier, but `logcat -c` hangs forever on this
    phone's ROM and takes the whole adb connection down with it when killed. So
    the buffer is left alone and a timestamp is recorded instead; `stop` drops
    anything older. Clearing is still attempted first, briefly, because where it
    works it is exact.
    """
    serial = require_device()
    stamp = out_of(adb("shell", 'date "+%m-%d %H:%M:%S.000"', timeout=10)).strip()

    cleared = adb("logcat", "-c", timeout=10) is not None
    os.makedirs(os.path.dirname(STATE), exist_ok=True)
    with open(STATE, "w", encoding="utf-8") as f:
        f.write("" if cleared else stamp)

    # Ask for a larger buffer too; some ROMs refuse, which the stream makes harmless.
    adb("logcat", "-G", "4M", timeout=10)
    streaming = start_stream()

    how = "buffer cleared" if cleared else "marked at %s (buffer will not clear on this ROM)" % stamp
    if not cleared and not stamp:
        print("WARNING: could not clear the buffer or read the device clock; "
              "the next stop will include older lines.")
    if not streaming:
        print("WARNING: could not start the background stream; stop will read the buffer, "
              "which this phone overwrites within minutes.")
    print("%s, %s on %s - run the test, then: python tools/devicelog.py stop"
          % (how, "streaming to build/" if streaming else "not streaming", serial))


def start_stream():
    """Starts `adb logcat` writing the app's tags and every error to STREAM, detached.

    Detached so it outlives this script and the terminal call that ran it; `stop`
    ends it by the PID recorded here.
    """
    stop_stream()
    spec = ["%s:V" % t for t in ORDER] + ["*:E"]
    try:
        out = open(STREAM, "w", encoding="utf-8")
        flags = 0
        if os.name == "nt":
            flags = subprocess.CREATE_NEW_PROCESS_GROUP | 0x00000008  # DETACHED_PROCESS
        proc = subprocess.Popen([ADB, "logcat", "-v", "time", *spec], stdout=out,
                                stderr=subprocess.DEVNULL, stdin=subprocess.DEVNULL,
                                creationflags=flags)
        out.close()
    except OSError:
        return False
    with open(STREAM_PID, "w", encoding="utf-8") as f:
        f.write(str(proc.pid))
    return True


def stop_stream():
    """Ends a stream started by `start_stream`. Returns True if one was running."""
    try:
        with open(STREAM_PID, encoding="utf-8") as f:
            pid = int(f.read().strip())
    except (OSError, ValueError):
        return False
    if os.name == "nt":
        subprocess.run(["taskkill", "/PID", str(pid), "/F", "/T"], capture_output=True)
    else:
        try:
            os.kill(pid, 15)
        except OSError:
            pass
    try:
        os.remove(STREAM_PID)
    except OSError:
        pass
    return True


def stop(note, show):
    require_device()
    floor = since()
    streamed = read_stream() if stop_stream() else None
    events = collect(floor, streamed)
    if streamed is None:
        note = (note + " " if note else "") + "[no stream - read from the buffer, which may have lost lines]"
    text, verdict, crashes, errors = render(events, crash_buffer(floor), bool(app_pids()), note)

    os.makedirs(os.path.dirname(LOG), exist_ok=True)
    if not os.path.exists(LOG):
        with open(LOG, "w", encoding="utf-8", newline="\n") as f:
            f.write("# Device run log\n\nAppended by `tools/devicelog.py`. "
                    "Newest run at the bottom.\n")
    with open(LOG, "a", encoding="utf-8", newline="\n") as f:
        f.write(text)

    print("%s -> docs/device-run-log.md" % verdict)
    if show:
        for when, tag, body in crashes[:25]:
            print("  %s %s" % (tag, body))
        for when, tag, body in errors[:25]:
            print("  E/%s %s" % (tag, body))


if __name__ == "__main__":
    args = sys.argv[1:]
    cmd = args[0] if args else ""
    show = "--show" in args
    note = ""
    if "--note" in args:
        note = " ".join(args[args.index("--note") + 1:]).replace("--show", "").strip()

    if cmd == "start":
        start()
    elif cmd == "stop":
        stop(note, show)
    else:
        sys.exit(__doc__)
