"""Perceptual quality score (estimate) -- Tier 1 proxy (DSP-5).

``score(samples, sample_rate, measurements)`` maps the measurements
Diagnose already computes onto three sub-scores in [1, 5], in the
P.835 shape::

    {"ovrl": f, "sig": f, "bak": f, "engine": "proxy"}

or ``None`` for silence/empty input (never 1 or 5).

This is an *estimate* only: a deterministic mapping from measurements,
not a learned model. The function does NOT re-measure the signal --
``measurements`` is the diagnose findings context (noise floor dB,
ceiling-clip stats, LUFS, sibilant fraction, level spread, hum
ratio); ``samples``/``sample_rate`` are used only for the silence
gate and, when the context carries no explicit total, as the sample
count a flat-top population is a fraction of.

Measurements dict (all keys optional; a missing key is treated as
the clean anchor for that measurement)::

    noise_floor_db     float, dBFS of the background hiss floor
    ceiling_clip       diagnose.ceiling_clip_stats(...) dict
    clip_fraction      float, fraction of samples clipped/flat-topped
                       (takes precedence over ceiling_clip counts)
    total_samples      int, denominator for ceiling_clip counts
    lufs               float, integrated loudness
    lufs_distance_db   float, |lufs - target| (takes precedence)
    sibilant_fraction  float, fraction of frames with harsh sibilance
    level_spread_db    float, window-RMS spread in dB
    hum_ratio          float, hum band energy vs local floor ratio

All scoring anchors and the OVRL weights are named constants so they
can be tuned without touching the formulas.
"""

import math

try:
    import numpy as _np

    _HAVE_NUMPY = True
except ImportError:  # pragma: no cover
    _np = None
    _HAVE_NUMPY = False


# ---------------------------------------------------------------------------
# OVRL weights (named per contract)
# ---------------------------------------------------------------------------

QUALITY_W_SIG = 0.6
QUALITY_W_BAK = 0.4

# ---------------------------------------------------------------------------
# Anchors, named per measurement. "clean" = no penalty at/below it,
# "bad" = full penalty at/above it, linear in between.
# ---------------------------------------------------------------------------

# Clipping (SIG): fraction of samples flat-topped/clipped.
QUALITY_CLIP_FRACTION_CLEAN = 0.0
QUALITY_CLIP_FRACTION_BAD = 0.01    # 1% flat (the soundcheck_1 dose)
QUALITY_CLIP_PENALTY_MAX = 3.5

# Sibilance (SIG): fraction of frames with harsh sibilant energy.
QUALITY_SIBILANT_FRACTION_CLEAN = 0.0
QUALITY_SIBILANT_FRACTION_BAD = 0.25
QUALITY_SIBILANT_PENALTY_MAX = 1.2

# Level spread (SIG): window-RMS spread in dB.
QUALITY_LEVEL_SPREAD_CLEAN_DB = 3.0
QUALITY_LEVEL_SPREAD_BAD_DB = 20.0
QUALITY_LEVEL_SPREAD_PENALTY_MAX = 1.0

# Loudness distance (SIG): |LUFS - target| in dB.
QUALITY_LUFS_TARGET = -16.0         # matches diagnose.LOUDNESS_TARGET
QUALITY_LUFS_DISTANCE_CLEAN_DB = 0.0
QUALITY_LUFS_DISTANCE_BAD_DB = 10.0
QUALITY_LUFS_PENALTY_MAX = 0.5

# Noise floor (BAK): background hiss floor in dBFS.
QUALITY_NOISE_FLOOR_CLEAN_DB = -70.0
QUALITY_NOISE_FLOOR_BAD_DB = -30.0
QUALITY_NOISE_PENALTY_MAX = 3.2

# Hum (BAK): hum band energy vs local floor ratio (diagnose warns
# above 10x; penalty ramps up to 20x).
QUALITY_HUM_RATIO_CLEAN = 1.0
QUALITY_HUM_RATIO_BAD = 20.0
QUALITY_HUM_PENALTY_MAX = 0.8

_SCORE_MIN = 1.0
_SCORE_MAX = 5.0


def _clamp(v):
    return max(_SCORE_MIN, min(_SCORE_MAX, float(v)))


def _penalty(value, clean, bad, penalty_max):
    """Linear penalty: 0 at/below ``clean``, ``penalty_max`` at/above
    ``bad``."""
    if value is None:
        return 0.0
    try:
        v = float(value)
    except (TypeError, ValueError):
        return 0.0
    if not math.isfinite(v):
        return 0.0
    frac = (v - clean) / (bad - clean)
    frac = max(0.0, min(1.0, frac))
    return penalty_max * frac


def _get(measurements, *names):
    for name in names:
        if name in measurements and measurements[name] is not None:
            return measurements[name]
    return None


def _clip_fraction(measurements, n_samples):
    explicit = _get(measurements, "clip_fraction", "clipped_fraction",
                    "flat_fraction", "clipping_fraction")
    if explicit is not None:
        try:
            return float(explicit)
        except (TypeError, ValueError):
            pass
    ceil = measurements.get("ceiling_clip")
    if isinstance(ceil, dict):
        flat = ceil.get("flat_samples")
        if flat is None:
            flat = ceil.get("ceiling_samples")
        total = _get(measurements, "total_samples", "n_samples")
        if total is None:
            total = n_samples
        try:
            if flat is not None and total:
                return float(flat) / float(total)
        except (TypeError, ValueError, ZeroDivisionError):
            pass
        if ceil.get("is_clipped"):
            # Clipped by the detector but no population was carried:
            # treat it as the bad anchor (a real, audible dose).
            return QUALITY_CLIP_FRACTION_BAD
    return 0.0


def _lufs_distance(measurements):
    dist = _get(measurements, "lufs_distance_db", "loudness_distance_db")
    if dist is not None:
        try:
            return abs(float(dist))
        except (TypeError, ValueError):
            return None
    lufs = _get(measurements, "lufs", "lufs_db", "loudness_lufs")
    if lufs is not None:
        try:
            v = float(lufs)
        except (TypeError, ValueError):
            return None
        if math.isfinite(v):
            return abs(v - QUALITY_LUFS_TARGET)
    return None


def _is_silence(samples):
    if samples is None:
        return True
    try:
        n = len(samples)
    except TypeError:
        return True
    if n == 0:
        return True
    if _HAVE_NUMPY:
        try:
            x = _np.asarray(samples, dtype=_np.float64)
            return x.size == 0 or float(_np.max(_np.abs(x))) <= 0.0
        except Exception:
            pass
    return all(float(v) == 0.0 for v in samples)


def score(samples, sample_rate, measurements):
    """Tier 1 proxy score. See the module docstring for the
    measurements schema. Returns the score dict, or None for
    silence/empty."""
    if _is_silence(samples):
        return None
    meas = measurements if isinstance(measurements, dict) else {}
    try:
        n_samples = len(samples)
    except TypeError:
        n_samples = 0

    # SIG: signal fidelity -- clipping, sibilance, level spread,
    # loudness distance.
    sig_penalty = (
        _penalty(_clip_fraction(meas, n_samples),
                 QUALITY_CLIP_FRACTION_CLEAN, QUALITY_CLIP_FRACTION_BAD,
                 QUALITY_CLIP_PENALTY_MAX)
        + _penalty(_get(meas, "sibilant_fraction", "sibilant_frac"),
                   QUALITY_SIBILANT_FRACTION_CLEAN,
                   QUALITY_SIBILANT_FRACTION_BAD,
                   QUALITY_SIBILANT_PENALTY_MAX)
        + _penalty(_get(meas, "level_spread_db", "level_range_db",
                        "level_spread"),
                   QUALITY_LEVEL_SPREAD_CLEAN_DB,
                   QUALITY_LEVEL_SPREAD_BAD_DB,
                   QUALITY_LEVEL_SPREAD_PENALTY_MAX)
        + _penalty(_lufs_distance(meas),
                   QUALITY_LUFS_DISTANCE_CLEAN_DB,
                   QUALITY_LUFS_DISTANCE_BAD_DB,
                   QUALITY_LUFS_PENALTY_MAX)
    )
    sig = _clamp(_SCORE_MAX - sig_penalty)

    # BAK: background -- noise floor and hum.
    bak_penalty = (
        _penalty(_get(meas, "noise_floor_db", "noise_floor"),
                 QUALITY_NOISE_FLOOR_CLEAN_DB, QUALITY_NOISE_FLOOR_BAD_DB,
                 QUALITY_NOISE_PENALTY_MAX)
        + _penalty(_get(meas, "hum_ratio"),
                   QUALITY_HUM_RATIO_CLEAN, QUALITY_HUM_RATIO_BAD,
                   QUALITY_HUM_PENALTY_MAX)
    )
    bak = _clamp(_SCORE_MAX - bak_penalty)

    ovrl = _clamp(QUALITY_W_SIG * sig + QUALITY_W_BAK * bak)
    return {"ovrl": ovrl, "sig": sig, "bak": bak, "engine": "proxy"}
