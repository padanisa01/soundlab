"""Unit tests for multitrack DSP: resample_linear, mixdown,
resolve_record_target (audiofx.android_bridge)."""

import json
import math
import os
import sys
import tempfile
import unittest
import wave

sys.path.insert(
    0, os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "python")
)

from audiofx import android_bridge
from audiofx.android_bridge import mixdown, resample_linear, resolve_record_target
from audiofx.pipeline import read_wav_mono, write_wav

TARGET = 10.0 ** (-1.0 / 20.0)


def read_int16(path):
    """Raw int16 samples of a mono WAV file."""
    with wave.open(path, "rb") as w:
        raw = w.readframes(w.getnframes())
    return [
        int.from_bytes(raw[i : i + 2], "little", signed=True)
        for i in range(0, len(raw), 2)
    ]


def expected_mix_int16(track_paths, target_rate):
    """Reference mix computed independently of mixdown's internals."""
    bufs = []
    for p in track_paths:
        samples, rate = read_wav_mono(p)
        if rate != target_rate:
            samples = list(resample_linear(samples, rate, target_rate))
        bufs.append(list(samples))
    n = max(len(b) for b in bufs)
    mix = [0.0] * n
    for b in bufs:
        for i, s in enumerate(b):
            mix[i] += s
    peak = max(abs(s) for s in mix)
    gain = TARGET / peak
    return [
        int(round(min(max(s * gain, -TARGET), TARGET) * 32767)) for s in mix
    ]


class TestResampleLinear(unittest.TestCase):
    def test_equal_rates_return_unchanged(self):
        x = [0.1, -0.2, 0.3, 0.0]
        out = resample_linear(x, 8000, 8000)
        self.assertEqual(list(out), x)

    def test_upsample_doubles_sample_count(self):
        x = [i * 0.01 for i in range(800)]
        out = resample_linear(x, 8000, 16000)
        self.assertEqual(len(out), 1600)

    def test_upsample_interpolates_ramp_linearly(self):
        x = [i * 0.01 for i in range(100)]
        out = list(resample_linear(x, 8000, 16000))
        for k in range(99):
            self.assertAlmostEqual(out[2 * k], x[k], places=12)
            self.assertAlmostEqual(out[2 * k + 1], (x[k] + x[k + 1]) / 2.0,
                                   places=12)

    def test_downsample_halves_sample_count(self):
        x = [i * 0.01 for i in range(1600)]
        out = resample_linear(x, 16000, 8000)
        self.assertEqual(len(out), 800)

    def test_downsample_interpolates(self):
        x = [0.0, 1.0, 2.0, 3.0]
        out = list(resample_linear(x, 4000, 2000))
        self.assertEqual(len(out), 2)
        self.assertAlmostEqual(out[0], 0.0, places=12)
        self.assertAlmostEqual(out[1], 2.0, places=12)


class TestMixdown(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.rate = 16000

    def tearDown(self):
        self.tmp.cleanup()

    def path(self, name):
        return os.path.join(self.tmp.name, name)

    def write_track(self, name, samples, rate=None):
        p = self.path(name)
        write_wav(p, samples, self.rate if rate is None else rate)
        return p

    def test_two_tracks_sum_correctly(self):
        a = [0.05 + 0.008 * i for i in range(50)]
        b = [0.1] * 50
        pa = self.write_track("a.wav", a)
        pb = self.write_track("b.wav", b)
        out = self.path("mix.wav")
        res = mixdown(json.dumps([pa, pb]), out, self.rate)
        self.assertTrue(res["ok"])
        self.assertEqual(res["tracks_mixed"], 2)
        self.assertEqual(res["sample_rate"], self.rate)
        self.assertEqual(read_int16(out), expected_mix_int16([pa, pb], self.rate))

    def test_shorter_track_zero_padded(self):
        pa = self.write_track("a.wav", [0.2] * 100)
        pb = self.write_track("b.wav", [0.1] * 40)
        out = self.path("mix.wav")
        res = mixdown(json.dumps([pa, pb]), out, self.rate)
        self.assertEqual(res["tracks_mixed"], 2)
        got = read_int16(out)
        self.assertEqual(len(got), 100)
        self.assertEqual(res["duration_s"], round(100 / self.rate, 2))
        self.assertEqual(got, expected_mix_int16([pa, pb], self.rate))

    def test_peak_normalized_to_minus_1_dbfs(self):
        # Constant tracks: every mix sample equals the peak, so the
        # normalization gain is exact and the written int16 peak is the
        # nearest int16 step to 10**(-1/20) (16-bit PCM cannot represent
        # the irrational target exactly).
        pa = self.write_track("a.wav", [0.4] * 64)
        pb = self.write_track("b.wav", [0.4] * 64)
        out = self.path("mix.wav")
        mixdown(json.dumps([pa, pb]), out, self.rate)
        step = round(TARGET * 32767)
        self.assertEqual(read_int16(out), [step] * 64)
        samples, _ = read_wav_mono(out)
        peak = max(abs(s) for s in samples)
        # read_wav_mono scales int16 by 1/32768 while write_wav scales by
        # 32767, so the float peak is the written step over 32768.
        self.assertAlmostEqual(peak, step / 32768.0, places=9)

    def test_missing_and_empty_tracks_skipped(self):
        missing = self.path("nope.wav")
        empty = self.path("empty.wav")
        with wave.open(empty, "wb") as w:
            w.setnchannels(1)
            w.setsampwidth(2)
            w.setframerate(self.rate)
            w.writeframes(b"")
        good = self.write_track("good.wav", [0.4] * 64)
        out = self.path("mix.wav")
        res = mixdown(json.dumps([missing, empty, good]), out, self.rate)
        self.assertEqual(res["tracks_mixed"], 1)
        step = round(TARGET * 32767)
        self.assertEqual(read_int16(out), [step] * 64)

    def test_all_empty_raises(self):
        empty = self.path("empty.wav")
        with wave.open(empty, "wb") as w:
            w.setnchannels(1)
            w.setsampwidth(2)
            w.setframerate(self.rate)
            w.writeframes(b"")
        with self.assertRaises(ValueError) as ctx:
            mixdown(json.dumps([self.path("nope.wav"), empty]),
                    self.path("mix.wav"), self.rate)
        self.assertIn("no audible tracks to mix", str(ctx.exception))

    def test_muted_track_excluded_bit_identical(self):
        pa = self.write_track("a.wav", [0.3] * 64)
        pb = self.write_track("b.wav", [0.15] * 64)
        out = self.path("mix.wav")
        out_alone = self.path("mix_alone.wav")
        res = mixdown(
            json.dumps([{"id": "a", "path": pa}, {"id": "b", "path": pb}]),
            out, self.rate, json.dumps(["b"]),
        )
        self.assertEqual(res["tracks_mixed"], 1)
        mixdown(json.dumps([{"id": "a", "path": pa}]), out_alone, self.rate)
        self.assertEqual(read_int16(out), read_int16(out_alone))
        self.assertEqual(read_int16(out), expected_mix_int16([pa], self.rate))

    def test_unknown_muted_id_ignored(self):
        pa = self.write_track("a.wav", [0.3] * 64)
        pb = self.write_track("b.wav", [0.15] * 64)
        out = self.path("mix.wav")
        res = mixdown(
            json.dumps([{"id": "a", "path": pa}, {"id": "b", "path": pb}]),
            out, self.rate, json.dumps(["zzz-no-such-track"]),
        )
        self.assertEqual(res["tracks_mixed"], 2)
        self.assertEqual(read_int16(out),
                         expected_mix_int16([pa, pb], self.rate))

    def test_all_muted_raises(self):
        pa = self.write_track("a.wav", [0.3] * 64)
        pb = self.write_track("b.wav", [0.15] * 64)
        with self.assertRaises(ValueError) as ctx:
            mixdown(
                json.dumps([{"id": "a", "path": pa}, {"id": "b", "path": pb}]),
                self.path("mix.wav"), self.rate, json.dumps(["a", "b"]),
            )
        self.assertEqual(str(ctx.exception), "no audible tracks to mix")

    def test_bare_path_strings_still_work(self):
        pa = self.write_track("a.wav", [0.3] * 64)
        pb = self.write_track("b.wav", [0.15] * 64)
        out = self.path("mix.wav")
        res = mixdown(json.dumps([pa, pb]), out, self.rate)
        self.assertEqual(res["tracks_mixed"], 2)
        self.assertEqual(read_int16(out),
                         expected_mix_int16([pa, pb], self.rate))
        # Bare strings are never muted, even if a muted id would collide
        # with something else.
        out2 = self.path("mix2.wav")
        res2 = mixdown(json.dumps([pa, pb]), out2, self.rate,
                       json.dumps(["a", "b"]))
        self.assertEqual(res2["tracks_mixed"], 2)
        self.assertEqual(read_int16(out2), read_int16(out))

    def test_muted_track_with_missing_file_does_not_error(self):
        pa = self.write_track("a.wav", [0.3] * 64)
        missing = self.path("nope.wav")
        out = self.path("mix.wav")
        res = mixdown(
            json.dumps([{"id": "a", "path": pa}, {"id": "b", "path": missing}]),
            out, self.rate, json.dumps(["b"]),
        )
        self.assertEqual(res["tracks_mixed"], 1)
        self.assertEqual(read_int16(out), expected_mix_int16([pa], self.rate))



class TestResolveRecordTarget(unittest.TestCase):
    def resolve(self, tracks):
        return json.loads(resolve_record_target(json.dumps(tracks)))

    def test_selected_empty_unlocked_records_selected(self):
        tracks = [
            {"id": "t1", "locked": False, "has_audio": False,
             "position": 0, "selected": False},
            {"id": "t2", "locked": False, "has_audio": False,
             "position": 1, "selected": True},
        ]
        self.assertEqual(self.resolve(tracks),
                         {"action": "record", "track_id": "t2"})

    def test_selected_empty_unlocked_beats_earlier_position(self):
        tracks = [
            {"id": "t1", "locked": False, "has_audio": False,
             "position": 0, "selected": False},
            {"id": "t2", "locked": False, "has_audio": False,
             "position": 1, "selected": True},
        ]
        self.assertEqual(self.resolve(tracks)["track_id"], "t2")

    def test_selected_locked_falls_back_to_first_empty_unlocked(self):
        tracks = [
            {"id": "t1", "locked": True, "has_audio": False,
             "position": 0, "selected": True},
            {"id": "t2", "locked": False, "has_audio": False,
             "position": 1, "selected": False},
        ]
        self.assertEqual(self.resolve(tracks),
                         {"action": "record", "track_id": "t2"})

    def test_selected_with_audio_falls_back(self):
        tracks = [
            {"id": "t1", "locked": False, "has_audio": True,
             "position": 0, "selected": True},
            {"id": "t2", "locked": False, "has_audio": False,
             "position": 1, "selected": False},
        ]
        self.assertEqual(self.resolve(tracks),
                         {"action": "record", "track_id": "t2"})

    def test_never_targets_track_with_audio(self):
        tracks = [
            {"id": "t1", "locked": False, "has_audio": True,
             "position": 0, "selected": False},
            {"id": "t2", "locked": False, "has_audio": False,
             "position": 1, "selected": False},
        ]
        self.assertEqual(self.resolve(tracks)["track_id"], "t2")

    def test_all_full_creates(self):
        tracks = [
            {"id": "t1", "locked": False, "has_audio": True,
             "position": 0, "selected": True},
            {"id": "t2", "locked": False, "has_audio": True,
             "position": 1, "selected": False},
        ]
        self.assertEqual(self.resolve(tracks), {"action": "create"})

    def test_all_locked_creates(self):
        tracks = [
            {"id": "t1", "locked": True, "has_audio": False,
             "position": 0, "selected": True},
            {"id": "t2", "locked": True, "has_audio": False,
             "position": 1, "selected": False},
        ]
        self.assertEqual(self.resolve(tracks), {"action": "create"})

    def test_empty_list_creates(self):
        self.assertEqual(self.resolve([]), {"action": "create"})

    def test_first_by_position_wins(self):
        tracks = [
            {"id": "t9", "locked": False, "has_audio": False,
             "position": 5, "selected": False},
            {"id": "t3", "locked": False, "has_audio": False,
             "position": 2, "selected": False},
        ]
        self.assertEqual(self.resolve(tracks),
                         {"action": "record", "track_id": "t3"})


if __name__ == "__main__":
    unittest.main()
