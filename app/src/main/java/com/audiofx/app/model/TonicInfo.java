package com.audiofx.app.model;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * A track's tonic (Sa): the value object mirroring the JSON the
 * Python bridge already returns for tonic detection (design docs
 * 4.2 and 10.3). Stable keys: sa_hz, note, cents, score,
 * runner_up_rel. Not wired into MainActivity yet (that is OO-5);
 * this class is the Java-side statement of the payload contract.
 */
public final class TonicInfo {

    /** The Sa frequency in Hz. */
    public final double saHz;
    /** Note name of the nearest semitone, e.g. "G3". */
    public final String note;
    /** Deviation from the nearest semitone, in cents. */
    public final int cents;
    /** Detection score. */
    public final double score;
    /** Runner-up score relative to the winner (0 when absent). */
    public final double runnerUpRel;

    public TonicInfo(double saHz, String note, int cents,
            double score, double runnerUpRel) {
        this.saHz = saHz;
        this.note = note;
        this.cents = cents;
        this.score = score;
        this.runnerUpRel = runnerUpRel;
    }

    /** Emit exactly the bridge keys: sa_hz, note, cents, score,
     * runner_up_rel. */
    public JSONObject toJson() {
        JSONObject out = new JSONObject();
        try {
            out.put("sa_hz", saHz);
            out.put("note", note);
            out.put("cents", cents);
            out.put("score", score);
            out.put("runner_up_rel", runnerUpRel);
        } catch (JSONException ignored) {
            // put() only throws for a null key or a NaN/Infinite
            // double value; the keys above are fixed literals.
        }
        return out;
    }

    /** Read a tonic payload tolerantly: missing keys fall back to
     * defaults (0 / null note / 0), because the bridge returns {}
     * when no tonic is found. A null JSONObject yields the same
     * all-defaults value. */
    public static TonicInfo fromJson(JSONObject j) {
        if (j == null) {
            return new TonicInfo(0, null, 0, 0, 0);
        }
        return new TonicInfo(
                j.optDouble("sa_hz", 0),
                j.optString("note", null),
                j.optInt("cents", 0),
                j.optDouble("score", 0),
                j.optDouble("runner_up_rel", 0));
    }
}
