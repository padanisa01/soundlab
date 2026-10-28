"""Lookahead peak limiter (mono).

Reference implementation of the live recording limiter. The algorithm is
identical to the Java capture-path version (`LiveLimiter.java`): a delay line
holds the audio back by `lookahead_ms` while a peak envelope follower measures
the *undelayed* input, so gain reduction is fully engaged before a peak
arrives. Zero overshoot above the ceiling, by construction.

Design notes
------------
* Everything is mono float samples in [-1.0, 1.0]. `process` never mutates the
  input; it returns a new list.
* Output length equals input length. The output is delayed by the lookahead
  (first `lookahead_samples` outputs flush the zero-filled delay line), exactly
  like the live version -- tests must account for the shift.
* Pure Python, no NumPy: the per-sample loop is the point (it mirrors the
  Java), and offline use here is only for tests and parity checks.

True-peak mode (opt-in, `true_peak=True`, DSP-3)
------------------------------------------------
Sample-peak detection is blind to peaks that occur *between* samples: a
sine at fs/4 with sample peak 0.89 reconstructs to ~1.0 between samples.
In true-peak mode the detector first interpolates the input 4x with a
32-tap polyphase windowed-sinc FIR (`_tp_fir_coeffs`), takes the peak
over the oversampled stream, and feeds THAT into the identical envelope
follower (same attack/hold/release constants). Gain is still applied at
base rate to the delayed samples, and the 5 ms delay is unchanged. The
interpolator needs samples up to 4 ahead of the position it measures,
so the detected stream feeds the follower `_TP_DETECT_LAG` samples late
-- still far inside the lookahead window, so the gain is fully settled
before any detected peak exits the delay line. Default mode is
untouched and bit-identical to the pre-true-peak limiter.
"""

import math
from collections import deque


TRUE_PEAK_OVERSAMPLE = 4
_TP_FIR_TAPS = 32
# The interpolator for position n reads samples n-3 .. n+4, so its peak
# stream can only be fed to the envelope follower 4 samples late.
_TP_DETECT_LAG = 4


def _tp_fir_coeffs():
    """32-tap polyphase windowed-sinc interpolation FIR coefficients.

    Tap q is the sinc kernel sampled at a = (q - 16) / 4 (quarter-sample
    steps over [-4, +3.75] input samples), Hamming-windowed over the tap
    index. The four polyphase branches (taps q with q % 4 == phase) are
    each normalized to sum to exactly 1.0, so every fractional phase
    reproduces DC bit-exactly. The SAME numeric values are pasted as
    `TP_FIR_COEFFS` in LiveLimiter.java; a unit test diffs the two.
    """
    coeffs = []
    for q in range(_TP_FIR_TAPS):
        a = (q - _TP_FIR_TAPS // 2) / float(TRUE_PEAK_OVERSAMPLE)
        sinc = 1.0 if a == 0.0 else math.sin(math.pi * a) / (math.pi * a)
        window = 0.54 - 0.46 * math.cos(
            2.0 * math.pi * q / (_TP_FIR_TAPS - 1)
        )
        coeffs.append(sinc * window)
    for phase in range(TRUE_PEAK_OVERSAMPLE):
        branch = range(phase, _TP_FIR_TAPS, TRUE_PEAK_OVERSAMPLE)
        total = sum(coeffs[q] for q in branch)
        for q in branch:
            coeffs[q] /= total
    return coeffs


def _tp_detected_peaks(samples, coeffs):
    """Per-sample true-peak estimate: max |value| over the 4x grid.

    detected[n] covers the base sample n and the interpolated positions
    n + 1/4, n + 1/2, n + 3/4. Fractional positions whose 8-tap support
    (n-3 .. n+4) falls outside the buffer are skipped at the edges; the
    base sample itself always counts.
    """
    n = len(samples)
    detected = [0.0] * n
    for base in range(n):
        best = abs(float(samples[base]))
        for phase in range(1, TRUE_PEAK_OVERSAMPLE):
            frac = phase / float(TRUE_PEAK_OVERSAMPLE)
            y = 0.0
            complete = True
            for j in range(-3, 5):
                idx = base + j
                if idx < 0 or idx >= n:
                    complete = False
                    break
                q = int(round((frac - j + 4.0) * TRUE_PEAK_OVERSAMPLE))
                y += float(samples[idx]) * coeffs[q]
            if complete and abs(y) > best:
                best = abs(y)
        detected[base] = best
    return detected


class Limiter:
    """Lookahead peak limiter.

    Args:
        ceiling_db: maximum output level in dBFS. Peaks never exceed this.
        lookahead_ms: how far ahead the envelope follower sees (the output is
            delayed by this much).
        attack_ms: gain-drop smoothing time constant. Must be well under
            lookahead_ms so the gain fully settles before a detected peak
            exits the delay line -- that is what guarantees zero overshoot.
        release_ms: peak-envelope decay / gain-recovery time constant.
        true_peak: when True, the envelope follower runs on the 4x
            oversampled (inter-sample) peak stream instead of the raw
            sample peaks. Default False preserves the original
            sample-peak behavior and outputs bit-for-bit.
    """

    def __init__(
        self,
        ceiling_db=-1.0,
        lookahead_ms=5.0,
        attack_ms=0.1,
        release_ms=50.0,
        true_peak=False,
    ):
        self.ceiling_db = float(ceiling_db)
        self.lookahead_ms = float(lookahead_ms)
        self.attack_ms = float(attack_ms)
        self.release_ms = float(release_ms)
        self.true_peak = bool(true_peak)

    @property
    def ceiling_linear(self):
        return 10.0 ** (self.ceiling_db / 20.0)

    def lookahead_samples(self, sample_rate):
        return max(1, int(self.lookahead_ms * sample_rate / 1000.0))

    def process(self, samples, sample_rate):
        """Apply limiting. Returns a new list, same length as input."""
        sr = float(sample_rate)
        threshold = self.ceiling_linear
        lookahead = self.lookahead_samples(sample_rate)
        attack_coeff = math.exp(-1.0 / (self.attack_ms * sr / 1000.0))
        release_coeff = math.exp(-1.0 / (self.release_ms * sr / 1000.0))

        # NB: no maxlen -- append()+popleft() keeps length exactly constant,
        # giving a true lookahead-sample delay. With maxlen set, the append
        # would discard one extra sample and the delay would be off by one.
        delay = deque([0.0] * lookahead)
        peak = 0.0
        hold = 0
        # A detected peak must be held until its gain has been applied: the
        # peak seen at iteration i exits the delay line at iteration i + L,
        # so the hold spans L + 1 iterations. Without this, the envelope
        # would decay inside the lookahead window and hot transients would
        # overshoot the ceiling.
        hold_samples = lookahead + 1
        gain = 1.0
        out = [0.0] * len(samples)

        # True-peak mode: precompute the oversampled peak stream. It
        # feeds the follower _TP_DETECT_LAG samples late (its support
        # reaches 4 samples ahead); the follower otherwise runs exactly
        # as in sample-peak mode, on the detected peak for each sample.
        detected = (
            _tp_detected_peaks(samples, _tp_fir_coeffs())
            if self.true_peak
            else None
        )

        for i, x in enumerate(samples):
            if detected is None:
                ax = abs(float(x))
            elif i >= _TP_DETECT_LAG:
                ax = detected[i - _TP_DETECT_LAG]
            else:
                ax = 0.0
            # Peak envelope on the UNDELAYED input (lookahead): instant attack,
            # hold, then exponential release decay. The hold refreshes on
            # >= (not just >): a steady periodic signal re-confirms the same
            # peak every cycle, and without the refresh the hold would expire,
            # the envelope would decay, and the delayed peak would overshoot.
            if ax >= peak:
                peak = ax
                hold = hold_samples
            elif hold > 0:
                hold -= 1
            else:
                peak *= release_coeff
            # Gain computer.
            if peak < 1e-12:
                target = 1.0
            else:
                target = threshold / peak
                if target > 1.0:
                    target = 1.0
            # Smooth the gain itself: fast when dropping, slow when recovering.
            coeff = attack_coeff if target < gain else release_coeff
            gain += coeff * (target - gain)
            # Apply to the DELAYED sample.
            delay.append(float(x))
            out[i] = delay.popleft() * gain

        return out
