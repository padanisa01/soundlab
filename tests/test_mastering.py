"""Unit tests for audiofx.mastering (DSP-2: multiband compression +
dynamic EQ, the ``mastering`` render op).

Acceptance (PRD F2): unity settings are bit-transparent within
+/-1 LSB after 16-bit quantization (exact crossover recombination);
a two-level signal gets >= 3 dB of crest/dynamic-range reduction in
the driven band with < 0.5 dB change in an untouched band; the
dynamic EQ pulls >= 4 dB only during a 6.5 kHz burst; silence yields
gain 1.0 and no NaNs; the op round-trips through render_chain
identically.
"""

import json
import math
import os
import sys
import tempfile
import unittest

sys.path.insert(
    0, os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "python")
)

import numpy as np

from audiofx import android_bridge, mastering
from audiofx.pipeline import read_wav_mono, write_wav

SR = 44100
LSB = 1.0 / 32768.0


def sine(freq_hz, seconds, amplitude=0.5, sr=SR):
    n = int(seconds * sr)
    t = np.arange(n, dtype=np.float64) / sr
    return amplitude * np.sin(2.0 * np.pi * freq_hz * t)


def band_of(x, lo_hz, hi_hz, sr=SR):
    """Brick-wall FFT band extraction (test measurement only)."""
    X = np.fft.rfft(np.asarray(x, dtype=np.float64))
    freqs = np.fft.rfftfreq(len(x), 1.0 / sr)
    X[(freqs < lo_hz) | (freqs >= hi_hz)] = 0.0
    return np.fft.irfft(X, len(x))


def rms(x):
    x = np.asarray(x, dtype=np.float64)
    return float(np.sqrt(np.mean(x ** 2))) if x.size else 0.0


def db(v):
    return 20.0 * math.log10(max(v, 1e-12))


def tone_amplitude(x, freq_hz, sr=SR):
    """Amplitude of a pure tone in a segment, by correlation."""
    x = np.asarray(x, dtype=np.float64)
    t = np.arange(x.size, dtype=np.float64) / sr
    ref = np.exp(-2j * np.pi * freq_hz * t)
    return float(2.0 * abs(np.mean(x * ref)))


def unity_bands():
    return [{"thr_db": 0.0, "ratio": 4.0, "attack_ms": 10.0,
             "release_ms": 100.0} for _ in range(4)]


def off_dyn_eq():
    return [{"freq_hz": f, "max_db": 0.0, "thr_db": -20.0}
            for f in (300.0, 3000.0, 6500.0)]


def quantize16(x):
    return np.round(np.asarray(x, dtype=np.float64) * 32767.0)


class TestUnityTransparency(unittest.TestCase):
    def _signal(self):
        return (sine(100.0, 1.0, 0.30) + sine(800.0, 1.0, 0.20)
                + sine(3000.0, 1.0, 0.15) + sine(9000.0, 1.0, 0.10))

    def test_unity_transparency(self):
        sig = self._signal()
        out = mastering.master(sig, SR, unity_bands(), off_dyn_eq())
        self.assertEqual(len(out), len(sig))
        diff = np.max(np.abs(quantize16(out) - quantize16(sig)))
        self.assertLessEqual(diff, 1.0)  # within +/-1 LSB

    def test_crossover_recombination(self):
        # Ratio 1.0 can never change gain, even with the envelope far
        # over threshold: what remains is pure crossover split + sum,
        # which must recombine exactly.
        sig = self._signal() * 1.2  # peaks near full scale
        bands = [{"thr_db": -40.0, "ratio": 1.0, "attack_ms": 1.0,
                  "release_ms": 50.0} for _ in range(4)]
        out = mastering.master(sig, SR, bands, off_dyn_eq())
        self.assertEqual(len(out), len(sig))
        diff = np.max(np.abs(quantize16(out) - quantize16(sig)))
        self.assertLessEqual(diff, 1.0)


class TestBandIsolation(unittest.TestCase):
    def test_two_level_signal(self):
        n = SR  # 1 s
        t = np.arange(n, dtype=np.float64) / SR
        # Low band (60 Hz): quiet first half, loud second half, with a
        # 20 ms amplitude ramp at the junction (no click splatter).
        amp = np.full(n, 0.05)
        amp[n // 2:] = 0.8
        ramp = int(0.020 * SR)
        amp[n // 2 - ramp // 2: n // 2 + ramp // 2] = np.linspace(
            0.05, 0.8, ramp)
        low = amp * np.sin(2.0 * np.pi * 60.0 * t)
        high = 0.1 * np.sin(2.0 * np.pi * 3000.0 * t)
        sig = low + high
        bands = unity_bands()
        bands[0] = {"thr_db": -20.0, "ratio": 10.0, "attack_ms": 1.0,
                    "release_ms": 100.0}
        out = mastering.master(sig, SR, bands, off_dyn_eq())

        # Driven band: loud/quiet level ratio (its crest over the two
        # levels) must fall by >= 3 dB.
        in_low = band_of(sig, 0.0, 120.0)
        out_low = band_of(out, 0.0, 120.0)
        edge = int(0.05 * SR)
        in_ratio = db(rms(in_low[n // 2 + edge:]) / rms(in_low[:n // 2 - edge]))
        out_ratio = db(rms(out_low[n // 2 + edge:])
                       / rms(out_low[:n // 2 - edge]))
        self.assertGreaterEqual(in_ratio - out_ratio, 3.0)

        # Untouched band (3 kHz, high_mid): < 0.5 dB change overall.
        in_hi = band_of(sig, 1000.0, 6000.0)
        out_hi = band_of(out, 1000.0, 6000.0)
        self.assertLess(abs(db(rms(out_hi) / rms(in_hi))), 0.5)


class TestDynamicEq(unittest.TestCase):
    def test_burst_pull_only_during_burst(self):
        n = int(1.2 * SR)
        t = np.arange(n, dtype=np.float64) / SR
        amp = np.full(n, 0.03)
        b0, b1 = int(0.4 * SR), int(0.8 * SR)
        amp[b0:b1] = 0.7
        ramp = int(0.010 * SR) & ~1  # even, so the centered slice matches
        amp[b0 - ramp // 2: b0 + ramp // 2] = np.linspace(0.03, 0.7, ramp)
        amp[b1 - ramp // 2: b1 + ramp // 2] = np.linspace(0.7, 0.03, ramp)
        sig = amp * np.sin(2.0 * np.pi * 6500.0 * t) \
            + 0.1 * np.sin(2.0 * np.pi * 200.0 * t)
        dyn = off_dyn_eq()
        dyn[2] = {"freq_hz": 6500.0, "max_db": -6.0, "thr_db": -20.0}
        out = mastering.master(sig, SR, unity_bands(), dyn)

        burst_in = sig[int(0.5 * SR): int(0.7 * SR)]
        burst_out = out[int(0.5 * SR): int(0.7 * SR)]
        pull = db(tone_amplitude(burst_in, 6500.0)
                  / tone_amplitude(burst_out, 6500.0))
        self.assertGreaterEqual(pull, 4.0)

        quiet_in = sig[int(0.05 * SR): int(0.25 * SR)]
        quiet_out = out[int(0.05 * SR): int(0.25 * SR)]
        change = abs(db(tone_amplitude(quiet_out, 6500.0)
                        / tone_amplitude(quiet_in, 6500.0)))
        self.assertLess(change, 1.0)


class TestSilence(unittest.TestCase):
    def test_silence_no_nans_gain_unity(self):
        sig = np.zeros(SR // 2)
        bands, dyn = mastering.preset_params("gentle")
        out = mastering.master(sig, SR, bands, dyn)
        self.assertEqual(len(out), len(sig))
        self.assertTrue(np.all(np.isfinite(out)))
        self.assertTrue(np.all(out == 0.0))


class TestPresets(unittest.TestCase):
    def test_preset_shapes_and_order(self):
        for name in ("gentle", "punch", "broadcast"):
            bands, dyn = mastering.preset_params(name)
            self.assertEqual(len(bands), 4)
            self.assertEqual(len(dyn), 3)
            for b in bands:
                for k in ("thr_db", "ratio", "attack_ms", "release_ms"):
                    self.assertIn(k, b)
            self.assertEqual([d["freq_hz"] for d in dyn],
                             [300.0, 3000.0, 6500.0])
            for d in dyn:
                self.assertIn("max_db", d)
                self.assertIn("thr_db", d)

    def test_unknown_preset_raises(self):
        with self.assertRaises(ValueError):
            mastering.preset_params("arena")


class TestMasteringOpWiring(unittest.TestCase):
    def test_render_chain_round_trip_identical_twice(self):
        bands, dyn = mastering.preset_params("gentle")
        ops = [{"op": "mastering",
                "params": {"preset": "gentle", "bands": bands,
                           "dyn_eq": dyn}}]
        with tempfile.TemporaryDirectory() as d:
            src = os.path.join(d, "pristine.wav")
            sig = sine(200.0, 1.0, 0.6) + sine(4000.0, 1.0, 0.3)
            write_wav(src, sig, SR)
            outs = []
            for i in (1, 2):
                dst = os.path.join(d, "out%d.wav" % i)
                res = android_bridge.render_chain(
                    src, json.dumps(ops), dst)
                self.assertTrue(res["ok"])
                samples, sr = read_wav_mono(dst)
                self.assertEqual(sr, SR)
                outs.append(samples)
            self.assertEqual(outs[0], outs[1])  # deterministic render
            orig, _ = read_wav_mono(src)
            # Gentle mastering on this loud signal does change it.
            self.assertTrue(any(a != b for a, b in zip(outs[0], orig)))

    def test_dsp_version_is_2(self):
        self.assertEqual(android_bridge.DSP_VERSION, "2")

    def test_tune_up_never_offers_mastering(self):
        from audiofx import diagnose
        for pid, prof in diagnose.TUNE_UP_PROFILES.items():
            self.assertNotIn("mastering", prof["fixes"])
        # And on a maximally-bad signal's real fixes, for every profile.
        bad = np.clip(sine(440.0, 1.0, 1.4), -1.0, 1.0)
        findings = diagnose.analyze(bad, SR)
        for pid in diagnose.TUNE_UP_PROFILES:
            ops = [f["op"] for f in diagnose.tune_up_fixes(findings, pid)]
            self.assertNotIn("mastering", ops)


if __name__ == "__main__":
    unittest.main()
