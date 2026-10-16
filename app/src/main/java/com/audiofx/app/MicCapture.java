package com.audiofx.app;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

/**
 * Shared microphone capture setup. Tries configs highest-fidelity first:
 * UNPROCESSED @ 48 kHz stereo (raw, no AGC or noise suppression), then
 * VOICE_RECOGNITION @ 48 kHz stereo, then MIC @ 44.1 kHz stereo, finally
 * MIC @ 44.1 kHz mono. Stereo is how Android engages multiple built-in
 * mics; apps cannot address individual mics.
 */
final class MicCapture {

    private static final int[][] CONFIGS = {
            // {audioSource, sampleRate, channelConfig}
            {MediaRecorder.AudioSource.UNPROCESSED, 48000, AudioFormat.CHANNEL_IN_STEREO},
            {MediaRecorder.AudioSource.VOICE_RECOGNITION, 48000, AudioFormat.CHANNEL_IN_STEREO},
            {MediaRecorder.AudioSource.MIC, 44100, AudioFormat.CHANNEL_IN_STEREO},
            {MediaRecorder.AudioSource.MIC, 44100, AudioFormat.CHANNEL_IN_MONO},
    };

    static final class Handle {
        final AudioRecord rec;
        final int sampleRate;
        final int channels;
        final String label;

        Handle(AudioRecord rec, int sampleRate, int channels, String label) {
            this.rec = rec;
            this.sampleRate = sampleRate;
            this.channels = channels;
            this.label = label;
        }
    }

    /** Open the best working capture config, or null when none works. */
    static Handle open() {
        for (int[] c : CONFIGS) {
            int source = c[0];
            int sampleRate = c[1];
            int channelConfig = c[2];
            int minBuf;
            try {
                minBuf = AudioRecord.getMinBufferSize(sampleRate, channelConfig,
                        AudioFormat.ENCODING_PCM_16BIT);
            } catch (Exception e) {
                continue;
            }
            if (minBuf <= 0) {
                continue;
            }
            final AudioRecord rec;
            try {
                rec = new AudioRecord(source, sampleRate, channelConfig,
                        AudioFormat.ENCODING_PCM_16BIT, minBuf * 4);
            } catch (Exception e) {
                continue;
            }
            if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
                rec.release();
                continue;
            }
            int channels = channelConfig == AudioFormat.CHANNEL_IN_STEREO ? 2 : 1;
            String label = (channels == 2 ? "stereo" : "mono") + " "
                    + (sampleRate / 1000) + " kHz " + sourceName(source);
            return new Handle(rec, sampleRate, channels, label);
        }
        return null;
    }

    private static String sourceName(int source) {
        if (source == MediaRecorder.AudioSource.UNPROCESSED) {
            return "UNPROCESSED";
        }
        if (source == MediaRecorder.AudioSource.VOICE_RECOGNITION) {
            return "VOICE_RECOGNITION";
        }
        return "MIC";
    }

    /**
     * Receives the peak amplitude (0..1) of each captured PCM block, for
     * live UI (e.g. a growing waveform while recording). Called on the
     * capture thread; implementations must be cheap and thread-safe.
     */
    interface BlockListener {
        void onBlockPeak(float peak);
    }

    /** Max |sample| of a little-endian PCM-16 block, mixed to mono. */
    static float blockPeak(byte[] buf, int n, int channels) {
        int frames = n / (2 * channels);
        float peak = 0f;
        for (int f = 0; f < frames; f++) {
            float sum = 0f;
            for (int c = 0; c < channels; c++) {
                int i = (f * channels + c) * 2;
                int s = (buf[i] & 0xff) | (buf[i + 1] << 8);
                sum += s / 32768f;
            }
            float m = Math.abs(sum / channels);
            if (m > peak) {
                peak = m;
            }
        }
        return peak;
    }

    private MicCapture() {
    }
}
