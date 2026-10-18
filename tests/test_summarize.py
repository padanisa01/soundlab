"""Tests for audiofx.summarize (no network: urlopen is stubbed)."""

import io
import json
import os
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..",
                               "app", "src", "main", "python"))
from audiofx import summarize


class FakeResp(object):
    def __init__(self, payload):
        self._payload = payload

    def read(self):
        return json.dumps(self._payload).encode("utf-8")

    def __enter__(self):
        return self

    def __exit__(self, *args):
        return False


class SummarizeTest(unittest.TestCase):
    FINDINGS = json.dumps([
        {"id": "clipping", "severity": "warn", "title": "Clipping detected",
         "detail": "38 clipped samples."},
        {"id": "level", "severity": "ok", "title": "Healthy level",
         "detail": "Peak at -3 dBFS."},
    ])

    def test_build_prompt_frames_profile(self):
        prompt = summarize.build_prompt(
            json.loads(self.FINDINGS), "singing")
        self.assertIn("vocal singing performance", prompt)
        self.assertIn("ISSUE: Clipping detected", prompt)
        self.assertIn("OK: Healthy level", prompt)

    def test_build_prompt_unknown_profile_falls_back(self):
        prompt = summarize.build_prompt(json.loads(self.FINDINGS), "nope")
        self.assertIn("podcast episode", prompt)

    def test_empty_key_returns_empty(self):
        self.assertEqual(summarize.summarize(self.FINDINGS, "podcast", ""), "")

    def test_bad_json_returns_empty(self):
        self.assertEqual(summarize.summarize("not json", "podcast", "k"), "")

    def test_success_path(self):
        real = summarize.urllib.request.urlopen
        summarize.urllib.request.urlopen = lambda req, timeout=None: FakeResp({
            "choices": [{"message": {"content": "  Your take sounds good. "}}]})
        try:
            out = summarize.summarize(self.FINDINGS, "podcast", "k")
        finally:
            summarize.urllib.request.urlopen = real
        self.assertEqual(out, "Your take sounds good.")

    def test_think_block_stripped(self):
        self.assertEqual(
            summarize._strip_thinking(
                "<think>hmm</think>Plain summary here."),
            "Plain summary here.")

    def test_network_error_returns_empty(self):
        real = summarize.urllib.request.urlopen

        def boom(req, timeout=None):
            raise IOError("no route")

        summarize.urllib.request.urlopen = boom
        try:
            out = summarize.summarize(self.FINDINGS, "podcast", "k")
        finally:
            summarize.urllib.request.urlopen = real
        self.assertEqual(out, "")


if __name__ == "__main__":
    unittest.main()
