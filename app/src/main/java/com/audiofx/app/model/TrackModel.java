package com.audiofx.app.model;

import com.audiofx.app.LibraryDb;

import org.json.JSONArray;

import java.io.File;

/**
 * One track of a Project (design doc 4.2): the domain view of a
 * LibraryDb.Track row, composing the row with its effect history
 * and (in later slices) its tonic and notation.
 *
 * Deviation from the design doc 4.2 sketch, documented here: the
 * sketch names a NotationResult type for the notation field, but
 * the app's real notation payload is a JSONArray of notation
 * events (the values of MainActivity's notationByTrack map), so
 * the field is a JSONArray. {@link #tonic} and {@link #notation}
 * are null in this slice (OO-4): the tonic/notation state still
 * lives in MainActivity's maps and moves onto TrackModel in OO-5.
 *
 * File paths are resolved through LibraryDb (the repository owns
 * the library file layout); this class never builds paths itself.
 */
public final class TrackModel {

    /** Identity + persisted fields (LibraryDb.Track row). */
    public final LibraryDb.Track row;
    /** The track's tonic; null in this slice (state moves in OO-5). */
    public TonicInfo tonic;
    /** The track's notation events; null in this slice (OO-5). */
    public JSONArray notation;
    /** The track's ordered effect history, loaded from the ops table. */
    public final EffectHistory history;

    private final LibraryDb db;

    public TrackModel(LibraryDb db, LibraryDb.Track row,
            EffectHistory history) {
        this.db = db;
        this.row = row;
        this.history = history;
        this.tonic = null;
        this.notation = null;
    }

    /** The track's pristine take file (LibraryDb.trackTakeFile). */
    public File takeFile() {
        return db.trackTakeFile(row.id);
    }

    /** The track's cached render file (LibraryDb.trackRenderFile). */
    public File renderFile() {
        return db.trackRenderFile(row.id);
    }

    /** A track counts as having audio when its take WAV holds
     * samples: the exact predicate of MainActivity.trackHasAudio
     * (file exists and is longer than the 44-byte WAV header),
     * applied to {@link #takeFile()}. */
    public boolean hasAudio() {
        File f = takeFile();
        return f != null && f.exists() && f.length() > 44;
    }
}
