"""Repair operations for the diagnostics tune-up chain.

Each function takes mono float samples in [-1.0, 1.0] and returns a new
list; inputs are never mutated. NumPy is the fast path (all spectral and
filter work is vectorized). The trivial ops have pure-Python fallbacks;
the spectral ops raise a clear error without NumPy, following the
effects.py convention.

Op registry (names used in render_chain and by diagnose.fix):
    dc_remove, fade_ends, silence_trim, hum_notch, declip, denoise,
    deplosive, deess, level, loudness, eq_balance
"""

import math

try:
    import numpy as _np

    _HAVE_NUMPY = True
except ImportError:  # pragma: no cover
    _np = None
    _HAVE_NUMPY = False


def _need_numpy(op):
    if not _HAVE_NUMPY:
        raise RuntimeError("repair.%s requires NumPy" % op)


def _as_np(samples):
    return _np.asarray(samples, dtype=_np.float64)


# ---------------------------------------------------------------------------
# STFT helpers (spectral ops)
# ---------------------------------------------------------------------------

def _stft(samples, n_fft=2048, hop=None):
    """Hann-windowed STFT. Returns (spectra, n_fft, hop); spectra is a
    complex array of shape (n_frames, n_fft//2 + 1)."""
    _need_numpy("_stft")
    if hop is None:
        hop = n_fft // 2
    x = _as_np(samples)
    n = x.size
    if n < n_fft:
        x = _np.pad(x, (0, n_fft - n))
        n = n_fft
    # Enough frames to cover the tail (ceil), zero-padding the last one.
    n_frames = 1 + max(0, (n - n_fft + hop - 1) // hop)
    need = (n_frames - 1) * hop + n_fft
    if need > n:
        x = _np.pad(x, (0, need - n))
    win = _np.hanning(n_fft)
    spectra = _np.empty((n_frames, n_fft // 2 + 1), dtype=_np.complex128)
    for i in range(n_frames):
        spectra[i] = _np.fft.rfft(x[i * hop : i * hop + n_fft] * win)
    return spectra, n_fft, hop


def _istft(spectra, n_fft, hop, length):
    """Inverse STFT with overlap-add. _stft already applied the analysis
    window, so frames are added as-is and normalized by the window sum
    (Hann is COLA for hop = n_fft/2^m). Re-windowing here would weight by
    win^2 and break reconstruction."""
    _need_numpy("_istft")
    n_frames = spectra.shape[0]
    out_len = (n_frames - 1) * hop + n_fft
    out = _np.zeros(out_len)
    win = _np.hanning(n_fft)
    for i in range(n_frames):
        frame = _np.fft.irfft(spectra[i], n_fft)
        out[i * hop : i * hop + n_fft] += frame
    wsum = _np.zeros(out_len)
    for i in range(n_frames):
        wsum[i * hop : i * hop + n_fft] += win
    nz = wsum > 1e-9
    out[nz] /= wsum[nz]
    return out[:length]


def _freqs(n_fft, sample_rate):
    return _np.fft.rfftfreq(n_fft, 1.0 / sample_rate)


# ---------------------------------------------------------------------------
# Trivial ops (pure Python)
# ---------------------------------------------------------------------------

def dc_remove(samples):
    """Subtract the mean (DC offset)."""
    if _HAVE_NUMPY:
        x = _as_np(samples)
        if x.size == 0:
            return _np.zeros(0)
        return x - _np.mean(x)
    if not samples:
        return []
    mean = sum(samples) / len(samples)
    return [s - mean for s in samples]


def fade_ends(samples, sample_rate, fade_sec=0.03):
    """Short linear fades at both ends to remove abrupt starts/stops.

    30 ms clears the abrupt_ends check (2 ms edge window at 10% of peak:
    2/30 < 0.1) while staying inaudible."""
    n = len(samples)
    if n == 0:
        return _np.zeros(0) if _HAVE_NUMPY else []
    fade_n = max(1, min(int(fade_sec * sample_rate), n // 2))
    if _HAVE_NUMPY:
        out = _as_np(samples).copy()
        ramp = (_np.arange(fade_n) + 1) / (fade_n + 1)
        out[:fade_n] *= ramp
        out[n - fade_n :] *= ramp[::-1]
        return out
    out = list(samples)
    for i in range(fade_n):
        t = (i + 1) / (fade_n + 1)
        out[i] *= t
        out[n - 1 - i] *= t
    return out


# ---------------------------------------------------------------------------
# Silence trim
# ---------------------------------------------------------------------------

def _window_rms_np(x, win):
    n = (x.size // win) * win
    if n == 0:
        return _np.array([])
    return _np.sqrt(_np.mean(x[:n].reshape(-1, win) ** 2, axis=1))


def silence_trim(samples, sample_rate, threshold_db=-45.0,
                 min_silence_sec=0.5, keep_sec=0.15, edge_fade_sec=0.005):
    """Remove the middle of long silent regions, keeping ``keep_sec`` of
    silence at each edge with a short crossfade so cuts are click-free."""
    n = len(samples)
    if n == 0:
        return []
    win = max(1, int(0.05 * sample_rate))
    thresh = 10.0 ** (threshold_db / 20.0)
    if _HAVE_NUMPY:
        x = _as_np(samples)
        rms = _window_rms_np(x, win)
        silent = rms < thresh
    else:
        silent = []
        for i in range(0, n, win):
            seg = samples[i : i + win]
            r = math.sqrt(sum(s * s for s in seg) / max(1, len(seg)))
            silent.append(r < thresh)
    # Merge window flags into regions (in samples).
    regions = []
    start = None
    nw = len(silent)
    for i, s in enumerate(silent):
        if s and start is None:
            start = i
        elif not s and start is not None:
            regions.append((start * win, i * win))
            start = None
    if start is not None:
        regions.append((start * win, n))
    min_len = int(min_silence_sec * sample_rate)
    keep_n = int(keep_sec * sample_rate)
    fade_n = max(1, int(edge_fade_sec * sample_rate))
    # Build kept segments: cut the middle out of long silent regions.
    cuts = []  # (cut_start, cut_end) in samples
    for rs, re in regions:
        if re - rs >= min_len and re - rs > 2 * keep_n:
            cuts.append((rs + keep_n, re - keep_n))
    if not cuts:
        return _as_np(samples) if _HAVE_NUMPY else list(samples)
    out = []
    pos = 0
    for cs, ce in cuts:
        out.extend(samples[pos:cs])
        # Crossfade the splice.
        seg_a = out[-fade_n:] if len(out) >= fade_n else []
        seg_b = samples[ce : ce + fade_n]
        if seg_a and len(seg_b) == fade_n:
            out = out[: -fade_n]
            for i in range(fade_n):
                t = (i + 1) / (fade_n + 1)
                out.append(seg_a[i] * (1 - t) + seg_b[i] * t)
            pos = ce + fade_n
        else:
            pos = ce
    out.extend(samples[pos:])
    return _np.asarray(out, dtype=_np.float64) if _HAVE_NUMPY else out


# ---------------------------------------------------------------------------
# Hum notch (spectral)
# ---------------------------------------------------------------------------

def _padded_stft_process(x, n_fft, hop, frame_fn):
    """Run frame_fn(spectra) -> spectra with reflect padding, so the wsum
    edge blowup from sharp spectral edits lands in the padding, which is
    trimmed afterwards. Returns a list the same length as x."""
    pad = n_fft
    xp = _np.pad(x, (pad, pad), mode="reflect")
    spectra, _, _ = _stft(xp, n_fft, hop)
    spectra = frame_fn(spectra)
    out = _np.asarray(_istft(spectra, n_fft, hop, xp.size))
    return out[pad : pad + x.size]


def hum_notch(samples, sample_rate, freq_hz):
    """Notch the mains fundamental and its 2nd/3rd harmonics with a
    frame-based spectral notch (long FFT for Hz-level precision)."""
    _need_numpy("hum_notch")
    x = _as_np(samples)
    if x.size == 0:
        return []
    n_fft = 16384
    hop = n_fft // 4

    def apply(spectra):
        freqs = _np.fft.rfftfreq(n_fft, 1.0 / sample_rate)
        mask = _np.ones_like(freqs)
        for mult in (1, 2, 3):
            f0 = freq_hz * mult
            if f0 >= sample_rate / 2:
                break
            d = _np.abs(freqs - f0)
            # Full cut within +/-4 Hz, smooth ramp to unity at +/-10 Hz.
            m = _np.ones_like(d)
            m[d <= 4.0] = 0.02
            ramp = (d > 4.0) & (d < 10.0)
            m[ramp] = 0.02 + 0.98 * (d[ramp] - 4.0) / 6.0
            mask *= m
        return spectra * mask[_np.newaxis, :]

    return _padded_stft_process(x, n_fft, hop, apply)


# ---------------------------------------------------------------------------
# Declip (partial: tames harshness, cannot restore lost peaks)
# ---------------------------------------------------------------------------

def declip(samples, threshold=0.999, context=32):
    """Interpolate over clipped runs (|x| >= threshold) with cubic Hermite
    segments built from the surrounding unclipped samples. Reduces the
    harshness of clipping; it cannot recover the original peaks."""
    n = len(samples)
    if n == 0:
        return _np.zeros(0) if _HAVE_NUMPY else []
    if _HAVE_NUMPY:
        x = _as_np(samples)
        if not _np.any(_np.abs(x) >= threshold):
            return x.copy()  # common case: single vector pass, no scan
        work = x.tolist()
    else:
        work = list(samples)
    out = list(work)
    i = 0
    while i < n:
        if abs(out[i]) < threshold:
            i += 1
            continue
        a = i
        while i < n and abs(out[i]) >= threshold:
            i += 1
        b = i  # run is [a, b)
        # Edge samples and slopes for the Hermite segment.
        x0 = out[a - 1] if a > 0 else out[b] if b < n else 0.0
        x1 = out[b] if b < n else out[a - 1] if a > 0 else 0.0
        m0 = (out[a - 1] - out[a - 2]) if a > 1 else 0.0
        m1 = (out[b + 1] - out[b]) if b + 1 < n else 0.0
        run = b - a
        for k in range(run):
            t = (k + 1) / (run + 1)
            t2 = t * t
            t3 = t2 * t
            h00 = 2 * t3 - 3 * t2 + 1
            h10 = t3 - 2 * t2 + t
            h01 = -2 * t3 + 3 * t2
            h11 = t3 - t2
            out[a + k] = h00 * x0 + h10 * run * m0 + h01 * x1 + h11 * run * m1
    return _np.asarray(out, dtype=_np.float64) if _HAVE_NUMPY else out


# ---------------------------------------------------------------------------
# Denoise (partial: spectral subtraction, conservative)
# ---------------------------------------------------------------------------

def denoise(samples, sample_rate, strength=0.5, floor=0.15):
    """Spectral subtraction: learn a per-bin noise profile from the
    quietest frames, then subtract. Conservative by default; strong
    settings cause watery artifacts, so the UI should label this
    "reduce hiss", never "remove noise"."""
    _need_numpy("denoise")
    x = _as_np(samples)
    if x.size == 0:
        return []
    n_fft = 2048
    hop = n_fft // 2

    def apply(spectra):
        mag = _np.abs(spectra)
        n_frames = mag.shape[0]
        if n_frames < 8:
            return spectra
        frame_energy = _np.mean(mag ** 2, axis=1)
        n_noise = max(2, n_frames // 10)
        noise_idx = _np.argpartition(frame_energy, n_noise)[:n_noise]
        noise_profile = _np.median(mag[noise_idx], axis=0)
        sub = strength * noise_profile[_np.newaxis, :]
        clean_mag = _np.maximum(mag - sub, floor * mag)
        gain = clean_mag / _np.maximum(mag, 1e-12)
        return spectra * gain

    return _padded_stft_process(x, n_fft, hop, apply)


# ---------------------------------------------------------------------------
# Deplosive: dynamic low-cut on plosive bursts
# ---------------------------------------------------------------------------

def _fft_lowpass(x, cutoff_hz, sample_rate):
    """Zero-phase lowpass via FFT (brickwall with smooth rolloff)."""
    n = x.size
    spec = _np.fft.rfft(x)
    freqs = _np.fft.rfftfreq(n, 1.0 / sample_rate)
    # Smooth rolloff over half an octave to avoid ringing.
    edge = cutoff_hz * _np.sqrt(2.0)
    resp = _np.ones_like(freqs)
    above = freqs > cutoff_hz
    resp[above] = _np.maximum(
        0.0, 1.0 - (freqs[above] - cutoff_hz) / (edge - cutoff_hz))
    return _np.fft.irfft(spec * resp, n)


def _moving_avg(x, win):
    # Causal moving average, length-preserving: out[i] = mean(x[i-win+1..i])
    # with zero padding at the start.
    c = _np.cumsum(_np.pad(x, (win - 1, 0)))
    prev = _np.concatenate(([0.0], c[:-1]))[:x.size]
    return (c[win - 1:] - prev) / win


def deplosive(samples, sample_rate, cutoff_hz=150.0, reduction=0.9):
    """Detect plosive thumps (transient low-frequency bursts) and duck the
    low band only where they happen."""
    _need_numpy("deplosive")
    x = _as_np(samples)
    n = x.size
    if n == 0:
        return []
    low = _fft_lowpass(x, cutoff_hz, sample_rate)
    env_win = max(1, int(0.02 * sample_rate))
    env = _moving_avg(_np.abs(low), env_win)
    med = _np.median(env)
    thresh = max(0.02, 4.0 * med)
    mask = (env > thresh).astype(float)
    # Dilate ~30 ms each side and smooth the edges (~10 ms ramps).
    dil = max(1, int(0.03 * sample_rate))
    sm = max(1, int(0.01 * sample_rate))
    if _np.any(mask):
        idx = _np.where(mask > 0)[0]
        lo = max(0, idx.min() - dil)
        hi = min(n, idx.max() + dil + 1)
        m2 = _np.zeros(n)
        m2[lo:hi] = 1.0
        mask = _moving_avg(_np.pad(m2, (sm, sm))[: n + sm], sm)[:n]
    out = x - mask * low * reduction
    return out


# ---------------------------------------------------------------------------
# De-esser: dynamic cut of the sibilant band
# ---------------------------------------------------------------------------

def _fft_bandpass(x, lo_hz, hi_hz, sample_rate):
    n = x.size
    spec = _np.fft.rfft(x)
    freqs = _np.fft.rfftfreq(n, 1.0 / sample_rate)
    resp = ((freqs >= lo_hz) & (freqs <= hi_hz)).astype(float)
    return _np.fft.irfft(spec * resp, n)


def deess(samples, sample_rate, lo_hz=5000.0, hi_hz=9000.0, reduction_db=8.0):
    """Split-band de-esser: where the 5-9 kHz band dominates, duck just
    that band."""
    _need_numpy("deess")
    x = _as_np(samples)
    n = x.size
    if n == 0:
        return []
    band = _fft_bandpass(x, lo_hz, hi_hz, sample_rate)
    frame = max(1, int(0.02 * sample_rate))
    nb = n // frame
    if nb == 0:
        return x
    b_rms = _np.sqrt(_np.mean(band[: nb * frame].reshape(nb, frame) ** 2,
                              axis=1))
    x_rms = _np.sqrt(_np.mean(x[: nb * frame].reshape(nb, frame) ** 2,
                              axis=1))
    sib = (b_rms > 0.35 * x_rms) & (b_rms > 10.0 ** (-40.0 / 20.0))
    att = 10.0 ** (-reduction_db / 20.0)
    g = _np.where(sib, att, 1.0)
    # Smooth the per-frame gain (3-frame moving average) and upsample.
    g = _moving_avg(_np.pad(g, (1, 1)), 3)[:nb]
    g_full = _np.interp(_np.arange(n), _np.arange(nb) * frame + frame / 2, g,
                        left=g[0], right=g[-1])
    out = x - (1.0 - g_full) * band
    return out


# ---------------------------------------------------------------------------
# Level: slow gain riding for consistent loudness
# ---------------------------------------------------------------------------

def level(samples, sample_rate, window_sec=0.5, max_gain_db=12.0):
    """Gentle post-production leveling: ride the gain toward the median
    window RMS with slow attack/release. Narrows quiet/loud swings."""
    n = len(samples)
    if n == 0:
        return []
    win = max(1, int(window_sec * sample_rate))
    if _HAVE_NUMPY:
        x = _as_np(samples)
        nw = max(1, n // win)
        rms = _np.sqrt(_np.mean(
            x[: nw * win].reshape(nw, win) ** 2, axis=1))
        target = float(_np.median(rms)) + 1e-9
        raw = _np.clip(target / (rms + 1e-9),
                       10.0 ** (-max_gain_db / 20.0),
                       10.0 ** (max_gain_db / 20.0))
        # Slow smoothing over windows (attack ~0.1 s, release ~0.5 s).
        sm = _np.empty_like(raw)
        g = 1.0
        a_att, a_rel = 0.5, 0.12
        for i, r in enumerate(raw):
            c = a_att if r < g else a_rel
            g += c * (r - g)
            sm[i] = g
        grid = (_np.arange(nw) + 0.5) * win
        g_full = _np.interp(_np.arange(n), grid, sm, left=sm[0],
                            right=sm[-1])
        return x * g_full
    # Pure-Python fallback.
    rms = []
    for i in range(0, n, win):
        seg = samples[i : i + win]
        rms.append(math.sqrt(sum(s * s for s in seg) / max(1, len(seg))))
    target = sorted(rms)[len(rms) // 2] + 1e-9
    lo = 10.0 ** (-max_gain_db / 20.0)
    hi = 10.0 ** (max_gain_db / 20.0)
    raw = [min(hi, max(lo, target / (r + 1e-9))) for r in rms]
    sm, g = [], 1.0
    for r in raw:
        c = 0.5 if r < g else 0.12
        g += c * (r - g)
        sm.append(g)
    out = []
    for i, s in enumerate(samples):
        gi = sm[min(i // win, len(sm) - 1)]
        out.append(s * gi)
    return out


# ---------------------------------------------------------------------------
# Loudness normalize (ITU-R BS.1770-style)
# ---------------------------------------------------------------------------

def _fir_from_response(sample_rate, freqs_hz, gains_db, ntaps=255):
    """Linear-phase FIR from a piecewise-linear log-frequency response."""
    _need_numpy("_fir_from_response")
    n_fft = 1
    while n_fft < 4 * ntaps:
        n_fft *= 2
    f = _np.fft.rfftfreq(n_fft, 1.0 / sample_rate)
    log_f = _np.log10(_np.maximum(f, 1.0))
    log_pts = _np.log10(_np.maximum(_np.asarray(freqs_hz, dtype=float), 1.0))
    g = _np.interp(log_f, log_pts, _np.asarray(gains_db, dtype=float),
                   left=gains_db[0], right=gains_db[-1])
    h_full = _np.fft.irfft(10.0 ** (g / 20.0), n_fft)
    # h_full is zero-phase: peak at index 0 with wraparound. Circular-shift
    # to causal linear phase (peak at ntaps//2), then window. (Taking the
    # middle slice instead grabs the impulse tail -- near silence.)
    h_shifted = _np.roll(h_full, ntaps // 2)
    taps = h_shifted[:ntaps] * _np.hanning(ntaps)
    return taps


def _fft_convolve_np(x, taps):
    n = x.size + taps.size - 1
    n_fft = 1
    while n_fft < n:
        n_fft *= 2
    y = _np.fft.irfft(_np.fft.rfft(x, n_fft) * _np.fft.rfft(taps, n_fft),
                      n_fft)
    # Compensate linear-phase delay (peak at ntaps//2) to keep alignment.
    delay = taps.size // 2
    return y[delay : delay + x.size]


def _k_weighting(x, sample_rate):
    """Approximate K-weighting (high shelf +4 dB, highpass 60 Hz) as FIR."""
    freqs = [20, 60, 200, 1000, 1680, 5000, 20000]
    gains = [-38.0, -3.0, 0.0, 0.0, 4.0, 4.0, 4.0]
    taps = _fir_from_response(sample_rate, freqs, gains)
    return _fft_convolve_np(x, taps)


def measure_lufs(samples, sample_rate):
    """Integrated loudness (LUFS) with absolute (-70) and relative (-10)
    gating, per ITU-R BS.1770."""
    _need_numpy("measure_lufs")
    x = _as_np(samples)
    if x.size == 0:
        return float("-inf")
    y = _k_weighting(x, sample_rate)
    block = int(0.4 * sample_rate)
    hop = block // 4
    if y.size < block:
        blocks = [y]
    else:
        n_blocks = 1 + (y.size - block) // hop
        blocks = [y[i * hop : i * hop + block] for i in range(n_blocks)]
    louds = []
    for b in blocks:
        ms = _np.mean(b ** 2)
        if ms > 0:
            louds.append(-0.691 + 10.0 * _np.log10(ms))
    louds = _np.array([l for l in louds if l > -70.0])
    if louds.size == 0:
        return float("-inf")
    rel_gate = _np.mean(10.0 ** (louds / 10.0))
    rel_gate_db = 10.0 * _np.log10(rel_gate) - 10.0
    gated = louds[louds > rel_gate_db]
    if gated.size == 0:
        return float("-inf")
    return float(10.0 * _np.log10(_np.mean(10.0 ** (gated / 10.0))))


def loudness_normalize(samples, sample_rate, target_lufs=-16.0,
                       peak_ceiling_db=-1.0):
    """Gain to the target integrated loudness, then cap peaks at the
    ceiling so the gain never introduces clipping."""
    _need_numpy("loudness_normalize")
    x = _as_np(samples)
    n = x.size
    if n == 0:
        return []
    lufs = measure_lufs(x, sample_rate)
    if lufs == float("-inf"):
        return x
    gain = 10.0 ** ((target_lufs - lufs) / 20.0)
    peak = float(_np.max(_np.abs(x)))
    ceiling = 10.0 ** (peak_ceiling_db / 20.0)
    if peak * gain > ceiling and peak > 0:
        gain = ceiling / peak
    return x * gain


# ---------------------------------------------------------------------------
# EQ balance: gentle corrective shelves
# ---------------------------------------------------------------------------

def eq_balance(samples, sample_rate, low_gain_db=0.0, high_gain_db=0.0):
    """Corrective EQ: low shelf at 250 Hz, high shelf at 5 kHz.
    Gains clamp to +/-6 dB (matches BALANCE_MAX_CUT_DB)."""
    _need_numpy("eq_balance")
    x = _as_np(samples)
    if x.size == 0:
        return []
    low_gain_db = max(-6.0, min(6.0, float(low_gain_db)))
    high_gain_db = max(-6.0, min(6.0, float(high_gain_db)))
    if abs(low_gain_db) < 0.05 and abs(high_gain_db) < 0.05:
        return x
    freqs = [20, 100, 250, 800, 2000, 5000, 12000, 20000]
    gains = [low_gain_db, low_gain_db, low_gain_db, 0.0, 0.0,
             high_gain_db, high_gain_db, high_gain_db]
    taps = _fir_from_response(sample_rate, freqs, gains)
    return _fft_convolve_np(x, taps)
