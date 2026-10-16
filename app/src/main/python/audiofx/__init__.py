"""AudioFX: audio effects engine.

Pure-Python DSP (optional NumPy fast path) shared by the desktop unit tests
and the Android app, where it runs under Chaquopy. All effects operate on
mono float samples in [-1.0, 1.0] and are exact bypass at zero amount.
"""

from .effects import Warmth, Reverb, Echo
from .pipeline import EffectChain, read_wav_mono, write_wav

__version__ = "1.0.0"
__all__ = ["Warmth", "Reverb", "Echo", "EffectChain", "read_wav_mono", "write_wav"]
