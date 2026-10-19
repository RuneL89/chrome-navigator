import sqlite3
con = sqlite3.connect('analysis.db')
con.row_factory = sqlite3.Row
cur = con.cursor()
tid = 'task-1792364015400'
print("== assist text (any) ==")
for r in cur.execute("SELECT stepIndex, assistText FROM decision_log WHERE taskId=? AND assistText IS NOT NULL", (tid,)):
    print(f"s{r['stepIndex']}:", (r['assistText'] or '')[:250])
print("== goals/plans across the task ==")
for r in cur.execute("SELECT stepIndex, planJson FROM decision_log WHERE taskId=? AND planJson IS NOT NULL ORDER BY CAST(stepIndex AS INTEGER)", (tid,)):
    import json
    p = json.loads(r['planJson'])
    print(f"s{r['stepIndex']} {p['version']}: {p['subgoals']}")
