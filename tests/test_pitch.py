"""Tests for duration-preserving pitch shift (audiofx.pitch).

Includes the transient-preservation regression: a phase-vocoder
version smeared attacks (click concentration 0.65); WSOLA must
keep onsets sharp and on time.
"""
import os
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..",
                                "app", "src", "main", "python"))
import numpy as np

from audiofx import pitch

SR = 22050


def _sine(freq, dur=1.5, amp=0.5):
    t = np.arange(int(dur * SR)) / SR
    return amp * np.sin(2 * np.pi * freq * t)


def _peak_freq(x):
    spec = np.abs(np.fft.rfft(x * np.hanning(len(x))))
    freqs = np.fft.rfftfreq(len(x), 1.0 / SR)
    return float(freqs[int(np.argmax(spec))])


class TestPitchShift(unittest.TestCase):

    def test_octave_up(self):
        y = pitch.pitch_shift(_sine(220.0), SR, 12.0)
        self.assertEqual(len(y), int(1.5 * SR))
        self.assertAlmostEqual(_peak_freq(y), 440.0, delta=6.0)

    def test_octave_down(self):
        y = pitch.pitch_shift(_sine(220.0), SR, -12.0)
        self.assertAlmostEqual(_peak_freq(y), 110.0, delta=4.0)

    def test_fifth_up(self):
        y = pitch.pitch_shift(_sine(220.0), SR, 7.0)
        want = 220.0 * 2 ** (7.0 / 12.0)
        self.assertAlmostEqual(_peak_freq(y), want, delta=6.0)

    def test_zero_shift_identical(self):
        x = _sine(330.0)
        y = pitch.pitch_shift(x, SR, 0.0)
        self.assertTrue(np.array_equal(np.asarray(y), np.asarray(x)))

    def test_range_guard(self):
        with self.assertRaises(ValueError):
            pitch.pitch_shift(_sine(220.0), SR, 13.0)


class TestTransientPreservation(unittest.TestCase):
    """Clicks must stay sharp and at the same times after a shift."""

    def _clicks(self, sr=44100):
        n = sr * 4
        t = np.arange(n) / sr
        x = 0.05 * np.sin(2 * np.pi * 220 * t)
        for ct in (0.5, 1.5, 2.5, 3.5):
            s = int(ct * sr)
            env = np.hanning(int(0.03 * sr))
            seg = 0.8 * np.sin(2 * np.pi * 200
                               * np.arange(len(env)) / sr) * env
            x[s:s + len(env)] += seg
        return x, sr

    def test_clicks_stay_sharp_and_on_time(self):
        x, sr = self._clicks()
        y = np.asarray(pitch.pitch_shift(x, sr, 4.0))
        self.assertEqual(len(y), len(x))
        for ct in (0.5, 1.5, 2.5, 3.5):
            c = int(ct * sr)
            near = float((y[c - 882:c + 882] ** 2).sum())
            wide = float((y[c - 5292:c + 5292] ** 2).sum())
            self.assertGreater(near / wide, 0.80)
            # The envelope peak within +-60 ms sits at the click.
            seg = np.abs(y[c - 2646:c + 2646])
            peak_at = (int(np.argmax(seg)) - 2646) / sr
            self.assertLess(abs(peak_at), 0.03)


# ---------------------------------------------------------------------------
# Formant-preserving pitch shift (DSP-1): synthetic vowel fixtures.
#
# The vowel is a harmonic source (equal-amplitude harmonics of F0)
# multiplied in the spectrum by three resonators (Lorentzian
# resonances) at known formant frequencies, so the true formant
# peaks are known by construction. Formants are measured back with
# a harmonic-peak spectral-envelope helper: sample the spectrum at
# the harmonics of the measured f0, interpolate in the log domain,
# smooth, and pick the peak inside each formant's band (bands split
# at the geometric means of neighbouring formants).
# ---------------------------------------------------------------------------

VOWEL_F0 = 90.0
VOWEL_FORMANTS = (700.0, 1800.0, 2900.0)
VOWEL_BANDWIDTHS = (400.0, 450.0, 500.0)


def _make_vowel(dur=2.0):
    n = int(dur * SR)
    t = np.arange(n) / SR
    source = np.zeros(n)
    for k in range(1, int((SR / 2 - 200) // VOWEL_F0) + 1):
        source += np.sin(2 * np.pi * VOWEL_F0 * k * t)
    spec = np.fft.rfft(source)
    freqs = np.fft.rfftfreq(n, 1.0 / SR)
    envelope = np.zeros_like(freqs)
    for formant, bw in zip(VOWEL_FORMANTS, VOWEL_BANDWIDTHS):
        envelope += 1.0 / (1.0 + ((freqs - formant) / (bw / 2.0)) ** 2)
    y = np.fft.irfft(spec * envelope, n)
    return y * (0.5 / np.max(np.abs(y)))


def _cents(a, b):
    return 1200.0 * np.log2(a / b)


def _measure_f0(x):
    """Autocorrelation f0 (parabolic-refined) over the middle of x."""
    seg = np.asarray(x, dtype=np.float64)
    seg = seg[len(seg) // 4: len(seg) // 4 + SR // 2].copy()
    seg -= seg.mean()
    ac = np.correlate(seg, seg, "full")[len(seg) - 1:]
    lo, hi = int(SR / 400), int(SR / 50)
    # First strong local peak (not the global max, which may sit at
    # a multiple of the true period and halve the estimate).
    peak = lo + int(np.argmax(ac[lo:hi]))
    threshold = 0.93 * ac[peak]
    j = peak
    for lag in range(lo + 1, hi - 1):
        if ac[lag] >= threshold and ac[lag] >= ac[lag - 1] \
                and ac[lag] >= ac[lag + 1]:
            j = lag
            break
    d = 0.5 * (ac[j - 1] - ac[j + 1]) / (ac[j - 1] - 2 * ac[j] + ac[j + 1])
    return SR / (j + d)


def _measure_formants(x):
    """Formant peaks (Hz) of x via its harmonic-peak envelope."""
    f0 = _measure_f0(x)
    n = len(x)
    spec = np.abs(np.fft.rfft(x * np.hanning(n)))
    freqs = np.fft.rfftfreq(n, 1.0 / SR)
    ks = np.arange(1, int((SR / 2 - 300) / f0) + 1)
    hf = ks * f0
    hm = np.array([
        spec[(freqs >= h - 0.35 * f0) & (freqs <= h + 0.35 * f0)].max()
        for h in hf])
    grid = np.arange(100.0, 5000.0, 5.0)
    log_env = np.interp(grid, hf, np.log(np.maximum(hm, 1e-12)))
    sigma = 100.0
    rad = int(3 * sigma / 5)
    kern = np.exp(-0.5 * (np.arange(-rad, rad + 1) * 5.0 / sigma) ** 2)
    kern /= kern.sum()
    smooth = np.convolve(log_env, kern, mode="same")
    out = []
    for i, formant in enumerate(VOWEL_FORMANTS):
        lo = (np.sqrt(VOWEL_FORMANTS[i - 1] * formant) if i > 0
              else formant * 0.45)
        hi = (np.sqrt(formant * VOWEL_FORMANTS[i + 1])
              if i + 1 < len(VOWEL_FORMANTS) else formant * 1.45)
        band = (grid >= lo) & (grid <= hi)
        out.append(float(grid[band][int(np.argmax(smooth[band]))]))
    return out


class TestFormantPitchShift(unittest.TestCase):
    """pitch_shift_formant: f0 moves, formants stay (PRD F1 bounds:
    formant peaks within +/-50 cents of the original's, f0 within
    +/-10 cents of target)."""

    @classmethod
    def setUpClass(cls):
        cls.x = _make_vowel()
        cls.orig_formants = _measure_formants(cls.x)

    def _check_shift(self, semis):
        y = pitch.pitch_shift_formant(self.x, SR, semis)
        self.assertEqual(len(y), len(self.x))
        target_f0 = VOWEL_F0 * 2 ** (semis / 12.0)
        self.assertLessEqual(
            abs(_cents(_measure_f0(y), target_f0)), 10.0)
        for got, want in zip(_measure_formants(y), self.orig_formants):
            self.assertLessEqual(abs(_cents(got, want)), 50.0)

    def test_formants_preserved_up_7(self):
        self._check_shift(7.0)

    def test_formants_preserved_down_5(self):
        self._check_shift(-5.0)

    def test_wsola_control_fails_formant_bound(self):
        # Control: plain WSOLA moves the formants with the pitch, so
        # the same measurement MUST fail the +/-50 cent bound. If
        # this ever passes, the formant assertions above are vacuous.
        for semis in (7.0, -5.0):
            y = pitch.pitch_shift(self.x, SR, semis)
            for got, want in zip(_measure_formants(y),
                                 self.orig_formants):
                self.assertGreater(abs(_cents(got, want)), 50.0)

    def test_short_buffer_falls_back_to_wsola(self):
        x = _sine(220.0, dur=0.05)  # 1102 samples < STFT window
        self.assertTrue(np.array_equal(
            np.asarray(pitch.pitch_shift_formant(x, SR, 5.0)),
            np.asarray(pitch.pitch_shift(x, SR, 5.0))))

    def test_silent_falls_back_to_wsola(self):
        x = np.zeros(4096)
        y = pitch.pitch_shift_formant(x, SR, 5.0)
        self.assertTrue(np.array_equal(
            np.asarray(y), np.asarray(pitch.pitch_shift(x, SR, 5.0))))

    def test_zero_shift_identical(self):
        y = pitch.pitch_shift_formant(self.x, SR, 0.0)
        self.assertTrue(np.array_equal(np.asarray(y),
                                       np.asarray(self.x)))

    def test_range_guard(self):
        with self.assertRaises(ValueError):
            pitch.pitch_shift_formant(self.x, SR, 13.0)


if __name__ == "__main__":
    unittest.main(verbosity=2)
