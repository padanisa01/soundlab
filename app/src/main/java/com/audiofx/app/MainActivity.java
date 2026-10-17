package com.audiofx.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.view.Gravity;
import android.Manifest;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.UriPermission;
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
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;
import androidx.documentfile.provider.DocumentFile;
import com.google.android.gms.ads.AdError;
import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.AdView;
import com.google.android.gms.ads.FullScreenContentCallback;
import com.google.android.gms.ads.LoadAdError;
import com.google.android.gms.ads.MobileAds;
import com.google.android.gms.ads.interstitial.InterstitialAd;
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback;

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
import java.util.ArrayList;
import java.util.Date;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

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
    private TextView tvProjectName;
    private ImageButton btnMenu;
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
    private ImageButton btnNormalize;
    private Switch switchAgc;
    private Switch switchNs;
    private Switch switchLiveFx;
    private Switch switchAutoNorm;
    private LinearLayout advMicPanel;
    private LinearLayout advMicHeader;
    private ImageView advMicChevron;
    private ProgressBar progressBar;
    private LinearLayout histCard;
    private LinearLayout histList;
    private TextView tvHistCount;
    private WaveformView waveformView;
    private LinearLayout selChipRow;
    private TextView selChipText;
    private Button btnSelClear;
    private Button btnTrim;
    private Button btnJoin;
    private TextView tvTimeCur;
    private TextView tvTimeTot;
    private TextView tvSource;

    private String inputPath;   // cached WAV picked by the user, or a library pristine
    private String inputFileName = "audio.wav";
    private String currentOutputPath;  // file backing Play/Save; null until first Apply
    private String playerOutputPath;   // file the live player was built for
    private String currentEntryId;     // library entry being edited; null for picked files
    private LibraryDb libraryDb;
    private double audioDurationSec;
    private int outputSeq;  // disambiguates output files within the same millisecond
    private Double selStartSec;  // waveform selection window, null = none
    private Double selEndSec;    // null = from start point to end of file
    private Uri saveFolderUri;  // persisted via takePersistableUriPermission
    private MediaPlayer player;
    private boolean isPaused;
    private final AudioRecorder recorder = new AudioRecorder();
    private final LiveFxMonitor liveMonitor = new LiveFxMonitor();
    private String pendingRecName;
    private ObjectAnimator recPulse;
    /** Peak accumulator fed by the capture threads; drained by recTick. */
    private volatile float livePeakAcc;
    private android.app.AlertDialog saveDialog;
    private TextView saveFolderLabel;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Runnable scheduledStop;

    /** Ticks the elapsed-time readout and the live waveform while recording. */
    private final Runnable recTick = new Runnable() {
        @Override
        public void run() {
            boolean dry = recorder.isRecording();
            boolean live = liveMonitor.isRecording();
            if (dry || live) {
                double elapsed = dry ? recorder.elapsedSec() : liveMonitor.elapsedSec();
                tvTimeCur.setText(fmtTime(elapsed));
                float peak = livePeakAcc;
                livePeakAcc = 0f;
                waveformView.addLivePeak(peak);
                waveformView.setDuration(elapsed);
                mainHandler.postDelayed(this, 200);
            }
        }
    };

    /** Cheap, thread-safe peak feed from the capture threads. */
    private void onCaptureBlock(float peak) {
        if (peak > livePeakAcc) {
            livePeakAcc = peak;
        }
    }

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

        /** All rows for the file, oldest first (for the history panel). */
        List<Row> list(String fileHash) {
            Deque<Row> stack = rows.get(fileHash);
            return stack == null ? new ArrayList<>() : new ArrayList<>(stack);
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
        tvProjectName = findViewById(R.id.tv_project_name);
        btnMenu = findViewById(R.id.btn_menu);
        btnMenu.setOnClickListener(v -> showMainMenu());
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
        switchAutoNorm = findViewById(R.id.switch_autonorm);
        SharedPreferences prefs = getSharedPreferences("soundlab", MODE_PRIVATE);
        switchAgc.setChecked(prefs.getBoolean("mic_agc", false));
        switchNs.setChecked(prefs.getBoolean("mic_ns", false));
        switchLiveFx.setChecked(prefs.getBoolean("mic_livefx", false));
        switchAutoNorm.setChecked(prefs.getBoolean("mic_autonorm", true));
        switchAgc.setOnCheckedChangeListener((b, on) ->
                prefs.edit().putBoolean("mic_agc", on).apply());
        switchNs.setOnCheckedChangeListener((b, on) ->
                prefs.edit().putBoolean("mic_ns", on).apply());
        switchLiveFx.setOnCheckedChangeListener((b, on) ->
                prefs.edit().putBoolean("mic_livefx", on).apply());
        switchAutoNorm.setOnCheckedChangeListener((b, on) ->
                prefs.edit().putBoolean("mic_autonorm", on).apply());
        advMicPanel = findViewById(R.id.adv_mic_panel);
        advMicHeader = findViewById(R.id.adv_mic_header);
        advMicChevron = findViewById(R.id.adv_mic_chevron);
        setAdvMicExpanded(prefs.getBoolean("mic_adv_expanded", false), false);
        advMicHeader.setOnClickListener(v -> toggleAdvMic());
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
        progressBar = findViewById(R.id.progress_bar);
        histCard = findViewById(R.id.hist_card);
        histList = findViewById(R.id.hist_list);
        tvHistCount = findViewById(R.id.tv_hist_count);
        waveformView = findViewById(R.id.waveform);
        selChipRow = findViewById(R.id.sel_chip_row);
        selChipText = findViewById(R.id.sel_chip_text);
        btnSelClear = findViewById(R.id.btn_sel_clear);
        btnTrim = findViewById(R.id.btn_trim);
        btnJoin = findViewById(R.id.btn_join);
        btnTrim.setOnClickListener(v -> trimSelection());
        btnJoin.setOnClickListener(v -> joinSelection());
        tvTimeCur = findViewById(R.id.tv_time_cur);
        tvTimeTot = findViewById(R.id.tv_time_tot);
        tvSource = findViewById(R.id.tv_source);
        waveformView.setOnSelectionListener(this::onSelectionChanged);

        selectReverb(reverbPreset);

        seekWarmth.setOnSeekBarChangeListener(sliderListener());
        seekEcho.setOnSeekBarChangeListener(sliderListener());
        updateSliderLabels();
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

        libraryDb = new LibraryDb(this);
        restoreSaveFolderUri(prefs);
        updateProjectName();
        refreshHistoryPanel();

        // AdMob banner (test ad unit ID until the real one is configured).
        MobileAds.initialize(this, status -> {
        });
        AdView adView = findViewById(R.id.ad_view);
        adView.loadAd(new AdRequest.Builder().build());
        loadShareInterstitial();

        String launchEntryId = getIntent().getStringExtra(LibraryActivity.EXTRA_ENTRY_ID);
        if (launchEntryId != null) {
            adoptLibraryEntry(launchEntryId);
        } else {
            setStatus("Tap the menu to open audio or your library.");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // The entry may have been renamed or edited in the library.
        if (currentEntryId != null) {
            updateProjectName();
            refreshHistoryPanel();
        }
    }

    /** Hamburger menu: Open, Library, Save as MP3, Share. */
    private void showMainMenu() {
        boolean canSave = inputPath != null && new File(inputPath).exists();
        final String[] items = canSave
                ? new String[]{"Open audio file", "Recordings library",
                        "Save as MP3", "Share"}
                : new String[]{"Open audio file", "Recordings library"};
        new AlertDialog.Builder(this, R.style.Theme_SoundLab_Dialog)
                .setItems(items, (d, which) -> {
                    String sel = items[which];
                    if (sel.equals("Open audio file")) {
                        pickAudio();
                    } else if (sel.equals("Recordings library")) {
                        openLibrary();
                    } else if (sel.equals("Share")) {
                        shareAudio();
                    } else {
                        onSaveClicked();
                    }
                })
                .show();
    }

    /** Project status bar: entry/file name, or an untitled placeholder. */
    private void updateProjectName() {
        String name;
        boolean untitled = false;
        if (currentEntryId != null) {
            String entryName = null;
            try {
                LibraryDb.LibraryEntry e = libraryDb.getEntry(currentEntryId);
                if (e != null) {
                    entryName = e.name;
                }
            } catch (Exception ignored) {
            }
            name = entryName != null ? entryName : inputFileName;
        } else if (inputPath != null) {
            name = inputFileName;
        } else {
            untitled = true;
            name = "<untitled project - "
                    + new SimpleDateFormat("yyyy-MM-dd - HH:mm", Locale.US)
                            .format(new Date())
                    + ">";
        }
        tvProjectName.setText(name);
        tvProjectName.setTextColor(getColor(
                untitled ? R.color.sl_dim : R.color.sl_txt));
    }

    /** Effect history panel: library ops or legacy picked-file history. */
    private void refreshHistoryPanel() {
        List<String[]> rows = new ArrayList<>();
        if (currentEntryId != null) {
            try {
                for (LibraryDb.Op op : libraryDb.listOps(currentEntryId)) {
                    rows.add(new String[]{op.label, fmtDateTime(op.createdAt)});
                }
            } catch (Exception ignored) {
            }
        } else if (inputPath != null) {
            for (EffectHistoryDb.Row r : historyDb.list(fileHash(inputFileName))) {
                rows.add(new String[]{r.effects, fmtDateTime(r.timestamp)});
            }
        }
        // No history, no panel.
        if (rows.isEmpty()) {
            histCard.setVisibility(View.GONE);
            return;
        }
        histCard.setVisibility(View.VISIBLE);
        histList.removeAllViews();
        tvHistCount.setText(rows.size()
                + (rows.size() == 1 ? " effect" : " effects"));
        float density = getResources().getDisplayMetrics().density;
        int n = 1;
        for (String[] row : rows) {
            LinearLayout r = new LinearLayout(this);
            r.setOrientation(LinearLayout.HORIZONTAL);
            int vpad = (int) (4 * density);
            r.setPadding(0, vpad, 0, vpad);
            TextView label = new TextView(this);
            label.setText(n + ". " + row[0]);
            label.setTextColor(getColor(R.color.sl_txt));
            label.setTextSize(13f);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            r.addView(label, lp);
            TextView when = new TextView(this);
            when.setText(row[1]);
            when.setTextColor(getColor(R.color.sl_dim));
            when.setTextSize(11.5f);
            r.addView(when);
            histList.addView(r);
            n++;
        }
    }

    private static String fmtDateTime(long ms) {
        return new SimpleDateFormat("MMM d, h:mm a", Locale.US).format(new Date(ms));
    }

    private void openLibrary() {
        startActivity(new Intent(this, LibraryActivity.class));
    }

    /** Restore the persisted SAF save-folder URI when we still hold permission. */
    private void restoreSaveFolderUri(SharedPreferences prefs) {
        String saved = prefs.getString("save_folder_uri", null);
        if (saved == null) {
            return;
        }
        try {
            Uri uri = Uri.parse(saved);
            boolean held = false;
            List<UriPermission> perms =
                    getContentResolver().getPersistedUriPermissions();
            for (UriPermission p : perms) {
                if (p.getUri().equals(uri) && p.isWritePermission()) {
                    held = true;
                    break;
                }
            }
            if (held) {
                saveFolderUri = uri;
            } else {
                prefs.edit().remove("save_folder_uri").apply();
            }
        } catch (Exception ignored) {
            prefs.edit().remove("save_folder_uri").apply();
        }
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

    /** Expand/collapse the Advanced mic settings panel, persisted. */
    private void toggleAdvMic() {
        boolean expanded = advMicPanel.getVisibility() != View.VISIBLE;
        setAdvMicExpanded(expanded, true);
        getSharedPreferences("soundlab", MODE_PRIVATE).edit()
                .putBoolean("mic_adv_expanded", expanded).apply();
    }

    private void setAdvMicExpanded(boolean expanded, boolean animate) {
        advMicPanel.setVisibility(expanded ? View.VISIBLE : View.GONE);
        float target = expanded ? 180f : 0f;
        if (animate) {
            advMicChevron.animate().rotation(target).setDuration(200).start();
        } else {
            advMicChevron.setRotation(target);
        }
        advMicChevron.setContentDescription(expanded
                ? "Collapse advanced mic settings" : "Expand advanced mic settings");
    }

    private void togglePlayPause() {        if (isPlayerPlaying()) {
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
        // Library entries use the persisted op chain; picked files use the
        // legacy session-only history.
        int depth = currentEntryId != null
                ? undoDepth() : historyDb.depth(fileHash(inputFileName));
        undoBadge.setText(String.valueOf(depth));
        undoBadge.setBackgroundResource(
                depth > 0 ? R.drawable.bg_badge : R.drawable.bg_badge_zero);
        undoBadge.setTextColor(getColor(
                depth > 0 ? android.R.color.white : R.color.sl_mut));
        btnUndo.setEnabled(depth > 0);
        refreshHistoryPanel();
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
            btnTrim.setVisibility(View.GONE);
            btnJoin.setVisibility(View.GONE);
        } else {
            selChipRow.setBackgroundResource(R.drawable.bg_sel_chip);
            selChipText.setText(endSec != null
                    ? "Window " + fmtSec1(startSec) + " – " + fmtSec1(endSec)
                    : "Start " + fmtSec1(startSec) + " — tap again for end");
            selChipText.setTextColor(getColor(R.color.sl_teal2));
            selChipText.setTypeface(null, Typeface.BOLD);
            btnSelClear.setVisibility(View.VISIBLE);
            btnTrim.setVisibility(View.VISIBLE);
            btnJoin.setVisibility(View.VISIBLE);
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
            Uri uri = data.getData();
            // Persist the tree permission so re-export works across sessions.
            try {
                getContentResolver().takePersistableUriPermission(uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } catch (Exception ignored) {
            }
            saveFolderUri = uri;
            getSharedPreferences("soundlab", MODE_PRIVATE).edit()
                    .putString("save_folder_uri", uri.toString()).apply();
            if (saveDialog != null && saveDialog.isShowing()
                    && saveFolderLabel != null) {
                saveFolderLabel.setText(saveFolderName());
            } else {
                showSaveDialog();
            }
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
        loadWaveformForPath(inputPath);
    }

    /** Reload peaks/duration for any file: the input or an edited output. */
    private void loadWaveformForPath(String path) {
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

    private AlertDialog blockingProgress;

    /** Modal spinner dialog, visible wherever the page is scrolled. */
    private void showBlockingProgress(String message) {
        dismissBlockingProgress();
        float density = getResources().getDisplayMetrics().density;
        int pad = (int) (20 * density);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        layout.setPadding(pad, pad, pad, pad);
        layout.addView(new ProgressBar(this));
        TextView tv = new TextView(this);
        tv.setText(message);
        tv.setTextColor(getColor(R.color.sl_txt));
        tv.setTextSize(15f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMarginStart(pad);
        tv.setLayoutParams(lp);
        layout.addView(tv);
        blockingProgress = new AlertDialog.Builder(this,
                R.style.Theme_SoundLab_Dialog)
                .setView(layout)
                .setCancelable(false)
                .create();
        blockingProgress.show();
    }

    private void dismissBlockingProgress() {
        if (blockingProgress != null) {
            try {
                blockingProgress.dismiss();
            } catch (Exception ignored) {
            }
            blockingProgress = null;
        }
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
     *
     * Picked-file path (recordings go through adoptLibraryEntry instead):
     * session-only history, cache outputs, never touches the library.
     */
    private void adoptAudioFile(File wavFile, String displayName) {
        try {
            File dest = new File(getCacheDir(), "input.wav");
            if (!wavFile.getAbsolutePath().equals(dest.getAbsolutePath())) {
                copyFile(wavFile, dest);
            }
            currentEntryId = null; // picked file: not a library entry
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
            refreshPlayPause(); // input is savable as-is, no Apply needed
            updateUndoBadge();
            waveformView.setPeaks(new float[0]);
            waveformView.setDuration(0);
            tvTimeCur.setText("0:00");
            tvTimeTot.setText("0:00");
            tvSource.setVisibility(View.GONE);
            trackCard.setVisibility(View.VISIBLE);
            tvTrackName.setText(inputFileName);
            tvTrackMeta.setText("Loading…");
            updateProjectName();
            setStatus("Ready. Adjust the effects and tap Apply.");
            loadWaveform();
        } catch (Exception e) {
            setStatus("Could not read file: " + e.getMessage());
        }
    }

    // ---- Recordings library ----

    /**
     * Adopt a library entry as the current project. The pristine WAV is the
     * read-only input; the render cache backs Play/Save once ops exist.
     * History is keyed by the entry ID, so renames never orphan it.
     */
    private void adoptLibraryEntry(String entryId) {
        adoptLibraryEntry(entryId, true);
    }

    private void adoptLibraryEntry(String entryId, boolean loadNow) {
        LibraryDb.LibraryEntry e;
        try {
            e = libraryDb.getEntry(entryId);
        } catch (Exception ex) {
            setStatus("Library unavailable: " + ex.getMessage());
            return;
        }
        if (e == null) {
            setStatus("Library entry not found.");
            return;
        }
        if (!(new File(e.pristinePath).exists())) {
            // Defensive per spec: drop entries whose take is gone.
            try {
                libraryDb.deleteEntry(entryId);
            } catch (Exception ignored) {
            }
            currentEntryId = null;
            setStatus("Recording file missing — removed from library.");
            return;
        }
        currentEntryId = entryId;
        inputPath = e.pristinePath;
        inputFileName = e.name;
        releasePlayer();
        btnStop.setEnabled(false);
        currentOutputPath = null;
        audioDurationSec = 0;
        selStartSec = null;
        selEndSec = null;
        setPlayPauseEnabled(false);
        refreshPlayPause(); // the take is savable as-is, no Apply needed
        waveformView.setPeaks(new float[0]);
        waveformView.setDuration(0);
        tvTimeCur.setText("0:00");
        tvTimeTot.setText("0:00");
        tvSource.setVisibility(View.GONE);
        trackCard.setVisibility(View.VISIBLE);
        tvTrackName.setText(e.name);
        tvTrackMeta.setText("Loading…");
        updateProjectName();
        int depth = undoDepth();
        setStatus(depth > 0
                ? "Loaded from library — " + depth + " effects in history."
                : "Loaded from library — pristine take.");
        updateUndoBadge();
        if (!loadNow) {
            return; // the caller chains straight into an op that loads
        }
        boolean engineOk = LibraryDb.DSP_VERSION.equals(e.dspVersion);
        File render = libraryDb.renderFile(entryId);
        if (!engineOk && render.exists()) {
            // Engine moved on: keep the last render as the audible truth
            // and warn instead of silently re-rendering.
            currentOutputPath = render.getAbsolutePath();
            setStatus("Note: rendered with an older engine — re-apply to refresh.");
            loadWaveformForPath(currentOutputPath);
            return;
        }
        if (!engineOk) {
            setStatus("Note: engine updated since this take — re-rendering.");
        }
        if (hasRenderableOps(entryId)) {
            reRenderEntry("Rendering...");
        } else {
            loadWaveformForPath(inputPath);
        }
    }

    /** True for ops that render_chain executes (display-only ops excluded). */
    private static boolean isRenderableOp(String opType) {
        return "fx".equals(opType) || "normalize".equals(opType)
                || "trim".equals(opType) || "join".equals(opType);
    }

    private boolean hasRenderableOps(String entryId) {
        try {
            for (LibraryDb.Op op : libraryDb.listOps(entryId)) {
                if (isRenderableOp(op.opType)) {
                    return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    /** Undo depth = persisted renderable op count; survives restarts. */
    private int undoDepth() {
        if (currentEntryId == null) {
            return 0;
        }
        int n = 0;
        try {
            for (LibraryDb.Op op : libraryDb.listOps(currentEntryId)) {
                if (isRenderableOp(op.opType)) {
                    n++;
                }
            }
        } catch (Exception ignored) {
        }
        return n;
    }

    /** ops_json for render_chain: renderable ops only, in seq order. */
    private String buildOpsJson(String entryId) throws Exception {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (LibraryDb.Op op : libraryDb.listOps(entryId)) {
            if (!isRenderableOp(op.opType)) {
                continue; // livefx / fork are display-only
            }
            if (!first) {
                sb.append(",");
            }
            first = false;
            sb.append("{\"op\":\"").append(op.opType)
                    .append("\",\"params\":").append(op.paramsJson).append("}");
        }
        sb.append("]");
        return sb.toString();
    }

    /**
     * Render the entry's op chain to the render cache (call on a background
     * thread). Writes to a temp file and atomically renames into place.
     * Returns the rendered duration in seconds.
     */
    private double renderChainToCache(String entryId) throws Exception {
        LibraryDb.LibraryEntry e = libraryDb.getEntry(entryId);
        if (e == null) {
            throw new Exception("Library entry not found");
        }
        if (!Python.isStarted()) {
            Python.start(new AndroidPlatform(this));
        }
        String opsJson = buildOpsJson(entryId);
        File render = libraryDb.renderFile(entryId);
        File tmp = new File(render.getParentFile(), render.getName() + ".tmp");
        PyObject result = Python.getInstance().getModule("audiofx.android_bridge")
                .callAttr("render_chain", e.pristinePath, opsJson,
                        tmp.getAbsolutePath());
        double dur = Double.parseDouble(
                result.callAttr("get", "duration_s").toString());
        if (!tmp.renameTo(render)) {
            copyFile(tmp, render);
            tmp.delete();
        }
        return dur;
    }

    /**
     * Re-render the current entry in the background with the standard
     * progress UI, then reload the waveform.
     */
    private void reRenderEntry(String workingMsg) {
        final String entryId = currentEntryId;
        if (entryId == null) {
            return;
        }
        setBusy(true);
        waveformView.startProgressAnimation();
        setStatus(workingMsg);
        new Thread(() -> {
            try {
                final double dur = renderChainToCache(entryId);
                runOnUiThread(() -> {
                    currentOutputPath =
                            libraryDb.renderFile(entryId).getAbsolutePath();
                    audioDurationSec = dur;
                    releasePlayer();
                    btnStop.setEnabled(false);
                    loadWaveformForPath(currentOutputPath);
                    setPlayPauseEnabled(true);
                    setBusy(false);
                    waveformView.finishProgressAnimation();
                    updateUndoBadge();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    setStatus("Error: " + e.getMessage());
                    setBusy(false);
                    waveformView.clearProgress();
                    updateUndoBadge();
                });
            }
        }).start();
    }

    /**
     * Append an op to the current library entry and re-render the whole
     * chain (cumulative semantics). The op row is rolled back if the
     * render fails, so the DB never disagrees with the audio.
     */
    private void applyLibraryOp(final String opType, final String paramsJson,
                                final String label, final String workingMsg,
                                final String doneMsg, final Runnable onRendered) {
        final String entryId = currentEntryId;
        if (entryId == null) {
            return;
        }
        setBusy(true);
        if (selStartSec != null && audioDurationSec > 0
                && ("fx".equals(opType))) {
            float fromFrac = (float) (selStartSec / audioDurationSec);
            float toFrac = 1f;
            if (selEndSec != null) {
                toFrac = (float) Math.min(1.0, selEndSec / audioDurationSec);
            }
            waveformView.startProgressAnimation(fromFrac, toFrac);
        } else {
            waveformView.startProgressAnimation();
        }
        setStatus(workingMsg);
        new Thread(() -> {
            boolean added = false;
            try {
                libraryDb.addOp(entryId, opType, paramsJson, label);
                added = true;
                final double dur = renderChainToCache(entryId);
                runOnUiThread(() -> {
                    currentOutputPath =
                            libraryDb.renderFile(entryId).getAbsolutePath();
                    audioDurationSec = dur;
                    releasePlayer();
                    btnStop.setEnabled(false);
                    if (onRendered != null) {
                        onRendered.run();
                    }
                    setStatus(doneMsg);
                    setPlayPauseEnabled(true);
                    setBusy(false);
                    waveformView.finishProgressAnimation();
                    updateUndoBadge();
                });
            } catch (Exception e) {
                if (added) {
                    try {
                        libraryDb.popLastOp(entryId);
                    } catch (Exception ignored) {
                    }
                }
                runOnUiThread(() -> {
                    setStatus("Error: " + e.getMessage());
                    setBusy(false);
                    waveformView.clearProgress();
                    updateUndoBadge();
                });
            }
        }).start();
    }

    /** Pop the last renderable op and re-render (cross-session undo). */
    private void undoLibraryOp() {
        final String entryId = currentEntryId;
        if (entryId == null) {
            return;
        }
        final LibraryDb.Op last;
        try {
            List<LibraryDb.Op> ops = libraryDb.listOps(entryId);
            LibraryDb.Op found = null;
            for (int i = ops.size() - 1; i >= 0; i--) {
                if (isRenderableOp(ops.get(i).opType)) {
                    found = ops.get(i);
                    break;
                }
            }
            if (found == null
                    || !isRenderableOp(ops.get(ops.size() - 1).opType)) {
                // Nothing renderable to undo; display-only ops are never popped.
                Toast.makeText(this, "Nothing to undo", Toast.LENGTH_SHORT).show();
                return;
            }
            last = found;
        } catch (Exception e) {
            setStatus("Undo failed: " + e.getMessage());
            return;
        }
        setBusy(true);
        waveformView.startProgressAnimation();
        setStatus("Undoing...");
        new Thread(() -> {
            try {
                libraryDb.popLastOp(entryId);
                final boolean remaining = hasRenderableOps(entryId);
                final String refreshPath;
                if (remaining) {
                    renderChainToCache(entryId);
                    refreshPath = libraryDb.renderFile(entryId).getAbsolutePath();
                } else {
                    // Back to pristine: drop the render cache.
                    File render = libraryDb.renderFile(entryId);
                    if (render.exists()) {
                        render.delete();
                    }
                    LibraryDb.LibraryEntry e = libraryDb.getEntry(entryId);
                    refreshPath = e != null ? e.pristinePath : null;
                }
                if (refreshPath == null || !(new File(refreshPath).exists())) {
                    throw new Exception("audio missing after undo");
                }
                runOnUiThread(() -> {
                    currentOutputPath = remaining ? refreshPath : null;
                    releasePlayer();
                    btnStop.setEnabled(false);
                    loadWaveformForPath(refreshPath);
                    int depth = undoDepth();
                    setStatus("Undone: " + last.label + (depth > 0
                            ? " — " + depth + " op(s) left."
                            : " — back to the original audio."));
                    setBusy(false);
                    waveformView.finishProgressAnimation();
                    updateUndoBadge();
                });
            } catch (Exception e) {
                // Roll the popped op back on: re-add with the same content
                // (it regains the top seq, preserving order).
                try {
                    libraryDb.addOp(entryId, last.opType, last.paramsJson, last.label);
                } catch (Exception ignored) {
                }
                runOnUiThread(() -> {
                    setStatus("Undo failed: " + e.getMessage());
                    setBusy(false);
                    waveformView.clearProgress();
                    updateUndoBadge();
                });
            }
        }).start();
    }

    /** Disable the edit controls + progress bar while background work runs. */
    private void setBusy(boolean busy) {
        setEditButtonsEnabled(!busy);
        btnUndo.setEnabled(!busy);
        showProgress(busy);
    }

    // ---- Library op params (spec section 6) ----

    private static String fxParamsJson(double warmth, String preset, double echo,
                                       Double startSec, Double endSec) {
        return "{\"warmth\":" + warmth
                + ",\"reverb_preset\":\"" + preset + "\""
                + ",\"echo\":" + echo
                + ",\"start_sec\":" + (startSec == null ? "null" : startSec)
                + ",\"end_sec\":" + (endSec == null ? "null" : endSec) + "}";
    }

    private static String normalizeParamsJson() {
        return "{\"target_db\":-1.0,\"percentile\":99.0}";
    }

    private static String editParamsJson(double startSec, Double endSec) {
        return "{\"start_sec\":" + startSec
                + ",\"end_sec\":" + (endSec == null ? "null" : endSec) + "}";
    }

    /** Sample rate / channels / duration from a 16-bit PCM WAV header. */
    private static double[] wavInfo(File f) throws Exception {
        try (InputStream in = new FileInputStream(f)) {
            byte[] h = new byte[44];
            int r = 0;
            while (r < 44) {
                int n = in.read(h, r, 44 - r);
                if (n < 0) {
                    throw new Exception("truncated WAV header");
                }
                r += n;
            }
            int channels = (h[22] & 0xff) | ((h[23] & 0xff) << 8);
            int sampleRate = (h[24] & 0xff) | ((h[25] & 0xff) << 8)
                    | ((h[26] & 0xff) << 16) | ((h[27] & 0xff) << 24);
            long dataBytes = Math.max(0, f.length() - 44);
            double dur = (sampleRate > 0 && channels > 0)
                    ? dataBytes / (double) (sampleRate * channels * 2) : 0;
            return new double[]{sampleRate, channels, dur};
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
        btnTrim.setEnabled(false);
        btnJoin.setEnabled(false);
        btnNormalize.setEnabled(false);
        btnNormalize.setAlpha(0.4f);
        // Live waveform: capture threads feed block peaks via the listener.
        livePeakAcc = 0f;
        recorder.setBlockListener(this::onCaptureBlock);
        liveMonitor.setBlockListener(this::onCaptureBlock);
        waveformView.startLive();
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
        btnTrim.setEnabled(true);
        btnJoin.setEnabled(true);
        btnNormalize.setEnabled(true);
        btnNormalize.setAlpha(1f);
        waveformView.endLive();
        recorder.setBlockListener(null);
        liveMonitor.setBlockListener(null);
        seekWarmth.setEnabled(true);
        seekEcho.setEnabled(true);
        chipStudio.setEnabled(true);
        chipHall.setEnabled(true);
        chipBath.setEnabled(true);
        chipCave.setEnabled(true);
    }

    private void stopRecording() {
        final boolean wasLive = liveMonitor.isRecording();
        // Snapshot UI state before stop() resets the session labels.
        final String captureLabel = wasLive
                ? liveMonitor.activeLabel().replace(" live FX", "")
                : recorder.activeLabel();
        final int liveWarmthPct = seekWarmth.getProgress();
        final String livePreset = reverbPreset;
        final int liveEchoPct = seekEcho.getProgress();
        final File take;
        if (wasLive) {
            take = liveMonitor.stop();
        } else {
            take = recorder.stop();
        }
        exitRecordingUi();
        if (take == null) {
            setStatus("Recording failed — nothing captured.");
            return;
        }
        // Durable home for the take: filesDir/library/takes/<uuid>.wav.
        // The pristine stays raw; auto-normalize becomes op #1 below.
        final String entryId = UUID.randomUUID().toString();
        final File dest = libraryDb.takeFile(entryId);
        try {
            copyFile(take, dest);
            take.delete();
            double[] info = wavInfo(dest);
            String name = pendingRecName;
            if (name.toLowerCase(Locale.US).endsWith(".wav")) {
                name = name.substring(0, name.length() - 4);
            }
            libraryDb.createEntryWithId(entryId, name, System.currentTimeMillis(),
                    info[2], (int) info[0], (int) info[1],
                    wasLive ? "mic-livefx" : "mic", captureLabel,
                    LibraryDb.DSP_VERSION, dest.getAbsolutePath());
            if (wasLive) {
                // Display-only first op: the wet take IS the pristine.
                libraryDb.addOp(entryId, "livefx",
                        "{\"warmth\":" + (liveWarmthPct / 100.0)
                                + ",\"reverb_preset\":\"" + livePreset + "\""
                                + ",\"echo\":" + (liveEchoPct / 100.0) + "}",
                        "Live FX · Warmth " + liveWarmthPct + "%, "
                                + reverbDisplayName(livePreset)
                                + ", Echo " + liveEchoPct + "%");
            }
        } catch (Exception e) {
            dest.delete(); // don't orphan a take file with no DB row
            setStatus("Could not save recording: " + e.getMessage());
            return;
        }
        // Adopt without an initial load when auto-normalize follows: the
        // normalize op's own render completion loads the waveform, which
        // avoids a pristine-load racing the normalized render.
        adoptLibraryEntry(entryId, !switchAutoNorm.isChecked());
        if (switchAutoNorm.isChecked()) {
            normalizeAudio();
        }
    }

    /** Library path for Apply: append an fx op, re-render cumulatively. */
    private void applyFxOp() {
        final double warmth = seekWarmth.getProgress() / 100.0;
        final String preset = reverbPreset;
        final double echo = seekEcho.getProgress() / 100.0;
        final String windowDesc = selStartSec != null
                ? " [" + fmtSec(selStartSec) + "–"
                        + (selEndSec != null ? fmtSec(selEndSec) : "end") + "]"
                : "";
        final String label = "Warmth " + seekWarmth.getProgress() + "%, Reverb "
                + preset + ", Echo " + seekEcho.getProgress() + "%" + windowDesc;
        applyLibraryOp("fx", fxParamsJson(warmth, preset, echo, selStartSec, selEndSec),
                label, "Processing...",
                "Done — " + label + " (cumulative).",
                () -> loadWaveformForPath(currentOutputPath));
    }

    /** Library path for Normalize: append the op, re-render. */
    private void applyNormalizeOp() {
        applyLibraryOp("normalize", normalizeParamsJson(), "Normalize to -1 dBFS",
                "Normalizing...", "Normalized to -1 dBFS.",
                () -> loadWaveformForPath(currentOutputPath));
    }

    /** Library path for Trim/Join: append the op, re-render. */
    private void applyEditOp(String op, double startSec, Double endSec) {
        final String rangeDesc = fmtSec(startSec) + "–"
                + (endSec != null ? fmtSec(endSec) : "end");
        final String label = op.equals("trim")
                ? "Trim " + rangeDesc + " to silence"
                : "Join (removed " + rangeDesc + ")";
        final String workingMsg = op.equals("trim") ? "Trimming to silence..." : "Joining...";
        final String doneMsg = op.equals("trim")
                ? "Trimmed " + rangeDesc + " to silence — selection kept."
                : "Joined: removed " + rangeDesc + ".";
        final double s = startSec;
        final Double e = endSec;
        applyLibraryOp(op, editParamsJson(startSec, endSec), label, workingMsg, doneMsg,
                () -> {
                    if (op.equals("trim")) {
                        // Duration unchanged: zero the affected peak buckets
                        // locally; the selection stays exactly as it was.
                        // (audioDurationSec is already the rendered duration.)
                        zeroPeakRange(s, e != null ? e : audioDurationSec);
                    } else {
                        loadWaveformForPath(currentOutputPath);
                    }
                });
    }

    private void processAudio() {
        if (inputPath == null) {
            Toast.makeText(this, "Pick an audio file first", Toast.LENGTH_SHORT).show();
            return;
        }
        if (currentEntryId != null) {
            // Library entry: cumulative op appended to the persisted chain.
            applyFxOp();
            return;
        }
        // Legacy path for picked files (session-only history, cache outputs).
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
        if (currentEntryId != null) {
            // Library entry: cumulative op appended to the persisted chain.
            applyNormalizeOp();
            return;
        }
        // Legacy path for picked files (session-only history, cache outputs).
        final String hash = fileHash(inputFileName);
        // Every application writes its own file so Undo can restore earlier ones.
        final String outPath = new File(getCacheDir(),
                "output_" + System.currentTimeMillis() + "_" + (outputSeq++) + ".wav")
                .getAbsolutePath();
        final long timestamp = System.currentTimeMillis();
        final String effectsDesc = "Normalize to -1 dBFS";
        btnNormalize.setEnabled(false);
        btnNormalize.setAlpha(0.4f);
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
                final String msg = "Normalized: p"
                        + result.callAttr("get", "percentile").toString() + " "
                        + result.callAttr("get", "ref_percentile_db").toString() + " dB to "
                        + result.callAttr("get", "peak_after_db").toString() + " dBFS";
                runOnUiThread(() -> {
                    setStatus(msg);
                    setPlayPauseEnabled(true);
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
                    btnNormalize.setAlpha(1f);
                    btnProcess.setEnabled(true);
                    btnProcess.setAlpha(1f);
                });
            }
        }).start();
    }

    private void trimSelection() {
        if (selStartSec == null) {
            Toast.makeText(this, "Select a range on the waveform first",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        editSelection("trim", selStartSec, selEndSec);
    }

    private void joinSelection() {
        if (selStartSec == null) {
            Toast.makeText(this, "Select a range on the waveform first",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        double s = selStartSec;
        Double e = selEndSec;
        // The joined region disappears; drop the selection first so the
        // later waveform reload has nothing stale to clear.
        waveformView.clearSelection();
        editSelection("join", s, e);
    }

    /**
     * Structural edit of the current audio (processed output when there
     * is one, else the input), undoable like any other step.
     * op = "trim": the window becomes silence (selection kept);
     * op = "join": the window is cut out and the parts glued together.
     * endSec null = to the end of the file.
     */
    private void editSelection(String op, double startSec, Double endSec) {
        final String srcPath = (currentOutputPath != null
                && new File(currentOutputPath).exists()) ? currentOutputPath : inputPath;
        if (srcPath == null || !(new File(srcPath).exists())) {
            Toast.makeText(this, "Pick an audio file or record first",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        if (currentEntryId != null) {
            // Library entry: cumulative op appended to the persisted chain.
            // (joinSelection already cleared the selection above.)
            applyEditOp(op, startSec, endSec);
            return;
        }
        // Legacy path for picked files (session-only history, cache outputs).
        final String hash = fileHash(inputFileName);
        final String rangeDesc = fmtSec(startSec) + "–"
                + (endSec != null ? fmtSec(endSec) : "end");
        final String effectsDesc = op.equals("trim")
                ? "Trim " + rangeDesc + " to silence"
                : "Join (removed " + rangeDesc + ")";
        // Every application writes its own file so Undo can restore earlier ones.
        final String outPath = new File(getCacheDir(),
                "output_" + System.currentTimeMillis() + "_" + (outputSeq++) + ".wav")
                .getAbsolutePath();
        final long timestamp = System.currentTimeMillis();
        releasePlayer();
        btnStop.setEnabled(false);
        setEditButtonsEnabled(false);
        btnUndo.setEnabled(false); // no racing an undo mid-edit
        showProgress(true);
        waveformView.startProgressAnimation();
        setStatus(op.equals("trim") ? "Trimming to silence..." : "Joining...");
        new Thread(() -> {
            try {
                if (!Python.isStarted()) {
                    Python.start(new AndroidPlatform(this));
                }
                PyObject result = Python.getInstance().getModule("audiofx.android_bridge")
                        .callAttr(op.equals("trim") ? "trim_to_silence" : "join_remove",
                                srcPath, outPath, startSec, endSec);
                final double dur = Double.parseDouble(
                        result.callAttr("get", "duration_s").toString());
                historyDb.log(hash, effectsDesc, timestamp, outPath);
                currentOutputPath = outPath;
                audioDurationSec = dur;
                final String msg;
                if (op.equals("trim")) {
                    msg = "Trimmed " + rangeDesc + " to silence — selection kept.";
                } else {
                    msg = "Joined: removed "
                            + result.callAttr("get", "removed_s").toString()
                            + "s, new duration " + fmtSec1(dur) + ".";
                }
                runOnUiThread(() -> {
                    if (op.equals("trim")) {
                        // Duration unchanged: zero the affected peak buckets
                        // locally; the selection stays exactly as it was.
                        zeroPeakRange(startSec, endSec != null ? endSec : dur);
                    } else {
                        loadWaveformForPath(outPath);
                    }
                    setStatus(msg);
                    setPlayPauseEnabled(true);
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
                runOnUiThread(() -> setEditButtonsEnabled(true));
            }
        }).start();
    }

    /** Zero the displayed peak buckets overlapping the startSec/endSec window. */
    private void zeroPeakRange(double startSec, double endSec) {
        float[] peaks = waveformView.getPeaks();
        int n = peaks.length;
        if (n == 0 || audioDurationSec <= 0) {
            return;
        }
        int from = Math.max(0, (int) (startSec / audioDurationSec * n));
        int to = Math.min(n, (int) Math.ceil(endSec / audioDurationSec * n));
        for (int i = from; i < to; i++) {
            peaks[i] = 0f;
        }
        waveformView.updatePeaks(peaks);
    }

    /** Enable/disable the buttons that must not run during an edit. */
    private void setEditButtonsEnabled(boolean enabled) {
        btnTrim.setEnabled(enabled);
        btnJoin.setEnabled(enabled);
        btnProcess.setEnabled(enabled);
        btnProcess.setAlpha(enabled ? 1f : 0.55f);
        btnNormalize.setEnabled(enabled);
    }

    private void undoLastEffect() {
        if (currentEntryId != null) {
            // Library entry: pop the persisted op chain (cross-session).
            undoLibraryOp();
            return;
        }
        // Legacy path for picked files (session-only history, cache outputs).
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
        // Structural edits (trim/join) change the audio itself: reload the
        // waveform so peaks and duration match the restored file.
        String refreshPath = (currentOutputPath != null
                && new File(currentOutputPath).exists()) ? currentOutputPath : inputPath;
        if (refreshPath != null) {
            loadWaveformForPath(refreshPath);
        }
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
        showSaveDialog();
    }

    /**
     * Save dialog: the user names the MP3 and sees/picks the destination
     * folder, whether or not one was chosen before.
     */
    private void showSaveDialog() {
        if (saveDialog != null && saveDialog.isShowing()) {
            return;
        }
        final float density = getResources().getDisplayMetrics().density;
        int pad = (int) (20 * density);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, (int) (8 * density), pad, (int) (8 * density));

        TextView nameLabel = new TextView(this);
        nameLabel.setText("File name");
        nameLabel.setTextColor(getColor(R.color.sl_mut));
        nameLabel.setTextSize(12);
        root.addView(nameLabel);

        final EditText nameInput = new EditText(this);
        String stem = toMp3Name(inputFileName);
        if (stem.toLowerCase(Locale.US).endsWith(".mp3")) {
            stem = stem.substring(0, stem.length() - 4);
        }
        nameInput.setText(stem);
        nameInput.setTextColor(getColor(R.color.sl_txt));
        nameInput.setHintTextColor(getColor(R.color.sl_dim));
        nameInput.setSelectAllOnFocus(true);
        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        nameLp.bottomMargin = (int) (16 * density);
        root.addView(nameInput, nameLp);

        TextView folderLabel = new TextView(this);
        folderLabel.setText("Save folder");
        folderLabel.setTextColor(getColor(R.color.sl_mut));
        folderLabel.setTextSize(12);
        root.addView(folderLabel);

        LinearLayout folderRow = new LinearLayout(this);
        folderRow.setOrientation(LinearLayout.HORIZONTAL);
        folderRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        saveFolderLabel = new TextView(this);
        saveFolderLabel.setText(saveFolderName());
        saveFolderLabel.setTextColor(getColor(R.color.sl_txt));
        saveFolderLabel.setTextSize(14);
        LinearLayout.LayoutParams folderLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        folderRow.addView(saveFolderLabel, folderLp);
        Button chooseBtn = new Button(this);
        chooseBtn.setText("Choose…");
        chooseBtn.setTextColor(getColor(R.color.sl_teal2));
        chooseBtn.setBackgroundResource(R.drawable.bg_ghost);
        chooseBtn.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            startActivityForResult(intent, PICK_FOLDER);
        });
        folderRow.addView(chooseBtn);
        root.addView(folderRow);

        saveDialog = new AlertDialog.Builder(this, R.style.Theme_SoundLab_Dialog)
                .setTitle("Save as MP3")
                .setView(root)
                .setPositiveButton("Save", null)
                .setNegativeButton("Cancel", null)
                .setOnDismissListener(d -> saveDialog = null)
                .show();
        // The dialog theme inherits light Material buttons; re-tint them.
        saveDialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setTextColor(getColor(R.color.sl_teal2));
        saveDialog.getButton(AlertDialog.BUTTON_NEGATIVE)
                .setTextColor(getColor(R.color.sl_mut));
        // Manual click handling: the Save tap needs a folder first, and a
        // collision check before the dialog dismisses itself.
        saveDialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    String name = sanitizeMp3Name(nameInput.getText().toString());
                    if (saveFolderUri == null) {
                        Toast.makeText(this, "Choose a save folder first",
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (mp3ExistsInFolder(name)) {
                        showCollisionDialog(name);
                    } else {
                        saveDialog.dismiss();
                        doSaveMp3(name, false);
                    }
                });
    }

    /** "File exists" choice: Overwrite / Keep both. Never silently overwrite. */
    private void showCollisionDialog(final String name) {
        new AlertDialog.Builder(this, R.style.Theme_SoundLab_Dialog)
                .setTitle("File exists")
                .setMessage("\u201c" + name + "\u201d is already in this folder.")
                .setPositiveButton("Overwrite", (d, w) -> {
                    if (saveDialog != null) {
                        saveDialog.dismiss();
                    }
                    doSaveMp3(name, true);
                })
                .setNegativeButton("Keep both", (d, w) -> {
                    if (saveDialog != null) {
                        saveDialog.dismiss();
                    }
                    doSaveMp3(name, false);
                })
                .setNeutralButton("Cancel", null)
                .show();
    }

    private boolean mp3ExistsInFolder(String name) {
        if (saveFolderUri == null) {
            return false;
        }
        try {
            DocumentFile dir = DocumentFile.fromTreeUri(this, saveFolderUri);
            return dir != null && dir.findFile(name) != null;
        } catch (Exception ignored) {
            return false;
        }
    }

    private String saveFolderName() {
        if (saveFolderUri == null) {
            return "No folder chosen yet";
        }
        try {
            DocumentFile dir = DocumentFile.fromTreeUri(this, saveFolderUri);
            if (dir != null && dir.getName() != null) {
                return dir.getName();
            }
        } catch (Exception ignored) {
        }
        return "Chosen folder";
    }

    private static String sanitizeMp3Name(String raw) {
        String name = raw.trim().replaceAll("[/\\\\]", "_");
        if (name.isEmpty()) {
            name = "soundlab";
        }
        if (!name.toLowerCase(Locale.US).endsWith(".mp3")) {
            name += ".mp3";
        }
        return name;
    }

    private void doSaveMp3(String mp3Name, boolean overwrite) {
        // Save the processed output when there is one, else the input as-is.
        final String srcPath = (currentOutputPath != null
                && new File(currentOutputPath).exists()) ? currentOutputPath : inputPath;
        final boolean raw = srcPath.equals(inputPath);
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
                String savedAs = copyToSaveFolder(tmpMp3, mp3Name, overwrite);
                final String doneMsg = raw ? "Saved (no effects): " + savedAs
                        : "Saved: " + savedAs;
                runOnUiThread(() -> {
                    setStatus(doneMsg);
                    if (currentEntryId != null) {
                        try {
                            libraryDb.logExport(currentEntryId, savedAs,
                                    saveFolderName(), undoDepth());
                            maybeAdoptExportName(currentEntryId, savedAs);
                        } catch (Exception ignored) {
                        }
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> setStatus("Save failed: " + e.getMessage()));
            } finally {
                runOnUiThread(() -> {
                    showProgress(false);
                });
            }
        }).start();
    }

    /**
     * Default the project name to the user's export filename: when the
     * entry still has its auto-generated "Recording ..." name, adopt the
     * filename the user chose in the save dialog. Manual renames are
     * never overridden.
     */
    private void maybeAdoptExportName(String entryId, String mp3Name) {
        LibraryDb.LibraryEntry e;
        try {
            e = libraryDb.getEntry(entryId);
        } catch (Exception ex) {
            return;
        }
        if (e == null || e.name == null
                || !e.name.matches("Recording \\d{4}-\\d{2}-\\d{2} \\d{2}-\\d{2}")) {
            return;
        }
        String stem = mp3Name;
        if (stem.toLowerCase(Locale.US).endsWith(".mp3")) {
            stem = stem.substring(0, stem.length() - 4);
        }
        stem = stem.trim();
        if (stem.isEmpty() || stem.equals(e.name)) {
            return;
        }
        try {
            libraryDb.renameEntry(entryId, stem);
        } catch (Exception ignored) {
            return;
        }
        inputFileName = stem;
        tvTrackName.setText(stem);
        updateProjectName();
        setStatus("Saved: " + mp3Name + " — project renamed to " + stem + ".");
    }

    /** Size-optimized bitrate for shared MP3s (CBR). */
    private static final int SHARE_BITRATE_KBPS = 96;

    /** Test interstitial unit: swap for the real one at release. */
    private static final String SHARE_INTERSTITIAL_UNIT =
            "ca-app-pub-3940256099942544/1033173712";

    private InterstitialAd shareInterstitial;
    // Share flow state. The interstitial covers the encode wait, so the
    // user stares at the ad instead of a spinner.
    private volatile boolean shareInProgress;
    private volatile boolean shareAdShowing;
    private volatile boolean shareEncodeDone;
    private volatile File shareMp3Result;
    private volatile String shareEncodeError;

    /** Preloads the interstitial shown in the share flow. */
    private void loadShareInterstitial() {
        InterstitialAd.load(this, SHARE_INTERSTITIAL_UNIT,
                new AdRequest.Builder().build(),
                new InterstitialAdLoadCallback() {
                    @Override
                    public void onAdLoaded(InterstitialAd ad) {
                        shareInterstitial = ad;
                        shareInterstitial.setFullScreenContentCallback(
                                new FullScreenContentCallback() {
                                    @Override
                                    public void onAdDismissedFullScreenContent() {
                                        onShareAdGone();
                                    }

                                    @Override
                                    public void onAdFailedToShowFullScreenContent(
                                            AdError e) {
                                        onShareAdGone();
                                    }
                                });
                    }

                    @Override
                    public void onAdFailedToLoad(LoadAdError e) {
                        shareInterstitial = null;
                    }
                });
    }

    private void onShareAdGone() {
        shareAdShowing = false;
        shareInterstitial = null;
        loadShareInterstitial();
        if (shareEncodeDone) {
            finishShare();
        } else {
            showBlockingProgress("Preparing share file...");
        }
    }

    /**
     * Share entry point: encoding starts in the background and the
     * interstitial covers the wait. When no ad is ready (offline or no
     * fill), the blocking progress dialog covers it instead, so share
     * never blocks on ads.
     */
    private void shareAudio() {
        if (shareInProgress) {
            return;
        }
        final String srcPath = (currentOutputPath != null
                && new File(currentOutputPath).exists()) ? currentOutputPath : inputPath;
        if (srcPath == null || !(new File(srcPath).exists())) {
            setStatus("Nothing to share yet.");
            return;
        }
        shareInProgress = true;
        shareAdShowing = false;
        shareEncodeDone = false;
        shareMp3Result = null;
        shareEncodeError = null;
        new Thread(() -> encodeShareFile(srcPath)).start();
        if (shareInterstitial != null) {
            shareAdShowing = true;
            shareInterstitial.show(this);
        } else {
            loadShareInterstitial();
            showBlockingProgress("Preparing share file...");
        }
    }

    /** Background MP3 encode for the share flow. */
    private void encodeShareFile(String srcPath) {
        try {
            if (!Python.isStarted()) {
                Python.start(new AndroidPlatform(this));
            }
            File shareDir = new File(getCacheDir(), "share");
            if (!shareDir.exists() && !shareDir.mkdirs()) {
                throw new Exception("Could not create share folder");
            }
            String base = inputFileName;
            int dot = base.lastIndexOf('.');
            if (dot > 0) {
                base = base.substring(0, dot);
            }
            base = base.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
            if (base.isEmpty()) {
                base = "soundlab";
            }
            File mp3 = new File(shareDir, base + ".mp3");
            Python.getInstance().getModule("audiofx.android_bridge").callAttr(
                    "encode_wav_to_mp3", srcPath, mp3.getAbsolutePath(),
                    SHARE_BITRATE_KBPS);
            shareMp3Result = mp3;
        } catch (Exception e) {
            shareEncodeError = e.getMessage();
        } finally {
            shareEncodeDone = true;
            runOnUiThread(this::onShareEncodeDone);
        }
    }

    private void onShareEncodeDone() {
        if (shareAdShowing) {
            return; // the ad-dismiss handler finishes the flow
        }
        finishShare();
    }

    /** Launches the app picker once the encode is done and no ad is up. */
    private void finishShare() {
        dismissBlockingProgress();
        shareInProgress = false;
        if (shareEncodeError != null) {
            setStatus("Share failed: " + shareEncodeError);
            return;
        }
        if (shareMp3Result == null) {
            setStatus("Nothing to share yet.");
            return;
        }
        try {
            Uri uri = FileProvider.getUriForFile(this,
                    getPackageName() + ".fileprovider", shareMp3Result);
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("audio/mpeg");
            send.putExtra(Intent.EXTRA_STREAM, uri);
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            setStatus("Sharing " + shareMp3Result.getName() + ".");
            startActivity(Intent.createChooser(send, "Share audio"));
        } catch (Exception e) {
            setStatus("Share failed: " + e.getMessage());
        }
    }

    private String copyToSaveFolder(File src, String baseName, boolean overwrite)
            throws Exception {
        DocumentFile dir = DocumentFile.fromTreeUri(this, saveFolderUri);
        if (dir == null || !dir.canWrite()) {
            throw new Exception("Save folder is not writable");
        }
        String name = baseName;
        if (overwrite) {
            DocumentFile existing = dir.findFile(baseName);
            if (existing != null && !existing.delete()) {
                throw new Exception("Could not overwrite existing file");
            }
        } else {
            int copy = 1;
            while (dir.findFile(name) != null) {
                int dot = baseName.lastIndexOf('.');
                String stem = dot > 0 ? baseName.substring(0, dot) : baseName;
                String ext = dot > 0 ? baseName.substring(dot) : "";
                name = stem + " (" + (copy++) + ")" + ext;
            }
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
        dismissBlockingProgress();
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
