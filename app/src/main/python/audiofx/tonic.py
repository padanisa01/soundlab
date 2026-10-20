"""Tonic (Sa) detection and swara notation for a performance.

Two public entry points, both pure analysis (inputs never mutated):

    detect_tonic(samples, sample_rate) -> dict or None
        The piece's tonic from its long-term spectrum: the tanpura /
        shruti drone is present in every frame, so |STFT| averaged
        over the whole file concentrates it. Candidate fundamentals
        (prominent peaks, 50-300 Hz) are scored by their harmonic
        series; a candidate whose f0/2 scores nearly as well wins over
        it (Pa is Sa's 3rd harmonic, but Sa is not a harmonic of Pa,
        so the full series separates them). None when no candidate
        exists.

    notate_song(samples, sample_rate) -> dict
        detect_tonic + a predominant-melody transcription. The
        melody track is a salience function (Salamon & Gomez style):
        per frame, spectral peaks are picked and every f0 candidate
        on a 10-cent grid scores the weighted sum of peak magnitudes
        at its harmonic positions, so neither harmonics nor
        accompaniment can hijack the pitch the way a plain
        autocorrelation can. Frames are quantized to the nearest
        swara relative to Sa and segmented into note events.
        "swaras" reports each distinct pitch centre with its mass
        and width: narrow = fixed note, wide = kampita / gamaka
        oscillation whose centre must not be read as a fixed class.

NumPy is required (spectral work), following the repair.py
convention.
"""

import math

try:
    import numpy as _np

    _HAVE_NUMPY = True
except ImportError:  # pragma: no cover
    _np = None
    _HAVE_NUMPY = False


def _need_numpy():
    if not _HAVE_NUMPY:
        raise RuntimeError("tonic requires NumPy")


# ---------------------------------------------------------------------------
# Tunables
# ---------------------------------------------------------------------------

#: Tanpura / shruti tonic range, Hz.
TONIC_F0_LO = 50.0
TONIC_F0_HI = 300.0
#: LTAS FFT size (about 3 Hz bins at 48 kHz).
TONIC_N_FFT = 16384
TONIC_LTAS_HOP = 8192
#: Harmonics in the tonic series score.
TONIC_N_HARM = 10
#: Peak prominence over the neighbourhood median (LTAS candidates).
TONIC_PROM = 1.35
#: f0/2 (or f0/3) wins if it scores >= this fraction of the winner.
TONIC_SUBHARM_RATIO = 0.75
#: Melody tracker: window/hop and f0 search range.
MELODY_WIN_SEC = 0.046
MELODY_HOP_SEC = 0.010
MELODY_F0_LO = 80.0
MELODY_F0_HI = 900.0
#: Harmonic-match tolerance for the salience sum, cents.
MELODY_MATCH_CENTS = 35.0
#: Minimum peak mass (relative) for a swara to count as distinct.
MELODY_MIN_REL = 0.08
#: A peak counts as FIXED when its histogram width is <= this (cents).
SWARA_FIXED_STD = 35.0
#: Note segmentation: runs shorter than this merge / are absorbed.
NOTATE_MIN_NOTE_SEC = 0.08
NOTATE_MIN_REST_SEC = 0.15

_NOTE_NAMES = ["C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A",
               "A#", "B"]
# Pitch-class names by semitone index from Sa. Index 6 is tivra Ma
# (rare); Pa is 7. (An earlier draft omitted index 6 and shifted
# every label from Pa onward by one semitone.)
SWARA_NAMES = ["Sa", "r", "R", "g", "G", "m", "M", "Pa", "d", "D",
               "n", "N"]
_NOTATE_NAMES = ["S", "r", "R", "g", "G", "m", "M", "P", "d", "D", "n", "N"]


# ---------------------------------------------------------------------------
# Tonic detection
# ---------------------------------------------------------------------------

def _ltas(x, sr):
    """Long-term average magnitude spectrum (|STFT| mean)."""
    win = _np.hanning(TONIC_N_FFT)
    n_frames = 1 + max(0, (len(x) - TONIC_N_FFT) // TONIC_LTAS_HOP)
    acc = _np.zeros(TONIC_N_FFT // 2 + 1)
    for i in range(n_frames):
        seg = x[i * TONIC_LTAS_HOP: i * TONIC_LTAS_HOP + TONIC_N_FFT]
        if len(seg) < TONIC_N_FFT:
            seg = _np.pad(seg, (0, TONIC_N_FFT - len(seg)))
        acc += _np.abs(_np.fft.rfft(seg * win))
    return _np.fft.rfftfreq(TONIC_N_FFT, 1.0 / sr), acc / max(n_frames, 1)


def _at(freqs, mag, f):
    return float(_np.interp(f, freqs, mag))


def _series_score(freqs, mag, f0):
    return sum(_at(freqs, mag, k * f0) / math.sqrt(k)
               for k in range(1, TONIC_N_HARM + 1)
               if k * f0 < freqs[-1])


def _candidates(freqs, mag):
    """Prominent local peaks of the LTAS in the tonic range."""
    out = []
    lo = int(_np.searchsorted(freqs, TONIC_F0_LO))
    hi = int(_np.searchsorted(freqs, TONIC_F0_HI))
    for j in range(max(1, lo), min(len(mag) - 1, hi)):
        if not (mag[j] >= mag[j - 1] and mag[j] >= mag[j + 1]):
            continue
        nb = _np.concatenate([mag[max(0, j - 12):j], mag[j + 1:j + 13]])
        if mag[j] > TONIC_PROM * float(_np.median(nb)):
            out.append(float(freqs[j]))
    return out


def _refine_f0(freqs, mag, f0):
    """FFT bins quantize f0 to ~25 cents at 200 Hz; rescan the
    series score on a fine cents grid around the winner."""
    best_s, best_f = _series_score(freqs, mag, f0), f0
    for cents in range(-40, 41, 2):
        f = f0 * 2 ** (cents / 1200.0)
        s = _series_score(freqs, mag, f)
        if s > best_s:
            best_s, best_f = s, f
    return best_f


def note_of(f):
    """(concert note name, cents deviation) for a frequency."""
    midi = 69 + 12 * math.log2(f / 440.0)
    nearest = int(round(midi))
    cents = int(round((midi - nearest) * 100))
    return ("%s%d" % (_NOTE_NAMES[nearest % 12], nearest // 12 - 1),
            cents)


def detect_tonic(samples, sample_rate):
    """Tonic (Sa) of a performance. Returns a dict or None.

    Keys: sa_hz, note, cents, score, runner_up_rel.
    """
    _need_numpy()
    x = _np.asarray(samples, dtype=_np.float64)
    if x.size < TONIC_N_FFT:
        x = _np.pad(x, (0, TONIC_N_FFT - x.size))
    freqs, mag = _ltas(x, sample_rate)
    cands = _candidates(freqs, mag)
    if not cands:
        return None
    scored = sorted(((_series_score(freqs, mag, f0), f0) for f0 in cands),
                    reverse=True)
    best_s, best_f = scored[0]
    runner_up = scored[1][0] / best_s if len(scored) > 1 else 0.0
    # Subharmonic preference: walk down while f0/n scores nearly as
    # well (Pa posing as a fundamental is the classic failure).
    changed = True
    while changed:
        changed = False
        for n in (2, 3):
            for s, f in scored:
                if abs(f - best_f / n) / (best_f / n) <= 0.02 \
                        and s >= TONIC_SUBHARM_RATIO * best_s:
                    best_s, best_f = s, f
                    changed = True
                    break
            if changed:
                break
    sa = _refine_f0(freqs, mag, best_f)
    note, cents = note_of(sa)
    return {"sa_hz": round(float(sa), 2), "note": note, "cents": cents,
            "score": round(float(best_s), 3),
            "runner_up_rel": round(float(runner_up), 3)}


# ---------------------------------------------------------------------------
# Melody tracking (salience) and notation
# ---------------------------------------------------------------------------

def track_melody(x, sample_rate):
    """Per-frame salience f0 track. Returns (times, f0s, rms) with
    NaN on unvoiced frames, median-smoothed across voiced runs."""
    sr = sample_rate
    n_win = int(MELODY_WIN_SEC * sr)
    hop = int(MELODY_HOP_SEC * sr)
    n_fft = 1
    while n_fft < 4 * n_win:
        n_fft *= 2
    freqs = _np.fft.rfftfreq(n_fft, 1.0 / sr)
    n_frames = max(0, (len(x) - n_win) // hop)
    rms = _np.array([_np.sqrt(_np.mean(x[i * hop:i * hop + n_win] ** 2))
                     for i in range(n_frames)])
    if n_frames == 0:
        return _np.array([]), _np.array([]), rms
    thresh = max(float(_np.percentile(rms, 90)) * 0.15, 1e-4)
    win = _np.hanning(n_win)
    cands = _np.arange(1200 * math.log2(MELODY_F0_LO),
                       1200 * math.log2(MELODY_F0_HI), 10.0)
    harm_off = 1200 * _np.log2(_np.arange(1, 11))
    weights = 1.0 / _np.sqrt(_np.arange(1, 11))
    times, f0s = [], []
    for n in range(n_frames):
        times.append(n * hop / sr)
        if rms[n] < thresh:
            f0s.append(_np.nan)
            continue
        mag = _np.abs(_np.fft.rfft(x[n * hop:n * hop + n_win] * win,
                                   n_fft))
        mid = mag[1:-1]
        pk = _np.flatnonzero((mid > mag[:-2]) & (mid >= mag[2:])
                             & (mid > mag.max() * 0.02)) + 1
        if pk.size == 0:
            f0s.append(_np.nan)
            continue
        a = _np.log(mag[pk - 1] + 1e-12)
        b = _np.log(mag[pk] + 1e-12)
        c = _np.log(mag[pk + 1] + 1e-12)
        d = 0.5 * (a - c) / (a - 2 * b + c + 1e-12)
        f = freqs[pk] + _np.clip(d, -1, 1) * (freqs[1] - freqs[0])
        pc = _np.sort(1200 * _np.log2(_np.maximum(f, 1e-6)))
        pm = mag[pk][_np.argsort(1200 * _np.log2(_np.maximum(f, 1e-6)))]
        sal = _np.zeros(len(cands))
        for w, off in zip(weights, harm_off):
            tgt = cands + off
            ins = _np.searchsorted(pc, tgt)
            for side in (0, -1):
                jj = _np.clip(ins + side, 0, len(pc) - 1)
                hit = _np.abs(pc[jj] - tgt) <= MELODY_MATCH_CENTS
                cand_idx = _np.flatnonzero(hit)
                if cand_idx.size:
                    sal[cand_idx] += w * _np.sqrt(pm[jj[hit]])
        f0 = 2 ** (cands[int(_np.argmax(sal))] / 1200.0)
        # Subharmonic rejection: 110 Hz "scores" for a real 330 Hz
        # note because its series coincides with real peaks, but its
        # fundamental has no spectral peak. Restrict to candidates
        # that do, within 85% of the max salience.
        dist = _np.abs(pc[:, None] - cands[None, :]).min(axis=0)
        qual = (dist <= 50.0) & (sal >= 0.85 * sal.max())
        best = int(_np.argmax(_np.where(qual, sal, -1.0)))
        f0 = 2 ** (cands[best] / 1200.0)
        # Snap to the real fundamental peak when one is near (the
        # match window otherwise leaves a plateau; argmax lands
        # anywhere in it, a systematic bias).
        tgt = cands[best]
        ins = int(_np.searchsorted(pc, tgt))
        for side in (0, -1):
            jj = ins + side
            if 0 <= jj < len(pc) and abs(pc[jj] - tgt) <= MELODY_MATCH_CENTS:
                f0 = 2 ** (pc[jj] / 1200.0)
                break
        f0s.append(f0)
    times = _np.array(times)
    f0s = _np.array(f0s)
    ok = _np.isfinite(f0s)
    if ok.sum() >= 5:
        idx = _np.arange(len(f0s))
        fill = _np.interp(idx, idx[ok], f0s[ok])
        padded = _np.pad(fill, (2, 2), mode="edge")
        med = _np.median(_np.stack([padded[i:i + len(fill)]
                                    for i in range(5)]), axis=0)
        f0s[ok] = med[ok]
    return times, f0s, rms


def _hist_peaks(smooth, min_rel):
    """Local maxima on a circular 1200-bin histogram."""
    peaks = []
    for i in range(1200):
        window = _np.concatenate([smooth[max(0, i - 25):i],
                                  smooth[i + 1:i + 26]])
        if smooth[i] <= 0 or smooth[i] < window.max():
            continue
        if smooth[i] >= min_rel * smooth.max():
            peaks.append((i, float(smooth[i] / smooth.max())))
    peaks.sort(key=lambda p: -p[1])
    return peaks


def _swara_stats(times, f0s, rms, sa):
    """Distinct swaras from the melody track: each histogram peak
    gets (swara_index, cents_dev, rel_mass, width_std, is_fixed)."""
    ok = _np.isfinite(f0s)
    if not ok.any():
        return []
    cents = (1200.0 * _np.log2(f0s[ok] / sa)) % 1200.0
    wts = rms[ok]
    hist = _np.zeros(1200)
    _np.add.at(hist, _np.floor(cents).astype(int) % 1200, wts)
    kernel = _np.hanning(41)
    kernel /= kernel.sum()
    ext = _np.concatenate([hist[-20:], hist, hist[:20]])
    smooth = _np.convolve(ext, kernel, mode="same")[20:-20]
    peaks = _hist_peaks(smooth, MELODY_MIN_REL)
    out = []
    for b, rel in peaks:
        sw = int(round(b / 100.0)) % 12
        dev = int(round(((b - 100 * round(b / 100.0) + 50) % 100) - 50))
        idx = (_np.arange(b - 80, b + 81) % 1200)
        own = _np.ones(len(idx), bool)
        for q, _ in peaks:
            if q == b:
                continue
            dself = _np.abs((idx - b + 600) % 1200 - 600)
            doth = _np.abs((idx - q + 600) % 1200 - 600)
            own &= dself <= doth
        w = smooth[idx][own]
        std = (float(_np.sqrt(
            (((idx[own] - b + 600) % 1200 - 600) ** 2 * w).sum()
            / w.sum())) if w.sum() > 0 else 0.0)
        out.append({"index": sw, "name": SWARA_NAMES[sw],
                    "cents_dev": dev, "mass": round(rel, 3),
                    "width_cents": round(std, 1),
                    "fixed": bool(std <= SWARA_FIXED_STD)})
    return out


def _symbol(cents):
    """Notation symbol for a pitch: nearest swara, octave marked
    with ' above and , below the Sa octave."""
    k = int(math.floor(cents / 100.0 + 0.5))
    octv, cls = divmod(k, 12)
    base = _NOTATE_NAMES[cls]
    if octv > 0:
        return base + "'" * octv
    if octv < 0:
        return base + "," * (-octv)
    return base


def _note_events(times, f0s, sa, hop_sec):
    """Segment the f0 track into note events: (start, dur, symbol)."""
    runs = []
    for t, f0 in zip(times, f0s):
        s = "-" if not _np.isfinite(f0) else _symbol(
            1200.0 * math.log2(f0 / sa))
        if runs and runs[-1][2] == s:
            runs[-1][1] = t
        else:
            runs.append([t, t, s])
    events = []
    for st, en, s in runs:
        dur = en - st + hop_sec
        if s != "-" and dur < NOTATE_MIN_NOTE_SEC:
            if events and events[-1][2] != "-":
                events[-1][1] = en
            continue
        if s == "-" and dur < NOTATE_MIN_REST_SEC and events:
            events[-1][1] = en
            continue
        events.append([st, en, s])
    return [{"start": round(float(st), 2),
             "dur": round(float(en - st + hop_sec), 2),
             "sym": s} for st, en, s in events]


def notation_text(info):
    """Render a notate_song() result as plain text (4 s bars)."""
    lines = ["Sa = %.1f Hz (%s %+d cents); %d events; '-' = rest"
             % (info["sa_hz"], info["note"], info["cents"],
                len(info["events"])), ""]
    cur_bar = -1
    parts = []
    for ev in info["events"]:
        bar = int(ev["start"] // 4)
        if bar != cur_bar:
            cur_bar = bar
            parts.append("\n%3d:%02d  " % (int(ev["start"] // 60),
                                           int(ev["start"] % 60)))
        parts.append("%s(%.1f) " % (ev["sym"], round(ev["dur"], 1)))
    lines.append("".join(parts))
    return "\n".join(lines)


def notate_song(samples, sample_rate):
    """Tonic + predominant-melody notation for a performance.

    Returns None when no tonic can be found; otherwise a dict with
    the tonic fields, the distinct "swaras" stats, the segmented
    "events", and a preformatted "text" rendering.
    """
    _need_numpy()
    tonic = detect_tonic(samples, sample_rate)
    if tonic is None:
        return None
    x = _np.asarray(samples, dtype=_np.float64)
    times, f0s, rms = track_melody(x, sample_rate)
    sa = tonic["sa_hz"]
    info = dict(tonic)
    info["swaras"] = _swara_stats(times, f0s, rms, sa)
    info["events"] = _note_events(times, f0s, sa, MELODY_HOP_SEC)
    info["text"] = notation_text(info)
    return info
