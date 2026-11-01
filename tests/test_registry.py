"""Unit tests for the Python effect registry (OO-1, design 4.1).

The registry (audiofx.registry.REGISTRY) and the checked-in manifest
(audiofx/ops_manifest.json) are the single source of truth for the op
list: registry keys == manifest names == the 20 ops android_bridge
renders. android_bridge._apply_op is a thin adapter over
registry.apply_op and keeps its historical error surface: unknown ops
raise ValueError("unknown op %r"), livefx raises its exact ValueError,
and tune_up still expands sub-ops (through the registry) and forbids
nesting.
"""

import json
import math
import os
import sys
import unittest

sys.path.insert(
    0, os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "python")
)

from audiofx import android_bridge, registry, repair

MANIFEST_PATH = os.path.join(
    os.path.dirname(__file__), "..", "app", "src", "main", "python",
    "audiofx", "ops_manifest.json")

EXPECTED_OPS = {
    "fx", "pitch_shift", "mastering", "normalize", "trim", "join",
    "dc_remove", "fade_ends", "silence_trim", "hum_notch", "declip",
    "denoise", "deplosive", "deess", "level", "loudness", "eq_balance",
    "tune_up", "punch_in", "livefx",
}

# Java MainActivity.isRenderableOp: every op except livefx (livefx and
# the Java-only display label "fork" are not renderable; "fork" is not
# a Python op at all).
EXPECTED_NON_RENDERABLE = {"livefx"}

SR = 22050


def _load_manifest():
    with open(MANIFEST_PATH, encoding="utf-8") as f:
        data = json.load(f)
    if isinstance(data, dict):
        return data["ops"]
    return data


def _sine(freq_hz=220.0, seconds=0.25, amplitude=0.4, dc=0.0):
    return [
        dc + amplitude * math.sin(2.0 * math.pi * freq_hz * i / SR)
        for i in range(int(seconds * SR))
    ]


def _as_list(samples):
    return [float(s) for s in samples]


class TestRegistryCompleteness(unittest.TestCase):
    def test_registry_keys_equal_manifest_names_equal_expected_ops(self):
        manifest_names = {entry["name"] for entry in _load_manifest()}
        self.assertEqual(set(registry.REGISTRY), EXPECTED_OPS)
        self.assertEqual(manifest_names, EXPECTED_OPS)
        self.assertEqual(set(registry.REGISTRY), manifest_names)

    def test_every_spec_apply_is_callable_and_named(self):
        for name, spec in registry.REGISTRY.items():
            self.assertEqual(spec.name, name)
            self.assertTrue(callable(spec.apply), name)
            self.assertTrue(spec.summary, name)

    def test_renderable_flags_match_java_list(self):
        for name, spec in registry.REGISTRY.items():
            self.assertEqual(
                spec.renderable, name not in EXPECTED_NON_RENDERABLE, name)
        for entry in _load_manifest():
            self.assertEqual(
                entry["renderable"],
                entry["name"] not in EXPECTED_NON_RENDERABLE, entry["name"])

    def test_registry_defaults_match_manifest_defaults(self):
        manifest = {e["name"]: e for e in _load_manifest()}
        for name, spec in registry.REGISTRY.items():
            self.assertEqual(dict(spec.defaults), manifest[name]["defaults"],
                             name)


class TestRegistryDispatch(unittest.TestCase):
    def test_dc_remove_matches_direct_repair_call(self):
        sig = _sine(dc=0.2)
        via_registry = registry.apply_op(list(sig), SR, "dc_remove", {}, 0)
        direct = repair.dc_remove(list(sig))
        self.assertEqual(_as_list(via_registry), _as_list(direct))

    def test_fade_ends_matches_direct_repair_call_with_default(self):
        sig = _sine()
        via_registry = registry.apply_op(list(sig), SR, "fade_ends", {}, 0)
        direct = repair.fade_ends(list(sig), SR, fade_sec=0.02)
        self.assertEqual(_as_list(via_registry), _as_list(direct))

    def test_normalize_matches_bridge_helper(self):
        sig = _sine(amplitude=0.25)
        via_registry = registry.apply_op(list(sig), SR, "normalize", {}, 0)
        direct, _info = android_bridge._apply_normalize(list(sig), SR)
        self.assertEqual(_as_list(via_registry), _as_list(direct))

    def test_registry_unknown_op_raises_keyerror_naming_op(self):
        with self.assertRaises(KeyError) as ctx:
            registry.apply_op(_sine(), SR, "no_such_op", {}, 0)
        self.assertIn("no_such_op", str(ctx.exception))


class TestBridgeSurfaceUnchanged(unittest.TestCase):
    def test_unknown_op_through_bridge_raises_valueerror_naming_op(self):
        with self.assertRaises(ValueError) as ctx:
            android_bridge._apply_op(_sine(), SR, "no_such_op", {}, 0)
        self.assertEqual(str(ctx.exception), "unknown op 'no_such_op'")

    def test_unknown_op_through_render_chain_raises_valueerror(self):
        import tempfile
        from audiofx.pipeline import write_wav
        with tempfile.TemporaryDirectory() as d:
            src = os.path.join(d, "in.wav")
            dst = os.path.join(d, "out.wav")
            write_wav(src, _sine(), SR)
            with self.assertRaises(ValueError) as ctx:
                android_bridge.render_chain(
                    src, json.dumps([{"op": "no_such_op", "params": {}}]), dst)
            self.assertEqual(str(ctx.exception), "unknown op 'no_such_op'")

    def test_livefx_raises_exact_valueerror(self):
        with self.assertRaises(ValueError) as ctx:
            android_bridge._apply_op(_sine(), SR, "livefx", {}, 0)
        self.assertEqual(
            str(ctx.exception),
            "livefx ops are never re-rendered in render_chain")
        with self.assertRaises(ValueError) as ctx2:
            registry.apply_op(_sine(), SR, "livefx", {}, 0)
        self.assertEqual(str(ctx2.exception), str(ctx.exception))

    def test_pitch_shift_bad_engine_still_valueerror(self):
        with self.assertRaises(ValueError) as ctx:
            android_bridge._apply_op(
                _sine(), SR, "pitch_shift", {"engine": "bogus"}, 0)
        self.assertIn("unknown pitch_shift engine", str(ctx.exception))


class TestTuneUpThroughRegistry(unittest.TestCase):
    def test_tune_up_expands_sub_ops(self):
        sig = _sine(dc=0.2)
        params = {"fixes": [{"op": "dc_remove", "params": {}}]}
        via_bridge = android_bridge._apply_op(list(sig), SR, "tune_up",
                                              params, 0)
        direct = repair.dc_remove(list(sig))
        self.assertEqual(_as_list(via_bridge), _as_list(direct))

    def test_tune_up_rejects_nesting(self):
        params = {"fixes": [{"op": "tune_up", "params": {"fixes": []}}]}
        with self.assertRaises(ValueError) as ctx:
            android_bridge._apply_op(_sine(), SR, "tune_up", params, 0)
        self.assertEqual(str(ctx.exception), "tune_up ops cannot nest")

    def test_tune_up_unknown_sub_op_raises_valueerror_naming_sub_op(self):
        params = {"fixes": [{"op": "no_such_op", "params": {}}]}
        with self.assertRaises(ValueError) as ctx:
            android_bridge._apply_op(_sine(), SR, "tune_up", params, 0)
        self.assertEqual(str(ctx.exception), "unknown op 'no_such_op'")


if __name__ == "__main__":
    unittest.main()
