"""Entry point called from the Android UI (MainActivity) via Chaquopy.

Kept deliberately thin: argument validation, WAV in/out, and the fixed
effect order Warmth -> Reverb -> Echo.
"""

import os

from .effects import Echo, Reverb, Warmth
from .pipeline import EffectChain, encode_mp3, read_wav_mono, write_wav


def list_reverb_presets():
    """Preset names for the Android spinner, sorted."""
    return sorted(Reverb.PRESETS)


def process_file(input_path, output_path, warmth=0.5, reverb_preset="studio", echo=0.3):
    """Apply the effect chain to a WAV file.

    Returns a dict with ok/output_path/sample_rate/duration_s.
    Raises ValueError on a missing input or unknown preset.
    """
    if not os.path.isfile(input_path):
        raise ValueError("input file not found: %s" % input_path)
    samples, sample_rate = read_wav_mono(input_path)
    chain = EffectChain(
        [Warmth(warmth), Reverb(reverb_preset), Echo(echo)]
    )
    processed = chain.process(samples, sample_rate)
    write_wav(output_path, processed, sample_rate)
    return {
        "ok": True,
        "output_path": output_path,
        "sample_rate": sample_rate,
        "duration_s": round(len(processed) / sample_rate, 2),
    }


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
