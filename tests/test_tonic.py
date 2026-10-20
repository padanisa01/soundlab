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


def test_export_shivkumar_layout():
    """Unit grid, sustains, rests, grouping bars, octave dots."""
    info = {
        "note": "G3", "cents": 4,
        "swaras": [{"index": 7, "name": "Pa"}, {"index": 0, "name": "Sa"},
                   {"index": 4, "name": "G"}],
        "events": [
            {"start": 0.0, "dur": 0.3, "sym": "S"},
            {"start": 0.3, "dur": 0.6, "sym": "P"},
            {"start": 0.9, "dur": 0.3, "sym": "-"},
            {"start": 1.2, "dur": 0.3, "sym": "S'"},
            {"start": 1.5, "dur": 0.3, "sym": "P,"},
            {"start": 1.8, "dur": 0.3, "sym": "G"},
            {"start": 2.1, "dur": 0.3, "sym": "S"},
        ],
    }
    doc = tonic.format_shivkumar(info, "Test Song")
    lines = doc.split("\n")
    assert lines[0] == "Test Song"
    assert "Sa (tonic): G3 +4c" in lines[1]
    assert "Swaras present: S G P" in doc  # ascending by index
    body = [ln for ln in lines if ln.endswith("||")]
    assert len(body) == 1, body
    # unit = median note dur 0.3: S(1) P(2: P ,) ;(1) | Sdot(1) Pdot(1) | G S
    assert body[0].startswith("S P , ; |"), body[0]
    assert "S\u0307" in body[0]      # dot above = upper octave
    assert "P\u0323" in body[0]      # dot below = lower octave
    assert body[0].endswith("G S ||"), body[0]


def test_export_shivkumar_full_lines_and_runs():
    """16 one-unit notes make two full avartanam lines; a sub-unit
    note runs together with the previous note's slot."""
    evs = []
    t = 0.0
    for i in range(16):
        evs.append({"start": round(t, 2), "dur": 0.25, "sym": "S"})
        t += 0.25
    evs.append({"start": 4.0, "dur": 0.05, "sym": "R"})  # sub-unit
    evs.append({"start": 4.05, "dur": 0.45, "sym": "G"})
    info = {"note": "C4", "cents": 0, "swaras": [], "events": evs}
    doc = tonic.format_shivkumar(info, "Runs")
    body = [ln for ln in doc.split("\n") if ln.endswith("||")]
    assert len(body) == 3, body
    assert body[0] == "S S S S | S S | S S ||", body[0]
    assert body[1] == "S S S S | S S | S S ||", body[1]
    # R and G share unit 16 as a run ("RG"); G sustains one more unit.
    assert body[2] == "RG , ||", body[2]


def test_export_section():
    """The PDF section API: meta line + body lines."""
    import json
    sys.path.insert(0, "app/src/main/python")
    from audiofx import android_bridge as ab_local
    info = {
        "note": "G3", "cents": 4,
        "swaras": [{"index": 0, "name": "Sa"}, {"index": 7, "name": "Pa"}],
        "events": [{"start": 0.0, "dur": 0.3, "sym": "S"},
                   {"start": 0.3, "dur": 0.3, "sym": "P"}],
    }
    sec = json.loads(ab_local.notation_section(json.dumps(info)))
    assert sec["meta"].startswith("Sa (tonic): G3 +4c"), sec["meta"]
    assert "Swaras present: S P" in sec["meta"], sec["meta"]
    assert "One unit = 0.30 s" in sec["meta"], sec["meta"]
    assert sec["body"] == ["S P ||"], sec["body"]


if __name__ == "__main__":
    test_detect_tonic_finds_drone()
    test_detect_tonic_silence()
    test_notate_song_events()
    test_missing_fundamental_sa_wins_over_pa()
    test_true_sa_with_strong_pa_not_flipped()
    test_lower_pa_winner_flips_up_to_sa()
    test_export_shivkumar_layout()
    test_export_shivkumar_full_lines_and_runs()
    test_export_section()
    print("ALL TONIC TESTS PASS")
