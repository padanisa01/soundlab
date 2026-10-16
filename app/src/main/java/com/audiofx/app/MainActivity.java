package com.audiofx.app;

import android.app.Activity;
import android.Manifest;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Typeface;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.Switch;
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
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Deque;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Thin UI shell. All DSP lives in the Python module
 * {@code audiofx.android_bridge} (app/src/main/python), which is unit-tested
 * on desktop via tools/verify.py and bundled into the APK by Chaquopy.
 */
public class MainActivity extends Activity {

    private static final int PICK_AUDIO = 1001;
    private static final int PICK_FOLDER = 1002;
    private static final int REQ_RECORD_AUDIO = 2001;

    private LinearLayout trackCard;
    private TextView tvTrackName;
    private TextView tvTrackMeta;
    private Button btnPick;
    private Button btnChange;
    private TextView toastView;
    private final Runnable hideToast = () -> {
        toastView.animate().alpha(0f).setDuration(250)
                .withEndAction(() -> toastView.setVisibility(View.GONE)).start();
    };
    private TextView tvWarmthVal;
    private TextView tvEchoVal;
    private TextView tvReverbVal;
    private SeekBar seekWarmth;
    private SeekBar seekEcho;
    private String reverbPreset = "concert_hall";
    private LinearLayout chipStudio;
    private LinearLayout chipHall;
    private LinearLayout chipBath;
    private LinearLayout chipCave;
    private TextView chipStudioName;
    private TextView chipHallName;
    private TextView chipBathName;
    private TextView chipCaveName;
    private Button btnProcess;
    private Button btnUndo;
    private TextView undoBadge;
    private ImageButton btnPlayPause;
    private ImageButton btnRecord;
    private ImageButton btnStop;
    private Button btnNormalize;
    private Switch switchAgc;
    private Switch switchNs;
    private Switch switchLiveFx;
    private Button btnSave;
    private ProgressBar progressBar;
    private WaveformView waveformView;
    private LinearLayout selChipRow;
    private TextView selChipText;
    private Button btnSelClear;
    private TextView tvTimeCur;
    private TextView tvTimeTot;
    private TextView tvSource;

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
    private final AudioRecorder recorder = new AudioRecorder();
    private final LiveFxMonitor liveMonitor = new LiveFxMonitor();
    private String pendingRecName;
    private ObjectAnimator recPulse;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Runnable scheduledStop;

    /** Ticks the elapsed-time readout while the mic is recording. */
    private final Runnable recTick = new Runnable() {
        @Override
        public void run() {
            if (recorder.isRecording()) {
                tvTimeCur.setText(fmtTime(recorder.elapsedSec()));
                mainHandler.postDelayed(this, 200);
            } else if (liveMonitor.isRecording()) {
                tvTimeCur.setText(fmtTime(liveMonitor.elapsedSec()));
                mainHandler.postDelayed(this, 200);
            }
        }
    };

    /** Polls getCurrentPosition() and moves the waveform progress fill. */
    private final Runnable positionUpdater = new Runnable() {
        @Override
        public void run() {
            if (player != null && audioDurationSec > 0) {
                try {
                    int ms = player.getCurrentPosition();
                    waveformView.setPlaybackPosition((float) (ms / 1000.0 / audioDurationSec));
                    tvTimeCur.setText(fmtTime(ms / 1000.0));
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

        int depth(String fileHash) {
            Deque<Row> stack = rows.get(fileHash);
            return stack == null ? 0 : stack.size();
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
            setSelChip(null, null);
            setStatus("Selection cleared — full file.");
            return;
        }
        selStartSec = (double) startFrac * audioDurationSec;
        if (endFrac != null) {
            selEndSec = (double) endFrac * audioDurationSec;
            setSelChip(selStartSec, selEndSec);
            setStatus("Window: " + fmtSec(selStartSec) + "–" + fmtSec(selEndSec)
                    + " — tap for a new start, double-tap to clear.");
        } else {
            selEndSec = null;
            setSelChip(selStartSec, null);
            setStatus("Start: " + fmtSec(selStartSec)
                    + " — tap again to set the end, double-tap to clear.");
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        trackCard = findViewById(R.id.track_card);
        tvTrackName = findViewById(R.id.tv_track_name);
        tvTrackMeta = findViewById(R.id.tv_track_meta);
        btnPick = findViewById(R.id.btn_pick);
        btnChange = findViewById(R.id.btn_change);
        btnNormalize = findViewById(R.id.btn_normalize);
        btnNormalize.setOnClickListener(v -> normalizeAudio());
        toastView = findViewById(R.id.toast_view);
        tvWarmthVal = findViewById(R.id.tv_warmth_val);
        tvEchoVal = findViewById(R.id.tv_echo_val);
        tvReverbVal = findViewById(R.id.tv_reverb_val);
        seekWarmth = findViewById(R.id.seek_warmth);
        seekEcho = findViewById(R.id.seek_echo);
        switchAgc = findViewById(R.id.switch_agc);
        switchNs = findViewById(R.id.switch_ns);
        switchLiveFx = findViewById(R.id.switch_livefx);
        SharedPreferences prefs = getSharedPreferences("soundlab", MODE_PRIVATE);
        switchAgc.setChecked(prefs.getBoolean("mic_agc", false));
        switchNs.setChecked(prefs.getBoolean("mic_ns", false));
        switchLiveFx.setChecked(prefs.getBoolean("mic_livefx", false));
        switchAgc.setOnCheckedChangeListener((b, on) ->
                prefs.edit().putBoolean("mic_agc", on).apply());
        switchNs.setOnCheckedChangeListener((b, on) ->
                prefs.edit().putBoolean("mic_ns", on).apply());
        switchLiveFx.setOnCheckedChangeListener((b, on) ->
                prefs.edit().putBoolean("mic_livefx", on).apply());
        chipStudio = findViewById(R.id.chip_studio);
        chipHall = findViewById(R.id.chip_hall);
        chipBath = findViewById(R.id.chip_bath);
        chipCave = findViewById(R.id.chip_cave);
        chipStudioName = findViewById(R.id.chip_studio_name);
        chipHallName = findViewById(R.id.chip_hall_name);
        chipBathName = findViewById(R.id.chip_bath_name);
        chipCaveName = findViewById(R.id.chip_cave_name);
        btnProcess = findViewById(R.id.btn_process);
        btnUndo = findViewById(R.id.btn_undo);
        undoBadge = findViewById(R.id.undo_badge);
        btnPlayPause = findViewById(R.id.btn_play_pause);
        btnRecord = findViewById(R.id.btn_record);
        btnStop = findViewById(R.id.btn_stop);
        btnSave = findViewById(R.id.btn_save);
        progressBar = findViewById(R.id.progress_bar);
        waveformView = findViewById(R.id.waveform);
        selChipRow = findViewById(R.id.sel_chip_row);
        selChipText = findViewById(R.id.sel_chip_text);
        btnSelClear = findViewById(R.id.btn_sel_clear);
        tvTimeCur = findViewById(R.id.tv_time_cur);
        tvTimeTot = findViewById(R.id.tv_time_tot);
        tvSource = findViewById(R.id.tv_source);
        waveformView.setOnSelectionListener(this::onSelectionChanged);

        selectReverb(reverbPreset);

        seekWarmth.setOnSeekBarChangeListener(sliderListener());
        seekEcho.setOnSeekBarChangeListener(sliderListener());
        updateSliderLabels();

        btnPick.setOnClickListener(v -> pickAudio());
        btnChange.setOnClickListener(v -> pickAudio());
        findViewById(R.id.btn_zoom_in).setOnClickListener(v -> waveformView.zoomBy(1.4f));
        findViewById(R.id.btn_zoom_out).setOnClickListener(v -> waveformView.zoomBy(1f / 1.4f));
        btnSelClear.setOnClickListener(v -> waveformView.clearSelection());
        chipStudio.setOnClickListener(v -> selectReverb("studio"));
        chipHall.setOnClickListener(v -> selectReverb("concert_hall"));
        chipBath.setOnClickListener(v -> selectReverb("bathroom"));
        chipCave.setOnClickListener(v -> selectReverb("cave"));
        btnProcess.setOnClickListener(v -> processAudio());
        // Pressed-state shrink on Apply (SL-10).
        btnProcess.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    v.animate().scaleX(0.98f).scaleY(0.98f).setDuration(80).start();
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.animate().scaleX(1f).scaleY(1f).setDuration(80).start();
                    break;
            }
            return false;
        });
        btnUndo.setOnClickListener(v -> undoLastEffect());
        btnPlayPause.setOnClickListener(v -> togglePlayPause());
        btnRecord.setOnClickListener(v -> onRecordClicked());
        btnStop.setOnClickListener(v -> stopPlayback());
        btnSave.setOnClickListener(v -> onSaveClicked());

        setStatus("Pick a WAV file to begin.");
    }

    private SeekBar.OnSeekBarChangeListener sliderListener() {
        return new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                updateSliderLabels();
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        };
    }

    /** Toast pill replacing the old always-visible status line. */
    private void setStatus(String msg) {
        toastView.setText(msg);
        toastView.setVisibility(View.VISIBLE);
        toastView.setAlpha(0f);
        toastView.animate().alpha(1f).setDuration(200).start();
        mainHandler.removeCallbacks(hideToast);
        mainHandler.postDelayed(hideToast, 2600);
    }

    private void togglePlayPause() {
        if (isPlayerPlaying()) {
            pausePlayback();
            return;
        }
        playOutput();
    }

    private boolean isPlayerPlaying() {
        try {
            return player != null && player.isPlaying();
        } catch (Exception e) {
            return false;
        }
    }

    /** Swap the transport icon to match the live player state. */
    private void refreshPlayPause() {
        btnPlayPause.setImageResource(
                isPlayerPlaying() ? R.drawable.ic_pause : R.drawable.ic_play);
    }

    private void setPlayPauseEnabled(boolean enabled) {
        btnPlayPause.setEnabled(enabled);
        btnPlayPause.setAlpha(enabled ? 1f : 0.4f);
    }

    private void selectReverb(String preset) {
        reverbPreset = preset;
        paintChip(chipStudio, chipStudioName, "studio");
        paintChip(chipHall, chipHallName, "concert_hall");
        paintChip(chipBath, chipBathName, "bathroom");
        paintChip(chipCave, chipCaveName, "cave");
        tvReverbVal.setText(reverbDisplayName(preset));
    }

    private void paintChip(LinearLayout chip, TextView name, String preset) {
        boolean sel = preset.equals(reverbPreset);
        chip.setBackgroundResource(
                sel ? R.drawable.bg_chip_selected : R.drawable.bg_chip);
        name.setTextColor(getColor(sel ? R.color.sl_teal2 : R.color.sl_txt));
    }

    private static String reverbDisplayName(String preset) {
        switch (preset) {
            case "studio": return "Studio";
            case "bathroom": return "Bathroom";
            case "cave": return "Cave";
            default: return "Concert Hall";
        }
    }

    /** Undo depth badge; also drives the Undo button state. */
    private void updateUndoBadge() {
        int depth = historyDb.depth(fileHash(inputFileName));
        undoBadge.setText(String.valueOf(depth));
        undoBadge.setBackgroundResource(
                depth > 0 ? R.drawable.bg_badge : R.drawable.bg_badge_zero);
        undoBadge.setTextColor(getColor(
                depth > 0 ? android.R.color.white : R.color.sl_mut));
        btnUndo.setEnabled(depth > 0);
    }

    private static String fmtTime(double s) {
        s = Math.max(0, s);
        int m = (int) (s / 60);
        int sec = (int) (s % 60);
        return m + ":" + (sec < 10 ? "0" + sec : sec);
    }

    private static String fmtSec1(double s) {
        return (Math.round(s * 10) / 10.0) + "s";
    }

    private void setSelChip(Double startSec, Double endSec) {
        if (startSec == null) {
            selChipRow.setBackgroundResource(R.drawable.bg_sel_chip_empty);
            selChipText.setText("Full track");
            selChipText.setTextColor(getColor(R.color.sl_mut));
            selChipText.setTypeface(null, Typeface.NORMAL);
            btnSelClear.setVisibility(View.GONE);
        } else {
            selChipRow.setBackgroundResource(R.drawable.bg_sel_chip);
            selChipText.setText(endSec != null
                    ? "Window " + fmtSec1(startSec) + " – " + fmtSec1(endSec)
                    : "Start " + fmtSec1(startSec) + " — tap again for end");
            selChipText.setTextColor(getColor(R.color.sl_teal2));
            selChipText.setTypeface(null, Typeface.BOLD);
            btnSelClear.setVisibility(View.VISIBLE);
        }
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
                adoptAudioFile(dest, getDisplayName(uri));
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
                final int bitDepth = Integer.parseInt(
                        result.callAttr("get", "bit_depth").toString());
                runOnUiThread(() -> {
                    waveformView.setPeaks(peaks);
                    waveformView.setDuration(dur);
                    audioDurationSec = dur;
                    tvTrackMeta.setText(String.format(
                            "%.1fs · WAV %d-bit mono · WAV input only", dur, bitDepth));
                    tvTimeCur.setText("0:00");
                    tvTimeTot.setText(fmtTime(dur));
                    // The raw input can be auditioned before any Apply.
                    setPlayPauseEnabled(true);
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

    private static void copyFile(File src, File dest) throws Exception {
        try (InputStream in = new FileInputStream(src);
             OutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
        }
    }

    /**
     * Make {@code wavFile} the current input: copies it to input.wav,
     * snapshots a pristine copy for Undo, and resets the UI.
     */
    private void adoptAudioFile(File wavFile, String displayName) {
        try {
            File dest = new File(getCacheDir(), "input.wav");
            if (!wavFile.getAbsolutePath().equals(dest.getAbsolutePath())) {
                copyFile(wavFile, dest);
            }
            inputPath = dest.getAbsolutePath();
            inputFileName = displayName;
            // Pristine per-file copy, so Undo can always return to the
            // original audio even if another file is picked later.
            String hash = fileHash(inputFileName);
            File pristine = new File(getCacheDir(), "orig_" + hash + ".wav");
            copyFile(wavFile, pristine);
            originalInputPath.put(hash, pristine.getAbsolutePath());
            // Reset playback state for the new file.
            releasePlayer();
            btnStop.setEnabled(false);
            currentOutputPath = null;
            audioDurationSec = 0;
            setPlayPauseEnabled(false);
            refreshPlayPause();
            btnSave.setEnabled(true); // input is savable as-is, no Apply needed
            updateUndoBadge();
            waveformView.setPeaks(new float[0]);
            waveformView.setDuration(0);
            tvTimeCur.setText("0:00");
            tvTimeTot.setText("0:00");
            tvSource.setVisibility(View.GONE);
            btnPick.setVisibility(View.GONE);
            trackCard.setVisibility(View.VISIBLE);
            tvTrackName.setText(inputFileName);
            tvTrackMeta.setText("Loading…");
            setStatus("Ready. Adjust the effects and tap Apply.");
            loadWaveform();
        } catch (Exception e) {
            setStatus("Could not read file: " + e.getMessage());
        }
    }

    // ---- Microphone recording ----

    private void onRecordClicked() {
        if (recorder.isRecording() || liveMonitor.isRecording()) {
            stopRecording();
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{Manifest.permission.RECORD_AUDIO}, REQ_RECORD_AUDIO);
            return;
        }
        startRecording();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                          int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_RECORD_AUDIO) {
            if (grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startRecording();
            } else {
                setStatus("Microphone permission denied — can't record.");
            }
        }
    }

    private void startRecording() {
        if (player != null && player.isPlaying()) {
            stopPlayback();
        }
        if (switchLiveFx.isChecked()) {
            startLiveRecording();
            return;
        }
        recorder.setPreprocessing(switchAgc.isChecked(), switchNs.isChecked());
        File take = new File(getCacheDir(), "rec_take.wav");
        if (!recorder.start(take)) {
            setStatus("Microphone unavailable on this device.");
            return;
        }
        pendingRecName = "Recording "
                + new SimpleDateFormat("yyyy-MM-dd HH-mm", Locale.US).format(new Date())
                + ".wav";
        enterRecordingUi(false);
        StringBuilder fx = new StringBuilder();
        if (recorder.agcActive()) {
            fx.append(" + AGC");
        } else if (switchAgc.isChecked()) {
            fx.append(" (AGC unavailable on this device)");
        }
        if (recorder.nsActive()) {
            fx.append(" + noise suppression");
        } else if (switchNs.isChecked()) {
            fx.append(" (noise suppression unavailable on this device)");
        }
        setStatus("Recording " + recorder.activeLabel() + fx
                + " — tap the red button to stop.");
    }

    /**
     * Live effect mode: the current Warmth/Reverb/Echo settings are
     * snapshotted into a Python LiveChain, the mic feeds it in blocks,
     * the wet signal plays back live and is baked into the take.
     */
    private void startLiveRecording() {
        try {
            if (!Python.isStarted()) {
                Python.start(new AndroidPlatform(this));
            }
        } catch (Exception e) {
            setStatus("Could not start the audio engine.");
            return;
        }
        double warmth = seekWarmth.getProgress() / 100.0;
        double echo = seekEcho.getProgress() / 100.0;
        File take = new File(getCacheDir(), "rec_take.wav");
        if (!liveMonitor.start(take, warmth, reverbPreset, echo,
                switchAgc.isChecked(), switchNs.isChecked())) {
            setStatus("Live FX unavailable on this device.");
            return;
        }
        pendingRecName = "Recording "
                + new SimpleDateFormat("yyyy-MM-dd HH-mm", Locale.US).format(new Date())
                + ".wav";
        enterRecordingUi(true);
        String fxDesc = "Warmth " + seekWarmth.getProgress() + "%, Reverb "
                + reverbDisplayName(reverbPreset) + ", Echo " + seekEcho.getProgress() + "%";
        StringBuilder fx = new StringBuilder();
        if (liveMonitor.agcActive()) {
            fx.append(" + AGC");
        }
        if (liveMonitor.nsActive()) {
            fx.append(" + noise suppression");
        }
        setStatus("LIVE FX " + liveMonitor.activeLabel() + " (" + fxDesc + fx
                + ") — use headphones. Tap the red button to stop.");
    }

    /** Shared recording UI: pulsing red button, REC pill, elapsed timer. */
    private void enterRecordingUi(boolean live) {
        btnRecord.setImageResource(R.drawable.ic_stop_white);
        recPulse = ObjectAnimator.ofFloat(btnRecord, "alpha", 1f, 0.35f);
        recPulse.setDuration(600);
        recPulse.setRepeatMode(ValueAnimator.REVERSE);
        recPulse.setRepeatCount(ValueAnimator.INFINITE);
        recPulse.start();
        setPlayPauseEnabled(false);
        btnStop.setEnabled(false);
        btnProcess.setEnabled(false);
        btnProcess.setAlpha(0.55f);
        if (live) {
            // Effect params are snapshotted at record start; moving
            // sliders mid-take would do nothing, so lock them.
            seekWarmth.setEnabled(false);
            seekEcho.setEnabled(false);
            chipStudio.setEnabled(false);
            chipHall.setEnabled(false);
            chipBath.setEnabled(false);
            chipCave.setEnabled(false);
        }
        tvSource.setVisibility(View.VISIBLE);
        tvSource.setText("REC");
        tvSource.setTextColor(getColor(R.color.sl_red));
        tvTimeCur.setText("0:00");
        tvTimeTot.setText("--:--");
        mainHandler.post(recTick);
    }

    private void exitRecordingUi() {
        mainHandler.removeCallbacks(recTick);
        if (recPulse != null) {
            recPulse.cancel();
            recPulse = null;
        }
        btnRecord.setAlpha(1f);
        btnRecord.setImageResource(0);
        tvSource.setVisibility(View.GONE);
        tvSource.setTextColor(getColor(R.color.sl_org));
        btnProcess.setEnabled(true);
        btnProcess.setAlpha(1f);
        seekWarmth.setEnabled(true);
        seekEcho.setEnabled(true);
        chipStudio.setEnabled(true);
        chipHall.setEnabled(true);
        chipBath.setEnabled(true);
        chipCave.setEnabled(true);
    }

    private void stopRecording() {
        final File take;
        if (liveMonitor.isRecording()) {
            take = liveMonitor.stop();
        } else {
            take = recorder.stop();
        }
        exitRecordingUi();
        if (take == null) {
            setStatus("Recording failed — nothing captured.");
            return;
        }
        File named = new File(getCacheDir(), pendingRecName);
        if (!take.renameTo(named)) {
            named = take; // rename failed; use the take file directly
        }
        adoptAudioFile(named, pendingRecName);
    }

    private void processAudio() {
        if (inputPath == null) {
            Toast.makeText(this, "Pick an audio file first", Toast.LENGTH_SHORT).show();
            return;
        }
        final double warmth = seekWarmth.getProgress() / 100.0;
        final String preset = reverbPreset;
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
        btnProcess.setAlpha(0.55f);
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
                    setPlayPauseEnabled(true);
                    btnSave.setEnabled(true);
                    updateUndoBadge();
                    showProgress(false);
                    waveformView.finishProgressAnimation();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    setStatus("Error: " + e.getMessage());
                    showProgress(false);
                    waveformView.clearProgress();
                    updateUndoBadge();
                });
            } finally {
                runOnUiThread(() -> {
                    btnProcess.setEnabled(true);
                    btnProcess.setAlpha(1f);
                });
            }
        }).start();
    }

    /**
     * Peak-normalize the current audio (processed output when there is
     * one, else the input) to -1 dBFS. Whole-file gain, undoable like
     * any other processing step.
     */
    private void normalizeAudio() {
        final String srcPath = (currentOutputPath != null
                && new File(currentOutputPath).exists()) ? currentOutputPath : inputPath;
        if (srcPath == null || !(new File(srcPath).exists())) {
            Toast.makeText(this, "Pick an audio file or record first",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        final String hash = fileHash(inputFileName);
        // Every application writes its own file so Undo can restore earlier ones.
        final String outPath = new File(getCacheDir(),
                "output_" + System.currentTimeMillis() + "_" + (outputSeq++) + ".wav")
                .getAbsolutePath();
        final long timestamp = System.currentTimeMillis();
        final String effectsDesc = "Normalize to -1 dBFS";
        btnNormalize.setEnabled(false);
        btnProcess.setEnabled(false);
        btnProcess.setAlpha(0.55f);
        btnUndo.setEnabled(false); // no racing an undo mid-normalize
        showProgress(true);
        waveformView.startProgressAnimation();
        setStatus("Normalizing...");
        new Thread(() -> {
            try {
                if (!Python.isStarted()) {
                    Python.start(new AndroidPlatform(this));
                }
                PyObject result = Python.getInstance().getModule("audiofx.android_bridge")
                        .callAttr("normalize_file", srcPath, outPath);
                final double dur = Double.parseDouble(
                        result.callAttr("get", "duration_s").toString());
                historyDb.log(hash, effectsDesc, timestamp, outPath);
                currentOutputPath = outPath;
                audioDurationSec = dur;
                final String msg = "Normalized: peak "
                        + result.callAttr("get", "peak_before_db").toString() + " dB to "
                        + result.callAttr("get", "peak_after_db").toString() + " dBFS";
                runOnUiThread(() -> {
                    setStatus(msg);
                    setPlayPauseEnabled(true);
                    btnSave.setEnabled(true);
                    updateUndoBadge();
                    showProgress(false);
                    waveformView.finishProgressAnimation();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    setStatus("Error: " + e.getMessage());
                    showProgress(false);
                    waveformView.clearProgress();
                    updateUndoBadge();
                });
            } finally {
                runOnUiThread(() -> {
                    btnNormalize.setEnabled(true);
                    btnProcess.setEnabled(true);
                    btnProcess.setAlpha(1f);
                });
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
            setPlayPauseEnabled(inputPath != null && new File(inputPath).exists());
        }
        btnSave.setEnabled(inputPath != null && new File(inputPath).exists());
        updateUndoBadge();
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
        setPlayPauseEnabled(true);
        refreshPlayPause();
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
        setPlayPauseEnabled(true);
        refreshPlayPause();
        showSourcePill();
        setStatus(playbackStatus(startMs, stopMs));
    }

    /** Orange pill: INPUT when auditioning raw audio, PROCESSED otherwise. */
    private void showSourcePill() {
        tvSource.setVisibility(View.VISIBLE);
        tvSource.setText(isPlayingInput() ? "INPUT" : "PROCESSED");
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
                            setStatus("Stopped at window end.");
                        }
                    } catch (Exception ignored) {
                    }
                }
            };
            mainHandler.postDelayed(scheduledStop, Math.max(0, stopMs - startMs));
        }
    }

    private String playbackStatus(int startMs, Integer stopMs) {
        String what = isPlayingInput() ? "input" : "processed audio";
        if (stopMs != null) {
            return "Playing " + what + " " + (startMs / 1000) + "s–" + (stopMs / 1000) + "s...";
        }
        return startMs > 0 ? "Playing " + what + " from " + (startMs / 1000) + "s..."
                : "Playing " + what + "...";
    }

    /** True when the live player is on the raw input rather than an output. */
    private boolean isPlayingInput() {
        return playerOutputPath != null && playerOutputPath.equals(inputPath);
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
        // Audition the processed output when there is one, otherwise the
        // raw input — so a selection can be heard before any Apply.
        final String playPath = currentOutputPath != null ? currentOutputPath : inputPath;
        if (playPath == null) {
            Toast.makeText(this, "Pick an audio file first", Toast.LENGTH_SHORT).show();
            return;
        }
        File f = new File(playPath);
        if (!f.exists() || f.length() < 44) {
            setStatus("No audio to play (file missing or empty).");
            return;
        }
        // If the live player is already on this exact file, just seek in place.
        if (player != null && playPath.equals(playerOutputPath)) {
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
            setPlayPauseEnabled(true);
            refreshPlayPause();
            showSourcePill();
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
            player.setDataSource(playPath);
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
                playerOutputPath = playPath;
                isPaused = false;
                scheduleStopAt(stopMs, startMs, mp);
                showPlaybackRange(startMs);
                startPositionUpdates();
                setStatus(playbackStatus(startMs, stopMs));
                setPlayPauseEnabled(true);
                refreshPlayPause();
                showSourcePill();
                btnStop.setEnabled(true);
            });
            player.setOnCompletionListener(mp -> {
                isPaused = false;
                stopPositionUpdates();
                waveformView.clearPlayback();
                setStatus("Playback finished.");
                setPlayPauseEnabled(true);
                refreshPlayPause();
                tvSource.setVisibility(View.GONE);
                tvTimeCur.setText(fmtTime(audioDurationSec));
                btnStop.setEnabled(false);
            });
            player.setOnErrorListener((mp, what, extra) -> {
                setStatus("Playback error: " + what + "/" + extra);
                releasePlayer();
                setPlayPauseEnabled(true);
                refreshPlayPause();
                tvSource.setVisibility(View.GONE);
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
        setPlayPauseEnabled(currentOutputPath != null || inputPath != null);
        refreshPlayPause();
        tvSource.setVisibility(View.GONE);
        tvTimeCur.setText("0:00");
        btnStop.setEnabled(false);
        setStatus("Playback stopped.");
    }

    private void onSaveClicked() {
        if (inputPath == null || !(new File(inputPath).exists())) {
            Toast.makeText(this, "Pick an audio file or record first", Toast.LENGTH_SHORT).show();
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
        // Save the processed output when there is one, else the input as-is.
        final String srcPath = (currentOutputPath != null
                && new File(currentOutputPath).exists()) ? currentOutputPath : inputPath;
        final boolean raw = srcPath.equals(inputPath);
        btnSave.setEnabled(false);
        showProgress(true);
        setStatus(raw ? "Encoding MP3 (no effects)..." : "Encoding MP3...");
        new Thread(() -> {
            try {
                if (!Python.isStarted()) {
                    Python.start(new AndroidPlatform(this));
                }
                // MP3 encoding lives in the Python module (lameenc, installed
                // via Chaquopy pip), so it is unit-tested like the effects.
                File tmpMp3 = new File(getCacheDir(), "output.mp3");
                Python.getInstance().getModule("audiofx.android_bridge").callAttr(
                        "encode_wav_to_mp3", srcPath, tmpMp3.getAbsolutePath());
                String savedAs = copyToSaveFolder(tmpMp3, mp3Name);
                final String doneMsg = raw ? "Saved (no effects): " + savedAs
                        : "Saved: " + savedAs;
                runOnUiThread(() -> setStatus(doneMsg));
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

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (recorder.isRecording()) {
            recorder.stop();
        }
        if (liveMonitor.isRecording()) {
            liveMonitor.stop();
        }
        if (player != null) {
            player.release();
            player = null;
        }
    }
}
