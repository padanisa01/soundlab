package com.audiofx.app;

import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;

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

        /**
         * Human-readable suffix naming the input device Android
         * actually routed to this capture, e.g. " · phone mic" or
         * " · earphone mic" -- appended to the capture label in the
         * recording status line, so a preferred-device request that
         * the system overruled is visible instead of silent. Empty
         * when the route cannot be read (pre-API 28, or no routed
         * device reported). Read after recording has started.
         */
        String micRouteSuffix() {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
                return "";
            }
            try {
                AudioDeviceInfo d = rec.getRoutedDevice();
                if (d == null) {
                    return "";
                }
                switch (d.getType()) {
                    case AudioDeviceInfo.TYPE_BUILTIN_MIC:
                        return " · phone mic";
                    case AudioDeviceInfo.TYPE_WIRED_HEADSET:
                        return " · earphone mic";
                    case AudioDeviceInfo.TYPE_BLUETOOTH_SCO:
                        return " · Bluetooth mic";
                    case AudioDeviceInfo.TYPE_USB_HEADSET:
                    case AudioDeviceInfo.TYPE_USB_DEVICE:
                        return " · USB mic";
                    default:
                        return " · external mic";
                }
            } catch (Exception e) {
                return "";
            }
        }
    }

    /** Open the best working capture config, or null when none works. */
    static Handle open() {
        return open(null);
    }

    /**
     * As {@link #open()}, but when {@code preferredInput} is non-null
     * (the built-in phone microphone, from the mic_phone_mic setting)
     * it is set as the AudioRecord's preferred input device before
     * capture starts, so a plugged-in earphone/headset mic does not
     * become the recording source. This is a preference, not a
     * guarantee: Android may still route elsewhere (notably Bluetooth
     * SCO), so callers surface the actual route via
     * {@link Handle#micRouteSuffix()} once recording has started.
     * Preferred-device routing needs API 28+; on older devices, or if
     * setting it throws, capture proceeds with the system default.
     */
    static Handle open(AudioDeviceInfo preferredInput) {
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
            if (preferredInput != null
                    && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                try {
                    rec.setPreferredDevice(preferredInput);
                } catch (Exception ignored) {
                    // A failed preference must never fail capture;
                    // the routed-device label reports what was used.
                }
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

    /**
     * Peak of a little-endian PCM-16 block: the maximum |sample| over
     * EVERY channel independently. (The old version averaged the
     * channels frame-by-frame first, so one loud or clipped channel
     * was diluted -- or partly cancelled by an out-of-phase partner --
     * and the soundcheck clip watch could miss clipping that was
     * plainly audible on that channel.)
     */
    static float blockPeak(byte[] buf, int n, int channels) {
        int samples = n / 2;
        float peak = 0f;
        for (int k = 0; k < samples; k++) {
            int i = k * 2;
            int s = (buf[i] & 0xff) | (buf[i + 1] << 8);
            float m = Math.abs(s / 32768f);
            if (m > peak) {
                peak = m;
            }
        }
        return peak;
    }

    private MicCapture() {
    }
}
