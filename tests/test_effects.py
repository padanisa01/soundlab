"""Unit tests for audiofx.effects. Run: python3 tools/verify.py"""

import math
import os
import random
import sys
import unittest

sys.path.insert(
    0, os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "python")
)

import audiofx.effects as effects
from audiofx.effects import Echo, Reverb, Warmth


def sine(freq_hz, seconds, sample_rate, amplitude=0.8):
    return [
        amplitude * math.sin(2.0 * math.pi * freq_hz * i / sample_rate)
        for i in range(int(seconds * sample_rate))
    ]


def impulse(length, at=0, amplitude=1.0):
    sig = [0.0] * length
    sig[at] = amplitude
    return sig


def energy(sig):
    return sum(s * s for s in sig)


class TestWarmth(unittest.TestCase):
    SR = 8000

    def test_bypass_is_identity(self):
        sig = sine(440, 0.2, self.SR)
        self.assertEqual(Warmth(0.0).process(sig, self.SR), sig)

    def test_output_bounded(self):
        sig = sine(110, 0.5, self.SR, amplitude=1.0)
        out = Warmth(1.0).process(sig, self.SR)
        self.assertTrue(all(abs(s) <= 1.0 for s in out), "warmth must not clip")

    def test_slider_is_monotonic(self):
        sig = sine(220, 0.5, self.SR)
        diffs = []
        for amount in (0.25, 0.5, 0.75, 1.0):
            out = Warmth(amount).process(sig, self.SR)
            diffs.append(energy([o - s for o, s in zip(out, sig)]))
        for lo, hi in zip(diffs, diffs[1:]):
            self.assertGreater(hi, lo)

    def test_amount_clamped(self):
        self.assertEqual(Warmth(5.0).amount, 1.0)
        self.assertEqual(Warmth(-2.0).amount, 0.0)

    def test_adds_low_end_body(self):
        # Warmth should raise energy vs. the dry signal's lowpassed part only
        # when driven; simply assert it changes the signal audibly.
        sig = sine(90, 0.5, self.SR)
        out = Warmth(1.0).process(sig, self.SR)
        self.assertGreater(energy([o - s for o, s in zip(out, sig)]), 0.0)


class TestReverb(unittest.TestCase):
    SR = 8000

    def test_preset_names(self):
        self.assertEqual(
            set(Reverb.PRESETS), {"studio", "concert_hall", "bathroom", "cave"}
        )

    def test_unknown_preset_raises(self):
        with self.assertRaises(ValueError):
            Reverb("cathedral")

    def test_output_longer_than_input(self):
        sig = impulse(800)
        out = Reverb("studio", ir_seconds=0.05).process(sig, self.SR)
        self.assertGreater(len(out), len(sig))
        self.assertEqual(len(out), len(sig) + len(Reverb("studio", ir_seconds=0.05)._build_ir(self.SR)) - 1)

    def test_tail_decays(self):
        sig = impulse(400)
        out = Reverb("concert_hall", ir_seconds=0.2).process(sig, self.SR)
        tail = out[len(sig):]
        first = max(abs(s) for s in tail[: len(tail) // 4])
        last = max(abs(s) for s in tail[3 * len(tail) // 4 :])
        self.assertGreater(first, last)

    def test_deterministic_with_seed(self):
        sig = sine(440, 0.2, self.SR)
        a = Reverb("bathroom", seed=7, ir_seconds=0.05).process(sig, self.SR)
        b = Reverb("bathroom", seed=7, ir_seconds=0.05).process(sig, self.SR)
        self.assertEqual(a, b)

    def test_cave_ir_longer_than_studio(self):
        cave = len(Reverb("cave")._build_ir(self.SR))
        studio = len(Reverb("studio")._build_ir(self.SR))
        self.assertGreater(cave, studio)

    def test_predelay_shifts_onset(self):
        # With predelay, the impulse response starts with zeros.
        ir = Reverb("cave")._build_ir(self.SR)
        predelay = int(40.0 * self.SR / 1000.0)
        self.assertTrue(all(s == 0.0 for s in ir[:predelay]))
        self.assertTrue(any(s != 0.0 for s in ir[predelay : predelay + 50]))

    def test_numpy_and_pure_python_agree(self):
        sig = sine(330, 0.1, self.SR)
        with_numpy = Reverb("studio", ir_seconds=0.03).process(sig, self.SR)
        old = effects._HAVE_NUMPY
        effects._HAVE_NUMPY = False
        try:
            pure = Reverb("studio", ir_seconds=0.03).process(sig, self.SR)
        finally:
            effects._HAVE_NUMPY = old
        self.assertEqual(len(with_numpy), len(pure))
        for a, b in zip(with_numpy, pure):
            self.assertAlmostEqual(a, b, places=9)


class TestEcho(unittest.TestCase):
    SR = 8000

    def test_bypass_is_identity(self):
        sig = sine(440, 0.2, self.SR)
        self.assertEqual(Echo(0.0).process(sig, self.SR), sig)

    def test_impulse_echo_timing_and_level(self):
        amount, delay_ms = 0.6, 100.0
        delay = int(delay_ms * self.SR / 1000)
        out = Echo(amount, delay_ms=delay_ms).process(impulse(4 * delay), self.SR)
        self.assertAlmostEqual(out[0], 1.0)
        self.assertAlmostEqual(out[delay], amount, places=9)
        self.assertAlmostEqual(out[2 * delay], amount * 0.65 * amount, places=9)

    def test_output_bounded_and_stable(self):
        # Feedback < 1 is stable, but resonant buildup can legitimately reach
        # ~1 + 1/(1 - 0.65) of the input at max amount; it must not diverge.
        sig = sine(220, 1.0, self.SR, amplitude=1.0)
        out = Echo(1.0, delay_ms=50.0).process(sig, self.SR)
        tail = out[len(out) * 3 // 4 :]
        self.assertTrue(all(abs(s) < 4.0 for s in out))
        self.assertLess(max(abs(s) for s in tail), 4.0)

    def test_amount_clamped(self):
        self.assertEqual(Echo(9.0).amount, 1.0)
        self.assertEqual(Echo(-1.0).amount, 0.0)


class TestConvolve(unittest.TestCase):
    def test_fft_matches_direct(self):
        rng = random.Random(42)
        x = [rng.uniform(-1, 1) for _ in range(300)]
        h = [rng.uniform(-1, 1) for _ in range(37)]
        if not effects._HAVE_NUMPY:
            self.skipTest("numpy not installed")
        got = effects._fft_convolve(x, h)
        want = effects._direct_convolve(x, h)
        self.assertEqual(len(got), len(want))
        for a, b in zip(got, want):
            self.assertAlmostEqual(a, b, places=9)


if __name__ == "__main__":
    unittest.main()
