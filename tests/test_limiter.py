"""Unit tests for audiofx.limiter. Run: python3 tools/verify.py"""

import math
import os
import sys
import unittest

sys.path.insert(
    0, os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "python")
)

from audiofx.limiter import Limiter


SR = 48000


def sine(freq_hz, seconds, amplitude=0.8):
    return [
        amplitude * math.sin(2.0 * math.pi * freq_hz * i / SR)
        for i in range(int(seconds * SR))
    ]


class LimiterTest(unittest.TestCase):
    def test_hot_signal_never_exceeds_ceiling(self):
        # 1.5x full-scale sine: every peak would clip without limiting.
        lim = Limiter(ceiling_db=-1.0)
        out = lim.process(sine(1000, 1.0, amplitude=1.5), SR)
        ceiling = 10.0 ** (-1.0 / 20.0)
        self.assertLessEqual(max(abs(v) for v in out), ceiling * (1.0 + 1e-6))

    def test_impulse_caught_by_lookahead(self):
        # A single full-scale-exceeding impulse must not overshoot either.
        sig = [0.0] * SR + [2.0] + [0.0] * SR
        lim = Limiter(ceiling_db=-1.0)
        out = lim.process(sig, SR)
        ceiling = 10.0 ** (-1.0 / 20.0)
        self.assertLessEqual(max(abs(v) for v in out), ceiling * (1.0 + 1e-6))

    def test_quiet_signal_is_transparent(self):
        # Below the ceiling the limiter must be bit-transparent apart from the
        # lookahead delay: out[L:] == in[:-L].
        lim = Limiter(ceiling_db=-1.0)
        sig = sine(440, 1.0, amplitude=0.5)
        out = lim.process(sig, SR)
        L = lim.lookahead_samples(SR)
        for a, b in zip(out[L:], sig[:-L]):
            self.assertAlmostEqual(a, b, places=9)

    def test_ceiling_param_is_honored(self):
        lim = Limiter(ceiling_db=-6.0)
        out = lim.process(sine(1000, 1.0, amplitude=1.5), SR)
        ceiling = 10.0 ** (-6.0 / 20.0)
        self.assertLessEqual(max(abs(v) for v in out), ceiling * (1.0 + 1e-6))

    def test_output_length_matches_input(self):
        lim = Limiter()
        sig = sine(440, 0.1, amplitude=0.9)
        out = lim.process(sig, SR)
        self.assertEqual(len(out), len(sig))

    def test_does_not_mutate_input(self):
        lim = Limiter()
        sig = sine(1000, 0.5, amplitude=1.5)
        snapshot = list(sig)
        lim.process(sig, SR)
        self.assertEqual(sig, snapshot)


if __name__ == "__main__":
    unittest.main()
