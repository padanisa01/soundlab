"""Tests for the minimal NumPy inference helpers (audiofx.nn).

Contract (contracts.md, nn.py section): conv2d(x, w, b, stride=1, pad=0),
dense(x, w, b), relu(x), sigmoid(x) -- pure NumPy, float32 in/out;
load_weights(path) loads with numpy.load(..., allow_pickle=False) ONLY.

Layout conventions (Keras / channels-last, matching CREPE weights):
  x: (H, W, C_in) or batched (N, H, W, C_in)
  w: (kh, kw, C_in, C_out); conv2d computes cross-correlation exactly
     like Keras Conv2D (the kernel is NOT flipped).
All expected values below are hand-computed.
"""
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..",
                                "app", "src", "main", "python"))
import numpy as np

from audiofx import nn

AUDIOFX_DIR = os.path.join(os.path.dirname(__file__), "..",
                           "app", "src", "main", "python", "audiofx")


class TestConv2dHandComputed(unittest.TestCase):

    def test_single_channel_2x2_kernel(self):
        # x = [[1,2,3],[4,5,6],[7,8,9]], kernel [[1,0],[0,1]], bias 0.
        # Cross-correlation: out[i,j] = x[i,j] + x[i+1,j+1]
        #   -> [[1+5, 2+6], [4+8, 5+9]] = [[6,8],[12,14]]
        x = np.arange(1, 10, dtype=np.float32).reshape(3, 3, 1)
        w = np.zeros((2, 2, 1, 1), dtype=np.float32)
        w[0, 0, 0, 0] = 1.0
        w[1, 1, 0, 0] = 1.0
        b = np.zeros(1, dtype=np.float32)
        y = nn.conv2d(x, w, b)
        self.assertEqual(y.shape, (2, 2, 1))
        np.testing.assert_array_equal(
            y[:, :, 0], np.array([[6, 8], [12, 14]], dtype=np.float32))

    def test_bias_and_two_filters(self):
        # 1x1 kernel over 2 channels; filter0 = ch0 + 2*ch1 + bias 10,
        # filter1 = -ch0 + bias 0.
        x = np.zeros((2, 1, 2), dtype=np.float32)
        x[0, 0] = [3.0, 4.0]
        x[1, 0] = [5.0, 6.0]
        w = np.zeros((1, 1, 2, 2), dtype=np.float32)
        w[0, 0, 0, 0] = 1.0
        w[0, 0, 1, 0] = 2.0
        w[0, 0, 0, 1] = -1.0
        b = np.array([10.0, 0.0], dtype=np.float32)
        y = nn.conv2d(x, w, b)
        # pos0: [3+8+10, -3] = [21, -3]; pos1: [5+12+10, -5] = [27, -5]
        np.testing.assert_array_equal(
            y, np.array([[[21.0, -3.0]], [[27.0, -5.0]]], dtype=np.float32))

    def test_stride_2(self):
        # 4x4 input 1..16, 2x2 all-ones kernel, stride 2, bias 0:
        # blocks [[1,2,5,6],[3,4,7,8],[9,10,13,14],[11,12,15,16]]
        # sums = [[14, 22], [46, 54]]
        x = np.arange(1, 17, dtype=np.float32).reshape(4, 4, 1)
        w = np.ones((2, 2, 1, 1), dtype=np.float32)
        b = np.zeros(1, dtype=np.float32)
        y = nn.conv2d(x, w, b, stride=2)
        self.assertEqual(y.shape, (2, 2, 1))
        np.testing.assert_array_equal(
            y[:, :, 0], np.array([[14, 22], [46, 54]], dtype=np.float32))

    def test_symmetric_pad_1(self):
        # 1x1 input [[2]], 3x3 all-ones kernel, pad=1: the single output
        # sees the value once (rest is padding zeros) plus bias 1 -> 3.
        x = np.array([[[2.0]]], dtype=np.float32)
        w = np.ones((3, 3, 1, 1), dtype=np.float32)
        b = np.array([1.0], dtype=np.float32)
        y = nn.conv2d(x, w, b, pad=1)
        self.assertEqual(y.shape, (1, 1, 1))
        self.assertEqual(float(y[0, 0, 0]), 3.0)

    def test_batched_matches_hand_computed(self):
        x1 = np.arange(1, 10, dtype=np.float32).reshape(3, 3, 1)
        x = np.concatenate([x1, x1], axis=0).reshape(2, 3, 3, 1)
        # second frame is 2*x1
        x[1] = 2.0 * x1
        w = np.zeros((2, 2, 1, 1), dtype=np.float32)
        w[0, 0, 0, 0] = 1.0
        w[1, 1, 0, 0] = 1.0
        b = np.zeros(1, dtype=np.float32)
        y = nn.conv2d(x, w, b)
        self.assertEqual(y.shape, (2, 2, 2, 1))
        np.testing.assert_array_equal(
            y[0, :, :, 0], np.array([[6, 8], [12, 14]], dtype=np.float32))
        np.testing.assert_array_equal(
            y[1, :, :, 0], np.array([[12, 16], [24, 28]], dtype=np.float32))

    def test_output_is_float32(self):
        x = np.ones((3, 3, 1), dtype=np.float64)
        w = np.ones((2, 2, 1, 1), dtype=np.float64)
        b = np.zeros(1, dtype=np.float64)
        y = nn.conv2d(x, w, b)
        self.assertEqual(y.dtype, np.float32)


class TestDenseAndActivations(unittest.TestCase):

    def test_dense_hand_computed(self):
        # x=[1,2,3]; w columns: [1,0,-1] and [2,1,0]; b=[10, 20]
        # out = [1 - 3 + 10, 2 + 2 + 20] = [8, 24]
        x = np.array([1.0, 2.0, 3.0], dtype=np.float32)
        w = np.array([[1.0, 2.0], [0.0, 1.0], [-1.0, 0.0]], dtype=np.float32)
        b = np.array([10.0, 20.0], dtype=np.float32)
        y = nn.dense(x, w, b)
        np.testing.assert_array_equal(y, np.array([8.0, 24.0], dtype=np.float32))
        self.assertEqual(y.dtype, np.float32)

    def test_dense_batched_rows(self):
        x = np.array([[1.0, 0.0], [0.0, 1.0]], dtype=np.float32)
        w = np.eye(2, dtype=np.float32)
        b = np.array([5.0, 6.0], dtype=np.float32)
        y = nn.dense(x, w, b)
        np.testing.assert_array_equal(
            y, np.array([[6.0, 6.0], [5.0, 7.0]], dtype=np.float32))

    def test_relu(self):
        x = np.array([-2.0, -0.5, 0.0, 0.5, 3.0], dtype=np.float32)
        y = nn.relu(x)
        np.testing.assert_array_equal(
            y, np.array([0.0, 0.0, 0.0, 0.5, 3.0], dtype=np.float32))
        self.assertEqual(y.dtype, np.float32)

    def test_sigmoid_known_values(self):
        x = np.array([0.0, 2.0, -2.0], dtype=np.float32)
        y = nn.sigmoid(x)
        self.assertAlmostEqual(float(y[0]), 0.5, places=6)
        self.assertAlmostEqual(float(y[1]), 0.880797, places=6)
        self.assertAlmostEqual(float(y[2]), 0.119203, places=6)
        self.assertEqual(y.dtype, np.float32)

    def test_sigmoid_extremes_stay_finite(self):
        y = nn.sigmoid(np.array([500.0, -500.0], dtype=np.float32))
        self.assertTrue(np.all(np.isfinite(y)))
        self.assertAlmostEqual(float(y[0]), 1.0, places=6)
        self.assertAlmostEqual(float(y[1]), 0.0, places=6)


class TestLoadWeights(unittest.TestCase):

    def test_roundtrip_plain_arrays(self):
        want = {
            "a": np.arange(6, dtype=np.float32).reshape(2, 3),
            "b": np.array([1.5, -2.5], dtype=np.float32),
        }
        with tempfile.TemporaryDirectory() as d:
            path = os.path.join(d, "w.npz")
            np.savez(path, **want)
            got = nn.load_weights(path)
        self.assertEqual(sorted(got), ["a", "b"])
        for k in want:
            np.testing.assert_array_equal(got[k], want[k])

    def test_pickled_object_archive_is_rejected(self):
        # An npz holding an object array can only be read with pickle;
        # load_weights must refuse it (allow_pickle=False contract).
        with tempfile.TemporaryDirectory() as d:
            path = os.path.join(d, "evil.npz")
            np.savez(path, obj=np.array([{"x": 1}], dtype=object))
            with self.assertRaises(Exception):
                nn.load_weights(path)

    def test_crepe_tiny_artifact_loads_and_matches_topology(self):
        # The converted artifact is a spike record, not app payload:
        # it lives in tools/spike/ and must NOT ship in the APK
        # (neural engine descoped by the DSP-0 spike NO-GO).
        path = os.path.join(os.path.dirname(__file__), "..",
                            "tools", "spike", "crepe_tiny.npz")
        self.assertTrue(os.path.isfile(path), "crepe_tiny.npz missing")
        w = nn.load_weights(path)
        # CREPE-tiny topology: 6 conv blocks, capacity 4, 360 bins.
        self.assertEqual(w["conv1_kernel"].shape, (512, 1, 1, 128))
        self.assertEqual(w["conv2_kernel"].shape, (64, 1, 128, 16))
        self.assertEqual(w["conv3_kernel"].shape, (64, 1, 16, 16))
        self.assertEqual(w["conv4_kernel"].shape, (64, 1, 16, 16))
        self.assertEqual(w["conv5_kernel"].shape, (64, 1, 16, 32))
        self.assertEqual(w["conv6_kernel"].shape, (64, 1, 32, 64))
        self.assertEqual(w["classifier_kernel"].shape, (256, 360))
        self.assertEqual(w["classifier_bias"].shape, (360,))
        for key, arr in w.items():
            self.assertEqual(arr.dtype, np.float32, key)


if __name__ == "__main__":
    unittest.main(verbosity=2)
