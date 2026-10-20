"""Tests for duration-preserving pitch shift (audiofx.pitch).

Includes the transient-preservation regression: a phase-vocoder
version smeared attacks (click concentration 0.65); WSOLA must
keep onsets sharp and on time.
"""
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


class TestTransientPreservation(unittest.TestCase):
    """Clicks must stay sharp and at the same times after a shift."""

    def _clicks(self, sr=44100):
        n = sr * 4
        t = np.arange(n) / sr
        x = 0.05 * np.sin(2 * np.pi * 220 * t)
        for ct in (0.5, 1.5, 2.5, 3.5):
            s = int(ct * sr)
            env = np.hanning(int(0.03 * sr))
            seg = 0.8 * np.sin(2 * np.pi * 200
                               * np.arange(len(env)) / sr) * env
            x[s:s + len(env)] += seg
        return x, sr

    def test_clicks_stay_sharp_and_on_time(self):
        x, sr = self._clicks()
        y = np.asarray(pitch.pitch_shift(x, sr, 4.0))
        self.assertEqual(len(y), len(x))
        for ct in (0.5, 1.5, 2.5, 3.5):
            c = int(ct * sr)
            near = float((y[c - 882:c + 882] ** 2).sum())
            wide = float((y[c - 5292:c + 5292] ** 2).sum())
            self.assertGreater(near / wide, 0.80)
            # The envelope peak within +-60 ms sits at the click.
            seg = np.abs(y[c - 2646:c + 2646])
            peak_at = (int(np.argmax(seg)) - 2646) / sr
            self.assertLess(abs(peak_at), 0.03)


if __name__ == "__main__":
    unittest.main(verbosity=2)
