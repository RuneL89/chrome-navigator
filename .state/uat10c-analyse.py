#!/usr/bin/env python3
"""UAT 10C row analyser — pull the device DB and print one task's decision rows.

Usage:
  python .state/uat10c-analyse.py                 # pull, list recent tasks
  python .state/uat10c-analyse.py <taskId>        # pull, print that task's rows + terminal
  python .state/uat10c-analyse.py <taskId> --no-pull

Reads: databases/navigator-decision-log.db{,-wal,-shm} via `adb exec-out run-as`.
Pulls into .state/uat10c-db-live/ (temp-copied for WAL replay before opening).

Prints, per row: step, time, triage, chosen intent + p/margin (Cascade v1.7.0 law:
LOW_FLOOR 0.30 / HIGH_FLOOR 0.55 / HIGH_MARGIN 0.15), executed action, outcome,
the Phase-10C evidence fields (targetScores/targetStateText/assistText/landingHost/
plan), then the task_terminal row.
"""
import json
import os
import shutil
import sqlite3
import subprocess
import sys
import tempfile

ADB = r"C:\Users\atavi\AppData\Local\Android\Sdk\platform-tools\adb.exe"
DB = "navigator-decision-log.db"
LIVE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "uat10c-db-live")
LOW_FLOOR, HIGH_FLOOR, HIGH_MARGIN = 0.30, 0.55, 0.15


def device() -> str:
    out = subprocess.run([ADB, "devices"], capture_output=True, text=True).stdout
    for line in out.splitlines()[1:]:
        if "\tdevice" in line and "emulator" not in line:
            return line.split("\t")[0].strip()
    raise SystemExit("no physical device on adb")


def pull() -> str:
    serial = device()
    os.makedirs(LIVE, exist_ok=True)
    for name in (DB, DB + "-wal", DB + "-shm"):
        with open(os.path.join(LIVE, name), "wb") as fh:
            subprocess.run(
                [ADB, "-s", serial, "exec-out", "run-as", "navigator.app", "cat",
                 "databases/" + name], stdout=fh, check=False)
    return serial


def connect() -> sqlite3.Connection:
    d = tempfile.mkdtemp()
    for name in os.listdir(LIVE):
        shutil.copy(os.path.join(LIVE, name), os.path.join(d, name))
    return sqlite3.connect(os.path.join(d, DB))


def band(p: float, margin: float) -> str:
    if p < LOW_FLOOR:
        return "LOW"
    if p < HIGH_FLOOR or margin < HIGH_MARGIN:
        return "UNCERTAIN"
    return "HIGH"


def show_task(c: sqlite3.Connection, task: str) -> None:
    cols = [r[1] for r in c.execute("pragma table_info(decision_log)")]
    sql = "select * from decision_log where taskId=? order by stepIndex"
    rows = [dict(zip(cols, r)) for r in c.execute(sql, (task,))]
    if not rows:
        print("no rows for", task)
        return
    print(f"\n=== {task} — {rows[0]['goal']!r} ({len(rows)} rows) ===")
    for row in rows:
        ts = row["timestamp"] / 1000
        import datetime
        when = datetime.datetime.fromtimestamp(ts).strftime("%H:%M:%S")
        ans = json.loads(row["teacherAnswerJson"]) if row["teacherAnswerJson"] else None
        prob = ""
        if ans and ans.get("probabilities"):
            probs = ans["probabilities"]
            chosen = ans["chosen"]
            p = probs.get(chosen, 0.0)
            margin = p - max([v for k, v in probs.items() if k != chosen], default=0.0)
            prob = f" p={p:.3f} margin={margin:.3f} -> {band(p, margin)}"
        act = json.loads(row["executedActionJson"]) if row["executedActionJson"] else None
        gate = json.loads(row["gateEventJson"]) if row["gateEventJson"] else None
        chosen = (ans or {}).get("chosen", "-")
        print(f"\n[step {row['stepIndex']}] {when}  triage={row['triageClass']}"
              f"  chosen={chosen}{prob}")
        print(f"  outcome={row['outcome']}  action={act}  gate={gate}"
              f"  escalated={row['escalated']}")
        if row["planJson"]:
            plan = json.loads(row["planJson"])
            print(f"  plan={plan.get('version')} subtasks={len(plan.get('subgoals', []))}")
        if row["targetScoresJson"]:
            print(f"  targetScores={row['targetScoresJson']}")
        if row["targetStateText"]:
            head = "\n      ".join(row["targetStateText"].splitlines()[:6])
            print(f"  targetStateText:\n      {head}")
        if row["assistText"]:
            print(f"  assistText={row['assistText'][:200]!r}")
        if row["landingHost"]:
            print(f"  landingHost={row['landingHost']!r}")
        if row["fallbackJson"]:
            print(f"  fallback={row['fallbackJson'][:200]}")
        if row["exitJson"]:
            print(f"  exit={row['exitJson'][:220]}")
        if row["typedSecret"]:
            print(f"  typedSecret={row['typedSecret']}")
    for term in c.execute("select * from task_terminal where taskId=?", (task,)):
        print(f"\nTERMINAL: {term}")


def main() -> None:
    task = None
    no_pull = "--no-pull" in sys.argv
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    if args:
        task = args[0]
    if not no_pull:
        serial = pull()
        print("pulled from", serial)
    c = connect()
    print("user_version:", c.execute("pragma user_version").fetchone()[0])
    if task:
        show_task(c, task)
        return
    print("\nrecent tasks:")
    for tid, n, mx in c.execute(
            "select taskId, count(*), max(timestamp) from decision_log "
            "group by taskId order by max(timestamp) desc limit 10"):
        import datetime
        when = datetime.datetime.fromtimestamp(mx / 1000).strftime("%m-%d %H:%M:%S")
        print(f"  {tid}  rows={n}  last={when}")
    print("\nterminal rows (newest first):")
    for t in c.execute("select * from task_terminal order by timestamp desc limit 8"):
        print("  ", t)


if __name__ == "__main__":
    main()
