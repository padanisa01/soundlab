"""Duration-preserving pitch shift (phase vocoder, NumPy).

pitch_shift(x, sr, semitones) shifts every frequency by
2**(semitones/12) while keeping the buffer length: the signal is
time-stretched by the same ratio with a Laroche phase vocoder
(same pitch, longer), then resampled back to the original length
(pitch scaled, duration restored). Used by the "pitch_shift"
render op so tracks recorded at one shruti can be matched to
another track's tonic.

NumPy is required (the app installs it via Chaquopy); a clear
ValueError is raised without it.
"""

import math

try:
    import numpy as _np
except Exception:  # pragma: no cover - exercised only without numpy
    _np = None

#: Phase-vocoder framing.
PV_WIN = 2048
PV_HOP = 512
#: Supported shift range (one octave each way).
PITCH_MAX_SEMITONES = 12.0


def _need_numpy():
    if _np is None:
        raise ValueError("pitch_shift requires numpy")


def _stft(x, win_len, hop):
    win = _np.hanning(win_len)
    pad = win_len // 2
    xp = _np.pad(x, (pad, pad))
    n_frames = 1 + max(0, (len(xp) - win_len) // hop)
    frames = _np.stack(
        [xp[i * hop: i * hop + win_len] for i in range(n_frames)]) * win
    return _np.fft.rfft(frames, axis=1).T  # (bins, frames)


def _istft(spec, win_len, hop, length):
    win = _np.hanning(win_len)
    frames = _np.fft.irfft(spec.T, n=win_len, axis=1) * win
    n = (spec.shape[1] - 1) * hop + win_len
    y = _np.zeros(n)
    wsum = _np.zeros(n)
    for i in range(spec.shape[1]):
        s = i * hop
        y[s: s + win_len] += frames[i]
        wsum[s: s + win_len] += win ** 2
    nz = wsum > 1e-8
    y[nz] /= wsum[nz]
    pad = win_len // 2
    return y[pad: pad + length]


def _pv_stretch(spec, factor):
    """Phase-vocoder time stretch by factor (>1 = longer)."""
    bins, T = spec.shape
    if T < 2 or factor == 1.0:
        return spec
    win_len = (bins - 1) * 2
    omega = 2.0 * math.pi * PV_HOP * _np.arange(bins) / win_len
    mag = _np.abs(spec)
    phase = _np.angle(spec)
    dphi = phase[:, 1:] - phase[:, :-1] - omega[:, None]
    dphi = dphi - 2.0 * math.pi * _np.round(dphi / (2.0 * math.pi))
    true_freq = (omega[:, None] + dphi) / PV_HOP  # rad per sample
    t_out = int(round(T * factor))
    pos = _np.arange(t_out) / factor
    i0 = _np.floor(pos).astype(int)
    frac = pos - i0
    i1 = _np.minimum(i0 + 1, T - 1)
    out_mag = (1.0 - frac)[None, :] * mag[:, i0] + frac[None, :] * mag[:, i1]
    seg = _np.minimum(i0, T - 2)
    adv = true_freq[:, seg] * PV_HOP
    adv[:, 0] = 0.0
    out_phase = phase[:, [0]] + _np.cumsum(adv, axis=1)
    return out_mag * _np.exp(1j * out_phase)


def pitch_shift(samples, sample_rate, semitones):
    """Shift pitch by semitones (float, +/-12), duration preserved.

    samples: array-like of floats. Returns a float64 NumPy array of
    the same length. A zero shift returns an unchanged copy.
    """
    _need_numpy()
    semis = float(semitones)
    if not math.isfinite(semis):
        raise ValueError("semitones must be finite: %r" % (semitones,))
    if abs(semis) > PITCH_MAX_SEMITONES:
        raise ValueError(
            "semitones out of range (+/-%g): %r"
            % (PITCH_MAX_SEMITONES, semitones))
    x = _np.asarray(samples, dtype=_np.float64)
    if x.size == 0 or semis == 0.0:
        return x.copy()
    ratio = 2.0 ** (semis / 12.0)
    spec = _stft(x, PV_WIN, PV_HOP)
    stretched = _istft(_pv_stretch(spec, ratio), PV_WIN, PV_HOP,
                       int(round(x.size * ratio)))
    if stretched.size == 0:
        return x.copy()
    idx = _np.linspace(0, stretched.size - 1, x.size)
    return _np.interp(idx, _np.arange(stretched.size), stretched)
