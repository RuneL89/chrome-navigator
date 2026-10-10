#!/usr/bin/env python
"""Phase 8 Gate 8.1 reference-vector generator (Phase 8C: --checkpoint v32b).

Records (state + question -> probability distribution) pairs from the REAL laya
checkpoint and the REAL shipped int8 artifact, for the Kotlin pipeline to match
within max |dp| <= 0.01 (PHASE_08 ss3 Gate 8.1; PHASE_08C Gate 8C.2 for v32b).

Per case:
  - the question is built in the EXACT form `LayaEngine` asks it (same instructions,
    same option labels/criteria, same option order — the strings mirror
    navigator.app.laya.LayaEngine);
  - the sequence is built with the UPSTREAM `laya.common.build_sequence` (imported, not
    re-typed) over the checkpoint's tokenizer — the strongest available ground truth;
  - `probabilities` (the parity target) = the shipped int8 ONNX artifact's logits,
    temperature-scaled + softmaxed with the same law as `Calibration.kt`;
  - `agentProbabilities` (drift evidence, NOT a gate) = the eager torch Agent's answer
    for the same input, recorded so the int8-vs-fp32 drift is measured and visible.

Output: app/src/test/resources/laya/reference-vectors.json (default checkpoint), or
app/src/test/resources/laya/reference-vectors-v32b.json (--checkpoint v32b — lengths and
temperatures read from THAT checkpoint's rl_agent_config.json, not assumed).

Phase 08H adds `--state-view-parity` (stdlib only — no torch/HF/network): it runs the
PYTHON mirror of `navigator.core.state.LayaStateView.deriveWith` (the vision 03 §4
dual-view derivation, `LAYA_STATE_VIEW_VERSION=1`) over the case corpus with a
whitespace-word token counter and writes
app/src/test/resources/laya/state-view-parity.json, whose expected views the JVM
`LayaStateViewParityTest` must reproduce byte-for-byte. The real model runs are
untouched by this mode.

Usage (after tools/fetch_laya.py built the artifact):
  .tools/uv.exe run --no-project --with "git+https://github.com/NandhaKishorM/laya" \\
      --with "torch==2.5.1" --with "transformers>=4.48,<5" --with numpy \\
      --with onnx --with "onnxruntime==1.20.1" python tools/laya_reference.py
  ... --checkpoint v32b   # the Phase-8C primary checkpoint
  python tools/laya_reference.py --state-view-parity   # stdlib-only, no model needed
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import sys
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(PROJECT_ROOT / "tools"))
from fetch_laya import (  # noqa: E402
    CHECKPOINTS,
    HF_REPO,
    HF_REVISION,
    MODEL_NAME,
    checkpoint_dir,
    ensure_windows_crt,
)

FIXTURE_DIR = PROJECT_ROOT / "app" / "src" / "test" / "resources" / "laya"

# --- the cascade question forms (mirror LayaEngine.kt EXACTLY; Phase 10B) ----------
# The shadow migrated to the cascade (vision 02 §4 v1.3.0): stage-1 = the FIXED
# 9-intent choice (the question's own INTENT_INSTRUCTIONS + the intent criteria);
# stage-2 = the same batched per-candidate score form (one score question per
# candidate, the per-candidate instructions, the 5-level relevance rubric verbatim);
# noul + the non-cascade single-stage form are unchanged. The retired two-stage
# bucketed form (BUCKET_INSTRUCTIONS/element forms) is GONE from the engine.

SINGLE_STAGE_INSTRUCTIONS = "What is the best next action?"
NOUL_FALSE = ("false", "no, the statement does not hold")
NOUL_TRUE = ("true", "yes, the statement holds")
SCORE_INSTRUCTIONS = "Score the state against the rubric."

# The cascade stage-1 vocabulary: intents-v1 (vision 02 §3 v1.3.0) — id + criteria,
# in the FIXED table order (navigator.core.decision.Intents.ALL).
INTENT_INSTRUCTIONS = (
    "You are navigating a web browser toward the active subtask. Choose the ONE next move "
    "from the categories. Judge from the subtask, its done-condition, and the page description."
)

INTENTS = [
    ("open-site", "The subtask needs a different site or page loaded by its address; code opens the URL directly."),
    ("follow-link", "A link or search result on this page plausibly leads toward the subtask's target."),
    ("activate-control", "A button, toggle, or other control on this page advances the subtask when pressed."),
    ("fill-field", "A form or search field on this page needs text typed to advance; the content will be phrased for you."),
    ("submit", "A search or form submission should fire now to advance."),
    ("go-back", "This page is a dead end or wrong turn; going back is the best move."),
    ("scroll", "The subtask's target is likely below the visible area."),
    ("dismiss-nag", "A popup, cookie, permission, or upgrade dialog blocks the page; dismiss it."),
    ("verify-done", "The subtask's done-condition may now hold on this page; run the completion check."),
]


def intent_question() -> dict:
    crit = {ident: text for ident, text in INTENTS}
    assert list(crit) == [ident for ident, _ in INTENTS], "intent options must be in table order"
    return {"t": "choice", "ins": INTENT_INSTRUCTIONS, "crit": crit}


# The stage-2 rubric (vision 02 §3 v1.3.0) — the declared levels in order.
RUBRIC_LEVELS = [
    "does not advance the active subtask",
    "unlikely to advance — peripheral or unrelated",
    "possibly advances — plausible but unclear",
    "likely advances — directly relevant",
    "decisively advances — the obvious target",
]


def stage2_question(label: str, subtask: str) -> dict:
    """One per-candidate score question (TargetRubric.stage2Questions' exact shape)."""
    return {"t": "score", "ins": f"Score how well acting on '{label}' advances: {subtask}.", "crit": list(RUBRIC_LEVELS)}


# --- case definitions ---------------------------------------------------------------

STATE_SHORT = (
    "GOAL: find an apple pie recipe\n"
    "URL: https://www.allrecipes.com/recipes/78/baking/\n"
    "TITLE: Baking Recipes - Allrecipes\n"
    "HISTORY: (none)\n"
    "UNTRUSTED PAGE CONTENT:\n"
    "Browse our collection of baking recipes, from quick breads to pies and cakes."
)

STATE_LONG = (
    "GOAL: compare two laptop prices on a shop listing\n"
    "URL: https://www.verkkokauppa.com/fi/product/612435/Super-XYZ-14-Laptop\n"
    "TITLE: SuperXYZ 14\" Laptop - Verkkokauppa\n"
    "HISTORY: step 0: tap \"Laptops\" -> progressed\n"
    "HISTORY: step 1: tap \"SuperXYZ 14\" -> progressed\n"
    "HINT: try scrolling if the buy button is not visible\n"
    "UNTRUSTED PAGE CONTENT:\n"
    "SuperXYZ 14 Laptop - 16 GB RAM, 512 GB SSD, 2.8K OLED display. "
    "Delivery 1-3 business days. Free returns within 30 days. "
    "Add the two-year extended warranty for 89,90 eur. "
    "Customers also bought: sleeve, USB-C hub, wireless mouse. "
    "Price alert available. 4523,00 eur incl. VAT."
)

STATE_NONASCII = (
    "GOAL: hyväksy evästeet ja hae parhaat hakutulokset\n"
    "URL: https://www.google.com/search?q=omenapiirakka+resepti\n"
    "TITLE: omenapiirakka resepti - Google-haku\n"
    "HISTORY: (none)\n"
    "UNTRUSTED PAGE CONTENT:\n"
    "Hyväksy evästeet — näin parannamme kokemustasi. Haku-osio: "
    "parhaat omenapiirakan reseptit suomalaisille leivontasivustoille."
)


SUBTASK_TEXT = "unsubscribe from the first mailing list"


def opt(label: str, bucket: str, cx: int, cy: int) -> tuple[str, str]:
    return (label, f"bucket={bucket} ({cx},{cy})")


CASES = [
    # -- cascade stage-1: the FIXED intent choice (9 options; calibration choice:6-10) ----
    dict(
        id="intent-stage-9",
        kind="choice",
        state=STATE_SHORT,
        ids=[ident for ident, _ in INTENTS],
        q=intent_question(),
    ),
    dict(
        id="intent-stage-9-nonascii",
        kind="choice",
        state=STATE_NONASCII,
        ids=[ident for ident, _ in INTENTS],
        q=intent_question(),
    ),
    dict(
        id="intent-stage-9-long",
        kind="choice",
        state=STATE_LONG,
        ids=[ident for ident, _ in INTENTS],
        q=intent_question(),
    ),
    # -- cascade stage-2: the batched per-candidate score form (rubric verbatim) ----------
    dict(
        id="stage2-candidate-a",
        kind="score",
        state=STATE_SHORT,
        ids=[str(i + 1) for i in range(5)],
        q=stage2_question("Unsubscribe from Cooking Classics", SUBTASK_TEXT),
    ),
    dict(
        id="stage2-candidate-b",
        kind="score",
        state=STATE_LONG,
        ids=[str(i + 1) for i in range(5)],
        q=stage2_question("Unsubscribe from Star Log", SUBTASK_TEXT),
    ),
    dict(
        id="stage2-candidate-nonascii",
        kind="score",
        state=STATE_NONASCII,
        ids=[str(i + 1) for i in range(5)],
        q=stage2_question("Hyväksy kaikki", SUBTASK_TEXT),
    ),
    # -- noul (the anchored done-check verification; unchanged form) -----------------------
    dict(
        id="noul-goal-achieved",
        kind="noul",
        state=STATE_SHORT,
        ids=["false", "true"],
        q={
            "t": "noul",
            "ins": 'The goal "find an apple pie recipe" is fully achieved on the current page.',
        },
    ),
    dict(
        id="noul-goal-achieved-long",
        kind="noul",
        state=STATE_LONG,
        ids=["false", "true"],
        q={
            "t": "noul",
            "ins": 'The goal "compare two laptop prices on a shop listing" is fully achieved on the current page.',
        },
    ),
    # -- score (the generic ordered rubric, a question's own instructions) -----------------
    dict(
        id="score-urgency-4",
        kind="score",
        state=STATE_LONG,
        ids=["1", "2", "3", "4"],
        q={
            "t": "score",
            "ins": SCORE_INSTRUCTIONS,
            "crit": [
                "not urgent, browse another day",
                "mildly urgent, this week",
                "urgent, today",
                "critical, right now",
            ],
        },
    ),
    # -- single-stage fallback (a non-cascade question: the recovery set) -------------------
    dict(
        id="single-recovery-4",
        kind="choice",
        state=STATE_LONG,
        ids=["back", "scroll", "rephrase", "give-up"],
        q={
            "t": "choice",
            "ins": SINGLE_STAGE_INSTRUCTIONS,
            "crit": {
                "back": "recovery action chosen by the engine when stuck",
                "scroll": "recovery action chosen by the engine when stuck",
                "rephrase": "recovery action chosen by the engine when stuck",
                "give-up": "recovery action chosen by the engine when stuck",
            },
        },
    ),
]

# --- the laya state view (Phase 08H — the EXACT mirror of LayaStateView.kt) ---------
# Vision 03 §4 dual-view law: a pure, deterministic derivation of the canonical text,
# same sections in the same order, shrunk head-anchored within its budget. The Kotlin
# `LayaStateView.deriveWith(canonical, budget, count)` and this `derive_state_view`
# must agree BYTE FOR BYTE on every input (proven by state-view-parity.json).

UNTRUSTED_HEADER = "UNTRUSTED PAGE CONTENT:"

# The bucket-header vocabulary of the CANONICAL state text (the taxonomy is unchanged -
# vision 03 §3; only the shadow's QUESTION form migrated to the cascade). The state-view
# derivation still collapses these headers when their lines shrink away.
BUCKETS = [  # canonical Bucket enum order (navigator.core.enumerate.Bucket ordinals)
    ("nav-links", "links believed to navigate"),
    ("form-fields", "editable fields"),
    ("buttons", "clickable non-link controls"),
    ("scroll", "scroll actions on scrollable containers"),
    ("back", "system back"),
    ("finish", "goal achieved, stop"),
]

BUCKET_HEADERS = {f"{serial}:" for serial, _ in BUCKETS}


def word_count(text: str) -> int:
    """The parity counter: whitespace-separated tokens (the Kotlin test's counter)."""
    return len(text.split())


def render_view(app_lines: list[str], excerpt: list[str], sections: list[list[str]]) -> str:
    out = []
    out.extend(app_lines)
    out.extend(excerpt)
    for section in sections:
        out.extend(section)
    return "".join(f"{line}\n" for line in out)


def derive_state_view(canonical: str, budget: int, count=word_count) -> str:
    """The laya state view of `canonical` within `budget` tokens.

    Mirrors `LayaStateView.deriveWith` line for line: identity when the canonical
    already fits; otherwise candidate lines drop from the tail of the candidate region
    (emptied bucket headers collapse), then the content-excerpt lines shrink
    head-anchored (tail first) — whole lines only, never a mid-line cut; a loud
    failure when even the app-authored block alone exceeds the budget.
    """
    if count(canonical) <= budget:
        return canonical
    lines = canonical.split("\n")
    while lines and lines[-1] == "":
        lines.pop()
    if UNTRUSTED_HEADER not in lines:
        raise ValueError(
            f"laya state view is {count(canonical)} tokens, over the {budget}-token view budget "
            f"(vision 03 §4 dual-view law) with no UNTRUSTED PAGE CONTENT block to shrink — "
            f"refusing to derive an oversized view (LAYA_STATE_VIEW_VERSION=1)"
        )
    header_index = lines.index(UNTRUSTED_HEADER)
    app_lines = lines[: header_index + 1]
    content = lines[header_index + 1 :]

    excerpt = []
    for line in content:
        if line in BUCKET_HEADERS:
            break
        excerpt.append(line)
    sections: list[list[str]] = []
    current: list[str] | None = None
    for line in content[len(excerpt) :]:
        if line in BUCKET_HEADERS:
            current = [line]
            sections.append(current)
        elif current is not None:
            current.append(line)

    # Stage 1: drop candidate lines lowest-in-text first (the canonical text orders
    # them deterministically); a bucket whose lines are gone loses its header.
    while count(render_view(app_lines, excerpt, sections)) > budget:
        droppable = [s for s in sections if len(s) > 1]
        if not droppable:
            break
        last = droppable[-1]
        if len(last) == 2:
            sections.remove(last)
        else:
            last.pop()
    # Stage 2: shrink the content excerpt head-anchored — whole lines, tail first.
    while count(render_view(app_lines, excerpt, sections)) > budget and excerpt:
        excerpt.pop()
    view = render_view(app_lines, excerpt, sections)
    if count(view) > budget:
        raise ValueError(
            f"laya state view is {count(view)} tokens, over the {budget}-token view budget "
            f"(vision 03 §4 dual-view law) even with an empty UNTRUSTED PAGE CONTENT block — "
            f"the app-authored lines alone do not fit; refusing to derive an oversized view "
            f"(LAYA_STATE_VIEW_VERSION=1)"
        )
    return view


def v2_state(goal: str, url: str, title: str, plan: list[str], progress: list[str],
             history: list[str], excerpt: list[str], buckets: list[tuple[str, list[str]]]) -> str:
    """Assembles a v2 canonical state text (the builder's exact section order)."""
    lines = [f"GOAL: {goal}", f"URL: {url}", f"TITLE: {title}"]
    lines.extend(f"PLAN: {p}" for p in plan)
    lines.extend(f"TASK PROGRESS: {p}" for p in progress)
    lines.extend(f"HISTORY: {h}" for h in history) if history else lines.append("HISTORY: (none)")
    lines.append(UNTRUSTED_HEADER)
    lines.extend(excerpt)
    for header, bucket_lines in buckets:
        if not bucket_lines:
            continue
        lines.append(header)
        lines.extend(bucket_lines)
    return "".join(f"{line}\n" for line in lines)


def candidate_line(node_id: str, label: str, cx: int, cy: int) -> str:
    return f'[{node_id}] "{label}" tap ({cx},{cy})'


# The parity corpus: identity (fits), candidate-stage shrink, excerpt-stage shrink,
# bucket-header collapse, plan/progress riding the app block, and a no-untrusted-block
# rejection handled JVM-side (loud failure is JVM-tested; the fixture holds successes).
STATE_VIEW_CASES = [
    dict(
        id="identity-fits-319",
        budget=319,
        state=v2_state(
            goal="find the featured article",
            url="https://news.example.test/featured",
            title="Today's Featured Article",
            plan=[],
            progress=[],
            history=[],
            excerpt=["A quiet harbor town wakes before sunrise."],
            buckets=[("back:", [candidate_line("back", "Back", 0, 0)]),
                     ("finish:", [candidate_line("finish", "Finish", 0, 0)])],
        ),
    ),
    dict(
        id="candidate-stage-shrinks-buckets-collapse",
        budget=60,
        state=v2_state(
            goal="find the featured article",
            url="https://news.example.test/featured",
            title="Today's Featured Article",
            plan=["open the section index"],
            progress=["0 processed, 2 pending, 0 skipped of 2 tracked targets (drained=False)"],
            history=["step 0: tap \"Sections\" -> progressed"],
            excerpt=[
                "A quiet harbor town wakes before sunrise.",
                "The market square fills with the smell of bread.",
                "Nobody remembers who lit the first lamp.",
            ],
            buckets=[
                ("nav-links:", [candidate_line(f"n{i}", f"Section {i}", 270, 300 + 60 * i) for i in range(6)]),
                ("buttons:", [candidate_line(f"b{i}", f"Button {i}", 540, 800 + 60 * i) for i in range(3)]),
                ("scroll:", [candidate_line("scroll-down@n9", "Scroll down", 540, 1100),
                             candidate_line("scroll-up@n9", "Scroll up", 540, 300)]),
                ("back:", [candidate_line("back", "Back", 0, 0)]),
                ("finish:", [candidate_line("finish", "Finish", 0, 0)]),
            ],
        ),
    ),
    dict(
        id="excerpt-stage-shrinks-head-anchored",
        budget=45,
        state=v2_state(
            goal="summarize the harbor story",
            url="https://news.example.test/featured",
            title="Today's Featured Article",
            plan=[],
            progress=[],
            history=[],
            excerpt=[
                "A quiet harbor town wakes before sunrise.",
                "The market square fills with the smell of bread.",
                "Nobody remembers who lit the first lamp.",
                "The ferry arrives late, as always.",
                "Gulls argue over the morning catch.",
            ],
            buckets=[("back:", [candidate_line("back", "Back", 0, 0)]),
                     ("finish:", [candidate_line("finish", "Finish", 0, 0)])],
        ),
    ),
    dict(
        id="plan-and-progress-ride-the-app-block",
        budget=90,
        state=v2_state(
            goal="unsubscribe from every mailing list on the page",
            url="https://lists.example.test/subscriptions",
            title="Your Subscriptions",
            plan=["open the subscriptions page", "resolve every listed target", "confirm the list is clear"],
            progress=["2 processed, 3 pending, 0 skipped of 5 tracked targets (drained=False)",
                      "pending target: \"weekly digest\"",
                      "pending target: \"product updates\""],
            history=["step 0: tap \"Unsubscribe\" -> progressed", "step 1: tap \"Unsubscribe\" -> progressed"],
            excerpt=["Manage how we reach you. Each list can be paused or removed at any time."],
            buckets=[
                ("buttons:", [candidate_line(f"u{i}", "Unsubscribe", 540, 400 + 80 * i) for i in range(5)]),
                ("back:", [candidate_line("back", "Back", 0, 0)]),
                ("finish:", [candidate_line("finish", "Finish", 0, 0)]),
            ],
        ),
    ),
    dict(
        id="identity-with-canonical-equality-check",
        budget=319,
        state=v2_state(
            goal="compare two laptop prices on a shop listing",
            url="https://shop.example.test/listings/laptops",
            title="Laptop Listings",
            plan=[],
            progress=[],
            history=["step 0: tap \"Laptops\" -> progressed"],
            excerpt=["Free returns within 30 days on every listing."],
            buckets=[
                ("nav-links:", [candidate_line("n0", "14-inch laptops", 270, 540),
                                candidate_line("n1", "Compare (2)", 270, 590)]),
                ("form-fields:", [candidate_line("n2", "Search terminal", 200, 180)]),
                ("back:", [candidate_line("back", "Back", 0, 0)]),
                ("finish:", [candidate_line("finish", "Finish", 0, 0)]),
            ],
        ),
    ),
]


def state_view_parity_fixture() -> dict:
    records = []
    for case in STATE_VIEW_CASES:
        canonical = case["state"]
        budget = case["budget"]
        records.append({
            "id": case["id"],
            "canonical": canonical,
            "budgetTokens": budget,
            "canonicalWordCount": word_count(canonical),
            "view": derive_state_view(canonical, budget, word_count),
            "viewWordCount": word_count(derive_state_view(canonical, budget, word_count)),
        })
    return {
        "schema": "navigator.laya.state-view-parity/1",
        "gate": "PHASE_08H ss3 Gate 8H.2 (the Kotlin LayaStateView derivation must "
                "reproduce every `view` byte-for-byte with the same counter)",
        "derivation": "navigator.core.state.LayaStateView.deriveWith(canonical, budgetTokens, count) "
            "must equal `view`; the counter is whitespace-word tokens (Python str.split() law)",
        "layaStateViewVersion": 1,
        "cases": records,
    }


# --- int8 post-processing (same law as Calibration.kt) --------------------------------
# The 421M checkpoint's fitted tables (its rl_agent_config.json); the v32b checkpoint
# ships temperature [1,1,1] and an EMPTY temperature_by_options map (identity) — the
# tables are always loaded from the checkpoint config at run time, never assumed.

BASE_TEMPERATURES = [1.6369030475616455, 1.2514300346374512, 1.983399510383606]
TEMPERATURE_BY_OPTIONS = {
    "choice:2": 1.9063563346862793,
    "choice:3-5": 1.7601518630981445,
    "choice:6-10": 1.0000158548355103,
    "choice:11+": 0.10058280825614929,
    "score:3-5": 1.2514300346374512,
    "noul:2": 1.983399510383606,
}
TEMP_MIN, TEMP_MAX = 0.5, 5.0
QTYPES = {"choice": 0, "score": 1, "noul": 2}


def clamp_temperature(t: float) -> float:
    if t != t or t in (float("inf"), float("-inf")):
        return 1.0
    return min(TEMP_MAX, max(TEMP_MIN, t))


def temp_bucket(qtype: int, k: int) -> str:
    size = "2" if k <= 2 else "3-5" if k <= 5 else "6-10" if k <= 10 else "11+"
    return f"{'choice' if qtype == 0 else 'score' if qtype == 1 else 'noul'}:{size}"


class CalibrationTables:
    """The (base, fitted) temperature tables of ONE checkpoint config, upstream law."""

    def __init__(self, base: list[float], fitted: dict[str, float]):
        self.base = base
        self.fitted = fitted

    @staticmethod
    def from_cfg(cfg: dict) -> "CalibrationTables":
        return CalibrationTables(
            base=[float(t) for t in cfg.get("temperature", BASE_TEMPERATURES)],
            fitted={k: float(v) for k, v in (cfg.get("temperature_by_options") or {}).items()},
        )

    def for_(self, qtype: int, k: int) -> float:
        base = self.base[qtype]
        return clamp_temperature(self.fitted.get(temp_bucket(qtype, k), base))


def calibrated_probabilities(
    logits: list[float],
    qtype: int,
    tables: CalibrationTables,
) -> tuple[list[float], float]:
    k = len(logits)
    t = tables.for_(qtype, k)
    z = [v / t for v in logits]
    m = max(z)
    ex = [pow(2.718281828459045, v - m) for v in z]
    s = sum(ex)
    p = [v / s for v in ex]
    return p, t


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument(
        "--checkpoint",
        choices=sorted(CHECKPOINTS),
        default="laya-421m",
        help="which checkpoint to record vectors for (Phase 8C adds v32b)",
    )
    ap.add_argument(
        "--state-view-parity",
        action="store_true",
        help="stdlib-only mode (Phase 08H): write the LayaStateView parity fixture, no model",
    )
    args = ap.parse_args()

    if args.state_view_parity:
        fixture = state_view_parity_fixture()
        fixture_path = FIXTURE_DIR / "state-view-parity.json"
        FIXTURE_DIR.mkdir(parents=True, exist_ok=True)
        fixture_path.write_text(json.dumps(fixture, indent=1, ensure_ascii=False), encoding="utf-8")
        for record in fixture["cases"]:
            print(
                f"{record['id']}: canonical={record['canonicalWordCount']}w "
                f"budget={record['budgetTokens']}w view={record['viewWordCount']}w "
                f"({'identity' if record['canonical'] == record['view'] else 'shrunk'})"
            )
        print(f"wrote {len(fixture['cases'])} state-view parity cases -> {fixture_path}")
        return 0

    ckpt = CHECKPOINTS[args.checkpoint]
    fixture_name = (
        "reference-vectors.json" if ckpt.key == "laya-421m" else f"reference-vectors-{ckpt.key}.json"
    )
    fixture_path = FIXTURE_DIR / fixture_name
    model_path = checkpoint_dir(ckpt) / ckpt.model_name
    if not model_path.exists():
        raise SystemExit(f"{model_path} missing - run tools/fetch_laya.py --checkpoint {ckpt.key} first")

    ensure_windows_crt()
    import numpy as np  # noqa: F401
    import torch
    import torch.version  # noqa: F401  (torch 2.3 does not auto-import it)
    import onnxruntime as ort
    from laya.agent import Agent
    from laya.common import build_sequence

    subfolder = f" (subfolder {ckpt.subfolder})" if ckpt.subfolder else ""
    print(f"loading Agent from {ckpt.repo}@{ckpt.revision}{subfolder} (torch eager)...")
    agent = Agent(
        ckpt.repo,
        compile=False,
        device="cpu",
        revision=ckpt.revision,
        subfolder=ckpt.subfolder,
    )
    tok = agent.tok
    # The checkpoint's OWN sequence budgets + calibration (never assumed from 421m).
    max_len = int(agent.cfg.get("max_len", 512))
    head_max_len = int(agent.cfg.get("head_max_len", 192))
    tables = CalibrationTables.from_cfg(agent.cfg)
    print(f"checkpoint config: max_len={max_len} head_max_len={head_max_len} "
          f"temperature={tables.base} fitted={tables.fitted}")

    print(f"opening int8 session over {model_path} ...")
    opts = ort.SessionOptions()
    opts.intra_op_num_threads = 1
    opts.inter_op_num_threads = 1
    session = ort.InferenceSession(str(model_path), opts, providers=["CPUExecutionProvider"])

    records = []
    for case in CASES:
        q = case["q"]
        ids, markers = build_sequence(tok, case["state"], q, max_len, head_max_len)
        k = len(case["ids"])
        assert len(markers) == k, f"{case['id']}: {len(markers)} markers for {k} options"
        qtype = QTYPES[q["t"]]

        # the shipped artifact's answer (the parity target)
        out = session.run(
            ["logits", "act_logits"],
            {
                "input_ids": np.asarray([ids], dtype=np.int64),
                "attention_mask": np.ones((1, len(ids)), dtype=np.int64),
                "marker_pos": np.asarray([markers], dtype=np.int64),
                "marker_mask": np.ones((1, k), dtype=bool),
                "qtype": np.asarray([qtype], dtype=np.int64),
            },
        )
        logits = [float(v) for v in out[0][0][:k]]
        probs, temp = calibrated_probabilities(logits, qtype, tables)

        # the eager Agent's answer (drift evidence — NOT the parity target).
        # `Agent.predict` takes the USER question format ({type, instructions, criteria});
        # `_to_internal` maps it to the {t, ins, crit} form the fixture stores (the same
        # form `build_sequence` consumes, so the recorded sequence is unchanged).
        user_q = {"type": q["t"], "instructions": q["ins"]}
        if "crit" in q:
            user_q["criteria"] = q["crit"]
        answers = agent.predict(case["state"], {case["id"]: user_q})["answers"][case["id"]]
        # choice/score answers carry `probabilities`; a noul answer carries only `noul`
        # (p_true) — reconstruct [p_false, p_true] for the drift record (evidence only).
        if "probabilities" in answers:
            agent_probs = [float(answers["probabilities"][key]) for key in answers["probabilities"]]
        elif answers.get("type") == "noul":
            p_true = float(answers["noul"])
            agent_probs = [round(1.0 - p_true, 4), p_true]
        else:
            agent_probs = None

        max_drift = max(abs(a - b) for a, b in zip(probs, agent_probs)) if len(agent_probs) == k else None
        chosen_idx = max(range(k), key=lambda i: probs[i])
        print(
            f"{case['id']}: k={k} tokens={len(ids)} chosen={case['ids'][chosen_idx]} "
            f"(p={probs[chosen_idx]:.4f}) maxΔp vs eager agent={max_drift}"
        )
        records.append({
            "id": case["id"],
            "kind": q["t"],
            "state": case["state"],
            "optionIds": case["ids"],
            "question": q,
            "qtype": qtype,
            "tokenCount": len(ids),
            "inputIdsSha256": hashlib.sha256(
                json.dumps(ids, separators=(",", ":")).encode("utf-8"),
            ).hexdigest(),
            "markerPositions": markers,
            "temperatureUsed": temp,
            "logitsInt8": [round(v, 6) for v in logits],
            "probabilities": [round(v, 10) for v in probs],
            "agentProbabilities": agent_probs,
            "agentChosen": answers.get("choice") or answers.get("noul") or answers.get("score"),
            "maxAbsDeltaVsEagerAgent": None if max_drift is None else round(max_drift, 10),
        })

    fixture = {
        "schema": "navigator.laya.reference-vectors/1",
        "gate": (
            "PHASE_08 ss3 Gate 8.1 (max abs probability delta <= 0.01)"
            if ckpt.key == "laya-421m"
            else f"PHASE_08C ss3 Gate 8C.2 (max abs probability delta <= 0.01; checkpoint {ckpt.key})"
        ),
        "parityTarget": "probabilities (int8 artifact logits, calibrated exactly like "
            "navigator.app.laya.Calibration)",
        "driftEvidence": "agentProbabilities / maxAbsDeltaVsEagerAgent: the eager torch "
            "Agent on the same input — the int8-vs-eager drift is RECORDED, not gated",
        "checkpoint": {
            "repo": ckpt.repo,
            "revision": ckpt.revision,
            "subfolder": ckpt.subfolder or "",
            "maxLen": max_len,
            "headMaxLen": head_max_len,
            "baseTemperatures": tables.base,
            "temperatureByOptions": tables.fitted,
        },
        "artifact": {
            "file": ckpt.model_name,
            "sha256": hashlib.sha256(model_path.read_bytes()).hexdigest(),
        },
        "cases": records,
    }
    FIXTURE_DIR.mkdir(parents=True, exist_ok=True)
    fixture_path.write_text(json.dumps(fixture, indent=1, ensure_ascii=False), encoding="utf-8")
    print(f"wrote {len(records)} reference vectors -> {fixture_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
