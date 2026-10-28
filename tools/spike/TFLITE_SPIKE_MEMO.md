# TFLite spike memo — neural notation engine revival (2026-10-28)

Question (user-approved spike): can CREPE-tiny run as a TFLite model
fast enough to revive the neural pitch tracker that the first spike
(DSP-4) descoped, when the pure-NumPy path projected 132–408 s of
phone inference for a 3-minute song against the PRD's ~90 s gate?

## Method

- `tools/convert_crepe_tflite.py` builds the Keras model layer-for-
  layer as `forward_nn` in `tools/benchmark_crepe.py` (itself a port
  of crepe/core.py 'tiny') and loads the weights **from the verified
  `tools/spike/crepe_tiny.npz`** — the TFLite model cannot drift from
  the tensors the NumPy reference runs. TensorFlow 2.21.0 (CPU venv).
- Output: float32 `tools/spike/crepe_tiny.tflite`, no quantization.
  Frame normalisation stays outside the model, as in the nn.py path.
- `tools/benchmark_crepe_tflite.py` checks parity against the NumPy
  reference and benchmarks both, using the first spike's projection
  method (6000 frames/min at a 10 ms hop; phone = VM x 5).

## Results

| Gate | Result |
|---|---|
| Model size ≤ 2 MB | **1,957,340 bytes (1.957 MB)** — pass, little headroom |
| Parity (noise frames) | 32/32 argmax agree, max \|diff\| 7.5e-07, 0 cents |
| Parity (sine frames, 110–440 Hz) | 6/6 argmax agree, max \|diff\| 8.0e-07, 0 cents |
| NumPy reference (control) | 2.410 ms/frame → 216.9 s phone projection (inside spike 1's 132–408 s range, as expected) |
| TFLite frame-by-frame | 0.836 ms/frame → **75.3 s** phone projection |
| TFLite batched (32) | 0.820 ms/frame → **73.8 s** phone projection; 32/32 argmax agreement |

Parity is exact at decision level: the earlier spike's open
"TensorFlow parity UNVERIFIED" item is closed for this model.

## Caveats

- The x5 VM→phone factor is the PRD's blunt instrument. This VM is a
  shared 2-core x86; the phone runs ARM with XNNPACK (and possibly
  GPU/NNAPI delegates the desktop interpreter does not use here).
  75 s vs a 90 s gate is a pass on projection, not a measurement —
  the deciding benchmark must run on the phone.
- Batching barely helps through the Python interpreter; on-device,
  per-frame invoke overhead differs again. Frame-by-frame numbers
  are the honest planning figure.
- Levers not exercised: float16 quantization (~halves the model to
  ~1.0 MB, minimal accuracy risk) and int8 quantization (faster on
  ARM, needs a fresh parity + accuracy pass).

## Verdict

**GO, conditional.** Size and parity gates pass outright; speed
passes on the standard projection with ~17% headroom. Next step if
the engine is approved: an on-device benchmark harness in a debug
build (real phone timing for a 3-minute song) BEFORE any UI wiring;
then the accuracy A/B against Classic on the gamaka fixtures, where
Classic collapses to ~48% note accuracy and neural has to earn the
switch. The DSP Lab Notation row's Neural segment stays disabled
until both pass.
