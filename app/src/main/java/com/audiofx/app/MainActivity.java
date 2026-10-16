package com.audiofx.app;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.documentfile.provider.DocumentFile;

import com.chaquo.python.PyObject;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;

import org.json.JSONArray;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * Thin UI shell. All DSP lives in the Python module
 * {@code audiofx.android_bridge} (app/src/main/python), which is unit-tested
 * on desktop via tools/verify.py and bundled into the APK by Chaquopy.
 */
public class MainActivity extends Activity {

    private static final int PICK_AUDIO = 1001;
    private static final int PICK_FOLDER = 1002;

    private TextView tvPicked;
    private TextView tvStatus;
    private TextView tvWarmthVal;
    private TextView tvEchoVal;
    private SeekBar seekWarmth;
    private SeekBar seekEcho;
    private Spinner spinnerReverb;
    private Button btnProcess;
    private Button btnUndo;
    private Button btnPlay;
    private Button btnPause;
    private Button btnStop;
    private Button btnSave;
    private ProgressBar progressBar;
    private WaveformView waveformView;

    private String inputPath;   // cached WAV picked by the user
    private String inputFileName = "audio.wav";
    private String currentOutputPath;  // file backing Play/Save; null until first Apply
    private String playerOutputPath;   // file the live player was built for
    private double audioDurationSec;
    private int outputSeq;  // disambiguates output files within the same millisecond
    private Double selStartSec;  // waveform selection window, null = none
    private Double selEndSec;    // null = from start point to end of file
    private Uri saveFolderUri;  // session-only; never persisted
    private MediaPlayer player;
    private boolean isPaused;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Runnable scheduledStop;

    /** Polls getCurrentPosition() and moves the waveform progress fill. */
    private final Runnable positionUpdater = new Runnable() {
        @Override
        public void run() {
            if (player != null && audioDurationSec > 0) {
                try {
                    int ms = player.getCurrentPosition();
                    waveformView.setPlaybackPosition((float) (ms / 1000.0 / audioDurationSec));
                } catch (Exception ignored) {
                }
                mainHandler.postDelayed(this, 200);
            }
        }
    };

    private void startPositionUpdates() {
        stopPositionUpdates();
        mainHandler.post(positionUpdater);
    }

    private void stopPositionUpdates() {
        mainHandler.removeCallbacks(positionUpdater);
    }

    // In-memory session state for Undo.
    private final Map<String, String> originalInputPath = new HashMap<>(); // fileHash -> pristine WAV
    private final EffectHistoryDb historyDb = new EffectHistoryDb();

    /**
     * Session-only log of applied effects, keyed by input filename hash.
     * Each row records what was applied, when, and which file it produced,
     * so Undo can walk the stack back to the original audio.
     */
    private static final class EffectHistoryDb {
        static final class Row {
            final String fileHash;
            final String effects;
            final long timestamp;
            final String outputPath;

            Row(String fileHash, String effects, long timestamp, String outputPath) {
                this.fileHash = fileHash;
                this.effects = effects;
                this.timestamp = timestamp;
                this.outputPath = outputPath;
            }
        }

        private final Map<String, Deque<Row>> rows = new HashMap<>();

        void log(String fileHash, String effects, long timestamp, String outputPath) {
            Deque<Row> stack = rows.get(fileHash);
            if (stack == null) {
                stack = new ArrayDeque<>();
                rows.put(fileHash, stack);
            }
            stack.addLast(new Row(fileHash, effects, timestamp, outputPath));
        }

        /** Remove and return the most recent row, or null if none. */
        Row undo(String fileHash) {
            Deque<Row> stack = rows.get(fileHash);
            return (stack == null || stack.isEmpty()) ? null : stack.removeLast();
        }

        /** Most recent remaining row, or null. */
        Row latest(String fileHash) {
            Deque<Row> stack = rows.get(fileHash);
            return (stack == null || stack.isEmpty()) ? null : stack.peekLast();
        }

        boolean canUndo(String fileHash) {
            Deque<Row> stack = rows.get(fileHash);
            return stack != null && !stack.isEmpty();
        }
    }

    /** Session key for the effect history: hash of the input file name. */
    private static String fileHash(String fileName) {
        return Integer.toHexString(fileName.hashCode());
    }

    private static String fmtSec(double s) {
        return ((int) Math.round(s)) + "s";
    }

    private void onSelectionChanged(Float startFrac, Float endFrac) {
        if (startFrac == null || audioDurationSec <= 0) {
            selStartSec = null;
            selEndSec = null;
            setStatus("Selection cleared — full file.");
            return;
        }
        selStartSec = (double) startFrac * audioDurationSec;
        if (endFrac != null) {
            selEndSec = (double) endFrac * audioDurationSec;
            setStatus("Window: " + fmtSec(selStartSec) + "–" + fmtSec(selEndSec)
                    + " — tap for a new start, double-tap to clear.");
        } else {
            selEndSec = null;
            setStatus("Start: " + fmtSec(selStartSec)
                    + " — tap again to set the end, double-tap to clear.");
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvPicked = findViewById(R.id.tv_picked);
        tvStatus = findViewById(R.id.tv_status);
        tvWarmthVal = findViewById(R.id.tv_warmth_val);
        tvEchoVal = findViewById(R.id.tv_echo_val);
        seekWarmth = findViewById(R.id.seek_warmth);
        seekEcho = findViewById(R.id.seek_echo);
        spinnerReverb = findViewById(R.id.spinner_reverb);
        btnProcess = findViewById(R.id.btn_process);
        btnUndo = findViewById(R.id.btn_undo);
        btnPlay = findViewById(R.id.btn_play);
        btnPause = findViewById(R.id.btn_pause);
        btnStop = findViewById(R.id.btn_stop);
        btnSave = findViewById(R.id.btn_save);
        progressBar = findViewById(R.id.progress_bar);
        waveformView = findViewById(R.id.waveform);
        waveformView.setOnSelectionListener(this::onSelectionChanged);

        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(
                this, R.array.reverb_presets, android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerReverb.setAdapter(adapter);

        seekWarmth.setOnSeekBarChangeListener(sliderListener(tvWarmthVal));
        seekEcho.setOnSeekBarChangeListener(sliderListener(tvEchoVal));
        updateSliderLabels();

        findViewById(R.id.btn_pick).setOnClickListener(v -> pickAudio());
        btnProcess.setOnClickListener(v -> processAudio());
        btnUndo.setOnClickListener(v -> undoLastEffect());
        btnPlay.setOnClickListener(v -> playOutput());
        btnPause.setOnClickListener(v -> pausePlayback());
        btnStop.setOnClickListener(v -> stopPlayback());
        btnSave.setOnClickListener(v -> onSaveClicked());

        setStatus("Pick a WAV file to begin.");
    }

    private SeekBar.OnSeekBarChangeListener sliderListener(TextView label) {
        return new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                updateSliderLabels();
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        };
    }

    private void updateSliderLabels() {
        tvWarmthVal.setText(seekWarmth.getProgress() + "%");
        tvEchoVal.setText(seekEcho.getProgress() + "%");
    }

    private void pickAudio() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("audio/*");
        startActivityForResult(intent, PICK_AUDIO);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_AUDIO && resultCode == RESULT_OK
                && data != null && data.getData() != null) {
            Uri uri = data.getData();
            try {
                File dest = new File(getCacheDir(), "input.wav");
                copyUriToFile(uri, dest);
                inputPath = dest.getAbsolutePath();
                inputFileName = getDisplayName(uri);
                // Pristine per-file copy, so Undo can always return to the
                // original audio even if another file is picked later.
                String hash = fileHash(inputFileName);
                File pristine = new File(getCacheDir(), "orig_" + hash + ".wav");
                copyUriToFile(uri, pristine);
                originalInputPath.put(hash, pristine.getAbsolutePath());
                // Reset playback state for the new file.
                releasePlayer();
                btnStop.setEnabled(false);
                currentOutputPath = null;
                audioDurationSec = 0;
                btnPlay.setEnabled(false);
                btnSave.setEnabled(false);
                btnUndo.setEnabled(historyDb.canUndo(hash));
                waveformView.setPeaks(new float[0]);
                waveformView.setDuration(0);
                tvPicked.setText("Picked: " + inputFileName);
                setStatus("Ready. Adjust the effects and tap Apply.");
                loadWaveform();
            } catch (Exception e) {
                setStatus("Could not read file: " + e.getMessage());
            }
        }
        if (requestCode == PICK_FOLDER && resultCode == RESULT_OK
                && data != null && data.getData() != null) {
            saveFolderUri = data.getData();
            DocumentFile dir = DocumentFile.fromTreeUri(this, saveFolderUri);
            String label = (dir != null && dir.getName() != null) ? dir.getName() : "chosen folder";
            setStatus("Save folder: " + label);
            doSaveMp3();
        }
    }

    private String getDisplayName(Uri uri) {
        try (Cursor c = getContentResolver().query(
                uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                String name = c.getString(0);
                if (name != null && !name.isEmpty()) return name;
            }
        } catch (Exception ignored) {
        }
        String seg = uri.getLastPathSegment();
        return seg != null ? seg : "audio.wav";
    }

    private static String toMp3Name(String wavName) {
        if (wavName.length() > 4
                && wavName.regionMatches(true, wavName.length() - 4, ".wav", 0, 4)) {
            return wavName.substring(0, wavName.length() - 4) + ".mp3";
        }
        return wavName + ".mp3";
    }

    private void loadWaveform() {
        final String path = inputPath;
        new Thread(() -> {
            try {
                if (!Python.isStarted()) {
                    Python.start(new AndroidPlatform(this));
                }
                PyObject result = Python.getInstance().getModule("audiofx.android_bridge")
                        .callAttr("waveform_peaks", path, 1200);
                // peaks_json is a Python str; PyObject.toString() gives str().
                JSONArray arr = new JSONArray(result.callAttr("get", "peaks_json").toString());
                final float[] peaks = new float[arr.length()];
                for (int i = 0; i < arr.length(); i++) {
                    peaks[i] = (float) arr.getDouble(i);
                }
                final double dur = Double.parseDouble(
                        result.callAttr("get", "duration_s").toString());
                runOnUiThread(() -> {
                    waveformView.setPeaks(peaks);
                    waveformView.setDuration(dur);
                    audioDurationSec = dur;
                });
            } catch (Exception e) {
                runOnUiThread(() -> setStatus("Waveform failed: " + e.getMessage()));
            }
        }).start();
    }

    private void showProgress(boolean show) {
        progressBar.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private void copyUriToFile(Uri uri, File dest) throws Exception {
        try (InputStream in = getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
        }
    }

    private void processAudio() {
        if (inputPath == null) {
            Toast.makeText(this, "Pick an audio file first", Toast.LENGTH_SHORT).show();
            return;
        }
        final double warmth = seekWarmth.getProgress() / 100.0;
        final String preset = spinnerReverb.getSelectedItem().toString();
        final double echo = seekEcho.getProgress() / 100.0;
        final String hash = fileHash(inputFileName);
        final String windowDesc = selStartSec != null
                ? " [" + fmtSec(selStartSec) + "–"
                        + (selEndSec != null ? fmtSec(selEndSec) : "end") + "]"
                : "";
        final String effectsDesc = "Warmth " + seekWarmth.getProgress() + "%, Reverb "
                + preset + ", Echo " + seekEcho.getProgress() + "%" + windowDesc;
        // Every application writes its own file so Undo can restore earlier ones.
        final String outPath = new File(getCacheDir(),
                "output_" + System.currentTimeMillis() + "_" + (outputSeq++) + ".wav")
                .getAbsolutePath();
        final long timestamp = System.currentTimeMillis();
        // Window bounds for the Python side; null becomes None (whole file).
        final Double startSec = selStartSec;
        final Double endSec = selEndSec;
        btnProcess.setEnabled(false);
        btnUndo.setEnabled(false); // no racing an undo mid-apply
        showProgress(true);
        if (selStartSec != null && audioDurationSec > 0) {
            float fromFrac = (float) (selStartSec / audioDurationSec);
            float toFrac = 1f;
            if (selEndSec != null) {
                toFrac = (float) Math.min(1.0, selEndSec / audioDurationSec);
            }
            waveformView.startProgressAnimation(fromFrac, toFrac);
        } else {
            waveformView.startProgressAnimation();
        }
        setStatus("Processing...");
        new Thread(() -> {
            try {
                if (!Python.isStarted()) {
                    Python.start(new AndroidPlatform(this));
                }
                Python py = Python.getInstance();
                PyObject result = py.getModule("audiofx.android_bridge").callAttr(
                        "process_file", inputPath, outPath, warmth, preset, echo,
                        startSec, endSec);
                // process_file returns a Python dict. PyObject.get(String) does
                // getattr(), which returns null for dict keys, so read values
                // via dict.get() instead.
                final double dur = Double.parseDouble(
                        result.callAttr("get", "duration_s").toString());
                // Echo back the window Python actually applied (start_s/end_s
                // in the result) so a lost selection is visible in the status.
                final String appliedStart = result.callAttr("get", "start_s").toString();
                final String appliedEnd = result.callAttr("get", "end_s").toString();
                historyDb.log(hash, effectsDesc, timestamp, outPath);
                currentOutputPath = outPath;
                audioDurationSec = dur;
                final String msg = "Done: " + dur + "s @ "
                        + result.callAttr("get", "sample_rate") + " Hz — " + effectsDesc
                        + " (applied " + appliedStart + "s–" + appliedEnd + "s)";
                runOnUiThread(() -> {
                    setStatus(msg);
                    btnPlay.setEnabled(true);
                    btnSave.setEnabled(true);
                    btnUndo.setEnabled(true);
                    showProgress(false);
                    waveformView.finishProgressAnimation();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    setStatus("Error: " + e.getMessage());
                    showProgress(false);
                    waveformView.clearProgress();
                    btnUndo.setEnabled(historyDb.canUndo(hash));
                });
            } finally {
                runOnUiThread(() -> btnProcess.setEnabled(true));
            }
        }).start();
    }

    private void undoLastEffect() {
        final String hash = fileHash(inputFileName);
        EffectHistoryDb.Row removed = historyDb.undo(hash);
        if (removed == null) {
            Toast.makeText(this, "Nothing to undo", Toast.LENGTH_SHORT).show();
            return;
        }
        new File(removed.outputPath).delete();
        releasePlayer();
        btnStop.setEnabled(false);
        EffectHistoryDb.Row top = historyDb.latest(hash);
        if (top == null) {
            currentOutputPath = originalInputPath.get(hash);
            setStatus("Undone: " + removed.effects + " — back to the original audio.");
        } else {
            currentOutputPath = top.outputPath;
            setStatus("Undone: " + removed.effects + " — now at: " + top.effects + ".");
        }
        if (currentOutputPath == null || !(new File(currentOutputPath).exists())) {
            btnPlay.setEnabled(false);
            btnSave.setEnabled(false);
        }
        btnUndo.setEnabled(historyDb.canUndo(hash));
    }

    private void releasePlayer() {
        cancelScheduledStop();
        stopPositionUpdates();
        waveformView.clearPlayback();
        if (player != null) {
            try {
                player.stop();
            } catch (Exception ignored) {
            }
            player.release();
            player = null;
            playerOutputPath = null;
        }
        isPaused = false;
    }

    private void pausePlayback() {
        if (player == null || isPaused) {
            return;
        }
        try {
            if (!player.isPlaying()) {
                return;
            }
            player.pause();
        } catch (Exception ignored) {
            return;
        }
        isPaused = true;
        cancelScheduledStop();
        stopPositionUpdates();
        btnPlay.setEnabled(true);
        btnPause.setEnabled(false);
        int sec = 0;
        try {
            sec = player.getCurrentPosition() / 1000;
        } catch (Exception ignored) {
        }
        setStatus("Paused at " + sec + "s — tap Play to resume.");
    }

    private void resumePlayback() {
        if (player == null) {
            isPaused = false;
            return;
        }
        try {
            player.start();
        } catch (Exception e) {
            setStatus("Playback failed: " + e);
            return;
        }
        isPaused = false;
        int startMs = 0;
        try {
            startMs = player.getCurrentPosition();
        } catch (Exception ignored) {
        }
        Integer stopMs = computeStopMs(startMs);
        scheduleStopAt(stopMs, startMs, player);
        showPlaybackRange(startMs);
        startPositionUpdates();
        btnPlay.setEnabled(false);
        btnPause.setEnabled(true);
        setStatus(playbackStatus(startMs, stopMs));
    }

    /**
     * Show the playback range on the waveform: the selection window (or the
     * whole file), with the fill at the given playback position.
     * No-op until durations are known.
     */
    private void showPlaybackRange(int positionMs) {
        if (audioDurationSec <= 0) {
            return;
        }
        float dur = (float) audioDurationSec;
        float s = selStartSec != null ? (float) (selStartSec / audioDurationSec) : 0f;
        float e = 1f;
        if (selEndSec != null) {
            float se = (float) (selEndSec / audioDurationSec);
            if (se > s) {
                e = Math.min(1f, se);
            }
        }
        waveformView.setPlaybackRange(s, e);
        waveformView.setPlaybackPosition(positionMs / 1000f / dur);
    }

    private void cancelScheduledStop() {
        if (scheduledStop != null) {
            mainHandler.removeCallbacks(scheduledStop);
            scheduledStop = null;
        }
    }

    /** End of the playback window in ms, or null for end-of-file. */
    private Integer computeStopMs(int startMs) {
        if (selEndSec != null && audioDurationSec > 0) {
            int endMs = (int) (selEndSec * 1000);
            if (endMs > startMs) {
                return endMs;
            }
        }
        return null;
    }

    private void scheduleStopAt(Integer stopMs, int startMs, MediaPlayer mp) {
        cancelScheduledStop();
        if (stopMs != null) {
            scheduledStop = () -> {
                if (player == mp) {
                    try {
                        if (mp.isPlaying()) {
                            stopPlayback();
                        }
                    } catch (Exception ignored) {
                    }
                }
            };
            mainHandler.postDelayed(scheduledStop, Math.max(0, stopMs - startMs));
        }
    }

    private String playbackStatus(int startMs, Integer stopMs) {
        if (stopMs != null) {
            return "Playing " + (startMs / 1000) + "s–" + (stopMs / 1000) + "s...";
        }
        return startMs > 0 ? "Playing from " + (startMs / 1000) + "s..."
                : "Playing processed audio...";
    }

    private void playOutput() {
        // Resume a paused playback from the pause point.
        if (isPaused && player != null) {
            resumePlayback();
            return;
        }
        float fraction = 0f;
        if (selStartSec != null && audioDurationSec > 0) {
            fraction = (float) (selStartSec / audioDurationSec);
        }
        playOutputAt(fraction);
    }

    private int seekMs(float fraction) {
        if (audioDurationSec <= 0) {
            return 0;
        }
        float f = Math.max(0f, Math.min(1f, fraction));
        return (int) (f * audioDurationSec * 1000);
    }

    private void playOutputAt(float fraction) {
        if (currentOutputPath == null) {
            Toast.makeText(this, "Apply effects first", Toast.LENGTH_SHORT).show();
            return;
        }
        File f = new File(currentOutputPath);
        if (!f.exists() || f.length() < 44) {
            setStatus("No audio to play (file missing or empty).");
            return;
        }
        // If the live player is already on this exact file, just seek in place.
        if (player != null && currentOutputPath.equals(playerOutputPath)) {
            int startMs = seekMs(fraction);
            Integer stopMs = computeStopMs(startMs);
            try {
                player.seekTo(startMs);
                if (!player.isPlaying()) {
                    player.start();
                }
            } catch (Exception ignored) {
            }
            isPaused = false;
            scheduleStopAt(stopMs, startMs, player);
            showPlaybackRange(startMs);
            startPositionUpdates();
            setStatus(playbackStatus(startMs, stopMs));
            btnPlay.setEnabled(false);
            btnPause.setEnabled(true);
            btnStop.setEnabled(true);
            return;
        }
        setStatus("Creating player...");
        try {
            releasePlayer();
            player = new MediaPlayer();
            // If the UI freezes on one of these status lines, the blocking
            // call is the one AFTER the last line shown.
            setStatus("Opening " + (f.length() / 1024) + " KB audio...");
            player.setDataSource(currentOutputPath);
            final boolean[] prepared = {false};
            player.setOnPreparedListener(mp -> {
                prepared[0] = true;
                int startMs = seekMs(fraction);
                Integer stopMs = computeStopMs(startMs);
                if (startMs > 0) {
                    try {
                        mp.seekTo(startMs);
                    } catch (Exception ignored) {
                    }
                }
                mp.start();
                playerOutputPath = currentOutputPath;
                isPaused = false;
                scheduleStopAt(stopMs, startMs, mp);
                showPlaybackRange(startMs);
                startPositionUpdates();
                setStatus(playbackStatus(startMs, stopMs));
                btnPlay.setEnabled(false);
                btnPause.setEnabled(true);
                btnStop.setEnabled(true);
            });
            player.setOnCompletionListener(mp -> {
                isPaused = false;
                stopPositionUpdates();
                waveformView.clearPlayback();
                setStatus("Playback finished.");
                btnPlay.setEnabled(true);
                btnPause.setEnabled(false);
                btnStop.setEnabled(false);
            });
            player.setOnErrorListener((mp, what, extra) -> {
                setStatus("Playback error: " + what + "/" + extra);
                releasePlayer();
                btnPlay.setEnabled(true);
                btnPause.setEnabled(false);
                btnStop.setEnabled(false);
                return true;
            });
            player.prepareAsync();
            setStatus("Preparing audio...");
            final MediaPlayer thisPlayer = player;
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                // Only report if this player is still the active one (i.e. the
                // user did not stop it or start a new playback since).
                if (player == thisPlayer && !prepared[0]) {
                    setStatus("Still preparing after 15s (" + (f.length() / 1024)
                            + " KB file) - prepareAsync never called back.");
                }
            }, 15000);
        } catch (Exception e) {
            setStatus("Playback failed: " + e);
        }
    }

    private void stopPlayback() {
        releasePlayer();
        btnPause.setEnabled(false);
        btnStop.setEnabled(false);
        setStatus("Playback stopped.");
    }

    private void onSaveClicked() {
        if (currentOutputPath == null || !(new File(currentOutputPath).exists())) {
            Toast.makeText(this, "Apply effects first", Toast.LENGTH_SHORT).show();
            return;
        }
        if (saveFolderUri == null) {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            startActivityForResult(intent, PICK_FOLDER);
        } else {
            doSaveMp3();
        }
    }

    private void doSaveMp3() {
        final String mp3Name = toMp3Name(inputFileName);
        btnSave.setEnabled(false);
        showProgress(true);
        setStatus("Encoding MP3...");
        new Thread(() -> {
            try {
                if (!Python.isStarted()) {
                    Python.start(new AndroidPlatform(this));
                }
                // MP3 encoding lives in the Python module (lameenc, installed
                // via Chaquopy pip), so it is unit-tested like the effects.
                File tmpMp3 = new File(getCacheDir(), "output.mp3");
                Python.getInstance().getModule("audiofx.android_bridge").callAttr(
                        "encode_wav_to_mp3", currentOutputPath, tmpMp3.getAbsolutePath());
                String savedAs = copyToSaveFolder(tmpMp3, mp3Name);
                runOnUiThread(() -> setStatus("Saved: " + savedAs));
            } catch (Exception e) {
                runOnUiThread(() -> setStatus("Save failed: " + e.getMessage()));
            } finally {
                runOnUiThread(() -> {
                    btnSave.setEnabled(true);
                    showProgress(false);
                });
            }
        }).start();
    }

    private String copyToSaveFolder(File src, String baseName) throws Exception {
        DocumentFile dir = DocumentFile.fromTreeUri(this, saveFolderUri);
        if (dir == null || !dir.canWrite()) {
            throw new Exception("Save folder is not writable");
        }
        String name = baseName;
        int copy = 1;
        while (dir.findFile(name) != null) {
            int dot = baseName.lastIndexOf('.');
            String stem = dot > 0 ? baseName.substring(0, dot) : baseName;
            String ext = dot > 0 ? baseName.substring(dot) : "";
            name = stem + " (" + (copy++) + ")" + ext;
        }
        DocumentFile dest = dir.createFile("audio/mpeg", name);
        if (dest == null) {
            throw new Exception("Could not create file in save folder");
        }
        try (InputStream in = new FileInputStream(src);
             OutputStream out = getContentResolver().openOutputStream(dest.getUri())) {
            if (out == null) throw new Exception("Could not open save destination");
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        }
        return name;
    }

    private void setStatus(String msg) {
        tvStatus.setText(msg);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (player != null) {
            player.release();
            player = null;
        }
    }
}
