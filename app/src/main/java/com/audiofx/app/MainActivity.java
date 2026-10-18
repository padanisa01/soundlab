package com.audiofx.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Handler;
import android.os.Looper;
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
import android.util.Log;
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
import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.AdSize;
import com.google.android.gms.ads.AdView;
import com.google.android.gms.ads.MobileAds;

import com.chaquo.python.PyObject;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;

import org.json.JSONArray;
import org.json.JSONObject;

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

    private TextView tvProjectName;
    private ImageButton btnMenu;
    private AdView adView;
    private PremiumBilling premiumBilling;
    private Button btnOpen;
    private Button btnSave2;
    private Button btnShare;
    private Button btnDiagnose;
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
    private android.app.AlertDialog diagDialog;
    /** Session-scoped diagnosis cache: re-show instantly until an op is
     * applied or the audio changes. Keyed by the diagnosed file path. */
    private String cachedDiagJson;
    private String cachedDiagPath;
    /** Tune-up profile metadata JSON (from list_tune_up_profiles). */
    private String cachedProfilesJson;
    /** Live refs to the open diagnosis dialog's profile UI. */
    private java.util.ArrayList<Button> diagProfileBtns;
    private TextView diagProfileDesc;
    private Button diagTuneUpBtn;
    private TextView diagSummaryTv;
    private String diagFindingsJson;
    /** Executive-summary cache, keyed by findings hash + profile. */
    private final java.util.HashMap<String, String> diagSummaryCache =
            new java.util.HashMap<>();
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

        tvProjectName = findViewById(R.id.tv_project_name);
        btnMenu = findViewById(R.id.btn_menu);
        btnMenu.setOnClickListener(v -> showMainMenu());
        btnOpen = findViewById(R.id.btn_open);
        btnSave2 = findViewById(R.id.btn_save2);
        btnShare = findViewById(R.id.btn_share);
        btnDiagnose = findViewById(R.id.btn_diagnose);
        btnOpen.setOnClickListener(v -> pickAudio());
        btnSave2.setOnClickListener(v -> onSaveClicked());
        btnShare.setOnClickListener(v -> shareAudio());
        btnDiagnose.setOnClickListener(v -> onDiagnoseClicked());
        updateTrackActions();
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
        switchNs.setChecked(prefs.getBoolean("mic_ns", true));
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
        // Premium users never see ads.
        MobileAds.initialize(this, status -> {
        });
        adView = findViewById(R.id.ad_view);
        if (isPremium()) {
            adView.setVisibility(View.GONE);
        } else {
            adView.loadAd(new AdRequest.Builder().build());
        }
        premiumBilling = new PremiumBilling(this, premium -> {
            if (premium && adView != null) {
                adView.setVisibility(View.GONE);
                setStatus("Premium activated — all ads removed.");
            }
            if (premium) {
                // Unlock any Pro tune-up recipes in the open report.
                refreshDiagProfileSegments();
            }
        });
        premiumBilling.start();

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

    /** Hamburger menu: Open, Library, Save as MP3, Share, Buy premium. */
    private void showMainMenu() {
        boolean canSave = inputPath != null && new File(inputPath).exists();
        List<String> menu = new ArrayList<>();
        menu.add("Open audio file");
        menu.add("Recordings library");
        if (canSave) {
            menu.add("Save as MP3");
            menu.add("Share");
        }
        if (!isPremium()) {
            menu.add("Buy premium version");
        }
        final String[] items = menu.toArray(new String[0]);
        new AlertDialog.Builder(this, R.style.Theme_SoundLab_Dialog)
                .setItems(items, (d, which) -> {
                    String sel = items[which];
                    if (sel.equals("Open audio file")) {
                        pickAudio();
                    } else if (sel.equals("Recordings library")) {
                        openLibrary();
                    } else if (sel.equals("Share")) {
                        shareAudio();
                    } else if (sel.equals("Buy premium version")) {
                        buyPremium();
                    } else {
                        onSaveClicked();
                    }
                })
                .show();
    }

    /** Starts the one-time premium purchase flow. */
    private void buyPremium() {
        if (premiumBilling != null) {
            premiumBilling.buyPremium();
        }
    }

    /**
     * The Open/Save/Share bar is always visible. Save and Share are enabled
     * only when a track is loaded; Open is always available.
     */
    private void updateTrackActions() {
        boolean hasTrack = inputPath != null && new File(inputPath).exists();
        btnSave2.setEnabled(hasTrack);
        btnSave2.setAlpha(hasTrack ? 1f : 0.4f);
        btnShare.setEnabled(hasTrack);
        btnShare.setAlpha(hasTrack ? 1f : 0.4f);
        btnDiagnose.setEnabled(hasTrack);
        btnDiagnose.setAlpha(hasTrack ? 1f : 0.4f);
    }

    /** Project status bar: entry/file name, or an untitled placeholder. */
    private void updateProjectName() {        String name;
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
            final String displayName = getDisplayName(uri);
            final boolean compressed = displayName.toLowerCase(Locale.US)
                    .endsWith(".mp3");
            if (compressed) {
                // MP3: decode to WAV in the background, then adopt as usual.
                setStatus("Decoding MP3...");
                showProgress(true);
                new Thread(() -> {
                    try {
                        File dest = new File(getCacheDir(), "input.wav");
                        AudioDecoder.decodeToWav(this, uri, dest);
                        runOnUiThread(() -> {
                            showProgress(false);
                            adoptAudioFile(dest, displayName);
                        });
                    } catch (Exception e) {
                        runOnUiThread(() -> {
                            showProgress(false);
                            setStatus("Could not decode MP3: " + e.getMessage());
                        });
                    }
                }).start();
                return;
            }
            try {
                File dest = new File(getCacheDir(), "input.wav");
                copyUriToFile(uri, dest);
                adoptAudioFile(dest, displayName);
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

    private static String toMp3Name(String audioName) {
        String lower = audioName.toLowerCase(Locale.US);
        if (lower.endsWith(".wav") || lower.endsWith(".mp3")) {
            return audioName.substring(0, audioName.length() - 4) + ".mp3";
        }
        return audioName + ".mp3";
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
     * Make {@code wavFile} the current input as a first-class library entry:
     * the decoded WAV is copied to the durable pristine, so effects, undo
     * history and the project itself survive restarts — same as recordings.
     * (Spec change 2026-10-17: picked files are no longer session-only.)
     */
    private void adoptAudioFile(File wavFile, String displayName) {
        final String entryId = UUID.randomUUID().toString();
        final File dest = libraryDb.takeFile(entryId);
        try {
            copyFile(wavFile, dest);
            double[] info = wavInfo(dest);
            String name = displayName;
            int dot = name.lastIndexOf('.');
            if (dot > 0) {
                name = name.substring(0, dot);
            }
            name = name.trim();
            if (name.isEmpty()) {
                name = "Imported audio";
            }
            libraryDb.createEntryWithId(entryId, name, System.currentTimeMillis(),
                    info[2], (int) info[0], (int) info[1],
                    "import", displayName, LibraryDb.DSP_VERSION,
                    dest.getAbsolutePath());
        } catch (Exception e) {
            dest.delete(); // don't orphan a take file with no DB row
            setStatus("Could not import file: " + e.getMessage());
            return;
        }
        adoptLibraryEntry(entryId);
        setStatus("Ready. Adjust the effects and tap Apply.");
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
        cachedDiagJson = null;
        cachedDiagPath = null;
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
        updateTrackActions();
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
                || "trim".equals(opType) || "join".equals(opType)
                || "dc_remove".equals(opType) || "fade_ends".equals(opType)
                || "silence_trim".equals(opType) || "hum_notch".equals(opType)
                || "declip".equals(opType) || "denoise".equals(opType)
                || "deplosive".equals(opType) || "deess".equals(opType)
                || "level".equals(opType) || "loudness".equals(opType)
                || "eq_balance".equals(opType) || "tune_up".equals(opType);
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
                cachedDiagJson = null;
                cachedDiagPath = null;
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

    /** Run the diagnostics on the current output, then show the report. */
    private void onDiagnoseClicked() {
        final String entryId = currentEntryId;
        // Same fallback playback uses: the render when there is one,
        // otherwise the pristine input (fresh picks have no render yet).
        String path = (currentOutputPath != null
                && new File(currentOutputPath).exists()) ? currentOutputPath
                        : inputPath;
        if (entryId == null || path == null || !new File(path).exists()) {
            setStatus("Open or record audio first.");
            return;
        }
        final String diagPath = path;
        if (diagPath.equals(cachedDiagPath) && cachedDiagJson != null) {
            showDiagnosisReport(cachedDiagJson);
            return;
        }
        setBusy(true);
        setStatus("Diagnosing...");
        waveformView.startProgressAnimation();
        new Thread(() -> {
            try {
                if (!Python.isStarted()) {
                    Python.start(new AndroidPlatform(this));
                }
                final String findingsJson = Python.getInstance()
                        .getModule("audiofx.android_bridge")
                        .callAttr("diagnose_file", diagPath).toString();
                cachedProfilesJson = Python.getInstance()
                        .getModule("audiofx.android_bridge")
                        .callAttr("list_tune_up_profiles").toString();
                runOnUiThread(() -> {
                    setBusy(false);
                    waveformView.finishProgressAnimation();
                    cachedDiagPath = diagPath;
                    cachedDiagJson = findingsJson;
                    showDiagnosisReport(findingsJson);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    setBusy(false);
                    waveformView.clearProgress();
                    setStatus("Diagnosis failed: " + e.getMessage());
                });
            }
        }).start();
    }

    /** The mechanic's report card (Option A): score hero, grouped findings,
     * profile picker, sticky Tune Up bar. */
    private void showDiagnosisReport(final String findingsJson) {
        final float dp = getResources().getDisplayMetrics().density;
        JSONArray findings;
        try {
            findings = new JSONArray(findingsJson);
        } catch (Exception e) {
            setStatus("Diagnosis failed: bad report.");
            return;
        }
        java.util.ArrayList<JSONObject> warns = new java.util.ArrayList<>();
        java.util.ArrayList<JSONObject> infos = new java.util.ArrayList<>();
        java.util.ArrayList<JSONObject> oks = new java.util.ArrayList<>();
        boolean hasFixable = false;
        for (int i = 0; i < findings.length(); i++) {
            JSONObject f = findings.optJSONObject(i);
            if (f == null) {
                continue;
            }
            String sev = f.optString("severity", "info");
            if ("warn".equals(sev)) {
                warns.add(f);
                if (!f.isNull("fix")) {
                    hasFixable = true;
                }
            } else if ("ok".equals(sev)) {
                oks.add(f);
            } else {
                infos.add(f);
                if (!f.isNull("fix")) {
                    hasFixable = true;
                }
            }
        }

        int score = Math.max(0, 100 - 10 * warns.size() - 2 * infos.size());
        String verdict = score >= 85 ? "Excellent" : score >= 70 ? "Good"
                : score >= 50 ? "Needs work" : "Rough";

        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * dp);
        list.setPadding(pad, pad, pad, (int) (8 * dp));

        // LLM executive summary (Groq/Qwen). Fills in async; the card is
        // hidden entirely when no API key is configured.
        diagFindingsJson = findingsJson;
        final String groqKey = BuildConfig.GROQ_API_KEY;
        LinearLayout summaryCard = new LinearLayout(this);
        summaryCard.setOrientation(LinearLayout.VERTICAL);
        summaryCard.setBackgroundResource(R.drawable.bg_card);
        LinearLayout.LayoutParams scLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        scLp.setMargins(0, 0, 0, (int) (12 * dp));
        summaryCard.setLayoutParams(scLp);
        summaryCard.setPadding((int) (14 * dp), (int) (11 * dp),
                (int) (14 * dp), (int) (12 * dp));
        TextView summaryLabel = new TextView(this);
        summaryLabel.setText("SUMMARY");
        summaryLabel.setTextColor(getColor(R.color.sl_mut));
        summaryLabel.setTextSize(10.5f);
        summaryCard.addView(summaryLabel);
        diagSummaryTv = new TextView(this);
        diagSummaryTv.setTextColor(getColor(R.color.sl_txt));
        diagSummaryTv.setTextSize(13.5f);
        diagSummaryTv.setLineSpacing(0, 1.25f);
        diagSummaryTv.setPadding(0, (int) (4 * dp), 0, 0);
        summaryCard.addView(diagSummaryTv);
        if (groqKey == null || groqKey.isEmpty()) {
            summaryCard.setVisibility(android.view.View.GONE);
        } else {
            diagSummaryTv.setText("Summarizing your report…");
            fetchDiagSummary();
        }
        list.addView(summaryCard);

        // Score hero: ring + verdict caption.
        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.HORIZONTAL);
        hero.setGravity(android.view.Gravity.CENTER_VERTICAL);
        hero.setPadding(0, 0, 0, (int) (12 * dp));
        ScoreRingView ring = new ScoreRingView(this);
        int ringSize = (int) (96 * dp);
        ring.setLayoutParams(new LinearLayout.LayoutParams(ringSize, ringSize));
        ring.setScore(score);
        hero.addView(ring);
        LinearLayout cap = new LinearLayout(this);
        cap.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams capLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        capLp.setMargins((int) (16 * dp), 0, 0, 0);
        cap.setLayoutParams(capLp);
        TextView verdictTv = new TextView(this);
        verdictTv.setText(verdict);
        verdictTv.setTextColor(getColor(R.color.sl_txt));
        verdictTv.setTextSize(17);
        verdictTv.setTypeface(null, android.graphics.Typeface.BOLD);
        cap.addView(verdictTv);
        TextView subTv = new TextView(this);
        String sub = warns.size() + (warns.size() == 1 ? " issue" : " issues")
                + " · " + oks.size() + " passed";
        if (!infos.isEmpty()) {
            sub += " · " + infos.size() + " notes";
        }
        subTv.setText(sub);
        subTv.setTextColor(getColor(R.color.sl_mut));
        subTv.setTextSize(12.5f);
        subTv.setPadding(0, (int) (3 * dp), 0, 0);
        cap.addView(subTv);
        hero.addView(cap);
        list.addView(hero);

        // Tune-up recipe picker: segmented control + recipe description.
        TextView tuneAsLabel = new TextView(this);
        tuneAsLabel.setText("Tune up as");
        tuneAsLabel.setTextColor(getColor(R.color.sl_mut));
        tuneAsLabel.setTextSize(12);
        list.addView(tuneAsLabel);

        LinearLayout segments = new LinearLayout(this);
        segments.setOrientation(LinearLayout.HORIZONTAL);
        segments.setPadding(0, (int) (4 * dp), 0, 0);
        diagProfileBtns = new java.util.ArrayList<>();
        for (String pid : TUNE_UP_PROFILE_ORDER) {
            Button seg = new Button(this);
            seg.setTag(pid);
            seg.setTextSize(12);
            seg.setBackgroundResource(R.drawable.bg_ghost);
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            if (!diagProfileBtns.isEmpty()) {
                slp.setMargins((int) (6 * dp), 0, 0, 0);
            }
            seg.setLayoutParams(slp);
            seg.setOnClickListener(v -> onProfileSelected((String) v.getTag()));
            segments.addView(seg);
            diagProfileBtns.add(seg);
        }
        list.addView(segments);

        diagProfileDesc = new TextView(this);
        diagProfileDesc.setTextColor(getColor(R.color.sl_mut));
        diagProfileDesc.setTextSize(12);
        diagProfileDesc.setPadding(0, (int) (4 * dp), 0, (int) (4 * dp));
        list.addView(diagProfileDesc);
        refreshDiagProfileSegments();

        // Grouped findings: issues open, notes and passed collapsed.
        if (!warns.isEmpty()) {
            LinearLayout warnBody =
                    addDiagSection(list, "Needs attention", warns, true, dp);
            boolean first = true;
            for (JSONObject f : warns) {
                addDiagIssueCard(warnBody, f, first, dp);
                first = false;
            }
        }
        if (!infos.isEmpty()) {
            LinearLayout infoBody =
                    addDiagSection(list, "Notes", infos, false, dp);
            for (JSONObject f : infos) {
                addDiagIssueCard(infoBody, f, false, dp);
            }
        }
        LinearLayout passedBody = addDiagSection(list, "Passed", oks, false, dp);
        for (JSONObject f : oks) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(0, (int) (7 * dp), 0, (int) (7 * dp));
            TextView dot = new TextView(this);
            dot.setText("\u25cf ");
            dot.setTextColor(getColor(R.color.sl_teal2));
            dot.setTextSize(11);
            row.addView(dot);
            TextView title = new TextView(this);
            title.setText(f.optString("title", ""));
            title.setTextColor(getColor(R.color.sl_mut));
            title.setTextSize(13.5f);
            row.addView(title);
            passedBody.addView(row);
        }

        // Root: scrollable report with a sticky Tune Up bar.
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        scroll.addView(list);
        root.addView(scroll);
        if (hasFixable) {
            LinearLayout bar = new LinearLayout(this);
            bar.setPadding(pad, (int) (8 * dp), pad, (int) (4 * dp));
            diagTuneUpBtn = new Button(this);
            diagTuneUpBtn.setBackgroundResource(R.drawable.bg_apply);
            diagTuneUpBtn.setTextColor(getColor(R.color.sl_txt));
            diagTuneUpBtn.setTextSize(15.5f);
            diagTuneUpBtn.setTypeface(null, android.graphics.Typeface.BOLD);
            diagTuneUpBtn.setPadding(0, (int) (13 * dp), 0, (int) (13 * dp));
            diagTuneUpBtn.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
            diagTuneUpBtn.setOnClickListener(v -> {
                final String profile = currentTuneUpProfile();
                if (isProfileLocked(profile)) {
                    showProfileUpsell(profile);
                    return;
                }
                if (diagDialog != null) {
                    diagDialog.dismiss();
                }
                runTuneUp(findingsJson, profile);
            });
            bar.addView(diagTuneUpBtn);
            root.addView(bar);
        } else {
            diagTuneUpBtn = null;
        }

        diagDialog = new AlertDialog.Builder(this, R.style.Theme_SoundLab_Dialog)
                .setTitle("Diagnosis")
                .setView(root)
                .setNegativeButton("Close", null)
                .setOnDismissListener(d -> {
                    diagDialog = null;
                    diagProfileBtns = null;
                    diagProfileDesc = null;
                    diagTuneUpBtn = null;
                    diagSummaryTv = null;
                    diagFindingsJson = null;
                })
                .show();
        diagDialog.getButton(AlertDialog.BUTTON_NEGATIVE)
                .setTextColor(getColor(R.color.sl_mut));
        refreshDiagProfileSegments();
    }

    /** Collapsible report section; returns the body container to fill. */
    private LinearLayout addDiagSection(LinearLayout parent, String title,
            java.util.ArrayList<JSONObject> items, boolean openByDefault,
            final float dp) {
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(android.view.Gravity.CENTER_VERTICAL);
        head.setPadding(0, (int) (10 * dp), 0, (int) (6 * dp));
        TextView titleTv = new TextView(this);
        titleTv.setText(title + " (" + items.size() + ")");
        titleTv.setTextColor(getColor(R.color.sl_txt));
        titleTv.setTextSize(14);
        titleTv.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        titleTv.setLayoutParams(tlp);
        head.addView(titleTv);
        final TextView chev = new TextView(this);
        chev.setTextColor(getColor(R.color.sl_mut));
        chev.setTextSize(13);
        head.addView(chev);
        parent.addView(head);

        final LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        parent.addView(body);

        final boolean[] isOpen = {openByDefault};
        Runnable sync = () -> {
            body.setVisibility(isOpen[0] ? android.view.View.VISIBLE
                    : android.view.View.GONE);
            chev.setText(isOpen[0] ? "\u25be" : "\u25b8");
        };
        sync.run();
        head.setOnClickListener(v -> {
            isOpen[0] = !isOpen[0];
            sync.run();
        });
        return body;
    }

    /** Expandable issue card with detail, tip and Fix button. */
    private void addDiagIssueCard(LinearLayout parent, final JSONObject f,
            boolean openByDefault, final float dp) {
        String severity = f.optString("severity", "info");
        final int dotColor = "warn".equals(severity) ? R.color.sl_red
                : R.color.sl_org;

        final LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_card);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        clp.setMargins(0, 0, 0, (int) (10 * dp));
        card.setLayoutParams(clp);

        LinearLayout headRow = new LinearLayout(this);
        headRow.setOrientation(LinearLayout.HORIZONTAL);
        headRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        headRow.setPadding((int) (14 * dp), (int) (12 * dp),
                (int) (14 * dp), (int) (12 * dp));
        TextView dot = new TextView(this);
        dot.setText("\u25cf ");
        dot.setTextColor(getColor(dotColor));
        dot.setTextSize(12);
        headRow.addView(dot);
        TextView title = new TextView(this);
        title.setText(f.optString("title", ""));
        title.setTextColor(getColor(R.color.sl_txt));
        title.setTextSize(14);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        title.setLayoutParams(tlp);
        headRow.addView(title);
        final TextView chev = new TextView(this);
        chev.setTextColor(getColor(R.color.sl_mut));
        chev.setTextSize(12);
        headRow.addView(chev);
        card.addView(headRow);

        final LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding((int) (14 * dp), 0, (int) (14 * dp), (int) (13 * dp));
        TextView detail = new TextView(this);
        detail.setText(f.optString("detail", ""));
        detail.setTextColor(getColor(R.color.sl_mut));
        detail.setTextSize(13);
        detail.setLineSpacing(0, 1.15f);
        body.addView(detail);
        String advice = f.optString("advice", "");
        if (!advice.isEmpty()) {
            TextView tip = new TextView(this);
            tip.setText(advice);
            tip.setTextColor(getColor(R.color.sl_mut));
            tip.setTextSize(12.5f);
            tip.setLineSpacing(0, 1.15f);
            LinearLayout.LayoutParams tipLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            tipLp.setMargins((int) (10 * dp), (int) (8 * dp), 0, 0);
            tip.setLayoutParams(tipLp);
            tip.setPadding((int) (8 * dp), 0, 0, 0);
            tip.setBackgroundResource(R.drawable.bg_ghost);
            body.addView(tip);
        }
        final JSONObject fix = f.optJSONObject("fix");
        if (fix != null) {
            Button fixBtn = new Button(this);
            fixBtn.setText(fix.optString("label", "Fix"));
            fixBtn.setTextSize(12.5f);
            fixBtn.setTextColor(getColor(R.color.sl_teal2));
            fixBtn.setBackgroundResource(R.drawable.bg_ghost);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, (int) (10 * dp), 0, 0);
            fixBtn.setLayoutParams(lp);
            fixBtn.setPadding((int) (16 * dp), (int) (6 * dp),
                    (int) (16 * dp), (int) (6 * dp));
            fixBtn.setOnClickListener(v -> {
                if (diagDialog != null) {
                    diagDialog.dismiss();
                }
                applyLibraryOp(fix.optString("op"),
                        fix.optJSONObject("params").toString(),
                        fix.optString("label"),
                        "Applying fix...", "Fix applied.",
                        () -> onDiagnoseClicked());
            });
            body.addView(fixBtn);
        }
        card.addView(body);

        final boolean[] isOpen = {openByDefault};
        Runnable sync = () -> {
            body.setVisibility(isOpen[0] ? android.view.View.VISIBLE
                    : android.view.View.GONE);
            chev.setText(isOpen[0] ? "\u25be" : "\u25b8");
        };
        sync.run();
        headRow.setOnClickListener(v -> {
            isOpen[0] = !isOpen[0];
            sync.run();
        });
        parent.addView(card);
    }

    private static final String[] TUNE_UP_PROFILE_ORDER =
            {"podcast", "singing", "music"};

    private String currentTuneUpProfile() {
        return getSharedPreferences("soundlab", MODE_PRIVATE)
                .getString("tuneup_profile", "podcast");
    }

    private JSONObject tuneUpProfiles() {
        try {
            if (cachedProfilesJson != null) {
                return new JSONObject(cachedProfilesJson);
            }
        } catch (Exception ignored) {
        }
        return new JSONObject();
    }

    /** True when the recipe is premium-gated and not owned. */
    private boolean isProfileLocked(String pid) {
        JSONObject meta = tuneUpProfiles().optJSONObject(pid);
        return meta != null && meta.optBoolean("premium", false) && !isPremium();
    }

    /** Profile segment tap: locked Pro recipes open the upsell instead. */
    private void onProfileSelected(String pid) {
        if (isProfileLocked(pid)) {
            showProfileUpsell(pid);
            return;
        }
        getSharedPreferences("soundlab", MODE_PRIVATE).edit()
                .putString("tuneup_profile", pid).apply();
        refreshDiagProfileSegments();
        fetchDiagSummary();
    }

    /** Fill the executive summary, from cache or Groq/Qwen. */
    private void fetchDiagSummary() {
        if (diagSummaryTv == null || diagFindingsJson == null) {
            return;
        }
        final String groqKey = BuildConfig.GROQ_API_KEY;
        if (groqKey == null || groqKey.isEmpty()) {
            return;
        }
        final String profile = currentTuneUpProfile();
        final String cacheKey = diagFindingsJson.hashCode() + "|" + profile;
        final String hit = diagSummaryCache.get(cacheKey);
        final TextView tv = diagSummaryTv;
        if (hit != null) {
            tv.setText(hit);
            return;
        }
        tv.setText("Summarizing your report…");
        new Thread(() -> {
            String summary = "";
            try {
                if (!Python.isStarted()) {
                    Python.start(new AndroidPlatform(this));
                }
                summary = Python.getInstance()
                        .getModule("audiofx.android_bridge")
                        .callAttr("summarize_diagnosis", diagFindingsJson,
                                profile, groqKey).toString();
            } catch (Exception ignored) {
            }
            final String out = summary;
            runOnUiThread(() -> {
                if (out != null && !out.isEmpty()) {
                    diagSummaryCache.put(cacheKey, out);
                }
                boolean stale = tv != diagSummaryTv || diagFindingsJson == null
                        || !profile.equals(currentTuneUpProfile());
                if (!stale) {
                    tv.setText((out != null && !out.isEmpty()) ? out
                            : "Summary unavailable.");
                }
            });
        }).start();
    }

    /** Sync the segment control, recipe line and Tune Up label. */
    private void refreshDiagProfileSegments() {
        if (diagProfileBtns == null) {
            return;
        }
        String current = currentTuneUpProfile();
        JSONObject profiles = tuneUpProfiles();
        boolean premium = isPremium();
        for (Button seg : diagProfileBtns) {
            String pid = (String) seg.getTag();
            JSONObject meta = profiles.optJSONObject(pid);
            String label = meta != null ? meta.optString("label", pid) : pid;
            boolean locked = meta != null && meta.optBoolean("premium", false)
                    && !premium;
            seg.setText(label + (locked ? " \uD83D\uDD12" : ""));
            boolean selected = pid.equals(current);
            seg.setTextColor(getColor(
                    selected ? R.color.sl_teal2 : R.color.sl_mut));
            seg.setTypeface(null, selected ? android.graphics.Typeface.BOLD
                    : android.graphics.Typeface.NORMAL);
        }
        JSONObject cur = profiles.optJSONObject(current);
        if (diagProfileDesc != null) {
            diagProfileDesc.setText(
                    cur != null ? cur.optString("description", "") : "");
        }
        if (diagTuneUpBtn != null) {
            String label = cur != null ? cur.optString("label", current)
                    : current;
            diagTuneUpBtn.setText("Tune Up as " + label);
        }
    }

    /** Upsell for the premium tune-up recipes. */
    private void showProfileUpsell(final String pid) {
        JSONObject meta = tuneUpProfiles().optJSONObject(pid);
        String label = meta != null ? meta.optString("label", pid) : pid;
        new AlertDialog.Builder(this, R.style.Theme_SoundLab_Dialog)
                .setTitle(label + " is a Pro recipe")
                .setMessage("The " + label
                        + " tune-up recipe comes with Premium, our one-time "
                        + "purchase. It also removes all ads.")
                .setPositiveButton("Buy premium", (d, w) -> buyPremium())
                .setNegativeButton("Not now", null)
                .show();
    }

    /** One-button Tune Up: build the composite op for the profile. */
    private void runTuneUp(final String findingsJson, final String profile) {
        setBusy(true);
        setStatus("Building tune-up...");
        new Thread(() -> {
            try {
                if (!Python.isStarted()) {
                    Python.start(new AndroidPlatform(this));
                }
                final String tuneUpJson = Python.getInstance()
                        .getModule("audiofx.android_bridge")
                        .callAttr("tune_up_build", findingsJson, profile)
                        .toString();
                final JSONObject params =
                        new JSONObject(tuneUpJson).getJSONObject("params");
                final String summary = params.optString("summary", "Tune Up");
                final String receipt = summary.replaceFirst(
                        "^Tune Up \\([^)]*\\): ", "");
                runOnUiThread(() -> applyLibraryOp("tune_up",
                        params.toString(), summary,
                        "Tuning up...", "Tune-up complete. " + receipt,
                        () -> onDiagnoseClicked()));
            } catch (Exception e) {
                runOnUiThread(() -> {
                    setBusy(false);
                    setStatus("Tune-up failed: " + e.getMessage());
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
                cachedDiagJson = null;
                cachedDiagPath = null;
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
        showEncodeWaitScreen(raw ? "Encoding MP3 (no effects)..."
                : "Encoding MP3...", !isPremium());
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
                    dismissEncodeWaitScreen();
                    setStatus(doneMsg);
                    if (currentEntryId != null) {
                        try {
                            libraryDb.logExport(currentEntryId, savedAs,
                                    saveFolderName(), undoDepth());
                        } catch (Exception e) {
                            Log.w("AudioMechanic", "logExport failed", e);
                        }
                        // Separate try: an export-logging failure must not
                        // block the project rename below.
                        try {
                            maybeAdoptExportName(currentEntryId, savedAs);
                        } catch (Exception e) {
                            Log.w("AudioMechanic", "adoptExportName failed", e);
                        }
                    }
                });
            } catch (Exception e) {
                final String err = e.getMessage();
                runOnUiThread(() -> {
                    dismissEncodeWaitScreen();
                    setStatus("Save failed: " + err);
                });
            } finally {
                encodeWaitDone = true;
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
        updateProjectName();
        setStatus("Saved: " + mp3Name + " — project renamed to " + stem + ".");
    }

    /** Size-optimized bitrate for shared MP3s (CBR). */
    private static final int SHARE_BITRATE_KBPS = 96;

    private AlertDialog encodeWaitDialog;
    private AdView encodeWaitAd;
    private Handler encodeAdHandler;
    private Runnable encodeAdRefresh;
    // Share flow state (volatile: written on the encode thread).
    private volatile boolean shareInProgress;
    private volatile boolean shareEncodeDone;
    private volatile boolean encodeWaitDone;
    private volatile File shareMp3Result;
    private volatile String shareEncodeError;

    /** Test banner unit for the wait screen: swap for the real one at release. */
    private static final String ENCODE_WAIT_AD_UNIT =
            "ca-app-pub-3940256099942544/6300978111";
    /** Fresh ad request every interval while encoding is still running. */
    private static final long ENCODE_AD_REFRESH_MS = 15000;

    /**
     * Split-screen wait dialog: encoding progress on top, ad below.
     * Non-cancelable; dismissed automatically when encoding finishes.
     */
    private void showEncodeWaitScreen(String message, boolean withAd) {
        dismissEncodeWaitScreen();
        encodeWaitDone = false;
        float density = getResources().getDisplayMetrics().density;
        int pad = (int) (20 * density);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(pad, pad, pad, pad);
        top.addView(new ProgressBar(this));
        TextView tv = new TextView(this);
        tv.setText(message);
        tv.setTextColor(getColor(R.color.sl_txt));
        tv.setTextSize(15f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMarginStart(pad);
        tv.setLayoutParams(lp);
        top.addView(tv);
        root.addView(top);

        if (withAd) {
            View divider = new View(this);
            divider.setBackgroundColor(getColor(R.color.sl_dim));
            root.addView(divider, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, (int) (1 * density)));

            LinearLayout adPanel = new LinearLayout(this);
            adPanel.setGravity(Gravity.CENTER);
            adPanel.setPadding(0, (int) (12 * density), 0, (int) (4 * density));
            encodeWaitAd = new AdView(this);
            encodeWaitAd.setAdSize(AdSize.MEDIUM_RECTANGLE);
            encodeWaitAd.setAdUnitId(ENCODE_WAIT_AD_UNIT);
            adPanel.addView(encodeWaitAd);
            root.addView(adPanel);
        }

        encodeWaitDialog = new AlertDialog.Builder(this,
                R.style.Theme_SoundLab_Dialog)
                .setView(root)
                .setCancelable(false)
                .create();
        encodeWaitDialog.show();
        android.view.Window w = encodeWaitDialog.getWindow();
        if (w != null) {
            w.setLayout((int) (340 * density),
                    android.view.WindowManager.LayoutParams.WRAP_CONTENT);
        }
        if (withAd) {
            loadEncodeWaitAd();
            encodeAdHandler = new Handler(Looper.getMainLooper());
            encodeAdRefresh = new Runnable() {
                @Override
                public void run() {
                    if (encodeWaitDialog == null || encodeWaitDone) {
                        return;
                    }
                    loadEncodeWaitAd();
                    encodeAdHandler.postDelayed(this, ENCODE_AD_REFRESH_MS);
                }
            };
            encodeAdHandler.postDelayed(encodeAdRefresh, ENCODE_AD_REFRESH_MS);
        }
    }

    private void loadEncodeWaitAd() {
        if (encodeWaitAd != null) {
            encodeWaitAd.loadAd(new AdRequest.Builder().build());
        }
    }

    private void dismissEncodeWaitScreen() {
        if (encodeAdHandler != null) {
            encodeAdHandler.removeCallbacksAndMessages(null);
            encodeAdHandler = null;
        }
        encodeAdRefresh = null;
        if (encodeWaitAd != null) {
            try {
                encodeWaitAd.destroy();
            } catch (Exception ignored) {
            }
            encodeWaitAd = null;
        }
        if (encodeWaitDialog != null) {
            try {
                encodeWaitDialog.dismiss();
            } catch (Exception ignored) {
            }
            encodeWaitDialog = null;
        }
    }

    /**
     * Share entry point: encoding starts in the background and the
     * split-screen wait dialog covers it (progress on top, ads below).
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
        shareEncodeDone = false;
        shareMp3Result = null;
        shareEncodeError = null;
        showEncodeWaitScreen("Preparing share file...", !isPremium());
        new Thread(() -> encodeShareFile(srcPath)).start();
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
            encodeWaitDone = true;
            runOnUiThread(this::finishShare);
        }
    }

    /**
     * Encoding is done: auto-close the wait screen (and its ad) and
     * open the app picker.
     */
    private void finishShare() {
        dismissEncodeWaitScreen();
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

    /** True once the premium one-time purchase is owned. */
    boolean isPremium() {
        return PremiumBilling.isPremium(this);
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
        dismissEncodeWaitScreen();
        if (premiumBilling != null) {
            premiumBilling.destroy();
        }
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
