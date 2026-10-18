"""Entry point called from the Android UI (MainActivity) via Chaquopy.

Kept deliberately thin: argument validation, WAV in/out, and the fixed
effect order Warmth -> Reverb -> Echo.
"""

import json
import math
import os
import wave

from . import diagnose as _diagnose
from . import repair as _repair
from .effects import Echo, Reverb, Warmth
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


def _apply_op(samples, sample_rate, op, params, index):
    """Apply one render_chain op. Shared by the main loop and the tune_up
    expansion so the two can never drift apart."""
    if op == "fx":
        samples, _, _ = _apply_fx(
            samples, sample_rate,
            warmth=params.get("warmth", 0.5),
            reverb_preset=params.get("reverb_preset", "studio"),
            echo=params.get("echo", 0.3),
            start_sec=params.get("start_sec"),
            end_sec=params.get("end_sec"))
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
    peak = max(abs(s) for s in samples)
    if peak <= 0:
        raise ValueError("input is silent: nothing to normalize")
    mag = sorted(abs(s) for s in samples)
    ref = mag[min(len(mag) - 1, int(math.ceil(percentile / 100.0 * len(mag))) - 1)]
    if ref <= 0:
        raise ValueError("input is silent below the percentile: nothing to normalize")
    target_peak = 10.0 ** (target_db / 20.0)
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


def render_chain(pristine_path, ops_json, output_path):
    """Render a library op chain: fold ops over pristine in float, write
    the 16-bit WAV once.

    ``ops_json`` is a JSON array of {"op": <type>, "params": {...}} where
    <type> is one of "fx", "normalize", "trim", "join", the repair ops
    ("dc_remove", "fade_ends", "silence_trim", "hum_notch", "declip",
    "denoise", "deplosive", "deess", "level", "loudness", "eq_balance"),
    or the composite "tune_up" (whose params.fixes is an array of repair
    ops). Ops apply in sequence in float; window positions refer to the
    current buffer at apply time, exactly like stacked application.
    ``livefx`` ops are never passed in (display-only): they raise
    ValueError, as do unknown ops and invalid parameters (same validation
    as the file-level functions).

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
    samples = list(samples)
    for i, entry in enumerate(ops):
        if not isinstance(entry, dict):
            raise ValueError("op #%d is not an object" % i)
        op = entry.get("op")
        params = entry.get("params") or {}
        if not isinstance(params, dict):
            raise ValueError("op #%d params must be an object" % i)
        samples = _apply_op(samples, sample_rate, op, params, i)
    write_wav(output_path, samples, sample_rate)
    return {
        "ok": True,
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
                     max_passes=3):
    """Iterate the tune-up until no fixable warns remain (or max_passes).

    Conservative fixes (denoise, de-esser, EQ) often don't clear their own
    check in one pass; without this the user has to tap Tune Up repeatedly.
    Each pass re-diagnoses the previous pass's render and appends that
    pass's fixes. Returns ONE composite tune_up op containing every pass's
    fixes, so it stays a single history entry / single undo. Fixes are
    pure functions, so rendering incrementally per pass equals rendering
    the flat fix list once.
    """
    try:
        findings = json.loads(findings_json)
    except (TypeError, ValueError) as exc:
        raise ValueError("findings_json is not valid JSON: %s" % exc)
    if not isinstance(findings, list):
        raise ValueError("findings_json must be a JSON array of findings")
    prof = _diagnose.TUNE_UP_PROFILES.get(
        profile, _diagnose.TUNE_UP_PROFILES[_diagnose.DEFAULT_PROFILE])
    all_fixes = []
    cur_in = input_path
    tmps = []
    passes = 0
    try:
        for _ in range(max_passes):
            fixes = _diagnose.tune_up_fixes(findings, profile)
            if not fixes:
                break
            passes += 1
            all_fixes.extend(fixes)
            tmp = os.path.join(os.path.dirname(os.path.abspath(input_path)),
                               ".tune_tmp_%d.wav" % len(tmps))
            tmps.append(tmp)
            r = render_chain(cur_in, json.dumps(
                [{"op": f["op"], "params": f["params"]} for f in fixes]), tmp)
            if not r.get("ok"):
                break
            cur_in = tmp
            findings = json.loads(diagnose_file(tmp))
    finally:
        for t in tmps:
            try:
                os.unlink(t)
            except OSError:
                pass
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
    return json.dumps({
        "op": "tune_up",
        "params": {"fixes": all_fixes, "summary": summary,
                   "profile": profile, "passes": passes},
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
