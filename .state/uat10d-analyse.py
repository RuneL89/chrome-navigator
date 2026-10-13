#!/usr/bin/env python3
"""UAT 10D emulator row analyser — read the already-pulled device DB (WAL-aware copy) and
print one task's decision rows + terminal. Companion to uat10c-analyse.py; this one uses
the local python 3.11 on PATH and the .state/uat10d-db-live.db pull.

Usage: python3.11 .state/uat10d-analyse.py [taskId]
"""
import datetime
import json
import os
import shutil
import sqlite3
import sys
import tempfile

LIVE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "uat10d-db-live.db")


def connect() -> sqlite3.Connection:
    d = tempfile.mkdtemp()
    for suffix in ("", "-wal", "-shm"):
        src = LIVE + suffix
        if os.path.exists(src):
            shutil.copy(src, os.path.join(d, os.path.basename(LIVE) + suffix))
    c = sqlite3.connect(os.path.join(d, os.path.basename(LIVE)))
    d2 = tempfile.mkdtemp()
    for suffix in ("", "-wal", "-shm"):
        s = LIVE + suffix
        if os.path.exists(s):
            shutil.copy(s, os.path.join(d2, os.path.basename(LIVE) + suffix))
    return c


def band(p: float, margin: float) -> str:
    if p < 0.30:
        return "LOW"
    if p < 0.55 or margin < 0.15:
        return "UNCERTAIN"
    return "HIGH"


def show_task(c: sqlite3.Connection, task: str, max_state_lines: int = 14) -> None:
    cols = [r[1] for r in c.execute("pragma table_info(decision_log)")]
    rows = [dict(zip(cols, r)) for r in
            c.execute("select * from decision_log where taskId=? order by stepIndex", (task,))]
    if not rows:
        print("no rows for", task)
        return
    print(f"\n=== {task} — {rows[0]['goal']!r} ({len(rows)} rows) ===")
    for row in rows:
        when = datetime.datetime.fromtimestamp(row["timestamp"] / 1000).strftime("%H:%M:%S")
        ans = json.loads(row["teacherAnswerJson"]) if row["teacherAnswerJson"] else None
        prob = ""
        if ans and ans.get("probabilities"):
            probs, chosen = ans["probabilities"], ans["chosen"]
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
            rb = plan.get("rolledBack", 0)
            lesson = plan.get("lesson")
            print(f"  plan={plan.get('version')} subtasks={len(plan.get('subgoals', []))}"
                  f" rolledBack={rb}" + (f" lesson={lesson!r}" if lesson else ""))
        if row["targetScoresJson"]:
            print(f"  targetScores={row['targetScoresJson']}")
        if row["targetStateText"]:
            head = "\n      ".join(row["targetStateText"].splitlines()[:max_state_lines])
            print(f"  targetStateText:\n      {head}")
        if row["assistText"]:
            print(f"  assistText={row['assistText'][:300]!r}")
        if row["landingHost"]:
            print(f"  landingHost={row['landingHost']!r}")
        if row["exitJson"]:
            print(f"  exit={row['exitJson'][:240]}")
    for term in c.execute("select * from task_terminal where taskId=?", (task,)):
        print(f"\nTERMINAL: {term}")


def main() -> None:
    c = connect()
    print("user_version:", c.execute("pragma user_version").fetchone()[0])
    if len(sys.argv) > 1:
        show_task(c, sys.argv[1])
        return
    print("\nrecent tasks:")
    for tid, n, mx in c.execute(
            "select taskId, count(*), max(timestamp) from decision_log "
            "group by taskId order by max(timestamp) desc limit 12"):
        when = datetime.datetime.fromtimestamp(mx / 1000).strftime("%m-%d %H:%M:%S")
        print(f"  {tid}  rows={n}  last={when}")


if __name__ == "__main__":
    main()
