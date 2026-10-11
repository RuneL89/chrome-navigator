# The noul primitive is non-discriminative — the choice primitive verifies (proven live)

**Context:** UAT on the S25+ failed with 53 steps of `verify-done` @ 0.92-0.99 and nine
exit checks whose propositions were ATOMICALLY true (urlHost conditions whose own PAGE
IDENTITY line showed the matching address) yet scored pTrue 0.35-0.60, so a code-STRONG
condition could not reliably pass the engine's confirmation.

**Experiments (live, pinned snapshot `typesafe/jev-1.13-20260917`, OpenRouter
`/api/alpha/decisions`, ~$0.0001 total):**

1. Three proposition phrasings of a trivially-true condition (current wording /
   literal TRUE-or-FALSE string check / crisp "do not hedge" instructions) →
   **0.54 / 0.50 / 0.45** — all hedge.
2. Discrimination probes on one state: trivially-true ("'Wikipedia' appears") **0.16** ·
   trivially-false ("the address is example.com") **0.16** · another true ("'free'
   appears") **0.15** · wrong-host **0.15** — **the truth value is ignored**;
   meanwhile a choice question on the SAME request: 0.98/0.96 confidence (correct).
3. Within ONE request: true **0.37** · false **0.34** · the true one repeated **0.32** —
   indistinguishable. The same proposition on an EMPTY state → **0.14** (vs 0.37 on the
   rich state) — **the noul value tracks the STATE's richness, not the proposition.**
4. **The choice-based verification works:** "Decide whether the page shown SATISFIES the
   active subtask's done-condition" → satisfied case (host matches) **satisfied @ 0.99,
   confidence 0.98**; mismatch case (news.example.com vs a wikipedia.org condition)
   **not-satisfied @ 0.82, confidence 0.64.**

**Implication:** every done-check, every exit path, and the additive H9 check consume
noul values that do not discriminate propositions. The exit-reliability disease (from
the 2026-10-06 UAT onward: "done-ness never honest", goals never completing) is this
primitive's as-served behavior, not our prompts — three phrasings failed identically.

**Recommended fix (vision-level):** express verification as a two-option CHOICE
(satisfied / not-satisfied) with the done-condition riding the state (it already does);
map p(satisfied) onto the existing exit-policy bands (HIGH > 0.70, sustained >= 0.55)
unchanged; apply the same redesign to the additive H9 consult; migrate the shadow's
judged form and the exit-check row/RLCD case accordingly. The systemone endpoint
(auth-separate) could be probed with a TypeSafe key later to see whether noul behaves
correctly there — a possible provider-endpoint bug to report.

**Also observed (small data defect):** the page TITLE on Chrome reads
"Chrome: Wikipedia, the free encyclopedia" — our title reader takes the raw window
title including Chrome's "Chrome: " prefix; strip it when present.

---

## RESOLUTION (2026-10-11): it was OUR wire shape, not the primitive

The SDK-documented noul shape carries the question in **`instructions`**
(`"refund": {"type":"noul","instructions":"Is the customer asking for money back?"}`)
— there is NO `proposition` field. The Phase-3 reverse-engineered shape put our real
question in `proposition` (an unknown field the model ignores) with boilerplate in
`instructions`, so every noul answered the boilerplate → a state-dependent constant.
That is the entire "hedging" disease, from the first UAT onward.

**Corrected shape, live-verified on BOTH surfaces with our exact state format:**

| Endpoint | host-true | host-false | word-true | word-false |
|---|---|---|---|---|
| `/api/v1/systemone` | **0.98** | **0.01** | **0.99** | **0.01** |
| `/api/alpha/decisions` (pinned) | **0.99** | **0.01** | **0.98** | **0.01** |

Fix shipped (Round 2.3): `SystemOneClient.questionJson` emits the noul question as
`instructions`, drops the ignored `proposition` field, `PROMPT_VERSION` →
`jev-instructions-3`; the six noul-carrying transcript fixtures corrected with
provenance notes; `Types.kt` KDoc records the corrected law. No choice redesign needed;
no vision-level primitive change (the anchored-proposition law is intact — the
proposition IS the instruction). The exit bands keep their thresholds: true conditions
now score 0.98+ (Path A accepts first check), false conditions 0.01 (clean refusal —
the 0.55-band luck is gone).

Secondary fix shipped alongside: Chrome's window-title prefix ("Chrome: ") is stripped
by the title reader (`WindowTitles.stripChromePrefix`) — the live rows showed the
prefix polluting TITLE and the PAGE IDENTITY lines.
