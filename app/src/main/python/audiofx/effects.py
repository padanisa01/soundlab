"""Audio effects: Warmth, Reverb, Echo.

Design notes
------------
* Everything is mono float samples in [-1.0, 1.0]. Helpers never mutate the
  input; they return new lists.
* NumPy is used only as a fast path (FFT convolution for reverb). When it is
  missing, a pure-Python fallback is used; long signals then raise a clear
  error instead of hanging.
* Warmth and Echo are exact identity at amount=0.0 (cheap bypass).
"""

import math
import random

try:
    import numpy as _np

    _HAVE_NUMPY = True
except ImportError:  # pragma: no cover - desktop CI always has numpy
    _np = None
    _HAVE_NUMPY = False


def _clamp01(value):
    return max(0.0, min(1.0, float(value)))


def _lowpass(samples, cutoff_hz, sample_rate):
    """One-pole low-pass filter. Returns a new list."""
    alpha = 1.0 - math.exp(-2.0 * math.pi * cutoff_hz / sample_rate)
    out = [0.0] * len(samples)
    y = 0.0
    for i, x in enumerate(samples):
        y += alpha * (x - y)
        out[i] = y
    return out


def _direct_convolve(x, h):
    n, m = len(x), len(h)
    out = [0.0] * (n + m - 1)
    for i, xv in enumerate(x):
        if xv == 0.0:
            continue
        for j, hv in enumerate(h):
            out[i + j] += xv * hv
    return out


def _fft_convolve(x, h):
    """Overlap-add FFT convolution via NumPy. Returns a list of floats."""
    xa = _np.asarray(x, dtype=_np.float64)
    ha = _np.asarray(h, dtype=_np.float64)
    n, m = xa.size, ha.size
    if m == 0:
        return [0.0] * n
    block = 1
    while block < 2 * m:
        block *= 2
    H = _np.fft.rfft(ha, block)
    seg_len = block - m + 1
    out = _np.zeros(n + m - 1, dtype=_np.float64)
    for start in range(0, n, seg_len):
        seg = xa[start : start + seg_len]
        y = _np.fft.irfft(_np.fft.rfft(seg, block) * H, block)
        end = min(start + y.size, out.size)
        out[start:end] += y[: end - start]
    return out.tolist()


def _convolve(x, h):
    if _HAVE_NUMPY:
        return _fft_convolve(x, h)
    if len(x) * len(h) > 4_000_000:
        raise RuntimeError(
            "signal too long for pure-Python convolution "
            "(%d x %d taps); install numpy for the FFT fast path" % (len(x), len(h))
        )
    return _direct_convolve(x, h)


class Warmth:
    """Warmth: saturated low-end body plus softened highs.

    ``amount`` is the UI slider, 0.0..1.0. 0.0 is bit-identical bypass.
    """

    def __init__(self, amount=0.5):
        self.amount = _clamp01(amount)

    def process(self, samples, sample_rate):
        a = self.amount
        if a <= 0.0:
            return list(samples)
        low = _lowpass(samples, 750.0, sample_rate)
        drive = 1.0 + 2.5 * a
        wet = [math.tanh(drive * s) for s in low]
        dry_gain = 1.0 - 0.5 * a
        wet_gain = 0.5 * a
        return [d * dry_gain + w * wet_gain for d, w in zip(samples, wet)]


class Reverb:
    """Convolution reverb with named presets.

    Each preset synthesizes a decaying-noise impulse response: ``decay`` is
    the ~60 dB decay time in seconds, ``predelay_ms`` the pre-delay,
    ``brightness`` the IR damping (0 = dark, 1 = bright), ``wet`` the mix.
    ``ir_seconds`` is an advanced/test override for the IR length.
    """

    PRESETS = {
        "studio": {"decay": 0.5, "predelay_ms": 8.0, "brightness": 0.75, "wet": 0.25},
        "concert_hall": {"decay": 2.2, "predelay_ms": 25.0, "brightness": 0.55, "wet": 0.35},
        "bathroom": {"decay": 0.8, "predelay_ms": 5.0, "brightness": 0.95, "wet": 0.30},
        "cave": {"decay": 4.0, "predelay_ms": 40.0, "brightness": 0.30, "wet": 0.45},
    }

    def __init__(self, preset="studio", seed=1234, ir_seconds=None):
        if preset not in self.PRESETS:
            raise ValueError(
                "unknown reverb preset %r; choose from %s"
                % (preset, sorted(self.PRESETS))
            )
        self.preset = preset
        self.params = dict(self.PRESETS[preset])
        self.seed = seed
        self.ir_seconds = ir_seconds

    def _build_ir(self, sample_rate):
        p = self.params
        ir_len = int((self.ir_seconds or p["decay"]) * sample_rate)
        ir_len = max(8, min(ir_len, 5 * sample_rate))
        rng = random.Random(self.seed)
        decay = p["decay"]
        ir = [
            rng.uniform(-1.0, 1.0) * math.exp(-6.9078 * (i / sample_rate) / decay)
            for i in range(ir_len)
        ]
        cutoff = 1200.0 + p["brightness"] * 6800.0
        ir = _lowpass(ir, cutoff, sample_rate)
        # Unit-energy normalization: the wet path then has ~unity RMS gain,
        # so the `wet` mix directly sets reverb loudness relative to dry.
        # (Peak normalization blew up sustained signals: ~29x RMS gain on a
        # 0.8 s IR, caught on a real 112 s track, 2026-10-16.)
        energy = math.sqrt(sum(s * s for s in ir)) or 1.0
        ir = [s / energy for s in ir]
        predelay = int(p["predelay_ms"] * sample_rate / 1000.0)
        return [0.0] * predelay + ir

    def process(self, samples, sample_rate):
        wet = self.params["wet"]
        ir = self._build_ir(sample_rate)
        tail = _convolve(samples, ir)
        out = [0.0] * len(tail)
        dry_gain = 1.0 - wet
        for i, s in enumerate(samples):
            out[i] += s * dry_gain
        for i, s in enumerate(tail):
            out[i] += s * wet
        return out


class Echo:
    """Feedback delay. ``amount`` (0.0..1.0) is the UI slider: it scales both
    the echo level and the feedback. 0.0 is bit-identical bypass."""

    def __init__(self, amount=0.5, delay_ms=320.0):
        self.amount = _clamp01(amount)
        self.delay_ms = max(1.0, float(delay_ms))

    def process(self, samples, sample_rate):
        a = self.amount
        if a <= 0.0:
            return list(samples)
        delay = max(1, int(self.delay_ms * sample_rate / 1000.0))
        feedback = 0.65 * a
        buf = [0.0] * delay
        idx = 0
        out = []
        append = out.append
        for s in samples:
            d = buf[idx]
            append(s + a * d)
            buf[idx] = s + feedback * d
            idx = (idx + 1) % delay
        return out
