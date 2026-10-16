package com.audiofx.app;

import android.media.audiofx.AutomaticGainControl;
import android.media.audiofx.NoiseSuppressor;

/**
 * Best-effort attach of automatic gain control and noise suppression to
 * a capture session. Both are optional: when the device lacks them the
 * getters report inactive and capture proceeds without them.
 */
final class CaptureFx {

    private AutomaticGainControl agc;
    private NoiseSuppressor ns;

    void attach(int sessionId, boolean wantAgc, boolean wantNs) {
        release();
        if (wantNs && NoiseSuppressor.isAvailable()) {
            try {
                ns = NoiseSuppressor.create(sessionId);
                if (ns != null) {
                    ns.setEnabled(true);
                }
            } catch (Exception ignored) {
                ns = null;
            }
        }
        if (wantAgc && AutomaticGainControl.isAvailable()) {
            try {
                agc = AutomaticGainControl.create(sessionId);
                if (agc != null) {
                    agc.setEnabled(true);
                }
            } catch (Exception ignored) {
                agc = null;
            }
        }
    }

    boolean agcActive() {
        return agc != null && agc.getEnabled();
    }

    boolean nsActive() {
        return ns != null && ns.getEnabled();
    }

    void release() {
        if (ns != null) {
            try {
                ns.release();
            } catch (Exception ignored) {
            }
            ns = null;
        }
        if (agc != null) {
            try {
                agc.release();
            } catch (Exception ignored) {
            }
            agc = null;
        }
    }
}
