# tools/ — DOX contract

## Purpose

**Retired folder — no live scripts.** The model-acquisition and reference-generation
pipeline that lived here (the Phase-8 laya ONNX build and its reference vectors) is
**RETIRED by owner directive 2026-10-19** (PHASE_VEXP3): the on-device shadow engine,
its 569 MB artifact, the Room/RLCD/training program, and both scripts
(`fetch_laya.py`, `laya_reference.py`) are deleted from the tree. The state-text budget
law that survives in `:core` needs no generator — its fixtures are committed.

## Ownership

* Nothing. Do NOT add a model-download, ONNX-export, or vector-generation script
  without a new owner directive and its own phase plan; a reintroduced training
  pipeline would also have to restore a provenance contract this folder no longer has

## Local Contracts

* The surviving tokenizer fixtures under `core/src/main/resources/laya/` and
  `core/src/test/resources/tokenizer/` are provenance-pinned and immutable; a tokenizer
  change is a fixture-level event (regenerate vectors in the same commit, with the
  generator re-established by that plan)

## Work Guidance

* History only: the deleted scripts remain in git history for provenance archaeology;
  nothing here runs in any build

## Verification

* `./gradlew :core:test` — the tokenizer reference-vector test is the executable
  contract for the surviving fixtures; a mismatch is a real change, never flakiness

## Child DOX Index

No child folders.
