package com.audiofx.app;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

/** 16-bit PCM WAV header write/finalize, shared by the recorders. */
final class WavWriter {

    static void writeHeader(File f, int sampleRate, int channels) throws IOException {
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

    static void finalizeHeader(File f, long dataBytes) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(f, "rw")) {
            raf.seek(4);
            raf.writeInt(Integer.reverseBytes((int) (36 + dataBytes)));
            raf.seek(40);
            raf.writeInt(Integer.reverseBytes((int) dataBytes));
        }
    }

    private WavWriter() {
    }
}
