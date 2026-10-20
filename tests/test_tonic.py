"""Tests for tonic detection and swara notation (audiofx.tonic)."""
import math
import sys
import os

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..",
                                "app", "src", "main", "python"))
import numpy as np

from audiofx import tonic

SR = 22050
SA = 220.0


def _note(freq, dur, amp=0.55):
    t = np.arange(int(dur * SR)) / SR
    return amp * (np.sin(2 * np.pi * freq * t)
                  + 0.5 * np.sin(2 * np.pi * 2 * freq * t)
                  + 0.25 * np.sin(2 * np.pi * 3 * freq * t))


def _song():
    """Tanpura-ish drone at Sa=220 plus an ascending melody."""
    semis = [0, 2, 4, 5, 7, 9, 11, 12]     # S R G m P D N S'
    melody = np.concatenate([_note(SA * 2 ** (s / 12), 0.45)
                             for s in semis])
    t = np.arange(len(melody)) / SR
    drone = 0.16 * (np.sin(2 * np.pi * SA * t)
                    + 0.6 * np.sin(2 * np.pi * 2 * SA * t)
                    + 0.5 * np.sin(2 * np.pi * 3 * SA * t)
                    + 0.4 * np.sin(2 * np.pi * 4 * SA * t))
    return melody + drone


def test_detect_tonic_finds_drone():
    info = tonic.detect_tonic(_song(), SR)
    assert info is not None
    assert abs(info["sa_hz"] - SA) < 3.0, info
    assert info["note"].startswith("A")  # 220 Hz = A3
    print("detect tonic ok:", info)


def test_detect_tonic_silence():
    assert tonic.detect_tonic(np.zeros(SR * 2), SR) is None
    assert tonic.notate_song(np.zeros(SR * 2), SR) is None
    print("silence ok")


def test_notate_song_events():
    info = tonic.notate_song(_song(), SR)
    assert info is not None
    names = {s["name"] for s in info["swaras"]}
    assert {"R", "G", "m", "Pa", "D", "N"} <= names, names
    syms = [ev["sym"] for ev in info["events"]]
    dedup = [s for i, s in enumerate(syms) if i == 0 or s != syms[i - 1]]
    assert "R" in dedup and "N" in dedup and len(info["events"]) >= 8
    assert "Sa =" in info["text"]
    print("notation ok: %d events, swaras %s"
          % (len(info["events"]), sorted(names)))


if __name__ == "__main__":
    test_detect_tonic_finds_drone()
    test_detect_tonic_silence()
    test_notate_song_events()
    test_missing_fundamental_sa_wins_over_pa()
    test_true_sa_with_strong_pa_not_flipped()
    print("ALL TONIC TESTS PASS")


def _render_parts(parts, sr=SR, dur=3.0):
    """Sustained sum of (freq, amp) sine partials."""
    n = int(sr * dur)
    t = np.arange(n) / sr
    x = np.zeros(n)
    for f, a in parts:
        x += a * np.sin(2 * np.pi * f * t)
    return x


def test_missing_fundamental_sa_wins_over_pa():
    """Pa trap: Sa (G3) has NO fundamental, only upper partials,
    while its Pa (D4) has a full strong series. The fifth-down
    preference must still return Sa."""
    sa = 196.0
    parts = [(sa * k, a) for k, a in
             ((2, 0.50), (3, 0.40), (4, 0.30), (5, 0.22), (6, 0.16))]
    pa = sa * 1.5
    parts += [(pa * k, a) for k, a in
              ((1, 0.60), (2, 0.35), (3, 0.20), (4, 0.12))]
    res = tonic.detect_tonic(_render_parts(parts), SR)
    assert res is not None
    assert abs(res["sa_hz"] - sa) < sa * 0.015, res


def test_true_sa_with_strong_pa_not_flipped():
    """A true Sa WITH its fundamental, plus a strong Pa partial,
    must stay Sa (the Durga configuration)."""
    sa = 220.0
    parts = [(sa * k, a) for k, a in
             ((1, 0.55), (2, 0.45), (3, 0.35), (4, 0.25), (5, 0.18))]
    pa = sa * 1.5
    parts += [(pa, 0.40), (pa * 2, 0.20)]
    res = tonic.detect_tonic(_render_parts(parts), SR)
    assert res is not None
    assert abs(res["sa_hz"] - sa) < sa * 0.015, res
