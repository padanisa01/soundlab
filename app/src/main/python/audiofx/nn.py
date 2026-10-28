"""Minimal pure-NumPy inference helpers (DSP-0 / DSP-4).

Just the primitives the CREPE-tiny forward pass needs -- nothing more:

    conv2d(x, w, b, stride=1, pad=0)   im2col cross-correlation
    dense(x, w, b)                     fully connected layer
    relu(x), sigmoid(x)                activations
    load_weights(path)                 npz -> dict of ndarrays

Conventions (Keras / channels-last, matching the CREPE weights):
  * conv inputs are (H, W, C_in) or batched (N, H, W, C_in);
    kernels are (kh, kw, C_in, C_out).
  * conv2d computes cross-correlation exactly like Keras Conv2D --
    the kernel is NOT flipped.
  * stride / pad accept an int or an (h, w) pair; pad is symmetric
    zero padding applied to both spatial sides (asymmetric 'same'
    padding, when a model needs it, is the caller's job: pad the
    array first, then call with pad=0).
  * Everything is float32 in, float32 out.

Security contract: load_weights uses numpy.load(...,
allow_pickle=False) ONLY. Weights files are data, never code.
"""
import numpy as np

__all__ = ["conv2d", "dense", "relu", "sigmoid", "load_weights"]


def _pair(value, name):
    if isinstance(value, (tuple, list)):
        if len(value) != 2:
            raise ValueError("%s must be an int or a pair" % name)
        return int(value[0]), int(value[1])
    return int(value), int(value)


def conv2d(x, w, b, stride=1, pad=0):
    """2D cross-correlation (Keras Conv2D semantics), im2col + matmul.

    x: (H, W, C_in) or (N, H, W, C_in); w: (kh, kw, C_in, C_out);
    b: (C_out,). Returns float32 with the same rank as the input.
    """
    x = np.asarray(x, dtype=np.float32)
    w = np.asarray(w, dtype=np.float32)
    b = np.asarray(b, dtype=np.float32)
    single = x.ndim == 3
    if single:
        x = x[np.newaxis, :, :, :]
    if x.ndim != 4:
        raise ValueError("conv2d: x must be (H, W, C) or (N, H, W, C)")
    if w.ndim != 4:
        raise ValueError("conv2d: w must be (kh, kw, C_in, C_out)")
    n, h, width, c_in = x.shape
    kh, kw, w_c_in, c_out = w.shape
    if w_c_in != c_in:
        raise ValueError(
            "conv2d: channel mismatch, x has %d, w expects %d" % (c_in, w_c_in)
        )
    if b.shape != (c_out,):
        raise ValueError("conv2d: b must have shape (%d,)" % c_out)
    sh, sw = _pair(stride, "stride")
    ph, pw = _pair(pad, "pad")
    if ph or pw:
        x = np.pad(x, ((0, 0), (ph, ph), (pw, pw), (0, 0)), mode="constant")
        h, width = x.shape[1], x.shape[2]
    h_out = (h - kh) // sh + 1
    w_out = (width - kw) // sw + 1
    if h_out <= 0 or w_out <= 0:
        raise ValueError("conv2d: kernel larger than (padded) input")

    # im2col: a strided view of every kernel window, materialised by the
    # reshape into one (n * h_out * w_out, kh * kw * c_in) matrix so the
    # whole convolution becomes a single BLAS matmul.
    sn, shx, swx, scx = x.strides
    windows = np.lib.stride_tricks.as_strided(
        x,
        shape=(n, h_out, w_out, kh, kw, c_in),
        strides=(sn, sh * shx, sw * swx, shx, swx, scx),
    )
    cols = windows.reshape(n * h_out * w_out, kh * kw * c_in)
    out = cols @ w.reshape(kh * kw * c_in, c_out) + b
    out = out.reshape(n, h_out, w_out, c_out).astype(np.float32)
    return out[0] if single else out


def dense(x, w, b):
    """Fully connected layer: y = x @ w + b along the last axis."""
    x = np.asarray(x, dtype=np.float32)
    w = np.asarray(w, dtype=np.float32)
    b = np.asarray(b, dtype=np.float32)
    if w.ndim != 2:
        raise ValueError("dense: w must be (in, out)")
    if x.shape[-1] != w.shape[0]:
        raise ValueError(
            "dense: channel mismatch, x has %d, w expects %d"
            % (x.shape[-1], w.shape[0])
        )
    if b.shape != (w.shape[1],):
        raise ValueError("dense: b must have shape (%d,)" % w.shape[1])
    return (x @ w + b).astype(np.float32)


def relu(x):
    """Rectified linear unit: max(x, 0), float32."""
    x = np.asarray(x, dtype=np.float32)
    return np.maximum(x, np.float32(0.0)).astype(np.float32)


def sigmoid(x):
    """Numerically stable logistic sigmoid, float32, values in (0, 1)."""
    x = np.asarray(x, dtype=np.float32)
    z = np.exp(-np.abs(x))
    out = np.where(x >= 0, 1.0 / (1.0 + z), z / (1.0 + z))
    return out.astype(np.float32)


def load_weights(path):
    """Load an .npz weights file into a dict of ndarrays.

    Uses allow_pickle=False ONLY (contract / security gate): a weights
    file that needs pickle to load is rejected, never executed.
    """
    with np.load(path, allow_pickle=False) as data:
        return {key: np.array(data[key]) for key in data.files}
