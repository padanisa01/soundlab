package com.audiofx.app;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.audiofx.AcousticEchoCanceler;
import android.os.Build;

import com.chaquo.python.PyObject;
import com.chaquo.python.Python;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

/**
 * Live effect mode: microphone -> Python live chain (Warmth -> Reverb ->
 * Echo, params snapshotted at start) -> monitor output, with the wet
 * signal written to a WAV take. The take keeps the capture channel
 * count (stereo when the device captures stereo).
 *
 * Monitoring latency is one block plus device buffering (roughly
 * 0.1-0.3 s); this is a creative monitor, not a pro-audio path.
 * Headphones are strongly recommended: the mic hears the speaker.
 *
 * Supports pause/resume: pause() halts capture and the monitor but
 * keeps the session and the take file open (the RandomAccessFile keeps
 * its position); resume() continues appending to the same take.
 * isRecording() stays true while paused; isPaused() distinguishes.
 */
public class LiveFxMonitor {

    private static final int BLOCK_SAMPLES = 2048;

    private MicCapture.Handle cap;
    private AudioTrack track;
    private AcousticEchoCanceler aec;
    private final CaptureFx fx = new CaptureFx();
    private Thread worker;
    private volatile boolean running;
    private volatile boolean paused;
    private RandomAccessFile wav;
    private File outFile;
    private long dataBytes;
    private long startMs;
    private volatile long elapsedBaseMs;
    private String activeLabel = "";
    private PyObject chain;
    private volatile MicCapture.BlockListener blockListener;

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

    public synchronized boolean agcActive() {
        return fx.agcActive();
    }

    public synchronized boolean nsActive() {
        return fx.nsActive();
    }

    /** Set before start(): live peak feed for the recording waveform. */
    public void setBlockListener(MicCapture.BlockListener listener) {
        blockListener = listener;
    }

    /**
     * Start live-FX capture. Returns false when capture, the Python
     * chain, or monitor output cannot start on this device. The caller
     * must have started Chaquopy Python first.
     */
    public synchronized boolean start(File outFile, double warmth, String reverbPreset,
                                      double echo, boolean wantAgc, boolean wantNs,
                                      boolean wantLimiter) {
        if (running) {
            return true;
        }
        MicCapture.Handle h = MicCapture.open();
        if (h == null) {
            return false;
        }
        final PyObject newChain;
        final boolean outStereo = h.channels == 2;
        final int outChannels = outStereo ? 2 : 1;
        try {
            newChain = Python.getInstance().getModule("audiofx.android_bridge")
                    .callAttr("live_chain_create", warmth, reverbPreset, echo,
                            h.sampleRate, BLOCK_SAMPLES, h.channels, outStereo);
        } catch (Exception e) {
            h.rec.release();
            return false;
        }
        try {
            WavWriter.writeHeader(outFile, h.sampleRate, outChannels);
        } catch (IOException e) {
            h.rec.release();
            return false;
        }
        AudioTrack.Builder tb = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setSampleRate(h.sampleRate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(outStereo
                                ? AudioFormat.CHANNEL_OUT_STEREO
                                : AudioFormat.CHANNEL_OUT_MONO)
                        .build())
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(BLOCK_SAMPLES * outChannels * 2 * 4);
        if (Build.VERSION.SDK_INT >= 26) {
            tb.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY);
        }
        final AudioTrack newTrack;
        try {
            newTrack = tb.build();
        } catch (Exception e) {
            h.rec.release();
            outFile.delete();
            return false;
        }
        if (newTrack.getState() != AudioTrack.STATE_INITIALIZED) {
            newTrack.release();
            h.rec.release();
            outFile.delete();
            return false;
        }
        try {
            h.rec.startRecording();
        } catch (Exception e) {
            newTrack.release();
            h.rec.release();
            outFile.delete();
            return false;
        }
        if (h.rec.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
            try {
                h.rec.stop();
            } catch (Exception ignored) {
            }
            newTrack.release();
            h.rec.release();
            outFile.delete();
            return false;
        }
        fx.attach(h.rec.getAudioSessionId(), wantAgc, wantNs);
        // Best-effort echo cancellation against speaker feedback; the
        // real fix is headphones.
        AcousticEchoCanceler newAec = null;
        if (AcousticEchoCanceler.isAvailable()) {
            try {
                newAec = AcousticEchoCanceler.create(h.rec.getAudioSessionId());
                if (newAec != null) {
                    newAec.setEnabled(true);
                }
            } catch (Exception ignored) {
                newAec = null;
            }
        }
        final RandomAccessFile rw;
        try {
            rw = new RandomAccessFile(outFile, "rw");
            rw.seek(44);
        } catch (IOException e) {
            if (newAec != null) {
                newAec.release();
            }
            fx.release();
            try {
                h.rec.stop();
            } catch (Exception ignored) {
            }
            newTrack.release();
            h.rec.release();
            outFile.delete();
            return false;
        }
        cap = h;
        track = newTrack;
        aec = newAec;
        chain = newChain;
        wav = rw;
        this.outFile = outFile;
        dataBytes = 0;
        startMs = System.currentTimeMillis();
        elapsedBaseMs = 0;
        paused = false;
        activeLabel = h.label + " live FX";
        running = true;
        spawnWorker();
        return true;
    }

    /**
     * Shared capture worker: reads mic blocks, runs them through the
     * Python live chain, appends the wet signal to the take file and
     * plays it on the monitor track. Used by start() and resume().
     */
    private void spawnWorker() {
        final AudioRecord rec = cap.rec;
        final RandomAccessFile rw = wav;
        final AudioTrack t = track;
        final PyObject c = chain;
        final int channels = cap.channels;
        final int outCh = channels == 2 ? 2 : 1;
        final int blockBytes = BLOCK_SAMPLES * channels * 2;
        // Safety limiter on the mic input, before the FX chain, so hot
        // signals cannot clip the wet take.
        final LiveLimiter limiter =
                wantLimiter ? new LiveLimiter(cap.sampleRate, channels) : null;
        worker = new Thread(() -> {
            byte[] inBuf = new byte[blockBytes];
            int filled = 0;
            try {
                t.play();
                while (running && !paused) {
                    int n = rec.read(inBuf, filled, blockBytes - filled);
                    if (n < 0) {
                        break;
                    }
                    if (n == 0) {
                        continue;
                    }
                    filled += n;
                    if (filled < blockBytes) {
                        continue;
                    }
                    filled = 0;
                    if (limiter != null) {
                        limiter.processPcm16(inBuf, blockBytes);
                    }
                    final byte[] wet;
                    try {
                        wet = c.callAttr("process_pcm_block", inBuf)
                                .toJava(byte[].class);
                    } catch (Exception e) {
                        break;
                    }
                    // Recheck after the Python call: a pause() or stop()
                    // may have landed while the block was processing.
                    if (!running || paused) {
                        break;
                    }
                    rw.write(wet);
                    dataBytes += wet.length;
                    MicCapture.BlockListener bl = blockListener;
                    if (bl != null) {
                        bl.onBlockPeak(MicCapture.blockPeak(wet, wet.length, outCh));
                    }
                    t.write(wet, 0, wet.length);
                }
            } catch (IOException ignored) {
            }
        }, "LiveFxMonitor");
        worker.start();
    }

    /**
     * Halt capture and the monitor but keep the session and the take
     * file. The worker thread is stopped and joined, the AudioRecord is
     * stopped but NOT released, the monitor track is paused and flushed
     * (stale buffered audio is discarded), and the RandomAccessFile
     * stays open at its current position for resume(). The elapsed
     * clock freezes. No-op when not recording or already paused.
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
                t.join(4000);
            } catch (InterruptedException ignored) {
            }
        }
        if (track != null) {
            try {
                track.pause();
            } catch (Exception ignored) {
            }
            try {
                track.flush();
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * Resume a paused session, appending to the same take file and
     * restarting the monitor. Returns false when not paused or the
     * capture session cannot restart.
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
     * Stop capture and monitoring, finalize the WAV header. Safe to
     * call while paused. Returns the finished wet take, or null when
     * nothing usable was recorded.
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
                t.join(4000);
            } catch (InterruptedException ignored) {
            }
        }
        if (track != null) {
            try {
                track.stop();
            } catch (Exception ignored) {
            }
            track.release();
            track = null;
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
        if (aec != null) {
            try {
                aec.release();
            } catch (Exception ignored) {
            }
            aec = null;
        }
        if (wav != null) {
            try {
                wav.close();
            } catch (IOException ignored) {
            }
            wav = null;
        }
        File f = outFile;
        outFile = null;
        chain = null;
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
