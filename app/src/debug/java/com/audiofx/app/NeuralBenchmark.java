package com.audiofx.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Build;
import android.widget.TextView;

import org.tensorflow.lite.Interpreter;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * On-device benchmark harness for the neural notation engine
 * (TFLite spike, branch dsp/tflite-spike). DEBUG BUILDS ONLY: this
 * class, the CREPE-tiny model asset and the TFLite runtime all live
 * in the debug source set / debugImplementation, so release builds
 * contain none of it. MainActivity reaches it reflectively.
 *
 * Times the TFLite interpreter over the frames of one WAV file
 * (1024-sample windows, 10 ms hop at 16 kHz, per-frame normalised,
 * exactly the framing the notation engine would use) and reports
 * ms/frame plus the projected time for a 3-minute song against the
 * PRD's 90 s gate. Decode + resample time is reported separately;
 * only the interpreter loop is the gate number.
 */
public final class NeuralBenchmark {

    private static final int SAMPLE_RATE = 16000;
    private static final int WINDOW = 1024;
    private static final int HOP = 160; // 10 ms at 16 kHz
    private static final int THREADS = 4;
    private static final int WARMUP_FRAMES = 100;
    private static final double GATE_SECONDS_3MIN = 90.0;

    private NeuralBenchmark() {
    }

    public static void run(final Activity activity, final String wavPath) {
        final TextView progress = new TextView(activity);
        progress.setText("Preparing…");
        progress.setPadding(48, 32, 48, 8);
        final AtomicBoolean cancelled = new AtomicBoolean(false);
        final AlertDialog progressDialog = new AlertDialog.Builder(activity)
                .setTitle("Neural benchmark")
                .setView(progress)
                .setNegativeButton("Cancel",
                        (d, w) -> cancelled.set(true))
                .setCancelable(false)
                .create();
        progressDialog.show();

        new Thread(() -> {
            String result;
            try {
                result = benchmark(activity, wavPath, cancelled,
                        done -> activity.runOnUiThread(() ->
                                progress.setText(done)));
            } catch (Throwable t) {
                result = "Benchmark failed: " + t.getClass().getSimpleName()
                        + ": " + t.getMessage();
            }
            final String text = result;
            activity.runOnUiThread(() -> {
                progressDialog.dismiss();
                TextView body = new TextView(activity);
                body.setText(text);
                body.setTextIsSelectable(true);
                body.setPadding(48, 32, 48, 8);
                new AlertDialog.Builder(activity)
                        .setTitle("Neural benchmark")
                        .setView(body)
                        .setPositiveButton("Close", null)
                        .show();
            });
        }).start();
    }

    private interface Progress {
        void show(String text);
    }

    private static String benchmark(Activity activity, String wavPath,
            AtomicBoolean cancelled, Progress progress) throws Exception {
        long t0 = System.nanoTime();
        float[] samples = readWavMono16k(new File(wavPath));
        long prepMs = (System.nanoTime() - t0) / 1_000_000;
        double seconds = samples.length / (double) SAMPLE_RATE;
        int frames = Math.max(0,
                1 + (samples.length - WINDOW) / HOP);
        if (frames < WARMUP_FRAMES + 10) {
            return "File too short to benchmark: "
                    + String.format(Locale.US, "%.1f", seconds)
                    + " s (" + frames + " frames).";
        }

        ByteBuffer model = loadModel(activity);
        Interpreter.Options options = new Interpreter.Options();
        options.setNumThreads(THREADS);
        float[][][][] input = new float[1][WINDOW][1][1];
        float[][] output = new float[1][360];
        long inferNs = 0;
        int voiced = 0;
        int done = 0;
        try (Interpreter interpreter = new Interpreter(model, options)) {
            for (int f = 0; f < frames; f++) {
                if (cancelled.get()) {
                    return "Benchmark cancelled at frame " + done
                            + " of " + frames + ".";
                }
                fillFrame(samples, f * HOP, input);
                long s = System.nanoTime();
                interpreter.run(input, output);
                if (f >= WARMUP_FRAMES) {
                    inferNs += System.nanoTime() - s;
                    done++;
                    float max = 0;
                    for (float v : output[0]) {
                        if (v > max) {
                            max = v;
                        }
                    }
                    if (max >= 0.5f) {
                        voiced++;
                    }
                }
                if (f % 500 == 0) {
                    progress.show(String.format(Locale.US,
                            "Frame %,d of %,d…", f, frames));
                }
            }
        }
        double inferMs = inferNs / 1e6;
        double msPerFrame = inferMs / done;
        double projected3Min = msPerFrame * 18000 / 1000.0;
        return String.format(Locale.US,
                "Device: %s %s (Android %d)\n"
                        + "File: %.1f s of audio, %,d frames "
                        + "(decode+resample %d ms)\n"
                        + "Interpreter: %d threads, float32 model\n"
                        + "Inference: %.1f s for %,d frames "
                        + "(%.3f ms/frame)\n"
                        + "Voiced frames (max activation >= 0.5): %.0f%%\n"
                        + "Projected 3-minute song: %.1f s "
                        + "(gate %.0f s: %s)",
                Build.MANUFACTURER, Build.MODEL, Build.VERSION.SDK_INT,
                seconds, frames, prepMs,
                THREADS,
                inferMs / 1000.0, done, msPerFrame,
                100.0 * voiced / Math.max(1, done),
                projected3Min, GATE_SECONDS_3MIN,
                projected3Min <= GATE_SECONDS_3MIN ? "PASS" : "FAIL");
    }

    /** Copy one window into the model input, per-frame normalised
     * (subtract mean, divide by std) as CREPE does. */
    private static void fillFrame(float[] samples, int start,
            float[][][][] input) {
        double mean = 0;
        for (int i = 0; i < WINDOW; i++) {
            mean += samples[start + i];
        }
        mean /= WINDOW;
        double var = 0;
        for (int i = 0; i < WINDOW; i++) {
            double d = samples[start + i] - mean;
            var += d * d;
        }
        double std = Math.max(Math.sqrt(var / WINDOW), 1e-8);
        for (int i = 0; i < WINDOW; i++) {
            input[0][i][0][0] =
                    (float) ((samples[start + i] - mean) / std);
        }
    }

    /** Load the model into a direct ByteBuffer via an asset stream:
     * openFd would fail, because aapt compresses .tflite assets by
     * default and compressed assets have no file descriptor. */
    private static ByteBuffer loadModel(Activity activity)
            throws Exception {
        byte[] bytes;
        try (InputStream in =
                activity.getAssets().open("crepe_tiny.tflite")) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) != -1) {
                bos.write(buf, 0, n);
            }
            bytes = bos.toByteArray();
        }
        ByteBuffer bb = ByteBuffer.allocateDirect(bytes.length);
        bb.order(ByteOrder.nativeOrder());
        bb.put(bytes);
        bb.flip();
        return bb;
    }

    /** Minimal WAV reader: PCM integer (8/16/24/32-bit) or float32,
     * any channel count (mixed to mono), resampled to 16 kHz by
     * linear interpolation. Returns mono samples in [-1, 1]. */
    private static float[] readWavMono16k(File file) throws Exception {
        byte[] bytes;
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            if (raf.length() > Integer.MAX_VALUE - 8) {
                throw new IllegalArgumentException("file too large");
            }
            bytes = new byte[(int) raf.length()];
            raf.readFully(bytes);
        }
        if (bytes.length < 44 || bytes[0] != 'R' || bytes[1] != 'I'
                || bytes[2] != 'F' || bytes[3] != 'F') {
            throw new IllegalArgumentException("not a WAV file");
        }
        int format = -1;
        int channels = -1;
        int sampleRate = -1;
        int bits = -1;
        int dataOff = -1;
        int dataLen = 0;
        int pos = 12;
        while (pos + 8 <= bytes.length) {
            String id = new String(bytes, pos, 4, "ASCII");
            long size = u32le(bytes, pos + 4);
            int body = pos + 8;
            if (id.equals("fmt ")) {
                format = u16le(bytes, body);
                channels = u16le(bytes, body + 2);
                sampleRate = (int) u32le(bytes, body + 4);
                bits = u16le(bytes, body + 14);
            } else if (id.equals("data")) {
                dataOff = body;
                dataLen = (int) Math.min(size, bytes.length - body);
                break;
            }
            pos = body + (int) size + ((size & 1) == 1 ? 1 : 0);
        }
        if (format < 0 || dataOff < 0 || channels < 1 || sampleRate < 1) {
            throw new IllegalArgumentException("malformed WAV");
        }
        int bytesPerSample = Math.max(1, bits / 8);
        int frameBytes = bytesPerSample * channels;
        int nFrames = dataLen / frameBytes;
        float[] mono = new float[nFrames];
        for (int i = 0; i < nFrames; i++) {
            double sum = 0;
            for (int c = 0; c < channels; c++) {
                sum += sampleAt(bytes,
                        dataOff + i * frameBytes + c * bytesPerSample,
                        format, bits);
            }
            mono[i] = (float) (sum / channels);
        }
        if (sampleRate == SAMPLE_RATE) {
            return mono;
        }
        int outLen = (int) ((long) nFrames * SAMPLE_RATE / sampleRate);
        float[] out = new float[outLen];
        for (int i = 0; i < outLen; i++) {
            double src = (double) i * sampleRate / SAMPLE_RATE;
            int i0 = (int) src;
            int i1 = Math.min(i0 + 1, nFrames - 1);
            double frac = src - i0;
            out[i] = (float) (mono[i0] * (1 - frac) + mono[i1] * frac);
        }
        return out;
    }

    private static double sampleAt(byte[] b, int off, int format,
            int bits) {
        if (format == 3 && bits == 32) {
            int raw = (int) u32le(b, off);
            return Float.intBitsToFloat(raw);
        }
        switch (bits) {
            case 8:
                return ((b[off] & 0xFF) - 128) / 128.0;
            case 16:
                return ((short) u16le(b, off)) / 32768.0;
            case 24: {
                int v = (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8)
                        | (b[off + 2] << 16);
                return v / 8388608.0;
            }
            case 32:
                return ((int) u32le(b, off)) / 2147483648.0;
            default:
                return 0;
        }
    }

    private static int u16le(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8);
    }

    private static long u32le(byte[] b, int off) {
        return (b[off] & 0xFFL) | ((b[off + 1] & 0xFFL) << 8)
                | ((b[off + 2] & 0xFFL) << 16)
                | ((b[off + 3] & 0xFFL) << 24);
    }
}
