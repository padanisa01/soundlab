"""Multiband mastering engine (op ``mastering``, DSP-2).

Four-band feed-forward compression plus three-band dynamic EQ:

- Bands [low, low_mid, high_mid, high] split at 120 Hz / 1 kHz /
  6 kHz by linear-phase (zero-phase) FFT-domain crossovers. The
  crossover masks are complementary by construction -- the band
  masks are successive differences of lowpass masks and sum to
  exactly 1 at every bin -- so at unity gain the bands recombine
  to the input bit-transparently (within float round-off, far
  below 1 LSB of 16-bit).
- Each band runs a feed-forward compressor: a one-pole
  attack/release envelope follower over block peaks of the band
  signal; the gain (in dB) ramps linearly between blocks so there
  are no gain steps. Thresholds / ratios / attack / release are
  exactly the op-param numbers -- nothing is re-scaled.
- Dynamic EQ: per-frame STFT peaking gains at 300 Hz / 3 kHz /
  6.5 kHz. A band's gain is driven by how far its band level sits
  over its threshold, reaching ``max_db`` at DYN_EQ_RAMP_DB over,
  and every gain is clamped to +/-DYN_EQ_MAX_GAIN_DB.

Silence: envelopes sit at ENVELOPE_FLOOR, every gain is 1.0, and
the output is exact silence -- no NaNs.

Op params (persisted in full numeric form; ``preset`` is metadata
only, rendering uses only the numbers)::

    {"preset": name,
     "bands":  [4 x {thr_db, ratio, attack_ms, release_ms}],
     "dyn_eq": [3 x {freq_hz, max_db, thr_db}]}
"""

import math

try:
    import numpy as _np

    _HAVE_NUMPY = True
except ImportError:  # pragma: no cover
    _np = None
    _HAVE_NUMPY = False

from . import repair as _repair

#: Band split frequencies, low -> high (contract-frozen).
CROSSOVERS_HZ = (120.0, 1000.0, 6000.0)
#: Dynamic-EQ center frequencies, low -> high (contract-frozen).
DYN_EQ_FREQS_HZ = (300.0, 3000.0, 6500.0)

#: Raised-cosine crossover transition width, in octaves, centered
#: (in log-frequency) on each crossover frequency.
CROSSOVER_TRANSITION_OCTAVES = 1.0
#: Compressor envelope block size in samples (~5.8 ms at 44.1 kHz).
#: The envelope follower runs one step per block; the resulting
#: gain ramps linearly inside the block.
COMP_BLOCK_SAMPLES = 256
#: Envelope floor: silence measures this level, far below any
#: threshold, so its gain is exactly 1.0.
ENVELOPE_FLOOR = 1e-12

#: Dynamic-EQ STFT geometry.
DYN_EQ_N_FFT = 2048
DYN_EQ_HOP = 512
#: Band level must exceed the threshold by this many dB for the
#: gain to reach the band's max_db.
DYN_EQ_RAMP_DB = 12.0
#: Peaking shape width: Gaussian in log2 frequency, this sigma.
DYN_EQ_WIDTH_OCTAVES = 0.5
#: One-pole smoothing of the per-frame gain trajectory (per frame).
DYN_EQ_GAIN_SMOOTH = 0.35
#: Hard clamp on every dynamic-EQ gain (contract: +/-6 dB).
DYN_EQ_MAX_GAIN_DB = 6.0


def _need_numpy(op):
    if not _HAVE_NUMPY:
        raise RuntimeError("mastering.%s requires NumPy" % op)


def _band_param(band, key, index):
    try:
        return float(band[key])
    except (KeyError, TypeError, ValueError):
        raise ValueError("bands[%d] missing/invalid %r" % (index, key))


def _dyn_param(band, key, index):
    try:
        return float(band[key])
    except (KeyError, TypeError, ValueError):
        raise ValueError("dyn_eq[%d] missing/invalid %r" % (index, key))


# ---------------------------------------------------------------------------
# Crossovers
# ---------------------------------------------------------------------------

def _lowpass_mask(freqs, cutoff_hz):
    """Raised-cosine lowpass in log-frequency: 1 below the
    transition, 0 above it, smooth in between."""
    half = CROSSOVER_TRANSITION_OCTAVES / 2.0
    lo = cutoff_hz * (2.0 ** -half)
    f = _np.maximum(freqs, 1e-9)
    t = _np.clip(_np.log2(f / lo) / CROSSOVER_TRANSITION_OCTAVES, 0.0, 1.0)
    return 0.5 * (1.0 + _np.cos(_np.pi * t))


def _band_masks(freqs):
    """The 4 complementary band masks; they sum to 1 at every bin."""
    lp = [_lowpass_mask(freqs, fc) for fc in CROSSOVERS_HZ]
    return [lp[0], lp[1] - lp[0], lp[2] - lp[1], 1.0 - lp[2]]


def _split_bands(x, sample_rate):
    n = x.size
    spectrum = _np.fft.rfft(x)
    freqs = _np.fft.rfftfreq(n, 1.0 / sample_rate)
    return [_np.fft.irfft(spectrum * m, n) for m in _band_masks(freqs)]


# ---------------------------------------------------------------------------
# Per-band compressor
# ---------------------------------------------------------------------------

def _compress_band(x, sample_rate, thr_db, ratio, attack_ms, release_ms):
    """Feed-forward compression of one band signal.

    One-pole attack/release envelope over per-block peaks; gain in
    dB from the static curve ``(thr - env_db) * (1 - 1/ratio)`` when
    the envelope is over threshold, else 0 dB; per-sample gain
    ramps linearly from the previous block's gain.
    """
    n = x.size
    if n == 0 or ratio <= 1.0:
        return x
    block = COMP_BLOCK_SAMPLES
    n_blocks = (n + block - 1) // block
    padded = _np.zeros(n_blocks * block)
    padded[:n] = x
    peaks = _np.max(_np.abs(padded.reshape(n_blocks, block)), axis=1)
    dt = block / float(sample_rate)
    a_atk = math.exp(-dt / max(attack_ms, 0.01) * 1000.0)
    a_rel = math.exp(-dt / max(release_ms, 0.01) * 1000.0)
    env = 0.0
    gains_db = _np.zeros(n_blocks)
    slope = 1.0 - 1.0 / ratio
    for i in range(n_blocks):
        target = float(peaks[i])
        a = a_atk if target > env else a_rel
        env = target + a * (env - target)
        env_db = 20.0 * math.log10(max(env, ENVELOPE_FLOOR))
        gains_db[i] = (thr_db - env_db) * slope if env_db > thr_db else 0.0
    if not _np.any(gains_db):
        return x
    # Per-sample gain: linear ramp from the previous block's gain
    # (0 dB before the first block) to this block's gain.
    prev = _np.concatenate(([0.0], gains_db[:-1]))
    t = _np.tile(_np.arange(block, dtype=_np.float64) / block, n_blocks)
    gain = 10.0 ** ((prev.repeat(block)
                     + (gains_db - prev).repeat(block) * t) / 20.0)
    return x * gain[:n]


# ---------------------------------------------------------------------------
# Dynamic EQ
# ---------------------------------------------------------------------------

def _dyn_eq(x, sample_rate, dyn_eq):
    """Per-frame STFT peaking gains driven by band level over
    threshold. Only called when at least one max_db is nonzero."""
    n_fft, hop = DYN_EQ_N_FFT, DYN_EQ_HOP
    pad = n_fft
    if x.size > 1:
        xp = _np.pad(x, (pad, pad), mode="reflect")
    else:
        xp = _np.pad(x, (pad, pad), mode="constant")
    spectra, _, _ = _repair._stft(xp, n_fft, hop)
    freqs = _np.fft.rfftfreq(n_fft, 1.0 / sample_rate)
    win = _np.hanning(n_fft)
    # Parseval scale: band mean-square = 2*sum|X|^2/(N * sum(win^2)).
    power_scale = 2.0 / (n_fft * float(_np.sum(win ** 2)))
    log_ratio = _np.zeros_like(freqs)
    pos = freqs > 0
    log_ratio[pos] = _np.log2(freqs[pos])
    total_db = _np.zeros_like(spectra, dtype=_np.float64)
    power = _np.abs(spectra) ** 2
    for i, band in enumerate(dyn_eq):
        max_db = _dyn_param(band, "max_db", i)
        if max_db == 0.0:
            continue
        f0 = _dyn_param(band, "freq_hz", i)
        thr_db = _dyn_param(band, "thr_db", i)
        shape = _np.exp(-0.5 * ((log_ratio - math.log2(f0))
                                / DYN_EQ_WIDTH_OCTAVES) ** 2)
        shape[~pos] = 0.0
        in_band = shape >= 0.5
        band_power = power[:, in_band].sum(axis=1) * power_scale
        level_db = 10.0 * _np.log10(_np.maximum(band_power,
                                                ENVELOPE_FLOOR ** 2))
        frac = _np.clip((level_db - thr_db) / DYN_EQ_RAMP_DB, 0.0, 1.0)
        target = _np.clip(max_db * frac,
                          -DYN_EQ_MAX_GAIN_DB, DYN_EQ_MAX_GAIN_DB)
        smoothed = _np.empty_like(target)
        g = 0.0
        for j in range(target.size):
            g += DYN_EQ_GAIN_SMOOTH * (float(target[j]) - g)
            smoothed[j] = g
        total_db += smoothed[:, _np.newaxis] * shape[_np.newaxis, :]
    total_db = _np.clip(total_db, -DYN_EQ_MAX_GAIN_DB, DYN_EQ_MAX_GAIN_DB)
    out = _repair._istft(spectra * (10.0 ** (total_db / 20.0)),
                         n_fft, hop, xp.size)
    return _np.asarray(out)[pad: pad + x.size]


# ---------------------------------------------------------------------------
# Public contract
# ---------------------------------------------------------------------------

_PRESETS = {
    "gentle": (
        [{"thr_db": -18.0, "ratio": 1.5, "attack_ms": 30.0, "release_ms": 250.0},
         {"thr_db": -16.0, "ratio": 1.5, "attack_ms": 20.0, "release_ms": 200.0},
         {"thr_db": -16.0, "ratio": 2.0, "attack_ms": 10.0, "release_ms": 150.0},
         {"thr_db": -18.0, "ratio": 2.0, "attack_ms": 5.0, "release_ms": 120.0}],
        [{"freq_hz": 300.0, "max_db": -1.5, "thr_db": -24.0},
         {"freq_hz": 3000.0, "max_db": -2.0, "thr_db": -24.0},
         {"freq_hz": 6500.0, "max_db": -3.0, "thr_db": -22.0}],
    ),
    "punch": (
        [{"thr_db": -14.0, "ratio": 3.0, "attack_ms": 10.0, "release_ms": 150.0},
         {"thr_db": -14.0, "ratio": 2.5, "attack_ms": 8.0, "release_ms": 120.0},
         {"thr_db": -12.0, "ratio": 3.0, "attack_ms": 5.0, "release_ms": 100.0},
         {"thr_db": -14.0, "ratio": 3.0, "attack_ms": 3.0, "release_ms": 80.0}],
        [{"freq_hz": 300.0, "max_db": -2.0, "thr_db": -20.0},
         {"freq_hz": 3000.0, "max_db": -3.0, "thr_db": -20.0},
         {"freq_hz": 6500.0, "max_db": -4.0, "thr_db": -18.0}],
    ),
    "broadcast": (
        [{"thr_db": -16.0, "ratio": 4.0, "attack_ms": 8.0, "release_ms": 120.0},
         {"thr_db": -12.0, "ratio": 4.0, "attack_ms": 5.0, "release_ms": 100.0},
         {"thr_db": -10.0, "ratio": 4.0, "attack_ms": 3.0, "release_ms": 80.0},
         {"thr_db": -12.0, "ratio": 5.0, "attack_ms": 2.0, "release_ms": 60.0}],
        [{"freq_hz": 300.0, "max_db": -3.0, "thr_db": -18.0},
         {"freq_hz": 3000.0, "max_db": -4.0, "thr_db": -16.0},
         {"freq_hz": 6500.0, "max_db": -6.0, "thr_db": -14.0}],
    ),
}


def preset_params(name):
    """Expand a preset name to its explicit (bands, dyn_eq) numeric
    op-param lists. Raises ValueError on an unknown name."""
    try:
        bands, dyn_eq = _PRESETS[name]
    except (KeyError, TypeError):
        raise ValueError("unknown mastering preset %r" % (name,))
    return ([dict(b) for b in bands], [dict(d) for d in dyn_eq])


def master(samples, sample_rate, bands, dyn_eq):
    """Apply the multiband mastering chain. Returns a float buffer
    the same length as the input. Raises ValueError on malformed
    band/dyn-EQ params."""
    _need_numpy("master")
    if not isinstance(bands, (list, tuple)) or len(bands) != 4:
        raise ValueError("mastering bands must be a list of 4 band params")
    if not isinstance(dyn_eq, (list, tuple)) or len(dyn_eq) != 3:
        raise ValueError("mastering dyn_eq must be a list of 3 band params")
    x = _np.asarray(samples, dtype=_np.float64)
    if x.size == 0:
        return _np.zeros(0)
    if not _np.any(x):
        return _np.zeros(x.size)  # silence: gain 1.0, no NaNs
    band_signals = _split_bands(x, sample_rate)
    out = _np.zeros(x.size)
    for i, band in enumerate(bands):
        out += _compress_band(
            band_signals[i], sample_rate,
            _band_param(band, "thr_db", i),
            max(1.0, _band_param(band, "ratio", i)),
            _band_param(band, "attack_ms", i),
            _band_param(band, "release_ms", i))
    if any(_dyn_param(d, "max_db", i) != 0.0 for i, d in enumerate(dyn_eq)):
        out = _dyn_eq(out, sample_rate, dyn_eq)
    return out
