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
    print("ALL TONIC TESTS PASS")
