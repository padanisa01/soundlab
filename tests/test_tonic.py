"""Tests for tonic detection and swara notation (audiofx.tonic)."""
import math
import sys
import os
import unittest

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


def test_lower_pa_winner_flips_up_to_sa():
    """Fourth-up trap (measured on pitch-shifted content): the
    lower Pa (F#3) has a real fundamental + strong 2nd partial and
    wins the raw vote, while the true Sa (B3) is missing its
    fundamental. The detector must land on B3."""
    sa = 246.94  # B3
    parts = [(sa * k, a) for k, a in
             ((2, 0.65), (3, 0.45), (4, 0.30), (5, 0.20))]
    pa_lo = sa * 0.75  # F#3, the lower Pa
    parts += [(pa_lo * k, a) for k, a in
              ((1, 0.50), (2, 0.35), (3, 0.15))]
    n = int(SR * 3)
    t = np.arange(n) / SR
    x = np.zeros(n)
    for f, a in parts:
        x += a * np.sin(2 * np.pi * f * t)
    res = tonic.detect_tonic(x, SR)
    assert res is not None
    # The class must be B (the F# winner must not survive). The
    # octave is genuinely underdetermined here: B2's partial stack
    # contains B3's, and B3 has no fundamental -- accept either.
    assert res["note"].startswith("B"), res
    assert any(abs(res["sa_hz"] - o) < o * 0.015
               for o in (sa, sa / 2.0)), res
    print("lower-pa flip ok:", res)



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


# ---------------------------------------------------------------------------
# Plateau-vs-transit segmenter (DSP-4, tasks N1/N2). TestCase style so
# tools/verify.py discovers them; fixtures are synthetic f0 tracks
# (no NN). segment_plateaus times are at MELODY_HOP_SEC, tonic = SA.
# ---------------------------------------------------------------------------

def _track_from_cents(cents, voiced=None):
    cents = np.asarray(cents, dtype=float)
    times = np.arange(len(cents)) * tonic.MELODY_HOP_SEC
    freqs = np.where(np.isfinite(cents),
                     SA * 2.0 ** (cents / 1200.0), np.nan)
    if voiced is None:
        voiced = np.isfinite(cents)
    return times, freqs, np.asarray(voiced, dtype=bool)


def _notes(events):
    return [ev for ev in events if ev["sym"] != "-"]


class TestSegmentPlateaus(unittest.TestCase):

    def test_steady_plateau_one_event(self):
        times, freqs, voiced = _track_from_cents(np.zeros(100))
        events = tonic.segment_plateaus(times, freqs, voiced, SA)
        self.assertEqual(len(events), 1, events)
        ev = events[0]
        self.assertEqual(ev["sym"], "S")
        self.assertAlmostEqual(ev["start"], 0.0, delta=0.02)
        self.assertAlmostEqual(ev["dur"], 1.0, delta=0.02)
        # A fixed note keeps the exact legacy event shape; the
        # _swara_stats oscillation flag ("fixed") is carried only
        # by a kampita note.
        self.assertNotIn("fixed", ev)

    def test_glide_between_plateaus_absorbed_two_events(self):
        # 0.50 s at Sa, a 260 ms glide to the adjacent swara (+100
        # cents), then 0.50 s there: the transit frames belong to
        # the plateaus they connect, never to a note of their own.
        # (Steady-to-steady the glide spans 0.49 s -> 0.75 s; the
        # 25 frames strictly between the plateau levels are the
        # linspace interior.)
        cents = np.concatenate([
            np.zeros(50),
            np.linspace(0.0, 100.0, 27)[1:-1],
            np.full(50, 100.0),
        ])
        times, freqs, voiced = _track_from_cents(cents)
        events = tonic.segment_plateaus(times, freqs, voiced, SA)
        notes = _notes(events)
        self.assertEqual([ev["sym"] for ev in notes], ["S", "r"],
                         events)
        self.assertEqual(len(events), 2, events)
        for ev in notes:
            self.assertGreater(ev["dur"], 0.5)
        self.assertAlmostEqual(
            sum(ev["dur"] for ev in notes), len(cents) * 0.01,
            delta=0.02)

    def test_kampita_oscillation_single_event_flagged(self):
        # +/-140 cents around Sa at 5 Hz for 1.2 s: one note, not a
        # stream of fragments, flagged the _swara_stats way -- a
        # wide oscillation centre is not "fixed".
        t = np.arange(120) * tonic.MELODY_HOP_SEC
        cents = 140.0 * np.sin(2 * np.pi * 5.0 * t)
        times, freqs, voiced = _track_from_cents(cents)
        events = tonic.segment_plateaus(times, freqs, voiced, SA)
        notes = _notes(events)
        self.assertEqual(len(notes), 1, events)
        self.assertEqual(notes[0]["sym"], "S")
        self.assertIn("fixed", notes[0])
        self.assertIs(notes[0]["fixed"], False)
        self.assertAlmostEqual(notes[0]["start"], 0.0, delta=0.02)
        self.assertAlmostEqual(notes[0]["dur"], 1.2, delta=0.02)

    def test_landing_note_in_descent(self):
        # Sa' held, a glide down that DWELLS 50 ms on Ni, then
        # glides on through ni to Dha held: the dwell is a landing
        # note (human notation writes it), while ni -- crossed at
        # the same 20 cents/frame glide slope, no dwell -- is not.
        cents = np.concatenate([
            np.full(50, 1200.0),
            np.array([1180.0, 1160.0, 1140.0, 1120.0]),
            np.full(5, 1100.0),
            np.array([1080.0, 1060.0, 1040.0, 1020.0, 1000.0,
                      980.0, 960.0, 940.0, 920.0]),
            np.full(50, 900.0),
        ])
        times, freqs, voiced = _track_from_cents(cents)
        events = tonic.segment_plateaus(times, freqs, voiced, SA)
        notes = _notes(events)
        self.assertEqual([ev["sym"] for ev in notes],
                         ["S'", "N", "D"], events)
        self.assertGreaterEqual(notes[1]["dur"], 0.05)

    def test_pure_glide_descent_no_landing(self):
        # The same Sa' -> Dha descent as one continuous glide
        # (20 cents/frame, no dwell anywhere): the slope gate
        # rejects every crossed centre -- endpoints only.
        cents = np.concatenate([
            np.full(50, 1200.0),
            np.arange(1180.0, 900.0, -20.0),
            np.full(50, 900.0),
        ])
        times, freqs, voiced = _track_from_cents(cents)
        events = tonic.segment_plateaus(times, freqs, voiced, SA)
        self.assertEqual([ev["sym"] for ev in _notes(events)],
                         ["S'", "D"], events)

    def test_short_blip_no_event(self):
        # 50 ms at R: below NOTATE_MIN_NOTE_SEC, so no event.
        self.assertLess(0.05, tonic.NOTATE_MIN_NOTE_SEC)
        times, freqs, voiced = _track_from_cents(np.full(5, 200.0))
        events = tonic.segment_plateaus(times, freqs, voiced, SA)
        self.assertEqual(events, [])

    def test_unvoiced_gaps_split_events(self):
        cents = np.concatenate([
            np.zeros(50), np.full(30, np.nan), np.zeros(50)])
        times, freqs, voiced = _track_from_cents(cents)
        events = tonic.segment_plateaus(times, freqs, voiced, SA)
        notes = _notes(events)
        self.assertEqual([ev["sym"] for ev in notes], ["S", "S"],
                         events)
        self.assertAlmostEqual(notes[1]["start"], 0.8, delta=0.02)
        # The 300 ms gap is a rest event between the two notes
        # (>= NOTATE_MIN_REST_SEC), in the legacy event shape.
        self.assertEqual([ev["sym"] for ev in events],
                         ["S", "-", "S"], events)


class TestNotateSongUsesSegmenter(unittest.TestCase):

    def test_classic_path_calls_segment_plateaus(self):
        calls = []
        orig = tonic.segment_plateaus

        def spy(times, freqs_hz, voiced, tonic_hz):
            calls.append((len(times), tonic_hz))
            return orig(times, freqs_hz, voiced, tonic_hz)

        tonic.segment_plateaus = spy
        try:
            info = tonic.notate_song(_song(), SR)
        finally:
            tonic.segment_plateaus = orig
        self.assertIsNotNone(info)
        self.assertEqual(len(calls), 1)
        self.assertGreater(len(info["events"]), 0)


if __name__ == "__main__":
    test_detect_tonic_finds_drone()
    test_detect_tonic_silence()
    test_notate_song_events()
    test_missing_fundamental_sa_wins_over_pa()
    test_true_sa_with_strong_pa_not_flipped()
    test_lower_pa_winner_flips_up_to_sa()
    print("ALL TONIC TESTS PASS")
