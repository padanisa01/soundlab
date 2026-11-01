"""DB v4 -> v5 migration semantics (OO-7, tonic + notation persistence).

The Java side (LibraryDb.onUpgrade) is exercised on the phone; this
test pins the SQL semantics the migration relies on, using sqlite3
directly:

- The v4 tracks DDL below is reconstructed from LibraryDb.onCreate
  as it stood at DB_VERSION 4: the current onCreate tracks DDL
  minus the two v5 columns (tonic_json, notation_json). onCreate
  builds the latest schema directly (muted/volume are inline there
  even though they arrived via the v3/v4 ADD COLUMN migrations),
  so "current DDL minus the new columns" is the faithful v4 shape.
- The two ALTER statements are copied character-for-character from
  LibraryDb.onUpgrade's oldVersion < 5 block (the Java string
  concatenations "ALTER TABLE tracks ADD COLUMN" + " tonic_json
  TEXT" / " notation_json TEXT" joined).
"""

import sqlite3
import unittest

# v4 schema (see module docstring for how this was derived).
V4_ENTRIES_DDL = (
    "CREATE TABLE entries(\n"
    "  id TEXT PRIMARY KEY,\n"
    "  name TEXT NOT NULL,\n"
    "  created_at INTEGER NOT NULL,\n"
    "  updated_at INTEGER NOT NULL,\n"
    "  duration_s REAL,\n"
    "  sample_rate INTEGER,\n"
    "  channels INTEGER,\n"
    "  source TEXT,\n"
    "  capture_label TEXT,\n"
    "  dsp_version TEXT,\n"
    "  pristine_path TEXT NOT NULL\n"
    ")"
)

V4_TRACKS_DDL = (
    "CREATE TABLE tracks(\n"
    "  id TEXT PRIMARY KEY,\n"
    "  entry_id TEXT NOT NULL REFERENCES entries(id) ON DELETE CASCADE,\n"
    "  name TEXT NOT NULL,\n"
    "  wav_path TEXT NOT NULL,\n"
    "  locked INTEGER NOT NULL DEFAULT 0,\n"
    "  muted INTEGER NOT NULL DEFAULT 0,\n"
    "  volume REAL NOT NULL DEFAULT 1.0,\n"
    "  position INTEGER NOT NULL,\n"
    "  created_at INTEGER NOT NULL,\n"
    "  updated_at INTEGER NOT NULL\n"
    ")"
)

# Exactly the statements LibraryDb.onUpgrade executes for v5.
V5_ALTERS = [
    "ALTER TABLE tracks ADD COLUMN tonic_json TEXT",
    "ALTER TABLE tracks ADD COLUMN notation_json TEXT",
]

TONIC_JSON = (
    '{"sa_hz": 196.0, "note": "G3", "cents": 4, '
    '"score": 0.91, "runner_up_rel": 0.42}'
)
NOTATION_JSON = '[{"swara": "Sa", "start": 0.0, "end": 0.5}]'


class TestDbMigrationV4ToV5(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(":memory:")
        self.db.execute(V4_ENTRIES_DDL)
        self.db.execute(V4_TRACKS_DDL)
        self.db.execute(
            "CREATE INDEX idx_tracks_entry ON tracks(entry_id, position)")
        self.db.execute(
            "INSERT INTO entries (id, name, created_at, updated_at,"
            " duration_s, sample_rate, channels, source, capture_label,"
            " dsp_version, pristine_path) VALUES"
            " ('entry-1', 'Proj', 1000, 2000, 12.5, 44100, 2,"
            " 'record', NULL, '2', '/files/takes/entry-1.wav')")
        # Two fixture tracks exercising every v4 column, incl.
        # non-default locked/muted/volume on the second row.
        self.db.execute(
            "INSERT INTO tracks (id, entry_id, name, wav_path, locked,"
            " muted, volume, position, created_at, updated_at) VALUES"
            " ('track-1', 'entry-1', 'Track 1', '/files/tracks/t1.wav',"
            " 0, 0, 1.0, 1, 1000, 2000)")
        self.db.execute(
            "INSERT INTO tracks (id, entry_id, name, wav_path, locked,"
            " muted, volume, position, created_at, updated_at) VALUES"
            " ('track-2', 'entry-1', 'Vocal', '/files/tracks/t2.wav',"
            " 1, 1, 0.75, 2, 1100, 2100)")
        self.db.commit()

    def tearDown(self):
        self.db.close()

    def _columns(self):
        return [row[1] for row in
                self.db.execute("PRAGMA table_info(tracks)")]

    def _migrate(self):
        for stmt in V5_ALTERS:
            self.db.execute(stmt)
        self.db.commit()

    def test_columns_added(self):
        self.assertNotIn("tonic_json", self._columns())
        self.assertNotIn("notation_json", self._columns())
        self._migrate()
        cols = self._columns()
        self.assertIn("tonic_json", cols)
        self.assertIn("notation_json", cols)

    def test_existing_rows_intact_and_new_columns_null(self):
        before = self.db.execute(
            "SELECT id, entry_id, name, wav_path, locked, muted,"
            " volume, position, created_at, updated_at FROM tracks"
            " ORDER BY position").fetchall()
        self._migrate()
        after = self.db.execute(
            "SELECT id, entry_id, name, wav_path, locked, muted,"
            " volume, position, created_at, updated_at, tonic_json,"
            " notation_json FROM tracks ORDER BY position").fetchall()
        self.assertEqual(len(after), 2)
        for old_row, new_row in zip(before, after):
            self.assertEqual(tuple(old_row), tuple(new_row[:10]))
            self.assertIsNone(new_row[10])  # tonic_json
            self.assertIsNone(new_row[11])  # notation_json

    def test_set_and_round_trip(self):
        self._migrate()
        # The by-id column update setTrackTonic performs.
        self.db.execute(
            "UPDATE tracks SET tonic_json=? WHERE id=?",
            (TONIC_JSON, "track-1"))
        self.db.execute(
            "UPDATE tracks SET notation_json=? WHERE id=?",
            (NOTATION_JSON, "track-1"))
        self.db.commit()
        row = self.db.execute(
            "SELECT tonic_json, notation_json FROM tracks"
            " WHERE id='track-1'").fetchone()
        self.assertEqual(row[0], TONIC_JSON)
        self.assertEqual(row[1], NOTATION_JSON)
        # The other track is untouched.
        row2 = self.db.execute(
            "SELECT tonic_json, notation_json FROM tracks"
            " WHERE id='track-2'").fetchone()
        self.assertIsNone(row2[0])
        self.assertIsNone(row2[1])


if __name__ == "__main__":
    unittest.main()
