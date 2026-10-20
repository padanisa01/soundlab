"""Tests for duration-preserving pitch shift (audiofx.pitch)."""
import os
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..",
                                "app", "src", "main", "python"))
import numpy as np

from audiofx import pitch

SR = 22050


def _sine(freq, dur=1.5, amp=0.5):
    t = np.arange(int(dur * SR)) / SR
    return amp * np.sin(2 * np.pi * freq * t)


def _peak_freq(x):
    spec = np.abs(np.fft.rfft(x * np.hanning(len(x))))
    freqs = np.fft.rfftfreq(len(x), 1.0 / SR)
    return float(freqs[int(np.argmax(spec))])


class TestPitchShift(unittest.TestCase):

    def test_octave_up(self):
        y = pitch.pitch_shift(_sine(220.0), SR, 12.0)
        self.assertEqual(len(y), int(1.5 * SR))
        self.assertAlmostEqual(_peak_freq(y), 440.0, delta=6.0)

    def test_octave_down(self):
        y = pitch.pitch_shift(_sine(220.0), SR, -12.0)
        self.assertAlmostEqual(_peak_freq(y), 110.0, delta=4.0)

    def test_fifth_up(self):
        y = pitch.pitch_shift(_sine(220.0), SR, 7.0)
        want = 220.0 * 2 ** (7.0 / 12.0)
        self.assertAlmostEqual(_peak_freq(y), want, delta=6.0)

    def test_zero_shift_identical(self):
        x = _sine(330.0)
        y = pitch.pitch_shift(x, SR, 0.0)
        self.assertTrue(np.array_equal(np.asarray(y), np.asarray(x)))

    def test_range_guard(self):
        with self.assertRaises(ValueError):
            pitch.pitch_shift(_sine(220.0), SR, 13.0)


if __name__ == "__main__":
    unittest.main(verbosity=2)
