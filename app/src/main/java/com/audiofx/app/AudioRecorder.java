package com.audiofx.app;

import android.media.AudioRecord;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Microphone capture to 16-bit PCM WAV, with optional AGC / noise
 * suppression attached to the capture session.
 */
public class AudioRecorder {

    private MicCapture.Handle cap;
    private Thread worker;
    private volatile boolean running;
    private File outFile;
    private long dataBytes;
    private long startMs;
    private String activeLabel = "";
    private boolean wantAgc;
    private boolean wantNs;
    private final CaptureFx fx = new CaptureFx();

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

    /** Set before start(): whether to enable AGC / noise suppression. */
    public synchronized void setPreprocessing(boolean agcOn, boolean nsOn) {
        wantAgc = agcOn;
        wantNs = nsOn;
    }

    /** True when the AGC effect actually attached to the capture session. */
    public synchronized boolean agcActive() {
        return fx.agcActive();
    }

    /** True when the noise suppressor actually attached to the capture session. */
    public synchronized boolean nsActive() {
        return fx.nsActive();
    }

    /**
     * Start capturing to {@code outFile}. Returns false when no capture
     * config works on this device.
     */
    public synchronized boolean start(File outFile) {
        if (running) {
            return true;
        }
        MicCapture.Handle h = MicCapture.open();
        if (h == null) {
            return false;
        }
        try {
            WavWriter.writeHeader(outFile, h.sampleRate, h.channels);
        } catch (IOException e) {
            h.rec.release();
            return false;
        }
        try {
            h.rec.startRecording();
        } catch (Exception e) {
            h.rec.release();
            outFile.delete();
            return false;
        }
        if (h.rec.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
            try {
                h.rec.stop();
            } catch (Exception ignored) {
            }
            h.rec.release();
            outFile.delete();
            return false;
        }
        fx.attach(h.rec.getAudioSessionId(), wantAgc, wantNs);
        cap = h;
        this.outFile = outFile;
        dataBytes = 0;
        startMs = System.currentTimeMillis();
        activeLabel = h.label;
        running = true;
        final AudioRecord rec = h.rec;
        worker = new Thread(() -> {
            byte[] buf = new byte[8192];
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
        if (cap != null) {
            try {
                cap.rec.stop();
            } catch (Exception ignored) {
            }
            cap.rec.release();
            cap = null;
        }
        fx.release();
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
            WavWriter.finalizeHeader(f, dataBytes);
        } catch (IOException e) {
            f.delete();
            return null;
        }
        return f;
    }
}
