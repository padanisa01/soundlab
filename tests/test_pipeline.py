"""Unit tests for audiofx.pipeline and audiofx.android_bridge."""

import json
import math
import os
import struct
import sys
import tempfile
import unittest
import unittest.mock
import wave

sys.path.insert(
    0, os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "python")
)

from audiofx import Echo, EffectChain, Reverb, Warmth, read_wav_mono, write_wav
from audiofx import android_bridge
from audiofx import pipeline
from audiofx.live import LiveChain, StereoLiveChain


def sine(freq_hz, seconds, sample_rate, amplitude=0.5):
    return [
        amplitude * math.sin(2.0 * math.pi * freq_hz * i / sample_rate)
        for i in range(int(seconds * sample_rate))
    ]


class Tag:
    """Test effect stub that records application order."""

    def __init__(self, tag, log):
        self.tag = tag
        self.log = log

    def process(self, samples, sample_rate):
        self.log.append(self.tag)
        return samples


class TestEffectChain(unittest.TestCase):
    def test_applies_in_order(self):
        log = []
        chain = EffectChain([Tag("a", log), Tag("b", log), Tag("c", log)])
        sig = [0.1, 0.2, 0.3]
        self.assertEqual(chain.process(sig, 8000), sig)
        self.assertEqual(log, ["a", "b", "c"])

    def test_add(self):
        chain = EffectChain().add(Warmth(0.0)).add(Echo(0.0))
        sig = sine(440, 0.1, 8000)
        self.assertEqual(chain.process(sig, 8000), sig)

    def test_chain_actually_processes(self):
        chain = EffectChain([Warmth(1.0)])
        sig = sine(440, 0.2, 8000)
        out = chain.process(sig, 8000)
        self.assertNotEqual(out, sig)


class TestWavIO(unittest.TestCase):
    SR = 16000

    def test_round_trip(self):
        sig = sine(440, 0.25, self.SR)
        with tempfile.TemporaryDirectory() as d:
            p = os.path.join(d, "t.wav")
            write_wav(p, sig, self.SR)
            back, sr = read_wav_mono(p)
        self.assertEqual(sr, self.SR)
        self.assertEqual(len(back), len(sig))
        for a, b in zip(sig, back):
            # 16-bit quantization tolerance.
            self.assertAlmostEqual(a, b, delta=1.0 / 32768 + 1e-9)

    def test_stereo_mixed_to_mono(self):
        with tempfile.TemporaryDirectory() as d:
            p = os.path.join(d, "stereo.wav")
            with wave.open(p, "wb") as w:
                w.setnchannels(2)
                w.setsampwidth(2)
                w.setframerate(self.SR)
                w.writeframes(struct.pack("<4h", 32767, -32768, 16384, 16384))
            samples, sr = read_wav_mono(p)
        self.assertEqual(sr, self.SR)
        self.assertEqual(len(samples), 2)
        self.assertAlmostEqual(samples[0], (32767 / 32768.0 + -1.0) / 2, places=5)
        self.assertAlmostEqual(samples[1], 0.5, places=5)

    def test_unsupported_bit_depth_raises(self):
        with tempfile.TemporaryDirectory() as d:
            p = os.path.join(d, "b24.wav")
            with wave.open(p, "wb") as w:
                w.setnchannels(1)
                w.setsampwidth(3)
                w.setframerate(self.SR)
                w.writeframes(b"\x00\x00\x00" * 10)
            with self.assertRaises(ValueError):
                read_wav_mono(p)


class TestAndroidBridge(unittest.TestCase):
    SR = 8000

    def _make_wav(self, d, name="in.wav"):
        p = os.path.join(d, name)
        write_wav(p, sine(440, 0.3, self.SR), self.SR)
        return p

    def test_process_file_all_presets(self):
        with tempfile.TemporaryDirectory() as d:
            src = self._make_wav(d)
            for preset in android_bridge.list_reverb_presets():
                dst = os.path.join(d, "out_%s.wav" % preset)
                res = android_bridge.process_file(
                    src, dst, warmth=0.7, reverb_preset=preset, echo=0.4
                )
                self.assertTrue(res["ok"])
                self.assertEqual(res["sample_rate"], self.SR)
                self.assertTrue(os.path.isfile(dst))
                samples, _ = read_wav_mono(dst)
                self.assertGreater(len(samples), 0)
                self.assertTrue(all(abs(s) <= 1.0 for s in samples))

    def test_zero_amount_effects_are_bypass(self):
        # Reverb presets always add a tail, so exact-bypass is checked on the
        # chain directly with the slider effects at zero.
        with tempfile.TemporaryDirectory() as d:
            src = self._make_wav(d)
            orig, _ = read_wav_mono(src)
            chain = EffectChain([Warmth(0.0), Echo(0.0)])
            self.assertEqual(chain.process(orig, self.SR), orig)

    def test_bad_preset_raises(self):
        with tempfile.TemporaryDirectory() as d:
            src = self._make_wav(d)
            with self.assertRaises(ValueError):
                android_bridge.process_file(
                    src, os.path.join(d, "o.wav"), reverb_preset="arena"
                )

    def test_missing_input_raises(self):
        with tempfile.TemporaryDirectory() as d:
            with self.assertRaises(ValueError):
                android_bridge.process_file(
                    os.path.join(d, "nope.wav"), os.path.join(d, "o.wav")
                )

    def test_normalize_hits_target_peak(self):
        with tempfile.TemporaryDirectory() as d:
            src = os.path.join(d, "in.wav")
            write_wav(src, sine(440, 0.3, self.SR, amplitude=0.07), self.SR)
            dst = os.path.join(d, "out.wav")
            res = android_bridge.normalize_file(src, dst)
            self.assertTrue(res["ok"])
            self.assertEqual(res["sample_rate"], self.SR)
            samples, _ = read_wav_mono(dst)
            peak = max(abs(s) for s in samples)
            # 16-bit quantization: peak lands within 1 LSB of -1 dBFS.
            self.assertAlmostEqual(20 * math.log10(peak), -1.0, places=2)
            self.assertAlmostEqual(res["peak_before_db"],
                                   20 * math.log10(0.07), places=1)
            self.assertEqual(res["peak_after_db"], -1.0)
            self.assertGreater(res["applied_gain_db"], 20)

    def test_normalize_preserves_dynamics(self):
        # Whole-file constant gain: sample ratios are unchanged.
        with tempfile.TemporaryDirectory() as d:
            src = os.path.join(d, "in.wav")
            orig = sine(440, 0.3, self.SR, amplitude=0.2)
            write_wav(src, orig, self.SR)
            dst = os.path.join(d, "out.wav")
            android_bridge.normalize_file(src, dst)
            out, _ = read_wav_mono(dst)
            ratios = [o / n for o, n in zip(orig, out) if abs(n) > 1e-6]
            self.assertTrue(all(abs(r - ratios[0]) < 1e-3 for r in ratios))

    def test_normalize_silent_raises(self):
        with tempfile.TemporaryDirectory() as d:
            src = os.path.join(d, "in.wav")
            write_wav(src, [0.0] * 800, self.SR)
            with self.assertRaises(ValueError):
                android_bridge.normalize_file(src, os.path.join(d, "o.wav"))

    def test_normalize_missing_input_raises(self):
        with tempfile.TemporaryDirectory() as d:
            with self.assertRaises(ValueError):
                android_bridge.normalize_file(
                    os.path.join(d, "nope.wav"), os.path.join(d, "o.wav")
                )

    def test_normalize_pops_cap_the_gain(self):
        # Safe-normalize semantics (changed 2026-10-27): the gain is
        # capped by the absolute peak, so loud pops set the gain and
        # the body is NOT boosted past them. The old behavior gained
        # to the percentile and hard-clipped everything above it --
        # on dynamic material that flattened ~1% of samples at the
        # target (the soundcheck_1 regression).
        with tempfile.TemporaryDirectory() as d:
            src = os.path.join(d, "in.wav")
            body = sine(440, 0.3, self.SR, amplitude=0.07)
            pops = list(body)
            for i in range(0, len(pops), 200):
                pops[i] = 0.9 if i % 400 == 0 else -0.9
            write_wav(src, pops, self.SR)
            dst = os.path.join(d, "out.wav")
            res = android_bridge.normalize_file(src, dst)
            self.assertEqual(res["percentile"], 99.0)
            self.assertTrue(res["gain_capped_by_peak"])
            # Gain is set by the 0.9 peak, not the percentile body.
            target = 10.0 ** (-1.0 / 20.0)
            self.assertAlmostEqual(
                res["applied_gain_db"],
                20 * math.log10(target / 0.9), places=1)
            out, _ = read_wav_mono(dst)
            # Nothing exceeds the target, and the peak lands on it.
            self.assertLessEqual(max(abs(s) for s in out), target + 2 / 32768)
            self.assertAlmostEqual(20 * math.log10(max(abs(s) for s in out)),
                                   -1.0, places=2)
            # No flat tops were created: the pops stay isolated
            # single samples at the ceiling.
            from audiofx import diagnose
            stats = diagnose.ceiling_clip_stats(out)
            self.assertFalse(stats["is_clipped"])
            self.assertEqual(stats["flat_samples"], 0)

    def test_normalize_percentile_param(self):
        # percentile is a code-level parameter; p50 measures the
        # median as the reported reference, but the applied gain is
        # still capped by the absolute peak (safe normalize).
        with tempfile.TemporaryDirectory() as d:
            src = os.path.join(d, "in.wav")
            write_wav(src, sine(440, 0.3, self.SR, amplitude=0.07), self.SR)
            dst = os.path.join(d, "out.wav")
            res = android_bridge.normalize_file(src, dst, percentile=50.0)
            self.assertEqual(res["percentile"], 50.0)
            # The reported reference is the median of |sine|.
            median_ref = 0.707 * 0.07
            self.assertAlmostEqual(
                res["ref_percentile_db"],
                20 * math.log10(median_ref), places=0)
            # The applied gain is the peak gain, not the median gain.
            self.assertAlmostEqual(
                res["applied_gain_db"],
                20 * math.log10(10.0 ** (-1.0 / 20.0) / 0.07), places=0)
            out, _ = read_wav_mono(dst)
            self.assertLessEqual(max(abs(s) for s in out),
                                 10.0 ** (-1.0 / 20.0) + 2 / 32768)

    def test_normalize_percentile_100_matches_peak(self):
        # percentile=100 recovers classic absolute-peak normalization.
        with tempfile.TemporaryDirectory() as d:
            src = os.path.join(d, "in.wav")
            write_wav(src, sine(440, 0.3, self.SR, amplitude=0.07), self.SR)
            dst = os.path.join(d, "out.wav")
            res = android_bridge.normalize_file(src, dst, percentile=100.0)
            self.assertAlmostEqual(
                res["applied_gain_db"],
                20 * math.log10(10.0 ** (-1.0 / 20.0) / 0.07), places=1)

    def test_normalize_never_creates_flat_tops(self):
        # Regression (soundcheck_1, 2026-10-27): a dynamic signal
        # whose top ~2% sits far above its p99 reference was gained
        # to p99 and hard-held, flattening those samples at exactly
        # -1 dBFS (11,009 samples / 448 runs in the QA file). Safe
        # normalize must add zero flat-top samples.
        from audiofx import diagnose
        # 97% quiet body + one sustained loud passage (3%) ramping
        # 0.4 -> 0.9: the p99 reference lands mid-passage (~0.73),
        # so percentile gain would flatten the passage's top ~200
        # consecutive samples at the target.
        sig = [0.1 * math.sin(2.0 * math.pi * 440 * i / self.SR)
               for i in range(19400)]
        sig += [0.4 + 0.5 * j / 599.0 for j in range(600)]
        self.assertFalse(diagnose.ceiling_clip_stats(sig)["is_clipped"])
        out, info = android_bridge._apply_normalize(sig, self.SR)
        self.assertTrue(info["gain_capped_by_peak"])
        stats = diagnose.ceiling_clip_stats(list(out))
        self.assertFalse(stats["is_clipped"])
        self.assertEqual(stats["flat_samples"], 0)
        self.assertAlmostEqual(max(abs(s) for s in out),
                               10.0 ** (-1.0 / 20.0), places=6)

    def test_scan_clipping_file_detects_below_full_scale_ceiling(self):
        # A signal hard-held at -1 dBFS (peak 0.8912, never reaching
        # the 0.999 full-scale test) must scan as clipped, with the
        # exact at-ceiling population reported.
        with tempfile.TemporaryDirectory() as d:
            src = os.path.join(d, "in.wav")
            ceiling = 10.0 ** (-1.0 / 20.0)
            loud = sine(110, 0.3, self.SR, amplitude=1.2)
            clipped = [max(-ceiling, min(ceiling, v)) for v in loud]
            write_wav(src, clipped, self.SR)
            stats = json.loads(android_bridge.scan_clipping_file(src))
            self.assertTrue(stats["is_clipped"])
            self.assertAlmostEqual(stats["peak_db"], -1.0, places=1)
            self.assertGreater(stats["flat_samples"], 100)
            self.assertGreater(stats["flat_runs"], 10)
            self.assertGreater(stats["longest_run"], 5)
            # A clean sine at the same peak scans clean.
            clean = os.path.join(d, "clean.wav")
            write_wav(clean, sine(110, 0.3, self.SR, amplitude=ceiling),
                      self.SR)
            stats = json.loads(android_bridge.scan_clipping_file(clean))
            self.assertFalse(stats["is_clipped"])

    def test_normalize_bad_percentile_raises(self):
        with tempfile.TemporaryDirectory() as d:
            src = os.path.join(d, "in.wav")
            write_wav(src, sine(440, 0.3, self.SR, amplitude=0.07), self.SR)
            for bad in (0.0, -5.0, 100.5):
                with self.assertRaises(ValueError):
                    android_bridge.normalize_file(
                        src, os.path.join(d, "o.wav"), percentile=bad)


class TestWaveformPeaks(unittest.TestCase):
    def test_bucket_count_and_range(self):
        from audiofx.pipeline import waveform_peaks

        peaks = waveform_peaks([0.5, -0.25, 0.0, 1.0] * 100, n_buckets=16)
        self.assertEqual(len(peaks), 16)
        self.assertTrue(all(0.0 <= p <= 1.0 for p in peaks))
        # Normalized so the loudest bucket hits 1.0.
        self.assertAlmostEqual(max(peaks), 1.0)

    def test_silence_is_flat(self):
        from audiofx.pipeline import waveform_peaks

        self.assertEqual(waveform_peaks([0.0] * 1000, n_buckets=8), [0.0] * 8)

    def test_empty(self):
        from audiofx.pipeline import waveform_peaks

        self.assertEqual(waveform_peaks([], n_buckets=8), [0.0] * 8)

    def test_bridge_returns_json(self):
        import json

        with tempfile.TemporaryDirectory() as d:
            src = os.path.join(d, "in.wav")
            write_wav(src, sine(440.0, 0.5, 22050), 22050)
            result = android_bridge.waveform_peaks(src, n_buckets=32)
            peaks = json.loads(result["peaks_json"])
            self.assertEqual(len(peaks), 32)
            self.assertTrue(all(0.0 <= p <= 1.0 for p in peaks))
            self.assertAlmostEqual(max(peaks), 1.0)
            self.assertEqual(result["bit_depth"], 16)
            self.assertEqual(result["sample_rate"], 22050)

    def test_bridge_missing_input_raises(self):
        with self.assertRaises(ValueError):
            android_bridge.waveform_peaks("/nonexistent/in.wav")


class TestWindowedProcess(unittest.TestCase):
    SR = 22050

    def _impulse_wav(self, d):
        # 2 s file with a single impulse at 0.6 s.
        n = 2 * self.SR
        samples = [0.0] * n
        samples[int(0.6 * self.SR)] = 1.0
        path = os.path.join(d, "in.wav")
        write_wav(path, samples, self.SR)
        return path

    def test_explicit_full_window_matches_default(self):
        with tempfile.TemporaryDirectory() as d:
            src = self._impulse_wav(d)
            dst1 = os.path.join(d, "o1.wav")
            dst2 = os.path.join(d, "o2.wav")
            android_bridge.process_file(src, dst1, warmth=0.5,
                                        reverb_preset="studio", echo=0.3)
            android_bridge.process_file(src, dst2, warmth=0.5,
                                        reverb_preset="studio", echo=0.3,
                                        start_sec=0, end_sec=2.0)
            out1, _ = read_wav_mono(dst1)
            out2, _ = read_wav_mono(dst2)
            self.assertEqual(out1, out2)

    def test_outside_window_untouched(self):
        with tempfile.TemporaryDirectory() as d:
            src = self._impulse_wav(d)
            dst = os.path.join(d, "o.wav")
            # Echo of the 0.6 s impulse lands at 0.92 s, inside [0.5, 1.5].
            android_bridge.process_file(src, dst, warmth=0.5,
                                        reverb_preset="studio", echo=1.0,
                                        start_sec=0.5, end_sec=1.5)
            out, _ = read_wav_mono(dst)
            orig, _ = read_wav_mono(src)
            s = int(0.5 * self.SR)
            e = int(1.5 * self.SR)
            fade = int(0.01 * self.SR)
            # Outside the window (beyond the crossfade zones) bit-exact.
            self.assertEqual(out[: s - fade], orig[: s - fade])
            self.assertEqual(out[e + fade :], orig[e + fade :])
            # ... but something changed inside the window.
            self.assertTrue(any(a != b for a, b in zip(out, orig)))

    def test_degenerate_window_falls_back_to_full(self):
        with tempfile.TemporaryDirectory() as d:
            src = self._impulse_wav(d)
            dst = os.path.join(d, "o.wav")
            result = android_bridge.process_file(src, dst, start_sec=1.5, end_sec=0.5)
            self.assertEqual(result["start_s"], 0)
            self.assertEqual(result["end_s"], 2.0)

    def test_splice_crossfade_blends(self):
        from audiofx.pipeline import splice_with_crossfade

        orig = [0.0] * 1000
        repl = [1.0] * 400
        out = splice_with_crossfade(orig, repl, 300, 700, 22050, fade_sec=0.01)
        self.assertEqual(len(out), 1000)
        # fade_n = min(220, 200, 300, 300) = 200
        self.assertEqual(out[: 300 - 200], orig[: 300 - 200])
        self.assertEqual(out[700 + 200 :], orig[700 + 200 :])
        # Crossfade ramps 0 -> 1 into the window and 1 -> 0 out of it.
        self.assertTrue(0.0 < out[300] < 1.0)
        self.assertTrue(out[300] < out[310])
        self.assertTrue(0.0 < out[699] < 1.0)
        self.assertTrue(out[699] < out[689])


class TestLiveChain(unittest.TestCase):
    SR = 48000
    BLOCK = 2048

    def _offline(self, samples, warmth, preset, echo):
        chain = EffectChain([Warmth(warmth), Reverb(preset), Echo(echo)])
        return chain.process(samples, self.SR)

    def test_blocks_match_offline(self):
        # Stateful block processing must equal the offline whole-signal
        # chain (offline reverb extends with a tail; compare the signal part).
        sig = sine(440, 0.5, self.SR, amplitude=0.3)
        for preset in ("studio", "bathroom", "cave"):
            live = LiveChain(0.6, preset, 0.4, self.SR,
                             block_size=self.BLOCK, channels=1)
            out = []
            for i in range(0, len(sig), self.BLOCK):
                out.extend(live.process_block(sig[i:i + self.BLOCK]))
            ref = self._offline(sig, 0.6, preset, 0.4)
            self.assertEqual(len(out), len(sig))
            for a, b in zip(out, ref[:len(sig)]):
                self.assertAlmostEqual(a, b, places=5)

    def test_bypass_params_match_offline(self):
        sig = sine(440, 0.25, self.SR, amplitude=0.3)
        live = LiveChain(0.0, "studio", 0.0, self.SR,
                         block_size=self.BLOCK, channels=1)
        out = []
        for i in range(0, len(sig), self.BLOCK):
            out.extend(live.process_block(sig[i:i + self.BLOCK]))
        ref = self._offline(sig, 0.0, "studio", 0.0)
        for a, b in zip(out, ref[:len(sig)]):
            self.assertAlmostEqual(a, b, places=5)

    def test_pcm_block_round_trip(self):
        live = LiveChain(0.5, "studio", 0.3, self.SR,
                         block_size=1024, channels=2)
        n = 1024
        pcm = struct.pack("<%dh" % (n * 2), *[1000] * (n * 2))
        wet = live.process_pcm_block(pcm)
        self.assertEqual(len(wet), n * 2)  # mono 16-bit
        vals = struct.unpack("<%dh" % n, wet)
        self.assertTrue(any(v != 0 for v in vals))

    def test_bridge_factory(self):
        chain = android_bridge.live_chain_create(0.5, "studio", 0.3, self.SR)
        self.assertIsInstance(chain, LiveChain)
        stereo = android_bridge.live_chain_create(0.5, "studio", 0.3, self.SR,
                                                  stereo=True)
        self.assertIsInstance(stereo, StereoLiveChain)
        with self.assertRaises(ValueError):
            android_bridge.live_chain_create(0.5, "arena", 0.3, self.SR)

    def test_stereo_blocks_match_offline_per_channel(self):
        # Each channel must equal the offline chain with the matching
        # reverb seed (left 1234, right 987654).
        sig_l = sine(440, 0.4, self.SR, amplitude=0.3)
        sig_r = sine(550, 0.4, self.SR, amplitude=0.25)
        live = StereoLiveChain(0.6, "concert_hall", 0.4, self.SR,
                               block_size=self.BLOCK)
        got_l, got_r = [], []
        for i in range(0, len(sig_l), self.BLOCK):
            sl = sig_l[i:i + self.BLOCK]
            sr = sig_r[i:i + self.BLOCK]
            n = len(sl)
            pcm = struct.pack("<%dh" % (2 * n),
                              *[int(round(s * 32767))
                                for pair in zip(sl, sr)
                                for s in pair])
            # process_pcm_block expects full blocks; pad the tail block.
            pad = self.BLOCK - n
            pcm += struct.pack("<%dh" % (2 * pad), *[0] * (2 * pad))
            wet = live.process_pcm_block(pcm)
            vals = struct.unpack("<%dh" % (2 * self.BLOCK), wet)
            got_l.extend(v / 32768.0 for v in vals[0:2 * n:2])
            got_r.extend(v / 32768.0 for v in vals[1:2 * n:2])
        for got, sig, seed in ((got_l, sig_l, 1234), (got_r, sig_r, 987654)):
            ref = EffectChain([Warmth(0.6), Reverb("concert_hall", seed=seed),
                               Echo(0.4)]).process(sig, self.SR)
            self.assertEqual(len(got), len(sig))
            # places=3: the PCM16 pack/unpack round trip adds ~1 LSB of noise.
            for a, b in zip(got, ref[:len(sig)]):
                self.assertAlmostEqual(a, b, places=3)

    def test_stereo_pcm_length_and_decorrelation(self):
        live = StereoLiveChain(0.5, "studio", 0.3, self.SR, block_size=1024)
        n = 1024
        pcm = struct.pack("<%dh" % (2 * n), *[1000] * (2 * n))
        wet = live.process_pcm_block(pcm)
        self.assertEqual(len(wet), 4 * n)
        vals = struct.unpack("<%dh" % (2 * n), wet)
        # Decorrelated reverb seeds: channels must differ.
        self.assertTrue(any(a != b for a, b in zip(vals[0::2], vals[1::2])))

    def test_trim_to_silence(self):
        with tempfile.TemporaryDirectory() as d:
            src = os.path.join(d, "in.wav")
            sig = sine(440, 1.0, self.SR, amplitude=0.5)
            write_wav(src, sig, self.SR)
            dst = os.path.join(d, "out.wav")
            res = android_bridge.trim_to_silence(src, dst, 0.2, 0.5)
            self.assertTrue(res["ok"])
            self.assertEqual(res["duration_s"], 1.0)
            out, _ = read_wav_mono(dst)
            self.assertEqual(len(out), len(sig))
            s, e = int(0.2 * self.SR), int(0.5 * self.SR)
            fade_n = int(0.005 * self.SR)
            # Deep inside the window: digital silence.
            self.assertTrue(all(v == 0.0 for v in out[s + fade_n + 100:e - fade_n - 100]))
            # Outside the window: untouched.
            for a, b in zip(out[:s - 100], sig[:s - 100]):
                self.assertAlmostEqual(a, b, places=4)
            for a, b in zip(out[e + 100:], sig[e + 100:]):
                self.assertAlmostEqual(a, b, places=4)

    def test_trim_open_end_means_to_end(self):
        with tempfile.TemporaryDirectory() as d:
            src = os.path.join(d, "in.wav")
            write_wav(src, sine(440, 1.0, self.SR), self.SR)
            dst = os.path.join(d, "out.wav")
            res = android_bridge.trim_to_silence(src, dst, 0.8, None)
            self.assertTrue(res["ok"])
            out, _ = read_wav_mono(dst)
            n = len(out)
            fade_n = int(0.005 * self.SR)
            # Past the fade: digital silence.
            self.assertTrue(all(v == 0.0 for v in out[int(0.85 * self.SR):n - fade_n]))
            # The edge fade ramps inward: boundary keeps the original value
            # (continuous), fading toward silence inside the window.
            sig_end, _ = read_wav_mono(src)
            self.assertAlmostEqual(out[n - 1], sig_end[n - 1], places=2)
            self.assertLess(abs(out[n - 1 - fade_n]), 0.02)

    def test_trim_bad_window_raises(self):
        with tempfile.TemporaryDirectory() as d:
            src = os.path.join(d, "in.wav")
            write_wav(src, sine(440, 0.5, self.SR), self.SR)
            with self.assertRaises(ValueError):
                android_bridge.trim_to_silence(src, os.path.join(d, "o.wav"),
                                              None, 0.3)
            with self.assertRaises(ValueError):
                android_bridge.trim_to_silence(src, os.path.join(d, "o.wav"),
                                              0.3, 0.2)

    def test_join_remove(self):
        with tempfile.TemporaryDirectory() as d:
            src = os.path.join(d, "in.wav")
            sig = sine(440, 1.0, self.SR, amplitude=0.5)
            write_wav(src, sig, self.SR)
            dst = os.path.join(d, "out.wav")
            res = android_bridge.join_remove(src, dst, 0.2, 0.5)
            self.assertTrue(res["ok"])
            self.assertAlmostEqual(res["removed_s"], 0.3, places=2)
            out, _ = read_wav_mono(dst)
            s, e = int(0.2 * self.SR), int(0.5 * self.SR)
            fade_n = int(0.005 * self.SR)
            # Length: removed window plus the crossfade overlap.
            self.assertEqual(len(out), len(sig) - (e - s) - fade_n)
            self.assertEqual(res["duration_s"],
                             round((len(sig) - (e - s) - fade_n) / self.SR, 2))
            # Head and tail survive (away from the junction crossfade).
            for a, b in zip(out[:s - fade_n - 500], sig[:s - fade_n - 500]):
                self.assertAlmostEqual(a, b, places=4)
            # out[j] for j >= s - fade_n + fade_n maps to sig[e + fade_n + (j - s)].
            for a, b in zip(out[s + 500:], sig[e + fade_n + 500:]):
                self.assertAlmostEqual(a, b, places=4)

    def test_join_whole_file_raises(self):
        with tempfile.TemporaryDirectory() as d:
            src = os.path.join(d, "in.wav")
            write_wav(src, sine(440, 0.5, self.SR), self.SR)
            with self.assertRaises(ValueError):
                android_bridge.join_remove(src, os.path.join(d, "o.wav"),
                                          0.0, None)


class TestRenderChain(unittest.TestCase):
    """render_chain: fold an op list over pristine in float, single write."""

    SR = 22050

    def _pristine(self, d, name="pristine.wav", seconds=1.0, amplitude=0.4):
        p = os.path.join(d, name)
        write_wav(p, sine(440, seconds, self.SR, amplitude=amplitude), self.SR)
        return p

    def test_dsp_version(self):
        # Bumped "1" -> "2" by DSP-2 (the mastering op), in the same
        # commit as LibraryDb.DSP_VERSION.
        self.assertEqual(android_bridge.DSP_VERSION, "2")

    def test_empty_chain_reproduces_pristine(self):
        with tempfile.TemporaryDirectory() as d:
            src = self._pristine(d)
            dst = os.path.join(d, "out.wav")
            res = android_bridge.render_chain(src, "[]", dst)
            self.assertTrue(res["ok"])
            self.assertEqual(res["output_path"], dst)
            self.assertEqual(res["sample_rate"], self.SR)
            self.assertEqual(res["duration_s"], 1.0)
            orig, _ = read_wav_mono(src)
            out, _ = read_wav_mono(dst)
            self.assertEqual(out, orig)  # bit-identical: no DSP ran

    def test_chain_matches_sequential_file_application(self):
        # fx -> normalize -> trim -> join, then a windowed fx on the
        # shortened buffer (window refers to the current buffer, like
        # stacked application). Compared against the four existing
        # file-level functions chained through temp files, per sample.
        # Not bit-identical by design: the file path quantizes to 16-bit
        # after every op, while render_chain stays in float with a single
        # final write (no generational loss). The only per-sample
        # difference is 16-bit quantization noise, small even after the
        # normalize gain amplifies it.
        with tempfile.TemporaryDirectory() as d:
            src = self._pristine(d, seconds=2.0, amplitude=0.25)
            ops = [
                {"op": "fx", "params": {"warmth": 0.6, "reverb_preset": "studio",
                                        "echo": 0.4}},
                {"op": "normalize", "params": {"target_db": -1.0,
                                               "percentile": 99.0}},
                {"op": "trim", "params": {"start_sec": 0.3, "end_sec": 0.6}},
                {"op": "join", "params": {"start_sec": 1.2, "end_sec": 1.5}},
                {"op": "fx", "params": {"warmth": 0.4, "reverb_preset": "bathroom",
                                        "echo": 0.2, "start_sec": 0.5,
                                        "end_sec": 1.0}},
            ]
            dst = os.path.join(d, "chain.wav")
            res = android_bridge.render_chain(src, json.dumps(ops), dst)
            self.assertTrue(res["ok"])
            self.assertEqual(res["sample_rate"], self.SR)
            chain_out, _ = read_wav_mono(dst)
            # Sequential through the existing file-level functions.
            t1 = os.path.join(d, "t1.wav")
            t2 = os.path.join(d, "t2.wav")
            t3 = os.path.join(d, "t3.wav")
            t4 = os.path.join(d, "t4.wav")
            t5 = os.path.join(d, "t5.wav")
            android_bridge.process_file(src, t1, warmth=0.6,
                                        reverb_preset="studio", echo=0.4)
            android_bridge.normalize_file(t1, t2, target_db=-1.0,
                                          percentile=99.0)
            android_bridge.trim_to_silence(t2, t3, 0.3, 0.6)
            android_bridge.join_remove(t3, t4, 1.2, 1.5)
            android_bridge.process_file(t4, t5, warmth=0.4,
                                        reverb_preset="bathroom", echo=0.2,
                                        start_sec=0.5, end_sec=1.0)
            seq_out, _ = read_wav_mono(t5)
            self.assertEqual(len(chain_out), len(seq_out))
            self.assertEqual(res["duration_s"],
                             round(len(chain_out) / self.SR, 2))
            max_diff = 0.0
            for a, b in zip(chain_out, seq_out):
                max_diff = max(max_diff, abs(a - b))
                self.assertAlmostEqual(a, b, delta=8.0 / 32768)
            self.assertLess(max_diff, 8.0 / 32768)

    def test_percentile_param_honored(self):
        # Single-op chain: one final write in both paths -> bit-identical.
        with tempfile.TemporaryDirectory() as d:
            src = self._pristine(d)
            dst = os.path.join(d, "chain.wav")
            android_bridge.render_chain(
                src,
                json.dumps([{"op": "normalize",
                             "params": {"target_db": -1.0,
                                        "percentile": 50.0}}]),
                dst)
            ref = os.path.join(d, "ref.wav")
            android_bridge.normalize_file(src, ref, percentile=50.0)
            chain_out, _ = read_wav_mono(dst)
            ref_out, _ = read_wav_mono(ref)
            self.assertEqual(chain_out, ref_out)

    def test_livefx_op_raises(self):
        with tempfile.TemporaryDirectory() as d:
            src = self._pristine(d)
            ops = [{"op": "livefx", "params": {"warmth": 0.5,
                                               "reverb_preset": "studio",
                                               "echo": 0.3}}]
            with self.assertRaises(ValueError):
                android_bridge.render_chain(src, json.dumps(ops),
                                            os.path.join(d, "o.wav"))

    def test_unknown_op_raises(self):
        with tempfile.TemporaryDirectory() as d:
            src = self._pristine(d)
            ops = [{"op": "reverb", "params": {}}]
            with self.assertRaises(ValueError):
                android_bridge.render_chain(src, json.dumps(ops),
                                            os.path.join(d, "o.wav"))

    def test_windowed_fx_matches_windowed_process_file(self):
        # Single windowed fx op: one final write in both paths ->
        # bit-identical to windowed process_file.
        with tempfile.TemporaryDirectory() as d:
            src = self._pristine(d, seconds=2.0)
            ops = [{"op": "fx", "params": {"warmth": 0.5,
                                           "reverb_preset": "studio",
                                           "echo": 0.5, "start_sec": 0.4,
                                           "end_sec": 1.2}}]
            dst = os.path.join(d, "chain.wav")
            android_bridge.render_chain(src, json.dumps(ops), dst)
            ref = os.path.join(d, "ref.wav")
            android_bridge.process_file(src, ref, warmth=0.5,
                                        reverb_preset="studio", echo=0.5,
                                        start_sec=0.4, end_sec=1.2)
            chain_out, _ = read_wav_mono(dst)
            ref_out, _ = read_wav_mono(ref)
            self.assertEqual(chain_out, ref_out)

    def test_bad_normalize_param_raises(self):
        # Param validation matches the file-level functions.
        with tempfile.TemporaryDirectory() as d:
            src = self._pristine(d)
            ops = [{"op": "normalize",
                    "params": {"target_db": -1.0, "percentile": 0.0}}]
            with self.assertRaises(ValueError):
                android_bridge.render_chain(src, json.dumps(ops),
                                            os.path.join(d, "o.wav"))

    def test_bad_trim_window_raises(self):
        with tempfile.TemporaryDirectory() as d:
            src = self._pristine(d)
            ops = [{"op": "trim", "params": {"start_sec": 0.5,
                                             "end_sec": 0.2}}]
            with self.assertRaises(ValueError):
                android_bridge.render_chain(src, json.dumps(ops),
                                            os.path.join(d, "o.wav"))

    def test_join_whole_buffer_raises(self):
        with tempfile.TemporaryDirectory() as d:
            src = self._pristine(d)
            ops = [{"op": "join", "params": {"start_sec": 0.0,
                                             "end_sec": None}}]
            with self.assertRaises(ValueError):
                android_bridge.render_chain(src, json.dumps(ops),
                                            os.path.join(d, "o.wav"))

    def test_missing_pristine_raises(self):
        with tempfile.TemporaryDirectory() as d:
            with self.assertRaises(ValueError):
                android_bridge.render_chain(os.path.join(d, "nope.wav"), "[]",
                                            os.path.join(d, "o.wav"))

    def test_bad_ops_json_raises(self):
        with tempfile.TemporaryDirectory() as d:
            src = self._pristine(d)
            for bad in ("not json", '{"op": "fx"}', '[42]', 'null'):
                with self.assertRaises(ValueError):
                    android_bridge.render_chain(src, bad,
                                                os.path.join(d, "o.wav"))


class TestDiagnoseFileQuality(unittest.TestCase):
    """diagnose_file quality_engine shapes (DSP-5, Tier 1 only)."""

    SR = 8000

    def _fixture(self, d, name="in.wav", sig=None):
        p = os.path.join(d, name)
        write_wav(p, sig if sig is not None else sine(440, 0.5, self.SR),
                  self.SR)
        return p

    def test_off_output_byte_identical_to_default(self):
        with tempfile.TemporaryDirectory() as d:
            src = self._fixture(d)
            default = android_bridge.diagnose_file(src)
            off = android_bridge.diagnose_file(src, quality_engine="off")
            self.assertEqual(off, default)
            self.assertIsInstance(json.loads(off), list)

    def test_proxy_returns_object_with_quality(self):
        with tempfile.TemporaryDirectory() as d:
            src = self._fixture(d)
            off = json.loads(android_bridge.diagnose_file(
                src, quality_engine="off"))
            parsed = json.loads(android_bridge.diagnose_file(
                src, quality_engine="proxy"))
            self.assertIsInstance(parsed, dict)
            self.assertEqual(parsed["findings"], off)
            quality = parsed["quality"]
            self.assertIsInstance(quality, dict)
            self.assertEqual(quality["engine"], "proxy")
            for key in ("ovrl", "sig", "bak"):
                self.assertGreaterEqual(quality[key], 1.0)
                self.assertLessEqual(quality[key], 5.0)

    def test_proxy_silence_quality_is_null(self):
        with tempfile.TemporaryDirectory() as d:
            src = self._fixture(d, sig=[0.0] * self.SR)
            parsed = json.loads(android_bridge.diagnose_file(
                src, quality_engine="proxy"))
            self.assertIsInstance(parsed, dict)
            self.assertIsNone(parsed["quality"])
            self.assertIsInstance(parsed["findings"], list)

    def test_dnsmos_behaves_as_off_in_v1(self):
        # Tier 2 does not exist in v1: "dnsmos" must NOT behave as
        # "proxy" -- it returns the plain findings array, as "off".
        with tempfile.TemporaryDirectory() as d:
            src = self._fixture(d)
            off = android_bridge.diagnose_file(src, quality_engine="off")
            got = android_bridge.diagnose_file(
                src, quality_engine="dnsmos")
            self.assertEqual(got, off)
            self.assertIsInstance(json.loads(got), list)


class _FakeWav:
    """Stub for wave.Wave_read: reports header params, never returns frames."""

    def __init__(self, n_channels, sampwidth, sample_rate, n_frames):
        self._n_channels = n_channels
        self._sampwidth = sampwidth
        self._sample_rate = sample_rate
        self._n_frames = n_frames
        self.readframes_called = False

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False

    def getnchannels(self):
        return self._n_channels

    def getsampwidth(self):
        return self._sampwidth

    def getframerate(self):
        return self._sample_rate

    def getnframes(self):
        return self._n_frames

    def readframes(self, n):
        self.readframes_called = True
        return b""


class TestWavSizeGuard(unittest.TestCase):
    SR = 44100

    def test_check_wav_size_accepts_normal_file(self):
        # 3 minutes at 44.1 kHz: under both the duration and memory caps.
        pipeline._check_wav_size(3 * 60 * self.SR, self.SR)

    def test_check_wav_size_rejects_over_duration(self):
        # 3 hours at 44.1 kHz: over the 2 hour cap.
        with self.assertRaises(ValueError):
            pipeline._check_wav_size(3 * 3600 * self.SR, self.SR)

    def test_check_wav_size_rejects_over_bytes(self):
        # 300M frames at 44.1 kHz: ~1.9 hours (under duration) but ~2.4 GB
        # of float64, over the 2 GB buffer cap.
        with self.assertRaises(ValueError):
            pipeline._check_wav_size(300000000, self.SR)

    def test_read_wav_mono_rejects_huge_header_before_readframes(self):
        fake = _FakeWav(1, 2, self.SR, 10000000000)
        with unittest.mock.patch("audiofx.pipeline.wave") as mock_wave:
            mock_wave.open.return_value = fake
            with self.assertRaises(ValueError):
                read_wav_mono("/nonexistent/huge.wav")
        self.assertFalse(fake.readframes_called)

    def test_small_real_wav_still_loads(self):
        sig = sine(440, 0.01, self.SR)
        with tempfile.TemporaryDirectory() as d:
            p = os.path.join(d, "tiny.wav")
            write_wav(p, sig, self.SR)
            back, sr = read_wav_mono(p)
        self.assertEqual(sr, self.SR)
        self.assertEqual(len(back), len(sig))


class TestPitchShiftEngineDispatch(unittest.TestCase):
    """pitch_shift op engine dispatch (DSP-1): params.engine selects
    the WSOLA (default) or formant engine in _apply_op."""

    SR = 22050

    def _pristine(self, d, name="pristine.wav"):
        # A harmonic stack (not a lone sine) so the two engines have
        # distinguishable work to do.
        sig = []
        base = sine(220, 1.0, self.SR, amplitude=0.2)
        for i, s in enumerate(base):
            v = s
            v += 0.5 * 0.2 * math.sin(2.0 * math.pi * 440 * i / self.SR)
            v += 0.25 * 0.2 * math.sin(2.0 * math.pi * 660 * i / self.SR)
            sig.append(v)
        p = os.path.join(d, name)
        write_wav(p, sig, self.SR)
        return p

    def _render(self, d, src, params, name):
        dst = os.path.join(d, name)
        android_bridge.render_chain(
            src, json.dumps([{"op": "pitch_shift", "params": params}]), dst)
        out, _ = read_wav_mono(dst)
        return out

    def _direct(self, d, src, fn, name):
        orig, sr = read_wav_mono(src)
        ref = os.path.join(d, name)
        write_wav(ref, fn(orig, sr), sr)
        out, _ = read_wav_mono(ref)
        return out

    def test_op_without_engine_is_bit_identical_to_wsola(self):
        from audiofx import pitch as pitch_mod
        with tempfile.TemporaryDirectory() as d:
            src = self._pristine(d)
            chained = self._render(d, src, {"semitones": 5.0}, "chain.wav")
            direct = self._direct(
                d, src,
                lambda s, sr: pitch_mod.pitch_shift(s, sr, 5.0), "ref.wav")
            self.assertEqual(chained, direct)
            explicit = self._render(
                d, src, {"semitones": 5.0, "engine": "wsola"}, "wsola.wav")
            self.assertEqual(chained, explicit)

    def test_op_with_formant_engine_uses_formant_path(self):
        from audiofx import pitch as pitch_mod
        with tempfile.TemporaryDirectory() as d:
            src = self._pristine(d)
            chained = self._render(
                d, src, {"semitones": 5.0, "engine": "formant"},
                "chain.wav")
            direct = self._direct(
                d, src,
                lambda s, sr: pitch_mod.pitch_shift_formant(s, sr, 5.0),
                "ref.wav")
            self.assertEqual(chained, direct)

    def test_op_with_unknown_engine_raises(self):
        with tempfile.TemporaryDirectory() as d:
            src = self._pristine(d)
            with self.assertRaises(ValueError):
                self._render(
                    d, src, {"semitones": 5.0, "engine": "bogus"},
                    "chain.wav")


if __name__ == "__main__":
    unittest.main()
