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

from audiofx import android_bridge, diagnose, quality


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


class QualityDriversTest(unittest.TestCase):
    """score() also reports the per-driver penalty POINTS under
    "drivers" (pre-clamp); the ovrl/sig/bak numbers are unchanged."""

    def test_clean_drivers_all_zero(self):
        result = quality.score(sine(440, 0.25), SR, clean_measurements())
        self.assertEqual(result["drivers"], {
            "clipping": 0.0, "sibilance": 0.0, "level_spread": 0.0,
            "loudness": 0.0, "noise": 0.0, "hum": 0.0})
        self.assertEqual(result["sig"], 5.0)
        self.assertEqual(result["bak"], 5.0)
        self.assertEqual(result["ovrl"], 5.0)

    def test_clipped_drivers_clipping_dominant(self):
        meas = clean_measurements()
        meas["clip_fraction"] = 0.01  # exactly the bad anchor
        result = quality.score(sine(440, 0.25), SR, meas)
        d = result["drivers"]
        self.assertAlmostEqual(d["clipping"],
                               quality.QUALITY_CLIP_PENALTY_MAX)
        for key in ("sibilance", "level_spread", "loudness",
                    "noise", "hum"):
            self.assertEqual(d[key], 0.0)
        # SIG drivers sum to 5 - sig (no clamping in play here).
        self.assertAlmostEqual(
            d["clipping"] + d["sibilance"] + d["level_spread"]
            + d["loudness"], 5.0 - result["sig"])
        self.assertAlmostEqual(result["sig"], 1.5)

    def test_noisy_drivers_noise_dominant_for_bak(self):
        meas = clean_measurements()
        meas["noise_floor_db"] = -40.0
        result = quality.score(sine(440, 0.25), SR, meas)
        d = result["drivers"]
        # -40 dBFS is 30/40 of the way from the -70 clean anchor to
        # the -30 bad anchor: 3.2 * 0.75 = 2.4 points.
        self.assertAlmostEqual(d["noise"], 2.4)
        self.assertEqual(d["hum"], 0.0)
        self.assertGreater(d["noise"], d["hum"])
        self.assertAlmostEqual(d["noise"] + d["hum"],
                               5.0 - result["bak"])
        self.assertAlmostEqual(result["bak"], 2.6)
        self.assertAlmostEqual(result["ovrl"], 0.6 * 5.0 + 0.4 * 2.6)

    def test_drivers_are_pre_clamp_penalties(self):
        # Every SIG driver maxed: penalties sum to 6.2 points, past
        # what the [1, 5] clamp can express; drivers still report
        # the full pre-clamp penalties while sig clamps to 1.0.
        meas = clean_measurements()
        meas["clip_fraction"] = 1.0
        meas["sibilant_fraction"] = 1.0
        meas["level_spread_db"] = 100.0
        meas["lufs_distance_db"] = 44.0
        result = quality.score(sine(440, 0.25), SR, meas)
        d = result["drivers"]
        self.assertAlmostEqual(
            d["clipping"] + d["sibilance"] + d["level_spread"]
            + d["loudness"], 3.5 + 1.2 + 1.0 + 0.5)
        self.assertEqual(result["sig"], 1.0)

    def test_improve_min_penalty_constant(self):
        self.assertEqual(quality.QUALITY_IMPROVE_MIN_PENALTY, 0.3)


def _warn_fix(op, params=None):
    return {"id": op, "severity": "warn", "title": op,
            "fix": {"op": op, "params": params or {}, "label": op}}


def _quality_json(drivers):
    return json.dumps({"ovrl": 3.0, "sig": 3.0, "bak": 3.0,
                       "engine": "proxy", "drivers": drivers})


def _zero_drivers(**overrides):
    d = {"clipping": 0.0, "sibilance": 0.0, "level_spread": 0.0,
         "loudness": 0.0, "noise": 0.0, "hum": 0.0}
    d.update(overrides)
    return d


class QualityImproveBuildTest(unittest.TestCase):
    """quality_improve_build: tune_up_build's composite shape, but
    only the fixes whose quality driver penalty is at/above
    QUALITY_IMPROVE_MIN_PENALTY, in tune_up_fixes' canonical order."""

    def test_filters_to_penalized_drivers(self):
        findings = json.dumps([_warn_fix(op) for op in (
            "declip", "deess", "level", "loudness", "denoise",
            "hum_notch")])
        q = _quality_json(_zero_drivers(clipping=1.0, hum=0.5,
                                        noise=0.1))
        out = json.loads(android_bridge.quality_improve_build(
            findings, q, "podcast"))
        self.assertEqual(out["op"], "tune_up")
        ops = [f["op"] for f in out["params"]["fixes"]]
        # noise (0.1) is below the 0.3 threshold: denoise excluded.
        self.assertEqual(ops, ["declip", "hum_notch"])
        self.assertEqual(out["params"]["profile"], "podcast")

    def test_canonical_order_preserved(self):
        # Findings deliberately scrambled; the composite must come
        # out in tune_up_fixes' canonical order regardless.
        findings = json.dumps([_warn_fix(op) for op in (
            "denoise", "loudness", "level", "deess", "hum_notch",
            "declip")])
        q = _quality_json(_zero_drivers(
            clipping=1.0, sibilance=1.0, level_spread=1.0,
            loudness=0.5, noise=1.0, hum=0.5))
        out = json.loads(android_bridge.quality_improve_build(
            findings, q, "podcast"))
        ops = [f["op"] for f in out["params"]["fixes"]]
        self.assertEqual(ops, ["declip", "hum_notch", "deess",
                               "level", "loudness", "denoise"])

    def test_threshold_boundary(self):
        findings = json.dumps([_warn_fix("declip"),
                               _warn_fix("deess")])
        q = _quality_json(_zero_drivers(clipping=0.3, sibilance=0.29))
        out = json.loads(android_bridge.quality_improve_build(
            findings, q, "podcast"))
        ops = [f["op"] for f in out["params"]["fixes"]]
        self.assertEqual(ops, ["declip"])

    def test_empty_selection_mirrors_tune_up_build_shape(self):
        findings = json.dumps([_warn_fix("declip")])
        q = _quality_json(_zero_drivers())
        out = json.loads(android_bridge.quality_improve_build(
            findings, q, "podcast"))
        empty = json.loads(android_bridge.tune_up_build("[]", "podcast"))
        self.assertEqual(out["op"], empty["op"])
        self.assertEqual(set(out["params"]), set(empty["params"]))
        self.assertEqual(out["params"]["fixes"], [])
        self.assertEqual(out["params"]["profile"], "podcast")
        self.assertIn("nothing to fix", out["params"]["summary"])

    def test_driver_without_matching_finding_contributes_nothing(self):
        # Clipping is heavily penalized but the findings carry no
        # declip fix (and the one fix present, denoise, has a clean
        # driver): the selection is empty.
        findings = json.dumps([_warn_fix("denoise")])
        q = _quality_json(_zero_drivers(clipping=2.0))
        out = json.loads(android_bridge.quality_improve_build(
            findings, q, "podcast"))
        self.assertEqual(out["params"]["fixes"], [])

    def test_driver_fix_applies_even_when_recipe_excludes_it(self):
        # The singing recipe has no "level" fix ("no leveling"), but
        # Improve quality is an explicit request to fix the penalized
        # driver: the level fix applies anyway (the user's on-device
        # case: Singing profile + uneven level -> "nothing to
        # apply"). The profile still owns the summary label.
        findings = json.dumps([_warn_fix("level")])
        q = _quality_json(_zero_drivers(level_spread=1.0))
        out = json.loads(android_bridge.quality_improve_build(
            findings, q, "singing"))
        ops = [f["op"] for f in out["params"]["fixes"]]
        self.assertEqual(ops, ["level"])
        self.assertEqual(out["params"]["profile"], "singing")
        self.assertIn("Singing", out["params"]["summary"])

    def test_missing_drivers_is_empty(self):
        findings = json.dumps([_warn_fix("declip")])
        q = json.dumps({"ovrl": 3.0, "sig": 3.0, "bak": 3.0,
                        "engine": "proxy"})
        out = json.loads(android_bridge.quality_improve_build(
            findings, q, "podcast"))
        self.assertEqual(out["params"]["fixes"], [])

    def test_bad_json_raises(self):
        with self.assertRaises(ValueError):
            android_bridge.quality_improve_build(
                "not json", _quality_json(_zero_drivers()), "podcast")
        with self.assertRaises(ValueError):
            android_bridge.quality_improve_build(
                "[]", "not json", "podcast")


class QualityImproveConvergeTest(unittest.TestCase):
    """quality_improve_converge: multi-pass, keep-best. The
    single-pass build can regress the score it targets (a gain fix
    re-raises the noise floor); converge re-derives drivers per
    pass and discards a pass that lowers Overall, so its composite
    can never measure worse than the starting Overall."""

    SR2 = 16000

    def _fixture(self, burst_amp, noise_sigma):
        import random
        rng = random.Random(42)
        out = []
        # 0.4 s tone bursts separated by 0.6 s noise-only gaps.
        for sec in range(3):
            for i in range(int(0.4 * self.SR2)):
                out.append(burst_amp * math.sin(
                    2.0 * math.pi * 300.0 * i / self.SR2)
                    + rng.gauss(0.0, noise_sigma))
            for _ in range(int(0.6 * self.SR2)):
                out.append(rng.gauss(0.0, noise_sigma))
        return out

    def _run_case(self, samples):
        from audiofx.pipeline import write_wav
        import tempfile
        findings = diagnose.analyze(samples, self.SR2)
        q = quality.score(samples, self.SR2,
                          diagnose.quality_measurements(
                              samples, self.SR2))
        self.assertIsNotNone(q)
        findings_j = json.dumps(findings)
        quality_j = json.dumps(q)
        with tempfile.TemporaryDirectory() as d:
            path = os.path.join(d, "take.wav")
            write_wav(path, samples, self.SR2)
            conv = json.loads(android_bridge.quality_improve_converge(
                path, findings_j, quality_j, "singing"))
        single = json.loads(android_bridge.quality_improve_build(
            findings_j, quality_j, "singing"))

        def ovrl_after(op_json):
            fixes = op_json["params"]["fixes"]
            if not fixes:
                return q["ovrl"], 0
            rendered, _ = android_bridge._run_ops(
                list(samples), self.SR2,
                [{"op": f["op"], "params": f["params"]}
                 for f in fixes], None)
            q2 = quality.score(rendered, self.SR2,
                               diagnose.quality_measurements(
                                   rendered, self.SR2))
            return q2["ovrl"], len(fixes)

        return q["ovrl"], single, conv, ovrl_after

    def test_converge_never_measures_worse_than_start_or_build(self):
        # Quiet take, faint hiss: the loudness gain is the trap —
        # it re-raises the floor. (The user's on-device regression:
        # Overall 3.9 -> 3.3 from the single-pass build.)
        samples = self._fixture(0.10, 0.00035)
        ovrl0, single, conv, ovrl_after = self._run_case(samples)
        single_ovrl, single_n = ovrl_after(single)
        conv_ovrl, _ = ovrl_after(conv)
        self.assertGreater(single_n, 0)  # the trap was armed
        self.assertGreaterEqual(conv_ovrl, ovrl0 - 1e-6)
        self.assertGreaterEqual(conv_ovrl, single_ovrl - 1e-6)

    def test_converge_refuses_a_take_no_fix_set_improves(self):
        # Hissy AND quiet AND uneven: every selectable fix set makes
        # the measured Overall worse (the gain fixes cost more
        # Background than the Signal they buy; the spectral denoise
        # cannot hold the floor against +12 dB of gain riding). The
        # honest result is an empty composite, not a regression.
        # Measured at authoring time: single-pass build takes this
        # fixture 3.41 -> 2.85; converge must not.
        samples = self._fixture(0.10, 0.004)
        ovrl0, single, conv, ovrl_after = self._run_case(samples)
        single_ovrl, single_n = ovrl_after(single)
        self.assertGreater(single_n, 0)
        self.assertLess(single_ovrl, ovrl0)  # build really regresses
        self.assertEqual(conv["params"]["fixes"], [])
        conv_ovrl, _ = ovrl_after(conv)
        self.assertEqual(conv_ovrl, ovrl0)

    def test_clean_take_converges_to_nothing(self):
        samples = self._fixture(0.5, 0.00001)
        from audiofx.pipeline import write_wav
        import tempfile
        findings = json.dumps(diagnose.analyze(samples, self.SR2))
        q = quality.score(samples, self.SR2,
                          diagnose.quality_measurements(
                              samples, self.SR2))
        with tempfile.TemporaryDirectory() as d:
            path = os.path.join(d, "take.wav")
            write_wav(path, samples, self.SR2)
            conv = json.loads(android_bridge.quality_improve_converge(
                path, findings, json.dumps(q), "singing"))
        self.assertEqual(conv["params"]["fixes"], [])
        self.assertEqual(conv["params"]["passes"], 0)

    def test_converge_errors(self):
        with self.assertRaises(ValueError):
            android_bridge.quality_improve_converge(
                "/nonexistent/take.wav", "[]",
                json.dumps({"ovrl": 3.0, "drivers": {}}), "singing")
        with self.assertRaises(ValueError):
            android_bridge.quality_improve_converge(
                __file__, "[]", json.dumps({"drivers": {}}), "singing")
        with self.assertRaises(ValueError):
            android_bridge.quality_improve_converge(
                __file__, "not json",
                json.dumps({"ovrl": 3.0, "drivers": {}}), "singing")


if __name__ == "__main__":
    unittest.main()
