"""Entry point called from the Android UI (MainActivity) via Chaquopy.

Kept deliberately thin: argument validation, WAV in/out, and the fixed
effect order Warmth -> Reverb -> Echo.
"""

import os

from .effects import Echo, Reverb, Warmth
from .pipeline import EffectChain, read_wav_mono, write_wav


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
