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
import java.util.List;
import java.util.UUID;

/**
 * Plain SQLiteOpenHelper for the SoundLab "Library of recordings" feature.
 *
 * One immutable pristine WAV per entry (getFilesDir()/library/takes/<uuid>.wav)
 * plus an ordered, re-runnable op chain in {@code ops}. The cached render lives
 * at getFilesDir()/library/render/<uuid>.wav and is regenerable.
 *
 * Op types in use: 'livefx' (display-only, never rendered), 'fx', 'normalize',
 * 'trim', 'join', 'fork' (display-only marker, never rendered). Callers that
 * render via render_chain must filter out 'livefx' and 'fork' ops.
 */
public class LibraryDb extends SQLiteOpenHelper {

    public static final String DSP_VERSION = "1";

    private static final String DB_NAME = "soundlab_library.db";
    private static final int DB_VERSION = 1;

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
        db.execSQL("CREATE TABLE ops(\n"
                + "  id INTEGER PRIMARY KEY AUTOINCREMENT,\n"
                + "  entry_id TEXT NOT NULL REFERENCES entries(id) ON DELETE CASCADE,\n"
                + "  seq INTEGER NOT NULL,\n"
                + "  op_type TEXT NOT NULL,\n"
                + "  params_json TEXT NOT NULL,\n"
                + "  label TEXT NOT NULL,\n"
                + "  created_at INTEGER NOT NULL\n"
                + ")");
        db.execSQL("CREATE INDEX idx_ops_entry ON ops(entry_id, seq)");
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
        // Fresh v1: no migration, drop and recreate.
        db.execSQL("DROP TABLE IF EXISTS exports");
        db.execSQL("DROP TABLE IF EXISTS ops");
        db.execSQL("DROP TABLE IF EXISTS entries");
        onCreate(db);
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
     * Deletes the entry's DB rows (ops and exports cascade) AND the take +
     * render files. Missing files are ignored.
     */
    public synchronized void deleteEntry(String id) {
        deleteIfExists(takeFile(id));
        deleteIfExists(renderFile(id));
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
     * Forks an entry: new UUID, pristine file copied, all ops copied
     * preserving seq, plus a first (seq=0) display-only "fork" marker op with
     * label "Forked from &lt;old name&gt;".
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

            // Copy ops preserving seq.
            for (Op op : listOps(id)) {
                ContentValues ocv = new ContentValues();
                ocv.put("entry_id", newId);
                ocv.put("seq", op.seq);
                ocv.put("op_type", op.opType);
                ocv.put("params_json", op.paramsJson);
                ocv.put("label", op.label);
                ocv.put("created_at", op.createdAt);
                db.insertOrThrow("ops", null, ocv);
            }

            // Fork marker: seq 0 sorts first; display-only, never rendered.
            ContentValues fcv = new ContentValues();
            fcv.put("entry_id", newId);
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
    // Ops
    // ------------------------------------------------------------------

    /**
     * Appends an op. seq = max existing seq + 1, starting at 1 (the 'fork'
     * marker uses seq 0 so it always sorts first). Bumps entries.updated_at.
     *
     * @return the new op row id
     */
    public synchronized long addOp(String entryId, String opType,
            String paramsJson, String label) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        long rowId;
        try {
            int nextSeq = 1;
            try (Cursor c = db.rawQuery(
                    "SELECT MAX(seq) FROM ops WHERE entry_id=?",
                    new String[]{entryId})) {
                if (c.moveToFirst() && !c.isNull(0)) {
                    nextSeq = c.getInt(0) + 1;
                }
            }
            ContentValues cv = new ContentValues();
            cv.put("entry_id", entryId);
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
     * Deletes the highest-seq op row. Returns false when there are no ops.
     * Bumps entries.updated_at on success.
     */
    public synchronized boolean popLastOp(String entryId) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        boolean popped = false;
        try {
            Long rowId = null;
            try (Cursor c = db.rawQuery(
                    "SELECT id FROM ops WHERE entry_id=? ORDER BY seq DESC LIMIT 1",
                    new String[]{entryId})) {
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

    /** All ops for the entry, ORDER BY seq ASC. */
    public List<Op> listOps(String entryId) {
        List<Op> out = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor c = db.query("ops", null, "entry_id=?",
                new String[]{entryId}, null, null, "seq ASC")) {
            while (c.moveToNext()) {
                out.add(new Op(
                        c.getLong(c.getColumnIndexOrThrow("id")),
                        c.getString(c.getColumnIndexOrThrow("entry_id")),
                        c.getInt(c.getColumnIndexOrThrow("seq")),
                        c.getString(c.getColumnIndexOrThrow("op_type")),
                        c.getString(c.getColumnIndexOrThrow("params_json")),
                        c.getString(c.getColumnIndexOrThrow("label")),
                        c.getLong(c.getColumnIndexOrThrow("created_at"))));
            }
        }
        return out;
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

    public static class Op {
        public final long rowId;
        public final String entryId;
        public final int seq;
        public final String opType;
        public final String paramsJson;
        public final String label;
        public final long createdAt;

        public Op(long rowId, String entryId, int seq, String opType,
                String paramsJson, String label, long createdAt) {
            this.rowId = rowId;
            this.entryId = entryId;
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
