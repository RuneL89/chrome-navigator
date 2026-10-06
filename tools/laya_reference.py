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

Usage (after tools/fetch_laya.py built the artifact):
  .tools/uv.exe run --no-project --with "git+https://github.com/NandhaKishorM/laya" \\
      --with "torch==2.5.1" --with "transformers>=4.48,<5" --with numpy \\
      --with onnx --with "onnxruntime==1.20.1" python tools/laya_reference.py
  ... --checkpoint v32b   # the Phase-8C primary checkpoint
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

# --- the two-stage question forms (mirror LayaEngine.kt EXACTLY) ------------------

BUCKET_INSTRUCTIONS = "In which group is the best next action?"
SINGLE_STAGE_INSTRUCTIONS = "What is the best next action?"
NOUL_FALSE = ("false", "no, the statement does not hold")
NOUL_TRUE = ("true", "yes, the statement holds")
SCORE_INSTRUCTIONS = "Score the state against the rubric."

BUCKETS = [  # canonical Bucket enum order (navigator.core.enumerate.Bucket ordinals)
    ("nav-links", "links believed to navigate"),
    ("form-fields", "editable fields"),
    ("buttons", "clickable non-link controls"),
    ("scroll", "scroll actions on scrollable containers"),
    ("back", "system back"),
    ("finish", "goal achieved, stop"),
]


def bucket_question(present: list[str]) -> dict:
    crit = {serial: text for serial, text in BUCKETS if serial in present}
    assert list(crit) == present, "bucket question options must be in canonical order"
    return {"t": "choice", "ins": BUCKET_INSTRUCTIONS, "crit": crit}


def element_instructions(bucket: str) -> str:
    return f"Which option in the {bucket} group is the best next action?"


def element_question(bucket: str, options: list[tuple[str, str]]) -> dict:
    """options: (label, description) in the bucket's candidate order."""
    crit = {label: desc for label, desc in options}
    return {"t": "choice", "ins": element_instructions(bucket), "crit": crit}


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


def opt(label: str, bucket: str, cx: int, cy: int) -> tuple[str, str]:
    return (label, f"bucket={bucket} ({cx},{cy})")


CASES = [
    # -- bucket stages (6, 4 and 3 buckets present) ------------------------------------
    dict(
        id="bucket-stage-6",
        kind="choice",
        state=STATE_SHORT,
        ids=["nav-links", "form-fields", "buttons", "scroll", "back", "finish"],
        q=bucket_question([b for b, _ in BUCKETS]),
    ),
    dict(
        id="bucket-stage-4",
        kind="choice",
        state=STATE_NONASCII,
        ids=["nav-links", "buttons", "scroll", "finish"],
        q=bucket_question(["nav-links", "buttons", "scroll", "finish"]),
    ),
    dict(
        id="bucket-stage-3",
        kind="choice",
        state=STATE_LONG,
        ids=["form-fields", "scroll", "back"],
        q=bucket_question(["form-fields", "scroll", "back"]),
    ),
    # -- element stages (small, large; multiple buckets) ---------------------------------
    dict(
        id="element-navlinks-6",
        kind="choice",
        state=STATE_SHORT,
        ids=[f"n{i}" for i in range(6)],
        q=element_question(
            "nav-links",
            [
                opt("Apple Pies", "nav-links", 270, 300),
                opt("Cakes", "nav-links", 270, 360),
                opt("Cookies", "nav-links", 270, 420),
                opt("Breads", "nav-links", 270, 480),
                opt("No-Bake Desserts", "nav-links", 270, 540),
                opt("All Baking Recipes", "nav-links", 270, 600),
            ],
        ),
    ),
    dict(
        id="element-navlinks-12",
        kind="choice",
        state=STATE_LONG,
        ids=[f"n{i}" for i in range(12)],
        q=element_question(
            "nav-links",
            [
                opt("Laptops", "nav-links", 270, 240),
                opt("Computers", "nav-links", 270, 290),
                opt("Gaming", "nav-links", 270, 340),
                opt("Accessories", "nav-links", 270, 390),
                opt("Deals", "nav-links", 270, 440),
                opt("Brand: SuperXYZ", "nav-links", 270, 490),
                opt("14-inch laptops", "nav-links", 270, 540),
                opt("Compare (2)", "nav-links", 270, 590),
                opt("Reviews (23)", "nav-links", 270, 640),
                opt("Questions (4)", "nav-links", 270, 690),
                opt("Spare parts", "nav-links", 270, 740),
                opt("Product documents", "nav-links", 270, 790),
            ],
        ),
    ),
    dict(
        id="element-formfields-4",
        kind="choice",
        state=STATE_SHORT,
        ids=[f"n{i}" for i in range(4)],
        q=element_question(
            "form-fields",
            [
                opt("Search terminal", "form-fields", 200, 180),
                opt("Email", "form-fields", 200, 260),
                opt("Password", "form-fields", 200, 340),
                opt("Comment", "form-fields", 200, 420),
            ],
        ),
    ),
    dict(
        id="element-buttons-3",
        kind="choice",
        state=STATE_NONASCII,
        ids=[f"n{i}" for i in range(3)],
        q=element_question(
            "buttons",
            [
                opt("Hyväksy kaikki", "buttons", 540, 900),
                opt("Vain välttämättömät", "buttons", 540, 960),
                opt("Asetukset", "buttons", 540, 1020),
            ],
        ),
    ),
    dict(
        id="element-scroll-2",
        kind="choice",
        state=STATE_LONG,
        ids=["scroll-down@n9", "scroll-up@n9"],
        q=element_question(
            "scroll",
            [
                opt("scroll-down@n9", "scroll (540,1100)", 540, 1100),
                opt("scroll-up@n9", "scroll (540,300)", 540, 300),
            ],
        ),
    ),
    # -- noul (goal-achieved verification) -----------------------------------------------
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
    # -- score (an ordered rubric) ---------------------------------------------------------
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
    # -- single-stage fallback (a non-bucketable question: the recovery set) ---------------
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
    # -- one realistic two-stage pair, LONG state + small bucket ----------------------------
    dict(
        id="bucket-stage-long-state",
        kind="choice",
        state=STATE_LONG,
        ids=["nav-links", "form-fields", "buttons", "scroll", "back", "finish"],
        q=bucket_question([b for b, _ in BUCKETS]),
    ),
    dict(
        id="element-navlinks-2-long",
        kind="choice",
        state=STATE_LONG,
        ids=["n2", "n5"],
        q=element_question(
            "nav-links",
            [
                opt("Product documents", "nav-links", 270, 740),
                opt("Compare (2)", "nav-links", 270, 590),
            ],
        ),
    ),
]


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
    args = ap.parse_args()
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
