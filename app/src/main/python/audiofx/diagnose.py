"""Audio diagnostics: the mechanic's inspection report.

``analyze(samples, sample_rate)`` runs twelve checks over mono float
samples and returns a list of findings, warn-first::

    {"id": "clipping", "severity": "warn", "title": ..., "detail": ...,
     "fix": {"op": "declip", "params": {}, "label": "Reduce clipping"},
     "advice": "Enable the Limiter before recording ..."}

``fix`` is None when there is nothing to fix (or the damage is
permanent). ``tune_up_fixes(findings)`` reduces the warn findings to an
ordered, de-duplicated list of repair ops for the one-button Tune Up.

All thresholds are named module constants so they can be tuned.
NumPy is required for the four spectral checks; without it those checks
are skipped and the rest still run.
"""

import math

try:
    import numpy as _np

    _HAVE_NUMPY = True
except ImportError:  # pragma: no cover
    _np = None
    _HAVE_NUMPY = False


# ---------------------------------------------------------------------------
# Thresholds (tunable)
# ---------------------------------------------------------------------------

CLIP_THRESHOLD = 0.999          # |x| at or above this counts as clipped
# Ceiling (flat-top) clipping: clipping held at a ceiling BELOW full
# scale (e.g. a -1 dBFS hard hold at 0.8912) never reaches
# CLIP_THRESHOLD, so it needs its own detector: repeated runs of
# consecutive samples sitting exactly at the signal's own peak.
# Isolated single samples at the peak are normal transients, not
# flat-topping, so only runs of >= CEILING_RUN_MIN_LEN count.
CEILING_RUN_MIN_LEN = 2         # consecutive at-peak samples = a flat top
CEILING_MIN_SAMPLES = 8         # at-peak samples in flat-top runs to warn
CEILING_MIN_RUNS = 3            # flat-top runs to warn (or one long run)
CEILING_LONG_RUN = 8            # a single flat-top run this long warns
CEILING_TOL = 0.5 / 32768.0     # half a 16-bit LSB around the peak
QUIET_PEAK_DB = -12.0           # peak below this -> "too quiet"
DC_THRESHOLD = 0.01             # |mean| above this -> DC offset
SILENCE_DB = -45.0              # window RMS below this -> silent
SILENCE_WARN_SEC = 2.0          # total silence above this -> warn
HUM_RATIO = 10.0                # hum band energy vs local floor
NOISE_WARN_DB = -50.0           # noise floor above this -> warn
PLOSIVE_MIN_COUNT = 2           # plosive bursts to warn
SIBILANT_FRAC = 0.05            # fraction of sibilant frames to warn
LEVEL_RANGE_DB = 6.0            # window RMS spread above this -> uneven
END_ABRUPT_RATIO = 0.1          # edge peak vs overall peak -> abrupt end
LOUDNESS_TARGET = -16.0         # LUFS target
LOUDNESS_TOL_DB = 3.0           # outside target +/- tol -> warn
BALANCE_DB = 6.0                # band vs mid imbalance to warn
BALANCE_MAX_CUT_DB = 6.0        # max corrective EQ per pass

_SEVERITY_ORDER = {"warn": 0, "info": 1, "ok": 2}


def _arr(s, ctx):
    """Shared float64 view of the signal. Converted once in analyze() and
    reused by every check, so a 10-minute file doesn't pay the list->array
    conversion a dozen times."""
    x = ctx.get("x")
    if x is None and _HAVE_NUMPY and s:
        x = _np.asarray(s, dtype=_np.float64)
        ctx["x"] = x
    return x


def _finding(fid, severity, title, detail, fix=None, advice=""):
    return {
        "id": fid,
        "severity": severity,
        "title": title,
        "detail": detail,
        "fix": fix,
        "advice": advice,
    }


def _db(x):
    return 20.0 * math.log10(x) if x > 0 else float("-inf")


def _fix(op, params, label):
    return {"op": op, "params": params, "label": label}


# ---------------------------------------------------------------------------
# Individual checks. Each takes (samples, sample_rate, ctx) and returns a
# finding or None (None = check skipped, e.g. no NumPy). ctx carries the
# shared full-signal spectrum for the spectral checks.
# ---------------------------------------------------------------------------

def ceiling_clip_stats(samples):
    """Flat-top (ceiling) clipping statistics for a sample buffer.

    Returns a dict with the absolute peak, how many samples sit
    exactly at that peak (within CEILING_TOL), how many runs those
    samples form, the longest run, how many samples/runs are in
    flat-top runs (length >= CEILING_RUN_MIN_LEN), and
    ``is_clipped``: True when the flat-top evidence passes the
    CEILING_MIN_* thresholds. A clean signal whose loudest transient
    touches its peak once or twice is not clipped; a signal that was
    hard-held at a ceiling (full scale OR below it, e.g. -1 dBFS)
    leaves hundreds of repeated at-peak runs.
    """
    stats = {"peak": 0.0, "peak_db": float("-inf"), "ceiling_samples": 0,
             "ceiling_runs": 0, "longest_run": 0, "flat_samples": 0,
             "flat_runs": 0, "is_clipped": False}
    n = len(samples)
    if n == 0:
        return stats
    if _HAVE_NUMPY:
        x = _np.asarray(samples, dtype=_np.float64)
        peak = float(_np.max(_np.abs(x)))
        if peak <= 0:
            return stats
        mask = _np.abs(x) >= peak - CEILING_TOL
        count = int(_np.sum(mask))
        if count:
            d = _np.diff(mask.astype(_np.int8))
            starts = _np.where(d == 1)[0] + 1
            ends = _np.where(d == -1)[0] + 1
            if mask[0]:
                starts = _np.concatenate((_np.array([0]), starts))
            if mask[-1]:
                ends = _np.concatenate((ends, _np.array([len(mask)])))
            lens = ends - starts
            flat = lens[lens >= CEILING_RUN_MIN_LEN]
            stats["ceiling_runs"] = int(len(lens))
            stats["longest_run"] = int(_np.max(lens))
            stats["flat_samples"] = int(_np.sum(flat))
            stats["flat_runs"] = int(len(flat))
    else:
        peak = max(abs(v) for v in samples)
        if peak <= 0:
            return stats
        count = 0
        cur = 0
        for v in samples:
            if abs(v) >= peak - CEILING_TOL:
                count += 1
                cur += 1
            else:
                if cur:
                    stats["ceiling_runs"] += 1
                    stats["longest_run"] = max(stats["longest_run"], cur)
                    if cur >= CEILING_RUN_MIN_LEN:
                        stats["flat_samples"] += cur
                        stats["flat_runs"] += 1
                cur = 0
        if cur:
            stats["ceiling_runs"] += 1
            stats["longest_run"] = max(stats["longest_run"], cur)
            if cur >= CEILING_RUN_MIN_LEN:
                stats["flat_samples"] += cur
                stats["flat_runs"] += 1
    stats["peak"] = peak
    stats["peak_db"] = _db(peak)
    stats["ceiling_samples"] = count
    stats["is_clipped"] = bool(
        stats["flat_samples"] >= CEILING_MIN_SAMPLES
        and (stats["flat_runs"] >= CEILING_MIN_RUNS
             or stats["longest_run"] >= CEILING_LONG_RUN))
    return stats


def _check_clipping(s, sr, ctx):
    n = len(s)
    count = 0
    runs = 0
    longest = 0
    i = 0
    if _HAVE_NUMPY:
        mask = _np.abs(_arr(s, ctx)) >= CLIP_THRESHOLD
        count = int(_np.sum(mask))
        if count:
            # Run boundaries via diff: no Python loop over the signal.
            d = _np.diff(mask.astype(_np.int8))
            starts = _np.where(d == 1)[0] + 1
            ends = _np.where(d == -1)[0] + 1
            if mask[0]:
                starts = _np.concatenate((_np.array([0]), starts))
            if mask[-1]:
                ends = _np.concatenate((ends, _np.array([len(mask)])))
            runs = len(starts)
            longest = int(_np.max(ends - starts))
    else:
        while i < n:
            if abs(s[i]) >= CLIP_THRESHOLD:
                runs += 1
                cur = 0
                while i < n and abs(s[i]) >= CLIP_THRESHOLD:
                    cur += 1
                    i += 1
                count += cur
                longest = max(longest, cur)
            else:
                i += 1
    ceil = ceiling_clip_stats(s)
    if count == 0 and not ceil["is_clipped"]:
        return _finding("clipping", "ok", "No clipping",
                        "No samples at full scale and no flat-topped "
                        "ceiling.", None, "")
    if count == 0:
        # Flat-topped at a ceiling below full scale (e.g. held at
        # -1 dBFS): the full-scale test above sees nothing.
        return _finding(
            "clipping", "warn", "Clipping detected",
            "%d samples flat-topped at the %.1f dBFS ceiling across "
            "%d spots (longest run %d samples). This distortion is "
            "permanent, but its harshness can be reduced."
            % (ceil["flat_samples"], ceil["peak_db"], ceil["flat_runs"],
               ceil["longest_run"]),
            _fix("declip", {}, "Reduce clipping"),
            "Enable the Limiter before recording to make clipping "
            "impossible.")
    return _finding(
        "clipping", "warn", "Clipping detected",
        "%d clipped samples across %d spots%s. This distortion is "
        "permanent, but its harshness can be reduced."
        % (count, runs,
           " (longest run %d samples)" % longest if longest > 1 else ""),
        _fix("declip", {}, "Reduce clipping"),
        "Enable the Limiter before recording to make clipping impossible.")


def _check_level(s, sr, ctx):
    if _HAVE_NUMPY and s:
        peak = float(_np.max(_np.abs(_arr(s, ctx))))
    else:
        peak = max([abs(v) for v in s]) if s else 0.0
    peak_db = _db(peak)
    if peak_db < QUIET_PEAK_DB:
        return _finding(
            "level", "warn", "Recording is quiet",
            "Peak level is %.1f dBFS." % peak_db,
            _fix("loudness", {"target_lufs": LOUDNESS_TARGET},
                 "Normalize loudness"),
            "Get closer to the mic next time: a quiet recording turned up "
            "is noisier than a loud one turned down.")
    return _finding("level", "ok", "Healthy level",
                    "Peak level is %.1f dBFS." % peak_db, None, "")


def _check_dc(s, sr, ctx):
    if _HAVE_NUMPY and s:
        mean = float(_np.mean(_arr(s, ctx)))
    else:
        mean = sum(s) / len(s) if s else 0.0
    if abs(mean) > DC_THRESHOLD:
        return _finding(
            "dc_offset", "warn", "DC offset detected",
            "Signal mean is %+.3f (%.1f%% of full scale); it eats headroom "
            "and can cause clicks on edits." % (mean, abs(mean) * 100),
            _fix("dc_remove", {}, "Remove DC offset"),
            "Usually a hardware quirk. If every recording has it, try a "
            "different audio source.")
    return _finding("dc_offset", "ok", "No DC offset",
                    "Signal mean is %+.4f." % mean, None, "")


def _window_flags(s, sr, win_sec, db_thresh, ctx=None):
    win = max(1, int(win_sec * sr))
    thresh = 10.0 ** (db_thresh / 20.0)
    if _HAVE_NUMPY:
        x = _arr(s, ctx) if ctx is not None else _np.asarray(
            s, dtype=_np.float64)
        n = (x.size // win) * win
        if n == 0:
            return [], win
        rms = _np.sqrt(_np.mean(x[:n].reshape(-1, win) ** 2, axis=1))
        return (rms < thresh).tolist(), win
    flags = []
    for i in range(0, len(s), win):
        seg = s[i : i + win]
        r = math.sqrt(sum(v * v for v in seg) / max(1, len(seg)))
        flags.append(r < thresh)
    return flags, win


def _check_silence(s, sr, ctx):
    flags, win = _window_flags(s, sr, 0.05, SILENCE_DB, ctx)
    if not flags:
        return None
    total = sum(flags) * win / sr
    longest = 0
    cur = 0
    for f in flags:
        cur = cur + 1 if f else 0
        longest = max(longest, cur)
    longest_sec = longest * win / sr
    if total >= SILENCE_WARN_SEC:
        return _finding(
            "silence", "warn", "Dead air",
            "%.1f seconds of silence (longest stretch %.1f s)."
            % (total, longest_sec),
            _fix("silence_trim", {}, "Trim silence"),
            "Pause the recording instead of leaving gaps.")
    return _finding("silence", "ok", "No dead air",
                    "Only %.1f s of silence." % total, None, "")


def _spectrum(s, sr):
    if not _HAVE_NUMPY:
        return None, None
    x = _np.asarray(s, dtype=_np.float64)
    n_fft = 1
    while n_fft < x.size:
        n_fft *= 2
    n_fft = min(n_fft, 1 << 18)
    if x.size < n_fft:
        x = _np.pad(x, (0, n_fft - x.size))
    spec = _np.abs(_np.fft.rfft(x[:n_fft]))
    freqs = _np.fft.rfftfreq(n_fft, 1.0 / sr)
    return spec, freqs


def _band_energy(spec, freqs, lo, hi):
    sel = (freqs >= lo) & (freqs <= hi)
    return float(_np.sum(spec[sel] ** 2)) if _np.any(sel) else 0.0


def _check_hum(s, sr, ctx):
    spec, freqs = ctx.get("spectrum", (None, None))
    if spec is None:
        return None
    best = None
    for f0 in (50.0, 60.0):
        band = _band_energy(spec, freqs, f0 - 2, f0 + 2)
        floor = _band_energy(spec, freqs, f0 - 12, f0 - 4) + \
                _band_energy(spec, freqs, f0 + 4, f0 + 12)
        if floor <= 0:
            continue
        ratio = band / (floor / 2 + 1e-18)
        if ratio > HUM_RATIO and (best is None or ratio > best[1]):
            best = (f0, ratio)
    if best is None:
        return _finding("hum", "ok", "No mains hum",
                        "No 50/60 Hz tonal content found.", None, "")
    f0, ratio = best
    return _finding(
        "hum", "warn", "Mains hum detected",
        "Strong tonal content at %d Hz (%.0fx the local floor), plus "
        "harmonics." % (int(f0), ratio),
        _fix("hum_notch", {"freq_hz": f0}, "Remove hum"),
        "Move away from chargers and fluorescent lights; record on "
        "battery if you can.")


def _check_noise_floor(s, sr, ctx):
    flags, win = _window_flags(s, sr, 0.05, SILENCE_DB, ctx)
    if not flags or not _HAVE_NUMPY:
        return None
    x = _arr(s, ctx)
    n = (x.size // win) * win
    if n == 0:
        return None
    rms = _np.sqrt(_np.mean(x[:n].reshape(-1, win) ** 2, axis=1))
    k = max(1, int(0.05 * rms.size))
    quiet = _np.partition(rms, k)[:k]
    floor_db = _db(float(_np.mean(quiet)))
    overall_db = _db(float(_np.sqrt(_np.mean(x[:n] ** 2))))
    # Gate: if the "quiet" parts are nearly as loud as the whole signal,
    # there is no quiet part to measure -- it is signal, not hiss. (Without
    # this, any consistently loud signal false-positives.)
    if floor_db > NOISE_WARN_DB and (overall_db - floor_db) > 6.0:
        return _finding(
            "noise_floor", "warn", "High noise floor",
            "Background hiss sits at %.0f dBFS." % floor_db,
            _fix("denoise", {"strength": 0.5}, "Reduce hiss"),
            "Closer to the mic, quieter room, soft furnishings -- and "
            "enable noise suppression before you hit record.")
    return _finding("noise_floor", "info", "Noise floor",
                    "Background hiss sits at %.0f dBFS." % floor_db,
                    None, "")


def _check_plosive(s, sr, ctx):
    if not _HAVE_NUMPY:
        return None
    from . import repair
    x = _arr(s, ctx)
    n = x.size
    if n == 0:
        return None
    low = repair._fft_lowpass(x, 150.0, sr)
    env_win = max(1, int(0.02 * sr))
    env = repair._moving_avg(_np.abs(low), env_win)
    med = float(_np.median(env))
    thresh = max(0.02, 4.0 * med)
    mask = env > thresh
    # Count distinct bursts (merge neighbors within 100 ms).
    gap = int(0.1 * sr)
    idx = _np.where(mask)[0]
    if idx.size == 0:
        count = 0
    else:
        count = 1 + int(_np.sum(_np.diff(idx) > gap))
    if count >= PLOSIVE_MIN_COUNT:
        return _finding(
            "plosive", "warn", "Plosive thumps",
            "%d low-frequency bursts (p/b pops)." % count,
            _fix("deplosive", {}, "Tame plosives"),
            "Speak slightly past the mic, not into it -- or clip on a "
            "foam windscreen.")
    return _finding("plosive", "ok", "No plosives",
                    "No low-frequency thumps found.", None, "")


def _check_sibilance(s, sr, ctx):
    if not _HAVE_NUMPY:
        return None
    from . import repair
    x = _arr(s, ctx)
    n = x.size
    if n == 0:
        return None
    band = repair._fft_bandpass(x, 5000.0, 9000.0, sr)
    frame = max(1, int(0.02 * sr))
    nb = n // frame
    if nb == 0:
        return None
    b = _np.sqrt(_np.mean(band[: nb * frame].reshape(nb, frame) ** 2,
                          axis=1))
    w = _np.sqrt(_np.mean(x[: nb * frame].reshape(nb, frame) ** 2, axis=1))
    sib = (b > 0.35 * w) & (b > 10.0 ** (-40.0 / 20.0))
    frac = float(_np.mean(sib))
    if frac >= SIBILANT_FRAC:
        return _finding(
            "sibilance", "warn", "Harsh sibilance",
            "%.0f%% of the audio has harsh s/sh energy." % (frac * 100),
            _fix("deess", {}, "Soften sibilance"),
            "Angle the mic slightly off-axis; harshness is often mic "
            "position, not your voice.")
    return _finding("sibilance", "ok", "No harsh sibilance",
                    "Sibilant energy is under control.", None, "")


def _check_uneven_level(s, sr, ctx):
    win = max(1, int(0.5 * sr))
    if _HAVE_NUMPY:
        x = _arr(s, ctx)
        nw = max(1, x.size // win)
        rms = _np.sqrt(_np.mean(
            x[: nw * win].reshape(nw, win) ** 2, axis=1))
        # Ignore near-silent windows.
        rms = rms[rms > 10.0 ** (-50.0 / 20.0)]
        if rms.size < 4:
            return None
        spread = _db(float(_np.max(rms))) - _db(float(_np.min(rms)))
    else:
        rms = []
        for i in range(0, len(s), win):
            seg = s[i : i + win]
            r = math.sqrt(sum(v * v for v in seg) / max(1, len(seg)))
            if r > 10.0 ** (-50.0 / 20.0):
                rms.append(r)
        if len(rms) < 4:
            return None
        spread = _db(max(rms)) - _db(min(rms))
    if spread > LEVEL_RANGE_DB:
        return _finding(
            "uneven_level", "warn", "Uneven level",
            "Loud parts are %.0f dB above quiet parts." % spread,
            _fix("level", {}, "Even out level"),
            "Keep a consistent mic distance; don't turn your head away "
            "mid-sentence.")
    return _finding("uneven_level", "ok", "Consistent level",
                    "Level varies by only %.0f dB." % spread, None, "")


def _check_abrupt_ends(s, sr, ctx):
    n = len(s)
    if n == 0:
        return None
    # Look at the very edge (2 ms): with fade_ends' 30 ms fades the edge
    # sits at ~7% of peak, comfortably under the 10% threshold. (A max
    # over a wider window can never be cleared by any reasonable fade.)
    edge = max(1, int(0.002 * sr))
    if _HAVE_NUMPY:
        ax = _np.abs(_arr(s, ctx))
        peak = float(_np.max(ax))
        edge_start = float(_np.max(ax[:edge]))
        edge_end = float(_np.max(ax[-edge:]))
    else:
        peak = max([abs(v) for v in s])
        edge_start = max([abs(v) for v in s[:edge]])
        edge_end = max([abs(v) for v in s[-edge:]])
    if peak == 0:
        return None
    abrupt = []
    if edge_start > END_ABRUPT_RATIO * peak:
        abrupt.append("start")
    if edge_end > END_ABRUPT_RATIO * peak:
        abrupt.append("end")
    if abrupt:
        return _finding(
            "abrupt_ends", "warn", "Abrupt %s" % " and ".join(abrupt),
            "The audio %s cut off mid-waveform; this clicks on "
            "playback." % " and ".join(abrupt),
            _fix("fade_ends", {}, "Smooth the ends"),
            "Leave half a second of silence at the start and end -- it "
            "makes edits invisible.")
    return _finding("abrupt_ends", "ok", "Clean ends",
                    "Both ends fade to silence.", None, "")


def _check_loudness(s, sr, ctx):
    if not _HAVE_NUMPY:
        return None
    from . import repair
    try:
        lufs = repair.measure_lufs(s, sr)
    except RuntimeError:
        return None
    if lufs == float("-inf"):
        return None
    if abs(lufs - LOUDNESS_TARGET) <= LOUDNESS_TOL_DB:
        return _finding("loudness", "ok", "Good loudness",
                        "Integrated loudness is %.0f LUFS." % lufs, None, "")
    # Headroom gate: the fix gains up to the -1 dBFS ceiling but never
    # clips. If the peaks leave no room for a material gain, the fix would
    # be a no-op -- report info instead of an unfixable warn, or the
    # tune-up can never converge.
    peak = float(_np.max(_np.abs(_arr(s, ctx)))) if len(s) else 0.0
    ceiling = 10.0 ** (-1.0 / 20.0)
    if peak > 0:
        max_gain_db = 20.0 * math.log10(ceiling / peak)
        applied_db = min(LOUDNESS_TARGET - lufs, max_gain_db)
        if abs(applied_db) < 0.5:
            return _finding(
                "loudness", "info", "Loudness at peak limit",
                "Integrated loudness is %.0f LUFS (target %.0f); the peaks "
                "leave no headroom for more gain without clipping."
                % (lufs, LOUDNESS_TARGET),
                None, "")
    return _finding(
        "loudness", "warn", "Off-target loudness",
        "Integrated loudness is %.0f LUFS (target %.0f)."
        % (lufs, LOUDNESS_TARGET),
        _fix("loudness", {"target_lufs": LOUDNESS_TARGET},
             "Normalize loudness"),
        "")


def _check_balance(s, sr, ctx):
    spec, freqs = ctx.get("spectrum", (None, None))
    if spec is None:
        return None
    low = _band_energy(spec, freqs, 200, 400)
    mid = _band_energy(spec, freqs, 800, 2000)
    high = _band_energy(spec, freqs, 2000, 5000)
    if mid <= 0:
        return None
    low_db = 10.0 * math.log10(low / mid + 1e-18)
    high_db = 10.0 * math.log10(high / mid + 1e-18)
    if low_db > BALANCE_DB:
        # Cut proportional to the measured imbalance (clamped): a fixed
        # -2.5 dB can never converge on a +16 dB imbalance.
        cut = -min(BALANCE_MAX_CUT_DB, low_db - BALANCE_DB + 1.0)
        return _finding(
            "balance", "warn", "Muddy low end",
            "200-400 Hz sits %+.0f dB above the mids." % low_db,
            _fix("eq_balance",
                 {"low_gain_db": cut, "high_gain_db": 0.0},
                 "Clear the mud"),
            "Back off the mic slightly and soften the room.")
    if high_db > BALANCE_DB:
        cut = -min(BALANCE_MAX_CUT_DB, high_db - BALANCE_DB + 1.0)
        return _finding(
            "balance", "warn", "Harsh top end",
            "2-5 kHz sits %+.0f dB above the mids." % high_db,
            _fix("eq_balance",
                 {"low_gain_db": 0.0, "high_gain_db": cut},
                 "Tame the harshness"),
            "Angle the mic slightly off-axis.")
    return _finding("balance", "ok", "Balanced tone",
                    "No strong tonal imbalance.", None, "")


_CHECKS = (
    _check_clipping,
    _check_level,
    _check_dc,
    _check_silence,
    _check_hum,
    _check_noise_floor,
    _check_plosive,
    _check_sibilance,
    _check_uneven_level,
    _check_abrupt_ends,
    _check_loudness,
    _check_balance,
)


def analyze(samples, sample_rate):
    """Run all checks. Returns findings sorted warn-first."""
    s = list(samples)
    ctx = {}
    if _HAVE_NUMPY and len(s) > 0:
        ctx["x"] = _np.asarray(s, dtype=_np.float64)
        ctx["spectrum"] = _spectrum(s, sample_rate)
    findings = []
    for check in _CHECKS:
        try:
            f = check(s, sample_rate, ctx)
        except Exception:
            continue  # a broken check must never break the report
        if f is not None:
            findings.append(f)
    findings.sort(key=lambda f: _SEVERITY_ORDER.get(f["severity"], 9))
    return findings


# ---------------------------------------------------------------------------
# Tune Up: ordered, de-duplicated fix list for the one-button path
# ---------------------------------------------------------------------------

# Canonical tune-up order. Principle: a fix must not undo an earlier
# fix's check. So restoration first, then relative/gain-invariant fixes,
# then dynamics and tone, then loudness (final level), then denoise
# (final noise floor, after all gains are set), then fades last.
# The old order (denoise before loudness, loudness before EQ) could never
# converge: loudness re-raised the noise floor and EQ shifted LUFS.
_TUNE_UP_ORDER = (
    "declip",
    "dc_remove",
    "hum_notch",
    "deplosive",
    "deess",
    "silence_trim",
    "level",
    "eq_balance",
    "loudness",
    "denoise",
    "fade_ends",
)

#: Tune-up recipes per content type. Detection stays universal; the
#: profile only decides what the one-button Tune Up applies, and with
#: which parameters. ``params`` are per-op overrides merged over the
#: finding's own params (detection-derived values like the hum
#: frequency win). ``premium`` marks recipes sold with the one-time
#: premium purchase; the podcast recipe is always free.
TUNE_UP_PROFILES = {
    "podcast": {
        "label": "Podcast",
        "description": "Voice recipe: de-plosive, de-esser, leveling, "
                       "-16 LUFS",
        "premium": True,
        "fixes": ("declip", "dc_remove", "hum_notch", "denoise",
                  "deplosive", "deess", "silence_trim", "level",
                  "loudness", "eq_balance", "fade_ends"),
        "params": {
            "loudness": {"target_lufs": -16.0},
            "deess": {"reduction_db": 8.0},
            "denoise": {"strength": 0.5},
        },
    },
    "singing": {
        "label": "Singing",
        "description": "Vocal recipe: gentle de-esser, no leveling, "
                       "-14 LUFS (dynamics preserved)",
        "premium": False,
        "fixes": ("declip", "dc_remove", "hum_notch", "denoise",
                  "deplosive", "deess", "loudness", "eq_balance",
                  "fade_ends"),
        "params": {
            "loudness": {"target_lufs": -14.0},
            "deess": {"reduction_db": 4.0},
            "denoise": {"strength": 0.35},
        },
    },
    "music": {
        "label": "Music",
        "description": "Mix recipe: loudness, balance, fades, -14 LUFS",
        "premium": True,
        "fixes": ("declip", "dc_remove", "hum_notch", "denoise",
                  "loudness", "eq_balance", "fade_ends"),
        "params": {
            "loudness": {"target_lufs": -14.0},
            "denoise": {"strength": 0.5},
        },
    },
}

DEFAULT_PROFILE = "podcast"


def list_profiles():
    """Profile metadata for the UI: {id: {label, description, premium}}."""
    return {
        pid: {"label": p["label"], "description": p["description"],
              "premium": p["premium"]}
        for pid, p in TUNE_UP_PROFILES.items()
    }


def tune_up_fixes(findings, profile=DEFAULT_PROFILE):
    """Reduce warn findings to an ordered, de-duplicated list of
    ``{"op":..., "params":..., "label":...}`` fix ops for the profile's
    recipe. ``loudness`` supersedes ``level`` (it includes peak safety).
    Detection-derived params (hum frequency, EQ gains) are kept; the
    profile only overrides its tunable params."""
    prof = TUNE_UP_PROFILES.get(profile, TUNE_UP_PROFILES[DEFAULT_PROFILE])
    allowed = set(prof["fixes"])
    overrides = prof.get("params", {})
    by_op = {}
    for f in findings:
        if f.get("severity") != "warn":
            continue
        fix = f.get("fix")
        if not fix:
            continue
        op = fix["op"]
        if op not in allowed:
            continue
        params = dict(fix.get("params") or {})
        for key, val in overrides.get(op, {}).items():
            params[key] = val
        by_op.setdefault(op, {"op": op, "params": params,
                              "label": fix.get("label", op)})
    return [by_op[op] for op in _TUNE_UP_ORDER if op in by_op]
