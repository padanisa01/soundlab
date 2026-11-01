"""The effect registry: the single source of truth for render ops.

Every op android_bridge.render_chain can apply is registered here as an
EffectSpec (name -> apply handle + renderable flag + default params +
one-line summary), mirrored by ops_manifest.json (the Java side reads
the same list; a unit test asserts registry keys == manifest names ==
the op set). Adding an effect is exactly: one REGISTRY entry + one
manifest entry (design doc 4.1 / 10.2).

This module must NOT import android_bridge (the bridge is a thin
adapter over this registry; a cycle would break both). The float-core
helpers the op branches use (_apply_fx, _apply_normalize, _apply_trim,
_apply_join, _clamp_samples_window, resample_linear) live here with the
logic; android_bridge re-imports them so its existing surface is
unchanged.

Error contract: apply_op raises KeyError naming the op for an unknown
name (design 10.1); android_bridge._apply_op translates that to its
historical ValueError("unknown op %r"). All other errors (invalid
params, livefx, nested tune_up, ...) are the same ValueErrors the
pre-registry dispatch raised, unchanged.
"""

import math
import os
from dataclasses import dataclass, field
from typing import Callable

from . import mastering as _mastering
from . import pitch as _pitch
from . import repair as _repair
from .effects import Echo, Reverb, Warmth
from .pipeline import (
    EffectChain,
    read_wav_mono,
    splice_with_crossfade,
)

try:
    import numpy as _np

    _HAVE_NP = True
except ImportError:  # pragma: no cover
    _np = None
    _HAVE_NP = False


# ---------------------------------------------------------------------------
# Float-core helpers (moved verbatim from android_bridge; the bridge
# re-imports them, so process_file / normalize_file / trim_to_silence /
# join_remove and the test-suite references keep working unchanged).
# ---------------------------------------------------------------------------


def _apply_fx(samples, sample_rate, warmth=0.5, reverb_preset="studio",
              echo=0.3, start_sec=None, end_sec=None):
    """Float core of process_file: apply the effect chain to a sample list.

    Returns (processed_samples, s, e) where s/e are the sample window.
    Window semantics are identical to process_file (windowed: reverb tail
    truncated to the window, spliced with a crossfade; full-file: natural
    tail). Raises ValueError on an unknown preset (via Reverb).
    """
    n = len(samples)
    s = 0 if start_sec is None else int(start_sec * sample_rate)
    e = n if end_sec is None else int(end_sec * sample_rate)
    s = max(0, min(n, s))
    e = max(0, min(n, e))
    if e <= s:
        s, e = 0, n
    chain = EffectChain(
        [Warmth(warmth), Reverb(reverb_preset), Echo(echo)]
    )
    if s == 0 and e == n:
        processed = chain.process(samples, sample_rate)
    else:
        window_out = chain.process(samples[s:e], sample_rate)
        # Effects like reverb extend the signal with a tail; a windowed
        # application is confined to the selected window, so truncate.
        # (Full-file applies keep the natural tail extension.)
        window_out = window_out[: e - s]
        processed = splice_with_crossfade(samples, window_out, s, e, sample_rate)
    return processed, s, e


def _apply_normalize(samples, sample_rate, target_db=-1.0, percentile=99.0):
    """Float core of normalize_file: peak-normalize a sample list.

    The percentile of |samples| (default the 99th) is measured and
    reported, but the applied gain is CAPPED by the absolute peak:
    ``gain = min(target / ref_percentile, target / peak)``. A single
    whole-file constant gain can never push the loudest sample past
    the target, so Normalize introduces ZERO new clipped samples --
    no sample is ever hard-held at the target. (The previous version
    gained to the percentile and clipped everything above it: on a
    dynamic recording that flattened ~1% of all samples at exactly
    -1 dBFS, which is audible hard clipping the app created itself --
    the soundcheck_1 regression case.) Deliberate peak limiting, when
    wanted, is a separate smooth/lookahead limiter, not a clip hidden
    inside Normalize.

    Returns (out_samples, info) where info carries peak_before_db /
    ref_percentile_db / applied_gain_db / percentile /
    gain_capped_by_peak. Raises ValueError on an out-of-range
    percentile or a silent input.
    """
    if not 0.0 < percentile <= 100.0:
        raise ValueError("percentile must be in (0, 100]: %r" % (percentile,))
    target_peak = 10.0 ** (target_db / 20.0)
    if _HAVE_NP:
        x = _np.asarray(samples, dtype=_np.float64)
        n = x.size
        if n == 0:
            raise ValueError("input is silent: nothing to normalize")
        peak = float(_np.max(_np.abs(x)))
        if peak <= 0:
            raise ValueError("input is silent: nothing to normalize")
        k = min(n - 1, int(math.ceil(percentile / 100.0 * n)) - 1)
        ref = float(_np.partition(_np.abs(x), k)[k])
        if ref <= 0:
            raise ValueError(
                "input is silent below the percentile: nothing to normalize")
        gain = min(target_peak / ref, target_peak / peak)
        out = x * gain
    else:
        peak = max(abs(s) for s in samples)
        if peak <= 0:
            raise ValueError("input is silent: nothing to normalize")
        mag = sorted(abs(s) for s in samples)
        ref = mag[min(len(mag) - 1,
                      int(math.ceil(percentile / 100.0 * len(mag))) - 1)]
        if ref <= 0:
            raise ValueError(
                "input is silent below the percentile: nothing to normalize")
        gain = min(target_peak / ref, target_peak / peak)
        out = [s * gain for s in samples]
    info = {
        "peak_before_db": round(20 * math.log10(peak), 2),
        "ref_percentile_db": round(20 * math.log10(ref), 2),
        "applied_gain_db": round(20 * math.log10(gain), 2),
        "percentile": percentile,
        "gain_capped_by_peak": bool(gain < target_peak / ref),
    }
    return out, info


def _clamp_samples_window(samples, sample_rate, start_sec, end_sec):
    """Validate a selection window against a sample buffer; returns (s, e).

    ``end_sec`` may be None, meaning to the end of the buffer. Raises
    ValueError on a missing/empty selection.
    """
    n = len(samples)
    if start_sec is None:
        raise ValueError("no selection: select a range first")
    s = max(0, min(n, int(start_sec * sample_rate)))
    e = n if end_sec is None else max(0, min(n, int(end_sec * sample_rate)))
    if e <= s:
        raise ValueError("empty selection")
    return s, e


def _apply_trim(samples, sample_rate, start_sec, end_sec, edge_fade_sec=0.005):
    """Float core of trim_to_silence: zero the window with soft edges.

    Returns (out_samples, s, e). The selection stays valid: duration is
    unchanged. Raises ValueError on a missing/empty selection.
    """
    s, e = _clamp_samples_window(samples, sample_rate, start_sec, end_sec)
    if _HAVE_NP:
        x = _np.asarray(samples, dtype=_np.float64)
        out = x.copy()
        out[s:e] = 0.0
        fade_n = min(int(edge_fade_sec * sample_rate), (e - s) // 2)
        if fade_n > 0:
            t = 1.0 - (_np.arange(fade_n) + 1) / (fade_n + 1)
            out[s : s + fade_n] = x[s : s + fade_n] * t
            out[e - fade_n : e] = x[e - fade_n : e] * t[::-1]
        return out, s, e
    out = list(samples)
    for i in range(s, e):
        out[i] = 0.0
    fade_n = min(int(edge_fade_sec * sample_rate), (e - s) // 2)
    for i in range(fade_n):
        t = 1.0 - (i + 1) / (fade_n + 1)
        out[s + i] = samples[s + i] * t
        out[e - 1 - i] = samples[e - 1 - i] * t
    return out, s, e


def _apply_join(samples, sample_rate, start_sec, end_sec, fade_sec=0.005):
    """Float core of join_remove: cut the window out, crossfade the junction.

    Returns (out_samples, s, e). Raises ValueError on a missing/empty
    selection or a window covering the whole buffer.
    """
    s, e = _clamp_samples_window(samples, sample_rate, start_sec, end_sec)
    n = len(samples)
    if s <= 0 and e >= n:
        raise ValueError("selection covers the whole file: nothing to join")
    fade_n = min(int(fade_sec * sample_rate), s, n - e)
    if _HAVE_NP:
        x = _np.asarray(samples, dtype=_np.float64)
        head, tail = x[:s], x[e:]
        if fade_n > 0:
            t = (_np.arange(fade_n) + 1) / (fade_n + 1)
            jx = head[s - fade_n :] * (1 - t) + tail[:fade_n] * t
            out = _np.concatenate([head[: s - fade_n], jx, tail[fade_n:]])
        else:
            out = _np.concatenate([head, tail])
        return out, s, e
    head, tail = samples[:s], samples[e:]
    if fade_n > 0:
        out = head[:s - fade_n]
        for i in range(fade_n):
            t = (i + 1) / (fade_n + 1)
            out.append(head[s - fade_n + i] * (1 - t) + tail[i] * t)
        out.extend(tail[fade_n:])
    else:
        out = head + tail
    return out, s, e


def resample_linear(samples, from_rate, to_rate):
    """Resample a mono buffer to a new sample rate by linear interpolation.

    Each output sample is the weighted average of the two input samples
    bracketing its source position (pure linear interpolation, no
    filtering). When the rates are equal the input is returned as float64
    unchanged. Returns a float64 buffer (NumPy array when NumPy is
    available, otherwise a Python list).
    """
    if from_rate <= 0 or to_rate <= 0:
        raise ValueError("sample rates must be positive: %r -> %r"
                         % (from_rate, to_rate))
    if _HAVE_NP:
        x = _np.asarray(samples, dtype=_np.float64)
    else:
        x = [float(s) for s in samples]
    n_in = len(x)
    if from_rate == to_rate:
        return x
    n_out = int(round(n_in * to_rate / from_rate))
    if n_in == 0 or n_out <= 0:
        return _np.zeros(0, dtype=_np.float64) if _HAVE_NP else []
    ratio = from_rate / to_rate
    if _HAVE_NP:
        pos = _np.arange(n_out, dtype=_np.float64) * ratio
        base = _np.floor(pos)
        lo = _np.clip(base.astype(_np.int64), 0, n_in - 1)
        hi = _np.clip(lo + 1, 0, n_in - 1)
        frac = pos - base
        return x[lo] * (1.0 - frac) + x[hi] * frac
    out = []
    for i in range(n_out):
        pos = i * ratio
        base = math.floor(pos)
        lo = max(0, min(n_in - 1, base))
        hi = min(n_in - 1, lo + 1)
        frac = pos - base
        out.append(x[lo] * (1.0 - frac) + x[hi] * frac)
    return out


# ---------------------------------------------------------------------------
# Per-op apply functions: (samples, sample_rate, params, index) -> samples.
# Each is the corresponding pre-registry android_bridge._apply_op branch,
# unchanged.
# ---------------------------------------------------------------------------


def _op_fx(samples, sample_rate, params, index):
    # The effects module is list-based (Echo's feedback loop is
    # inherently sequential); convert at the boundary only.
    fx_in = (samples.tolist()
             if _HAVE_NP and isinstance(samples, _np.ndarray)
             else samples)
    processed, _, _ = _apply_fx(
        fx_in, sample_rate,
        warmth=params.get("warmth", 0.5),
        reverb_preset=params.get("reverb_preset", "studio"),
        echo=params.get("echo", 0.3),
        start_sec=params.get("start_sec"),
        end_sec=params.get("end_sec"))
    return (_np.asarray(processed, dtype=_np.float64)
            if _HAVE_NP else processed)


def _op_pitch_shift(samples, sample_rate, params, index):
    engine = params.get("engine", "wsola")
    if engine == "wsola":
        return _pitch.pitch_shift(
            samples, sample_rate,
            float(params.get("semitones", 0.0)))
    if engine == "formant":
        return _pitch.pitch_shift_formant(
            samples, sample_rate,
            float(params.get("semitones", 0.0)))
    raise ValueError(
        "unknown pitch_shift engine: %r" % (engine,))


def _op_mastering(samples, sample_rate, params, index):
    # Params persist in full numeric form; "preset" is metadata
    # only and rendering uses only the numbers (DSP-2 contract).
    return _mastering.master(
        samples, sample_rate,
        params.get("bands"), params.get("dyn_eq"))


def _op_normalize(samples, sample_rate, params, index):
    samples, _ = _apply_normalize(
        samples, sample_rate,
        target_db=params.get("target_db", -1.0),
        percentile=params.get("percentile", 99.0))
    return samples


def _op_trim(samples, sample_rate, params, index):
    samples, _, _ = _apply_trim(
        samples, sample_rate,
        params.get("start_sec"), params.get("end_sec"))
    return samples


def _op_join(samples, sample_rate, params, index):
    samples, _, _ = _apply_join(
        samples, sample_rate,
        params.get("start_sec"), params.get("end_sec"))
    return samples


def _op_dc_remove(samples, sample_rate, params, index):
    return _repair.dc_remove(samples)


def _op_fade_ends(samples, sample_rate, params, index):
    return _repair.fade_ends(
        samples, sample_rate,
        fade_sec=float(params.get("fade_sec", 0.02)))


def _op_silence_trim(samples, sample_rate, params, index):
    return _repair.silence_trim(
        samples, sample_rate,
        threshold_db=float(params.get("threshold_db", -45.0)),
        min_silence_sec=float(params.get("min_silence_sec", 0.5)))


def _op_hum_notch(samples, sample_rate, params, index):
    return _repair.hum_notch(
        samples, sample_rate, float(params.get("freq_hz", 60.0)))


def _op_declip(samples, sample_rate, params, index):
    return _repair.declip(samples)


def _op_denoise(samples, sample_rate, params, index):
    return _repair.denoise(
        samples, sample_rate,
        strength=float(params.get("strength", 0.5)))


def _op_deplosive(samples, sample_rate, params, index):
    return _repair.deplosive(samples, sample_rate)


def _op_deess(samples, sample_rate, params, index):
    return _repair.deess(
        samples, sample_rate,
        reduction_db=float(params.get("reduction_db", 8.0)))


def _op_level(samples, sample_rate, params, index):
    return _repair.level(samples, sample_rate)


def _op_loudness(samples, sample_rate, params, index):
    return _repair.loudness_normalize(
        samples, sample_rate,
        target_lufs=float(params.get("target_lufs", -16.0)))


def _op_eq_balance(samples, sample_rate, params, index):
    return _repair.eq_balance(
        samples, sample_rate,
        low_gain_db=float(params.get("low_gain_db", 0.0)),
        high_gain_db=float(params.get("high_gain_db", 0.0)))


def _op_tune_up(samples, sample_rate, params, index):
    fixes = params.get("fixes")
    if not isinstance(fixes, list):
        raise ValueError("op #%d tune_up params.fixes must be an array"
                         % index)
    for j, fix in enumerate(fixes):
        if not isinstance(fix, dict):
            raise ValueError("op #%d tune_up fix #%d is not an object"
                             % (index, j))
        sub = fix.get("op")
        sub_params = fix.get("params") or {}
        if not isinstance(sub_params, dict):
            raise ValueError("op #%d tune_up fix #%d params invalid"
                             % (index, j))
        if sub == "tune_up":
            raise ValueError("tune_up ops cannot nest")
        samples = apply_op(samples, sample_rate, sub, sub_params, index)
    return samples


def _op_punch_in(samples, sample_rate, params, index):
    # Replace [start_frame, start_frame + take_len) with the take,
    # crossfaded at the splice points. If the take runs past the end
    # the track extends.
    take_path = params.get("take_path")
    start_frame = int(params.get("start_frame", 0))
    if not take_path or not os.path.isfile(take_path):
        raise ValueError("op #%d punch_in take not found: %r"
                         % (index, take_path))
    take, take_rate = read_wav_mono(take_path)
    take = resample_linear(take, take_rate, sample_rate)
    take_list = (take.tolist()
                 if _HAVE_NP and isinstance(take, _np.ndarray)
                 else list(take))
    if not take_list:
        raise ValueError("op #%d punch_in take is empty" % index)
    orig = (samples.tolist()
            if _HAVE_NP and isinstance(samples, _np.ndarray)
            else list(samples))
    n = len(orig)
    start = max(0, min(start_frame, n))
    end = min(start + len(take_list), n)
    spliced = splice_with_crossfade(orig, take_list[:end - start],
                                    start, end, sample_rate)
    if start + len(take_list) > n:
        spliced.extend(take_list[n - start:])
    return (_np.asarray(spliced, dtype=_np.float64)
            if _HAVE_NP else spliced)


def _op_livefx(samples, sample_rate, params, index):
    # Live-FX takes are baked into the pristine; the livefx op is a
    # display-only label and must never be re-run.
    raise ValueError("livefx ops are never re-rendered in render_chain")


# ---------------------------------------------------------------------------
# The registry (mirrored by ops_manifest.json; keep the two in sync --
# tests/test_registry.py asserts they are identical).
# ---------------------------------------------------------------------------


@dataclass(frozen=True)
class EffectSpec:
    name: str                       # op_type, e.g. "pitch_shift"
    apply: Callable                 # (samples, sr, params, index) -> samples
    renderable: bool = True         # False for annotation-only ops
    defaults: dict = field(default_factory=dict)  # default params
    summary: str = ""               # one-line description


REGISTRY = {
    "fx": EffectSpec(
        "fx", _op_fx, True,
        {"warmth": 0.5, "reverb_preset": "studio", "echo": 0.3,
         "start_sec": None, "end_sec": None},
        "Warmth -> Reverb -> Echo effect chain"),
    "pitch_shift": EffectSpec(
        "pitch_shift", _op_pitch_shift, True,
        {"semitones": 0.0, "engine": "wsola"},
        "Shift pitch by semitones (WSOLA or formant engine)"),
    "mastering": EffectSpec(
        "mastering", _op_mastering, True,
        {"bands": None, "dyn_eq": None},
        "Multiband compression + dynamic EQ mastering"),
    "normalize": EffectSpec(
        "normalize", _op_normalize, True,
        {"target_db": -1.0, "percentile": 99.0},
        "Peak normalize, gain capped by the absolute peak"),
    "trim": EffectSpec(
        "trim", _op_trim, True,
        {"start_sec": None, "end_sec": None},
        "Silence the selected window (duration unchanged)"),
    "join": EffectSpec(
        "join", _op_join, True,
        {"start_sec": None, "end_sec": None},
        "Cut the selected window out and join the remainder"),
    "dc_remove": EffectSpec(
        "dc_remove", _op_dc_remove, True, {},
        "Remove DC offset (subtract the mean)"),
    "fade_ends": EffectSpec(
        "fade_ends", _op_fade_ends, True,
        {"fade_sec": 0.02},
        "Short linear fades at both ends"),
    "silence_trim": EffectSpec(
        "silence_trim", _op_silence_trim, True,
        {"threshold_db": -45.0, "min_silence_sec": 0.5},
        "Trim leading/trailing silence below a threshold"),
    "hum_notch": EffectSpec(
        "hum_notch", _op_hum_notch, True,
        {"freq_hz": 60.0},
        "Notch out mains hum at the given frequency"),
    "declip": EffectSpec(
        "declip", _op_declip, True, {},
        "Reconstruct clipped (flat-topped) samples"),
    "denoise": EffectSpec(
        "denoise", _op_denoise, True,
        {"strength": 0.5},
        "Spectral-gating noise reduction"),
    "deplosive": EffectSpec(
        "deplosive", _op_deplosive, True, {},
        "Reduce plosive low-frequency bursts"),
    "deess": EffectSpec(
        "deess", _op_deess, True,
        {"reduction_db": 8.0},
        "Reduce sibilance (de-esser)"),
    "level": EffectSpec(
        "level", _op_level, True, {},
        "Level volume over time (slow gain riding)"),
    "loudness": EffectSpec(
        "loudness", _op_loudness, True,
        {"target_lufs": -16.0},
        "Normalize integrated loudness to a LUFS target"),
    "eq_balance": EffectSpec(
        "eq_balance", _op_eq_balance, True,
        {"low_gain_db": 0.0, "high_gain_db": 0.0},
        "Low/high shelf EQ balance"),
    "tune_up": EffectSpec(
        "tune_up", _op_tune_up, True,
        {"fixes": []},
        "Composite op: expand and apply a list of fix sub-ops"),
    "punch_in": EffectSpec(
        "punch_in", _op_punch_in, True,
        {"take_path": None, "start_frame": 0},
        "Splice a recorded take into the track at a frame offset"),
    "livefx": EffectSpec(
        "livefx", _op_livefx, False, {},
        "Display-only label for live-FX takes (never re-rendered)"),
}


def apply_op(samples, sr, op_name, params, index):
    """Apply the registered op ``op_name`` to ``samples``.

    Raises KeyError naming the op for an unknown name (callers that
    expose a ValueError surface, e.g. android_bridge._apply_op,
    translate it)."""
    spec = REGISTRY[op_name]
    return spec.apply(samples, sr, params, index)
