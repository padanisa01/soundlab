package com.audiofx.app;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Build;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

/**
 * Plays back a 16-bit PCM WAV file for overdub monitoring: the
 * previously recorded tracks play through the speaker/headphones while
 * a new take is captured. The WAV header is parsed directly (no
 * MediaPlayer), so playback starts at an exact frame offset.
 *
 * All state changes are synchronized; the playback thread only touches
 * volatile flags and its own local resource copies, so pause()/stop()
 * can join it without deadlock. AudioTrack.release() and
 * RandomAccessFile.close() are both idempotent, so a release racing
 * the thread's own cleanup is harmless.
 */
public class OverdubPlayer {

    private AudioTrack track;
    private RandomAccessFile raf;
    private Thread worker;
    private volatile boolean playing;
    private volatile long playedFrames;

    /** Parsed WAV format info needed to configure playback. */
    private static final class WavInfo {
        int sampleRate;
        int channels;
        long dataStart;
        long dataBytes;
    }

    public synchronized boolean isPlaying() {
        return playing;
    }

    /** Frames written to the AudioTrack since start(). */
    public long framesPlayed() {
        return playedFrames;
    }

    /**
     * Parse a WAV file header, locating the "data" chunk. Accepts only
     * 16-bit PCM mono/stereo; returns null otherwise.
     */
    private static WavInfo parseWav(File f) {
        try (RandomAccessFile r = new RandomAccessFile(f, "r")) {
            if (r.length() < 44) {
                return null;
            }
            byte[] head = new byte[12];
            r.readFully(head);
            if (head[0] != 'R' || head[1] != 'I' || head[2] != 'F' || head[3] != 'F') {
                return null;
            }
            if (head[8] != 'W' || head[9] != 'A' || head[10] != 'V' || head[11] != 'E') {
                return null;
            }
            int audioFormat = -1;
            int channels = -1;
            int sampleRate = -1;
            int bits = -1;
            long dataStart = -1;
            long dataBytes = -1;
            byte[] chunk = new byte[8];
            while (r.read(chunk) == 8) {
                boolean isFmt = chunk[0] == 'f' && chunk[1] == 'm'
                        && chunk[2] == 't' && chunk[3] == ' ';
                boolean isData = chunk[0] == 'd' && chunk[1] == 'a'
                        && chunk[2] == 't' && chunk[3] == 'a';
                long size = (chunk[4] & 0xffL) | ((chunk[5] & 0xffL) << 8)
                        | ((chunk[6] & 0xffL) << 16) | ((chunk[7] & 0xffL) << 24);
                long pos = r.getFilePointer();
                if (isFmt) {
                    byte[] fmt = new byte[(int) Math.min(size, 16)];
                    r.readFully(fmt);
                    audioFormat = (fmt[0] & 0xff) | ((fmt[1] & 0xff) << 8);
                    channels = (fmt[2] & 0xff) | ((fmt[3] & 0xff) << 8);
                    sampleRate = (fmt[4] & 0xff) | ((fmt[5] & 0xff) << 8)
                            | ((fmt[6] & 0xff) << 16) | ((fmt[7] & 0xff) << 24);
                    bits = (fmt[14] & 0xff) | ((fmt[15] & 0xff) << 8);
                } else if (isData) {
                    dataStart = pos;
                    dataBytes = size;
                    break;
                }
                r.seek(pos + size + (size & 1));
            }
            if (audioFormat != 1 || bits != 16
                    || (channels != 1 && channels != 2)
                    || sampleRate <= 0 || dataStart < 0 || dataBytes <= 0) {
                return null;
            }
            WavInfo info = new WavInfo();
            info.sampleRate = sampleRate;
            info.channels = channels;
            info.dataStart = dataStart;
            info.dataBytes = dataBytes;
            return info;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Start playing {@code wavFile} from {@code startFrame}. Stops any
     * current playback first. Returns false when the file is unusable
     * or {@code startFrame} is at/past the end of the data.
     */
    public synchronized boolean start(File wavFile, long startFrame) {
        haltLocked();
        if (wavFile == null) {
            return false;
        }
        WavInfo info = parseWav(wavFile);
        if (info == null) {
            return false;
        }
        long totalFrames = info.dataBytes / (info.channels * 2L);
        long from = startFrame;
        if (from < 0) {
            from = 0;
        } else if (from > totalFrames) {
            from = totalFrames;
        }
        if (from >= totalFrames) {
            return false;
        }
        int channelMask = info.channels == 2
                ? AudioFormat.CHANNEL_OUT_STEREO
                : AudioFormat.CHANNEL_OUT_MONO;
        int minBuf = AudioTrack.getMinBufferSize(info.sampleRate, channelMask,
                AudioFormat.ENCODING_PCM_16BIT);
        if (minBuf <= 0) {
            return false;
        }
        AudioTrack.Builder tb = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setSampleRate(info.sampleRate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(channelMask)
                        .build())
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(Math.max(minBuf * 2, 8192));
        if (Build.VERSION.SDK_INT >= 26) {
            tb.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY);
        }
        final AudioTrack t;
        try {
            t = tb.build();
        } catch (Exception e) {
            return false;
        }
        if (t.getState() != AudioTrack.STATE_INITIALIZED) {
            t.release();
            return false;
        }
        final RandomAccessFile r;
        try {
            r = new RandomAccessFile(wavFile, "r");
            r.seek(info.dataStart + from * info.channels * 2L);
        } catch (IOException e) {
            t.release();
            return false;
        }
        track = t;
        raf = r;
        playing = true;
        playedFrames = 0;
        final int frameBytes = info.channels * 2;
        worker = new Thread(() -> {
            byte[] buf = new byte[16384];
            try {
                t.play();
                while (playing) {
                    final int n;
                    try {
                        n = r.read(buf);
                    } catch (IOException e) {
                        break;
                    }
                    if (n <= 0) {
                        break;
                    }
                    int off = 0;
                    while (off < n) {
                        final int w;
                        try {
                            w = t.write(buf, off, n - off);
                        } catch (Exception e) {
                            break;
                        }
                        if (w < 0) {
                            break;
                        }
                        if (w == 0) {
                            if (!playing) {
                                break;
                            }
                            continue;
                        }
                        off += w;
                    }
                    playedFrames += off / frameBytes;
                    if (off < n) {
                        break;
                    }
                }
            } finally {
                // Release our own copies without taking the monitor
                // lock, so a concurrent pause()/stop() joining us
                // cannot deadlock. Double release is safe.
                playing = false;
                try {
                    t.stop();
                } catch (Exception ignored) {
                }
                t.release();
                try {
                    r.close();
                } catch (IOException ignored) {
                }
            }
        }, "OverdubPlayer");
        worker.start();
        return true;
    }

    /** Halt playback immediately; safe to call when idle. */
    public synchronized void pause() {
        haltLocked();
    }

    /** Halt playback and release resources; safe to call when idle. */
    public synchronized void stop() {
        haltLocked();
    }

    /** Caller must hold the monitor lock. */
    private void haltLocked() {
        playing = false;
        Thread t = worker;
        worker = null;
        if (t != null) {
            try {
                t.join(2000);
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
        if (raf != null) {
            try {
                raf.close();
            } catch (IOException ignored) {
            }
            raf = null;
        }
    }
}
