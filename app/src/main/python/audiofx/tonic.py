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
#: Fifth-down (Pa trap): the winner may be the Pa of the true Sa.
#: The 2/3 candidate must clear BOTH bars: full series >= this
#: fraction of the winner's, and independent-partials >=
#: TONIC_FIFTH_DOWN_INDEP of the winner's independent partials.
TONIC_FIFTH_DOWN_RATIO = 0.75
TONIC_FIFTH_DOWN_INDEP = 0.70
#: Fourth-up (mirror trap): the winner may be the lower Pa of the
#: true Sa (Sa a fourth above it). Same two bars.
TONIC_FOURTH_UP_RATIO = 0.75
TONIC_FOURTH_UP_INDEP = 0.70
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
#: Plateau-vs-transit segmenter (PRD F4): a frame sits on a plateau
#: when it is within this many cents of a swara centre.
PLATEAU_TOLERANCE_CENTS = 35.0
#: External pitch tracks (neural engine): a frame counts as voiced
#: when its tracker confidence is at least this.
EXTERNAL_TRACK_MIN_CONF = 0.5
#: Hysteresis voicing for external tracks: a run must reach
#: EXTERNAL_TRACK_MIN_CONF to start, but only ends when the
#: confidence falls below this exit level -- a phrase does not
#: shatter because the tracker's confidence blinked mid-note.
EXTERNAL_TRACK_CONF_LOW = 0.35
#: Unvoiced gaps up to this long between voiced runs are bridged
#: (freqs interpolated in the cents domain).
EXTERNAL_TRACK_BRIDGE_SEC = 0.07
#: Voiced runs shorter than this are tracker blips, not phrases.
EXTERNAL_TRACK_MIN_RUN_SEC = 0.05
#: Octave snap for external tracks: a voiced frame sitting within
#: this many cents of exactly one octave away from its local
#: median is the tracker's classic octave error and is folded
#: onto the median's octave.
EXTERNAL_TRACK_OCTAVE_TOL_CENTS = 60.0

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


def _indep_score(freqs, mag, f0, skip_mult):
    """Series score over harmonics NOT divisible by skip_mult: the
    partials of f0 not shared with the series a skip_mult-th above
    it (3 = the Pa above). A phantom tonic borrows most of its
    score from shared partials and scores low here."""
    total = 0.0
    for k in range(1, TONIC_N_HARM + 1):
        if k % skip_mult == 0 or k * f0 >= freqs[-1]:
            continue
        total += _at(freqs, mag, k * f0) / (k ** 0.5)
    return total


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
    # Fifth-down preference: the winner may be the Pa (3/2) of the
    # true Sa -- the trap when Sa's fundamental is weak/absent but
    # its upper partials are strong (Devi Neeyae Thunai: Sa G3 has
    # almost no 196 Hz energy; its Pa, D4, won). Require the full
    # series AND the independent partials to nearly match, so a true
    # Sa with a strong Pa (Durga, G#) is not flipped to its own
    # fifth-down phantom.
    w = _refine_f0(freqs, mag, best_f)
    t = _refine_f0(freqs, mag, w * 2.0 / 3.0)
    if t >= TONIC_F0_LO:
        ts = _series_score(freqs, mag, t)
        if ts >= TONIC_FIFTH_DOWN_RATIO * _series_score(freqs, mag, w):
            ti = _indep_score(freqs, mag, t, 3)
            wi = _indep_score(freqs, mag, w, 2)
            if wi > 0 and ti >= TONIC_FIFTH_DOWN_INDEP * wi:
                best_f, best_s = t, ts
    # Fourth-up preference: the winner may be the LOWER Pa of the
    # true Sa (Sa a fourth above it) -- the mirror trap, measured on
    # pitch-shifted Devi: the winner F#3 is B3's lower Pa, propped
    # up by the shifted D fundamental on its 2nd partial, while B3
    # itself is a missing-fundamental tonic. Same two bars; the
    # winner's partials shared with the upper candidate are its
    # multiples of 4. Ground truths stay put (Durga 0.63/0.59,
    # Rec185 0.42/0.36, Devi-after-fifth-down 0.67/0.61 all fail).
    w2 = _refine_f0(freqs, mag, best_f)
    t4 = _refine_f0(freqs, mag, w2 * 4.0 / 3.0)
    if t4 <= TONIC_F0_HI:
        ts4 = _series_score(freqs, mag, t4)
        if ts4 >= TONIC_FOURTH_UP_RATIO * _series_score(freqs, mag, w2):
            ti4 = _indep_score(freqs, mag, t4, 3)
            wi4 = _indep_score(freqs, mag, w2, 4)
            if wi4 > 0 and ti4 >= TONIC_FOURTH_UP_INDEP * wi4:
                best_f, best_s = t4, ts4
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


def _phrase_note_segments(times, cents, ps, pe, hop_sec):
    """Note segments inside one voiced phrase [ps, pe), as
    (start_idx, end_idx, centre_cents) triples covering [ps, pe).

    A plateau is a run of frames within PLATEAU_TOLERANCE_CENTS of
    one swara centre sustained for >= NOTATE_MIN_NOTE_SEC. Transit
    frames (voiced but off every centre) are absorbed into the
    plateaus they connect: consecutive plateaus split the transit
    between them at its midpoint, leading/trailing transit belongs
    to the plateau it runs into / out of, and short off-centre or
    other-centre runs between plateaus of one centre are absorbed
    into that centre (so vibrato / kampita around one centre does
    not fragment). A phrase with no plateau at all is emitted as a
    single note only when it is a sustained oscillation around one
    centre (its median sits on the centre, it crosses the centre
    repeatedly, and it swings beyond plateau tolerance on both
    sides); anything else (glides alone, short blips) yields no
    segment.
    """
    seg_cents = cents[ps:pe]
    centres = _np.floor(seg_cents / 100.0 + 0.5) * 100.0
    on = _np.abs(seg_cents - centres) <= PLATEAU_TOLERANCE_CENTS
    runs = []
    i = 0
    while i < len(seg_cents):
        j = i + 1
        if on[i]:
            while j < len(seg_cents) and on[j] \
                    and centres[j] == centres[i]:
                j += 1
            runs.append((ps + i, ps + j, float(centres[i])))
        else:
            while j < len(seg_cents) and not on[j]:
                j += 1
            runs.append((ps + i, ps + j, None))
        i = j
    anchors = [(s, e, c) for s, e, c in runs if c is not None
               and times[e - 1] - times[s] + hop_sec
               >= NOTATE_MIN_NOTE_SEC - 1e-9]
    if not anchors:
        dur = times[pe - 1] - times[ps] + hop_sec
        if dur < NOTATE_MIN_NOTE_SEC - 1e-9:
            return []
        med = float(_np.median(seg_cents))
        centre = math.floor(med / 100.0 + 0.5) * 100.0
        dev = seg_cents - centre
        signs = _np.sign(dev[dev != 0.0])
        crossings = int((signs[1:] * signs[:-1] < 0).sum()) \
            if len(signs) > 1 else 0
        if abs(med - centre) <= PLATEAU_TOLERANCE_CENTS \
                and float(_np.std(dev)) > SWARA_FIXED_STD \
                and float(dev.max()) > PLATEAU_TOLERANCE_CENTS \
                and float(dev.min()) < -PLATEAU_TOLERANCE_CENTS \
                and crossings >= 2:
            return [(ps, pe, centre)]
        return []
    groups = []
    for s, e, c in anchors:
        if groups and groups[-1][2] == c:
            groups[-1][1] = e
        else:
            groups.append([s, e, c])
    out = []
    start = ps
    for gi, (gs, ge, c) in enumerate(groups):
        if gi + 1 < len(groups):
            end = (ge + groups[gi + 1][0]) // 2
        else:
            end = pe
        out.append((start, end, c))
        start = end
    return out


def segment_plateaus(times, freqs_hz, voiced, tonic_hz):
    """Plateau-vs-transit segmentation of an f0 track into note
    events, shared by both notation engines (PRD F4 / DSP-4).

    Args: per-frame `times` (sec), `freqs_hz` (NaN where unvoiced)
    and `voiced` flags at a uniform hop (MELODY_HOP_SEC for both
    existing front ends), plus the tonic `tonic_hz` the swara
    centres are relative to.

    Returns note events in the existing event dict shape used by
    notate_song -- {"start", "dur", "sym"}, with "-" events for
    rests of >= NOTATE_MIN_REST_SEC between notes -- plus one
    addition mirroring _swara_stats: a note whose pitch width
    around its centre exceeds SWARA_FIXED_STD (a sustained
    oscillation, kampita) carries "fixed": False, the existing
    oscillation flag; fixed notes keep the legacy shape exactly.
    Unvoiced gaps split events; transit frames never become
    events of their own.
    """
    _need_numpy()
    times = _np.asarray(times, dtype=_np.float64)
    freqs = _np.asarray(freqs_hz, dtype=_np.float64)
    voiced = _np.asarray(voiced, dtype=bool)
    n = min(len(times), len(freqs), len(voiced))
    if n == 0 or not _np.isfinite(tonic_hz) or tonic_hz <= 0:
        return []
    times, freqs, voiced = times[:n], freqs[:n], voiced[:n]
    diffs = _np.diff(times)
    diffs = diffs[diffs > 0]
    hop_sec = float(_np.median(diffs)) if len(diffs) else MELODY_HOP_SEC
    valid = voiced & _np.isfinite(freqs) & (freqs > 0)
    cents = _np.full(n, _np.nan)
    cents[valid] = 1200.0 * _np.log2(freqs[valid] / float(tonic_hz))
    # Voiced phrases: maximal runs of valid frames.
    phrases = []
    i = 0
    while i < n:
        if not valid[i]:
            i += 1
            continue
        j = i + 1
        while j < n and valid[j]:
            j += 1
        phrases.append((i, j))
        i = j
    # Chronological raw segments: notes, plus a rest segment for
    # any inter-note gap of >= NOTATE_MIN_REST_SEC; shorter gaps
    # are absorbed into the previous note, as _note_events did.
    raw = []
    last_note_phrase_end = None
    for ps, pe in phrases:
        notes = _phrase_note_segments(times, cents, ps, pe, hop_sec)
        if not notes:
            continue
        if raw and last_note_phrase_end is not None:
            gap_dur = times[ps - 1] - times[last_note_phrase_end] \
                + hop_sec if ps > last_note_phrase_end else 0.0
            if gap_dur >= NOTATE_MIN_REST_SEC - 1e-9:
                raw.append((last_note_phrase_end, ps, None))
            else:
                s0, _e0, c0 = raw[-1]
                raw[-1] = (s0, ps, c0)
        raw.extend(notes)
        last_note_phrase_end = pe
    events = []
    for s, e, centre in raw:
        ev = {"start": round(float(times[s]), 2),
              "dur": round(float(times[e - 1] - times[s] + hop_sec), 2),
              "sym": "-" if centre is None else _symbol(centre)}
        if centre is not None:
            dev = cents[s:e] - centre
            dev = dev[_np.isfinite(dev)]
            if len(dev) >= 3:
                # Width is measured on a 3-frame median: isolated
                # single-frame tracker dropouts were absorbed as
                # transit above and must not read as oscillation,
                # while a sustained kampita swing is unaffected.
                padded = _np.pad(dev, (1, 1), mode="edge")
                dev = _np.median(_np.stack(
                    [padded[i:i + len(dev)] for i in range(3)]),
                    axis=0)
            if len(dev) and float(_np.std(dev)) > SWARA_FIXED_STD:
                ev["fixed"] = False
        events.append(ev)
    return events


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
    info["events"] = segment_plateaus(
        times, f0s, _np.isfinite(f0s), sa)
    info["text"] = notation_text(info)
    return info


def _runs_of(mask):
    """(start, end) index pairs of the True runs in a bool array."""
    runs = []
    i = 0
    n = len(mask)
    while i < n:
        if not mask[i]:
            i += 1
            continue
        j = i + 1
        while j < n and mask[j]:
            j += 1
        runs.append((i, j))
        i = j
    return runs


def _external_voiced(times, freqs, confs):
    """Voicing for an external pitch track, with hysteresis.

    A run starts only when the confidence reaches
    EXTERNAL_TRACK_MIN_CONF, but continues until it falls below
    EXTERNAL_TRACK_CONF_LOW. Unvoiced gaps of at most
    EXTERNAL_TRACK_BRIDGE_SEC between runs are bridged (their
    freqs are interpolated in the cents domain), and runs shorter
    than EXTERNAL_TRACK_MIN_RUN_SEC are dropped as blips. Returns
    (voiced, freqs) with freqs defined wherever voiced is True.
    """
    n = len(freqs)
    valid = _np.isfinite(freqs) & (freqs > 0)
    voiced = _np.zeros(n, bool)
    if n == 0 or not valid.any():
        return voiced, freqs
    diffs = _np.diff(times)
    diffs = diffs[diffs > 0]
    hop = float(_np.median(diffs)) if len(diffs) else MELODY_HOP_SEC
    cents = _np.full(n, _np.nan)
    cents[valid] = 1200.0 * _np.log2(freqs[valid])
    on = False
    for i in range(n):
        if not valid[i]:
            on = False
        elif on:
            if confs[i] < EXTERNAL_TRACK_CONF_LOW:
                on = False
        elif confs[i] >= EXTERNAL_TRACK_MIN_CONF:
            on = True
        voiced[i] = on and valid[i]
    runs = _runs_of(voiced)
    for (s0, e0), (s1, _e1) in zip(runs, runs[1:]):
        if (s1 - e0) * hop <= EXTERNAL_TRACK_BRIDGE_SEC + 1e-9:
            c0 = cents[e0 - 1]
            c1 = cents[s1]
            for k in range(e0, s1):
                frac = (k - (e0 - 1)) / float(s1 - (e0 - 1))
                cents[k] = c0 + (c1 - c0) * frac
            voiced[e0:s1] = True
    for s, e in _runs_of(voiced):
        if times[e - 1] - times[s] + hop < \
                EXTERNAL_TRACK_MIN_RUN_SEC - 1e-9:
            voiced[s:e] = False
    out = freqs.copy()
    conv = voiced & _np.isfinite(cents)
    out[conv] = 2.0 ** (cents[conv] / 1200.0)
    return voiced, out


def _octave_snap(freqs, voiced):
    """Fold isolated octave errors in an external track.

    Each voiced frame is compared with the median cents of its
    voiced neighbours (up to two either side, centre excluded). A
    frame within EXTERNAL_TRACK_OCTAVE_TOL_CENTS of exactly one
    octave away from that median is folded onto the median's
    octave. Genuine octave leaps move together with their
    neighbours and are untouched (the median of a straddling
    window lands between the octaves, far from either fold bar).
    """
    out = freqs.copy()
    ok = voiced & _np.isfinite(freqs) & (freqs > 0)
    if ok.sum() < 3:
        return out
    cents = _np.full(len(freqs), _np.nan)
    cents[ok] = 1200.0 * _np.log2(freqs[ok])
    padded = _np.pad(cents, (2, 2), mode="constant",
                     constant_values=_np.nan)
    wins = _np.stack([padded[i:i + len(cents)] for i in range(5)])
    wins[2] = _np.nan  # neighbours only, never the frame itself
    enough = _np.isfinite(wins).sum(axis=0) >= 2
    med = _np.full(len(cents), _np.nan)
    if enough.any():
        import warnings as _warnings
        with _warnings.catch_warnings():
            _warnings.simplefilter("ignore")
            med[enough] = _np.nanmedian(wins[:, enough], axis=0)
    d = cents - med
    folded = cents.copy()
    down = ok & _np.isfinite(d) & (
        _np.abs(d - 1200.0) <= EXTERNAL_TRACK_OCTAVE_TOL_CENTS)
    up = ok & _np.isfinite(d) & (
        _np.abs(d + 1200.0) <= EXTERNAL_TRACK_OCTAVE_TOL_CENTS)
    folded[down] -= 1200.0
    folded[up] += 1200.0
    conv = ok & _np.isfinite(folded)
    out[conv] = 2.0 ** (folded[conv] / 1200.0)
    return out


def notate_from_track(samples, sample_rate, times, freqs_hz, confs):
    """Tonic + notation from an EXTERNAL f0 track (the neural
    engine's decoded CREPE track) instead of the built-in salience
    tracker.

    The tonic still comes from the audio (detect_tonic), and the
    segmentation, swara stats and text rendering are exactly
    notate_song's -- only the melody front end differs, which is
    what makes the Classic-vs-neural comparison clean. The track
    itself gets the hygiene an external tracker needs: hysteresis
    voicing with gap bridging and blip dropping
    (_external_voiced), then an octave snap for the tracker's
    isolated octave errors (_octave_snap); unvoiced frames are
    masked to NaN. Returns the same info dict as notate_song plus
    "engine": "external", or None when no tonic is found.
    """
    _need_numpy()
    tonic = detect_tonic(samples, sample_rate)
    if tonic is None:
        return None
    times = _np.asarray(times, dtype=_np.float64)
    freqs = _np.asarray(freqs_hz, dtype=_np.float64)
    confs = _np.asarray(confs, dtype=_np.float64)
    n = min(len(times), len(freqs), len(confs))
    if n == 0:
        return None
    times, freqs, confs = times[:n], freqs[:n], confs[:n]
    voiced, freqs = _external_voiced(times, freqs, confs)
    freqs = _octave_snap(freqs, voiced)
    f0s = _np.where(voiced, freqs, _np.nan)
    # Per-frame RMS at each frame time (MELODY_WIN_SEC window): the
    # energy weights _swara_stats expects, the same quantity
    # track_melody reports for the classic front end.
    x = _np.asarray(samples, dtype=_np.float64)
    sr = float(sample_rate)
    half = max(1, int(MELODY_WIN_SEC * sr / 2))
    rms = _np.zeros(n)
    for i in range(n):
        c = int(round(times[i] * sr))
        a = max(0, c - half)
        b = min(len(x), c + half)
        if b > a:
            rms[i] = float(_np.sqrt(_np.mean(x[a:b] ** 2)))
    sa = tonic["sa_hz"]
    info = dict(tonic)
    info["swaras"] = _swara_stats(times, f0s, rms, sa)
    info["events"] = segment_plateaus(times, f0s, voiced, sa)
    info["text"] = notation_text(info)
    info["engine"] = "external"
    return info
