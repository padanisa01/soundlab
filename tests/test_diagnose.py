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
        # No "ok" may precede a "warn" (info may sit between).
        first_ok = next((i for i, s in enumerate(severities) if s == "ok"),
                        len(severities))
        self.assertNotIn("warn", severities[first_ok:])

    def test_noise_gate_no_false_positive_on_loud_tone(self):
        # A consistently loud pure tone is signal, not hiss: the quietest
        # windows are as loud as the whole signal, so no warn.
        sig = sine(440, 1.0, 0.5)
        sev = {f["id"]: f["severity"] for f in diagnose.analyze(sig, SR)}
        self.assertNotEqual(sev["noise_floor"], "warn")

    def test_noise_gate_still_catches_real_hiss(self):
        import random
        random.seed(1)
        # Loud tone, then a quiet gap with real hiss.
        sig = sine(440, 1.0, 0.5) + [0.02 * (random.random() * 2 - 1)
                                    for _ in range(int(SR * 1.0))]
        sev = {f["id"]: f["severity"] for f in diagnose.analyze(sig, SR)}
        self.assertEqual(sev["noise_floor"], "warn")

    def test_fade_ends_clears_abrupt_ends(self):
        from audiofx import repair
        sig = sine(440, 1.0, 0.5)  # cut mid-waveform at both ends
        ids = [f["id"] for f in diagnose.analyze(sig, SR)
               if f["severity"] == "warn"]
        self.assertIn("abrupt_ends", ids)
        fixed = repair.fade_ends(sig, SR)
        ids = [f["id"] for f in diagnose.analyze(fixed, SR)
               if f["severity"] == "warn"]
        self.assertNotIn("abrupt_ends", ids)

    def test_empty_signal(self):
        findings = diagnose.analyze([], SR)
        self.assertIsInstance(findings, list)


class TuneUpConvergeTest(unittest.TestCase):
    """One tune_up op must converge: no re-taps needed."""

    def test_converge_clears_covered_checks(self):
        import json
        import random
        import tempfile
        from audiofx import android_bridge as ab
        from audiofx.pipeline import write_wav

        # Realistic bad phone recording: clipped peaks, 60 Hz hum, hiss,
        # muddy low end (~+10 dB), abrupt ends, off-target loudness.
        sr = 44100
        random.seed(7)
        sig = []
        for i in range(sr * 4):
            t = i / sr
            v = 0.35 * math.sin(2 * math.pi * 220 * t)
            v += 0.30 * math.sin(2 * math.pi * 110 * t)
            v += 0.10 * math.sin(2 * math.pi * 1000 * t)
            v += 0.12 * math.sin(2 * math.pi * 60 * t)
            v += 0.02 * (random.random() * 2 - 1)
            sig.append(max(-1.0, min(1.0, v * 1.6)))  # some clipping
        work = tempfile.mkdtemp()
        src = os.path.join(work, "in.wav")
        write_wav(src, sig, sr)

        before = json.loads(ab.diagnose_file(src))
        n_warn_before = sum(1 for f in before if f["severity"] == "warn")
        self.assertGreater(n_warn_before, 3)

        op = json.loads(ab.tune_up_converge(
            src, json.dumps(before), "singing"))
        self.assertGreaterEqual(op["params"]["passes"], 1)
        out = os.path.join(work, "out.wav")
        r = ab.render_chain(src, json.dumps(
            [{"op": "tune_up", "params": op["params"]}]), out)
        self.assertTrue(r["ok"])

        after = json.loads(ab.diagnose_file(out))
        recipe = set(diagnose.TUNE_UP_PROFILES["singing"]["fixes"])
        for f in after:
            if f["severity"] != "warn" or not f.get("fix"):
                continue
            # Every remaining warn must be outside the singing recipe
            # (by-design exclusions like uneven_level), never a fix the
            # recipe promised but failed to clear.
            self.assertNotIn(f["fix"]["op"], recipe,
                             "fix did not converge: %s" % f["id"])
        n_warn_after = sum(1 for f in after if f["severity"] == "warn")
        self.assertLess(n_warn_after, n_warn_before)

    def _nasty_src(self, work):
        import json
        import random
        from audiofx import android_bridge as ab
        from audiofx.pipeline import write_wav

        sr = 44100
        random.seed(7)
        sig = []
        for i in range(sr * 4):
            t = i / sr
            v = 0.35 * math.sin(2 * math.pi * 220 * t)
            v += 0.30 * math.sin(2 * math.pi * 110 * t)
            v += 0.10 * math.sin(2 * math.pi * 1000 * t)
            v += 0.12 * math.sin(2 * math.pi * 60 * t)
            v += 0.02 * (random.random() * 2 - 1)
            sig.append(max(-1.0, min(1.0, v * 1.6)))
        src = os.path.join(work, "in.wav")
        write_wav(src, sig, sr)
        return src

    def test_converge_progress_callbacks(self):
        import json
        import tempfile
        from audiofx import android_bridge as ab

        work = tempfile.mkdtemp()
        src = self._nasty_src(work)
        fj = ab.diagnose_file(src)

        calls = []

        class FakeProgress:
            def tuneUpPassStarted(self, n, max_n, labels_json):
                calls.append(("pass", n, max_n, json.loads(labels_json)))

            def tuneUpStepActive(self, i):
                calls.append(("active", i))

            def tuneUpStepDone(self, i):
                calls.append(("done", i))

        op = json.loads(ab.tune_up_converge(src, fj, "singing",
                                            FakeProgress()))
        self.assertTrue(calls, "no progress callbacks fired")
        # Every pass announces its step labels up front...
        passes = [c for c in calls if c[0] == "pass"]
        self.assertTrue(passes)
        for _, n, max_n, labels in passes:
            self.assertLessEqual(n, max_n)
            self.assertTrue(labels)
        # ...then each step goes active -> done in order, per pass.
        idx = 0
        for _, n, max_n, labels in passes:
            self.assertEqual(calls[idx][0], "pass")
            self.assertEqual(calls[idx][1], n)
            idx += 1
            for step in range(len(labels)):
                self.assertEqual(calls[idx], ("active", step))
                self.assertEqual(calls[idx + 1], ("done", step))
                idx += 2
        self.assertEqual(idx, len(calls))
        self.assertIn("fixes", op["params"])

    def test_converge_writes_output_once(self):
        import json
        import numpy as np
        import tempfile
        from audiofx import android_bridge as ab
        from audiofx.pipeline import read_wav_mono

        work = tempfile.mkdtemp()
        src = self._nasty_src(work)
        fj = ab.diagnose_file(src)
        out = os.path.join(work, "converged.wav")
        before = set(os.listdir(work))
        op = json.loads(ab.tune_up_converge(src, fj, "singing",
                                            output_path=out))
        # Single write, no temp files left behind.
        self.assertEqual(set(os.listdir(work)) - before, {"converged.wav"})
        self.assertIn("duration_s", op)
        self.assertGreater(op["duration_s"], 0)
        # The adopted file decodes to real audio of the right length.
        got, sr = read_wav_mono(out)
        self.assertEqual(sr, 44100)
        self.assertEqual(len(got), len(read_wav_mono(src)[0]))
        self.assertGreater(float(np.max(np.abs(got))), 0.01)

    def test_incremental_render_matches_full(self):
        import json
        import numpy as np
        import tempfile
        from audiofx import android_bridge as ab
        from audiofx.pipeline import read_wav_mono

        work = tempfile.mkdtemp()
        src = self._nasty_src(work)
        # Full chain render from pristine...
        ops = [{"op": "dc_remove", "params": {}},
               {"op": "hum_notch", "params": {"freq_hz": 60.0}},
               {"op": "fade_ends", "params": {}}]
        full = os.path.join(work, "full.wav")
        ab.render_chain(src, json.dumps(ops), full)
        # Seed the tip with the chain prefix, then apply the last op from it.
        ab.render_chain(src, json.dumps(ops[:2]),
                        os.path.join(work, "tip.wav"), cache_key="e1")
        inc = os.path.join(work, "inc.wav")
        r = ab.render_chain_from_tip("e1", json.dumps(ops[2:]), inc)
        self.assertTrue(r["cache_hit"])
        a = np.asarray(read_wav_mono(full)[0], dtype=np.float64)
        b = np.asarray(read_wav_mono(inc)[0], dtype=np.float64)
        self.assertEqual(a.shape, b.shape)
        self.assertTrue(np.array_equal(a, b))

    def test_converge_adopt_matches_full_chain(self):
        import json
        import numpy as np
        import tempfile
        from audiofx import android_bridge as ab
        from audiofx.pipeline import read_wav_mono

        work = tempfile.mkdtemp()
        src = self._nasty_src(work)
        base = [{"op": "dc_remove", "params": {}}]
        # Seed the tip the way applyLibraryOp does.
        ab.render_chain(src, json.dumps(base),
                        os.path.join(work, "tip.wav"), cache_key="e9")
        fj = ab.diagnose_file(os.path.join(work, "tip.wav"))
        adopted = os.path.join(work, "adopted.wav")
        op = json.loads(ab.tune_up_converge(
            os.path.join(work, "tip.wav"), fj, "singing",
            output_path=adopted, cache_key="e9"))
        # The adopted file must equal a full pristine-based chain render:
        # converge ran on the exact float tip, not the quantized file.
        ref = os.path.join(work, "ref.wav")
        ab.render_chain(src, json.dumps(
            base + [{"op": "tune_up", "params": op["params"]}]), ref)
        a = np.asarray(read_wav_mono(ref)[0], dtype=np.float64)
        b = np.asarray(read_wav_mono(adopted)[0], dtype=np.float64)
        self.assertTrue(np.array_equal(a, b))

    def test_tip_cache_miss_falls_back(self):
        import json
        import tempfile
        from audiofx import android_bridge as ab

        work = tempfile.mkdtemp()
        src = self._nasty_src(work)
        r = ab.render_chain_from_tip(
            "nope", json.dumps([{"op": "fade_ends", "params": {}}]),
            os.path.join(work, "x.wav"))
        self.assertFalse(r["ok"])
        self.assertFalse(r["cache_hit"])

    def test_converge_survives_broken_progress(self):
        import json
        import tempfile
        from audiofx import android_bridge as ab

        work = tempfile.mkdtemp()
        src = self._nasty_src(work)
        fj = ab.diagnose_file(src)

        class BrokenProgress:
            def __getattr__(self, name):
                def boom(*a):
                    raise RuntimeError("ui gone")
                return boom

        # A failing UI callback must never break the render.
        op = json.loads(ab.tune_up_converge(src, fj, "singing",
                                            BrokenProgress()))
        self.assertIn("fixes", op["params"])


if __name__ == "__main__":
    unittest.main()
