"""Unit tests for audiofx.pipeline and audiofx.android_bridge."""

import math
import os
import struct
import sys
import tempfile
import unittest
import wave

sys.path.insert(
    0, os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "python")
)

from audiofx import Echo, EffectChain, Reverb, Warmth, read_wav_mono, write_wav
from audiofx import android_bridge
from audiofx.live import LiveChain


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
        with self.assertRaises(ValueError):
            android_bridge.live_chain_create(0.5, "arena", 0.3, self.SR)


if __name__ == "__main__":
    unittest.main()
