package com.audiofx.app;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Plain SQLiteOpenHelper for the SoundLab "Library of recordings" feature.
 *
 * Multitrack model (DB v2): one library entry = one project. A project owns
 * N tracks (the {@code tracks} table); each track has its own immutable
 * pristine WAV at getFilesDir()/library/tracks/&lt;trackId&gt;.wav and its
 * own ordered, re-runnable op chain in {@code ops}, scoped by track_id. The
 * per-track cached render lives at
 * getFilesDir()/library/render/track_&lt;trackId&gt;.wav and the project mix
 * cache at getFilesDir()/library/render/mix_&lt;entryId&gt;.wav; both are
 * regenerable.
 *
 * Legacy single-take entries (created before tracks existed) keep their take
 * at getFilesDir()/library/takes/&lt;entryId&gt;.wav. Calling
 * ensureTracks(entryId, pristinePath) adopts it as "Track 1" without copying
 * the file and re-points the legacy entry-level ops at the new track.
 *
 * Op types in use: 'livefx' (display-only, never rendered), 'fx', 'normalize',
 * 'trim', 'join', 'fork' (entry-level display-only marker with NULL track_id,
 * never rendered). Callers that render via render_chain must filter out
 * 'livefx' and 'fork' ops.
 */
public class LibraryDb extends SQLiteOpenHelper {

    public static final String DSP_VERSION = "1";

    private static final String DB_NAME = "soundlab_library.db";
    private static final int DB_VERSION = 3;

    private final Context mAppContext;

    public LibraryDb(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
        mAppContext = context.getApplicationContext();
    }

    @Override
    public void onConfigure(SQLiteDatabase db) {
        db.setForeignKeyConstraintsEnabled(true);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE entries(\n"
                + "  id TEXT PRIMARY KEY,\n"
                + "  name TEXT NOT NULL,\n"
                + "  created_at INTEGER NOT NULL,\n"
                + "  updated_at INTEGER NOT NULL,\n"
                + "  duration_s REAL,\n"
                + "  sample_rate INTEGER,\n"
                + "  channels INTEGER,\n"
                + "  source TEXT,\n"
                + "  capture_label TEXT,\n"
                + "  dsp_version TEXT,\n"
                + "  pristine_path TEXT NOT NULL\n"
                + ")");
        db.execSQL("CREATE TABLE tracks(\n"
                + "  id TEXT PRIMARY KEY,\n"
                + "  entry_id TEXT NOT NULL REFERENCES entries(id) ON DELETE CASCADE,\n"
                + "  name TEXT NOT NULL,\n"
                + "  wav_path TEXT NOT NULL,\n"
                + "  locked INTEGER NOT NULL DEFAULT 0,\n"
                + "  muted INTEGER NOT NULL DEFAULT 0,\n"
                + "  position INTEGER NOT NULL,\n"
                + "  created_at INTEGER NOT NULL,\n"
                + "  updated_at INTEGER NOT NULL\n"
                + ")");
        db.execSQL("CREATE INDEX idx_tracks_entry ON tracks(entry_id, position)");
        db.execSQL("CREATE TABLE ops(\n"
                + "  id INTEGER PRIMARY KEY AUTOINCREMENT,\n"
                + "  entry_id TEXT NOT NULL REFERENCES entries(id) ON DELETE CASCADE,\n"
                + "  track_id TEXT REFERENCES tracks(id) ON DELETE CASCADE,\n"
                + "  seq INTEGER NOT NULL,\n"
                + "  op_type TEXT NOT NULL,\n"
                + "  params_json TEXT NOT NULL,\n"
                + "  label TEXT NOT NULL,\n"
                + "  created_at INTEGER NOT NULL\n"
                + ")");
        db.execSQL("CREATE INDEX idx_ops_entry ON ops(entry_id, seq)");
        db.execSQL("CREATE INDEX idx_ops_track ON ops(track_id)");
        db.execSQL("CREATE TABLE exports(\n"
                + "  id INTEGER PRIMARY KEY AUTOINCREMENT,\n"
                + "  entry_id TEXT NOT NULL REFERENCES entries(id) ON DELETE CASCADE,\n"
                + "  file_name TEXT NOT NULL,\n"
                + "  folder_label TEXT,\n"
                + "  op_count INTEGER,\n"
                + "  created_at INTEGER NOT NULL\n"
                + ")");
        db.execSQL("CREATE INDEX idx_entries_updated ON entries(updated_at DESC)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            // DB v2: multitrack. SQLite rejects ALTER TABLE ... ADD COLUMN
            // with a REFERENCES clause while foreign keys are enforced, so
            // the ops table is rebuilt with the track_id column and a real
            // FK instead. All rows are copied; no user data is lost.
            db.beginTransaction();
            try {
                db.execSQL("CREATE TABLE tracks(\n"
                        + "  id TEXT PRIMARY KEY,\n"
                        + "  entry_id TEXT NOT NULL REFERENCES entries(id) ON DELETE CASCADE,\n"
                        + "  name TEXT NOT NULL,\n"
                        + "  wav_path TEXT NOT NULL,\n"
                        + "  locked INTEGER NOT NULL DEFAULT 0,\n"
                        + "  position INTEGER NOT NULL,\n"
                        + "  created_at INTEGER NOT NULL,\n"
                        + "  updated_at INTEGER NOT NULL\n"
                        + ")");
                db.execSQL("CREATE INDEX idx_tracks_entry ON tracks(entry_id, position)");
                db.execSQL("CREATE TABLE ops_new(\n"
                        + "  id INTEGER PRIMARY KEY AUTOINCREMENT,\n"
                        + "  entry_id TEXT NOT NULL REFERENCES entries(id) ON DELETE CASCADE,\n"
                        + "  track_id TEXT REFERENCES tracks(id) ON DELETE CASCADE,\n"
                        + "  seq INTEGER NOT NULL,\n"
                        + "  op_type TEXT NOT NULL,\n"
                        + "  params_json TEXT NOT NULL,\n"
                        + "  label TEXT NOT NULL,\n"
                        + "  created_at INTEGER NOT NULL\n"
                        + ")");
                db.execSQL("INSERT INTO ops_new (id, entry_id, seq, op_type,"
                        + " params_json, label, created_at)"
                        + " SELECT id, entry_id, seq, op_type, params_json,"
                        + " label, created_at FROM ops");
                db.execSQL("DROP TABLE ops");
                db.execSQL("ALTER TABLE ops_new RENAME TO ops");
                // Keep AUTOINCREMENT continuing past the copied max row id.
                db.execSQL("INSERT INTO sqlite_sequence (name, seq)"
                        + " SELECT 'ops', MAX(id) FROM ops"
                        + " HAVING MAX(id) IS NOT NULL");
                db.execSQL("CREATE INDEX idx_ops_entry ON ops(entry_id, seq)");
                db.execSQL("CREATE INDEX idx_ops_track ON ops(track_id)");
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        }
        if (oldVersion < 3) {
            // DB v3: per-track mute flag. NOTE: unlike the v1->v2 migration
            // (which rebuilt the child table ops), the tracks table cannot
            // be rebuilt by drop/rename here: ops.track_id references
            // tracks(id) with ON DELETE CASCADE and foreign keys are
            // enforced (onConfigure), so DROP TABLE tracks would implicitly
            // delete every row in ops, wiping all op chains. PRAGMA
            // foreign_keys cannot be toggled inside onUpgrade either,
            // because SQLiteOpenHelper already runs it in a transaction.
            // ADD COLUMN is safe with FK enforcement on: it cannot name a
            // REFERENCES clause, existing rows get the default 0, and the
            // index on tracks is untouched.
            db.beginTransaction();
            try {
                db.execSQL("ALTER TABLE tracks ADD COLUMN"
                        + " muted INTEGER NOT NULL DEFAULT 0");
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        }
    }

    // ------------------------------------------------------------------
    // File layout helpers
    // ------------------------------------------------------------------

    private File libraryDir() {
        return new File(mAppContext.getFilesDir(), "library");
    }

    private File mkdirsParent(File f) {
        File parent = f.getParentFile();
        if (parent != null && !parent.isDirectory()) {
            parent.mkdirs();
        }
        return f;
    }

    /** getFilesDir()/library/takes/&lt;id&gt;.wav (dirs created on demand). */
    public File takeFile(String entryId) {
        return mkdirsParent(new File(libraryDir(), "takes/" + entryId + ".wav"));
    }

    /** getFilesDir()/library/render/&lt;id&gt;.wav (dirs created on demand). */
    public File renderFile(String entryId) {
        return mkdirsParent(new File(libraryDir(), "render/" + entryId + ".wav"));
    }

    /** getFilesDir()/library/tracks/&lt;id&gt;.wav (dirs created on demand). */
    public File trackTakeFile(String trackId) {
        return mkdirsParent(new File(libraryDir(), "tracks/" + trackId + ".wav"));
    }

    /** getFilesDir()/library/render/track_&lt;id&gt;.wav (dirs created on demand). */
    public File trackRenderFile(String trackId) {
        return mkdirsParent(new File(libraryDir(), "render/track_" + trackId + ".wav"));
    }

    /** getFilesDir()/library/render/mix_&lt;entryId&gt;.wav (dirs created on demand). */
    public File mixFile(String entryId) {
        return mkdirsParent(new File(libraryDir(), "render/mix_" + entryId + ".wav"));
    }

    /** getFilesDir()/library/punches/&lt;uuid&gt;.wav: durable punch-in takes
     * referenced by punch_in ops (dirs created on demand). */
    public File punchTakeFile(String uuid) {
        return mkdirsParent(new File(libraryDir(), "punches/" + uuid + ".wav"));
    }

    // ------------------------------------------------------------------
    // Entries
    // ------------------------------------------------------------------

    /**
     * Creates an entry with a generated UUID. updated_at is set to createdAt.
     * Returns the new entry id.
     */
    public synchronized String createEntry(String name, long createdAt,
            double durationS, int sampleRate, int channels,
            String source, String captureLabel, String dspVersion,
            String pristinePath) {
        return createEntryWithId(UUID.randomUUID().toString(), name, createdAt,
                durationS, sampleRate, channels, source, captureLabel,
                dspVersion, pristinePath);
    }

    /**
     * Creates an entry with a caller-supplied id (lets MainActivity know the
     * UUID before copying the take into takeFile(id)). Otherwise identical to
     * {@link #createEntry}: updated_at is set to createdAt, returns the id.
     *
     * @throws IllegalArgumentException if id is null/empty or an entry with
     *         that id already exists
     */
    public synchronized String createEntryWithId(String id, String name,
            long createdAt, double durationS, int sampleRate, int channels,
            String source, String captureLabel, String dspVersion,
            String pristinePath) {
        if (id == null || id.isEmpty()) {
            throw new IllegalArgumentException("id must not be null or empty");
        }
        if (getEntry(id) != null) {
            throw new IllegalArgumentException(
                    "Library entry already exists: " + id);
        }
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("id", id);
        cv.put("name", name);
        cv.put("created_at", createdAt);
        cv.put("updated_at", createdAt);
        cv.put("duration_s", durationS);
        cv.put("sample_rate", sampleRate);
        cv.put("channels", channels);
        cv.put("source", source);
        cv.put("capture_label", captureLabel);
        cv.put("dsp_version", dspVersion);
        cv.put("pristine_path", pristinePath);
        db.insertOrThrow("entries", null, cv);
        return id;
    }

    /** Returns the entry, or null when missing. */
    public LibraryEntry getEntry(String id) {
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor c = db.query("entries", null, "id=?",
                new String[]{id}, null, null, null)) {
            if (!c.moveToFirst()) {
                return null;
            }
            return readEntry(c);
        }
    }

    /** All entries, newest first (updated_at DESC). */
    public List<LibraryEntry> listEntries() {
        List<LibraryEntry> out = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor c = db.query("entries", null, null, null, null, null,
                "updated_at DESC")) {
            while (c.moveToNext()) {
                out.add(readEntry(c));
            }
        }
        return out;
    }

    /** Renames the entry; also bumps updated_at. */
    public synchronized void renameEntry(String id, String name) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("name", name);
        cv.put("updated_at", System.currentTimeMillis());
        db.update("entries", cv, "id=?", new String[]{id});
    }

    /**
     * Deletes the entry's DB rows (tracks and ops cascade via FK) AND the
     * legacy take + render files, the mix file, and every track's take +
     * render files. Missing files are ignored.
     */
    public synchronized void deleteEntry(String id) {
        deleteIfExists(takeFile(id));
        deleteIfExists(renderFile(id));
        deleteIfExists(mixFile(id));
        for (Track t : listTracks(id)) {
            deleteIfExists(trackTakeFile(t.id));
            deleteIfExists(trackRenderFile(t.id));
        }
        // Punch-in takes referenced by track ops; delete them too.
        try {
            for (Track t : listTracks(id)) {
                for (Op op : listOps(id, t.id)) {
                    if ("punch_in".equals(op.opType)
                            && op.paramsJson != null) {
                        String takePath =
                                new org.json.JSONObject(op.paramsJson)
                                        .optString("take_path", null);
                        if (takePath != null) {
                            deleteIfExists(new File(takePath));
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("entries", "id=?", new String[]{id});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    private static void deleteIfExists(File f) {
        if (f != null && f.exists()) {
            f.delete();
        }
    }

    /**
     * Forks an entry: new UUID, pristine file copied, tracks duplicated (new
     * UUIDs, wav files copied, same names/locked/position), each track's ops
     * copied preserving seq with remapped track_id, plus a first (seq=0)
     * entry-level display-only "fork" marker op (NULL track_id) with label
     * "Forked from &lt;old name&gt;".
     *
     * The fork marker is display-only: render_chain callers must filter out
     * op types 'livefx' and 'fork' before rendering.
     *
     * @return the new entry id
     * @throws IllegalArgumentException if the source entry (or its pristine
     *         file) is missing
     */
    public synchronized String duplicateEntry(String id) {
        LibraryEntry src = getEntry(id);
        if (src == null) {
            throw new IllegalArgumentException("No such library entry: " + id);
        }
        File srcTake = new File(src.pristinePath);
        if (!srcTake.isFile()) {
            throw new IllegalArgumentException(
                    "Pristine file missing for entry: " + id);
        }
        String newId = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        copyFile(srcTake, takeFile(newId));

        // Copy track wav files up front (mirrors the pristine copy above).
        // A missing track wav does not abort the fork; the track row still
        // points at its conventional path.
        List<Track> srcTracks = listTracks(id);
        Map<String, String> trackIdMap = new LinkedHashMap<>();
        Map<String, String> trackWavPath = new LinkedHashMap<>();
        for (Track t : srcTracks) {
            String newTrackId = UUID.randomUUID().toString();
            trackIdMap.put(t.id, newTrackId);
            File dstWav = trackTakeFile(newTrackId);
            File srcWav = new File(t.wavPath);
            if (srcWav.isFile()) {
                copyFile(srcWav, dstWav);
            }
            trackWavPath.put(newTrackId, dstWav.getAbsolutePath());
        }

        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues cv = new ContentValues();
            cv.put("id", newId);
            cv.put("name", src.name);
            cv.put("created_at", now);
            cv.put("updated_at", now);
            cv.put("duration_s", src.durationS);
            cv.put("sample_rate", src.sampleRate);
            cv.put("channels", src.channels);
            cv.put("source", src.source);
            cv.put("capture_label", src.captureLabel);
            cv.put("dsp_version", src.dspVersion);
            cv.put("pristine_path", takeFile(newId).getAbsolutePath());
            db.insertOrThrow("entries", null, cv);

            // Duplicate tracks: new UUIDs, same name/locked/muted/position.
            for (Track t : srcTracks) {
                String newTrackId = trackIdMap.get(t.id);
                ContentValues tcv = new ContentValues();
                tcv.put("id", newTrackId);
                tcv.put("entry_id", newId);
                tcv.put("name", t.name);
                tcv.put("wav_path", trackWavPath.get(newTrackId));
                tcv.put("locked", t.locked ? 1 : 0);
                tcv.put("muted", t.muted ? 1 : 0);
                tcv.put("position", t.position);
                tcv.put("created_at", now);
                tcv.put("updated_at", now);
                db.insertOrThrow("tracks", null, tcv);
            }

            // Copy ops preserving seq, remapping track_id onto the new
            // tracks. Entry-level rows (NULL track_id, e.g. older fork
            // markers) keep NULL track_id.
            for (Op op : listAllOps(db, id)) {
                ContentValues ocv = new ContentValues();
                ocv.put("entry_id", newId);
                ocv.put("track_id", op.trackId == null ? null
                        : trackIdMap.get(op.trackId));
                ocv.put("seq", op.seq);
                ocv.put("op_type", op.opType);
                String paramsJson = op.paramsJson;
                // punch_in ops reference a durable take file; copy it so
                // the fork does not share (and cannot lose) the original.
                if ("punch_in".equals(op.opType) && paramsJson != null) {
                    try {
                        org.json.JSONObject p =
                                new org.json.JSONObject(paramsJson);
                        String takePath = p.optString("take_path", null);
                        if (takePath != null) {
                            File punchSrc = new File(takePath);
                            if (punchSrc.isFile()) {
                                File dstTake = punchTakeFile(
                                        java.util.UUID.randomUUID()
                                                .toString());
                                copyFile(punchSrc, dstTake);
                                p.put("take_path",
                                        dstTake.getAbsolutePath());
                                paramsJson = p.toString();
                            }
                        }
                    } catch (Exception ignored) {
                    }
                }
                ocv.put("params_json", paramsJson);
                ocv.put("label", op.label);
                ocv.put("created_at", op.createdAt);
                db.insertOrThrow("ops", null, ocv);
            }

            // Fork marker: seq 0 sorts first; display-only, never rendered.
            ContentValues fcv = new ContentValues();
            fcv.put("entry_id", newId);
            fcv.put("track_id", (String) null);
            fcv.put("seq", 0);
            fcv.put("op_type", "fork");
            fcv.put("params_json", "{\"from_id\":\"" + escapeJson(src.id)
                    + "\",\"from_name\":\"" + escapeJson(src.name) + "\"}");
            fcv.put("label", "Forked from " + src.name);
            fcv.put("created_at", now);
            db.insertOrThrow("ops", null, fcv);

            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        return newId;
    }

    private static void copyFile(File src, File dst) {
        try (InputStream in = new FileInputStream(src);
             OutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
        } catch (IOException e) {
            throw new IllegalArgumentException(
                    "Could not copy pristine file: " + e.getMessage());
        }
    }

    private static String escapeJson(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default: sb.append(ch);
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Tracks
    // ------------------------------------------------------------------

    /**
     * Creates a track on the entry. Generates a UUID; the take path is
     * trackTakeFile(newId). position = max existing + 1, starting at 1.
     * Returns the new Track.
     */
    public synchronized Track createTrack(String entryId, String name) {
        String newId = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        String wavPath = trackTakeFile(newId).getAbsolutePath();
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        int position;
        try {
            position = 1;
            try (Cursor c = db.rawQuery(
                    "SELECT MAX(position) FROM tracks WHERE entry_id=?",
                    new String[]{entryId})) {
                if (c.moveToFirst() && !c.isNull(0)) {
                    position = c.getInt(0) + 1;
                }
            }
            ContentValues cv = new ContentValues();
            cv.put("id", newId);
            cv.put("entry_id", entryId);
            cv.put("name", name);
            cv.put("wav_path", wavPath);
            cv.put("locked", 0);
            cv.put("position", position);
            cv.put("created_at", now);
            cv.put("updated_at", now);
            db.insertOrThrow("tracks", null, cv);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        return new Track(newId, entryId, name, wavPath, false, false, position,
                now, now);
    }

    /** All tracks for the entry, ORDER BY position ASC. */
    public List<Track> listTracks(String entryId) {
        List<Track> out = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor c = db.query("tracks", null, "entry_id=?",
                new String[]{entryId}, null, null, "position ASC")) {
            while (c.moveToNext()) {
                out.add(readTrack(c));
            }
        }
        return out;
    }

    /** Returns the track, or null when missing. */
    public Track getTrack(String trackId) {
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor c = db.query("tracks", null, "id=?",
                new String[]{trackId}, null, null, null)) {
            if (!c.moveToFirst()) {
                return null;
            }
            return readTrack(c);
        }
    }

    /** Renames the track; also bumps the parent entry's updated_at. */
    public synchronized void renameTrack(String trackId, String name) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            String entryId = null;
            try (Cursor c = db.query("tracks", new String[]{"entry_id"},
                    "id=?", new String[]{trackId}, null, null, null)) {
                if (c.moveToFirst()) {
                    entryId = c.getString(0);
                }
            }
            if (entryId != null) {
                ContentValues cv = new ContentValues();
                cv.put("name", name);
                cv.put("updated_at", System.currentTimeMillis());
                db.update("tracks", cv, "id=?", new String[]{trackId});
                bumpUpdatedAt(db, entryId);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /** Sets the track's locked flag (and bumps the track's updated_at). */
    public synchronized void setTrackLocked(String trackId, boolean locked) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("locked", locked ? 1 : 0);
        cv.put("updated_at", System.currentTimeMillis());
        db.update("tracks", cv, "id=?", new String[]{trackId});
    }

    /** Sets the track's muted flag (and bumps the track's updated_at). */
    public synchronized void setTrackMuted(String trackId, boolean muted) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("muted", muted ? 1 : 0);
        cv.put("updated_at", System.currentTimeMillis());
        db.update("tracks", cv, "id=?", new String[]{trackId});
    }

    /**
     * Deletes the track's take + render files, then the row (the track's
     * ops cascade via FK). Missing files are ignored.
     */
    public synchronized void deleteTrack(String trackId) {
        deleteIfExists(trackTakeFile(trackId));
        deleteIfExists(trackRenderFile(trackId));
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("tracks", "id=?", new String[]{trackId});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /**
     * Migration helper for legacy single-take entries: when the entry has
     * no tracks, creates "Track 1" reusing the legacy pristine file (no
     * copy) and adopts the legacy entry-level ops (track_id IS NULL) onto
     * it. Returns listTracks(entryId).
     */
    public synchronized List<Track> ensureTracks(String entryId,
            String legacyPristinePath) {
        List<Track> existing = listTracks(entryId);
        if (!existing.isEmpty()) {
            return existing;
        }
        String newId = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues cv = new ContentValues();
            cv.put("id", newId);
            cv.put("entry_id", entryId);
            cv.put("name", "Track 1");
            cv.put("wav_path", legacyPristinePath);
            cv.put("locked", 0);
            cv.put("position", 1);
            cv.put("created_at", now);
            cv.put("updated_at", now);
            db.insertOrThrow("tracks", null, cv);
            ContentValues ocv = new ContentValues();
            ocv.put("track_id", newId);
            db.update("ops", ocv, "entry_id=? AND track_id IS NULL",
                    new String[]{entryId});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        return listTracks(entryId);
    }

    private static Track readTrack(Cursor c) {
        return new Track(
                c.getString(c.getColumnIndexOrThrow("id")),
                c.getString(c.getColumnIndexOrThrow("entry_id")),
                c.getString(c.getColumnIndexOrThrow("name")),
                c.getString(c.getColumnIndexOrThrow("wav_path")),
                c.getInt(c.getColumnIndexOrThrow("locked")) != 0,
                c.getInt(c.getColumnIndexOrThrow("muted")) != 0,
                c.getInt(c.getColumnIndexOrThrow("position")),
                c.getLong(c.getColumnIndexOrThrow("created_at")),
                c.getLong(c.getColumnIndexOrThrow("updated_at")));
    }

    // ------------------------------------------------------------------
    // Ops (track-scoped)
    // ------------------------------------------------------------------

    /**
     * Appends an op to the track's chain. seq = max existing seq for the
     * track + 1, starting at 1 (the entry-level 'fork' marker uses seq 0 so
     * it always sorts first). Bumps entries.updated_at.
     *
     * @return the new op row id
     * @throws IllegalArgumentException if trackId is null or empty
     */
    public synchronized long addOp(String entryId, String trackId, String opType,
            String paramsJson, String label) {
        if (trackId == null || trackId.isEmpty()) {
            throw new IllegalArgumentException(
                    "trackId must not be null or empty");
        }
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        long rowId;
        try {
            int nextSeq = 1;
            try (Cursor c = db.rawQuery(
                    "SELECT MAX(seq) FROM ops WHERE entry_id=? AND track_id=?",
                    new String[]{entryId, trackId})) {
                if (c.moveToFirst() && !c.isNull(0)) {
                    nextSeq = c.getInt(0) + 1;
                }
            }
            ContentValues cv = new ContentValues();
            cv.put("entry_id", entryId);
            cv.put("track_id", trackId);
            cv.put("seq", nextSeq);
            cv.put("op_type", opType);
            cv.put("params_json", paramsJson);
            cv.put("label", label);
            cv.put("created_at", System.currentTimeMillis());
            rowId = db.insertOrThrow("ops", null, cv);
            bumpUpdatedAt(db, entryId);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        return rowId;
    }

    /**
     * Deletes the track's highest-seq op row. Returns false when the track
     * has no ops. Bumps entries.updated_at on success.
     *
     * @throws IllegalArgumentException if trackId is null or empty
     */
    public synchronized boolean popLastOp(String entryId, String trackId) {
        if (trackId == null || trackId.isEmpty()) {
            throw new IllegalArgumentException(
                    "trackId must not be null or empty");
        }
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        boolean popped = false;
        try {
            Long rowId = null;
            try (Cursor c = db.rawQuery(
                    "SELECT id FROM ops WHERE entry_id=? AND track_id=?"
                            + " ORDER BY seq DESC LIMIT 1",
                    new String[]{entryId, trackId})) {
                if (c.moveToFirst()) {
                    rowId = c.getLong(0);
                }
            }
            if (rowId != null) {
                db.delete("ops", "id=?", new String[]{String.valueOf(rowId)});
                bumpUpdatedAt(db, entryId);
                popped = true;
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        return popped;
    }

    /** All ops for the track, ORDER BY seq ASC. */
    public List<Op> listOps(String entryId, String trackId) {
        List<Op> out = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor c = db.query("ops", null, "entry_id=? AND track_id=?",
                new String[]{entryId, trackId}, null, null, "seq ASC")) {
            while (c.moveToNext()) {
                out.add(readOp(c));
            }
        }
        return out;
    }

    /**
     * All ops for the entry across tracks (plus entry-level rows with NULL
     * track_id), ORDER BY seq ASC. Internal helper for duplicateEntry.
     */
    private List<Op> listAllOps(SQLiteDatabase db, String entryId) {
        List<Op> out = new ArrayList<>();
        try (Cursor c = db.query("ops", null, "entry_id=?",
                new String[]{entryId}, null, null, "seq ASC")) {
            while (c.moveToNext()) {
                out.add(readOp(c));
            }
        }
        return out;
    }

    private static Op readOp(Cursor c) {
        return new Op(
                c.getLong(c.getColumnIndexOrThrow("id")),
                c.getString(c.getColumnIndexOrThrow("entry_id")),
                c.getString(c.getColumnIndexOrThrow("track_id")),
                c.getInt(c.getColumnIndexOrThrow("seq")),
                c.getString(c.getColumnIndexOrThrow("op_type")),
                c.getString(c.getColumnIndexOrThrow("params_json")),
                c.getString(c.getColumnIndexOrThrow("label")),
                c.getLong(c.getColumnIndexOrThrow("created_at")));
    }

    // ------------------------------------------------------------------
    // Exports
    // ------------------------------------------------------------------

    /** Logs an MP3 export against the entry. */
    public synchronized void logExport(String entryId, String fileName,
            String folderLabel, int opCount) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("entry_id", entryId);
        cv.put("file_name", fileName);
        cv.put("folder_label", folderLabel);
        cv.put("op_count", opCount);
        cv.put("created_at", System.currentTimeMillis());
        db.insertOrThrow("exports", null, cv);
    }

    /** All export rows for the entry, newest first (created_at DESC). */
    public List<Export> listExports(String entryId) {
        List<Export> out = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor c = db.query("exports", null, "entry_id=?",
                new String[]{entryId}, null, null, "created_at DESC")) {
            while (c.moveToNext()) {
                out.add(new Export(
                        c.getLong(c.getColumnIndexOrThrow("id")),
                        c.getString(c.getColumnIndexOrThrow("entry_id")),
                        c.getString(c.getColumnIndexOrThrow("file_name")),
                        c.getString(c.getColumnIndexOrThrow("folder_label")),
                        c.getInt(c.getColumnIndexOrThrow("op_count")),
                        c.getLong(c.getColumnIndexOrThrow("created_at"))));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Storage
    // ------------------------------------------------------------------

    /** Sum of file lengths under getFilesDir()/library (takes + render). */
    public long librarySizeBytes() {
        return dirSizeBytes(libraryDir());
    }

    private static long dirSizeBytes(File dir) {
        long total = 0;
        File[] children = dir.listFiles();
        if (children == null) {
            return 0;
        }
        for (File f : children) {
            if (f.isDirectory()) {
                total += dirSizeBytes(f);
            } else {
                total += f.length();
            }
        }
        return total;
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private void bumpUpdatedAt(SQLiteDatabase db, String entryId) {
        ContentValues cv = new ContentValues();
        cv.put("updated_at", System.currentTimeMillis());
        db.update("entries", cv, "id=?", new String[]{entryId});
    }

    private static LibraryEntry readEntry(Cursor c) {
        return new LibraryEntry(
                c.getString(c.getColumnIndexOrThrow("id")),
                c.getString(c.getColumnIndexOrThrow("name")),
                c.getLong(c.getColumnIndexOrThrow("created_at")),
                c.getLong(c.getColumnIndexOrThrow("updated_at")),
                c.getDouble(c.getColumnIndexOrThrow("duration_s")),
                c.getInt(c.getColumnIndexOrThrow("sample_rate")),
                c.getInt(c.getColumnIndexOrThrow("channels")),
                c.getString(c.getColumnIndexOrThrow("source")),
                c.getString(c.getColumnIndexOrThrow("capture_label")),
                c.getString(c.getColumnIndexOrThrow("dsp_version")),
                c.getString(c.getColumnIndexOrThrow("pristine_path")));
    }

    // ------------------------------------------------------------------
    // Model classes
    // ------------------------------------------------------------------

    public static class LibraryEntry {
        public final String id;
        public final String name;
        public final long createdAt;
        public final long updatedAt;
        public final double durationS;
        public final int sampleRate;
        public final int channels;
        public final String source;
        public final String captureLabel;
        public final String dspVersion;
        public final String pristinePath;

        public LibraryEntry(String id, String name, long createdAt,
                long updatedAt, double durationS, int sampleRate, int channels,
                String source, String captureLabel, String dspVersion,
                String pristinePath) {
            this.id = id;
            this.name = name;
            this.createdAt = createdAt;
            this.updatedAt = updatedAt;
            this.durationS = durationS;
            this.sampleRate = sampleRate;
            this.channels = channels;
            this.source = source;
            this.captureLabel = captureLabel;
            this.dspVersion = dspVersion;
            this.pristinePath = pristinePath;
        }
    }

    public static class Track {
        public final String id;
        public final String entryId;
        public final String name;
        public final String wavPath;
        public final boolean locked;
        public final boolean muted;
        public final int position;
        public final long createdAt;
        public final long updatedAt;

        public Track(String id, String entryId, String name, String wavPath,
                boolean locked, boolean muted, int position, long createdAt,
                long updatedAt) {
            this.id = id;
            this.entryId = entryId;
            this.name = name;
            this.wavPath = wavPath;
            this.locked = locked;
            this.muted = muted;
            this.position = position;
            this.createdAt = createdAt;
            this.updatedAt = updatedAt;
        }
    }

    public static class Op {
        public final long rowId;
        public final String entryId;
        public final String trackId;
        public final int seq;
        public final String opType;
        public final String paramsJson;
        public final String label;
        public final long createdAt;

        public Op(long rowId, String entryId, String trackId, int seq,
                String opType, String paramsJson, String label,
                long createdAt) {
            this.rowId = rowId;
            this.entryId = entryId;
            this.trackId = trackId;
            this.seq = seq;
            this.opType = opType;
            this.paramsJson = paramsJson;
            this.label = label;
            this.createdAt = createdAt;
        }
    }

    public static class Export {
        public final long rowId;
        public final String entryId;
        public final String fileName;
        public final String folderLabel;
        public final int opCount;
        public final long createdAt;

        public Export(long rowId, String entryId, String fileName,
                String folderLabel, int opCount, long createdAt) {
            this.rowId = rowId;
            this.entryId = entryId;
            this.fileName = fileName;
            this.folderLabel = folderLabel;
            this.opCount = opCount;
            this.createdAt = createdAt;
        }
    }
}
