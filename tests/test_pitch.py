"""Tests for duration-preserving pitch shift (audiofx.pitch)."""
import os
import sys

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


def test_octave_up():
    y = pitch.pitch_shift(_sine(220.0), SR, 12.0)
    assert len(y) == int(1.5 * SR)
    assert abs(_peak_freq(y) - 440.0) < 6.0, _peak_freq(y)
    print("octave up ok:", _peak_freq(y))


def test_octave_down():
    y = pitch.pitch_shift(_sine(220.0), SR, -12.0)
    assert abs(_peak_freq(y) - 110.0) < 4.0, _peak_freq(y)
    print("octave down ok:", _peak_freq(y))


def test_fifth_up():
    y = pitch.pitch_shift(_sine(220.0), SR, 7.0)
    want = 220.0 * 2 ** (7.0 / 12.0)
    assert abs(_peak_freq(y) - want) < 6.0, (_peak_freq(y), want)
    print("fifth up ok:", _peak_freq(y), "want", want)


def test_zero_shift_identical():
    x = _sine(330.0)
    y = pitch.pitch_shift(x, SR, 0.0)
    assert np.array_equal(np.asarray(y), np.asarray(x))
    print("zero shift ok")


def test_range_guard():
    try:
        pitch.pitch_shift(_sine(220.0), SR, 13.0)
    except ValueError:
        print("range guard ok")
        return
    raise AssertionError("expected ValueError beyond +/-12 semitones")


if __name__ == "__main__":
    test_octave_up()
    test_octave_down()
    test_fifth_up()
    test_zero_shift_identical()
    test_range_guard()
    print("ALL PITCH TESTS PASS")
