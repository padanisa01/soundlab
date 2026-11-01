#!/usr/bin/env python3
"""Golden-render baseline for the OO redesign (OO-0).

Renders a fixed, deterministic fixture set through
``audiofx.android_bridge.render_chain`` with the CURRENT code and
records the SHA-256 of every output WAV. Re-running on unchanged DSP
code must reproduce identical hashes; after any refactor (registry,
model, ...) a mismatch proves a behavior change.

Usage (from the repo root):

    python3 tools/golden_render.py [--out DIR] [--check hashes.txt]

--out DIR    write hashes.txt into DIR (default: print to stdout).
--check FILE compare fresh renders against a saved hashes.txt;
             exit 0 only if every fixture line matches (comment
             lines starting with '#' are ignored).

Only the repo's own Python code is used (app/src/main/python is put
on sys.path); no dependencies beyond what the DSP module itself
already needs. Fixture WAVs (pristine + punch-in take) are generated
deterministically in a temp dir and are NOT kept: they are fully
described by build_pristine()/build_take() below and their own
hashes are recorded in the header comments.
"""

import argparse
import hashlib
import json
import math
import os
import random
import subprocess
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PY_SRC = os.path.join(ROOT, "app", "src", "main", "python")
sys.path.insert(0, PY_SRC)

from audiofx import android_bridge, mastering  # noqa: E402
from audiofx.pipeline import write_wav  # noqa: E402

SAMPLE_RATE = 22050


# ---------------------------------------------------------------------------
# Deterministic fixture signals
# ---------------------------------------------------------------------------

def build_pristine():
    """~3.6 s mono signal at SAMPLE_RATE exercising every op family:

    - 0.0-0.5 s  near-silence: -66 dB deterministic noise (below the
                 silence_trim threshold, but non-zero so denoise has
                 a real noise profile to learn and fade_ends has
                 samples to fade -- digital zero would make both
                 ops identities)
    - 0.5-1.5 s  body: 220+440 Hz tones + 60 Hz hum + DC offset +
                 a 6.5 kHz component + faint deterministic noise
    - 1.5-2.0 s  quiet gap: near-noise-floor tone + noise
                 (denoise noise-profile frames, silence region)
    - 2.0-2.6 s  loud/clipped: over-unity sum hard-clipped to +/-1
                 (declip flat tops, limiter-adjacent levels)
    - 2.6-3.1 s  body again at 330 Hz
    - 3.1-3.6 s  near-silence tail (same -66 dB noise)
    """
    sr = SAMPLE_RATE
    rng = random.Random(20261101)
    out = []

    def add(seconds, fn):
        n = int(seconds * sr)
        base = len(out)
        for i in range(n):
            t = (base + i) / sr
            out.append(fn(t, rng))

    add(0.5, lambda t, r: 0.0005 * (r.random() * 2.0 - 1.0))
    add(1.0, lambda t, r: (
        0.25 * math.sin(2.0 * math.pi * 220.0 * t)
        + 0.15 * math.sin(2.0 * math.pi * 440.0 * t)
        + 0.08 * math.sin(2.0 * math.pi * 60.0 * t)
        + 0.05 * math.sin(2.0 * math.pi * 6500.0 * t)
        + 0.03  # DC offset for dc_remove
        + 0.004 * (r.random() * 2.0 - 1.0)))
    add(0.5, lambda t, r: (
        0.004 * math.sin(2.0 * math.pi * 220.0 * t)
        + 0.003 * (r.random() * 2.0 - 1.0)))
    add(0.6, lambda t, r: max(-1.0, min(1.0, 1.3 * (
        0.9 * math.sin(2.0 * math.pi * 220.0 * t)
        + 0.5 * math.sin(2.0 * math.pi * 110.0 * t)))))
    add(0.5, lambda t, r: (
        0.25 * math.sin(2.0 * math.pi * 330.0 * t)
        + 0.12 * math.sin(2.0 * math.pi * 660.0 * t)
        + 0.004 * (r.random() * 2.0 - 1.0)))
    add(0.5, lambda t, r: 0.0005 * (r.random() * 2.0 - 1.0))
    return out


def build_take():
    """0.4 s punch-in take: 550 Hz + 1100 Hz tones at SAMPLE_RATE."""
    sr = SAMPLE_RATE
    return [
        0.5 * math.sin(2.0 * math.pi * 550.0 * i / sr)
        + 0.2 * math.sin(2.0 * math.pi * 1100.0 * i / sr)
        for i in range(int(0.4 * sr))
    ]


# ---------------------------------------------------------------------------
# Fixture chains (param shapes mirror tests/test_pipeline.py,
# tests/test_mastering.py, tests/test_multitrack.py, tests/test_diagnose.py)
# ---------------------------------------------------------------------------

def fixture_chains(take_path):
    bands, dyn_eq = mastering.preset_params("gentle")
    return [
        ("empty", []),
        ("fx", [{"op": "fx", "params": {
            "warmth": 0.7, "reverb_preset": "concert_hall",
            "echo": 0.4}}]),
        ("pitch_shift_wsola", [{"op": "pitch_shift", "params": {
            "semitones": 3.0, "engine": "wsola"}}]),
        ("pitch_shift_formant", [{"op": "pitch_shift", "params": {
            "semitones": 3.0, "engine": "formant"}}]),
        ("mastering", [{"op": "mastering", "params": {
            "preset": "gentle", "bands": bands, "dyn_eq": dyn_eq}}]),
        ("normalize", [{"op": "normalize", "params": {
            "target_db": -3.0, "percentile": 95.0}}]),
        ("trim", [{"op": "trim", "params": {
            "start_sec": 0.5, "end_sec": 1.0}}]),
        ("join", [{"op": "join", "params": {
            "start_sec": 0.5, "end_sec": 0.8}}]),
        ("dc_remove", [{"op": "dc_remove", "params": {}}]),
        ("fade_ends", [{"op": "fade_ends", "params": {
            "fade_sec": 0.05}}]),
        ("silence_trim", [{"op": "silence_trim", "params": {
            "threshold_db": -40.0, "min_silence_sec": 0.3}}]),
        ("hum_notch", [{"op": "hum_notch", "params": {
            "freq_hz": 50.0}}]),
        ("declip", [{"op": "declip", "params": {}}]),
        ("denoise", [{"op": "denoise", "params": {
            "strength": 1.2}}]),
        ("deplosive", [{"op": "deplosive", "params": {}}]),
        ("deess", [{"op": "deess", "params": {
            "reduction_db": 6.0}}]),
        ("level", [{"op": "level", "params": {}}]),
        ("loudness", [{"op": "loudness", "params": {
            "target_lufs": -14.0}}]),
        ("eq_balance", [{"op": "eq_balance", "params": {
            "low_gain_db": 3.0, "high_gain_db": -2.0}}]),
        ("tune_up", [{"op": "tune_up", "params": {"fixes": [
            {"op": "dc_remove", "params": {}, "label": "DC offset"},
            {"op": "hum_notch", "params": {"freq_hz": 60.0},
             "label": "Hum"},
            {"op": "fade_ends", "params": {"fade_sec": 0.03},
             "label": "Abrupt ends"},
        ]}}]),
        ("punch_in", [{"op": "punch_in", "params": {
            "take_path": take_path,
            "start_frame": int(0.6 * SAMPLE_RATE)}}]),
        ("multi_fx_normalize_fade", [
            {"op": "fx", "params": {
                "warmth": 0.6, "reverb_preset": "studio", "echo": 0.4}},
            {"op": "normalize", "params": {
                "target_db": -1.0, "percentile": 99.0}},
            {"op": "fade_ends", "params": {"fade_sec": 0.02}},
        ]),
    ]


LIVEFX_CHAIN = [{"op": "livefx", "params": {
    "warmth": 0.5, "reverb_preset": "studio", "echo": 0.3}}]


# ---------------------------------------------------------------------------
# Rendering
# ---------------------------------------------------------------------------

def _sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def _base_commit():
    try:
        out = subprocess.run(
            ["git", "rev-parse", "HEAD"], cwd=ROOT,
            capture_output=True, text=True, timeout=10)
        sha = out.stdout.strip()
        if out.returncode == 0 and sha:
            return sha
    except Exception:
        pass
    return "unknown"


def render_all(workdir):
    """Render every fixture; return the hashes.txt lines (no header)."""
    pristine = os.path.join(workdir, "pristine.wav")
    take = os.path.join(workdir, "take.wav")
    write_wav(pristine, build_pristine(), SAMPLE_RATE)
    write_wav(take, build_take(), SAMPLE_RATE)

    lines = []
    for name, chain in fixture_chains(take):
        dst = os.path.join(workdir, "out_%s.wav" % name)
        res = android_bridge.render_chain(
            pristine, json.dumps(chain), dst)
        if not res.get("ok"):
            raise RuntimeError("render_chain not ok for %s: %r"
                               % (name, res))
        lines.append("%s  %s  %d" % (
            name, _sha256_file(dst), os.path.getsize(dst)))

    # livefx is display-only and must RAISE, never render.
    try:
        android_bridge.render_chain(
            pristine, json.dumps(LIVEFX_CHAIN),
            os.path.join(workdir, "out_livefx.wav"))
        lines.append("livefx_raises  FAIL  no exception raised")
    except ValueError:
        lines.append("livefx_raises  PASS  ValueError")
    return lines, pristine, take


def header_lines(pristine, take):
    return [
        "# OO-0 golden render baseline (tools/golden_render.py)",
        "# base_commit %s" % _base_commit(),
        "# sample_rate %d" % SAMPLE_RATE,
        "# pristine_sha256 %s" % _sha256_file(pristine),
        "# take_sha256 %s" % _sha256_file(take),
        "# format: fixture_name  sha256  output_bytes",
    ]


def parse_hash_lines(text):
    """Fixture lines of a hashes.txt, ignoring comments/blanks."""
    out = []
    for line in text.splitlines():
        line = line.strip()
        if line and not line.startswith("#"):
            out.append(line)
    return out


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--out", metavar="DIR",
                    help="write hashes.txt into DIR (default: stdout)")
    ap.add_argument("--check", metavar="FILE",
                    help="compare fresh renders against FILE; "
                         "exit 1 on any mismatch")
    args = ap.parse_args(argv)

    with tempfile.TemporaryDirectory(prefix="golden_render_") as workdir:
        lines, pristine, take = render_all(workdir)
        header = header_lines(pristine, take)

    if args.check:
        with open(args.check, "r") as f:
            expected = parse_hash_lines(f.read())
        ok = expected == lines
        for exp, got in zip(expected, lines):
            if exp != got:
                print("MISMATCH expected: %s" % exp)
                print("MISMATCH actual:   %s" % got)
        if len(expected) != len(lines):
            print("MISMATCH line count: expected %d, got %d"
                  % (len(expected), len(lines)))
        print("golden check: %s (%d fixtures)"
              % ("PASS" if ok else "FAIL", len(lines)))
        return 0 if ok else 1

    text = "\n".join(header + lines) + "\n"
    if args.out:
        os.makedirs(args.out, exist_ok=True)
        path = os.path.join(args.out, "hashes.txt")
        with open(path, "w") as f:
            f.write(text)
        print("wrote %s (%d fixtures)" % (path, len(lines)))
    else:
        sys.stdout.write(text)
    failed = [l for l in lines if "  FAIL  " in l]
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
