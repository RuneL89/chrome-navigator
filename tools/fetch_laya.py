#!/usr/bin/env python
"""Phase 8 model acquisition (PHASE_08_laya_shadow.md ss2.1).

Obtains the ONNX int8 **per-tensor** build of `convaiinnovations/laya`:

  1. downloads the checkpoint snapshot at the project-pinned HF revision
     (55cf4c4ebb4ebe31b2550e8bdf3bd21b99753851 - the same revision the Phase-2
     tokenizer resource is pinned to),
  2. exports fp32 ONNX exactly as upstream `scripts/export_onnx.py` does (same dummy
     shapes, same dynamic axes, opset 18), with the revision forwarded so the snapshot
     is pinned,
  3. quantizes per-tensor int8 with the OFFICIAL upstream `quantize_model(...)`
     (MatMul-only, weight_type=QInt8, per_channel=False - per-channel is broken
     upstream, issue #790; per-channel is FORBIDDEN here),
  4. writes sha256 + provenance and places the artifact.

Why build rather than download a community build: the audited community builds
(`nvkudva/laya-web-q8`, `VishalMysore/layaForWeb` q8e8) ship **block-wise**
MatMulNBits/int8-embeddings variants, not the per-tensor int8 the phase doc and
vision 05 ss6 pin. Measured decision recorded in `.state/phase-8-status.json`.

Artifact layout (the model is ~0.5 GB and stays OUT of git):
  app/src/main/assets-local/laya/laya-int8.onnx   the artifact (gitignored)
  app/src/main/assets/laya/PROVENANCE.txt         provenance (committed)
  .tools/laya-fp32/laya.onnx                      intermediate fp32 export (gitignored)

Usage (uv is in .tools/, gitignored; no python on PATH):
  .tools/uv.exe run --no-project \\
      --with "git+https://github.com/NandhaKishorM/laya@2e4d9c87e8b1621deb344eac7de5c7258f32f849" \\
      --with "torch==2.5.1" --with "numpy<2" \\
      --with "transformers>=4.48,<5" --with onnx --with "onnxruntime==1.20.1" --with onnxscript \\
      python tools/fetch_laya.py         # build + place
  .tools/uv.exe run --no-project python tools/fetch_laya.py --check   # verify only

(torch 2.5.1 pin: the pinned upstream commit's traced forward calls the one-argument
`torch.is_autocast_enabled(device_type)` — a torch>=2.4 API — so the earlier 2.3.1 pin
cannot run the official export (measured TypeError); 2.5.1 is the verified combination on
this box. onnxruntime pins 1.20.1: the 1.30 wheel access-violates loading its pyd on this
box - measured; 1.20.1 is the verified quantization + parity runtime. The KOTLIN runtime
is com.microsoft.onnxruntime 1.30.0 (gradle/libs.versions.toml) - a different artifact
reading the same model file; the Python-side pin is a build-tool pin only.)
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import sys
import urllib.request
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parent.parent

# --- provenance pins -----------------------------------------------------------

# The laya checkpoint revision (Phase 2 pin; core/src/main/resources/laya/PROVENANCE.txt).
HF_REVISION = "55cf4c4ebb4ebe31b2550e8bdf3bd21b99753851"
HF_REPO = "convaiinnovations/laya"

# Upstream repo + the commit that last touched scripts/export_onnx.py (the file this
# tool mirrors/imports for the trace + quantization; fetched verbatim if not cached).
UPSTREAM_REPO = "NandhaKishorM/laya"
UPSTREAM_EXPORT_COMMIT = "c829d5ac2f0530f3c024c37cb812598ba47ceaa6"

# The laya PACKAGE commit this project pins (the installed `laya` whose
# `common.build_sequence`/Agent the reference vectors and this export run through).
# `scripts/export_onnx.py` is byte-identical at this commit and at UPSTREAM_EXPORT_COMMIT
# (verified 2026-10-04); a silent float past it is a provenance violation.
UPSTREAM_COMMIT = "2e4d9c87e8b1621deb344eac7de5c7258f32f849"

ASSETS_LOCAL = PROJECT_ROOT / "app" / "src" / "main" / "assets-local" / "laya"
ASSETS_COMMITTED = PROJECT_ROOT / "app" / "src" / "main" / "assets" / "laya"
FP32_DIR = PROJECT_ROOT / ".tools" / "laya-fp32"
UPSTREAM_CACHE = PROJECT_ROOT / ".tools" / "laya-upstream"

MODEL_NAME = "laya-int8.onnx"


def sha256_of(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 22), b""):
            h.update(chunk)
    return h.hexdigest()


def ensure_upstream_export_script() -> Path:
    """The pinned copy of the official export script (fetch once, then cached)."""
    cached = UPSTREAM_CACHE / "export_onnx.py"
    if cached.exists():
        return cached
    UPSTREAM_CACHE.mkdir(parents=True, exist_ok=True)
    url = (
        f"https://raw.githubusercontent.com/{UPSTREAM_REPO}/{UPSTREAM_EXPORT_COMMIT}"
        "/scripts/export_onnx.py"
    )
    print(f"fetching official export script: {url}")
    with urllib.request.urlopen(url, timeout=60) as r:
        cached.write_bytes(r.read())
    return cached


def ensure_windows_crt() -> None:
    """Windows-only: make torch AND onnxruntime importable without the VC++ redistributable.

    The torch wheels do not bundle `msvcp140.dll` / `vcruntime140*.dll`; this dev box has
    no system-wide VC runtime, so the DLLs are located from a source already on the machine
    (a self-contained extracted copy under .tools/vcrt/dlls, else the Android SDK's
    emulator set) and (a) registered with the OS DLL search for every native module and
    (b) copied app-local into torch's lib dir, where torch's own `os.add_dll_directory`
    finds them. No system change; the uv cache is gitignored. `NAVIGATOR_VCRT_DIR`
    overrides the source directory.

    ORDER IS LOAD-BEARING: the pip `mkl` torch runtime dep's `Library/bin` ships its own
    old `libiomp5md.dll`; once that dir is registered for torch, loading onnxruntime's
    native extension against it access-violates (measured: EXIT 139 inside
    `onnxruntime/capi/_pybind_state.py`). onnxruntime therefore preloads FIRST, against
    only the pure-CRT source dir; the MKL dir is registered afterwards for torch alone.
    """
    if os.name != "nt":
        return
    source = locate_crt_source()
    if source is not None:
        # a pure-CRT directory is safe for EVERY native module's load-time resolution
        _DLL_DIRECTORY_HANDLES.append(os.add_dll_directory(str(source)))
        os.environ["PATH"] = str(source) + os.pathsep + os.environ["PATH"]
    preload_onnxruntime()
    register_uv_env_native_dirs()
    try:
        import torch  # noqa: F401
        return
    except OSError:
        pass

    import glob
    import shutil

    vcomp = locate_vcomp_source()

    if source is None:
        raise SystemExit(
            "torch needs the VC++ runtime (msvcp140.dll) and no source copy was found; "
            "install https://aka.ms/vs/17/release/vc_redist.x64.exe or set NAVIGATOR_VCRT_DIR "
            "to a directory containing msvcp140.dll/vcruntime140.dll"
        )
    import importlib.util

    spec = importlib.util.find_spec("torch")
    if spec is None or not spec.submodule_search_locations:
        raise SystemExit("torch not found in this environment")
    torch_lib = os.path.join(list(spec.submodule_search_locations)[0], "lib")
    copied = []
    for dll in (
        "msvcp140.dll",
        "msvcp140_1.dll",
        "msvcp140_2.dll",
        "vcruntime140.dll",
        "vcruntime140_1.dll",
        "vcomp140.dll",
    ):
        src_dir = source if dll != "vcomp140.dll" else (vcomp or source)
        src = os.path.join(src_dir, dll)
        dst = os.path.join(torch_lib, dll)
        if os.path.isfile(src) and not os.path.isfile(dst):
            shutil.copy2(src, dst)
            copied.append(dll)
    print(f"CRT bootstrap: copied {copied or 'nothing'} from {source} into {torch_lib}")


def locate_crt_source() -> "str | None":
    """A directory holding the VC++ runtime DLLs (msvcp140.dll et al), or None."""
    import glob
    import os

    candidates = []
    if os.environ.get("NAVIGATOR_VCRT_DIR"):
        candidates.append(os.environ["NAVIGATOR_VCRT_DIR"])
    # the self-contained extracted redist this repo's tooling maintains (gitignored)
    candidates.append(str(PROJECT_ROOT / ".tools" / "vcrt" / "dlls"))
    local = os.environ.get("LOCALAPPDATA")
    if local:
        candidates.append(os.path.join(local, "Android", "Sdk", "emulator"))
        candidates.extend(sorted(glob.glob(os.path.join(local, "Python", "pythoncore-*"))))
        candidates.extend(sorted(glob.glob(os.path.join(local, "Programs", "*", "bin"))))
    candidates.extend(sorted(glob.glob(r"C:\Program Files\Eclipse Adoptium\*\bin")))
    return next(
        (d for d in candidates if os.path.isfile(os.path.join(d, "msvcp140.dll"))),
        None,
    )


def locate_vcomp_source() -> "str | None":
    """vcomp140 lives with different distributions than msvcp140 — locate independently."""
    import glob
    import os

    local = os.environ.get("LOCALAPPDATA")
    vcomp_candidates = (
        ([os.environ["NAVIGATOR_VCRT_DIR"]] if os.environ.get("NAVIGATOR_VCRT_DIR") else [])
        + [str(PROJECT_ROOT / ".tools" / "vcrt" / "dlls")]
        + ([local] if local else [])
        + sorted(glob.glob(os.path.join(local or ".", "Python", "pythoncore-*")))
        + [r"C:\Windows\System32"]
    )
    return next(
        (d for d in vcomp_candidates if os.path.isfile(os.path.join(d, "vcomp140.dll"))),
        None,
    )


def preload_onnxruntime() -> None:
    """Import onnxruntime BEFORE the MKL dirs join the DLL search (see ensure_windows_crt).

    Failure here is not fatal — `--check` mode never needs it, and the quantize step
    re-imports and fails loudly with the real error if the environment is broken.
    """
    try:
        import onnxruntime  # noqa: F401

        print("onnxruntime preloaded (before MKL dir registration)")
    except Exception as e:  # pragma: no cover - environment-specific
        print(f"onnxruntime preload failed (will retry where needed): {e}")


def register_uv_env_native_dirs() -> None:
    """Register the ephemeral-uv-env `Library/bin` with the OS DLL search.

    torch's Windows bootstrap adds `<sys.exec_prefix>/Library/bin` (the conda layout) to
    the DLL search — but under uv the site-packages live in the cache archive dir while
    `sys.exec_prefix` stays the base interpreter, so the torch dependency `mkl`'s
    `mkl_intel_thread.1.dll` (installed at `<env>/Library/bin`) is never found and torch
    import dies with WinError 126. Registering the dir explicitly fixes torch 2.3.
    """
    if os.name != "nt":
        return
    import importlib.util
    import pathlib

    spec = importlib.util.find_spec("torch")
    if spec is None or not spec.submodule_search_locations:
        return
    torch_root = pathlib.Path(list(spec.submodule_search_locations)[0])
    # walk up from .../Lib/site-packages/torch to the env root (the dir holding the
    # conda-style Library/bin where the `mkl` wheel installs its DLLs)
    native = next(
        (
            parent / "Library" / "bin"
            for parent in torch_root.parents
            if (parent / "Library" / "bin" / "mkl_intel_thread.1.dll").is_file()
        ),
        None,
    )
    if native is not None:
        # the returned handle MUST stay referenced or the directory is de-registered
        _DLL_DIRECTORY_HANDLES.append(os.add_dll_directory(str(native)))
        os.environ["PATH"] = str(native) + os.pathsep + os.environ["PATH"]


_DLL_DIRECTORY_HANDLES: list = []


def export_and_quantize(out_dir: Path) -> None:
    import torch  # noqa: F401  (env check: the uv --with set must include torch)
    import torch.version  # noqa: F401  (torch 2.3 does not auto-import it; transformers'
    # flex_attention import touches torch.version via torch.compiler and would crash)

    script = ensure_upstream_export_script()
    sys.path.insert(0, str(script.parent))
    # Official upstream code, imported verbatim: quantize_model is THE per-tensor int8
    # quantization (MatMul-only, QInt8, per_channel=False) - never re-typed, never tuned.
    import export_onnx as upstream

    from laya.agent import Agent

    out_dir.mkdir(parents=True, exist_ok=True)
    fp32_path = FP32_DIR / "laya.onnx"
    FP32_DIR.mkdir(parents=True, exist_ok=True)
    int8_path = out_dir / MODEL_NAME

    print(f"loading Agent from {HF_REPO}@{HF_REVISION} (CPU)...")
    agent = Agent(HF_REPO, compile=False, device="cpu", revision=HF_REVISION)

    # --- the official trace call, verbatim from export_onnx.export_to_onnx -----
    # (only difference: the Agent is constructed above with the pinned revision).
    batch, seq_len, num_markers = 2, 17, 3
    dummy_input_ids = torch.randint(0, 100, (batch, seq_len), dtype=torch.long)
    dummy_attention_mask = torch.ones((batch, seq_len), dtype=torch.long)
    dummy_marker_pos = torch.tensor([[1, 5, 9]] * batch, dtype=torch.long)
    dummy_marker_mask = torch.ones((batch, num_markers), dtype=torch.bool)
    dummy_qtype = torch.zeros(batch, dtype=torch.long)
    inputs = (
        dummy_input_ids,
        dummy_attention_mask,
        dummy_marker_pos,
        dummy_marker_mask,
        dummy_qtype,
    )
    dynamic_axes = {
        "input_ids": {0: "batch_size", 1: "seq_len"},
        "attention_mask": {0: "batch_size", 1: "seq_len"},
        "marker_pos": {0: "batch_size", 1: "num_markers"},
        "marker_mask": {0: "batch_size", 1: "num_markers"},
        "qtype": {0: "batch_size"},
        "logits": {0: "batch_size", 1: "num_markers"},
        "act_logits": {0: "batch_size"},
    }
    input_names = ["input_ids", "attention_mask", "marker_pos", "marker_mask", "qtype"]
    output_names = ["logits", "act_logits"]
    print(f"exporting fp32 ONNX to {fp32_path} ...")
    torch.onnx.export(
        agent.model,
        inputs,
        str(fp32_path),
        export_params=True,
        opset_version=18,
        do_constant_folding=True,
        input_names=input_names,
        output_names=output_names,
        dynamic_axes=dynamic_axes,
    )

    print(f"quantizing per-tensor int8 (official quantize_model) -> {int8_path} ...")
    upstream.quantize_model(str(fp32_path), str(int8_path), per_channel=False)
    print("done.")


def write_provenance(int8_path: Path, fp32_path: Path) -> None:
    meta = {
        "model": HF_REPO,
        "revision": HF_REVISION,
        "file": MODEL_NAME,
        "sha256": sha256_of(int8_path),
        "bytes": int8_path.stat().st_size,
        "quantization": (
            "int8 dynamic, per-tensor (official scripts/export_onnx.py quantize_model: "
            "MatMul-only, weight_type=QInt8, per_channel=False); per-channel forbidden "
            "(upstream issue #790)"
        ),
        "export": (
            "torch.onnx.export opset 18, official dummy shapes/dynamic axes, fp32 trace "
            f"(fp32 intermediate sha256 {sha256_of(fp32_path)})"
        ),
        "upstream_export_script": (
            f"https://github.com/{UPSTREAM_REPO}/blob/{UPSTREAM_EXPORT_COMMIT}"
            "/scripts/export_onnx.py"
        ),
        "upstream_package_commit": (
            f"https://github.com/{UPSTREAM_REPO}/blob/{UPSTREAM_COMMIT} (the installed laya "
            "package: Agent + common.build_sequence the reference vectors run through)"
        ),
        "graph_inputs": ["input_ids", "attention_mask", "marker_pos", "marker_mask", "qtype"],
        "graph_outputs": ["logits", "act_logits"],
        "checkpoint_config": "rl_agent_config.json at the pinned revision "
        "(max_len=512, head_max_len=192, temperatures in temperature_by_options)",
        "license": "Apache-2.0",
        "built": "by tools/fetch_laya.py (community ONNX builds audited and rejected: "
        "block-wise MatMulNBits, not the pinned per-tensor int8)",
    }
    lines = [
        "PROVENANCE - laya ONNX int8 shadow model (Phase 8)",
        "",
        f"File: {MODEL_NAME}",
        f"Source repo: https://huggingface.co/{HF_REPO}",
        f"Revision: {HF_REVISION} (same pin as the Phase-2 tokenizer resource)",
        f"sha256: {meta['sha256']}",
        f"Bytes: {meta['bytes']}",
        f"Quantization: {meta['quantization']}",
        f"Export: {meta['export']}",
        f"Graph inputs: {', '.join(meta['graph_inputs'])}",
        f"Graph outputs: {', '.join(meta['graph_outputs'])}",
        f"Upstream export script: {meta['upstream_export_script']}",
        f"Upstream package commit: {meta['upstream_package_commit']}",
        "License: Apache-2.0",
        "Built by: tools/fetch_laya.py; build decision recorded in "
        ".state/phase-8-status.json",
        "",
        "Machine-readable copy:",
        json.dumps(meta, indent=2),
        "",
    ]
    ASSETS_COMMITTED.mkdir(parents=True, exist_ok=True)
    (ASSETS_COMMITTED / "PROVENANCE.txt").write_text("\n".join(lines), encoding="utf-8")
    (out_dir_meta := ASSETS_LOCAL / "provenance.json").write_text(
        json.dumps(meta, indent=2), encoding="utf-8"
    )


def check() -> int:
    model = ASSETS_LOCAL / MODEL_NAME
    prov = ASSETS_COMMITTED / "PROVENANCE.txt"
    if not model.exists():
        print(f"FAIL: {model} is missing - run tools/fetch_laya.py (build mode) first")
        return 1
    if not prov.exists():
        print(f"FAIL: {prov} is missing")
        return 1
    recorded = None
    for line in prov.read_text(encoding="utf-8").splitlines():
        if line.startswith("sha256: "):
            recorded = line.removeprefix("sha256: ").strip()
            break
    actual = sha256_of(model)
    if recorded != actual:
        print(f"FAIL: sha256 mismatch for {model}: provenance says {recorded}, file is {actual}")
        return 1
    print(f"OK: {model} present, sha256 matches provenance ({actual[:16]}...)")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--check", action="store_true", help="verify presence + sha256 only")
    ap.add_argument(
        "--skip-export-if-present",
        action="store_true",
        help="skip the (expensive) export when the int8 artifact already exists",
    )
    args = ap.parse_args()
    if args.check:
        return check()
    ensure_windows_crt()
    model = ASSETS_LOCAL / MODEL_NAME
    if args.skip_export_if_present and model.exists():
        print(f"int8 artifact already present: {model}")
    else:
        export_and_quantize(ASSETS_LOCAL)
    fp32_path = FP32_DIR / "laya.onnx"
    write_provenance(model, fp32_path)
    print(f"provenance written: {ASSETS_COMMITTED / 'PROVENANCE.txt'}")
    return check()


if __name__ == "__main__":
    raise SystemExit(main())
