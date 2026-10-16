package com.audiofx.app;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;

/**
 * Microphone capture to 16-bit PCM WAV.
 *
 * Tries capture configs in order, highest fidelity first:
 * UNPROCESSED @ 48 kHz stereo (raw — no AGC, noise suppression or
 * other preprocessing), then VOICE_RECOGNITION @ 48 kHz stereo, then
 * MIC @ 44.1 kHz stereo, finally MIC @ 44.1 kHz mono.
 *
 * Stereo is how Android engages multiple built-in microphones; the
 * framework does not let apps address individual mics.
 */
public class AudioRecorder {

    private static final int[][] CONFIGS = {
            // {audioSource, sampleRate, channelConfig}
            {MediaRecorder.AudioSource.UNPROCESSED, 48000, AudioFormat.CHANNEL_IN_STEREO},
            {MediaRecorder.AudioSource.VOICE_RECOGNITION, 48000, AudioFormat.CHANNEL_IN_STEREO},
            {MediaRecorder.AudioSource.MIC, 44100, AudioFormat.CHANNEL_IN_STEREO},
            {MediaRecorder.AudioSource.MIC, 44100, AudioFormat.CHANNEL_IN_MONO},
    };

    private AudioRecord recorder;
    private Thread worker;
    private volatile boolean running;
    private File outFile;
    private long dataBytes;
    private long startMs;
    private String activeLabel = "";

    public synchronized boolean isRecording() {
        return running;
    }

    /** Human-readable capture config, e.g. "stereo 48 kHz UNPROCESSED". */
    public synchronized String activeLabel() {
        return activeLabel;
    }

    public double elapsedSec() {
        if (!running) {
            return 0;
        }
        return (System.currentTimeMillis() - startMs) / 1000.0;
    }

    /**
     * Start capturing to {@code outFile}. Returns false when no capture
     * config works on this device.
     */
    public synchronized boolean start(File outFile) {
        if (running) {
            return true;
        }
        for (int[] c : CONFIGS) {
            if (tryStart(c[0], c[1], c[2], outFile)) {
                return true;
            }
        }
        return false;
    }

    private boolean tryStart(int source, int sampleRate, int channelConfig, File outFile) {
        int minBuf;
        try {
            minBuf = AudioRecord.getMinBufferSize(sampleRate, channelConfig,
                    AudioFormat.ENCODING_PCM_16BIT);
        } catch (Exception e) {
            return false;
        }
        if (minBuf <= 0) {
            return false;
        }
        final AudioRecord rec;
        try {
            rec = new AudioRecord(source, sampleRate, channelConfig,
                    AudioFormat.ENCODING_PCM_16BIT, minBuf * 4);
        } catch (Exception e) {
            return false;
        }
        if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
            rec.release();
            return false;
        }
        final int channels = channelConfig == AudioFormat.CHANNEL_IN_STEREO ? 2 : 1;
        try {
            writeWavHeader(outFile, sampleRate, channels);
        } catch (IOException e) {
            rec.release();
            return false;
        }
        try {
            rec.startRecording();
        } catch (Exception e) {
            rec.release();
            outFile.delete();
            return false;
        }
        if (rec.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
            try {
                rec.stop();
            } catch (Exception ignored) {
            }
            rec.release();
            outFile.delete();
            return false;
        }
        recorder = rec;
        this.outFile = outFile;
        dataBytes = 0;
        startMs = System.currentTimeMillis();
        activeLabel = (channels == 2 ? "stereo" : "mono") + " "
                + (sampleRate / 1000) + " kHz " + sourceName(source);
        running = true;
        final int bufSize = minBuf * 2;
        worker = new Thread(() -> {
            byte[] buf = new byte[bufSize];
            try (FileOutputStream fos = new FileOutputStream(outFile, true)) {
                while (running) {
                    int n = rec.read(buf, 0, buf.length);
                    if (n > 0) {
                        fos.write(buf, 0, n);
                        dataBytes += n;
                    } else if (n < 0) {
                        break;
                    }
                }
            } catch (IOException ignored) {
            }
        }, "AudioRecorder");
        worker.start();
        return true;
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
     * Stop capturing and finalize the WAV header. Returns the finished
     * file, or null when nothing usable was recorded.
     */
    public synchronized File stop() {
        if (!running) {
            return null;
        }
        running = false;
        Thread t = worker;
        worker = null;
        if (t != null) {
            try {
                t.join(3000);
            } catch (InterruptedException ignored) {
            }
        }
        if (recorder != null) {
            try {
                recorder.stop();
            } catch (Exception ignored) {
            }
            recorder.release();
            recorder = null;
        }
        File f = outFile;
        outFile = null;
        activeLabel = "";
        if (f == null || dataBytes <= 0) {
            if (f != null) {
                f.delete();
            }
            return null;
        }
        try {
            finalizeWavHeader(f, dataBytes);
        } catch (IOException e) {
            f.delete();
            return null;
        }
        return f;
    }

    // ---- 16-bit PCM WAV ----

    private static void writeWavHeader(File f, int sampleRate, int channels) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(f, "rw")) {
            raf.setLength(0);
            raf.writeBytes("RIFF");
            raf.writeInt(Integer.reverseBytes(36)); // chunk size placeholder
            raf.writeBytes("WAVE");
            raf.writeBytes("fmt ");
            raf.writeInt(Integer.reverseBytes(16));
            raf.writeShort(Short.reverseBytes((short) 1)); // PCM
            raf.writeShort(Short.reverseBytes((short) channels));
            raf.writeInt(Integer.reverseBytes(sampleRate));
            raf.writeInt(Integer.reverseBytes(sampleRate * channels * 2)); // byte rate
            raf.writeShort(Short.reverseBytes((short) (channels * 2))); // block align
            raf.writeShort(Short.reverseBytes((short) 16)); // bits per sample
            raf.writeBytes("data");
            raf.writeInt(Integer.reverseBytes(0)); // data size placeholder
        }
    }

    private static void finalizeWavHeader(File f, long dataBytes) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(f, "rw")) {
            raf.seek(4);
            raf.writeInt(Integer.reverseBytes((int) (36 + dataBytes)));
            raf.seek(40);
            raf.writeInt(Integer.reverseBytes((int) dataBytes));
        }
    }
}
