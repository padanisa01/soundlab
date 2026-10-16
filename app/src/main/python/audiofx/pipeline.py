"""Effect chaining plus WAV file I/O (stdlib only)."""

import struct
import wave


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
    """Read a WAV file, mixing channels to mono. Returns (samples, sample_rate)."""
    with wave.open(path, "rb") as w:
        n_channels = w.getnchannels()
        sampwidth = w.getsampwidth()
        sample_rate = w.getframerate()
        n_frames = w.getnframes()
        raw = w.readframes(n_frames)
    if n_frames == 0:
        raise ValueError("WAV file has no audio frames: %s" % path)
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


def write_wav(path, samples, sample_rate):
    """Write mono float samples as 16-bit PCM WAV."""
    frames = struct.pack(
        "<%dh" % len(samples),
        *[int(round(max(-1.0, min(1.0, s)) * 32767)) for s in samples],
    )
    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(int(sample_rate))
        w.writeframes(frames)
