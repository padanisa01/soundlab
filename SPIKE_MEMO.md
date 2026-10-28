# SPIKE MEMO — DSP-0 de-risking spike (CREPE-tiny, pure NumPy)

Date: 2026-10-28. Branch: `dsp/spike` (worktree
`~/workspace/audiofx-worktrees/spike`), base `dsplab` @ cad7cfc.
Tasks S1–S3 of the DSP-upgrades task list; PRD decisions D1/D5 and
the PRD de-risking-spike section govern the gates below.

## Verdict: NO-GO for DSP-4 (neural pitch) in this branch

Per the PRD rule — a 3-minute song projecting to > ~90 s of phone
inference (dev-VM time × 5) ⇒ F4 is descoped from this branch
(separate TFLite proposal) and F5 ships Tier 1 only. Every measured
configuration projects well over 90 s; the most favourable single
measurement taken during the spike projects to ~199 s, and a
FLOP-count floor (below) shows ~156 s is unreachable-to-beat with a
pure-NumPy matmul implementation on this VM. Details in §3.

The D5 size gate, by contrast, PASSES (§1). Parity against the
reference TensorFlow implementation could NOT be run — no TF
runtime exists in this environment — and is recorded as an explicit
limitation, not a pass (§2).

## 1. S1 — Weights conversion (PASS, D5 gate passed)

- Source: official CREPE release, MIT license
  (github.com/marl/crepe). The git tree on `master` carries no
  weights; `setup.py` in that repo downloads them from the
  `models` branch. Downloaded with curl:
  `https://github.com/marl/crepe/raw/models/model-tiny.h5.bz2`
  (1,307,310 bytes compressed; decompressed
  `model-tiny.h5` = 2,011,144 bytes, HDF5 signature verified).
- Converter: `tools/convert_crepe_weights.py` (dev-only; run under a
  throwaway venv with h5py 3.16.0 — the system Python has no h5py).
  It validates every tensor shape against the CREPE-tiny topology
  before writing, and re-loads the result with
  `allow_pickle=False`.
- Output: `app/src/main/python/audiofx/crepe_tiny.npz`,
  **1,537,288 bytes (1.537 MB) — ≤ 2 MB, D5 gate PASSED**
  (np.savez_compressed, all float32, data only).
- Tensor shapes vs topology (crepe/core.py, capacity 'tiny' =
  multiplier 4; 6 conv blocks, filters [128, 16, 16, 16, 32, 64],
  widths [512, 64, 64, 64, 64, 64], strides [(4,1), (1,1)×5],
  360 output bins) — all matched exactly:

  | tensor | shape | tensor | shape |
  |---|---|---|---|
  | conv1_kernel | (512, 1, 1, 128) | conv4_kernel | (64, 1, 16, 16) |
  | conv2_kernel | (64, 1, 128, 16) | conv5_kernel | (64, 1, 16, 32) |
  | conv3_kernel | (64, 1, 16, 16) | conv6_kernel | (64, 1, 32, 64) |
  | classifier_kernel | (256, 360) | classifier_bias | (360,) |

  plus per-block bias and BatchNorm gamma/beta/moving-mean/
  moving-variance vectors sized to each block's filter count —
  39 keys total, including a scalar `bn_eps` = 1e-3 (the Keras
  BatchNormalization default; the h5 file does not store it).
  Key naming: `conv{i}_kernel`, `conv{i}_bias`, `conv{i}_bn_gamma`,
  `conv{i}_bn_beta`, `conv{i}_bn_mean`, `conv{i}_bn_var`,
  `classifier_kernel`, `classifier_bias`, `bn_eps`.

## 2. S2 — nn.py and parity (hand-verified; TF parity NOT run)

- `app/src/main/python/audiofx/nn.py` implements the frozen
  contract: `conv2d(x, w, b, stride=1, pad=0)` (im2col +
  matmul, Keras cross-correlation semantics, channels-last,
  3-D or batched 4-D input), `dense`, `relu`, `sigmoid`
  (numerically stable), `load_weights` using
  `numpy.load(..., allow_pickle=False)` ONLY. Pure NumPy float32.
- TDD: `tests/test_nn.py` was written first and seen failing
  (`ImportError: cannot import name 'nn' from 'audiofx'`), then
  made green: 14 tests, including hand-computed conv2d cases
  (single-channel 2×2 kernel, bias + two filters, stride 2,
  symmetric padding), hand-computed dense, known sigmoid values,
  and a test that a pickled-object npz is rejected by
  `load_weights`.
- PARITY LIMITATION (explicit): the PRD asks for bin-for-bin
  argmax agreement with the reference implementation on 10
  synthetic frames. No TensorFlow/Keras runtime is available in
  this environment (`import tensorflow` →
  `ModuleNotFoundError: No module named 'tensorflow'` on the
  system Python; none was installed — installing a full TF stack
  was out of the spike's scope), so **TF parity is UNVERIFIED**.
- Substitute check actually run (`tools/benchmark_crepe.py`): the
  full CREPE-tiny forward over nn.py vs an independent reference
  implementation written for the spike (convolution as an explicit
  loop over kernel taps, dense via einsum — no shared conv code),
  on 10 synthetic frames:
  - venv NumPy 2.5.4: **10/10 frames argmax agree**,
    max |activation diff| = 2.682e-07.
  - system NumPy 1.26.4: **10/10 frames argmax agree**,
    max |activation diff| = 3.278e-07.
  This validates nn.py's wiring/math against an independent
  implementation; it does not validate against Keras itself
  (e.g. a padding-convention discrepancy shared by both spike
  implementations would not be caught). If DSP-4 is ever revived,
  the TF check remains an open acceptance item.

## 3. S3 — Benchmark (NO-GO)

Method: `tools/benchmark_crepe.py` — full CREPE-tiny forward pass
(conv → ReLU → BatchNorm → maxpool ×6, permute/flatten, dense
sigmoid) over per-frame-normalised synthetic frames; 16 kHz,
1024-sample windows, 10 ms hop ⇒ 6,000 frames per minute of audio.
Min-of-reps timing (the dev VM is a shared, contended 2-CPU box).

This VM's BLAS build dominates the numbers, so both available
NumPy builds were measured (same hardware):

| environment | peak matmul (2048³, measured) | best batch | ms/frame | s / min of audio (VM) | 3-min phone projection (×5) |
|---|---|---|---|---|---|
| venv PyPI NumPy 2.5.4 (OpenBLAS) | 42.5 GFLOPS | 32 | 4.166 | 25.0 | **375.0 s** |
| system NumPy 1.26.4 (generic reference BLAS — the `tools/verify.py` interpreter) | 1.5 GFLOPS | 32 | 29.307 | 175.8 | **2637.6 s** |

Full venv batch sweep (ms/frame): batch 1: 5.996, 8: 6.410,
16: 4.674, 32: 4.166, 64: 11.936 (batch 64 degrades — conv2's
im2col matrix outgrows cache). The single most favourable
measurement of the whole spike (an early mean-of-reps probe,
batch 16, venv NumPy) was 2.21 ms/frame ⇒ 13.2 s/min VM ⇒
**~199 s phone for 3 min** — still over the gate.

Per-layer profile (venv, batch 16, ms/frame): conv1 0.469,
conv2 3.444, conv3 0.046, conv4 0.020, conv5 0.014, conv6 0.020,
dense+sigmoid 0.006; ReLU+BN+pool ≤ 0.301 (conv1), ≤ 0.015 else.
Conv2 dominates: its im2col form is (128 positions × batch) ×
(64 taps × 128 in-channels) against only 16 output channels.

FLOP floor (contention-independent): the topology costs
36,792,320 MACs ≈ 73.6 MFLOP per frame (conv1 and conv2 are
16,777,216 MACs each). At this VM's best measured BLAS peak
(42.5 GFLOPS), a hypothetical 100%-efficient implementation
needs ≥ 1.73 ms/frame ⇒ ≥ 10.4 s/min VM ⇒ **≥ ~156 s phone for a
3-minute song** — the ~90 s gate cannot be met by pure-NumPy
matmul on this VM under the PRD's ×5 rule, regardless of tuning.

### Go / No-Go

- D5 size gate: **GO** (1,537,288 bytes ≤ 2 MB).
- DSP-4 neural pitch (and with it DSP-5 Tier 2, which D4 ties to
  the spike): **NO-GO** for this branch. Best phone projection
  375.0 s (reproducible benchmark) / ~199 s (best single probe) /
  ~156 s (theoretical floor) — all > ~90 s.
- Per the PRD, DSP-4 is descoped to a separate TFLite proposal
  and DSP-5 ships Tier 1 only. `nn.py` and `crepe_tiny.npz` land
  anyway: nn.py is unit-tested infrastructure the contracts
  assign to DSP-0, and the npz passes its size gate; whether to
  keep the 1.5 MB npz in the APK while DSP-4 is descoped is a
  Phase 8 / user call, flagged here rather than decided here.

## Reproduce

```
curl -fL -o model-tiny.h5.bz2 \
  https://github.com/marl/crepe/raw/models/model-tiny.h5.bz2
python3 -c "import bz2; open('model-tiny.h5','wb').write(bz2.decompress(open('model-tiny.h5.bz2','rb').read()))"
<venv-with-h5py>/python tools/convert_crepe_weights.py model-tiny.h5
python3 tools/benchmark_crepe.py --profile     # either interpreter
python3 -m unittest tests.test_nn -v
python3 tools/verify.py
```
