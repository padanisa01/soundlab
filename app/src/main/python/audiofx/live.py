"""Real-time block processing for live effect monitoring/recording.

LiveChain mirrors the offline Warmth -> Reverb -> Echo chain, but keeps
filter, delay-line and convolution state across blocks, so a stream can
be processed in fixed-size chunks with output identical to offline
processing of the whole signal.
"""

import math
import struct

try:
    import numpy as _np

    _HAVE_NUMPY = True
except ImportError:  # pragma: no cover - desktop CI always has numpy
    _np = None
    _HAVE_NUMPY = False

from .effects import Reverb


class LiveChain:
    """Stateful block processor: Warmth -> Reverb -> Echo.

    Construct once per take (effect params are snapshotted), then call
    process_block/process_pcm_block for each consecutive input block.
    """

    def __init__(self, warmth=0.5, reverb_preset="studio", echo=0.3,
                 sample_rate=48000, block_size=2048, channels=2):
        if not _HAVE_NUMPY:
            raise RuntimeError("live effects need numpy")
        self.sample_rate = int(sample_rate)
        self.block_size = int(block_size)
        self.channels = int(channels)
        # Warmth: one-pole lowpass state carried across blocks.
        self.warmth = max(0.0, min(1.0, float(warmth)))
        self._lp_alpha = 1.0 - math.exp(-2.0 * math.pi * 750.0 / self.sample_rate)
        self._lp_y = 0.0
        # Reverb: partitioned convolution state.
        self._reverb = Reverb(reverb_preset)
        self._rev_wet = self._reverb.params["wet"]
        self._rev_dry = 1.0 - self._rev_wet
        self._init_reverb_partitions()
        # Echo: feedback delay line carried across blocks.
        self.echo = max(0.0, min(1.0, float(echo)))
        self._echo_delay = max(1, int(320.0 * self.sample_rate / 1000.0))
        self._echo_buf = [0.0] * self._echo_delay
        self._echo_idx = 0
        self._echo_fb = 0.65 * self.echo

    # ---- reverb: partitioned overlap-add convolution ----

    def _init_reverb_partitions(self):
        ir = self._reverb._build_ir(self.sample_rate)
        p = self.block_size
        n = 2 * p
        self._rev_n = n
        self._rev_parts = []
        for start in range(0, len(ir), p):
            seg = ir[start:start + p]
            if len(seg) < p:
                seg = seg + [0.0] * (p - len(seg))
            self._rev_parts.append(
                _np.fft.rfft(_np.asarray(seg, dtype=_np.float64), n))
        self._rev_pend = _np.zeros(len(self._rev_parts) * p, dtype=_np.float64)

    def _reverb_block(self, x):
        p = self.block_size
        n = self._rev_n
        X = _np.fft.rfft(_np.asarray(x, dtype=_np.float64), n)
        k = len(self._rev_parts)
        contrib = _np.zeros((k + 1) * p, dtype=_np.float64)
        for i, H in enumerate(self._rev_parts):
            y = _np.fft.irfft(X * H, n)
            contrib[i * p:(i + 2) * p] += y
        wet = self._rev_pend[:p] + contrib[:p]
        self._rev_pend = (
            _np.concatenate([self._rev_pend[p:], _np.zeros(p)]) + contrib[p:])
        return [s * self._rev_dry + w * self._rev_wet
                for s, w in zip(x, wet.tolist())]

    # ---- warmth: saturated low-end with carried lowpass state ----

    def _warmth_block(self, x):
        a = self.warmth
        if a <= 0.0:
            return list(x)
        alpha = self._lp_alpha
        y = self._lp_y
        low = [0.0] * len(x)
        for i, s in enumerate(x):
            y += alpha * (s - y)
            low[i] = y
        self._lp_y = y
        drive = 1.0 + 2.5 * a
        dry_gain = 1.0 - 0.5 * a
        wet_gain = 0.5 * a
        return [d * dry_gain + math.tanh(drive * w) * wet_gain
                for d, w in zip(x, low)]

    # ---- echo: feedback delay line carried across blocks ----

    def _echo_block(self, x):
        a = self.echo
        if a <= 0.0:
            return list(x)
        buf = self._echo_buf
        idx = self._echo_idx
        delay = self._echo_delay
        fb = self._echo_fb
        out = [0.0] * len(x)
        for i, s in enumerate(x):
            d = buf[idx]
            out[i] = s + a * d
            buf[idx] = s + fb * d
            idx += 1
            if idx == delay:
                idx = 0
        self._echo_idx = idx
        return out

    # ---- public API ----

    def process_block(self, samples):
        """Process one block of mono float samples; returns the wet block."""
        x = self._warmth_block(samples)
        x = self._reverb_block(x)
        return self._echo_block(x)

    def process_pcm_block(self, pcm_bytes):
        """PCM16 bytes in (interleaved, self.channels); mono PCM16 bytes out.

        The input block must hold exactly block_size frames.
        """
        ch = self.channels
        n = len(pcm_bytes) // (2 * ch)
        vals = struct.unpack("<%dh" % (n * ch), pcm_bytes)
        if ch == 1:
            mono = [v / 32768.0 for v in vals]
        else:
            mono = [sum(vals[i * ch:(i + 1) * ch]) / ch / 32768.0
                    for i in range(n)]
        wet = self.process_block(mono)
        return struct.pack(
            "<%dh" % len(wet),
            *[int(round(max(-1.0, min(1.0, s)) * 32767)) for s in wet],
        )
