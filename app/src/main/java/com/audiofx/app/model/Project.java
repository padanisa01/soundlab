package com.audiofx.app.model;

import com.audiofx.app.LibraryDb;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A project (design doc 4.2): an entry row plus its tracks and
 * per-project settings. Assembled in one place by
 * LibraryDb.loadProject (entry + tracks + histories); LibraryDb
 * keeps all SQL and this class is the in-memory composition.
 *
 * {@link #tracks} is an unmodifiable defensive copy of the list
 * handed to the constructor, in LibraryDb.listTracks order
 * (position ASC): callers cannot mutate the project's track list
 * through it. {@link #selected} is mutable session state: as of
 * OO-6 it is the ONLY home of the track selection (MainActivity's
 * former selected-id field is gone); MainActivity sets it via
 * {@link #trackById} and reads it via {@link #selectedId()}.
 */
public final class Project {

    /** The project's library entry row (name, source, pristine path). */
    public final LibraryDb.LibraryEntry entry;
    /** The project's tracks, in position order (unmodifiable). */
    public final List<TrackModel> tracks;
    /** Per-project settings (thin v1; see ProjectSettings). */
    public final ProjectSettings settings;
    /** The selected track, or null when none is selected. */
    public TrackModel selected;

    public Project(LibraryDb.LibraryEntry entry, List<TrackModel> tracks,
            ProjectSettings settings) {
        this.entry = entry;
        this.tracks = Collections.unmodifiableList(new ArrayList<>(tracks));
        this.settings = settings;
        this.selected = null;
    }

    /** The project's entry id (its LibraryEntry row id), or null
     * when the project has no entry row. As of OO-6 this is the
     * only home of the project identity MainActivity used to keep
     * in a separate entry-id field: a null Project (no project
     * open) is the picked-file / no-project state. */
    public String entryId() {
        return entry != null ? entry.id : null;
    }

    /** The selected track's id, or null when none is selected. */
    public String selectedId() {
        return selected != null ? selected.row.id : null;
    }

    /** The track with the given id, or null when there is none
     * (a null id yields null). Linear scan: track counts are small. */
    public TrackModel trackById(String id) {
        if (id == null) {
            return null;
        }
        for (TrackModel t : tracks) {
            if (id.equals(t.row.id)) {
                return t;
            }
        }
        return null;
    }
}
