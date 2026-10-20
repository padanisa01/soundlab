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
        out = list(repair.fade_ends(sig, SR, fade_sec=0.02))
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
        self.assertEqual(list(repair.declip(sig)), sig)

    def _noisy_recording(self, seconds=4.0, drone=False):
        """Sparse transients over a dirty floor. Beats are dense and
        long-tailed enough that the loud frames are genuinely content
        (so the gap guard's depth check passes)."""
        random.seed(7)
        n = int(seconds * SR)
        sig = [0.0] * n
        t = 0.25
        while t < seconds:
            s0 = int(t * SR)
            dec = int(0.15 * SR)
            for i in range(dec):
                env = math.exp(-i / (0.03 * SR))
                sig[s0 + i] += 0.7 * env * random.gauss(0, 1.0)
            t += 0.5
        if drone:
            f0 = 69.3  # C#2, a plausible tanpura tonic
            for h in range(1, 9):
                amp = 0.06 / math.sqrt(h)
                for i in range(n):
                    tt = i / SR
                    pluck = 0.8 + 0.2 * math.sin(2 * math.pi * 0.3 * tt)
                    sig[i] += amp * pluck * math.sin(
                        2 * math.pi * f0 * h * i / SR)
        hum = [0.0] * n
        for h in (1, 2, 3, 4, 5, 6, 7):  # 38 Hz motor comb + harmonics
            f = 38.0 * h
            for i in range(n):
                hum[i] += (0.03 / h) * math.sin(2 * math.pi * f * i / SR)
        noise = [random.gauss(0, 0.015) for _ in range(n)]
        return [a + b + c for a, b, c in zip(sig, hum, noise)]

    def test_denoise_reduces_hiss(self):
        sig = self._noisy_recording()
        out = repair.denoise(sig, SR, strength=0.6)
        rms = lambda x: math.sqrt(sum(v * v for v in x) / len(x))
        gap = lambda x: x[int(1.45 * SR) : int(1.7 * SR)]  # between beats
        self.assertLess(rms(gap(out)), rms(gap(sig)) * 0.8)

    def test_denoise_bypasses_without_noise_gap(self):
        # A uniformly loud signal has no pure-noise gap: the op must be
        # a bit-identical bypass rather than profiling the content.
        sig = sine(440, 3.0, 0.5)
        out = repair.denoise(sig, SR, strength=0.6)
        self.assertEqual(list(out), sig)

    def test_denoise_reduces_hum_in_gaps(self):
        sig = self._noisy_recording()
        out = repair.denoise(sig, SR, strength=0.6)
        # The hum tooth at 114 Hz is knocked down.
        self.assertLess(band_energy_db(out, 108, 120),
                        band_energy_db(sig, 108, 120) - 3.0)
        # Beat peaks survive: the loudest transient barely moves.
        self.assertAlmostEqual(max(abs(v) for v in out),
                               max(abs(v) for v in sig), delta=0.12)

    def test_denoise_preserves_tanpura_drone(self):
        """A tanpura-style drone (chromatic harmonic series, pluck-like
        amplitude cycling) sitting in the recording's gaps must survive
        denoise, while the hum comb is still reduced."""
        f0 = 69.3
        sig = self._noisy_recording(drone=True)
        out = repair.denoise(sig, SR, strength=0.6)
        # Drone fundamental and harmonics preserved (whole-signal band
        # energies, so pluck cycling averages out).
        for f, lo, hi in ((f0, 64, 75), (2 * f0, 134, 144),
                          (3 * f0, 203, 213)):
            before = band_energy_db(sig, lo, hi)
            after = band_energy_db(out, lo, hi)
            self.assertLess(abs(after - before), 3.0,
                            "drone at %.1f Hz changed %.1f dB"
                            % (f, after - before))
        # ... and the hiss band still goes down inside a pure gap
        # (proof the op processed rather than bypassing; the hum tooth
        # at 114 Hz shares an FFT bin with drone-harmonic leakage at
        # this resolution, so tooth removal is only asserted in the
        # drone-free test).
        gap = lambda x: x[int(1.45 * SR) : int(1.7 * SR)]
        self.assertLess(band_energy_db(gap(out), 4000, 6000),
                        band_energy_db(gap(sig), 4000, 6000) - 3.0)

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

    def test_moving_avg_preserves_length(self):
        import numpy as np
        for n, win in ((100, 1), (100, 7), (1000, 441), (10, 50)):
            x = np.arange(n, dtype=float)
            self.assertEqual(repair._moving_avg(x, win).shape, (n,))

    def test_deplosive_no_plosive_no_crash(self):
        # Deplosive's internal detector may find nothing even when called;
        # the mask must still match the signal length (was: off-by-one
        # broadcast crash).
        sig = sine(440, 1.0, 0.4)
        out = repair.deplosive(sig, SR)
        self.assertEqual(len(out), len(sig))


if __name__ == "__main__":
    unittest.main()
