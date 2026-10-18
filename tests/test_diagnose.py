"""Unit tests for audiofx.diagnose. Run: python3 tools/verify.py"""

import math
import os
import sys
import unittest

sys.path.insert(
    0, os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "python")
)

from audiofx import diagnose


SR = 48000


def sine(freq_hz, seconds, amplitude=0.8, phase=0.0):
    return [
        amplitude * math.sin(2.0 * math.pi * freq_hz * i / SR + phase)
        for i in range(int(seconds * SR))
    ]


def by_id(findings):
    return {f["id"]: f for f in findings}


class DiagnoseTest(unittest.TestCase):
    def test_clipping_found(self):
        sig = [max(-1.0, min(1.0, v)) for v in sine(440, 1.0, amplitude=1.5)]
        f = by_id(diagnose.analyze(sig, SR))["clipping"]
        self.assertEqual(f["severity"], "warn")
        self.assertIsNotNone(f["fix"])
        self.assertEqual(f["fix"]["op"], "declip")

    def test_clean_signal_no_clipping(self):
        f = by_id(diagnose.analyze(sine(440, 1.0, 0.5), SR))["clipping"]
        self.assertEqual(f["severity"], "ok")
        self.assertIsNone(f["fix"])

    def test_quiet_level(self):
        f = by_id(diagnose.analyze(sine(440, 1.0, 0.05), SR))["level"]
        self.assertEqual(f["severity"], "warn")
        self.assertEqual(f["fix"]["op"], "loudness")

    def test_dc_offset(self):
        sig = [v + 0.05 for v in sine(440, 1.0, 0.5)]
        f = by_id(diagnose.analyze(sig, SR))["dc_offset"]
        self.assertEqual(f["severity"], "warn")
        self.assertEqual(f["fix"]["op"], "dc_remove")

    def test_silence(self):
        sig = sine(440, 1.0, 0.5) + [0.0] * (3 * SR) + sine(440, 1.0, 0.5)
        f = by_id(diagnose.analyze(sig, SR))["silence"]
        self.assertEqual(f["severity"], "warn")
        self.assertEqual(f["fix"]["op"], "silence_trim")

    def test_hum_60hz(self):
        sig = [a + b for a, b in zip(sine(440, 2.0, 0.4), sine(60, 2.0, 0.1))]
        f = by_id(diagnose.analyze(sig, SR))["hum"]
        self.assertEqual(f["severity"], "warn")
        self.assertEqual(f["fix"]["params"]["freq_hz"], 60.0)

    def test_plosives(self):
        sig = sine(440, 2.0, 0.3)
        for start_ms in (500, 1200):
            start = int(start_ms * SR / 1000)
            for i in range(int(0.1 * SR)):
                sig[start + i] += 0.8 * math.sin(
                    2 * math.pi * 90 * i / SR) * (1 - i / (0.1 * SR))
        f = by_id(diagnose.analyze(sig, SR))["plosive"]
        self.assertEqual(f["severity"], "warn")
        self.assertEqual(f["fix"]["op"], "deplosive")

    def test_uneven_level(self):
        sig = sine(440, 1.0, 0.15) + sine(440, 1.0, 0.7)
        f = by_id(diagnose.analyze(sig, SR))["uneven_level"]
        self.assertEqual(f["severity"], "warn")
        self.assertEqual(f["fix"]["op"], "level")

    def test_abrupt_ends(self):
        # Sine starting at full amplitude (phase pi/2): abrupt start.
        sig = sine(440, 1.0, 0.8, phase=math.pi / 2)
        f = by_id(diagnose.analyze(sig, SR))["abrupt_ends"]
        self.assertEqual(f["severity"], "warn")
        self.assertEqual(f["fix"]["op"], "fade_ends")

    def test_loudness_off_target(self):
        # Very quiet -> integrated LUFS far below -16.
        sig = sine(440, 2.0, 0.02)
        f = by_id(diagnose.analyze(sig, SR))["loudness"]
        self.assertEqual(f["severity"], "warn")

    def test_tune_up_profiles(self):
        findings = [
            {"id": "sibilance", "severity": "warn",
             "fix": {"op": "deess", "params": {}, "label": "x"}},
            {"id": "silence", "severity": "warn",
             "fix": {"op": "silence_trim", "params": {}, "label": "x"}},
            {"id": "uneven_level", "severity": "warn",
             "fix": {"op": "level", "params": {}, "label": "x"}},
            {"id": "loudness", "severity": "warn",
             "fix": {"op": "loudness", "params": {}, "label": "x"}},
            {"id": "clipping", "severity": "ok", "fix": None},
        ]
        names = lambda ps: [o["op"] for o in diagnose.tune_up_fixes(
            findings, ps)]
        # Podcast takes everything, canonical order (level before loudness).
        self.assertEqual(names("podcast"),
                         ["deess", "silence_trim", "level", "loudness"])
        # Singing skips silence trim and leveling (dynamics are music).
        self.assertEqual(names("singing"), ["deess", "loudness"])
        # Music skips voice-specific fixes too.
        self.assertEqual(names("music"), ["loudness"])

    def test_tune_up_profile_param_overrides(self):
        findings = [
            {"id": "sibilance", "severity": "warn",
             "fix": {"op": "deess", "params": {}, "label": "x"}},
            {"id": "hum", "severity": "warn",
             "fix": {"op": "hum_notch", "params": {"freq_hz": 50.0},
                     "label": "x"}},
        ]
        singing = {o["op"]: o for o in diagnose.tune_up_fixes(
            findings, "singing")}
        # Profile overrides the tunable; detection keeps the measured freq.
        self.assertEqual(singing["deess"]["params"]["reduction_db"], 4.0)
        self.assertEqual(singing["hum_notch"]["params"]["freq_hz"], 50.0)
        podcast = {o["op"]: o for o in diagnose.tune_up_fixes(
            findings, "podcast")}
        self.assertEqual(podcast["deess"]["params"]["reduction_db"], 8.0)

    def test_list_profiles(self):
        profiles = diagnose.list_profiles()
        self.assertEqual(set(profiles), {"podcast", "singing", "music"})
        self.assertTrue(profiles["podcast"]["premium"])
        self.assertFalse(profiles["singing"]["premium"])
        self.assertTrue(profiles["music"]["premium"])

    def test_warn_first_ordering(self):
        sig = sine(440, 1.0, 0.05)  # quiet -> warn; most else ok
        findings = diagnose.analyze(sig, SR)
        severities = [f["severity"] for f in findings]
        first_ok = next((i for i, s in enumerate(severities) if s == "ok"),
                        len(severities))
        self.assertTrue(all(s == "warn" for s in severities[:first_ok]))

    def test_empty_signal(self):
        findings = diagnose.analyze([], SR)
        self.assertIsInstance(findings, list)


if __name__ == "__main__":
    unittest.main()
