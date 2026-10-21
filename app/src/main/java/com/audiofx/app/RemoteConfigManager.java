package com.audiofx.app;

import java.util.HashMap;
import java.util.Map;

/**
 * Firebase Remote Config for ads and paid-feature gating.
 *
 * Defaults are fail-closed for monetization (ads ON, paid features
 * hidden) so an unreachable Firebase or missing google-services.json
 * never gives away paid features. The Firebase console can flip to
 * launch mode (ads off, everything unlocked) without an app update.
 *
 * All getters are safe to call before init() completes; they return
 * the defaults until the first fetch activates.
 */
public final class RemoteConfigManager {

    private static final String KEY_ADS_ENABLED = "ads_enabled";
    private static final String KEY_PODCAST_UNLOCKED = "podcast_unlocked";
    private static final String KEY_MUSIC_UNLOCKED = "music_unlocked";

    // Fail-closed defaults: ads on, paid features hidden.
    private volatile boolean adsEnabled = true;
    private volatile boolean podcastUnlocked = false;
    private volatile boolean musicUnlocked = false;
    private volatile boolean fetched;

    /** Fetch and activate; safe to call on the UI thread.
     * @param onFetched run on the UI thread after the fetch completes
     * (or immediately if Firebase is unavailable). May be null. */
    public void init(Runnable onFetched) {
        try {
            com.google.firebase.remoteconfig.FirebaseRemoteConfig config =
                    com.google.firebase.remoteconfig.FirebaseRemoteConfig
                            .getInstance();
            Map<String, Object> defaults = new HashMap<>();
            defaults.put(KEY_ADS_ENABLED, true);
            defaults.put(KEY_PODCAST_UNLOCKED, false);
            defaults.put(KEY_MUSIC_UNLOCKED, false);
            config.setDefaultsAsync(defaults);
            // 12h cache; force-fetch on cold start when stale.
            config.fetchAndActivate().addOnCompleteListener(task -> {
                if (task.isSuccessful()) {
                    adsEnabled = config.getBoolean(KEY_ADS_ENABLED);
                    podcastUnlocked =
                            config.getBoolean(KEY_PODCAST_UNLOCKED);
                    musicUnlocked = config.getBoolean(KEY_MUSIC_UNLOCKED);
                }
                fetched = true;
                if (onFetched != null) {
                    onFetched.run();
                }
            });
        } catch (Exception ignored) {
            // Firebase not configured (no google-services.json yet):
            // stay on the fail-closed defaults.
            fetched = true;
            if (onFetched != null) {
                onFetched.run();
            }
        }
    }

    /** Fetch and activate; safe to call on the UI thread. */
    public void init() {
        init(null);
    }

    public boolean adsEnabled() {
        return adsEnabled;
    }

    public boolean isPodcastUnlocked() {
        return podcastUnlocked;
    }

    public boolean isMusicUnlocked() {
        return musicUnlocked;
    }

    public boolean isFetched() {
        return fetched;
    }
}
