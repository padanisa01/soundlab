#!/usr/bin/env python3
"""TFLite spike: parity + benchmark of crepe_tiny.tflite against the
NumPy reference (forward_nn from tools/benchmark_crepe.py).

Parity: synthetic noise frames (benchmark_crepe.synthetic_frames) and
pitched sine frames at known f0; report argmax agreement, max |diff|
over the 360-bin outputs, and the largest argmax bin distance
(1 bin = 20 cents).

Benchmark: per-frame time for (a) the NumPy reference at batch 32,
(b) the TFLite interpreter invoked frame-by-frame, and (c) the TFLite
interpreter with a resized batch input, when the model allows it.
Projection follows the first spike's method: 6000 frames per minute
of audio (16 kHz, 10 ms hop), phone = VM x 5, gate = 90 s for a
3-minute song (PRD).

Dev-machine only; requires TensorFlow (for tf.lite.Interpreter).

Usage: python3 tools/benchmark_crepe_tflite.py
"""
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import numpy as np

from benchmark_crepe import forward_nn, synthetic_frames

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
NPZ = os.path.join(REPO, "tools", "spike", "crepe_tiny.npz")
TFLITE = os.path.join(REPO, "tools", "spike", "crepe_tiny.tflite")
FRAMES_PER_MINUTE = 6000


def sine_frames(freqs, sr=16000):
    """One normalised 1024-sample frame per frequency."""
    t = np.arange(1024, dtype=np.float32) / sr
    out = []
    for f in freqs:
        frame = np.sin(2 * np.pi * f * t).astype(np.float32)
        frame -= frame.mean()
        frame /= max(frame.std(), 1e-8)
        out.append(frame)
    return np.stack(out).astype(np.float32)


class TfliteRunner:
    def __init__(self, path):
        import tensorflow as tf
        self.interp = tf.lite.Interpreter(model_path=path)
        self.interp.allocate_tensors()
        self.inp = self.interp.get_input_details()[0]
        self.out = self.interp.get_output_details()[0]

    def run_frames(self, frames):
        """Frame-by-frame: (N, 1024) -> (N, 360)."""
        res = []
        for fr in frames:
            self.interp.set_tensor(
                self.inp["index"], fr.reshape(1, 1024, 1, 1))
            self.interp.invoke()
            res.append(self.interp.get_tensor(self.out["index"])[0])
        return np.stack(res).astype(np.float32)

    def try_batched(self, frames):
        """Resize the input to the batch and run once; None if the
        model does not accept a resized batch."""
        try:
            self.interp.resize_tensor_input(
                self.inp["index"],
                (len(frames), 1024, 1, 1))
            self.interp.allocate_tensors()
            self.interp.set_tensor(
                self.inp["index"],
                frames.reshape(-1, 1024, 1, 1))
            self.interp.invoke()
            return self.interp.get_tensor(
                self.out["index"]).astype(np.float32)
        except Exception as e:
            print("batched run unavailable: %s" % e)
            return None


def _time(fn, reps=3):
    return min(_once(fn) for _ in range(reps))


def _once(fn):
    t0 = time.perf_counter()
    fn()
    return time.perf_counter() - t0


def report(name, per_frame_s):
    per_min = per_frame_s * FRAMES_PER_MINUTE
    phone = per_min * 3 * 5
    print("%-28s %8.3f ms/frame | %7.1f s/min (VM) | 3-min phone "
          "projection (x5): %7.1f s"
          % (name, per_frame_s * 1e3, per_min, phone))
    return phone


def main():
    from audiofx import nn
    w = nn.load_weights(NPZ)
    runner = TfliteRunner(TFLITE)

    # ---- parity -------------------------------------------------
    noise = synthetic_frames(32)
    sines = sine_frames([110.0, 164.81, 220.0, 261.63, 329.63, 440.0])
    for label, frames in (("noise", noise), ("sines", sines)):
        ref = forward_nn(frames, w)
        got = runner.run_frames(frames)
        agree = int((np.argmax(ref, axis=1)
                     == np.argmax(got, axis=1)).sum())
        max_diff = float(np.abs(ref - got).max())
        bin_dist = int(np.abs(np.argmax(ref, axis=1)
                              - np.argmax(got, axis=1)).max())
        print("parity %-6s: %d/%d argmax agree, max |diff| = %.3e, "
              "max bin distance = %d (%d cents)"
              % (label, agree, len(frames), max_diff, bin_dist,
                 bin_dist * 20))

    # ---- benchmark ----------------------------------------------
    frames32 = synthetic_frames(32, seed=99)
    forward_nn(frames32, w)
    t_nn = _time(lambda: forward_nn(frames32, w)) / 32
    runner.run_frames(frames32[:2])
    t_lite1 = _time(lambda: runner.run_frames(frames32)) / 32
    print()
    report("numpy reference (batch 32)", t_nn)
    report("tflite frame-by-frame", t_lite1)
    batched = runner.try_batched(frames32)
    if batched is not None:
        ref = forward_nn(frames32, w)
        agree = int((np.argmax(ref, axis=1)
                     == np.argmax(batched, axis=1)).sum())
        print("batched output argmax agreement vs reference: %d/32"
              % agree)
        t_liteb = _time(lambda: runner.try_batched(frames32)) / 32
        report("tflite batched (32)", t_liteb)
    return 0


if __name__ == "__main__":
    sys.exit(main())
