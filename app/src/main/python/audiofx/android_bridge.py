"""Entry point called from the Android UI (MainActivity) via Chaquopy.

Kept deliberately thin: argument validation, WAV in/out, and the fixed
effect order Warmth -> Reverb -> Echo.
"""

import json
import math
import os
import wave

from . import diagnose as _diagnose
from . import pitch as _pitch
from . import repair as _repair
from . import tonic as _tonic
from .effects import Echo, Reverb, Warmth

try:
    import numpy as _np

    _HAVE_NP = True
except ImportError:  # pragma: no cover
    _np = None
    _HAVE_NP = False

import hashlib as _hashlib

# Chain-tip cache: entry id -> [float samples, sample_rate]. Lets a new op
# render incrementally from the previous tip instead of re-rendering the
# whole chain from pristine. The buffer is the exact float chain output:
# no intermediate 16-bit quantization or clipping (intermediate buffers
# can legitimately exceed +/-1.0, e.g. hum_notch overshoot). Validity is
# tracked by the Java caller, which clears it on entry switch; every
# successful render (full or incremental) re-seeds it. Bounded to a couple
# of entries; a miss just falls back to a full render.
_tip_cache = {}
_TIP_CACHE_MAX = 2


def _tip_seed(cache_key, samples, sample_rate):
    if cache_key is None:
        return
    if cache_key in _tip_cache:
        del _tip_cache[cache_key]
    while len(_tip_cache) >= _TIP_CACHE_MAX:
        _tip_cache.pop(next(iter(_tip_cache)))
    store = samples.copy() if _HAVE_NP else list(samples)
    _tip_cache[cache_key] = [store, sample_rate]
from .pipeline import (
    EffectChain,
    encode_mp3,
    read_wav_mono,
    splice_with_crossfade,
    waveform_peaks as _waveform_peaks,
    write_wav,
)

#: DSP engine version. Java stores this per library entry and refuses to
#: silently re-render when the engine changes.
DSP_VERSION = "1"


def _cb(progress, name, *args):
    """Fire a Java progress callback; never let UI failures break DSP."""
    if progress is None:
        return
    try:
        getattr(progress, name)(*args)
    except Exception:
        pass


def _apply_op(samples, sample_rate, op, params, index):
    """Apply one render_chain op. Shared by the main loop and the tune_up
    expansion so the two can never drift apart."""
    if op == "fx":
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
        samples = (_np.asarray(processed, dtype=_np.float64)
                   if _HAVE_NP else processed)
    elif op == "pitch_shift":
        samples = _pitch.pitch_shift(
            samples, sample_rate,
            float(params.get("semitones", 0.0)))
    elif op == "normalize":
        samples, _ = _apply_normalize(
            samples, sample_rate,
            target_db=params.get("target_db", -1.0),
            percentile=params.get("percentile", 99.0))
    elif op == "trim":
        samples, _, _ = _apply_trim(
            samples, sample_rate,
            params.get("start_sec"), params.get("end_sec"))
    elif op == "join":
        samples, _, _ = _apply_join(
            samples, sample_rate,
            params.get("start_sec"), params.get("end_sec"))
    elif op == "dc_remove":
        samples = _repair.dc_remove(samples)
    elif op == "fade_ends":
        samples = _repair.fade_ends(
            samples, sample_rate,
            fade_sec=float(params.get("fade_sec", 0.02)))
    elif op == "silence_trim":
        samples = _repair.silence_trim(
            samples, sample_rate,
            threshold_db=float(params.get("threshold_db", -45.0)),
            min_silence_sec=float(params.get("min_silence_sec", 0.5)))
    elif op == "hum_notch":
        samples = _repair.hum_notch(
            samples, sample_rate, float(params.get("freq_hz", 60.0)))
    elif op == "declip":
        samples = _repair.declip(samples)
    elif op == "denoise":
        samples = _repair.denoise(
            samples, sample_rate,
            strength=float(params.get("strength", 0.5)))
    elif op == "deplosive":
        samples = _repair.deplosive(samples, sample_rate)
    elif op == "deess":
        samples = _repair.deess(
            samples, sample_rate,
            reduction_db=float(params.get("reduction_db", 8.0)))
    elif op == "level":
        samples = _repair.level(samples, sample_rate)
    elif op == "loudness":
        samples = _repair.loudness_normalize(
            samples, sample_rate,
            target_lufs=float(params.get("target_lufs", -16.0)))
    elif op == "eq_balance":
        samples = _repair.eq_balance(
            samples, sample_rate,
            low_gain_db=float(params.get("low_gain_db", 0.0)),
            high_gain_db=float(params.get("high_gain_db", 0.0)))
    elif op == "tune_up":
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
            samples = _apply_op(samples, sample_rate, sub, sub_params, index)
    elif op == "punch_in":
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
        samples = (_np.asarray(spliced, dtype=_np.float64)
                   if _HAVE_NP else spliced)
    elif op == "livefx":
        # Live-FX takes are baked into the pristine; the livefx op is a
        # display-only label and must never be re-run.
        raise ValueError("livefx ops are never re-rendered in render_chain")
    else:
        raise ValueError("unknown op %r" % (op,))
    return samples


def list_reverb_presets():
    """Preset names for the Android spinner, sorted."""
    return sorted(Reverb.PRESETS)


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


def process_file(input_path, output_path, warmth=0.5, reverb_preset="studio", echo=0.3,
                 start_sec=None, end_sec=None):
    """Apply the effect chain to a WAV file.

    When ``start_sec``/``end_sec`` select a window, only that window is
    processed (spliced back with a short crossfade); otherwise the whole
    file is processed.

    Returns a dict with ok/output_path/sample_rate/duration_s/start_s/end_s.
    Raises ValueError on a missing input or unknown preset.
    """
    if not os.path.isfile(input_path):
        raise ValueError("input file not found: %s" % input_path)
    samples, sample_rate = read_wav_mono(input_path)
    processed, s, e = _apply_fx(samples, sample_rate, warmth=warmth,
                                reverb_preset=reverb_preset, echo=echo,
                                start_sec=start_sec, end_sec=end_sec)
    write_wav(output_path, processed, sample_rate)
    return {
        "ok": True,
        "output_path": output_path,
        "sample_rate": sample_rate,
        "duration_s": round(len(processed) / sample_rate, 2),
        "start_s": round(s / sample_rate, 2),
        "end_s": round(e / sample_rate, 2),
    }


def _apply_normalize(samples, sample_rate, target_db=-1.0, percentile=99.0):
    """Float core of normalize_file: peak-normalize a sample list.

    The reference level is the given percentile of |samples| (default the
    99th), not the absolute max, so a few pops or bursts cannot mislead the
    gain computation. Gain is a single whole-file constant (dynamics
    preserved); samples that would exceed the target after gain are
    hard-held at target_db dBFS, so loud transients are limited, never
    boosted into clipping.

    Returns (out_samples, info) where info carries peak_before_db /
    ref_percentile_db / applied_gain_db / percentile. Raises ValueError on
    an out-of-range percentile or a silent input.
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
        gain = target_peak / ref
        out = _np.clip(x * gain, -target_peak, target_peak)
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
        gain = target_peak / ref
        out = [min(max(s * gain, -target_peak), target_peak) for s in samples]
    info = {
        "peak_before_db": round(20 * math.log10(peak), 2),
        "ref_percentile_db": round(20 * math.log10(ref), 2),
        "applied_gain_db": round(20 * math.log10(gain), 2),
        "percentile": percentile,
    }
    return out, info


def normalize_file(input_path, output_path, target_db=-1.0, percentile=99.0):
    """Peak-normalize a WAV file so its ``percentile``-th percentile peak
    reaches target_db dBFS.

    The reference level is the given percentile of |samples| (default
    the 99th), not the absolute max, so a few pops or bursts cannot
    mislead the gain computation. Gain is a single whole-file constant
    (dynamics preserved); samples that would exceed the target after
    gain are hard-held at target_db dBFS, so loud transients are
    limited, never boosted into clipping. (A literal "no gain above
    the percentile" would put an amplitude discontinuity at the
    percentile level and glitch any normal signal crossing it.)

    Target defaults to -1 dBFS, leaving headroom for MP3 encoding.
    Returns a dict with ok/output_path/sample_rate/duration_s/
    peak_before_db/ref_percentile_db/peak_after_db/applied_gain_db/
    percentile. Raises ValueError on a missing input, a silent input,
    or an out-of-range percentile.
    """
    if not os.path.isfile(input_path):
        raise ValueError("input file not found: %s" % input_path)
    samples, sample_rate = read_wav_mono(input_path)
    out, info = _apply_normalize(samples, sample_rate,
                                 target_db=target_db, percentile=percentile)
    write_wav(output_path, out, sample_rate)
    return {
        "ok": True,
        "output_path": output_path,
        "sample_rate": sample_rate,
        "duration_s": round(len(out) / sample_rate, 2),
        "peak_before_db": info["peak_before_db"],
        "ref_percentile_db": info["ref_percentile_db"],
        "peak_after_db": round(target_db, 2),
        "applied_gain_db": info["applied_gain_db"],
        "percentile": info["percentile"],
    }


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


def _clamp_window(input_path, start_sec, end_sec):
    """Validate a selection window; returns (samples, sample_rate, s, e).

    ``end_sec`` may be None, meaning to the end of the file. Raises
    ValueError on a missing input, missing/empty selection.
    """
    if not os.path.isfile(input_path):
        raise ValueError("input file not found: %s" % input_path)
    samples, sample_rate = read_wav_mono(input_path)
    s, e = _clamp_samples_window(samples, sample_rate, start_sec, end_sec)
    return samples, sample_rate, s, e


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


def trim_to_silence(input_path, output_path, start_sec, end_sec,
                    edge_fade_sec=0.005):
    """Replace the selected window with silence (5 ms soft edges to
    avoid clicks). The selection stays valid: duration is unchanged.

    Returns a dict with ok/output_path/sample_rate/duration_s/
    start_s/end_s. Raises ValueError on a missing input or bad window.
    """
    samples, sample_rate, s, e = _clamp_window(input_path, start_sec, end_sec)
    out, s, e = _apply_trim(samples, sample_rate, start_sec, end_sec,
                            edge_fade_sec=edge_fade_sec)
    write_wav(output_path, out, sample_rate)
    return {
        "ok": True,
        "output_path": output_path,
        "sample_rate": sample_rate,
        "duration_s": round(len(out) / sample_rate, 2),
        "start_s": round(s / sample_rate, 2),
        "end_s": round(e / sample_rate, 2),
    }


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


def join_remove(input_path, output_path, start_sec, end_sec, fade_sec=0.005):
    """Cut the selected window out and glue the remaining parts together.

    A 5 ms crossfade at the junction avoids clicks; there is no silence
    in between. Returns a dict with ok/output_path/sample_rate/
    duration_s/removed_s. Raises ValueError on a missing input, a bad
    window, or a window covering the whole file.
    """
    samples, sample_rate, s, e = _clamp_window(input_path, start_sec, end_sec)
    out, s, e = _apply_join(samples, sample_rate, start_sec, end_sec,
                            fade_sec=fade_sec)
    write_wav(output_path, out, sample_rate)
    return {
        "ok": True,
        "output_path": output_path,
        "sample_rate": sample_rate,
        "duration_s": round(len(out) / sample_rate, 2),
        "removed_s": round((e - s) / sample_rate, 2),
    }


def _run_ops(samples, sample_rate, ops, progress=None):
    """Fold parsed op dicts over an in-memory buffer.

    The buffer stays a NumPy float64 array throughout when NumPy is
    available, otherwise a Python list; each op accepts array-like input
    and returns the same kind it runs under. Returns (samples, sample_rate).
    """
    if _HAVE_NP:
        samples = _np.asarray(samples, dtype=_np.float64)
    else:
        samples = list(samples)
    for i, entry in enumerate(ops):
        if not isinstance(entry, dict):
            raise ValueError("op #%d is not an object" % i)
        op = entry.get("op")
        params = entry.get("params") or {}
        if not isinstance(params, dict):
            raise ValueError("op #%d params must be an object" % i)
        _cb(progress, "tuneUpStepActive", i)
        samples = _apply_op(samples, sample_rate, op, params, i)
        _cb(progress, "tuneUpStepDone", i)
    return samples, sample_rate


def render_chain(pristine_path, ops_json, output_path, progress=None,
                 cache_key=None):
    """Render a library op chain: fold ops over pristine in float, write
    the 16-bit WAV once.

    ``ops_json`` is a JSON array of {"op": <type>, "params": {...}} where
    <type> is one of "fx", "normalize", "pitch_shift", "trim", "join", the repair ops
    ("dc_remove", "fade_ends", "silence_trim", "hum_notch", "declip",
    "denoise", "deplosive", "deess", "level", "loudness", "eq_balance"),
    or the composite "tune_up" (whose params.fixes is an array of repair
    ops). Ops apply in sequence in float; window positions refer to the
    current buffer at apply time, exactly like stacked application.
    ``livefx`` ops are never passed in (display-only): they raise
    ValueError, as do unknown ops and invalid parameters (same validation
    as the file-level functions).

    ``progress`` is an optional Java callback implementing the tune-up
    step protocol (tuneUpStepActive(int) / tuneUpStepDone(int)); it is
    only ever passed by tune_up_converge.

    ``cache_key`` (e.g. the library entry id) seeds the chain-tip cache
    with the exact float output, so a later render_chain_from_tip can
    continue incrementally.

    Returns {"ok": True, "output_path": ..., "duration_s": ..., "sample_rate": ...}.
    """
    if not os.path.isfile(pristine_path):
        raise ValueError("input file not found: %s" % pristine_path)
    try:
        ops = json.loads(ops_json)
    except (TypeError, ValueError) as exc:
        raise ValueError("ops_json is not valid JSON: %s" % exc)
    if not isinstance(ops, list):
        raise ValueError("ops_json must be a JSON array of ops")
    samples, sample_rate = read_wav_mono(pristine_path)
    samples, sample_rate = _run_ops(samples, sample_rate, ops, progress)
    _tip_seed(cache_key, samples, sample_rate)
    write_wav(output_path, samples, sample_rate)
    return {
        "ok": True,
        "output_path": output_path,
        "duration_s": round(len(samples) / sample_rate, 2),
        "sample_rate": sample_rate,
    }


def render_chain_from_tip(cache_key, new_ops_json, output_path,
                           progress=None):
    """Apply new ops starting from the cached chain tip (see _tip_cache).

    The caller guarantees the cache entry is current for the entry being
    rendered. On a miss returns {"ok": False, "cache_hit": False} and the
    caller falls back to a full render_chain. On a hit the tip is exact
    float, so incremental rendering is bit-identical to a full render.
    """
    entry = _tip_cache.get(cache_key)
    if entry is None:
        return {"ok": False, "cache_hit": False, "reason": "tip-cache miss"}
    try:
        ops = json.loads(new_ops_json)
    except (TypeError, ValueError) as exc:
        raise ValueError("new_ops_json is not valid JSON: %s" % exc)
    if not isinstance(ops, list):
        raise ValueError("new_ops_json must be a JSON array of ops")
    samples, sample_rate = _run_ops(entry[0], entry[1], ops, progress)
    _tip_seed(cache_key, samples, sample_rate)
    write_wav(output_path, samples, sample_rate)
    return {
        "ok": True,
        "cache_hit": True,
        "output_path": output_path,
        "duration_s": round(len(samples) / sample_rate, 2),
        "sample_rate": sample_rate,
    }


def diagnose_file(input_wav_path):
    """Run the diagnostics over a WAV file. Returns a JSON array of
    findings (see diagnose.analyze). Runs on a background thread from
    Java; a multi-minute file takes a few seconds."""
    if not os.path.isfile(input_wav_path):
        raise ValueError("input file not found: %s" % input_wav_path)
    samples, sample_rate = read_wav_mono(input_wav_path)
    findings = _diagnose.analyze(samples, sample_rate)
    return json.dumps(findings)


def tonic_file(input_wav_path):
    """Tonic + swara notation for a WAV file. Returns JSON (see
    tonic.notate_song). Runs on a background thread from Java; a
    multi-minute file takes on the order of a minute."""
    if not os.path.isfile(input_wav_path):
        raise ValueError("input file not found: %s" % input_wav_path)
    samples, sample_rate = read_wav_mono(input_wav_path)
    info = _tonic.notate_song(samples, sample_rate)
    return json.dumps(info if info is not None else {})


def list_tune_up_profiles():
    """Profile metadata for the Tune Up recipe picker UI."""
    return json.dumps(_diagnose.list_profiles())


def summarize_request_body(findings_json, profile):
    """Groq request-body JSON for the executive summary (HTTP is done in
    Java). Returns "" when the findings are unusable."""
    from audiofx import summarize as _summarize
    return _summarize.build_request_body(findings_json, profile)


def summarize_parse(response_json):
    """Extract the summary text from a Groq response body, or ""."""
    from audiofx import summarize as _summarize
    return _summarize.parse_response(response_json)


def tune_up_converge(input_path, findings_json, profile="podcast",
                     progress=None, max_passes=3, output_path=None,
                     cache_key=None):
    """Iterate the tune-up until no fixable warns remain (or max_passes).

    Conservative fixes (denoise, de-esser, EQ) often don't clear their own
    check in one pass; without this the user has to tap Tune Up repeatedly.
    Each pass re-diagnoses the previous pass's render and appends that
    pass's fixes. Returns ONE composite tune_up op containing every pass's
    fixes, so it stays a single history entry / single undo. Fixes are
    pure functions, so rendering incrementally per pass equals rendering
    the flat fix list once.

    Passes run on the in-memory buffer (no temp WAVs); the converged audio
    is written once to ``output_path`` when given, so the caller can adopt
    it directly instead of re-rendering the chain. The top-level return
    dict carries duration_s for the adopted render. ``cache_key`` seeds the
    chain-tip cache with the exact float output for later incremental
    renders.

    ``progress`` is an optional Java callback with methods
    tuneUpPassStarted(passNumber, maxPasses, labelsJson),
    tuneUpStepActive(stepIndex) and tuneUpStepDone(stepIndex); each call
    is exception-guarded so UI failures can never break the render.
    """
    try:
        findings = json.loads(findings_json)
    except (TypeError, ValueError) as exc:
        raise ValueError("findings_json is not valid JSON: %s" % exc)
    if not isinstance(findings, list):
        raise ValueError("findings_json must be a JSON array of findings")
    if not os.path.isfile(input_path):
        raise ValueError("input file not found: %s" % input_path)
    prof = _diagnose.TUNE_UP_PROFILES.get(
        profile, _diagnose.TUNE_UP_PROFILES[_diagnose.DEFAULT_PROFILE])
    all_fixes = []
    # Prefer the exact float chain tip over the 16-bit file when the
    # caller seeded it: the adopted render is then bit-identical to a
    # full chain re-render. (The file is only +/-0.5 LSB off, but exact
    # is free here.)
    tip = _tip_cache.get(cache_key) if cache_key is not None else None
    if tip is not None:
        samples, sample_rate = tip[0].copy(), tip[1]
    else:
        samples, sample_rate = read_wav_mono(input_path)
    passes = 0
    for _ in range(max_passes):
        fixes = _diagnose.tune_up_fixes(findings, profile)
        if not fixes:
            break
        passes += 1
        _cb(progress, "tuneUpPassStarted", passes, max_passes,
            json.dumps([f.get("label", f["op"]) for f in fixes]))
        all_fixes.extend(fixes)
        samples, sample_rate = _run_ops(
            samples, sample_rate,
            [{"op": f["op"], "params": f["params"]} for f in fixes],
            progress)
        findings = _diagnose.analyze(samples, sample_rate)
    labels = []
    seen = set()
    for f in all_fixes:
        if f["op"] not in seen:
            seen.add(f["op"])
            labels.append(f.get("label", f["op"]))
    if labels:
        summary = "Tune Up (%s): %s" % (prof["label"], ", ".join(labels))
        if passes > 1:
            summary += " (%d passes)" % passes
    else:
        summary = "Tune Up (%s): nothing to fix" % prof["label"]
    if output_path is not None:
        write_wav(output_path, samples, sample_rate)
    _tip_seed(cache_key, samples, sample_rate)
    return json.dumps({
        "op": "tune_up",
        "params": {"fixes": all_fixes, "summary": summary,
                   "profile": profile, "passes": passes},
        "duration_s": round(len(samples) / sample_rate, 2),
        "sample_rate": sample_rate,
    })


def tune_up_build(findings_json, profile="podcast"):
    """Build the composite tune_up op from diagnose_file's findings JSON
    and a profile id ("podcast", "singing", "music"). Returns the op as a
    JSON object string:
    {"op": "tune_up", "params": {"fixes": [...], "summary": "...",
                                 "profile": "podcast"}}."""
    try:
        findings = json.loads(findings_json)
    except (TypeError, ValueError) as exc:
        raise ValueError("findings_json is not valid JSON: %s" % exc)
    if not isinstance(findings, list):
        raise ValueError("findings_json must be a JSON array of findings")
    prof = _diagnose.TUNE_UP_PROFILES.get(
        profile, _diagnose.TUNE_UP_PROFILES[_diagnose.DEFAULT_PROFILE])
    fixes = _diagnose.tune_up_fixes(findings, profile)
    labels = [f.get("label", f.get("op", "?")) for f in fixes]
    summary = ("Tune Up (%s): " % prof["label"] + ", ".join(labels)) \
        if labels else "Tune Up (%s): nothing to fix" % prof["label"]
    return json.dumps({
        "op": "tune_up",
        "params": {"fixes": fixes, "summary": summary, "profile": profile},
    })


def live_chain_create(warmth, reverb_preset, echo, sample_rate,
                      block_size=2048, channels=2, stereo=False):
    """Create a stateful live chain for real-time block processing.

    With stereo=True the chain keeps stereo in/out (two decorrelated
    mono chains); otherwise it mixes down to mono. Feed it consecutive
    PCM16 blocks via process_pcm_block.
    """
    if stereo:
        from .live import StereoLiveChain
        return StereoLiveChain(warmth=warmth, reverb_preset=reverb_preset,
                               echo=echo, sample_rate=sample_rate,
                               block_size=block_size)
    from .live import LiveChain
    return LiveChain(warmth=warmth, reverb_preset=reverb_preset, echo=echo,
                     sample_rate=sample_rate, block_size=block_size,
                     channels=channels)


def encode_wav_to_mp3(input_wav_path, output_mp3_path, bitrate_kbps=192):
    """Encode a WAV file (e.g. the processed output) as MP3.

    Returns a dict with ok/output_path/sample_rate/size_bytes.
    Raises ValueError on a missing input, RuntimeError if lameenc is missing.
    """
    if not os.path.isfile(input_wav_path):
        raise ValueError("input file not found: %s" % input_wav_path)
    samples, sample_rate = read_wav_mono(input_wav_path)
    mp3_bytes = encode_mp3(samples, sample_rate, bitrate_kbps)
    with open(output_mp3_path, "wb") as f:
        f.write(mp3_bytes)
    return {
        "ok": True,
        "output_path": output_mp3_path,
        "sample_rate": sample_rate,
        "size_bytes": len(mp3_bytes),
    }


def waveform_peaks(input_path, n_buckets=240):
    """Peak amplitudes of a WAV file for the waveform display.

    Returns a dict with peaks_json (a JSON array of floats in 0..1),
    sample_rate, bit_depth and duration_s. Raises ValueError on a
    missing input.
    """
    if not os.path.isfile(input_path):
        raise ValueError("input file not found: %s" % input_path)
    samples, sample_rate = read_wav_mono(input_path)
    with wave.open(input_path, "rb") as w:
        bit_depth = w.getsampwidth() * 8
    peaks = _waveform_peaks(samples, int(n_buckets))
    return {
        "peaks_json": json.dumps(peaks),
        "sample_rate": sample_rate,
        "bit_depth": bit_depth,
        "duration_s": round(len(samples) / sample_rate, 2),
    }


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


def _parse_mix_args(tracks_json, muted_ids_json, target_sample_rate):
    """Shared argument parsing for mixdown()/mix_peaks().

    Returns (items, muted_ids_set, target_rate). Raises ValueError on
    malformed JSON or a non-positive sample rate.
    """
    try:
        items = json.loads(tracks_json)
    except (TypeError, ValueError) as exc:
        raise ValueError("tracks_json is not valid JSON: %s" % exc)
    if not isinstance(items, list):
        raise ValueError("tracks_json must be a JSON array of tracks")
    try:
        muted_ids = json.loads(muted_ids_json)
    except (TypeError, ValueError) as exc:
        raise ValueError("muted_ids_json is not valid JSON: %s" % exc)
    if not isinstance(muted_ids, list):
        raise ValueError("muted_ids_json must be a JSON array of ids")
    muted = set(m for m in muted_ids if isinstance(m, str))
    target_rate = int(target_sample_rate)
    if target_rate <= 0:
        raise ValueError("target_sample_rate must be positive: %r"
                         % (target_sample_rate,))
    return items, muted, target_rate


def _scale_samples(samples, gain):
    """Multiply a sample buffer (list or numpy array) by a gain."""
    if _HAVE_NP and isinstance(samples, _np.ndarray):
        return samples * gain
    return [s * gain for s in samples]


def _load_audible_track_samples(items, muted, target_rate):
    """Read each audible track's WAV as a float64 buffer at target_rate.

    Muted ids are skipped before any file access; missing, undecodable,
    or empty tracks contribute nothing. Tracks at other sample rates
    are resampled with linear interpolation (see resample_linear).
    A dict item's optional "gain" (per-track volume, default 1.0)
    scales that track's contribution to the mix.
    """
    tracks = []
    for item in items:
        gain = 1.0
        if isinstance(item, dict):
            track_id = item.get("id")
            p = item.get("path")
            g = item.get("gain")
            if isinstance(g, (int, float)) and not isinstance(g, bool):
                gain = max(0.0, float(g))
        else:
            track_id, p = None, item
        if track_id is not None and track_id in muted:
            continue
        if not isinstance(p, str) or not os.path.isfile(p):
            continue
        try:
            samples, rate = read_wav_mono(p)
        except Exception:
            continue
        if len(samples) == 0:
            continue
        if rate != target_rate:
            samples = resample_linear(samples, rate, target_rate)
        if gain != 1.0:
            samples = _scale_samples(samples, gain)
        tracks.append(samples)
    return tracks


def _sum_and_normalize(tracks, target_rate):
    """Zero-pad the track buffers to the longest, sum in float64, then
    true-peak normalize to -1 dBFS (gain = 10**(-1/20) / peak, output
    hard-clipped to +/-10**(-1/20)). Returns the normalized mix buffer.
    """
    target = 10.0 ** (-1.0 / 20.0)
    n = max(len(t) for t in tracks)
    if _HAVE_NP:
        mix = _np.zeros(n, dtype=_np.float64)
        for t in tracks:
            mix[: len(t)] += _np.asarray(t, dtype=_np.float64)
        peak = float(_np.max(_np.abs(mix))) if n else 0.0
        if peak > 0:
            return _np.clip(mix * (target / peak), -target, target)
        return mix
    mix = [0.0] * n
    for t in tracks:
        for i, s in enumerate(t):
            mix[i] += float(s)
    peak = max(abs(s) for s in mix) if mix else 0.0
    if peak > 0:
        gain = target / peak
        return [min(max(s * gain, -target), target) for s in mix]
    return mix


def mixdown(tracks_json, output_path, target_sample_rate, muted_ids_json="[]"):
    """Mix rendered track WAVs down to one mono file.

    ``tracks_json`` is a JSON array where each item is either a
    {"id": <str>, "path": <str>} object or (back-compat) a bare WAV
    file path string, which is treated as {"id": None, "path": <string>}
    and is never muted. ``muted_ids_json`` is a JSON array of track id
    strings to skip; a muted id skips its track even if its file is
    fine, unknown ids are ignored, and bare path strings are never
    muted. Empty tracks are skipped: a path that is missing, cannot be
    decoded, or holds no frames contributes nothing to the mix. Locked
    tracks are included in the mix (lock blocks changes, not playback).
    Tracks at other sample rates are resampled with linear
    interpolation (see resample_linear). A dict item may carry a
    "gain" (per-track volume, default 1.0) scaling that track before
    the sum. Shorter tracks are zero-padded
    to the longest, then summed in float64. The mix is true-peak
    normalized to -1 dBFS: gain = 10**(-1/20) / peak, and the output is
    hard-clipped to +/-10**(-1/20) (true peak, not a percentile).

    Writes the 16-bit WAV to ``output_path`` and returns {"ok": True,
    "output_path": ..., "sample_rate": ..., "duration_s": ...,
    "tracks_mixed": N}. Raises ValueError("no audible tracks to mix")
    when no track yields audio.
    """
    items, muted, target_rate = _parse_mix_args(
        tracks_json, muted_ids_json, target_sample_rate)
    tracks = _load_audible_track_samples(items, muted, target_rate)
    if not tracks:
        raise ValueError("no audible tracks to mix")
    out = _sum_and_normalize(tracks, target_rate)
    write_wav(output_path, out, target_rate)
    return {
        "ok": True,
        "output_path": output_path,
        "sample_rate": target_rate,
        "duration_s": round(len(out) / target_rate, 2),
        "tracks_mixed": len(tracks),
    }


def mix_peaks(tracks_json, target_sample_rate, muted_ids_json="[]",
              num_peaks=1200):
    """Peak amplitudes of the peak-normalized mix, computed in memory.

    Exactly the mix that mixdown() would write (same track selection,
    resampling, sum, and -1 dBFS normalization), but no file is written:
    this is the lazy-mix path for the waveform display, where the mix
    file itself is only produced on demand (playback, overdub, share).
    Returns {"ok": True, "peaks_json": ..., "duration_s": ...,
    "sample_rate": ..., "tracks_mixed": N}, where peaks_json is a JSON
    array of floats in 0..1. Raises ValueError("no audible tracks to
    mix") when no track yields audio.
    """
    items, muted, target_rate = _parse_mix_args(
        tracks_json, muted_ids_json, target_sample_rate)
    tracks = _load_audible_track_samples(items, muted, target_rate)
    if not tracks:
        raise ValueError("no audible tracks to mix")
    out = _sum_and_normalize(tracks, target_rate)
    peaks = _waveform_peaks(out, int(num_peaks))
    return {
        "ok": True,
        "peaks_json": json.dumps(peaks),
        "duration_s": round(len(out) / target_rate, 2),
        "sample_rate": target_rate,
        "tracks_mixed": len(tracks),
    }


def resolve_record_target(tracks_json):
    """Decide which track a new recording goes to. Returns a JSON string.

    Input is a JSON array of {"id": str, "locked": bool, "has_audio":
    bool, "position": int, "selected": bool}. The rule, in order:

    1. If the selected track exists, is not locked, and has no audio,
       record there.
    2. Else record on the first track by position with locked=false and
       has_audio=false.
    3. Else create a new track (Java auto-creates "Track N").

    A locked selected track is skipped. Recording never targets a track
    with has_audio=true. An empty input array resolves to create.

    Output is {"action": "record", "track_id": <id>} or
    {"action": "create"}.
    """
    try:
        tracks = json.loads(tracks_json)
    except (TypeError, ValueError) as exc:
        raise ValueError("tracks_json is not valid JSON: %s" % exc)
    if not isinstance(tracks, list):
        raise ValueError("tracks_json must be a JSON array of tracks")
    dicts = [t for t in tracks if isinstance(t, dict)]

    def recordable(t):
        return not t.get("locked", False) and not t.get("has_audio", False)

    selected = next((t for t in dicts if t.get("selected", False)), None)
    if selected is not None and recordable(selected):
        return json.dumps({"action": "record",
                           "track_id": selected.get("id")})
    for t in sorted(dicts, key=lambda t: t.get("position", 0)):
        if recordable(t):
            return json.dumps({"action": "record", "track_id": t.get("id")})
    return json.dumps({"action": "create"})


def notation_export(info_json, title):
    """Render a stored notation result as shivkumar.org-style text.

    info_json: a notate_song() result JSON (as produced by
    tonic_file). Returns the export document text.
    """
    import json as _json

    from . import tonic as _tonic

    info = _json.loads(info_json)
    return _tonic.format_shivkumar(info, title)


def notation_section(info_json):
    """One track's notation-export section as JSON.

    Returns {"meta": str, "body": [line, ...]} for laying out the
    multi-track PDF: the caller supplies per-track titles, this
    supplies the tonic/swaras meta line and the avartanam lines in
    the shivkumar.org convention (see tonic.format_shivkumar).
    """
    import json as _json

    from . import tonic as _tonic

    info = _json.loads(info_json)
    return _json.dumps(_tonic.export_section(info))
