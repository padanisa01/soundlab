#!/usr/bin/env python3
"""Convert the verified CREPE-tiny weights (tools/spike/crepe_tiny.npz)
to a float32 TFLite model for the neural-notation TFLite spike.

The Keras model built here mirrors `forward_nn` in
tools/benchmark_crepe.py layer for layer (which itself follows
crepe/core.py build_and_load_model('tiny')):

    per block i = 1..6: Conv2D(filters[i], (widths[i], 1),
    strides=(strides[i], 1), padding='same') -> ReLU ->
    BatchNormalization(eps=1e-3, moving statistics) -> MaxPool2D((2, 1))
    then Permute(2, 1, 3) -> Flatten -> Dense(360, sigmoid)

Weights are loaded FROM THE NPZ -- the exact tensors the NumPy
reference runs -- so the TFLite model cannot drift from the verified
weights. Input is one per-frame-normalised 1024-sample frame at
(1024, 1, 1); normalisation stays outside the model, exactly as in
the nn.py path (crepe.get_activation semantics).

Dev-machine only; requires TensorFlow (pip install tensorflow-cpu).

Usage:
    python3 tools/convert_crepe_tflite.py [in.npz] [out.tflite]
Defaults: tools/spike/crepe_tiny.npz -> tools/spike/crepe_tiny.tflite
"""
import os
import sys

import numpy as np

FILTERS = [128, 16, 16, 16, 32, 64]
WIDTHS = [512, 64, 64, 64, 64, 64]
STRIDES = [4, 1, 1, 1, 1, 1]


def build_model(w):
    import tensorflow as tf

    inp = tf.keras.Input(shape=(1024, 1, 1))
    x = inp
    for i in range(1, 7):
        conv = tf.keras.layers.Conv2D(
            FILTERS[i - 1], (WIDTHS[i - 1], 1),
            strides=(STRIDES[i - 1], 1), padding="same")
        x = conv(x)
        x = tf.keras.layers.Activation("relu")(x)
        bn = tf.keras.layers.BatchNormalization(
            epsilon=float(w["bn_eps"]))
        x = bn(x)
        x = tf.keras.layers.MaxPooling2D((2, 1))(x)
        conv.set_weights([w["conv%d_kernel" % i], w["conv%d_bias" % i]])
        bn.set_weights([w["conv%d_bn_gamma" % i], w["conv%d_bn_beta" % i],
                        w["conv%d_bn_mean" % i], w["conv%d_bn_var" % i]])
    x = tf.keras.layers.Permute((2, 1, 3))(x)
    x = tf.keras.layers.Flatten()(x)
    dense = tf.keras.layers.Dense(360, activation="sigmoid")
    x = dense(x)
    dense.set_weights([w["classifier_kernel"], w["classifier_bias"]])
    return tf.keras.Model(inp, x)


def main(argv):
    repo_root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    npz_path = argv[1] if len(argv) > 1 else os.path.join(
        repo_root, "tools", "spike", "crepe_tiny.npz")
    out_path = argv[2] if len(argv) > 2 else os.path.join(
        repo_root, "tools", "spike", "crepe_tiny.tflite")

    import tensorflow as tf
    print("tensorflow %s" % tf.__version__)

    with np.load(npz_path, allow_pickle=False) as data:
        w = {k: np.array(data[k]) for k in data.files}

    model = build_model(w)
    probe = model(np.zeros((1, 1024, 1, 1), dtype=np.float32)).numpy()
    assert probe.shape == (1, 360), probe.shape
    assert np.all((probe > 0) & (probe < 1))
    print("keras probe OK: output (1, 360), values in (0, 1)")

    converter = tf.lite.TFLiteConverter.from_keras_model(model)
    tflite = converter.convert()
    with open(out_path, "wb") as f:
        f.write(tflite)
    print("wrote %s" % out_path)
    print("tflite size: %d bytes (%.3f MB)" % (len(tflite), len(tflite) / 1e6))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
