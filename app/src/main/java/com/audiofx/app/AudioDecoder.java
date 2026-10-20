package com.audiofx.app;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;

/**
 * Decodes compressed audio (MP3 and friends) to 16-bit PCM WAV using
 * the platform MediaCodec. The DSP pipeline stays WAV-only; this is
 * the single entry point for anything that is not already WAV.
 */
public final class AudioDecoder {

    private AudioDecoder() {
    }

    /** Result of a decode: the WAV was written, and peaks were accumulated
     * from the PCM as it arrived (zero extra passes). Peaks are normalized
     * 0..1 and cover the whole file. */
    public static final class DecodeResult {
        public final float[] peaks;
        public final double durationSec;
        public final int sampleRate;

        DecodeResult(float[] peaks, double durationSec, int sampleRate) {
            this.peaks = peaks;
            this.durationSec = durationSec;
            this.sampleRate = sampleRate;
        }
    }

    /**
     * Decodes {@code uri} to 16-bit PCM WAV at {@code outWav}.
     * Throws on missing audio track or decode failure.
     */
    public static DecodeResult decodeToWav(Context context, Uri uri,
            File outWav, int numPeaks) throws Exception {
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(context, uri, null);
            int track = -1;
            MediaFormat format = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat f = extractor.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    track = i;
                    format = f;
                    break;
                }
            }
            if (track < 0 || format == null) {
                throw new Exception("No audio track found");
            }
            int sampleRate = format.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                    ? format.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 44100;
            int channels = format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                    ? format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 2;

            MediaCodec codec = MediaCodec.createDecoderByType(
                    format.getString(MediaFormat.KEY_MIME));
            long pcmBytes = 0;
            // Peak accumulation: per-chunk maxima, bucketed at the end.
            // The decode sees every PCM sample, so the waveform comes for
            // free with zero extra passes.
            java.util.ArrayList<Float> chunkPeaks =
                    new java.util.ArrayList<>();
            try {
                codec.configure(format, null, null, 0);
                codec.start();
                extractor.selectTrack(track);

                RandomAccessFile raf = new RandomAccessFile(outWav, "rw");
                try {
                    raf.write(new byte[44]); // WAV header placeholder
                    boolean sawInputEos = false;
                    boolean sawOutputEos = false;
                    MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                    while (!sawOutputEos) {
                        if (!sawInputEos) {
                            int inIdx = codec.dequeueInputBuffer(10000);
                            if (inIdx >= 0) {
                                ByteBuffer inBuf = codec.getInputBuffer(inIdx);
                                int n = extractor.readSampleData(inBuf, 0);
                                if (n < 0) {
                                    codec.queueInputBuffer(inIdx, 0, 0, 0,
                                            MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                    sawInputEos = true;
                                } else {
                                    codec.queueInputBuffer(inIdx, 0, n,
                                            extractor.getSampleTime(), 0);
                                    extractor.advance();
                                }
                            }
                        }
                        int outIdx = codec.dequeueOutputBuffer(info, 10000);
                        if (outIdx >= 0) {
                            ByteBuffer outBuf = codec.getOutputBuffer(outIdx);
                            if (info.size > 0 && outBuf != null) {
                                byte[] chunk = new byte[info.size];
                                outBuf.get(chunk);
                                raf.write(chunk);
                                pcmBytes += info.size;
                                chunkPeaks.add(chunkPeak(chunk, channels));
                            }
                            codec.releaseOutputBuffer(outIdx, false);
                            if ((info.flags
                                    & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                sawOutputEos = true;
                            }
                        } else if (outIdx
                                == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            MediaFormat outFormat = codec.getOutputFormat();
                            if (outFormat.containsKey(
                                    MediaFormat.KEY_SAMPLE_RATE)) {
                                sampleRate = outFormat.getInteger(
                                        MediaFormat.KEY_SAMPLE_RATE);
                            }
                            if (outFormat.containsKey(
                                    MediaFormat.KEY_CHANNEL_COUNT)) {
                                channels = outFormat.getInteger(
                                        MediaFormat.KEY_CHANNEL_COUNT);
                            }
                        }
                    }
                    if (pcmBytes == 0) {
                        throw new Exception("Decoded zero audio samples");
                    }
                    raf.seek(0);
                    writeWavHeader(raf, sampleRate, channels, pcmBytes);
                } finally {
                    raf.close();
                }
            } finally {
                try {
                    codec.stop();
                } catch (Exception ignored) {
                }
                codec.release();
            }
            long totalFrames = pcmBytes / Math.max(1, channels * 2);
            double durationSec = sampleRate > 0
                    ? (double) totalFrames / sampleRate : 0;
            return new DecodeResult(bucketPeaks(chunkPeaks, numPeaks),
                    durationSec, sampleRate);
        } finally {
            extractor.release();
        }
    }

    /** Max abs 16-bit sample in a PCM chunk, across channels, 0..1. */
    private static float chunkPeak(byte[] chunk, int channels) {
        float peak = 0f;
        int n = chunk.length / 2;
        for (int i = 0; i < n; i++) {
            int s = (chunk[2 * i] & 0xff) | (chunk[2 * i + 1] << 8);
            float a = Math.abs(s / 32768f);
            if (a > peak) {
                peak = a;
            }
        }
        return peak;
    }

    /** Reduce per-chunk peaks to numPeaks buckets (max), normalized 0..1. */
    private static float[] bucketPeaks(
            java.util.ArrayList<Float> chunkPeaks, int numPeaks) {
        float[] out = new float[Math.max(1, numPeaks)];
        int n = chunkPeaks.size();
        if (n == 0) {
            return out;
        }
        for (int b = 0; b < out.length; b++) {
            int from = (int) ((long) b * n / out.length);
            int to = (int) ((long) (b + 1) * n / out.length);
            float m = 0f;
            for (int i = from; i < to && i < n; i++) {
                float v = chunkPeaks.get(i);
                if (v > m) {
                    m = v;
                }
            }
            out[b] = m;
        }
        float top = 0f;
        for (float v : out) {
            if (v > top) {
                top = v;
            }
        }
        if (top > 0) {
            for (int b = 0; b < out.length; b++) {
                out[b] /= top;
            }
        }
        return out;
    }

    private static void writeWavHeader(RandomAccessFile raf, int sampleRate,
            int channels, long pcmBytes) throws IOException {
        int byteRate = sampleRate * channels * 2;
        raf.writeBytes("RIFF");
        writeLeInt(raf, (int) (36 + pcmBytes));
        raf.writeBytes("WAVE");
        raf.writeBytes("fmt ");
        writeLeInt(raf, 16);
        writeLeShort(raf, 1); // PCM
        writeLeShort(raf, channels);
        writeLeInt(raf, sampleRate);
        writeLeInt(raf, byteRate);
        writeLeShort(raf, channels * 2);
        writeLeShort(raf, 16);
        raf.writeBytes("data");
        writeLeInt(raf, (int) pcmBytes);
    }

    private static void writeLeInt(RandomAccessFile raf, int v)
            throws IOException {
        raf.write(v & 0xFF);
        raf.write((v >> 8) & 0xFF);
        raf.write((v >> 16) & 0xFF);
        raf.write((v >> 24) & 0xFF);
    }

    private static void writeLeShort(RandomAccessFile raf, int v)
            throws IOException {
        raf.write(v & 0xFF);
        raf.write((v >> 8) & 0xFF);
    }
}
