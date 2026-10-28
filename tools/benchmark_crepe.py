#!/usr/bin/env python3
"""Spike (DSP-0) benchmark + cross-check for the CREPE-tiny forward pass.

Dev-machine only, not shipped logic. Two jobs:

1. Cross-check: run the CREPE-tiny topology over `audiofx.nn` and over
   an INDEPENDENT reference implementation written here (convolution
   as an explicit loop over kernel taps -- no im2col, no shared conv
   code) on 10 synthetic frames; report argmax agreement per frame.
   NOTE: this is an implementation cross-check, not TensorFlow/Keras
   parity -- no TF runtime was available in the dev environment (see
   SPIKE_MEMO.md).

2. Benchmark: time the nn-based forward pass at several batch sizes
   and project seconds-of-compute per minute of audio (16 kHz,
   1024-sample windows, 10 ms hop = 6000 frames/minute) plus the PRD
   phone projection (VM x 5) for a 3-minute song.

The forward pass follows crepe/core.py build_and_load_model('tiny')
exactly: per block, Keras 'same'-padded Conv2D (cross-correlation)
-> ReLU (the Conv2D activation) -> BatchNormalization (moving
statistics, eps from the npz) -> MaxPool (2, 1); Dropout is identity
at inference. Then Permute (2, 1, 3) -> Flatten -> Dense sigmoid.
Frames are pre-normalised per frame exactly as crepe.get_activation
does (subtract mean, divide by clipped std).

Usage: python3 tools/benchmark_crepe.py [--profile]
"""
import os
import sys
import time

sys.path.insert(
    0,
    os.path.join(
        os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
        "app", "src", "main", "python",
    ),
)
import numpy as np

from audiofx import nn

STRIDES = [4, 1, 1, 1, 1, 1]
FRAMES_PER_MINUTE = 6000  # 60 s / 10 ms hop


def _same_pad(x, kernel_h, stride):
    """Keras padding='same' along H: total pad split floor/ceil."""
    in_h = x.shape[1]
    out_h = -(-in_h // stride)
    total = max((out_h - 1) * stride + kernel_h - in_h, 0)
    left = total // 2
    right = total - left
    if total == 0:
        return x
    return np.pad(x, ((0, 0), (left, right), (0, 0), (0, 0)))


def _batch_norm(y, w, i):
    eps = float(w["bn_eps"])
    return (
        (y - w["conv%d_bn_mean" % i])
        / np.sqrt(w["conv%d_bn_var" % i] + eps)
        * w["conv%d_bn_gamma" % i]
        + w["conv%d_bn_beta" % i]
    ).astype(np.float32)


def _maxpool2(y):
    n, h, wd, c = y.shape
    return y.reshape(n, h // 2, 2, wd, c).max(axis=2).astype(np.float32)


def _finish(y, w):
    # Permute (2, 1, 3) on (H, W, C) -> (W, H, C), then Flatten, Dense.
    y = y.transpose(0, 2, 1, 3).reshape(y.shape[0], -1)
    return nn.sigmoid(nn.dense(y, w["classifier_kernel"], w["classifier_bias"]))


def forward_nn(frames, w):
    """CREPE-tiny forward over audiofx.nn primitives."""
    y = frames.reshape(-1, 1024, 1, 1).astype(np.float32)
    for i in range(1, 7):
        kern = w["conv%d_kernel" % i]
        y = _same_pad(y, kern.shape[0], STRIDES[i - 1])
        y = nn.conv2d(y, kern, w["conv%d_bias" % i], stride=(STRIDES[i - 1], 1))
        y = nn.relu(y)
        y = _batch_norm(y, w, i)
        y = _maxpool2(y)
    return _finish(y, w)


def _conv_taps(x, kern, bias, stride):
    """Reference convolution: explicit loop over kernel taps.

    out[n, p, q, o] = sum_{t, u, c} x[n, p*s+t, q*s+u, c] * kern[t, u, c, o]
    computed by accumulating shifted slices -- deliberately shares no
    code with nn.conv2d's im2col path.
    """
    sh, sw = stride
    n, h, wd, _ = x.shape
    kh, kw, _, c_out = kern.shape
    h_out = (h - kh) // sh + 1
    w_out = (wd - kw) // sw + 1
    out = np.zeros((n, h_out, w_out, c_out), dtype=np.float32)
    for t in range(kh):
        for u in range(kw):
            sl = x[:, t : t + sh * h_out : sh, u : u + sw * w_out : sw, :]
            out += sl @ kern[t, u]
    return (out + bias).astype(np.float32)


def forward_ref(frames, w):
    """CREPE-tiny forward over the tap-loop reference convolution."""
    y = frames.reshape(-1, 1024, 1, 1).astype(np.float32)
    for i in range(1, 7):
        kern = w["conv%d_kernel" % i]
        y = _same_pad(y, kern.shape[0], STRIDES[i - 1])
        y = _conv_taps(y, kern, w["conv%d_bias" % i], (STRIDES[i - 1], 1))
        y = nn.relu(y)
        y = _batch_norm(y, w, i)
        y = _maxpool2(y)
    # Reference finish: einsum instead of nn.dense's matmul expression.
    y = y.transpose(0, 2, 1, 3).reshape(y.shape[0], -1)
    logits = np.einsum("ij,jk->ik", y, w["classifier_kernel"]) + w["classifier_bias"]
    return nn.sigmoid(logits.astype(np.float32))


def _time_one(fn):
    t0 = time.perf_counter()
    fn()
    return time.perf_counter() - t0


def synthetic_frames(n_frames, seed=1234):
    rng = np.random.default_rng(seed)
    frames = rng.standard_normal((n_frames, 1024), dtype=np.float32)
    frames -= frames.mean(axis=1, keepdims=True)
    frames /= np.clip(frames.std(axis=1, keepdims=True), 1e-8, None)
    return frames.astype(np.float32)


def cross_check(w, n_frames=10):
    frames = synthetic_frames(n_frames)
    a = forward_nn(frames, w)
    b = forward_ref(frames, w)
    agree = int((np.argmax(a, axis=1) == np.argmax(b, axis=1)).sum())
    max_diff = float(np.abs(a - b).max())
    print(
        "cross-check (nn im2col vs independent tap-loop reference): "
        "%d/%d frames argmax agree, max |diff| = %.3e" % (agree, n_frames, max_diff)
    )
    return agree, max_diff


def benchmark(w):
    print("numpy %s" % np.__version__)
    rows = []
    for batch, reps in [(1, 5), (8, 3), (16, 3), (32, 2), (64, 2)]:
        frames = synthetic_frames(batch, seed=99)
        forward_nn(frames, w)  # warmup
        # Min-of-reps: this dev VM is shared and heavily contended, so
        # the least-contended repetition is the least-biased estimate
        # of the machine's actual throughput.
        per_frame = min(
            _time_one(lambda: forward_nn(frames, w)) / batch for _ in range(reps)
        )
        per_min = per_frame * FRAMES_PER_MINUTE
        phone_3min = per_min * 3 * 5
        rows.append((batch, per_frame, per_min, phone_3min))
        print(
            "batch %3d: %8.3f ms/frame | %8.1f s per min of audio (VM) | "
            "3-min song phone projection (x5): %8.1f s"
            % (batch, per_frame * 1e3, per_min, phone_3min)
        )
    best = min(rows, key=lambda r: r[1])
    print(
        "best: batch %d at %.3f ms/frame -> %.1f s/min VM, %.1f s phone "
        "for a 3-minute song" % (best[0], best[1] * 1e3, best[2], best[3])
    )
    return best


def profile(w, batch=16):
    """Per-layer time at a fixed batch size (nn path)."""
    frames = synthetic_frames(batch, seed=7)
    y = frames.reshape(-1, 1024, 1, 1).astype(np.float32)
    print("profile at batch %d (ms/frame):" % batch)
    for i in range(1, 7):
        kern = w["conv%d_kernel" % i]
        yp = _same_pad(y, kern.shape[0], STRIDES[i - 1])
        bias = w["conv%d_bias" % i]
        stride = (STRIDES[i - 1], 1)
        yc = nn.conv2d(yp, kern, bias, stride=stride)  # warmup + value
        t_conv = min(_time_one(lambda: nn.conv2d(yp, kern, bias, stride=stride))
                     for _ in range(3))
        y = _maxpool2(_batch_norm(nn.relu(yc), w, i))
        t_rest = min(
            _time_one(lambda: _maxpool2(_batch_norm(nn.relu(yc), w, i)))
            for _ in range(3)
        )
        print(
            "  conv%d: out %s, conv %7.3f ms/frame, relu+bn+pool %6.3f"
            % (i, yc.shape[1:], t_conv / batch * 1e3, t_rest / batch * 1e3)
        )
    _finish(y, w)  # warmup
    t_dense = min(_time_one(lambda: _finish(y, w)) for _ in range(3))
    print("  dense+sigmoid: %6.3f ms/frame" % (t_dense / batch * 1e3))


def main():
    weights_path = os.path.join(
        os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
        "app", "src", "main", "python", "audiofx", "crepe_tiny.npz",
    )
    w = nn.load_weights(weights_path)
    cross_check(w)
    benchmark(w)
    if "--profile" in sys.argv:
        profile(w)
    return 0


if __name__ == "__main__":
    sys.exit(main())
