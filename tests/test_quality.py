"""Unit tests for audiofx.quality (Tier 1 proxy, DSP-5).

Run: python3 tools/verify.py

The Tier 1 score does NOT re-measure the signal: it maps the
measurements Diagnose already computes (noise floor, ceiling-clip
stats, LUFS, sibilant fraction, level spread, hum) onto SIG/BAK/OVRL
in [1, 5]. These tests therefore drive score() with measurement
dicts, exactly as diagnose/android_bridge will build them.
"""

import json
import math
import os
import sys
import unittest

sys.path.insert(
    0, os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "python")
)

from audiofx import diagnose, quality


SR = 48000


def sine(freq_hz, seconds, amplitude=0.5):
    return [
        amplitude * math.sin(2.0 * math.pi * freq_hz * i / SR)
        for i in range(int(seconds * SR))
    ]


def clean_measurements():
    """Clean studio-like measurements: very low noise floor, no
    clipping, on-target loudness, no sibilance, even level, no hum."""
    return {
        "noise_floor_db": -80.0,
        "ceiling_clip": {
            "peak": 0.5,
            "peak_db": -6.0,
            "ceiling_samples": 1,
            "ceiling_runs": 1,
            "longest_run": 1,
            "flat_samples": 0,
            "flat_runs": 0,
            "is_clipped": False,
        },
        "clip_fraction": 0.0,
        "total_samples": SR,
        "lufs": -16.0,
        "lufs_distance_db": 0.0,
        "sibilant_fraction": 0.0,
        "level_spread_db": 2.0,
        "hum_ratio": 1.0,
    }


class QualityScoreTest(unittest.TestCase):
    def test_clean_anchor_ovrl_at_least_4_5(self):
        result = quality.score(sine(440, 0.25), SR, clean_measurements())
        self.assertIsNotNone(result)
        self.assertEqual(result["engine"], "proxy")
        self.assertGreaterEqual(result["ovrl"], 4.5)
        for key in ("ovrl", "sig", "bak"):
            self.assertGreaterEqual(result[key], 1.0)
            self.assertLessEqual(result[key], 5.0)

    def test_added_noise_lowers_bak_and_ovrl(self):
        # Adding -40 dBFS noise: the noise-floor measurement rises
        # from the clean -80 dBFS to -40 dBFS, everything else held.
        clean = quality.score(sine(440, 0.25), SR, clean_measurements())
        noisy_meas = clean_measurements()
        noisy_meas["noise_floor_db"] = -40.0
        noisy = quality.score(sine(440, 0.25), SR, noisy_meas)
        self.assertLess(noisy["bak"], clean["bak"])
        self.assertLess(noisy["ovrl"], clean["ovrl"])

    def test_hard_clipping_lowers_sig(self):
        clean = quality.score(sine(440, 0.25), SR, clean_measurements())
        clipped_meas = clean_measurements()
        clipped_meas["clip_fraction"] = 0.10
        clipped_meas["ceiling_clip"] = {
            "peak": 1.0,
            "peak_db": 0.0,
            "ceiling_samples": SR // 10,
            "ceiling_runs": 400,
            "longest_run": 12,
            "flat_samples": SR // 10,
            "flat_runs": 400,
            "is_clipped": True,
        }
        clipped = quality.score(sine(440, 0.25), SR, clipped_meas)
        self.assertLess(clipped["sig"], clean["sig"])

    def test_soundcheck_style_measurements_sig_at_most_2_5(self):
        # soundcheck_1 QA profile (2026-10-27): 22.87 s mono 48 kHz,
        # 11,009 samples (1.003%) flat at a -1 dBFS ceiling across
        # 448 runs (443 flat-top runs, 11,004 flat samples reported
        # by the ceiling detector). Measurements built from those
        # known stats; no external file is needed.
        total = int(22.87 * SR)
        meas = clean_measurements()
        meas["total_samples"] = total
        meas["ceiling_clip"] = {
            "peak": 10.0 ** (-1.0 / 20.0),
            "peak_db": -1.0,
            "ceiling_samples": 11009,
            "ceiling_runs": 448,
            "longest_run": 62,
            "flat_samples": 11004,
            "flat_runs": 443,
            "is_clipped": True,
        }
        meas["clip_fraction"] = 11009 / total
        result = quality.score(sine(440, 0.25), SR, meas)
        self.assertIsNotNone(result)
        self.assertLessEqual(result["sig"], 2.5)

    def test_silence_is_none(self):
        self.assertIsNone(quality.score([0.0] * SR, SR,
                                        clean_measurements()))
        self.assertIsNone(quality.score([], SR, clean_measurements()))

    def test_scores_clamped_to_range(self):
        meas = {
            "noise_floor_db": 0.0,
            "ceiling_clip": {"flat_samples": SR, "flat_runs": 100,
                             "ceiling_samples": SR, "is_clipped": True},
            "clip_fraction": 1.0,
            "total_samples": SR,
            "lufs": -60.0,
            "lufs_distance_db": 44.0,
            "sibilant_fraction": 1.0,
            "level_spread_db": 100.0,
            "hum_ratio": 1000.0,
        }
        result = quality.score(sine(440, 0.25), SR, meas)
        self.assertIsNotNone(result)
        for key in ("ovrl", "sig", "bak"):
            self.assertGreaterEqual(result[key], 1.0)
            self.assertLessEqual(result[key], 5.0)

    def test_named_weights_exist(self):
        self.assertTrue(hasattr(quality, "QUALITY_W_SIG"))
        self.assertTrue(hasattr(quality, "QUALITY_W_BAK"))
        self.assertAlmostEqual(
            quality.QUALITY_W_SIG + quality.QUALITY_W_BAK, 1.0)


class DiagnoseQualityPlumbingTest(unittest.TestCase):
    def test_analyze_findings_identical_in_all_modes(self):
        sig = sine(440, 1.0, 0.5)
        off = diagnose.analyze(sig, SR)
        off_json = json.dumps(off)
        for mode in ("off", "proxy", "dnsmos"):
            got = diagnose.analyze(sig, SR, quality_engine=mode)
            self.assertEqual(json.dumps(got), off_json)

    def test_measurements_from_clean_sine_do_not_penalize_bak(self):
        # A continuous clean tone has no quiet part: diagnose's own
        # noise gate says that "floor" is signal, not hiss, so the
        # quality context must not turn it into a BAK penalty.
        sig = sine(440, 1.0, 0.5)
        meas = diagnose.quality_measurements(sig, SR)
        self.assertIsNone(meas["noise_floor_db"])
        result = quality.score(sig, SR, meas)
        self.assertIsNotNone(result)
        self.assertGreaterEqual(result["bak"], 4.5)

    def test_quality_measurements_reuse_diagnose_context(self):
        # The measurements context carries the fields score() reads,
        # built from the helpers diagnose already has.
        meas = diagnose.quality_measurements(sine(440, 1.0, 0.5), SR)
        self.assertIn("noise_floor_db", meas)
        self.assertIn("ceiling_clip", meas)
        self.assertIn("lufs", meas)
        self.assertIn("sibilant_fraction", meas)
        self.assertIn("level_spread_db", meas)
        self.assertEqual(meas["total_samples"], SR)
        self.assertFalse(meas["ceiling_clip"]["is_clipped"])


if __name__ == "__main__":
    unittest.main()
