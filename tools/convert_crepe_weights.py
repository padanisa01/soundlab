#!/usr/bin/env python3
"""Convert the official CREPE-tiny Keras weights to crepe_tiny.npz.

Dev-machine only -- this script is NOT shipped logic. It reads the
`model-tiny.h5` weights file from the official CREPE release
(https://github.com/marl/crepe, branch `models`, file
`model-tiny.h5.bz2`; CREPE is MIT-licensed, see that repo's LICENSE)
and writes a plain NumPy archive that `audiofx.nn.load_weights` can
load with ``allow_pickle=False`` (data only, no pickled objects).

Source topology (CREPE core.py, capacity 'tiny' = multiplier 4):
6 conv blocks; block i is Conv2D(filters[i], (widths[i], 1),
strides=strides[i], padding='same', activation='relu') ->
BatchNormalization -> MaxPool2D((2, 1)); then Permute/Flatten ->
Dense(360, sigmoid).  filters = [128, 16, 16, 16, 32, 64],
widths = [512, 64, 64, 64, 64, 64], strides = [(4,1), (1,1) x5].

Output keys (all float32, Keras layouts preserved):
    conv{i}_kernel      (width, 1, in_ch, out_ch)   i = 1..6
    conv{i}_bias        (out_ch,)
    conv{i}_bn_gamma    (out_ch,)
    conv{i}_bn_beta     (out_ch,)
    conv{i}_bn_mean     (out_ch,)   moving mean
    conv{i}_bn_var      (out_ch,)   moving variance
    classifier_kernel   (256, 360)
    classifier_bias     (360,)
    bn_eps              () scalar, Keras BatchNormalization default 1e-3

Usage:
    python3 tools/convert_crepe_weights.py [model-tiny.h5] [out.npz]
Defaults: input model-tiny.h5 in the CWD, output
app/src/main/python/audiofx/crepe_tiny.npz relative to the repo root.

Requires h5py (dev only):  pip install h5py
"""
import os
import sys

import numpy as np

# Expected tensor shapes for CREPE-tiny (capacity multiplier 4).
EXPECTED = {}
_FILTERS = [128, 16, 16, 16, 32, 64]
_WIDTHS = [512, 64, 64, 64, 64, 64]
_IN_CH = [1, 128, 16, 16, 16, 32]
for _i in range(6):
    _n = _i + 1
    EXPECTED["conv%d_kernel" % _n] = (_WIDTHS[_i], 1, _IN_CH[_i], _FILTERS[_i])
    EXPECTED["conv%d_bias" % _n] = (_FILTERS[_i],)
    for _kind in ("bn_gamma", "bn_beta", "bn_mean", "bn_var"):
        EXPECTED["conv%d_%s" % (_n, _kind)] = (_FILTERS[_i],)
EXPECTED["classifier_kernel"] = (256, 360)
EXPECTED["classifier_bias"] = (360,)

# h5 dataset-name suffix -> output key suffix, per layer group.
_CONV_MAP = {"kernel:0": "kernel", "bias:0": "bias"}
_BN_MAP = {
    "gamma:0": "bn_gamma",
    "beta:0": "bn_beta",
    "moving_mean:0": "bn_mean",
    "moving_variance:0": "bn_var",
}


def _collect(group, suffix_map, out, prefix):
    """Copy datasets under `group` whose name ends with a known suffix."""
    found = {}

    def visit(name, obj):
        import h5py

        if isinstance(obj, h5py.Dataset):
            for suffix, key_suffix in suffix_map.items():
                if name.endswith(suffix):
                    found[key_suffix] = np.asarray(obj, dtype=np.float32)

    group.visititems(visit)
    for key_suffix, arr in found.items():
        out["%s_%s" % (prefix, key_suffix)] = arr
    if set(found) != set(suffix_map.values()):
        raise ValueError(
            "layer %s: expected datasets %s, found %s"
            % (prefix, sorted(suffix_map.values()), sorted(found))
        )


def convert(h5_path, out_path):
    try:
        import h5py
    except ImportError:
        raise SystemExit(
            "h5py is required for conversion (dev only): pip install h5py"
        )

    tensors = {}
    with h5py.File(h5_path, "r") as f:
        for i in range(1, 7):
            _collect(f["conv%d" % i], _CONV_MAP, tensors, "conv%d" % i)
            _collect(f["conv%d-BN" % i], _BN_MAP, tensors, "conv%d" % i)
        _collect(f["classifier"], _CONV_MAP, tensors, "classifier")
    # Keras BatchNormalization default epsilon; the h5 weights file does
    # not store it (the layer is built with defaults in crepe/core.py).
    tensors["bn_eps"] = np.asarray(1e-3, dtype=np.float32)

    for key, shape in EXPECTED.items():
        if key not in tensors:
            raise ValueError("missing tensor %s" % key)
        if tensors[key].shape != shape:
            raise ValueError(
                "tensor %s: expected shape %s, got %s"
                % (key, shape, tensors[key].shape)
            )
        if tensors[key].dtype != np.float32:
            raise ValueError("tensor %s: expected float32" % key)

    os.makedirs(os.path.dirname(os.path.abspath(out_path)), exist_ok=True)
    np.savez_compressed(out_path, **tensors)

    # The shipped artifact MUST load without pickle (contract gate).
    with np.load(out_path, allow_pickle=False) as data:
        keys = sorted(data.files)
        for key in keys:
            assert data[key].dtype == np.float32, key
    size = os.path.getsize(out_path)
    print("wrote %s" % out_path)
    print("npz size: %d bytes (%.3f MB)" % (size, size / 1e6))
    print("tensors: %d keys" % len(keys))
    for key in sorted(EXPECTED):
        print("  %-22s %s" % (key, EXPECTED[key]))
    print("  %-22s %s" % ("bn_eps", ()))
    return size


def main(argv):
    repo_root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    h5_path = argv[1] if len(argv) > 1 else "model-tiny.h5"
    out_path = (
        argv[2]
        if len(argv) > 2
        else os.path.join(
            repo_root, "app", "src", "main", "python", "audiofx", "crepe_tiny.npz"
        )
    )
    convert(h5_path, out_path)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
