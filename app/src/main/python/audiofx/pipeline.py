"""Effect chaining plus WAV file I/O (stdlib only)."""

import struct
import wave


# WAV size guards (defense in depth for read_wav_mono): the decode builds a
# full mono float64 buffer in memory, so a malformed or huge header can OOM
# the process before any audio is touched. The duration cap mirrors the
# Android import cap; the byte cap bounds the actual buffer allocation.
MAX_WAV_DURATION_SEC = 2 * 3600  # 2 hours, mirrors the Android import cap
MAX_WAV_FLOAT64_BYTES = 2000000000  # 2 GB ceiling for the mono float64 buffer (8 bytes per frame)


def _check_wav_size(n_frames, sample_rate):
    """Raise ValueError if the WAV would exceed the duration or memory cap."""
    if sample_rate > 0 and n_frames / sample_rate > MAX_WAV_DURATION_SEC:
        raise ValueError("WAV exceeds 2 hour import cap")
    if n_frames * 8 > MAX_WAV_FLOAT64_BYTES:
        raise ValueError("WAV exceeds 2 GB decode buffer cap")


class EffectChain:
    """Applies effects in order: ``process`` feeds each effect's output into
    the next."""

    def __init__(self, effects=()):
        self.effects = list(effects)

    def add(self, effect):
        self.effects.append(effect)
        return self

    def process(self, samples, sample_rate):
        out = list(samples)
        for effect in self.effects:
            out = effect.process(out, sample_rate)
        return out


def _unpack_frames(raw, sampwidth, n_channels, n_frames):
    if sampwidth == 1:
        # 8-bit WAV is unsigned.
        vals = struct.unpack("<%dB" % (n_frames * n_channels), raw)
        return [(v - 128) / 128.0 for v in vals]
    if sampwidth == 2:
        vals = struct.unpack("<%dh" % (n_frames * n_channels), raw)
        return [v / 32768.0 for v in vals]
    raise ValueError("unsupported WAV bit depth: %d-bit" % (sampwidth * 8,))


def read_wav_mono(path):
    """Read a WAV file, mixing channels to mono. Returns (samples, sample_rate).

    Samples are a plain Python list of floats. Uses a NumPy fast path when
    available (vectorized unpack); otherwise falls back to stdlib.
    """
    with wave.open(path, "rb") as w:
        n_channels = w.getnchannels()
        sampwidth = w.getsampwidth()
        sample_rate = w.getframerate()
        n_frames = w.getnframes()
        _check_wav_size(n_frames, sample_rate)
        raw = w.readframes(n_frames)
    if n_frames == 0:
        raise ValueError("WAV file has no audio frames: %s" % path)
    try:
        import numpy as _np
    except ImportError:
        _np = None
    if _np is not None:
        if sampwidth == 2:
            vals = _np.frombuffer(raw, dtype="<i2").astype(_np.float64)
            vals /= 32768.0
        elif sampwidth == 1:
            vals = _np.frombuffer(raw, dtype=_np.uint8).astype(_np.float64)
            vals = (vals - 128.0) / 128.0
        else:
            raise ValueError("unsupported WAV bit depth: %d-bit"
                             % (sampwidth * 8,))
        if n_channels > 1:
            vals = vals.reshape(n_frames, n_channels).mean(axis=1)
        return vals.tolist(), sample_rate
    vals = _unpack_frames(raw, sampwidth, n_channels, n_frames)
    if n_channels == 1:
        return vals, sample_rate
    return (
        [
            sum(vals[i * n_channels : (i + 1) * n_channels]) / n_channels
            for i in range(n_frames)
        ],
        sample_rate,
    )


def _pack_int16(samples):
    """Clamp float samples to [-1, 1] and pack as little-endian int16."""
    if type(samples).__module__ == "numpy":
        # Vectorized pack: np.round matches Python round() (round-half-even).
        import numpy as _np

        q = _np.round(_np.clip(samples, -1.0, 1.0) * 32767).astype("<i2")
        return q.tobytes()
    return struct.pack(
        "<%dh" % len(samples),
        *[int(round(max(-1.0, min(1.0, s)) * 32767)) for s in samples],
    )


def write_wav(path, samples, sample_rate):
    """Write mono float samples as 16-bit PCM WAV."""
    frames = _pack_int16(samples)
    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(int(sample_rate))
        w.writeframes(frames)


def waveform_peaks(samples, n_buckets=240):
    """Peak amplitude per bucket for waveform display, normalized to 0..1.

    Each bucket covers an equal slice of the samples; within a bucket every
    k-th sample is examined (k chosen so at most ~512 samples are scanned
    per bucket), which keeps this fast on long recordings.
    """
    n = len(samples)
    if n == 0 or n_buckets <= 0:
        return [0.0] * max(n_buckets, 0)
    peaks = []
    for b in range(n_buckets):
        start = (b * n) // n_buckets
        end = ((b + 1) * n) // n_buckets
        step = max(1, (end - start) // 512)
        peak = 0.0
        for i in range(start, end, step):
            a = abs(samples[i])
            if a > peak:
                peak = a
        peaks.append(peak)
    top = max(peaks) or 1.0
    return [p / top for p in peaks]


def splice_with_crossfade(original, replacement, start, end, sample_rate,
                          fade_sec=0.01):
    """Splice ``replacement`` (which must cover ``[start, end)``) into a copy
    of ``original``, with a short linear crossfade at both splice points to
    avoid clicks. Returns the new sample list."""
    n = len(original)
    out = list(original)
    out[start:end] = replacement
    fade_n = int(fade_sec * sample_rate)
    fade_n = min(fade_n, (end - start) // 2, start, n - end)
    for i in range(fade_n):
        t = (i + 1) / (fade_n + 1)
        out[start + i] = original[start + i] * (1 - t) + replacement[i] * t
        j = end - fade_n + i
        out[j] = replacement[j - start] * (1 - t) + original[j] * t
    return out


def encode_mp3(samples, sample_rate, bitrate_kbps=192, quality=2):
    """Encode mono float samples as MP3 (CBR) via the ``lameenc`` package.

    Returns the MP3 file bytes. Raises RuntimeError if lameenc is missing.
    Samples are fed to the encoder in chunks so long recordings do not need
    a second full-size buffer.
    """
    try:
        import lameenc
    except ImportError:
        raise RuntimeError("lameenc is not installed (pip install lameenc)")
    enc = lameenc.Encoder()
    enc.set_bit_rate(int(bitrate_kbps))
    enc.set_in_sample_rate(int(sample_rate))
    enc.set_channels(1)
    enc.set_quality(int(quality))
    out = []
    chunk = 1 << 20  # ~1M samples per encoder call
    for i in range(0, len(samples), chunk):
        out.append(enc.encode(_pack_int16(samples[i : i + chunk])))
    out.append(enc.flush())
    return b"".join(out)
