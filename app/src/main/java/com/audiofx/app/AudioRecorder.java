package com.audiofx.app;

import android.media.AudioRecord;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Microphone capture to 16-bit PCM WAV, with optional AGC / noise
 * suppression attached to the capture session.
 *
 * Supports pause/resume: pause() halts capture but keeps the session
 * and the file open; resume() continues appending to the same file.
 * isRecording() stays true while paused; isPaused() distinguishes.
 */
public class AudioRecorder {

    private MicCapture.Handle cap;
    private Thread worker;
    private volatile boolean running;
    private volatile boolean paused;
    private File outFile;
    private long dataBytes;
    private long startMs;
    private volatile long elapsedBaseMs;
    private String activeLabel = "";
    private boolean wantAgc;
    private boolean wantNs;
    private boolean wantLimiter;
    private volatile MicCapture.BlockListener blockListener;
    private final CaptureFx fx = new CaptureFx();

    public synchronized boolean isRecording() {
        return running;
    }

    /** True while a pause is in effect; the session is still active. */
    public synchronized boolean isPaused() {
        return paused;
    }

    /** Frames captured so far; 0 when no session is active. */
    public synchronized long dataFrames() {
        if (cap == null) {
            return 0;
        }
        return dataBytes / (cap.channels * 2L);
    }

    /** Human-readable capture config, e.g. "stereo 48 kHz UNPROCESSED". */
    public synchronized String activeLabel() {
        return activeLabel;
    }

    /** Elapsed capture time; frozen while paused, 0 when idle. */
    public double elapsedSec() {
        if (!running) {
            return 0;
        }
        if (paused) {
            return elapsedBaseMs / 1000.0;
        }
        return (elapsedBaseMs + System.currentTimeMillis() - startMs) / 1000.0;
    }

    /** Set before start(): live peak feed for the recording waveform. */
    public void setBlockListener(MicCapture.BlockListener listener) {
        blockListener = listener;
    }

    /** Set before start(): whether to enable AGC / noise suppression / limiter. */
    public synchronized void setPreprocessing(boolean agcOn, boolean nsOn,
                                             boolean limiterOn) {
        wantAgc = agcOn;
        wantNs = nsOn;
        wantLimiter = limiterOn;
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
        elapsedBaseMs = 0;
        paused = false;
        activeLabel = h.label;
        running = true;
        spawnWorker();
        return true;
    }

    /**
     * Shared capture worker: reads blocks from the capture session and
     * appends them to the output file. Used by start() and resume();
     * the file is always opened in append mode and the WAV header is
     * only ever written once, by start().
     */
    private void spawnWorker() {
        final AudioRecord rec = cap.rec;
        final int channels = cap.channels;
        final LiveLimiter limiter =
                wantLimiter ? new LiveLimiter(cap.sampleRate, channels) : null;
        worker = new Thread(() -> {
            byte[] buf = new byte[8192];
            try (FileOutputStream fos = new FileOutputStream(outFile, true)) {
                while (running && !paused) {
                    int n = rec.read(buf, 0, buf.length);
                    if (n > 0) {
                        if (limiter != null) {
                            limiter.processPcm16(buf, n);
                        }
                        fos.write(buf, 0, n);
                        dataBytes += n;
                        MicCapture.BlockListener bl = blockListener;
                        if (bl != null) {
                            bl.onBlockPeak(MicCapture.blockPeak(buf, n, channels));
                        }
                    } else if (n < 0) {
                        break;
                    }
                }
            } catch (IOException ignored) {
            }
        }, "AudioRecorder");
        worker.start();
    }

    /**
     * Halt capture but keep the session and the file. The worker thread
     * is stopped and joined (its stream is closed), the AudioRecord is
     * stopped but NOT released so resume() can reuse it, and the
     * elapsed clock freezes. No-op when not recording or already
     * paused.
     */
    public synchronized void pause() {
        if (!running || paused) {
            return;
        }
        paused = true;
        elapsedBaseMs += System.currentTimeMillis() - startMs;
        if (cap != null) {
            try {
                cap.rec.stop();
            } catch (Exception ignored) {
            }
        }
        Thread t = worker;
        worker = null;
        if (t != null) {
            try {
                t.join(3000);
            } catch (InterruptedException ignored) {
            }
        }
    }

    /**
     * Resume a paused session, appending to the same file. Returns
     * false when not paused or the capture session cannot restart.
     */
    public synchronized boolean resume() {
        if (!running || !paused || cap == null) {
            return false;
        }
        try {
            cap.rec.startRecording();
        } catch (Exception e) {
            return false;
        }
        if (cap.rec.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
            return false;
        }
        paused = false;
        startMs = System.currentTimeMillis();
        spawnWorker();
        return true;
    }

    /**
     * Stop capturing and finalize the WAV header. Safe to call while
     * paused. Returns the finished file, or null when nothing usable
     * was recorded.
     */
    public synchronized File stop() {
        if (!running) {
            return null;
        }
        running = false;
        paused = false;
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
        elapsedBaseMs = 0;
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
