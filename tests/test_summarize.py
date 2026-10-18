"""Tests for audiofx.summarize (pure functions, no network)."""

import json
import os
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..",
                               "app", "src", "main", "python"))
from audiofx import summarize


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

    def test_request_body_shape(self):
        body = json.loads(summarize.build_request_body(
            self.FINDINGS, "podcast"))
        self.assertEqual(body["model"], "qwen/qwen3.8-27b")
        self.assertEqual(len(body["messages"]), 2)
        self.assertEqual(body["messages"][0]["role"], "system")
        self.assertIn("vocal singing performance",
                      json.loads(summarize.build_request_body(
                          self.FINDINGS, "singing"))["messages"][1]["content"])

    def test_request_body_bad_input(self):
        self.assertEqual(summarize.build_request_body("not json", "x"), "")
        self.assertEqual(summarize.build_request_body("[]", "x"), "")

    def test_parse_response(self):
        resp = json.dumps({"choices": [
            {"message": {"content": "  <think>hmm</think>Plain summary. "}}]})
        self.assertEqual(summarize.parse_response(resp), "Plain summary.")

    def test_parse_response_bad_input(self):
        self.assertEqual(summarize.parse_response("garbage"), "")
        self.assertEqual(summarize.parse_response("{}"), "")
        self.assertEqual(
            summarize.parse_response(json.dumps({"choices": []})), "")

    def test_think_block_stripped(self):
        self.assertEqual(
            summarize._strip_thinking(
                "<think>hmm</think>Plain summary here."),
            "Plain summary here.")


if __name__ == "__main__":
    unittest.main()
