package com.audiofx.app;

import android.content.Context;
import android.os.Bundle;

/**
 * Product analytics via Firebase Analytics. All methods are safe to call
 * even when Firebase is unavailable (no google-services.json): events are
 * silently dropped. No PII is logged; events carry only feature usage.
 */
public final class Analytics {

    private Analytics() {
    }

    private static com.google.firebase.analytics.FirebaseAnalytics instance(
            Context ctx) {
        try {
            return com.google.firebase.analytics.FirebaseAnalytics
                    .getInstance(ctx);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void log(Context ctx, String name, Bundle params) {
        try {
            com.google.firebase.analytics.FirebaseAnalytics a =
                    instance(ctx);
            if (a != null) {
                a.logEvent(name, params);
            }
        } catch (Exception ignored) {
        }
    }

    private static void log(Context ctx, String name) {
        log(ctx, name, null);
    }

    /** App cold start. Firebase timestamps it automatically. */
    public static void appOpen(Context ctx) {
        log(ctx, "app_open");
    }

    /** Diagnose button tapped. */
    public static void diagnoseClicked(Context ctx) {
        log(ctx, "diagnose_clicked");
    }

    /** An effect op was applied. */
    public static void effectApplied(Context ctx, String effectType,
            String reverbPreset) {
        Bundle b = new Bundle();
        b.putString("effect_type", effectType);
        if (reverbPreset != null) {
            b.putString("reverb_preset", reverbPreset);
        }
        log(ctx, "effect_applied", b);
    }

    /** Library screen viewed. */
    public static void libraryViewed(Context ctx) {
        log(ctx, "library_viewed");
    }

    /** Tune-up completed with the given profile. */
    public static void tuneUpCompleted(Context ctx, String profile) {
        Bundle b = new Bundle();
        b.putString("profile", profile);
        log(ctx, "tune_up_completed", b);
    }

    /** Recording started. */
    public static void recordStart(Context ctx) {
        log(ctx, "record_start");
    }

    /** Recording stopped. */
    public static void recordStop(Context ctx, double durationSec,
            boolean punchIn) {
        Bundle b = new Bundle();
        b.putDouble("duration_s", durationSec);
        b.putString("mode", punchIn ? "punch_in" : "take");
        log(ctx, "record_stop", b);
    }

    public static void trackAdded(Context ctx) {
        log(ctx, "track_added");
    }

    public static void trackDeleted(Context ctx) {
        log(ctx, "track_deleted");
    }

    public static void shareCompleted(Context ctx) {
        log(ctx, "share_completed");
    }

    public static void premiumPurchased(Context ctx) {
        log(ctx, "premium_purchased");
    }
}
