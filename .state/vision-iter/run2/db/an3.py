import sqlite3, json
con = sqlite3.connect('analysis.db')
con.row_factory = sqlite3.Row
cur = con.cursor()
tid = 'task-1792364015400'
rows = cur.execute("SELECT stepIndex, timestamp, outcome, stateText, teacherAnswerJson, executedActionJson, assistText, questionPrimaryJson FROM decision_log WHERE taskId=? ORDER BY CAST(stepIndex AS INTEGER)", (tid,)).fetchall()
for r in rows:
    st = r['stateText'] or ''
    title = next((l[7:] for l in st.split('\n') if l.startswith('TITLE:')), '')[:50]
    url = next((l[5:] for l in st.split('\n') if l.startswith('URL:')), '')[:60]
    try:
        a = json.loads(r['teacherAnswerJson'] or '{}')
        chosen = a.get('choice') or a.get('noul') or ''
        conf = a.get('confidence', '')
    except Exception:
        chosen, conf = '?', '?'
    try:
        ea = json.loads(r['executedActionJson'] or '{}')
        act = f"{ea.get('kind')}:{ea.get('disposition','')[:10]}" + (f" {ea.get('candidateId')}" if ea.get('candidateId') else '')
    except Exception:
        act = '?'
    print(f"s{r['stepIndex']:>2} {r['outcome']:<10} ans={chosen}@{conf} act={act:<24} | {title} | {url}")
print("\n== plan (latest) ==")
p = cur.execute("SELECT planJson FROM decision_log WHERE taskId=? AND planJson IS NOT NULL ORDER BY CAST(stepIndex AS INTEGER) DESC LIMIT 1", (tid,)).fetchone()
if p: print(p['planJson'][:800])
print("\n== last state text (tail) ==")
last = rows[-1]['stateText'] or ''
print(last[-700:])
