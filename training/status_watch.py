"""Keeps a live progress section at the top of STATUS.md while evaluate.py runs (works offline).

    python status_watch.py data/eval-r1-fixed.log data/eval-r2-fixed.log

Every 30 seconds it reads the logs and, when they changed, rewrites the part of STATUS.md between the
LIVE PROGRESS markers: calls done, passes, the latest result and the final score. It stops by itself once every
log has its final score line.
"""
import datetime as dt
import re
import sys
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
STATUS = HERE / "STATUS.md"
START, END = "<!-- LIVE PROGRESS START -->", "<!-- LIVE PROGRESS END -->"
LINE = re.compile(r"^\[(\d+)/(\d+)\] (PASS|FAIL) (.*)$")
FINAL = re.compile(r"^(\S+): (\d+)/(\d+) calls handled correctly")


def summary(log: Path) -> tuple[str, bool]:
    text = log.read_text(encoding="utf-8", errors="replace") if log.exists() else ""
    calls = [m for m in map(LINE.match, text.splitlines()) if m]
    final = next((m for m in map(FINAL.match, text.splitlines()) if m), None)
    if final:
        return f"- `{log.name}`: **finished — {final.group(2)}/{final.group(3)}**", True
    if not calls:
        return f"- `{log.name}`: not started yet", False
    passed = sum(m.group(3) == "PASS" for m in calls)
    last = calls[-1]
    return (f"- `{log.name}`: {len(calls)} of {last.group(2)} calls done in this log, {passed} passed "
            f"(a resumed run only lists the calls it ran). Latest: [{last.group(1)}] {last.group(3)} {last.group(4)[:160]}"), False


def write(block: str) -> None:
    status = STATUS.read_text(encoding="utf-8")
    new = f"{START}\n{block}\n{END}"
    if START in status and END in status:
        status = status[:status.index(START)] + new + status[status.index(END) + len(END):]
    else:
        status = new + "\n\n" + status
    STATUS.write_text(status, encoding="utf-8")


def main() -> None:
    logs = [HERE / p for p in sys.argv[1:]]
    seen = None
    while True:
        parts = [summary(p) for p in logs]
        lines = [s for s, _ in parts]
        if lines != seen:
            stamp = dt.datetime.now().strftime("%d %b %Y, %I:%M %p")
            write(f"## Live re-score progress (updated {stamp} by status_watch.py)\n" + "\n".join(lines))
            seen = lines
        if all(done for _, done in parts):
            return
        time.sleep(30)


if __name__ == "__main__":
    main()
