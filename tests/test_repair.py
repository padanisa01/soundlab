"""Unit tests for audiofx.repair. Run: python3 tools/verify.py"""

import math
import os
import random
import sys
import unittest

sys.path.insert(
    0, os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "python")
)

from audiofx import repair


SR = 48000


def sine(freq_hz, seconds, amplitude=0.8):
    return [
        amplitude * math.sin(2.0 * math.pi * freq_hz * i / SR)
        for i in range(int(seconds * SR))
    ]


def band_energy_db(sig, lo_hz, hi_hz):
    import numpy as np
    x = np.asarray(sig)
    spec = np.abs(np.fft.rfft(x))
    freqs = np.fft.rfftfreq(x.size, 1.0 / SR)
    sel = (freqs >= lo_hz) & (freqs <= hi_hz)
    e = np.sum(spec[sel] ** 2)
    return 10 * math.log10(e + 1e-18)


class RepairTest(unittest.TestCase):
    def test_dc_remove(self):
        sig = [v + 0.05 for v in sine(440, 0.5, 0.5)]
        out = repair.dc_remove(sig)
        self.assertAlmostEqual(sum(out) / len(out), 0.0, places=9)
        self.assertEqual(len(out), len(sig))

    def test_fade_ends(self):
        # DC signal: the ramp itself must be monotonic.
        sig = [0.8] * int(0.5 * SR)
        out = repair.fade_ends(sig, SR, fade_sec=0.02)
        fade_n = int(0.02 * SR)
        self.assertLess(abs(out[0]), 0.01)
        self.assertLess(abs(out[-1]), 0.01)
        head = out[:fade_n]
        self.assertEqual(head, sorted(head))
        tail = [-v for v in out[-fade_n:]]
        self.assertEqual(tail, sorted(tail))
        self.assertEqual(len(out), len(sig))

    def test_silence_trim(self):
        sig = sine(440, 1.0, 0.5) + [0.0] * (2 * SR) + sine(440, 1.0, 0.5)
        out = repair.silence_trim(sig, SR)
        # 2 s of silence mostly removed (keeps 0.15 s each side).
        self.assertLess(len(out), len(sig) - int(1.0 * SR))
        # No clicks at the splice: bounded sample-to-sample jump.
        jumps = [abs(b - a) for a, b in zip(out, out[1:])]
        self.assertLess(max(jumps), 0.2)

    def test_hum_notch(self):
        sig = [a + b for a, b in zip(sine(440, 1.0, 0.4), sine(60, 1.0, 0.15))]
        before = band_energy_db(sig, 55, 65)
        out = repair.hum_notch(sig, SR, 60.0)
        after = band_energy_db(out, 55, 65)
        self.assertLess(after, before - 15.0)
        # The 440 Hz tone survives.
        self.assertGreater(band_energy_db(out, 435, 445),
                           band_energy_db(sig, 435, 445) - 3.0)

    def test_declip_reduces_clipped_samples(self):
        # A declipper rebuilds peaks (which may exceed threshold again);
        # the win is removing the hard flat-top signature: no sample
        # pinned EXACTLY at +/-1.0, and the harsh high harmonics tamed.
        import numpy as np
        sig = [max(-1.0, min(1.0, v)) for v in sine(1000, 0.5, 1.5)]
        self.assertGreater(sum(1 for v in sig if abs(v) == 1.0), 0)
        out = repair.declip(sig)
        self.assertEqual(sum(1 for v in out if abs(v) == 1.0), 0)

        def hf(x):
            X = np.abs(np.fft.rfft(np.asarray(x)))
            f = np.fft.rfftfreq(len(x), 1.0 / SR)
            sel = (f >= 8000) & (f <= 20000)
            return 10 * math.log10(np.sum(X[sel] ** 2) + 1e-18)

        self.assertLess(hf(out), hf(sig) - 6.0)
        self.assertEqual(len(out), len(sig))

    def test_declip_leaves_clean_audio_alone(self):
        sig = sine(440, 0.5, 0.5)
        self.assertEqual(repair.declip(sig), sig)

    def test_denoise_reduces_hiss(self):
        random.seed(3)
        tone = sine(440, 1.0, 0.5)
        noise = [random.gauss(0, 0.05) for _ in tone]
        sig = [a + b for a, b in zip(tone, noise)]
        out = repair.denoise(sig, SR, strength=0.6)
        # Noise-only tail: first 0.1 s is tone+noise; use a noise-only probe.
        probe = [random.gauss(0, 0.05) for _ in range(int(0.5 * SR))]
        out_probe = repair.denoise(probe, SR, strength=0.6)
        rms = lambda x: math.sqrt(sum(v * v for v in x) / len(x))
        self.assertLess(rms(out_probe), rms(probe) * 0.7)
        # Tone still dominant at 440 Hz.
        self.assertGreater(band_energy_db(out, 435, 445), -20.0)

    def test_deplosive(self):
        sig = sine(440, 1.0, 0.3)
        start = int(0.5 * SR)
        for i in range(int(0.1 * SR)):
            sig[start + i] += 0.8 * math.sin(2 * math.pi * 90 * i / SR)
        out = repair.deplosive(sig, SR)
        burst = out[start:start + int(0.1 * SR)]
        before_burst = sig[start:start + int(0.1 * SR)]
        rms = lambda x: math.sqrt(sum(v * v for v in x) / len(x))
        self.assertLess(rms(burst), rms(before_burst) * 0.8)
        # Untouched region far from the burst is preserved.
        clean = out[: int(0.2 * SR)]
        ref = sig[: int(0.2 * SR)]
        self.assertLess(max(abs(a - b) for a, b in zip(clean, ref)), 0.05)

    def test_level_evens_loud_quiet(self):
        sig = sine(440, 1.0, 0.15) + sine(440, 1.0, 0.7)
        out = repair.level(sig, SR)
        def win_rms(x, n):
            return [math.sqrt(sum(v * v for v in x[i:i + n]) / n)
                    for i in range(0, len(x), n)]
        w = int(0.5 * SR)
        before = win_rms(sig, w)
        after = win_rms(out, w)
        spread = lambda r: max(r) / (min(r) + 1e-9)
        self.assertLess(spread(after), spread(before))

    def test_loudness_normalize(self):
        sig = sine(440, 2.0, 0.05)
        out = repair.loudness_normalize(sig, SR, target_lufs=-16.0)
        lufs = repair.measure_lufs(out, SR)
        self.assertAlmostEqual(lufs, -16.0, delta=2.0)
        self.assertLessEqual(max(abs(v) for v in out),
                             10.0 ** (-1.0 / 20.0) * 1.001)

    def test_eq_balance_clamps_gains(self):
        sig = sine(440, 0.5, 0.5)
        out = repair.eq_balance(sig, SR, low_gain_db=-10.0, high_gain_db=10.0)
        # Gains clamp to +/-3 dB: output stays within a sane range of input.
        rms_in = math.sqrt(sum(v * v for v in sig) / len(sig))
        rms_out = math.sqrt(sum(v * v for v in out) / len(out))
        self.assertLess(abs(20 * math.log10(rms_out / rms_in)), 4.0)

    def test_ops_do_not_mutate_input(self):
        sig = sine(440, 0.5, 0.5)
        snapshot = list(sig)
        for fn in (repair.dc_remove, repair.declip):
            fn(sig)
        repair.fade_ends(sig, SR)
        repair.level(sig, SR)
        self.assertEqual(sig, snapshot)


if __name__ == "__main__":
    unittest.main()
