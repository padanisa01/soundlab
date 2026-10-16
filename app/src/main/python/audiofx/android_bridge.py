"""Entry point called from the Android UI (MainActivity) via Chaquopy.

Kept deliberately thin: argument validation, WAV in/out, and the fixed
effect order Warmth -> Reverb -> Echo.
"""

import math
import os

from .effects import Echo, Reverb, Warmth
from .pipeline import (
    EffectChain,
    encode_mp3,
    read_wav_mono,
    splice_with_crossfade,
    waveform_peaks as _waveform_peaks,
    write_wav,
)


def list_reverb_presets():
    """Preset names for the Android spinner, sorted."""
    return sorted(Reverb.PRESETS)


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
    write_wav(output_path, processed, sample_rate)
    return {
        "ok": True,
        "output_path": output_path,
        "sample_rate": sample_rate,
        "duration_s": round(len(processed) / sample_rate, 2),
        "start_s": round(s / sample_rate, 2),
        "end_s": round(e / sample_rate, 2),
    }


def normalize_file(input_path, output_path, target_db=-1.0):
    """Peak-normalize a WAV file so its peak reaches target_db dBFS.

    A whole-file gain change: every sample is scaled by the same factor,
    so dynamics are preserved. Target defaults to -1 dBFS, leaving
    headroom for MP3 encoding. Returns a dict with ok/output_path/
    sample_rate/duration_s/peak_before_db/peak_after_db/applied_gain_db.
    Raises ValueError on a missing input or on silent input (no peak to
    normalize to).
    """
    if not os.path.isfile(input_path):
        raise ValueError("input file not found: %s" % input_path)
    samples, sample_rate = read_wav_mono(input_path)
    peak = max(abs(s) for s in samples)
    if peak <= 0:
        raise ValueError("input is silent: nothing to normalize")
    target_peak = 10.0 ** (target_db / 20.0)
    gain = target_peak / peak
    write_wav(output_path, [s * gain for s in samples], sample_rate)
    return {
        "ok": True,
        "output_path": output_path,
        "sample_rate": sample_rate,
        "duration_s": round(len(samples) / sample_rate, 2),
        "peak_before_db": round(20 * math.log10(peak), 2),
        "peak_after_db": round(target_db, 2),
        "applied_gain_db": round(20 * math.log10(gain), 2),
    }


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
    import json
    import wave

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
