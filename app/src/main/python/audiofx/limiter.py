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
"""

import math
from collections import deque


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
    """

    def __init__(
        self,
        ceiling_db=-1.0,
        lookahead_ms=5.0,
        attack_ms=0.1,
        release_ms=50.0,
    ):
        self.ceiling_db = float(ceiling_db)
        self.lookahead_ms = float(lookahead_ms)
        self.attack_ms = float(attack_ms)
        self.release_ms = float(release_ms)

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

        for i, x in enumerate(samples):
            ax = abs(float(x))
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
