"""Duration-preserving pitch shift (WSOLA time-stretch + resample).

pitch_shift(x, sr, semitones) shifts every frequency by
2**(semitones/12) while keeping the buffer length and, crucially,
the timing: the signal is time-stretched by the same ratio with
WSOLA (waveform similarity overlap-add, which keeps attacks and
onsets sharp), then resampled back to the original length (pitch
scaled, duration and tempo restored).

An earlier phase-vocoder version preserved duration and onset
positions but dispersed transient energy (click energy within
+/-20 ms of the attack fell to 0.65 of the original's share,
vs 0.92 for WSOLA), which reads as rhythmic slop against
unshifted tracks. WSOLA reconstructs steady tones exactly too.

Used by the "pitch_shift" render op so tracks recorded at one
shruti can be matched to another track's tonic.

NumPy is required (the app installs it via Chaquopy); a clear
ValueError is raised without it.
"""

import math

try:
    import numpy as _np
except Exception:  # pragma: no cover - exercised only without numpy
    _np = None

#: WSOLA framing: window, synthesis hop, search tolerance/step.
WS_WIN = 2048
WS_HOP = 512
WS_TOL = 384
WS_STEP = 2
#: Supported shift range (one octave each way).
PITCH_MAX_SEMITONES = 12.0


def _need_numpy():
    if _np is None:
        raise ValueError("pitch_shift requires numpy")


def _wsola_stretch(x, factor):
    """Time-stretch by factor (>1 = longer), onsets preserved.

    Each synthesis frame takes the input segment near its nominal
    position whose head best matches (normalized cross-correlation)
    the previous segment's overlap tail, then overlap-adds with a
    Hann window. Nominal positions are absolute (j * analysis hop),
    so no timing drift accumulates.
    """
    n_in = len(x)
    target_len = int(round(n_in * factor))
    if n_in <= WS_WIN or target_len <= WS_WIN:
        # Too short to window-search: plain resample stretch.
        idx = _np.linspace(0, n_in - 1, max(2, target_len))
        return _np.interp(idx, _np.arange(n_in), x)
    win, hs = WS_WIN, WS_HOP
    w = _np.hanning(win)
    overlap = win - hs
    ha = hs / factor
    n_frames = max(1, (target_len - win) // hs + 1)
    y = _np.zeros(target_len + win)
    wsum = _np.zeros(target_len + win)
    max_pos = n_in - win
    prev = None
    for j in range(n_frames):
        nominal = int(round(j * ha))
        if prev is None:
            chosen = max(0, min(nominal, max_pos))
        else:
            ref = prev[hs:hs + overlap]
            lo = max(0, nominal - WS_TOL)
            hi = min(max_pos, nominal + WS_TOL)
            if hi <= lo:
                chosen = max(0, min(lo, max_pos))
            else:
                starts = _np.arange(lo, hi + 1, WS_STEP)
                seg = _np.lib.stride_tricks.sliding_window_view(
                    x[lo:hi + overlap], overlap)[::WS_STEP][:len(starts)]
                num = seg @ ref
                den = _np.sqrt((seg * seg).sum(axis=1)
                               * float((ref * ref).sum()))
                corr = num / _np.maximum(den, 1e-12)
                chosen = int(starts[int(_np.argmax(corr))])
        frame = x[chosen:chosen + win]
        prev = frame
        o = j * hs
        y[o:o + win] += frame * w
        wsum[o:o + win] += w
    nz = wsum > 1e-8
    y[nz] /= wsum[nz]
    return y[:target_len]


def pitch_shift(samples, sample_rate, semitones):
    """Shift pitch by semitones (float, +/-12), duration preserved.

    samples: array-like of floats. Returns a float64 NumPy array of
    the same length, with onsets at the same times. A zero shift
    returns an unchanged copy.
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
    stretched = _wsola_stretch(x, ratio)
    if stretched.size == 0:
        return x.copy()
    idx = _np.linspace(0, stretched.size - 1, x.size)
    return _np.interp(idx, _np.arange(stretched.size), stretched)


# ---------------------------------------------------------------------------
# Formant-preserving pitch shift (DSP-1)
# ---------------------------------------------------------------------------

#: STFT framing for the formant correction (PRD F1: 2048/512).
FORMANT_N_FFT = 2048
FORMANT_HOP = 512
#: True-envelope iteration count (Röbel-style: re-lifter the log
#: spectrum raised to at least the current envelope, so the envelope
#: tracks harmonic peaks instead of the valley floor).
FORMANT_ENVELOPE_ITERATIONS = 3
#: Lifter cutoff as a fraction of the frame's estimated pitch
#: period (the cepstral peak in the search range below), clamped to
#: [_LIFTER_MIN_SEC, _LIFTER_MAX_SEC]. The cutoff must sit below the
#: pitch quefrency to strip the source ripple, yet high enough to
#: follow formant bumps a few hundred Hz wide; a fixed very short
#: cutoff over-smooths the envelope and the formant peaks wander.
_LIFTER_PITCH_FRACTION = 0.75
_LIFTER_MIN_SEC = 0.001
_LIFTER_MAX_SEC = 0.012
#: Quefrency search range for the pitch peak (50-400 Hz).
_PITCH_QUEFRENCY_MIN_SEC = 0.0025
_PITCH_QUEFRENCY_MAX_SEC = 0.020


def _spectral_envelope(spectra, sample_rate, n_fft):
    """Per-frame spectral envelope via real-cepstrum liftering.

    spectra: (n_frames, n_fft//2 + 1) complex STFT. Returns a
    positive real envelope array of the same shape. The lifter is a
    Hann taper ending at _LIFTER_PITCH_FRACTION x the frame's pitch
    period (clamped); the estimate is refined by true-envelope
    iteration (raise the log spectrum to the envelope, re-lifter).
    """
    logmag = _np.log(_np.maximum(_np.abs(spectra), 1e-12))
    cep = _np.fft.irfft(logmag, n=n_fft, axis=1)
    lo = max(1, int(sample_rate * _PITCH_QUEFRENCY_MIN_SEC))
    hi = min(n_fft // 2, int(sample_rate * _PITCH_QUEFRENCY_MAX_SEC))
    if hi <= lo:
        hi = lo + 1
    qp = lo + _np.argmax(_np.abs(cep[:, lo:hi]), axis=1)
    cutoff = _np.clip(_LIFTER_PITCH_FRACTION * qp,
                      sample_rate * _LIFTER_MIN_SEC,
                      sample_rate * _LIFTER_MAX_SEC)
    q = _np.arange(n_fft)
    qq = _np.minimum(q, n_fft - q).astype(_np.float64)[None, :]
    weights = _np.where(
        qq <= cutoff[:, None],
        0.5 + 0.5 * _np.cos(_np.pi * qq / cutoff[:, None]), 0.0)

    def lift(lm):
        c = _np.fft.irfft(lm, n=n_fft, axis=1)
        return _np.real(_np.fft.rfft(c * weights, n=n_fft, axis=1))

    log_env = lift(logmag)
    for _ in range(FORMANT_ENVELOPE_ITERATIONS):
        log_env = lift(_np.maximum(logmag, log_env))
    return _np.exp(log_env)


def pitch_shift_formant(samples, sample_rate, semitones):
    """Pitch shift preserving the spectral envelope (formants).

    Same contract as pitch_shift (float buffer, input length
    preserved, +/-12 semitone range), but the WSOLA-shifted spectrum
    is divided by its own per-frame cepstral envelope and re-imposed
    with the original's envelope, so vocal character survives the
    shift. Buffers shorter than the STFT window and all-silent input
    fall back to plain pitch_shift.
    """
    _need_numpy()
    # pitch_shift validates semitones (finite, within range).
    shifted = pitch_shift(samples, sample_rate, semitones)
    x = _np.asarray(samples, dtype=_np.float64)
    if (x.size < FORMANT_N_FFT or float(semitones) == 0.0
            or not _np.any(x)):
        return shifted
    from . import repair as _repair

    n_fft, hop = FORMANT_N_FFT, FORMANT_HOP
    pad = n_fft
    mode = "reflect" if x.size > pad else "edge"
    xp = _np.pad(x, (pad, pad), mode=mode)
    sp = _np.pad(_np.asarray(shifted, dtype=_np.float64),
                 (pad, pad), mode=mode)
    spec_orig, _, _ = _repair._stft(xp, n_fft, hop)
    spec_shift, _, _ = _repair._stft(sp, n_fft, hop)
    env_orig = _spectral_envelope(spec_orig, sample_rate, n_fft)
    env_shift = _spectral_envelope(spec_shift, sample_rate, n_fft)
    corrected = spec_shift * (env_orig / _np.maximum(env_shift, 1e-12))
    out = _np.asarray(_repair._istft(corrected, n_fft, hop, xp.size),
                      dtype=_np.float64)
    out = out[pad:pad + x.size]
    if out.size < x.size:
        out = _np.pad(out, (0, x.size - out.size))
    return out[:x.size].copy()
