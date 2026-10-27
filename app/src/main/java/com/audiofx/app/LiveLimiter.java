package com.audiofx.app;

/**
 * Lookahead peak limiter for the live microphone capture path.
 *
 * Mirrors {@code audiofx/limiter.py} exactly (same defaults, same
 * algorithm): a delay line holds the audio back by {@link #LOOKAHEAD_MS}
 * while a peak envelope follower measures the <i>undelayed</i> input, so
 * gain reduction is fully engaged before a peak arrives. Zero overshoot
 * above the ceiling, by construction.
 *
 * Stereo is <i>linked</i>: one shared envelope/hold/gain across channels,
 * so the stereo image never shifts. The delay line is per channel.
 *
 * Attack (0.1 ms) is deliberately far under the lookahead (5 ms) so the
 * gain fully settles inside the lookahead window; release is 50 ms.
 * A detected peak is held for lookahead+1 iterations so the envelope
 * cannot decay before the peak's gain has been applied.
 *
 * Threading: create one instance per recording, use only on the capture
 * thread. {@link #processPcm16} reuses an internal scratch buffer, so it
 * never allocates on the audio thread after the first block.
 */
public final class LiveLimiter {

    private static final double CEILING_DB = -1.0;
    private static final double LOOKAHEAD_MS = 5.0;
    private static final double ATTACK_MS = 0.1;
    private static final double RELEASE_MS = 50.0;

    private final double threshold = Math.pow(10.0, CEILING_DB / 20.0);
    private final int lookahead;
    private final double attackCoeff;
    private final double releaseCoeff;
    private final int holdSamples;
    private final int channels;

    private final float[][] delay;
    private int delayPos;
    private double peak;
    private int hold;
    private double gain = 1.0;
    private float[] scratch;

    public LiveLimiter(int sampleRate, int channels) {
        this.channels = channels;
        lookahead = Math.max(1, (int) (LOOKAHEAD_MS * sampleRate / 1000.0));
        attackCoeff = Math.exp(-1.0 / (ATTACK_MS * sampleRate / 1000.0));
        releaseCoeff = Math.exp(-1.0 / (RELEASE_MS * sampleRate / 1000.0));
        holdSamples = lookahead + 1;
        delay = new float[channels][lookahead];
        delayPos = 0;
    }

    /** Process interleaved float frames in place. */
    public void process(float[] io, int frames) {
        for (int f = 0; f < frames; f++) {
            int base = f * channels;
            double ax = 0.0;
            for (int c = 0; c < channels; c++) {
                double a = Math.abs(io[base + c]);
                if (a > ax) {
                    ax = a;
                }
            }
            if (ax >= peak) {
                peak = ax;
                hold = holdSamples;
            } else if (hold > 0) {
                hold--;
            } else {
                peak *= releaseCoeff;
            }
            double target = peak < 1e-12 ? 1.0 : Math.min(1.0, threshold / peak);
            double coeff = target < gain ? attackCoeff : releaseCoeff;
            gain += coeff * (target - gain);
            for (int c = 0; c < channels; c++) {
                float x = io[base + c];
                float[] dl = delay[c];
                float delayed = dl[delayPos];
                dl[delayPos] = x;
                io[base + c] = (float) (delayed * gain);
            }
            if (++delayPos == lookahead) {
                delayPos = 0;
            }
        }
    }

    /**
     * Limit a little-endian PCM-16 block in place. {@code nBytes} must be
     * a whole number of frames.
     */
    public void processPcm16(byte[] buf, int nBytes) {
        int frames = nBytes / (2 * channels);
        if (scratch == null || scratch.length < frames * channels) {
            scratch = new float[frames * channels];
        }
        for (int f = 0; f < frames; f++) {
            for (int c = 0; c < channels; c++) {
                int i = (f * channels + c) * 2;
                int s = (buf[i] & 0xff) | (buf[i + 1] << 8);
                scratch[f * channels + c] = s / 32768f;
            }
        }
        process(scratch, frames);
        for (int f = 0; f < frames; f++) {
            for (int c = 0; c < channels; c++) {
                float v = scratch[f * channels + c];
                if (v > 1f) {
                    v = 1f;
                } else if (v < -1f) {
                    v = -1f;
                }
                int s = Math.round(v * 32767f);
                int i = (f * channels + c) * 2;
                buf[i] = (byte) (s & 0xff);
                buf[i + 1] = (byte) ((s >> 8) & 0xff);
            }
        }
    }

    /** Clear delay line and state (call when starting a new take). */
    public void reset() {
        for (float[] dl : delay) {
            java.util.Arrays.fill(dl, 0f);
        }
        delayPos = 0;
        peak = 0.0;
        hold = 0;
        gain = 1.0;
    }
}
