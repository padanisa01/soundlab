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

from audiofx import Echo, EffectChain, Warmth, read_wav_mono, write_wav
from audiofx import android_bridge


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


if __name__ == "__main__":
    unittest.main()
