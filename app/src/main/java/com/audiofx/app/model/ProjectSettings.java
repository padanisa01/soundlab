package com.audiofx.app.model;

/**
 * Per-project settings (design doc 4.2): thin v1. This class is the
 * reserved home for per-project settings; it is intentionally EMPTY
 * of behavior in this slice.
 *
 * Entry-level values that look like settings today (source and
 * capture label) live on the entry row and are accessible via
 * Project.entry (LibraryDb.LibraryEntry.source / captureLabel).
 * No global SharedPreferences value is moved here in v1 — global
 * prefs stay global until a later slice (backlog D5) gives a
 * specific flag a per-project home.
 */
public final class ProjectSettings {
}
