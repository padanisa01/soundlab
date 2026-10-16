"""Unit tests for MP3 encoding (audiofx.pipeline.encode_mp3 and
audiofx.android_bridge.encode_wav_to_mp3). Skipped when lameenc is absent."""

import math
import os
import sys
import tempfile
import unittest

sys.path.insert(
    0, os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "python")
)

from audiofx import android_bridge
from audiofx.pipeline import encode_mp3, write_wav

try:
    import lameenc  # noqa: F401

    HAVE_LAMEENC = True
except ImportError:
    HAVE_LAMEENC = False


def sine(freq_hz, seconds, sample_rate, amplitude=0.5):
    return [
        amplitude * math.sin(2.0 * math.pi * freq_hz * i / sample_rate)
        for i in range(int(seconds * sample_rate))
    ]


@unittest.skipUnless(HAVE_LAMEENC, "lameenc not installed")
class TestEncodeMp3(unittest.TestCase):
    def test_produces_valid_mp3_frames(self):
        samples = sine(440.0, 1.0, 44100)
        data = encode_mp3(samples, 44100)
        self.assertTrue(len(data) > 1000)
        # First byte of an MP3 frame is always 0xFF, top 3 bits of the
        # second byte are set (frame sync).
        self.assertEqual(data[0], 0xFF)
        self.assertEqual(data[1] & 0xE0, 0xE0)

    def test_silence_encodes(self):
        data = encode_mp3([0.0] * 4410, 44100)
        self.assertTrue(len(data) > 0)
        self.assertEqual(data[0], 0xFF)

    def test_clips_out_of_range(self):
        data = encode_mp3([2.0, -2.0] * 2205, 44100)
        self.assertEqual(data[0], 0xFF)


@unittest.skipUnless(HAVE_LAMEENC, "lameenc not installed")
class TestEncodeWavToMp3(unittest.TestCase):
    def test_roundtrip(self):
        with tempfile.TemporaryDirectory() as d:
            wav_path = os.path.join(d, "in.wav")
            mp3_path = os.path.join(d, "out.mp3")
            write_wav(wav_path, sine(440.0, 0.5, 22050), 22050)
            result = android_bridge.encode_wav_to_mp3(wav_path, mp3_path)
            self.assertTrue(result["ok"])
            self.assertEqual(result["sample_rate"], 22050)
            self.assertTrue(os.path.getsize(mp3_path) > 0)
            with open(mp3_path, "rb") as f:
                head = f.read(2)
            self.assertEqual(head[0], 0xFF)
            self.assertEqual(head[1] & 0xE0, 0xE0)

    def test_missing_input(self):
        with self.assertRaises(ValueError):
            android_bridge.encode_wav_to_mp3("/nonexistent/in.wav", "/tmp/out.mp3")


if __name__ == "__main__":
    unittest.main()
