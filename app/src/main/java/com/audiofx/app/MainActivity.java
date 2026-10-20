package com.audiofx.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
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
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
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
import org.json.JSONException;
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
    /** Karaoke notation state (feature/tonic), PER TRACK: notation
     * events and tonic chip text keyed by track id (LEGACY_NOTATION_KEY
     * for picked files). The readouts always show the selected
     * track's notation. */
    private static final String LEGACY_NOTATION_KEY = "__legacy__";
    private final Map<String, JSONArray> notationByTrack = new HashMap<>();
    private final Map<String, JSONObject> tonicInfoByTrack = new HashMap<>();
    private ImageButton btnMenu;
    private AdView adView;
    private PremiumBilling premiumBilling;
    private Button btnOpen;
    private Button btnSave2;
    private Button btnShare;
    private Button btnDiagnose;
    private ImageButton btnNotation;
    private ImageButton btnPitch;
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
    /**
     * Selected lane's waveform. Null when no lane exists. Most call
     * sites use this alias; background ops capture their lane's view
     * directly so a mid-op selection change can't misroute progress.
     */
    private WaveformView waveformView;
    /** Per-track waveform lanes, in track position order. */
    private final List<TrackLane> lanes = new ArrayList<>();
    private final Map<String, TrackLane> laneById = new HashMap<>();
    private LinearLayout laneList;
    private LinearLayout tracksBar;
    private Button btnAddTrack;
    private LinearLayout selChipRow;
    private TextView selChipText;
    private Button btnSelClear;
    private Button btnTrim;
    private Button btnJoin;
    private TextView tvTimeCur;
    private TextView tvTimeTot;
    /** Multitrack: tracks of the open entry, in position order. */
    private List<LibraryDb.Track> mTracks = new ArrayList<>();
    /** Id of the selected track; null when no entry is open. */
    private String selectedTrackId;

    private String inputPath;   // cached WAV picked by the user, or a library pristine
    private String inputFileName = "audio.wav";
    private String currentOutputPath;  // mix file backing Play/Share; null until first render
    /**
     * True when the mix file is stale or missing relative to the tracks.
     * Lane peak refreshes set this; ensureMixFile() writes the file on
     * demand (playback, overdub, share) and clears it.
     */
    private boolean mixDirty = false;
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
    /** Id of the track the active recording lands in; null on the new-project path. */
    private String pendingRecordTrackId;
    /** Non-null while a punch-in recording is armed: the selected start
     * point in seconds (track time) where the take will replace audio. */
    private Double pendingPunchStartSec;
    /** True when the entry was pre-created at record start (no project
     * was open); stopRecording finalizes its metadata instead of
     * creating it. */
    private boolean pendingPreCreatedEntry;
    /** True when the active recording is the live-FX monitor. */
    private boolean recordingLive;
    /** Plays the existing mix back while a take is recorded (overdub). */
    private final OverdubPlayer overdubPlayer = new OverdubPlayer();
    /** Mix file feeding overdub playback; null when there is none. */
    private File monitorMixFile;
    /** Peak accumulator fed by the capture threads; drained by recTick. */
    private volatile float livePeakAcc;
    private android.app.AlertDialog saveDialog;
    private android.app.AlertDialog diagDialog;
    /** Session-scoped diagnosis cache: re-show instantly until an op is
     * applied or the audio changes. Keyed by the diagnosed file path. */
    private String cachedDiagJson;
    private String cachedDiagPath;
    /** Path of the audio file the current diagnosis (and its Tune Up) refers
     * to: the selected track's render file at diagnosis time. */
    private String diagLastPath;
    /** Id of the track the current diagnosis was computed for; cleared
     * with the diagnosis cache so a stale report can never be tuned up
     * onto the wrong track. */
    private String diagTrackId;
    /** Chain-tip cache key whose exact float chain tip is cached in Python
     * (see _tip_cache in android_bridge): "track:" + track id. A key
     * mismatch falls back to a full render, so switching tracks or
     * entries invalidates it implicitly. */
    private String tipCacheKey;
    /** Tune-up profile metadata JSON (from list_tune_up_profiles). */
    private String cachedProfilesJson;
    /** Track id whose lane hosts the active tune-up step overlay. */
    private String tuneUpLaneId;
    private String tuneupProfileLabel = "";
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
                // Live growth shows on the record target's lane; with no
                // project open yet there is no lane and only the timer runs.
                TrackLane recLane = laneById.get(pendingRecordTrackId);
                if (recLane != null) {
                    recLane.wave.addLivePeak(peak);
                    recLane.wave.setDuration(elapsed);
                }
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
                    // Project-time position on every lane; each lane
                    // clamps to its own content.
                    float pos = (float) (ms / 1000.0 / audioDurationSec);
                    for (TrackLane lane : lanes) {
                        lane.wave.setPlaybackPosition(pos);
                        updateLaneSwara(lane, ms / 1000.0);
                    }
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
        for (TrackLane lane : lanes) {
            if (lane.swaraView != null) {
                lane.swaraView.setText("");
            }
        }
    }

    /** One lane's karaoke readout: the swara of THAT track sounding
     * at time t (seconds on the project timeline). */
    private void updateLaneSwara(TrackLane lane, double t) {
        if (lane.swaraView == null) {
            return;
        }
        JSONArray events = notationByTrack.get(lane.trackId);
        if (events == null) {
            return;
        }
        String sym = swaraAt(events, t);
        if (!sym.equals(lane.swaraView.getText().toString())) {
            lane.swaraView.setText(sym);
        }
    }

    /** The event symbol covering time t, "" during rests/gaps. */
    private static String swaraAt(JSONArray events, double t) {
        try {
            int lo = 0;
            int hi = events.length() - 1;
            while (lo <= hi) {
                int mid = (lo + hi) / 2;
                JSONObject ev = events.getJSONObject(mid);
                if (t < ev.getDouble("start")) {
                    hi = mid - 1;
                } else if (t > ev.getDouble("start") + ev.getDouble("dur")) {
                    lo = mid + 1;
                } else {
                    String s = ev.optString("sym", "");
                    return "-".equals(s) ? "" : s;
                }
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    /** The notation key for the current context: the selected track
     * inside a project, the legacy key for picked files. */
    private String notationKey() {
        if (currentEntryId != null && selectedTrackId != null) {
            return selectedTrackId;
        }
        return inputPath != null ? "file:" + inputPath
                : LEGACY_NOTATION_KEY;
    }

    /** Karaoke readouts live on the lanes: each lane shows its own
     * track's notation (updateLaneHeader paints visibility). Kept as
     * a lane-refresh for callers that stored new notation. */
    private void refreshNotationUi() {
        for (TrackLane lane : lanes) {
            LibraryDb.Track t = getTrackById(lane.trackId);
            if (t != null) {
                updateLaneHeader(lane, t);
            }
        }
    }

    /** Re-render one lane's header (its tonic label may have changed). */
    private void refreshLaneHeaderFor(String trackId) {
        for (TrackLane lane : lanes) {
            if (lane.trackId.equals(trackId)) {
                LibraryDb.Track t = getTrackById(trackId);
                if (t != null) {
                    updateLaneHeader(lane, t);
                }
                return;
            }
        }
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
        btnNotation = findViewById(R.id.btn_notation);
        btnPitch = findViewById(R.id.btn_pitch);
        btnOpen.setOnClickListener(v -> pickAudio());
        btnSave2.setOnClickListener(v -> onSaveClicked());
        btnShare.setOnClickListener(v -> shareAudio());
        btnDiagnose.setOnClickListener(v -> onDiagnoseClicked());
        btnNotation.setOnClickListener(v -> onNotationClicked());
        btnPitch.setOnClickListener(v -> onPitchClicked());
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
        selChipRow = findViewById(R.id.sel_chip_row);
        selChipText = findViewById(R.id.sel_chip_text);
        btnSelClear = findViewById(R.id.btn_sel_clear);
        btnTrim = findViewById(R.id.btn_trim);
        btnJoin = findViewById(R.id.btn_join);
        btnTrim.setOnClickListener(v -> trimSelection());
        btnJoin.setOnClickListener(v -> joinSelection());
        tvTimeCur = findViewById(R.id.tv_time_cur);
        tvTimeTot = findViewById(R.id.tv_time_tot);
        // Lanes are built programmatically per track; each lane wires its
        // own tap/selection listeners in buildLane().

        selectReverb(reverbPreset);

        seekWarmth.setOnSeekBarChangeListener(sliderListener());
        seekEcho.setOnSeekBarChangeListener(sliderListener());
        updateSliderLabels();
        btnSelClear.setOnClickListener(v -> {
            if (waveformView != null) {
                waveformView.clearSelection();
            }
        });
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
        tracksBar = findViewById(R.id.tracks_bar);
        laneList = findViewById(R.id.lane_list);
        btnAddTrack = findViewById(R.id.btn_add_track);
        btnAddTrack.setOnClickListener(v -> onAddTrackClicked());
        btnStop.setOnClickListener(v -> {
            if (isRecordingSession()) {
                // During a recording session btnStop is the pause button.
                pauseRecording();
            } else {
                stopPlayback();
            }
        });

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
        btnNotation.setEnabled(hasTrack);
        btnNotation.setAlpha(hasTrack ? 1f : 0.4f);
        btnPitch.setEnabled(hasTrack);
        btnPitch.setAlpha(hasTrack ? 1f : 0.4f);
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
        if (currentEntryId != null && selectedTrackId != null) {
            // History follows the selected track.
            try {
                for (LibraryDb.Op op
                        : libraryDb.listOps(currentEntryId, selectedTrackId)) {
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
                ? undoDepth(selectedTrackId)
                : historyDb.depth(fileHash(inputFileName));
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
                        AudioDecoder.DecodeResult dr = AudioDecoder
                                .decodeToWav(this, uri, dest, 1200);
                        runOnUiThread(() -> {
                            showProgress(false);
                            adoptAudioFile(dest, displayName, dr);
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

    private void showProgress(boolean show) {
        progressBar.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    /** Disable the edit controls + progress bar while background work runs. */
    private void setBusy(boolean busy) {
        setEditButtonsEnabled(!busy);
        btnUndo.setEnabled(!busy);
        showProgress(busy);
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
        adoptAudioFile(wavFile, displayName, null);
    }

    /** Peaks computed during the MP3 decode; the render is a bit-identical
     * copy, so refreshAllLanePeaks can reuse them with no Python pass. */
    private float[] importPeaks;
    private double importPeaksDurationSec;
    private String importPeaksEntryId;

    private void adoptAudioFile(File wavFile, String displayName,
            AudioDecoder.DecodeResult decodeResult) {
        final String entryId = UUID.randomUUID().toString();
        final File dest = libraryDb.takeFile(entryId);
        if (decodeResult != null) {
            importPeaks = decodeResult.peaks;
            importPeaksDurationSec = decodeResult.durationSec;
            importPeaksEntryId = entryId;
        }
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
        diagLastPath = null;
        diagTrackId = null;
        diagSummaryCache.clear();
        audioDurationSec = 0;
        selStartSec = null;
        selEndSec = null;
        setPlayPauseEnabled(false);
        refreshPlayPause(); // the take is savable as-is, no Apply needed
        tvTimeCur.setText("0:00");
        tvTimeTot.setText("0:00");
        updateTrackActions();
        updateProjectName();
        // ---- Multitrack: every open entry shows its tracks ----
        ensureTracksForEntry(entryId);
        refreshLanes();
        int depth = undoDepth(selectedTrackId);
        setStatus(depth > 0
                ? "Loaded from library — " + depth + " effects in history."
                : "Loaded from library — pristine take.");
        updateUndoBadge();
        if (!loadNow) {
            return; // the caller chains straight into an op that loads
        }
        refreshAllLanePeaks();
        boolean engineOk = LibraryDb.DSP_VERSION.equals(e.dspVersion);
        if (!engineOk) {
            setStatus("Note: engine updated since this take — re-rendering.");
        }
        // The mix is the audible truth: render every track that holds
        // audio, then mix. refreshMix above shows the cached mix (if any)
        // while the fresh render runs.
        boolean anyAudio = false;
        for (LibraryDb.Track t : mTracks) {
            if (trackHasAudio(t)) {
                anyAudio = true;
                break;
            }
        }
        if (anyAudio) {
            reRenderEntry("Rendering...");
        } else {
            refreshAllLanePeaks();
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
                || "eq_balance".equals(opType) || "tune_up".equals(opType)
                || "punch_in".equals(opType);
    }

    /** Undo depth = the given track's persisted renderable op count;
     * survives restarts. */
    private int undoDepth(String trackId) {
        if (currentEntryId == null || trackId == null) {
            return 0;
        }
        int n = 0;
        try {
            for (LibraryDb.Op op : libraryDb.listOps(currentEntryId, trackId)) {
                if (isRenderableOp(op.opType)) {
                    n++;
                }
            }
        } catch (Exception ignored) {
        }
        return n;
    }

    /** ops_json for render_chain: the track's renderable ops, in seq order. */
    private String buildOpsJson(String entryId, String trackId) throws Exception {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (LibraryDb.Op op : libraryDb.listOps(entryId, trackId)) {
            if (!isRenderableOp(op.opType)) {
                continue; // livefx / fork are display-only
            }
            if (!first) {
                sb.append(",");
            }
            first = false;
            sb.append("{\"op\":").append(JSONObject.quote(op.opType))
                    .append(",\"params\":").append(op.paramsJson).append("}");
        }
        sb.append("]");
        return sb.toString();
    }

    /**
     * Full render of one track's op chain to its render file (call on a
     * background thread). Seeds the Python chain-tip cache under
     * "track:"+trackId, so the next apply on the track can render
     * incrementally. Returns the rendered duration.
     */
    private double renderChainToCache(String entryId, String trackId)
            throws Exception {
        double dur = renderTrackChain(entryId, trackId);
        tipCacheKey = "track:" + trackId;
        return dur;
    }

    /**
     * Render a single new op on the track starting from the cached chain
     * tip (call on a background thread). Falls back to a full
     * renderChainToCache on a cache miss. Returns the rendered duration
     * in seconds.
     */
    private double renderIncrementalOrFull(String entryId, String trackId,
            String opType, String paramsJson) throws Exception {
        final String cacheKey = "track:" + trackId;
        if (tipCacheKey != null && tipCacheKey.equals(cacheKey)) {
            try {
                if (!Python.isStarted()) {
                    Python.start(new AndroidPlatform(this));
                }
                String opJson = "[{\"op\":" + JSONObject.quote(opType)
                        + ",\"params\":" + (paramsJson != null ? paramsJson : "{}")
                        + "}]";
                File render = libraryDb.trackRenderFile(trackId);
                File tmp = new File(render.getParentFile(),
                        render.getName() + ".tmp");
                PyObject r = Python.getInstance()
                        .getModule("audiofx.android_bridge")
                        .callAttr("render_chain_from_tip", cacheKey, opJson,
                                tmp.getAbsolutePath());
                if ("True".equals(r.callAttr("get", "cache_hit").toString())) {
                    double dur = Double.parseDouble(
                            r.callAttr("get", "duration_s").toString());
                    if (!tmp.renameTo(render)) {
                        copyFile(tmp, render);
                        tmp.delete();
                    }
                    return dur;
                }
            } catch (Exception e) {
                android.util.Log.w("AudioMechanic",
                        "incremental render failed; full render", e);
            }
        }
        return renderChainToCache(entryId, trackId);
    }

    /**
     * Adopt a pre-rendered WAV (e.g. the tune-up's converged output) as the
     * track's render cache without re-rendering. The Python tip cache was
     * already seeded by the producer. Returns the adopted duration.
     */
    private double adoptPreRendered(String trackId, File preRendered,
            double dur) throws Exception {
        if (preRendered == null || !preRendered.exists()) {
            throw new Exception("pre-rendered audio missing");
        }
        File render = libraryDb.trackRenderFile(trackId);
        if (!preRendered.renameTo(render)) {
            copyFile(preRendered, render);
            if (!preRendered.delete()) {
                android.util.Log.w("AudioMechanic",
                        "could not delete tune-up temp file");
            }
        }
        tipCacheKey = "track:" + trackId;
        return dur;
    }

    /**
     * Re-render every track holding audio, then rebuild the mix (the
     * audible truth). The mix owns the progress tail via refreshMix.
     */
    private void reRenderEntry(String workingMsg) {
        final String entryId = currentEntryId;
        if (entryId == null) {
            return;
        }
        setBusy(true);
        lanesProgressStart();
        setStatus(workingMsg);
        new Thread(() -> {
            try {
                for (LibraryDb.Track t : mTracks) {
                    if (trackHasAudio(t)) {
                        if (undoDepth(t.id) > 0) {
                            renderChainToCache(entryId, t.id);
                        } else {
                            // No ops: the render IS the pristine. A file
                            // copy skips the Python DSP pass entirely.
                            copyFile(new File(t.wavPath),
                                    libraryDb.trackRenderFile(t.id));
                            // The Python tip cache was not seeded: force
                            // the next op to take the full render path.
                            if (("track:" + t.id).equals(tipCacheKey)) {
                                tipCacheKey = null;
                            }
                        }
                    }
                }
                runOnUiThread(() -> {
                    updateUndoBadge();
                    refreshAllLanePeaks();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    setStatus("Error: " + e.getMessage());
                    setBusy(false);
                    lanesProgressClear();
                    updateUndoBadge();
                });
            }
        }).start();
    }

    /**
     * Append an op to the selected track and re-render (cumulative
     * semantics). The op row is rolled back if the render fails, so the
     * DB never disagrees with the audio.
     */
    private void applyLibraryOp(final String opType, final String paramsJson,
                                final String label, final String workingMsg,
                                final String doneMsg, final Runnable onRendered) {
        applyLibraryOp(opType, paramsJson, label, workingMsg, doneMsg,
                onRendered, true, null, -1);
    }

    /**
     * Apply a tune-up whose converged audio is already rendered (adopt the
     * file instead of re-rendering the chain).
     */
    private void applyTuneUpOp(final String paramsJson, final String label,
                               final String workingMsg, final String doneMsg,
                               final Runnable onRendered, final File preRendered,
                               final double preRenderedDur) {
        applyLibraryOp("tune_up", paramsJson, label, workingMsg, doneMsg,
                onRendered, false, preRendered, preRenderedDur);
    }

    /**
     * Append an op to the SELECTED track and re-render (cumulative
     * semantics). Locked tracks are never touched: toast and no-op. The
     * op row is rolled back if the render fails, so the DB never
     * disagrees with the audio. The mix is rebuilt at the end.
     */
    private void applyLibraryOp(final String opType, final String paramsJson,
                                final String label, final String workingMsg,
                                final String doneMsg, final Runnable onRendered,
                                final boolean incremental, final File preRendered,
                                final double preRenderedDur) {
        final String entryId = currentEntryId;
        if (entryId == null) {
            return;
        }
        final LibraryDb.Track track = getSelectedTrack();
        if (track == null) {
            return;
        }
        if (!checkTrackUnlocked(track.id)) {
            return;
        }
        final String trackId = track.id;
        // Capture the lane's view now: a mid-op selection change must not
        // misroute progress onto another lane.
        final WaveformView opWave = laneWave(trackId);
        setBusy(true);
        if (selStartSec != null && audioDurationSec > 0
                && ("fx".equals(opType))) {
            float fromFrac = (float) (selStartSec / audioDurationSec);
            float toFrac = 1f;
            if (selEndSec != null) {
                toFrac = (float) Math.min(1.0, selEndSec / audioDurationSec);
            }
            if (opWave != null) {
                opWave.startProgressAnimation(fromFrac, toFrac);
            }
        } else if (opWave != null) {
            opWave.startProgressAnimation();
        }
        setStatus(workingMsg);
        new Thread(() -> {
            boolean added = false;
            try {
                libraryDb.addOp(entryId, trackId, opType, paramsJson, label);
                added = true;
                cachedDiagJson = null;
                cachedDiagPath = null;
                diagLastPath = null;
                diagTrackId = null;
                diagSummaryCache.clear();
                final double dur;
                if (preRendered != null) {
                    dur = adoptPreRendered(trackId, preRendered, preRenderedDur);
                } else if (incremental) {
                    dur = renderIncrementalOrFull(entryId, trackId, opType,
                            paramsJson);
                } else {
                    dur = renderChainToCache(entryId, trackId);
                }
                runOnUiThread(() -> {
                    releasePlayer();
                    btnStop.setEnabled(false);
                    if (onRendered != null) {
                        onRendered.run();
                    }
                    setStatus(doneMsg);
                    setPlayPauseEnabled(true);
                    setBusy(false);
                    if (opWave != null) {
                        opWave.finishProgressAnimation();
                    }
                    updateUndoBadge();
                    // Refresh the edited lane; the mix file goes stale.
                    refreshLanePeaks(trackId);
                });
            } catch (Exception e) {
                if (added) {
                    try {
                        libraryDb.popLastOp(entryId, trackId);
                    } catch (Exception ignored) {
                    }
                }
                runOnUiThread(() -> {
                    setStatus("Error: " + e.getMessage());
                    setBusy(false);
                    if (opWave != null) {
                        opWave.clearProgress();
                    }
                    updateUndoBadge();
                });
            }
        }).start();
    }

    /** Run the diagnostics, then show the report. Library entries diagnose
     * the selected track's freshly rendered file; picked files diagnose
     * the current output as before. */
    private void onDiagnoseClicked() {
        final String entryId = currentEntryId;
        if (entryId != null) {
            diagnoseSelectedTrack(entryId);
            return;
        }
        // Legacy path for picked files (unchanged).
        // Same fallback playback uses: the render when there is one,
        // otherwise the pristine input (fresh picks have no render yet).
        String path = (currentOutputPath != null
                && new File(currentOutputPath).exists()) ? currentOutputPath
                        : inputPath;
        if (path == null || !new File(path).exists()) {
            setStatus("Open or record audio first.");
            return;
        }
        final String diagPath = path;
        diagLastPath = diagPath;
        diagTrackId = null;
        if (diagPath.equals(cachedDiagPath) && cachedDiagJson != null) {
            showDiagnosisReport(cachedDiagJson);
            return;
        }
        runDiagnosis(diagPath, null); // legacy picked-file path: no lane
    }

    /**
     * Diagnose the selected track: render it fresh so the report matches
     * the current chain, then diagnose its render file. Diagnosing is
     * read-only, so locked tracks may be diagnosed; their Fix buttons
     * still no-op via applyLibraryOp's guard.
     */
    private void diagnoseSelectedTrack(final String entryId) {
        final LibraryDb.Track track = getSelectedTrack();
        if (track == null) {
            setStatus("Open or record audio first.");
            return;
        }
        if (!trackHasAudio(track)) {
            setStatus("The selected track has no audio to diagnose.");
            return;
        }
        final String trackId = track.id;
        final String diagPath =
                libraryDb.trackRenderFile(trackId).getAbsolutePath();
        if (diagPath.equals(cachedDiagPath) && cachedDiagJson != null
                && trackId.equals(diagTrackId)) {
            diagLastPath = diagPath;
            showDiagnosisReport(cachedDiagJson);
            return;
        }
        final WaveformView diagWave = laneWave(trackId);
        setBusy(true);
        setStatus("Diagnosing...");
        if (diagWave != null) {
            diagWave.startProgressAnimation();
        }
        new Thread(() -> {
            try {
                renderTrackBlocking(entryId, trackId);
            } catch (Exception e) {
                runOnUiThread(() -> {
                    setBusy(false);
                    if (diagWave != null) {
                        diagWave.clearProgress();
                    }
                    setStatus("Diagnosis failed: " + e.getMessage());
                });
                return;
            }
            diagTrackId = trackId;
            diagLastPath = diagPath;
            // runDiagnosis touches views; it must run on the UI thread
            // (it starts its own worker for the Python call).
            final WaveformView wave = laneWave(trackId);
            runOnUiThread(() -> runDiagnosis(diagPath, wave));
        }).start();
    }

    /** Run diagnose_file on a path and show the report card. */
    private void runDiagnosis(final String diagPath, final WaveformView diagWave) {
        setBusy(true);
        setStatus("Diagnosing...");
        if (diagWave != null) {
            diagWave.startProgressAnimation();
        }
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
                // Warm the LLM summary alongside the diagnosis cache.
                warmDiagSummaryCache(findingsJson);
                runOnUiThread(() -> {
                    setBusy(false);
                    if (diagWave != null) {
                        diagWave.finishProgressAnimation();
                    }
                    cachedDiagPath = diagPath;
                    cachedDiagJson = findingsJson;
                    showDiagnosisReport(findingsJson);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    setBusy(false);
                    if (diagWave != null) {
                        diagWave.clearProgress();
                    }
                    setStatus("Diagnosis failed: " + e.getMessage());
                });
            }
        }).start();
    }

    /** Tonic + swara notation for the SELECTED track: render it
     * fresh (like Diagnose), then run tonic_file on the render with
     * the lane waveform's progress animation. Legacy picked files
     * notate their current render / pristine input instead. */
    private void onNotationClicked() {
        final String entryId = currentEntryId;
        if (entryId != null) {
            notateSelectedTrack(entryId);
            return;
        }
        String path = (currentOutputPath != null
                && new File(currentOutputPath).exists()) ? currentOutputPath
                        : inputPath;
        if (path == null || !new File(path).exists()) {
            setStatus("Open or record audio first.");
            return;
        }
        runNotation(path, notationKey(), null, null);
    }

    private void notateSelectedTrack(final String entryId) {
        final LibraryDb.Track track = getSelectedTrack();
        if (track == null) {
            setStatus("Open or record audio first.");
            return;
        }
        if (!trackHasAudio(track)) {
            setStatus("The selected track has no audio to notate.");
            return;
        }
        final String trackId = track.id;
        final String path =
                libraryDb.trackRenderFile(trackId).getAbsolutePath();
        runNotation(path, trackId, entryId, laneWave(trackId));
    }

    private void runNotation(final String path, final String key,
            final String entryId, final WaveformView wave) {
        runNotation(path, key, entryId, wave, null);
    }

    private void runNotation(final String path, final String key,
            final String entryId, final WaveformView wave,
            final Runnable afterSuccess) {
        setBusy(true);
        setStatus("Notating \u2014 detecting Sa and tracing the melody...");
        if (wave != null) {
            wave.startProgressAnimation();
        }
        new Thread(() -> {
            try {
                if (entryId != null) {
                    renderTrackBlocking(entryId, key);
                }
                if (!Python.isStarted()) {
                    Python.start(new AndroidPlatform(this));
                }
                final String infoJson = Python.getInstance()
                        .getModule("audiofx.android_bridge")
                        .callAttr("tonic_file", path).toString();
                runOnUiThread(() -> {
                    setBusy(false);
                    if (wave != null) {
                        wave.finishProgressAnimation();
                    }
                    applyNotation(infoJson, key);
                    if (afterSuccess != null) {
                        afterSuccess.run();
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    setBusy(false);
                    if (wave != null) {
                        wave.clearProgress();
                    }
                    setStatus("Notation failed: " + e.getMessage());
                });
            }
        }).start();
    }

    /** Store a notation result for its track and refresh the
     * readouts (tonic chip by the title, karaoke label). */
    private void applyNotation(String infoJson, String key) {
        try {
            JSONObject info = new JSONObject(infoJson);
            if (!info.has("sa_hz") || !info.has("events")) {
                setStatus("No tonic found in this audio.");
                return;
            }
            notationByTrack.put(key, info.getJSONArray("events"));
            tonicInfoByTrack.put(key, info);
            refreshLaneHeaderFor(key);
            refreshNotationUi();
            setStatus("Notation ready \u2014 play to follow the swarams.");
        } catch (Exception e) {
            setStatus("Notation failed: bad report.");
        }
    }

    /** "Sa G3 +4c" display text for a stored tonic info. */
    private static String tonicText(JSONObject info) {
        int cents = info.optInt("cents", 0);
        return "Sa " + info.optString("note", "?") + " "
                + (cents >= 0 ? "+" : "") + cents + "c";
    }

    private static final String[] PITCH_NOTES = {
            "C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"};

    /** Pitch shift for the SELECTED track: show its current tonic,
     * take a target note/octave/cents, and apply a "pitch_shift" op
     * (undoable, lane progress animation via applyLibraryOp). If the
     * track has no tonic yet, detect it first, then open the dialog. */
    private void onPitchClicked() {
        final String entryId = currentEntryId;
        if (entryId == null) {
            setStatus("Pitch shift works on library tracks.");
            return;
        }
        final LibraryDb.Track track = getSelectedTrack();
        if (track == null) {
            setStatus("Open or record audio first.");
            return;
        }
        if (!trackHasAudio(track)) {
            setStatus("The selected track has no audio to shift.");
            return;
        }
        if (!checkTrackUnlocked(track.id)) {
            return;
        }
        JSONObject info = tonicInfoByTrack.get(track.id);
        if (info != null) {
            showPitchDialog(track.id, info);
            return;
        }
        final String path =
                libraryDb.trackRenderFile(track.id).getAbsolutePath();
        runNotation(path, track.id, entryId, laneWave(track.id), () -> {
            JSONObject inf = tonicInfoByTrack.get(track.id);
            if (inf != null) {
                showPitchDialog(track.id, inf);
            }
        });
    }

    private void showPitchDialog(final String trackId,
            final JSONObject info) {
        final double saHz = info.optDouble("sa_hz", 0);
        final String noteFull = info.optString("note", "?");
        int split = noteFull.length();
        while (split > 0 && Character.isDigit(noteFull.charAt(split - 1))) {
            split--;
        }
        String curNote = noteFull.substring(0, split);
        int curOct = 4;
        try {
            curOct = Integer.parseInt(noteFull.substring(split));
        } catch (Exception ignored) {
        }
        final float dp = getResources().getDisplayMetrics().density;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * dp);
        root.setPadding(pad, pad, pad, 0);

        TextView cur = new TextView(this);
        cur.setText("Current tonic: " + tonicText(info));
        cur.setTextColor(getColor(R.color.sl_txt));
        cur.setTextSize(15f);
        root.addView(cur);

        TextView targetLbl = new TextView(this);
        targetLbl.setText("Shift to (target tonic):");
        targetLbl.setTextColor(getColor(R.color.sl_mut));
        targetLbl.setTextSize(13f);
        LinearLayout.LayoutParams lblLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lblLp.topMargin = (int) (14 * dp);
        root.addView(targetLbl, lblLp);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = (int) (8 * dp);
        root.addView(row, rowLp);

        final Spinner noteSp = new Spinner(this);
        ArrayAdapter<String> noteAd = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, PITCH_NOTES);
        noteSp.setAdapter(noteAd);
        int noteIdx = 0;
        for (int i = 0; i < PITCH_NOTES.length; i++) {
            if (PITCH_NOTES[i].equals(curNote)) {
                noteIdx = i;
            }
        }
        noteSp.setSelection(noteIdx);
        row.addView(noteSp, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        final Spinner octSp = new Spinner(this);
        String[] octs = {"1", "2", "3", "4", "5", "6"};
        octSp.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, octs));
        octSp.setSelection(Math.max(0, Math.min(octs.length - 1, curOct - 1)));
        LinearLayout.LayoutParams octLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        octLp.leftMargin = (int) (8 * dp);
        row.addView(octSp, octLp);

        final EditText centsEt = new EditText(this);
        centsEt.setInputType(InputType.TYPE_CLASS_NUMBER
                | InputType.TYPE_NUMBER_FLAG_SIGNED);
        centsEt.setText(String.valueOf(info.optInt("cents", 0)));
        centsEt.setHint("cents");
        centsEt.selectAll();
        LinearLayout.LayoutParams centsLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        centsLp.leftMargin = (int) (8 * dp);
        row.addView(centsEt, centsLp);

        final AlertDialog dialog = new AlertDialog.Builder(
                this, R.style.Theme_SoundLab_Dialog)
                .setTitle("Pitch shift")
                .setView(root)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Convert", null)
                .create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    int cents;
                    try {
                        cents = Integer.parseInt(
                                centsEt.getText().toString().trim());
                    } catch (Exception e) {
                        centsEt.setError("Enter cents (-99 to 99)");
                        return;
                    }
                    if (cents < -99 || cents > 99) {
                        centsEt.setError("Cents must be -99 to 99");
                        return;
                    }
                    int ni = noteSp.getSelectedItemPosition();
                    int oct = octSp.getSelectedItemPosition() + 1;
                    int midi = (oct + 1) * 12 + ni;
                    double targetHz = 440.0
                            * Math.pow(2.0, (midi - 69) / 12.0)
                            * Math.pow(2.0, cents / 1200.0);
                    double semis = 12.0 * Math.log(targetHz / saHz)
                            / Math.log(2.0);
                    double rounded = Math.round(semis * 100.0) / 100.0;
                    if (Math.abs(rounded) < 0.01) {
                        setStatus("The track is already at that pitch.");
                        dialog.dismiss();
                        return;
                    }
                    if (Math.abs(rounded) > 12.0) {
                        centsEt.setError("Shift is limited to one octave");
                        return;
                    }
                    String params;
                    try {
                        params = new JSONObject()
                                .put("semitones", rounded).toString();
                    } catch (Exception e) {
                        return;
                    }
                    String label = "Pitch "
                            + (rounded >= 0 ? "+" : "")
                            + String.format(Locale.US, "%.2f", rounded)
                            + " st";
                    String done = "Pitch shifted to " + PITCH_NOTES[ni]
                            + oct + " " + (cents >= 0 ? "+" : "") + cents
                            + "c.";
                    dialog.dismiss();
                    applyLibraryOp("pitch_shift", params, label,
                            "Shifting pitch\u2026", done, null, true,
                            null, 0);
                });
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
        final String groqKey = BuildConfig.GROQ_API_KEY;        LinearLayout summaryCard = new LinearLayout(this);
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
        if (groqKey == null || groqKey.trim().isEmpty()) {
            summaryCard.setVisibility(android.view.View.GONE);
        } else {
            diagSummaryTv.setText("Summarizing your report…");
            fetchDiagSummary();
        }
        // NOTE: summaryCard is added to `list` below, after the recipe
        // picker, to keep the report order: Score, Tune up as, Summary,
        // Needs attention, Notes, Passed.

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
        list.addView(summaryCard);

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
                        null);
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

    /** Fill the executive summary, from cache or Groq/Qwen. The HTTP is
     * done here with HttpsURLConnection (Android's system trust store);
     * Python only builds the request body and parses the response. */
    /** Trimmed Groq key, or "" when not configured. */
    private String groqApiKey() {
        String k = BuildConfig.GROQ_API_KEY;
        return k == null ? "" : k.trim();
    }

    private static String summaryCacheKey(String findingsJson, String profile) {
        return findingsJson.hashCode() + "|" + profile;
    }

    /** Async Groq summary; the callback runs on the UI thread with "" on
     * any failure. Shared by the dialog fetch and the cache warmer. */
    private interface SummaryCallback {
        void onDone(String summary);
    }

    private void requestDiagSummary(final String findingsJson,
            final String profile, final String apiKey,
            final SummaryCallback cb) {
        new Thread(() -> {
            String summary = "";
            try {
                if (!Python.isStarted()) {
                    Python.start(new AndroidPlatform(this));
                }
                String body = Python.getInstance()
                        .getModule("audiofx.android_bridge")
                        .callAttr("summarize_request_body", findingsJson,
                                profile).toString();
                if (!body.isEmpty()) {
                    String resp = postGroqSummary(body, apiKey);
                    if (!resp.isEmpty()) {
                        summary = Python.getInstance()
                                .getModule("audiofx.android_bridge")
                                .callAttr("summarize_parse", resp).toString();
                    }
                }
            } catch (Exception e) {
                android.util.Log.w("AudioMechanic",
                        "summarize: failed: " + e);
            }
            final String out = summary;
            runOnUiThread(() -> cb.onDone(out));
        }).start();
    }

    /** Pre-fetch the summary when a fresh diagnosis is cached, so the
     * dialog finds it ready. Never delays the dialog: runs on its own
     * thread and fills the open dialog live if it is still on this
     * report and profile. */
    private void warmDiagSummaryCache(final String findingsJson) {
        final String apiKey = groqApiKey();
        if (apiKey.isEmpty()) {
            return;
        }
        final String profile = currentTuneUpProfile();
        final String cacheKey = summaryCacheKey(findingsJson, profile);
        if (diagSummaryCache.containsKey(cacheKey)) {
            return;
        }
        requestDiagSummary(findingsJson, profile, apiKey, summary -> {
            if (summary == null || summary.isEmpty()) {
                return;
            }
            diagSummaryCache.put(cacheKey, summary);
            if (diagSummaryTv != null && findingsJson.equals(diagFindingsJson)
                    && profile.equals(currentTuneUpProfile())) {
                diagSummaryTv.setText(summary);
            }
        });
    }

    /** Fill the executive summary, from cache or Groq/Qwen. */
    private void fetchDiagSummary() {
        if (diagSummaryTv == null || diagFindingsJson == null) {
            return;
        }
        final String apiKey = groqApiKey();
        if (apiKey.isEmpty()) {
            return;
        }
        final String profile = currentTuneUpProfile();
        final String cacheKey = summaryCacheKey(diagFindingsJson, profile);
        final String hit = diagSummaryCache.get(cacheKey);
        final TextView tv = diagSummaryTv;
        final String findingsJson = diagFindingsJson;
        if (hit != null) {
            tv.setText(hit);
            return;
        }
        tv.setText("Summarizing your report…");
        requestDiagSummary(findingsJson, profile, apiKey, summary -> {
            if (summary != null && !summary.isEmpty()) {
                diagSummaryCache.put(cacheKey, summary);
            }
            boolean stale = tv != diagSummaryTv || diagFindingsJson == null
                    || !profile.equals(currentTuneUpProfile());
            if (!stale) {
                tv.setText((summary != null && !summary.isEmpty()) ? summary
                        : "Summary unavailable.");
            }
        });
    }

    /** POST the summary request to Groq; returns the response body or "". */
    private String postGroqSummary(String bodyJson, String apiKey) {
        java.net.HttpURLConnection conn = null;
        try {
            java.net.URL url = new java.net.URL(
                    "https://api.groq.com/openai/v1/chat/completions");
            conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);
            conn.setDoOutput(true);
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(20000);
            byte[] body = bodyJson.getBytes(
                    java.nio.charset.StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(body.length);
            try (java.io.OutputStream os = conn.getOutputStream()) {
                os.write(body);
            }
            int code = conn.getResponseCode();
            if (code != 200) {
                android.util.Log.w("AudioMechanic",
                        "summarize: Groq HTTP " + code);
                return "";
            }
            try (java.io.InputStream is = conn.getInputStream();
                 java.util.Scanner s = new java.util.Scanner(is, "UTF-8")
                         .useDelimiter("\\A")) {
                return s.hasNext() ? s.next() : "";
            }
        } catch (Exception e) {
            android.util.Log.w("AudioMechanic",
                    "summarize: request failed: " + e);
            return "";
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
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

    /** One row of the tune-up step tracker: label + per-step bar. */
    private static class TuneUpRow {
        TextView label;
        ProgressBar bar;
        ObjectAnimator fill;
    }

    /** Progress callbacks invoked from Python during tune_up_converge, on
     * the tune-up worker thread; each hops to the UI thread. Methods must
     * stay public for Chaquopy reflection. */
    public class TuneUpProgress {
        public void tuneUpPassStarted(int passNumber, int maxPasses,
                String labelsJson) {
            runOnUiThread(() -> showTuneUpPass(passNumber, maxPasses,
                    labelsJson));
        }

        public void tuneUpStepActive(int stepIndex) {
            runOnUiThread(() -> setTuneUpStepActive(stepIndex));
        }

        public void tuneUpStepDone(int stepIndex) {
            runOnUiThread(() -> setTuneUpStepDone(stepIndex));
        }
    }

    /** Resolve the display label for a tune-up profile id. */
    private String tuneUpProfileLabel(String profile) {
        if (cachedProfilesJson != null) {
            try {
                org.json.JSONObject all =
                        new org.json.JSONObject(cachedProfilesJson);
                org.json.JSONObject p = all.optJSONObject(profile);
                if (p != null) {
                    return p.optString("label", profile);
                }
            } catch (Exception ignored) {
            }
        }
        return profile.substring(0, 1).toUpperCase()
                + profile.substring(1);
    }

    /** The lane hosting the tune-up overlay, or null when none. */
    private TrackLane tuneUpLane() {
        return tuneUpLaneId != null ? laneById.get(tuneUpLaneId) : null;
    }

    /** Show this pass's steps on the tune-up lane's overlay, all grey (pending). */
    private void showTuneUpPass(int passNumber, int maxPasses,
            String labelsJson) {
        TrackLane lane = tuneUpLane();
        if (lane == null) {
            return;
        }
        lane.steps.removeAllViews();
        try {
            JSONArray labels = new JSONArray(labelsJson);
            for (int i = 0; i < labels.length(); i++) {
                lane.steps.addView(makeTuneUpStepRow(
                        labels.optString(i, "")));
            }
        } catch (Exception ignored) {
        }
        lane.passTitle.setText("Tune Up (" + tuneupProfileLabel + ") - pass "
                + passNumber + " of " + maxPasses);
        lane.overlay.setVisibility(View.VISIBLE);
        lane.overlay.bringToFront();
    }

    private View makeTuneUpStepRow(String label) {
        final float dp = getResources().getDisplayMetrics().density;
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        int vpad = (int) (3 * dp);
        row.setPadding(0, vpad, 0, vpad);
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(12.5f);
        tv.setTextColor(getColor(R.color.sl_mut));
        ProgressBar bar = new ProgressBar(this, null,
                android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setProgress(0);
        bar.setProgressTintList(android.content.res.ColorStateList.valueOf(
                getColor(R.color.sl_green)));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                (int) (90 * dp), LinearLayout.LayoutParams.WRAP_CONTENT);
        blp.setMarginStart((int) (8 * dp));
        row.addView(tv, tlp);
        row.addView(bar, blp);
        TuneUpRow holder = new TuneUpRow();
        holder.label = tv;
        holder.bar = bar;
        row.setTag(holder);
        return row;
    }

    /** Mark a step in-progress: blinking green label, bar filling. */
    private void setTuneUpStepActive(int stepIndex) {
        TrackLane lane = tuneUpLane();
        View row = lane == null ? null : lane.steps.getChildAt(stepIndex);
        if (!(row != null && row.getTag() instanceof TuneUpRow)) {
            return;
        }
        TuneUpRow h = (TuneUpRow) row.getTag();
        h.label.setTextColor(getColor(R.color.sl_green));
        AlphaAnimation blink = new AlphaAnimation(1.0f, 0.35f);
        blink.setDuration(550);
        blink.setRepeatMode(Animation.REVERSE);
        blink.setRepeatCount(Animation.INFINITE);
        h.label.startAnimation(blink);
        if (h.fill != null) {
            h.fill.cancel();
        }
        h.fill = ObjectAnimator.ofInt(h.bar, "progress", 0, 90);
        h.fill.setDuration(3000);
        h.fill.start();
    }

    /** Mark a step done: solid green label, bar to 100%. */
    private void setTuneUpStepDone(int stepIndex) {
        TrackLane lane = tuneUpLane();
        View row = lane == null ? null : lane.steps.getChildAt(stepIndex);
        if (!(row != null && row.getTag() instanceof TuneUpRow)) {
            return;
        }
        TuneUpRow h = (TuneUpRow) row.getTag();
        if (h.fill != null) {
            h.fill.cancel();
            h.fill = null;
        }
        h.label.clearAnimation();
        h.label.setAlpha(1f);
        h.label.setTextColor(getColor(R.color.sl_green));
        h.bar.setProgress(100);
    }

    /** Remove the step tracker from the tune-up lane. */
    private void hideTuneUpOverlay() {
        for (TrackLane lane : lanes) {
            lane.overlay.setVisibility(View.GONE);
            lane.steps.removeAllViews();
        }
        tuneUpLaneId = null;
    }

    /** One-button Tune Up: converge the profile's fixes over up to 3 passes
     * and adopt the converged audio directly (no re-render, no automatic
     * re-diagnosis). Falls back to the single-pass build if the diagnosed
     * file is gone. */
    /** Tune Up the SELECTED track: converge on its own rendered file and
     * adopt the composite tune_up op onto that track. Locked tracks
     * no-op with the "Track locked" toast. */
    private void runTuneUp(final String findingsJson, final String profile) {
        final String entryId = currentEntryId;
        final LibraryDb.Track track = getSelectedTrack();
        if (entryId == null || track == null) {
            setStatus("Open or record audio first.");
            return;
        }
        if (!checkTrackUnlocked(track.id)) {
            return;
        }
        if (!trackHasAudio(track)) {
            setStatus("The selected track has no audio to tune up.");
            return;
        }
        final String trackId = track.id;
        if (diagTrackId != null && !diagTrackId.equals(trackId)) {
            setStatus("Track changed since diagnosis. Diagnose again first.");
            return;
        }
        final WaveformView tuneWave = laneWave(trackId);
        tuneUpLaneId = trackId;
        setBusy(true);
        setStatus("Tuning up...");
        if (tuneWave != null) {
            tuneWave.startProgressAnimation();
        }
        tuneupProfileLabel = tuneUpProfileLabel(profile);
        final TuneUpProgress progress = new TuneUpProgress();
        new Thread(() -> {
            try {
                if (!Python.isStarted()) {
                    Python.start(new AndroidPlatform(this));
                }
                final PyObject bridge = Python.getInstance()
                        .getModule("audiofx.android_bridge");
                // Converge on the selected track's own rendered file.
                File trackRender = libraryDb.trackRenderFile(trackId);
                if (!trackRender.exists()) {
                    renderTrackBlocking(entryId, trackId);
                }
                final String convergePath = trackRender.getAbsolutePath();
                final String tuneUpJson;
                final File preRendered;
                final double preRenderedDur;
                if (new File(convergePath).exists()) {
                    preRendered = new File(getCacheDir(), "tune_up_render.wav");
                    String out = bridge.callAttr("tune_up_converge",
                            convergePath, findingsJson, profile, progress, 3,
                            preRendered.getAbsolutePath(),
                            "track:" + trackId).toString();
                    tuneUpJson = out;
                    preRenderedDur = new JSONObject(out)
                            .optDouble("duration_s", -1);
                } else {
                    tuneUpJson = bridge.callAttr("tune_up_build",
                            findingsJson, profile).toString();
                    preRendered = null;
                    preRenderedDur = -1;
                }
                final JSONObject params =
                        new JSONObject(tuneUpJson).getJSONObject("params");
                final String summary = params.optString("summary", "Tune Up");
                final String receipt = summary.replaceFirst(
                        "^Tune Up \\([^)]*\\): ", "");
                runOnUiThread(() -> {
                    hideTuneUpOverlay();
                    if (preRendered != null) {
                        applyTuneUpOp(params.toString(), summary,
                                "Tuning up...",
                                "Tune-up complete. " + receipt,
                                null,
                                preRendered, preRenderedDur);
                    } else {
                        // No converged audio (diagnosed file gone): build
                        // the single-pass op and render it incrementally.
                        applyLibraryOp("tune_up",
                                params.toString(), summary,
                                "Tuning up...",
                                "Tune-up complete. " + receipt,
                                null);
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    hideTuneUpOverlay();
                    setBusy(false);
                    if (tuneWave != null) {
                        tuneWave.clearProgress();
                    }
                    setStatus("Tune-up failed: " + e.getMessage());
                });
            }
        }).start();
    }

    /** Pop the selected track's last renderable op and re-render
     * (cross-session undo). Locked tracks no-op with the toast. */
    private void undoLibraryOp() {
        final String entryId = currentEntryId;
        if (entryId == null) {
            return;
        }
        final LibraryDb.Track track = getSelectedTrack();
        if (track == null) {
            return;
        }
        if (!checkTrackUnlocked(track.id)) {
            return;
        }
        final String trackId = track.id;
        final LibraryDb.Op last;
        try {
            List<LibraryDb.Op> ops = libraryDb.listOps(entryId, trackId);
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
        final WaveformView undoWave = laneWave(trackId);
        setBusy(true);
        if (undoWave != null) {
            undoWave.startProgressAnimation();
        }
        setStatus("Undoing...");
        new Thread(() -> {
            try {
                // Punch-in takes are durable files referenced by the op;
                // delete the take when its op is undone.
                if ("punch_in".equals(last.opType)) {
                    try {
                        String takePath = new JSONObject(last.paramsJson)
                                .optString("take_path", null);
                        if (takePath != null) {
                            new File(takePath).delete();
                        }
                    } catch (Exception ignored) {
                    }
                }
                libraryDb.popLastOp(entryId, trackId);
                cachedDiagJson = null;
                cachedDiagPath = null;
                diagLastPath = null;
                diagTrackId = null;
                diagSummaryCache.clear();
                // Re-render the track (a bare take renders through the
                // empty chain, so undoing to zero ops still sounds right),
                // then rebuild the mix.
                renderChainToCache(entryId, trackId);
                final int depth = undoDepth(trackId);
                runOnUiThread(() -> {
                    updateUndoBadge();
                    refreshLanePeaks(trackId);
                    setStatus("Undone: " + last.label + (depth > 0
                            ? " — " + depth + " op(s) left."
                            : " — back to the original audio."));
                });
            } catch (Exception e) {
                // Roll the popped op back on: re-add with the same content
                // (it regains the top seq, preserving order).
                try {
                    libraryDb.addOp(entryId, trackId, last.opType,
                            last.paramsJson, last.label);
                } catch (Exception ignored) {
                }
                runOnUiThread(() -> {
                    setStatus("Undo failed: " + e.getMessage());
                    setBusy(false);
                    if (undoWave != null) {
                        undoWave.clearProgress();
                    }
                    updateUndoBadge();
                });
            }
        }).start();
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

    // ---- Multitrack: track panel, record targeting, render/mix ----

    /**
     * Load the entry's tracks, creating them for legacy entries
     * (Track 1 adopts the entry's pristine take and its ops). The selected
     * track defaults to the first track; a stale selection is reset.
     */
    private void ensureTracksForEntry(String entryId) {
        try {
            LibraryDb.LibraryEntry e = libraryDb.getEntry(entryId);
            mTracks = libraryDb.ensureTracks(entryId,
                    e != null ? e.pristinePath : null);
            if (mTracks == null) {
                mTracks = new ArrayList<>();
            }
        } catch (Exception ex) {
            setStatus("Could not load tracks: " + ex.getMessage());
            mTracks = new ArrayList<>();
        }
        if (selectedTrackId == null || getTrackById(selectedTrackId) == null) {
            selectedTrackId = mTracks.isEmpty() ? null : mTracks.get(0).id;
        }
    }

    private LibraryDb.Track getTrackById(String trackId) {
        if (trackId == null) {
            return null;
        }
        for (LibraryDb.Track t : mTracks) {
            if (trackId.equals(t.id)) {
                return t;
            }
        }
        return null;
    }

    /** The currently selected track, or null when no entry is open. */
    private LibraryDb.Track getSelectedTrack() {
        return getTrackById(selectedTrackId);
    }

    /**
     * Guard for track edits: shows the "Track locked" toast and returns
     * false when the track is locked.
     */
    private boolean checkTrackUnlocked(String trackId) {
        LibraryDb.Track t = getTrackById(trackId);
        if (t != null && t.locked) {
            Toast.makeText(this, R.string.track_locked,
                    Toast.LENGTH_SHORT).show();
            return false;
        }
        return true;
    }

    private void selectTrack(String trackId) {
        selectedTrackId = trackId;
        // Diagnosis is per-track: drop the old track's cached report.
        cachedDiagJson = null;
        cachedDiagPath = null;
        diagLastPath = null;
        diagTrackId = null;
        diagSummaryCache.clear();
        refreshNotationUi();
        refreshLanes();
        // History and the undo badge follow the selection.
        updateUndoBadge();
    }

    /** The selected track's lane, or null when there is none. */
    private TrackLane selectedLane() {
        return selectedTrackId != null ? laneById.get(selectedTrackId) : null;
    }

    /** A track's lane waveform, or null when the lane doesn't exist. */
    private WaveformView laneWave(String trackId) {
        TrackLane lane = trackId != null ? laneById.get(trackId) : null;
        return lane != null ? lane.wave : null;
    }

    /** Progress fill on every lane (multi-track background work). */
    private void lanesProgressStart() {
        for (TrackLane lane : lanes) {
            lane.wave.startProgressAnimation();
        }
    }

    private void lanesProgressFinish() {
        for (TrackLane lane : lanes) {
            lane.wave.finishProgressAnimation();
        }
    }

    private void lanesProgressClear() {
        for (TrackLane lane : lanes) {
            lane.wave.clearProgress();
        }
    }

    /** Only the selected lane drives the trim window. */
    private void onLaneSelectionChanged(String trackId, Float s, Float e) {
        if (!trackId.equals(selectedTrackId)) {
            return;
        }
        onSelectionChanged(s, e);
    }

    /**
     * Sync the per-track lanes with mTracks. Existing lanes are reused
     * so zoom, selection, and peaks survive; only structural changes
     * (add/remove/reorder) rebuild. Also refreshes the waveformView
     * alias (selected lane), the selection highlight, and the project
     * timeline.
     */
    private void refreshLanes() {
        if (laneList == null) {
            return;
        }
        if (currentEntryId == null) {
            lanes.clear();
            laneById.clear();
            laneList.removeAllViews();
            tracksBar.setVisibility(View.GONE);
            waveformView = null;
            audioDurationSec = 0;
            return;
        }
        tracksBar.setVisibility(View.VISIBLE);
        Map<String, TrackLane> old = new HashMap<>(laneById);
        lanes.clear();
        laneById.clear();
        laneList.removeAllViews();
        float density = getResources().getDisplayMetrics().density;
        for (LibraryDb.Track t : mTracks) {
            TrackLane lane = old.get(t.id);
            if (lane == null) {
                lane = buildLane(t, density);
            } else {
                lane.trackId = t.id;
            }
            updateLaneHeader(lane, t);
            lanes.add(lane);
            laneById.put(t.id, lane);
            laneList.addView(lane.root);
        }
        if (getSelectedTrack() == null) {
            selectedTrackId = mTracks.isEmpty() ? null : mTracks.get(0).id;
        }
        TrackLane sel = selectedLane();
        waveformView = sel != null ? sel.wave : null;
        updateLaneSelectionHighlights();
        updateProjectDuration();
    }

    /** Selection highlight across lanes. */
    private void updateLaneSelectionHighlights() {
        for (TrackLane lane : lanes) {
            lane.root.setBackgroundResource(
                    lane.trackId.equals(selectedTrackId)
                            ? R.drawable.bg_chip_selected : R.drawable.bg_card);
        }
    }

    /** Project timeline = longest lane; every lane draws against it. */
    private void updateProjectDuration() {
        double max = 0;
        for (TrackLane lane : lanes) {
            if (lane.contentDurSec > max) {
                max = lane.contentDurSec;
            }
        }
        audioDurationSec = max;
        for (TrackLane lane : lanes) {
            lane.wave.setProjectDuration(max);
        }
        tvTimeTot.setText(fmtTime(max));
    }

    /**
     * One lane: card with a header (name tap=rename, lock, mute) and the
     * track's own waveform on the shared project timeline, plus a
     * per-lane tune-up overlay. Tapping the header or a first tap on a
     * non-selected lane's waveform selects the track; taps on the
     * selected lane's waveform place trim markers. Track changes are
     * frozen during a recording session so the record target stays
     * stable.
     */
    private TrackLane buildLane(final LibraryDb.Track track, float density) {
        final TrackLane lane = new TrackLane();
        lane.trackId = track.id;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (14 * density);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundResource(R.drawable.bg_card);
        root.setClickable(true);
        root.setFocusable(true);
        root.setOnClickListener(v -> {
            if (isRecordingSession()) {
                return; // silent: the record target stays put
            }
            selectTrack(track.id);
        });
        LinearLayout.LayoutParams rootLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        rootLp.topMargin = (int) (14 * density);
        root.setLayoutParams(rootLp);
        lane.root = root;

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(header, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView name = new TextView(this);
        name.setTextSize(14f);
        name.setTypeface(null, android.graphics.Typeface.BOLD);
        name.setTextColor(getColor(R.color.sl_txt));
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        nameLp.rightMargin = (int) (8 * density);
        header.addView(name, nameLp);
        name.setOnClickListener(v -> {
            LibraryDb.Track t = getTrackById(track.id);
            if (t != null) {
                showRenameTrackDialog(t);
            }
        });
        lane.nameView = name;

        TextView tonic = new TextView(this);
        tonic.setTextSize(12.5f);
        tonic.setTextColor(getColor(R.color.sl_mut));
        tonic.setSingleLine(true);
        tonic.setVisibility(View.GONE);
        header.addView(tonic, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        lane.tonicView = tonic;

        Button lockBtn = new Button(this);
        lockBtn.setTextSize(14f);
        lockBtn.setBackgroundResource(R.drawable.bg_zoom_btn);
        lockBtn.setMinHeight(0);
        lockBtn.setMinimumHeight(0);
        lockBtn.setPadding(0, 0, 0, 0);
        LinearLayout.LayoutParams lockLp = new LinearLayout.LayoutParams(
                (int) (28 * density), (int) (28 * density));
        lockBtn.setOnClickListener(v -> {
            LibraryDb.Track t = getTrackById(track.id);
            if (t != null) {
                toggleTrackLock(t.id, !t.locked);
            }
        });
        header.addView(lockBtn, lockLp);
        lane.lockBtn = lockBtn;

        Button muteBtn = new Button(this);
        muteBtn.setTextSize(14f);
        muteBtn.setBackgroundResource(R.drawable.bg_zoom_btn);
        muteBtn.setMinHeight(0);
        muteBtn.setMinimumHeight(0);
        muteBtn.setPadding(0, 0, 0, 0);
        LinearLayout.LayoutParams muteLp = new LinearLayout.LayoutParams(
                (int) (28 * density), (int) (28 * density));
        muteLp.leftMargin = (int) (6 * density);
        muteBtn.setOnClickListener(v -> {
            LibraryDb.Track t = getTrackById(track.id);
            if (t != null) {
                toggleTrackMute(t.id, !t.muted);
            }
        });
        header.addView(muteBtn, muteLp);
        lane.muteBtn = muteBtn;

        Button deleteBtn = new Button(this);
        deleteBtn.setTextSize(14f);
        deleteBtn.setText("\uD83D\uDDD1"); // wastebasket
        deleteBtn.setBackgroundResource(R.drawable.bg_zoom_btn);
        deleteBtn.setMinHeight(0);
        deleteBtn.setMinimumHeight(0);
        deleteBtn.setPadding(0, 0, 0, 0);
        LinearLayout.LayoutParams delLp = new LinearLayout.LayoutParams(
                (int) (28 * density), (int) (28 * density));
        delLp.leftMargin = (int) (6 * density);
        deleteBtn.setOnClickListener(v -> {
            LibraryDb.Track t = getTrackById(track.id);
            if (t != null) {
                confirmDeleteTrack(t);
            }
        });
        header.addView(deleteBtn, delLp);
        lane.deleteBtn = deleteBtn;

        LinearLayout waveRow = new LinearLayout(this);
        waveRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams waveRowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (int) (110 * density));
        waveRowLp.topMargin = (int) (8 * density);
        root.addView(waveRow, waveRowLp);

        FrameLayout frame = new FrameLayout(this);
        waveRow.addView(frame, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));

        // Per-track volume: a vertical slider on the right of the
        // waveform (a SeekBar rotated -90 inside a fixed box; max at
        // the top). 0..150 maps to gain 0..1.5, 100 = unity.
        FrameLayout volBox = new FrameLayout(this);
        LinearLayout.LayoutParams volBoxLp = new LinearLayout.LayoutParams(
                (int) (40 * density), LinearLayout.LayoutParams.MATCH_PARENT);
        volBoxLp.leftMargin = (int) (4 * density);
        waveRow.addView(volBox, volBoxLp);
        SeekBar vol = new SeekBar(this);
        vol.setMax(150);
        vol.setProgress((int) Math.round(track.volume * 100));
        FrameLayout.LayoutParams volLp = new FrameLayout.LayoutParams(
                (int) (110 * density), (int) (40 * density));
        volLp.gravity = Gravity.CENTER;
        vol.setLayoutParams(volLp);
        vol.setRotation(-90f);
        vol.setContentDescription("Track volume");
        vol.setOnSeekBarChangeListener(
                new SeekBar.OnSeekBarChangeListener() {
                    @Override
                    public void onProgressChanged(SeekBar sb, int progress,
                            boolean fromUser) {
                    }

                    @Override
                    public void onStartTrackingTouch(SeekBar sb) {
                    }

                    @Override
                    public void onStopTrackingTouch(SeekBar sb) {
                        applyTrackVolume(track.id, sb.getProgress() / 100.0);
                    }
                });
        volBox.addView(vol);
        lane.volumeBar = vol;

        final WaveformView wv = new WaveformView(this);
        wv.setBackgroundResource(R.drawable.bg_wave);
        wv.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        wv.setOnTapListener(frac -> {
            if (isRecordingSession()) {
                return;
            }
            if (!track.id.equals(selectedTrackId)) {
                selectTrack(track.id); // first tap selects only
            } else {
                wv.placeMarkerTap(frac);
            }
        });
        wv.setOnSelectionListener((s, e) -> onLaneSelectionChanged(track.id, s, e));
        frame.addView(wv);
        lane.wave = wv;

        TextView swara = new TextView(this);
        swara.setTextSize(20f);
        swara.setTypeface(null, android.graphics.Typeface.BOLD);
        swara.setTextColor(getColor(R.color.sl_txt));
        swara.setBackgroundResource(R.drawable.bg_chip);
        int spx = (int) (10 * density);
        int spy = (int) (4 * density);
        swara.setPadding(spx, spy, spx, spy);
        swara.setVisibility(View.GONE);
        FrameLayout.LayoutParams swaraLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        swaraLp.gravity = Gravity.BOTTOM | Gravity.END;
        int sm = (int) (8 * density);
        swaraLp.setMargins(0, 0, sm, sm);
        swara.setLayoutParams(swaraLp);
        frame.addView(swara);
        lane.swaraView = swara;

        // Per-lane tune-up step overlay, initially hidden.
        LinearLayout overlay = new LinearLayout(this);
        overlay.setOrientation(LinearLayout.VERTICAL);
        overlay.setGravity(Gravity.CENTER);
        int opad = (int) (10 * density);
        overlay.setPadding(opad, opad, opad, opad);
        overlay.setBackgroundColor(0xD8141316);
        overlay.setVisibility(View.GONE);
        overlay.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        TextView passTitle = new TextView(this);
        passTitle.setTextColor(getColor(R.color.sl_txt));
        passTitle.setTextSize(13f);
        passTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams ptLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        ptLp.bottomMargin = (int) (6 * density);
        overlay.addView(passTitle, ptLp);
        ScrollView scroll = new ScrollView(this);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        LinearLayout steps = new LinearLayout(this);
        steps.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(steps);
        overlay.addView(scroll);
        frame.addView(overlay);
        lane.overlay = overlay;
        lane.steps = steps;
        lane.passTitle = passTitle;

        updateLaneHeader(lane, track);
        return lane;
    }

    /** Name, lock/mute icons, and the muted dim for one lane. */
    private void updateLaneHeader(TrackLane lane, LibraryDb.Track t) {
        lane.nameView.setText(t.name);
        JSONObject tonicInfo = tonicInfoByTrack.get(t.id);
        if (tonicInfo != null) {
            lane.tonicView.setText(tonicText(tonicInfo));
            lane.tonicView.setVisibility(View.VISIBLE);
        } else {
            lane.tonicView.setVisibility(View.GONE);
        }
        if (lane.swaraView != null) {
            lane.swaraView.setVisibility(
                    notationByTrack.containsKey(t.id)
                            ? View.VISIBLE : View.GONE);
        }
        if (lane.volumeBar != null) {
            lane.volumeBar.setProgress((int) Math.round(t.volume * 100));
        }
        lane.lockBtn.setText(t.locked ? "\uD83D\uDD12" : "\uD83D\uDD13");
        lane.lockBtn.setTextColor(getColor(
                t.locked ? R.color.sl_teal2 : R.color.sl_mut));
        // Locked is unmistakable: solid red chip, not just a tint.
        lane.lockBtn.setBackgroundResource(t.locked
                ? R.drawable.bg_chip_locked : R.drawable.bg_zoom_btn);
        lane.muteBtn.setText(t.muted ? "\uD83D\uDD07" : "\uD83D\uDD0A");
        lane.muteBtn.setTextColor(getColor(
                t.muted ? R.color.sl_teal2 : R.color.sl_mut));
        // A muted lane dims; it is still excluded from every mix.
        lane.wave.setAlpha(t.muted ? 0.4f : 1f);
    }

    private void onAddTrackClicked() {
        if (currentEntryId == null) {
            return;
        }
        if (isRecordingSession()) {
            setStatus("Stop recording first.");
            return;
        }
        try {
            LibraryDb.Track created =
                    createNumberedTrack(currentEntryId);
            selectedTrackId = created.id;
            refreshLanes();
            setStatus("Track added.");
        } catch (Exception e) {
            setStatus("Could not add track: " + e.getMessage());
        }
    }

    private void toggleTrackLock(String trackId, boolean locked) {
        if (currentEntryId == null) {
            return;
        }
        if (isRecordingSession()) {
            setStatus("Stop recording first.");
            return;
        }
        try {
            libraryDb.setTrackLocked(trackId, locked);
            mTracks = libraryDb.listTracks(currentEntryId);
            refreshLanes();
            setStatus(getString(locked
                    ? R.string.track_locked : R.string.track_unlocked));
        } catch (Exception e) {
            setStatus("Could not change lock: " + e.getMessage());
        }
    }

    /**
     * Mute toggle. Display-free: the lane dims and the mix file goes
     * stale (rebuilt on demand for play/overdub/share). No peak
     * recompute — each lane shows its own audio independently.
     */
    /** Per-track volume slider release: persist the gain, reload,
     * and invalidate the mix (same contract as mute). */
    private void applyTrackVolume(String trackId, double gain) {
        if (currentEntryId == null) {
            return;
        }
        if (isRecordingSession()) {
            setStatus("Stop recording first.");
            return;
        }
        try {
            libraryDb.setTrackVolume(trackId, gain);
            mTracks = libraryDb.listTracks(currentEntryId);
            mixDirty = true;
            releasePlayer(); // the playing mix no longer matches
            setStatus("Track volume " + Math.round(gain * 100) + "%");
        } catch (Exception e) {
            setStatus("Volume failed: " + e.getMessage());
        }
    }

    private void toggleTrackMute(String trackId, boolean muted) {
        if (currentEntryId == null) {
            return;
        }
        if (isRecordingSession()) {
            setStatus("Stop recording first.");
            return;
        }
        try {
            libraryDb.setTrackMuted(trackId, muted);
            mTracks = libraryDb.listTracks(currentEntryId);
            refreshLanes();
            mixDirty = true;
            releasePlayer(); // the playing mix no longer matches
            setStatus(getString(muted
                    ? R.string.track_muted : R.string.track_unmuted));
        } catch (Exception e) {
            setStatus("Could not change mute: " + e.getMessage());
        }
    }

    /** Rename dialog: EditText prefilled, trimmed, empty input ignored. */
    private void showRenameTrackDialog(final LibraryDb.Track track) {
        if (isRecordingSession()) {
            return; // silent: the record target stays put
        }
        final float density = getResources().getDisplayMetrics().density;
        int pad = (int) (20 * density);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, (int) (8 * density), pad, (int) (8 * density));
        final EditText nameInput = new EditText(this);
        nameInput.setText(track.name);
        nameInput.setTextColor(getColor(R.color.sl_txt));
        nameInput.setHintTextColor(getColor(R.color.sl_dim));
        nameInput.setSelectAllOnFocus(true);
        nameInput.setSingleLine(true);
        root.addView(nameInput, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        final AlertDialog dialog =
                new AlertDialog.Builder(this, R.style.Theme_SoundLab_Dialog)
                        .setTitle(R.string.rename_track)
                        .setView(root)
                        .setPositiveButton("Rename", null)
                        .setNegativeButton("Cancel", null)
                        .show();
        // The dialog theme inherits light Material buttons; re-tint them.
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setTextColor(getColor(R.color.sl_teal2));
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
                .setTextColor(getColor(R.color.sl_mut));
        // Manual click handling so an empty name keeps nothing and
        // a valid rename refreshes the panel before dismissing.
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    String name = nameInput.getText().toString().trim();
                    dialog.dismiss();
                    if (name.isEmpty()) {
                        return; // ignore empty
                    }
                    try {
                        libraryDb.renameTrack(track.id, name);
                        mTracks = libraryDb.listTracks(currentEntryId);
                        refreshLanes();
                    } catch (Exception e) {
                        setStatus("Rename failed: " + e.getMessage());
                    }
                });
    }

    /** Confirm then delete a track: DB row, ops, audio files, and lane. */
    private void confirmDeleteTrack(final LibraryDb.Track track) {
        if (isRecordingSession()) {
            return; // silent: the record target stays put
        }
        if (track.locked) {
            Toast.makeText(this, "Track locked — unlock to delete",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this, R.style.Theme_SoundLab_Dialog)
                .setTitle("Delete track?")
                .setMessage("Delete \"" + track.name + "\" and its audio?"
                        + " This cannot be undone.")
                .setPositiveButton("Delete", (d, w) -> deleteTrack(track.id))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void deleteTrack(String trackId) {
        final String entryId = currentEntryId;
        if (entryId == null) {
            return;
        }
        setBusy(true);
        setStatus("Deleting track...");
        new Thread(() -> {
            try {
                libraryDb.deleteTrack(trackId);
            } catch (Exception e) {
                runOnUiThread(() -> {
                    setBusy(false);
                    setStatus("Delete failed: " + e.getMessage());
                });
                return;
            }
            runOnUiThread(() -> {
                // If the deleted track was selected, pick another.
                if (trackId.equals(selectedTrackId)) {
                    selectedTrackId = null;
                }
                // Reload without ensureTracks: deleting the last track
                // must leave zero tracks, not auto-recreate one.
                try {
                    mTracks = libraryDb.listTracks(entryId);
                } catch (Exception ignored) {
                    mTracks = new ArrayList<>();
                }
                if (selectedTrackId == null && !mTracks.isEmpty()) {
                    selectedTrackId = mTracks.get(0).id;
                }
                refreshLanes();
                updateProjectDuration();
                mixDirty = true;
                setBusy(false);
                setStatus(mTracks.isEmpty() ? "Track deleted."
                        : "Track deleted.");
            });
        }).start();
    }

    /**
     * The existing new-project/entry-creation flow, extracted for the
     * no-project record case: durable home for the take under
     * filesDir/library/takes, then the library row. The take stays raw;
     * auto-normalize becomes a track op in stopRecording.
     */
    private String createProjectForTake(File take, boolean wasLive,
            String captureLabel) throws Exception {
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
            libraryDb.createEntryWithId(entryId, name,
                    System.currentTimeMillis(),
                    info[2], (int) info[0], (int) info[1],
                    wasLive ? "mic-livefx" : "mic", captureLabel,
                    LibraryDb.DSP_VERSION, dest.getAbsolutePath());
            return entryId;
        } catch (Exception e) {
            dest.delete(); // don't orphan a take file with no DB row
            throw e;
        }
    }

    /** A track counts as having audio when its take WAV holds samples. */
    private boolean trackHasAudio(LibraryDb.Track track) {
        if (track == null || track.wavPath == null) {
            return false;
        }
        File f = new File(track.wavPath);
        return f.exists() && f.length() > 44;
    }

    /** Track facts for resolve_record_target, in position order. */
    private String buildTracksJson() {
        JSONArray arr = new JSONArray();
        for (LibraryDb.Track t : mTracks) {
            try {
                JSONObject o = new JSONObject();
                o.put("id", t.id);
                o.put("locked", t.locked);
                o.put("has_audio", trackHasAudio(t));
                o.put("position", t.position);
                o.put("selected", t.id.equals(selectedTrackId));
                arr.put(o);
            } catch (Exception ignored) {
            }
        }
        return arr.toString();
    }

    /** Create "Track N" (N = max position + 1) and reload the list. */
    private LibraryDb.Track createNumberedTrack(String entryId)
            throws Exception {
        int maxPos = 0;
        for (LibraryDb.Track t : mTracks) {
            maxPos = Math.max(maxPos, t.position);
        }
        LibraryDb.Track created =
                libraryDb.createTrack(entryId, "Track " + (maxPos + 1));
        mTracks = libraryDb.listTracks(entryId);
        return created;
    }

    /**
     * Pick the take's track via Python's never-overwrite rule: only empty,
     * unlocked tracks are eligible, otherwise a new track is created.
     * A stale or busy pick is never overwritten either; the Java-side
     * fallback keeps the same invariant when the bridge fails.
     */
    private LibraryDb.Track resolveRecordTarget() {
        final String entryId = currentEntryId;
        if (entryId == null) {
            return null;
        }
        try {
            if (!Python.isStarted()) {
                Python.start(new AndroidPlatform(this));
            }
            PyObject result = Python.getInstance()
                    .getModule("audiofx.android_bridge")
                    .callAttr("resolve_record_target", buildTracksJson());
            String action = pyResultField(result, "action");
            if ("create".equals(action)) {
                return createNumberedTrack(entryId);
            }
            LibraryDb.Track picked =
                    getTrackById(pyResultField(result, "track_id"));
            if (picked != null && !picked.locked && !trackHasAudio(picked)) {
                return picked;
            }
            return createNumberedTrack(entryId);
        } catch (Exception e) {
            Log.w("AudioMechanic", "resolve_record_target failed", e);
            for (LibraryDb.Track t : mTracks) {
                if (!t.locked && !trackHasAudio(t)) {
                    return t;
                }
            }
            try {
                return createNumberedTrack(entryId);
            } catch (Exception ex) {
                return null;
            }
        }
    }

    /**
     * Read a field from a Python bridge result. The bridge contract is a
     * JSON string; a dict result is tolerated too.
     */
    private static String pyResultField(PyObject result, String key) {
        try {
            JSONObject o = new JSONObject(result.toString());
            if (!o.isNull(key)) {
                return String.valueOf(o.get(key));
            }
        } catch (Exception ignored) {
        }
        try {
            String v = result.callAttr("get", key).toString();
            return "None".equals(v) ? null : v;
        } catch (Exception ignored) {
        }
        return null;
    }

    /**
     * Full render of one track's op chain to its render file (blocking;
     * call off the UI thread). Display-only ops (livefx, fork) are
     * filtered out. See renderTrackChain for the duration-returning form.
     */
    private void renderTrackBlocking(String entryId, String trackId)
            throws Exception {
        renderTrackChain(entryId, trackId);
    }

    /**
     * Full render of one track's op chain to its render file, returning
     * the rendered duration. Writes to a temp file and atomically
     * renames into place. The Python tip cache is seeded under
     * "track:"+trackId; use renderChainToCache when the Java-side
     * tipCacheKey must track it too.
     */
    private double renderTrackChain(String entryId, String trackId)
            throws Exception {
        LibraryDb.Track track = getTrackById(trackId);
        if (track == null) {
            throw new Exception("track not found");
        }
        if (!Python.isStarted()) {
            Python.start(new AndroidPlatform(this));
        }
        String opsJson = buildOpsJson(entryId, trackId);
        File render = libraryDb.trackRenderFile(trackId);
        File tmp = new File(render.getParentFile(),
                render.getName() + ".tmp");
        PyObject result = Python.getInstance()
                .getModule("audiofx.android_bridge")
                .callAttr("render_chain", track.wavPath, opsJson,
                        tmp.getAbsolutePath(), null, "track:" + trackId);
        double dur = Double.parseDouble(
                result.callAttr("get", "duration_s").toString());
        if (!tmp.renameTo(render)) {
            copyFile(tmp, render);
            tmp.delete();
        }
        return dur;
    }

    /** Full render of one track only, with the standard progress UI. */
    private void renderTrack(String trackId) {
        final String entryId = currentEntryId;
        if (entryId == null || trackId == null) {
            return;
        }
        final WaveformView rtWave = laneWave(trackId);
        setBusy(true);
        if (rtWave != null) {
            rtWave.startProgressAnimation();
        }
        setStatus("Rendering track...");
        new Thread(() -> {
            try {
                renderTrackBlocking(entryId, trackId);
                runOnUiThread(() -> {
                    setBusy(false);
                    if (rtWave != null) {
                        rtWave.finishProgressAnimation();
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    setStatus("Render failed: " + e.getMessage());
                    setBusy(false);
                    if (rtWave != null) {
                        rtWave.clearProgress();
                    }
                });
            }
        }).start();
    }

    /**
     * One audible track for the mix: stable id (for mute) plus its
     * rendered WAV path.
     */
    static final class MixTrack {
        final String id;
        final String path;
        final double gain;

        MixTrack(String id, String path, double gain) {
            this.id = id;
            this.path = path;
            this.gain = gain;
        }
    }

    /**
     * One per-track waveform lane: header (name/lock/mute), the track's
     * own waveform on the shared project timeline, and a per-lane
     * tune-up step overlay. contentDurSec is the lane's audio length;
     * the project timeline is the max across lanes.
     */
    static final class TrackLane {
        String trackId;
        LinearLayout root;
        TextView nameView;
        TextView tonicView;
        TextView swaraView;
        SeekBar volumeBar;
        Button lockBtn;
        Button muteBtn;
        Button deleteBtn;
        WaveformView wave;
        LinearLayout overlay;
        LinearLayout steps;
        TextView passTitle;
        double contentDurSec;
    }

    /**
     * Rendered track WAVs for the mix, in track position order.
     * Tracks without a rendered take are skipped. Muted tracks are
     * NOT filtered here: the Python mixdown needs their ids.
     */
    private List<MixTrack> collectMixWavs() {
        final List<MixTrack> mixTracks = new ArrayList<>();
        for (LibraryDb.Track t : mTracks) {
            try {
                File render = libraryDb.trackRenderFile(t.id);
                if (render.exists() && render.length() > 44) {
                    mixTracks.add(new MixTrack(t.id,
                            render.getAbsolutePath(), t.volume));
                }
            } catch (Exception ignored) {
            }
        }
        return mixTracks;
    }

    /**
     * Shared JSON args for the Python mix calls (mixdown/mix_peaks).
     * Muted track ids ride along unfiltered: the Python side skips them.
     */
    private JSONObject mixCallArgs(List<MixTrack> mixTracks) throws JSONException {
        JSONArray tracksJson = new JSONArray();
        for (MixTrack mt : mixTracks) {
            JSONObject o = new JSONObject();
            o.put("id", mt.id);
            o.put("path", mt.path);
            o.put("gain", mt.gain);
            tracksJson.put(o);
        }
        JSONArray mutedJson = new JSONArray();
        for (LibraryDb.Track t : mTracks) {
            if (t.muted) {
                mutedJson.put(t.id);
            }
        }
        // The entry's sample rate: the first track's rate.
        int targetRate = 0;
        for (MixTrack mt : mixTracks) {
            try {
                targetRate = (int) wavInfo(new File(mt.path))[0];
                break;
            } catch (Exception ignored) {
            }
        }
        JSONObject args = new JSONObject();
        args.put("tracks", tracksJson.toString());
        args.put("muted", mutedJson.toString());
        args.put("rate", targetRate > 0 ? targetRate : 44100);
        return args;
    }

    /**
     * Blocking mixdown of rendered track WAVs (call off the UI thread).
     * Returns the mix file path.
     */
    private String mixdownBlocking(String entryId, List<MixTrack> mixTracks)
            throws Exception {
        if (!Python.isStarted()) {
            Python.start(new AndroidPlatform(this));
        }
        JSONObject a = mixCallArgs(mixTracks);
        File outFile = libraryDb.mixFile(entryId);
        PyObject result = Python.getInstance()
                .getModule("audiofx.android_bridge")
                .callAttr("mixdown", a.getString("tracks"),
                        outFile.getAbsolutePath(), a.getInt("rate"),
                        a.getString("muted"));
        if (!"True".equals(result.callAttr("get", "ok").toString())) {
            throw new Exception("mixdown failed");
        }
        String mixPath = result.callAttr("get", "output_path").toString();
        if (mixPath == null || mixPath.isEmpty()
                || !(new File(mixPath).exists())) {
            mixPath = outFile.getAbsolutePath();
        }
        if (!(new File(mixPath).exists())) {
            throw new Exception("mixdown produced no file");
        }
        return mixPath;
    }

    /** Peaks + duration for a WAV file (call off the UI thread). */
    private static final class PeaksResult {
        float[] peaks;
        double dur;
    }

    private PeaksResult waveformPeaksBlocking(String path) throws Exception {
        if (!Python.isStarted()) {
            Python.start(new AndroidPlatform(this));
        }
        PyObject result = Python.getInstance().getModule("audiofx.android_bridge")
                .callAttr("waveform_peaks", path, 1200);
        // peaks_json is a Python str; PyObject.toString() gives str().
        JSONArray arr = new JSONArray(result.callAttr("get", "peaks_json").toString());
        PeaksResult r = new PeaksResult();
        r.peaks = new float[arr.length()];
        for (int i = 0; i < arr.length(); i++) {
            r.peaks[i] = (float) arr.getDouble(i);
        }
        r.dur = Double.parseDouble(
                result.callAttr("get", "duration_s").toString());
        return r;
    }

    /**
     * Reload one lane's peaks from its render file. The mix file goes
     * stale (rebuilt on demand). A track with no render clears its lane.
     */
    private void refreshLanePeaks(String trackId) {
        final String entryId = currentEntryId;
        final TrackLane lane = trackId != null ? laneById.get(trackId) : null;
        if (entryId == null || lane == null) {
            return;
        }
        File render;
        try {
            render = libraryDb.trackRenderFile(trackId);
        } catch (Exception e) {
            return;
        }
        if (!render.exists() || render.length() <= 44) {
            lane.wave.setPeaks(new float[0]);
            lane.wave.setDuration(0);
            lane.contentDurSec = 0;
            updateProjectDuration();
            mixDirty = true;
            return;
        }
        final String path = render.getAbsolutePath();
        final WaveformView wv = lane.wave;
        setBusy(true);
        wv.startProgressAnimation();
        setStatus("Loading track...");
        new Thread(() -> {
            try {
                final PeaksResult r = waveformPeaksBlocking(path);
                runOnUiThread(() -> {
                    wv.setPeaks(r.peaks);
                    wv.setDuration(r.dur);
                    lane.contentDurSec = r.dur;
                    updateProjectDuration();
                    tvTimeCur.setText("0:00");
                    setPlayPauseEnabled(true);
                    mixDirty = true;
                    releasePlayer();
                    btnStop.setEnabled(false);
                    setBusy(false);
                    wv.finishProgressAnimation();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    setStatus("Waveform failed: " + e.getMessage());
                    setBusy(false);
                    wv.clearProgress();
                });
            }
        }).start();
    }

    /** True if any lane's render file exists (has been rendered/copied). */
    private boolean anyTrackRenderExists() {
        for (TrackLane lane : lanes) {
            try {
                File render = libraryDb.trackRenderFile(lane.trackId);
                if (render.exists() && render.length() > 44) {
                    return true;
                }
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    /**
     * Reload every lane's peaks from its render file (entry open, full
     * re-render). The mix file goes stale (rebuilt on demand).
     */
    private void refreshAllLanePeaks() {
        final String entryId = currentEntryId;
        if (entryId == null || lanes.isEmpty()) {
            return;
        }
        // MP3 import fast path: peaks were accumulated during the
        // MediaCodec decode and the render is a bit-identical copy,
        // so apply them directly with no Python pass. Only once the
        // render exists (reRenderEntry runs after the first refresh).
        if (entryId.equals(importPeaksEntryId) && importPeaks != null
                && anyTrackRenderExists()) {
            final float[] peaks = importPeaks;
            final double dur = importPeaksDurationSec;
            importPeaks = null;
            importPeaksEntryId = null;
            boolean anyAudio = false;
            for (TrackLane lane : lanes) {
                File render;
                try {
                    render = libraryDb.trackRenderFile(lane.trackId);
                } catch (Exception e) {
                    continue;
                }
                if (render.exists() && render.length() > 44) {
                    lane.wave.setPeaks(peaks);
                    lane.wave.setDuration(dur);
                    lane.contentDurSec = dur;
                    anyAudio = true;
                } else {
                    lane.wave.setPeaks(new float[0]);
                    lane.wave.setDuration(0);
                    lane.contentDurSec = 0;
                }
            }
            updateProjectDuration();
            tvTimeCur.setText("0:00");
            setPlayPauseEnabled(anyAudio);
            mixDirty = true;
            releasePlayer();
            btnStop.setEnabled(false);
            setBusy(false);
            for (TrackLane lane : lanes) {
                lane.wave.finishProgressAnimation();
            }
            return;
        }
        setBusy(true);
        for (TrackLane lane : lanes) {
            lane.wave.startProgressAnimation();
        }
        setStatus("Loading tracks...");
        new Thread(() -> {
            try {
                final Map<String, PeaksResult> got = new HashMap<>();
                for (TrackLane lane : lanes) {
                    File render = libraryDb.trackRenderFile(lane.trackId);
                    if (render.exists() && render.length() > 44) {
                        got.put(lane.trackId, waveformPeaksBlocking(
                                render.getAbsolutePath()));
                    }
                }
                runOnUiThread(() -> {
                    boolean anyAudio = false;
                    for (TrackLane lane : lanes) {
                        PeaksResult r = got.get(lane.trackId);
                        if (r != null) {
                            lane.wave.setPeaks(r.peaks);
                            lane.wave.setDuration(r.dur);
                            lane.contentDurSec = r.dur;
                            anyAudio = true;
                        } else {
                            lane.wave.setPeaks(new float[0]);
                            lane.wave.setDuration(0);
                            lane.contentDurSec = 0;
                        }
                    }
                    updateProjectDuration();
                    tvTimeCur.setText("0:00");
                    setPlayPauseEnabled(anyAudio);
                    mixDirty = true;
                    releasePlayer();
                    btnStop.setEnabled(false);
                    setBusy(false);
                    for (TrackLane lane : lanes) {
                        lane.wave.finishProgressAnimation();
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    setStatus("Waveform failed: " + e.getMessage());
                    setBusy(false);
                    for (TrackLane lane : lanes) {
                        lane.wave.clearProgress();
                    }
                });
            }
        }).start();
    }

    /**
     * Returns the fresh mix file for the current entry, writing it when
     * the mix is dirty (call off the UI thread). Null when there is no
     * entry or no audible audio. Updates currentOutputPath and clears
     * mixDirty.
     */
    private String ensureMixFile() throws Exception {
        final String entryId = currentEntryId;
        if (entryId == null) {
            return null;
        }
        File mixFile = libraryDb.mixFile(entryId);
        if (!mixDirty && mixFile.exists() && mixFile.length() > 44) {
            currentOutputPath = mixFile.getAbsolutePath();
            return currentOutputPath;
        }
        List<MixTrack> wavs = collectMixWavs();
        try {
            String path = mixdownBlocking(entryId, wavs);
            currentOutputPath = path;
            mixDirty = false;
            return path;
        } catch (Exception e) {
            if (e.getMessage() != null
                    && e.getMessage().contains("no audible tracks")) {
                currentOutputPath = null;
                mixDirty = true;
                return null;
            }
            throw e;
        }
    }

    /**
     * Show the current mix in the waveform. The mix itself is lazy: this
     * computes only the display peaks in memory (no file is written) and
     * marks the mix file dirty; ensureMixFile() produces the file on
     * demand for playback, overdub, and share. With no audible tracks at
     * all, no Python runs: the view just clears.
     */
    /** A track's content changed: re-render that track, refresh its lane. */
    private void onTrackContentChanged(String trackId) {
        final String entryId = currentEntryId;
        if (entryId == null || trackId == null) {
            return;
        }
        // The track's audio changed: any diagnosis of it is stale.
        cachedDiagJson = null;
        cachedDiagPath = null;
        diagLastPath = null;
        diagTrackId = null;
        diagSummaryCache.clear();
        final WaveformView tcWave = laneWave(trackId);
        setBusy(true);
        if (tcWave != null) {
            tcWave.startProgressAnimation();
        }
        setStatus("Rendering track...");
        new Thread(() -> {
            try {
                renderTrackBlocking(entryId, trackId);
            } catch (Exception e) {
                runOnUiThread(() -> {
                    setStatus("Render failed: " + e.getMessage());
                    setBusy(false);
                    if (tcWave != null) {
                        tcWave.clearProgress();
                    }
                });
                return;
            }
            // Hand the running progress animation to the lane refresh.
            runOnUiThread(() -> refreshLanePeaks(trackId));
        }).start();
    }

    // ---- Microphone recording ----

    /** True while a recording session is active, including a paused one. */
    private boolean isRecordingSession() {
        return recorder.isRecording() || liveMonitor.isRecording();
    }

    private void onRecordClicked() {
        if (recorder.isPaused() || liveMonitor.isPaused()) {
            resumeRecording();
            return;
        }
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
        // Resolve the take's track now so pause/stop use a stable target.
        if (currentEntryId == null) {
            // No project open: create an empty one now so the live lane
            // exists and the recording indicator has a waveform to draw
            // in. The take lands on Track 1 at stop via the normal path.
            try {
                String entryId = UUID.randomUUID().toString();
                String name = "Recording " + new SimpleDateFormat(
                        "yyyy-MM-dd HH-mm", Locale.US).format(new Date());
                pendingRecName = name + ".wav";
                libraryDb.createEntryWithId(entryId, name,
                        System.currentTimeMillis(), 0, 44100, 2, "mic",
                        "Mic", LibraryDb.DSP_VERSION, "");
                currentEntryId = entryId;
                inputFileName = name;
                // Create Track 1 directly (reserves its take-file path);
                // ensureTracks would adopt the entry's empty pristine path.
                libraryDb.createTrack(entryId, "Track 1");
                ensureTracksForEntry(entryId);
                refreshLanes();
                updateProjectName();
                updateTrackActions();
                pendingPreCreatedEntry = true;
                setStatus("Recording — tap the red button to stop.");
            } catch (Exception e) {
                currentEntryId = null;
                setStatus("Could not create project.");
                return;
            }
        }
        if (currentEntryId != null) {
            ensureTracksForEntry(currentEntryId);
            // Punch-in: the selected track is unlocked, holds audio, and
            // the user set a start marker. The take replaces audio from
            // the marker; otherwise the normal target rule applies.
            LibraryDb.Track selected = getSelectedTrack();
            pendingPunchStartSec = null;
            LibraryDb.Track target;
            if (selected != null && !selected.locked
                    && trackHasAudio(selected) && selStartSec != null) {
                target = selected;
                pendingPunchStartSec = selStartSec;
                setStatus("Punch-in at " + fmtSec(selStartSec)
                        + " on " + selected.name + " — tap the red button"
                        + " to stop.");
            } else {
                target = resolveRecordTarget();
            }
            if (target == null) {
                pendingPunchStartSec = null;
                setStatus("Could not start recording.");
                return;
            }
            pendingRecordTrackId = target.id;
            // The target may be freshly auto-created: make sure its lane
            // exists before capture, or the live peaks have nowhere to go.
            refreshLanes();
        }
        // The monitor mix must be fresh before capture starts; build it
        // off the UI thread, then start capture and overdub together.
        setBusy(true);
        setStatus("Preparing...");
        new Thread(() -> {
            File monitor = null;
            if (currentEntryId != null) {
                try {
                    String p = ensureMixFile();
                    if (p != null) {
                        monitor = new File(p);
                    }
                } catch (Exception ignored) {
                }
            }
            final File monitorFinal = monitor;
            runOnUiThread(() -> {
                setBusy(false);
                beginCapture(monitorFinal);
            });
        }).start();
    }

    /** Start the mic capture (UI thread); the monitor mix is already fresh. */
    private void beginCapture(File monitorMixFile) {
        if (switchLiveFx.isChecked()) {
            startLiveRecording(monitorMixFile);
            return;
        }
        recorder.setPreprocessing(switchAgc.isChecked(), switchNs.isChecked());
        File take = new File(getCacheDir(), "rec_take.wav");
        if (!recorder.start(take)) {
            pendingRecordTrackId = null;
            pendingPunchStartSec = null;
            setStatus("Microphone unavailable on this device.");
            return;
        }
        recordingLive = false;
        startOverdub(monitorMixFile);
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
    private void startLiveRecording(File monitorMixFile) {
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
            pendingRecordTrackId = null;
            setStatus("Live FX unavailable on this device.");
            return;
        }
        recordingLive = true;
        startOverdub(monitorMixFile);
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

    /**
     * Start overdub playback of the existing mix while a take is
     * recorded. The mix file is ensured fresh by the caller before
     * capture starts. A missing file just means the take is recorded
     * without playback, exactly as before this feature.
     */
    private void startOverdub(File monitorMixFile) {
        this.monitorMixFile = monitorMixFile;
        if (monitorMixFile != null && monitorMixFile.exists()
                && monitorMixFile.length() > 44) {
            try {
                overdubPlayer.start(monitorMixFile, 0);
            } catch (Exception ignored) {
            }
        }
    }

    /** Pause capture and overdub playback; the record-head UI freezes. */
    private void pauseRecording() {
        if (!isRecordingSession()) {
            return;
        }
        if (recordingLive) {
            liveMonitor.pause();
        } else {
            recorder.pause();
        }
        overdubPlayer.pause();
        // Freeze the record-head UI distinctly: steady pause icon, no pulse.
        if (recPulse != null) {
            recPulse.cancel();
            recPulse = null;
        }
        btnRecord.setAlpha(1f);
        btnRecord.setImageResource(R.drawable.ic_pause);
        // The pause button stays disabled until the session resumes.
        btnStop.setEnabled(false);
        setStatus("Recording paused. Tap the record button to resume.");
    }

    /** Resume a paused capture session, picking overdub playback back up. */
    private void resumeRecording() {
        if (!(recorder.isPaused() || liveMonitor.isPaused())) {
            return;
        }
        long offset = recordingLive
                ? liveMonitor.dataFrames() : recorder.dataFrames();
        boolean ok = recordingLive
                ? liveMonitor.resume() : recorder.resume();
        if (!ok) {
            setStatus("Could not resume recording.");
            return;
        }
        btnRecord.setImageResource(R.drawable.ic_stop_white);
        startRecPulse();
        btnStop.setEnabled(true);
        setStatus(recordingLive
                ? "Recording resumed (live FX). Tap the red button to stop."
                : "Recording resumed. Tap the red button to stop.");
        if (monitorMixFile != null && monitorMixFile.exists()) {
            try {
                // An offset past EOF starts nothing: the take continues
                // without playback, which is the correct fallback.
                overdubPlayer.start(monitorMixFile, offset);
            } catch (Exception ignored) {
            }
        }
    }

    /** The pulsing record-button animation used while a take is running. */
    private void startRecPulse() {
        if (recPulse != null) {
            recPulse.cancel();
        }
        recPulse = ObjectAnimator.ofFloat(btnRecord, "alpha", 1f, 0.35f);
        recPulse.setDuration(600);
        recPulse.setRepeatMode(ValueAnimator.REVERSE);
        recPulse.setRepeatCount(ValueAnimator.INFINITE);
        recPulse.start();
    }

    /** Shared recording UI: pulsing red button, REC pill, elapsed timer. */
    private void enterRecordingUi(boolean live) {
        btnRecord.setImageResource(R.drawable.ic_stop_white);
        startRecPulse();
        setPlayPauseEnabled(false);
        // During a recording session btnStop is the pause button.
        btnStop.setImageResource(R.drawable.ic_pause);
        btnStop.setEnabled(true);
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
        TrackLane recLane = laneById.get(pendingRecordTrackId);
        if (recLane != null) {
            recLane.wave.startLive();
        }
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
        // Restore btnStop's normal role: stop playback, look, and state.
        btnStop.setImageResource(R.drawable.ic_stop);
        btnStop.setEnabled(false);
        btnProcess.setEnabled(true);
        btnProcess.setAlpha(1f);
        btnTrim.setEnabled(true);
        btnJoin.setEnabled(true);
        btnNormalize.setEnabled(true);
        btnNormalize.setAlpha(1f);
        for (TrackLane lane : lanes) {
            lane.wave.endLive();
        }
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
        // Overdub playback always stops with the take, even from a pause.
        overdubPlayer.stop();
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
            // Zero audio captured: not a failure, keep the (empty) track.
            refreshLanes();
            if (pendingRecordTrackId != null) {
                selectTrack(pendingRecordTrackId);
            }
            setStatus("No audio captured.");
            pendingRecordTrackId = null;
            pendingPunchStartSec = null;
            monitorMixFile = null;
            return;
        }
        // Multitrack: the take lands in a track's take file, never as a
        // fresh entry per take. The target rule only ever picks empty,
        // unlocked tracks, so recording never overwrites.
        final boolean newProject = currentEntryId == null;
        final String entryId;
        final String trackId;
        try {
            if (newProject) {
                // No project open: create one first (the existing
                // new-entry flow), then its tracks. The take becomes
                // Track 1's audio, so no target resolution is needed.
                entryId = createProjectForTake(take, wasLive, captureLabel);
                ensureTracksForEntry(entryId);
                if (mTracks.isEmpty()) {
                    throw new Exception("new project has no tracks");
                }
                trackId = mTracks.get(0).id;
            } else {
                entryId = currentEntryId;
                ensureTracksForEntry(entryId);
                // Use the track picked at record start; resolve again
                // only if it somehow vanished mid-session.
                LibraryDb.Track target =
                        getTrackById(pendingRecordTrackId);
                if (target == null) {
                    target = resolveRecordTarget();
                }
                if (target == null) {
                    throw new Exception("no empty track available");
                }
                trackId = target.id;
                if (pendingPunchStartSec != null) {
                    // Punch-in: the take becomes a durable file referenced
                    // by the op; the op splices it into the track at the
                    // marker. Undoable like any other op.
                    String punchUuid = UUID.randomUUID().toString();
                    File punchTake = libraryDb.punchTakeFile(punchUuid);
                    try {
                        copyFile(take, punchTake);
                        take.delete();
                    } catch (Exception e) {
                        punchTake.delete();
                        throw e;
                    }
                    double[] info = wavInfo(new File(target.wavPath));
                    long startFrame =
                            (long) (pendingPunchStartSec * info[0]);
                    String params = "{\"take_path\":"
                            + JSONObject.quote(
                                    punchTake.getAbsolutePath())
                            + ",\"start_frame\":" + startFrame + "}";
                    libraryDb.addOp(entryId, trackId, "punch_in", params,
                            "Punch-in at " + fmtSec(pendingPunchStartSec));
                    // The marker served its purpose; clear the selection.
                    selStartSec = null;
                    selEndSec = null;
                    setSelChip(null, null);
                    pendingPunchStartSec = null;
                } else {
                    final File dest = libraryDb.trackTakeFile(trackId);
                    try {
                        copyFile(take, dest);
                        take.delete();
                    } catch (Exception e) {
                        dest.delete(); // keep the empty slot empty
                        throw e;
                    }
                    // Pre-created entry (record from app open): finalize
                    // its audio metadata now that the take has landed.
                    if (pendingPreCreatedEntry) {
                        try {
                            double[] info = wavInfo(dest);
                            libraryDb.updateEntryAudio(entryId, info[2],
                                    (int) info[0], (int) info[1],
                                    dest.getAbsolutePath());
                        } catch (Exception ignored) {
                        }
                        pendingPreCreatedEntry = false;
                    }
                }
            }
            if (wasLive) {
                // Display-only first op: the wet take IS the track audio.
                libraryDb.addOp(entryId, trackId, "livefx",
                        "{\"warmth\":" + (liveWarmthPct / 100.0)
                                + ",\"reverb_preset\":\"" + livePreset + "\""
                                + ",\"echo\":" + (liveEchoPct / 100.0) + "}",
                        "Live FX · Warmth " + liveWarmthPct + "%, "
                                + reverbDisplayName(livePreset)
                                + ", Echo " + liveEchoPct + "%");
            }
            if (switchAutoNorm.isChecked()) {
                // Track-scoped op; rendered with the track below.
                libraryDb.addOp(entryId, trackId, "normalize",
                        normalizeParamsJson(), "Normalize to -1 dBFS");
            }
        } catch (Exception e) {
            setStatus("Could not save recording: " + e.getMessage());
            pendingRecordTrackId = null;
            pendingPunchStartSec = null;
            pendingPreCreatedEntry = false;
            monitorMixFile = null;
            return;
        }
        // Select the recorded track, show it, render it, refresh the mix.
        selectedTrackId = trackId;
        if (newProject) {
            // Adopt without an initial load: onTrackContentChanged renders
            // the track and loads the mix waveform, which avoids a
            // pristine-load racing it.
            adoptLibraryEntry(entryId, false);
        }
        refreshLanes();
        onTrackContentChanged(trackId);
        setStatus(switchAutoNorm.isChecked()
                ? "Take saved and normalized."
                : "Take saved.");
        pendingRecordTrackId = null;
        monitorMixFile = null;
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
                null);
    }

    /** Library path for Normalize: append the op, re-render. */
    private void applyNormalizeOp() {
        applyLibraryOp("normalize", normalizeParamsJson(), "Normalize to -1 dBFS",
                "Normalizing...", "Normalized to -1 dBFS.",
                null);
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
                        zeroPeakRange(s, e != null ? e : audioDurationSec);
                    }
                    // Join changes duration: the end-of-op lane refresh
                    // covers the display.
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
        if (waveformView != null) {
            waveformView.clearSelection();
        }
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
                    }
                    // Join: legacy picked-file path (unreachable now that
                    // picks become library entries); lanes cover display.
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

    /** Zero the displayed peak buckets overlapping the startSec/endSec window.
     * The lane's peaks cover its own content (tracks start at 0, so
     * project seconds are track seconds). */
    private void zeroPeakRange(double startSec, double endSec) {
        TrackLane lane = selectedLane();
        if (lane == null) {
            return;
        }
        float[] peaks = lane.wave.getPeaks();
        int n = peaks.length;
        double dur = lane.contentDurSec;
        if (n == 0 || dur <= 0) {
            return;
        }
        int from = Math.max(0, (int) (startSec / dur * n));
        int to = Math.min(n, (int) Math.ceil(endSec / dur * n));
        for (int i = from; i < to; i++) {
            peaks[i] = 0f;
        }
        lane.wave.updatePeaks(peaks);
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
        // Structural edits (trim/join) change the audio itself. Legacy
        // picked-file path (unreachable now that picks become library
        // entries); lanes cover the display.
        updateUndoBadge();
    }

    private void releasePlayer() {
        cancelScheduledStop();
        stopPositionUpdates();
        for (TrackLane lane : lanes) {
            lane.wave.clearPlayback();
        }
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
        for (TrackLane lane : lanes) {
            lane.wave.setPlaybackRange(s, e);
            lane.wave.setPlaybackPosition(positionMs / 1000f / dur);
        }
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
        // The waveform shows a lazily-computed mix; make the mix file
        // fresh before auditioning it.
        if (currentEntryId != null && mixDirty) {
            setBusy(true);
            setStatus("Preparing mix...");
            new Thread(() -> {
                try {
                    ensureMixFile();
                    runOnUiThread(() -> {
                        setBusy(false);
                        playOutputAt(fraction);
                    });
                } catch (Exception e) {
                    runOnUiThread(() -> {
                        setBusy(false);
                        setStatus("Play failed: " + e.getMessage());
                    });
                }
            }).start();
            return;
        }
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
                btnStop.setEnabled(true);
            });
            player.setOnCompletionListener(mp -> {
                isPaused = false;
                stopPositionUpdates();
                for (TrackLane lane : lanes) {
                    lane.wave.clearPlayback();
                }
                setStatus("Playback finished.");
                setPlayPauseEnabled(true);
                refreshPlayPause();
                tvTimeCur.setText(fmtTime(audioDurationSec));
                btnStop.setEnabled(false);
            });
            player.setOnErrorListener((mp, what, extra) -> {
                setStatus("Playback error: " + what + "/" + extra);
                releasePlayer();
                setPlayPauseEnabled(true);
                refreshPlayPause();
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
        tvTimeCur.setText("0:00");
        btnStop.setEnabled(false);
        setStatus("Playback stopped.");
    }

    private void onSaveClicked() {
        if (inputPath == null || !(new File(inputPath).exists())) {
            Toast.makeText(this, "Pick an audio file or record first", Toast.LENGTH_SHORT).show();
            return;
        }
        if (currentEntryId != null) {
            // Multitrack project: one WAV per track.
            showSaveTracksDialog();
            return;
        }
        showSaveDialog();
    }

    /**
     * Save dialog for multitrack projects: each track with audio is
     * saved as a separate WAV named after the track, inside the chosen
     * folder. The MP3 flow's per-file name field does not apply here;
     * name collisions are kept as numbered copies.
     */
    private void showSaveTracksDialog() {
        if (saveDialog != null && saveDialog.isShowing()) {
            return;
        }
        final float density = getResources().getDisplayMetrics().density;
        int pad = (int) (20 * density);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, (int) (8 * density), pad, (int) (8 * density));

        int audible = 0;
        StringBuilder names = new StringBuilder();
        for (LibraryDb.Track t : mTracks) {
            if (trackHasAudio(t)) {
                if (audible > 0) {
                    names.append(", ");
                }
                names.append(sanitizeWavName(t.name));
                audible++;
            }
        }
        TextView info = new TextView(this);
        info.setText(audible == 0
                ? "No recorded audio to save yet."
                : "Saves " + audible
                        + (audible == 1 ? " track as WAV: " : " tracks as WAV: ")
                        + names);
        info.setTextColor(getColor(R.color.sl_txt));
        info.setTextSize(14);
        LinearLayout.LayoutParams infoLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        infoLp.bottomMargin = (int) (16 * density);
        root.addView(info, infoLp);

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
                .setTitle("Save tracks as WAV")
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
        // Manual click handling: the Save tap needs a folder first.
        saveDialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    if (saveFolderUri == null) {
                        Toast.makeText(this, "Choose a save folder first",
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    saveDialog.dismiss();
                    doSaveTracksWav();
                });
    }

    /**
     * Save every audible track as its own WAV in the chosen folder,
     * re-rendering each first so the file matches the current chain.
     * Empty tracks are skipped; collisions are kept as numbered copies.
     * Each saved file is logged as an export on the entry.
     */
    private void doSaveTracksWav() {
        final String entryId = currentEntryId;
        if (entryId == null || saveFolderUri == null) {
            return;
        }
        final List<LibraryDb.Track> audible = new ArrayList<>();
        for (LibraryDb.Track t : mTracks) {
            if (trackHasAudio(t)) {
                audible.add(t);
            }
        }
        if (audible.isEmpty()) {
            Toast.makeText(this, "No recorded audio to save",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        setBusy(true);
        setStatus("Saving tracks...");
        lanesProgressStart();
        new Thread(() -> {
            final List<String> saved = new ArrayList<>();
            String error = null;
            for (LibraryDb.Track t : audible) {
                try {
                    renderTrackBlocking(entryId, t.id);
                    File render = libraryDb.trackRenderFile(t.id);
                    String savedAs = copyToSaveFolder(render,
                            sanitizeWavName(t.name), false, "audio/wav");
                    saved.add(savedAs);
                    try {
                        libraryDb.logExport(entryId, savedAs, saveFolderName(),
                                undoDepth(t.id));
                    } catch (Exception logEx) {
                        Log.w("AudioMechanic", "logExport failed", logEx);
                    }
                } catch (Exception e) {
                    error = e.getMessage();
                    break;
                }
            }
            final int n = saved.size();
            final String err = error;
            runOnUiThread(() -> {
                setBusy(false);
                lanesProgressClear();
                if (err != null) {
                    setStatus("Save failed: " + err
                            + (n > 0 ? " (" + n + " saved before the error)"
                                    : ""));
                } else {
                    setStatus("Saved " + n
                            + (n == 1 ? " track." : " tracks."));
                }
            });
        }).start();
    }

    private static String sanitizeWavName(String raw) {
        String name = raw.trim().replaceAll("[/\\\\]", "_");
        if (name.isEmpty()) {
            name = "track";
        }
        if (!name.toLowerCase(Locale.US).endsWith(".wav")) {
            name += ".wav";
        }
        return name;
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
                                    saveFolderName(), undoDepth(selectedTrackId));
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
     * Multitrack projects share the mix: every audible track is
     * re-rendered and mixed fresh, then the existing 96 kbps MP3 encode
     * + FileProvider chooser runs on the mix file.
     */
    private void shareAudio() {
        if (shareInProgress) {
            return;
        }
        if (currentEntryId != null) {
            shareMixFresh();
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

    /**
     * Share for multitrack projects: re-render every audible track and
     * rebuild the mix, then run the existing share pipeline on the
     * fresh mix file.
     */
    private void shareMixFresh() {
        final String entryId = currentEntryId;
        if (entryId == null) {
            return;
        }
        shareInProgress = true;
        setBusy(true);
        setStatus("Preparing mix...");
        lanesProgressStart();
        new Thread(() -> {
            try {
                for (LibraryDb.Track t : mTracks) {
                    if (trackHasAudio(t)) {
                        renderTrackBlocking(entryId, t.id);
                    }
                }
                List<MixTrack> wavs = collectMixWavs();
                if (wavs.isEmpty()) {
                    throw new Exception("Nothing to share yet.");
                }
                final String mixPath = mixdownBlocking(entryId, wavs);
                runOnUiThread(() -> {
                    currentOutputPath = mixPath;
                    releasePlayer();
                    btnStop.setEnabled(false);
                    setBusy(false);
                    lanesProgressFinish();
                    // Now the existing share pipeline on the fresh mix.
                    shareEncodeDone = false;
                    shareMp3Result = null;
                    shareEncodeError = null;
                    showEncodeWaitScreen("Preparing share file...",
                            !isPremium());
                    new Thread(() -> encodeShareFile(mixPath)).start();
                });
            } catch (Exception e) {
                final boolean allMuted = e.getMessage() != null
                        && e.getMessage().contains("no audible tracks");
                runOnUiThread(() -> {
                    shareInProgress = false;
                    setStatus(allMuted ? "All tracks muted."
                            : "Share failed: " + e.getMessage());
                    setBusy(false);
                    lanesProgressClear();
                });
            }
        }).start();
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
        return copyToSaveFolder(src, baseName, overwrite, "audio/mpeg");
    }

    private String copyToSaveFolder(File src, String baseName, boolean overwrite,
            String mimeType) throws Exception {
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
        DocumentFile dest = dir.createFile(mimeType, name);
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
        // A paused session still counts as recording: capture and
        // overdub playback both stop here.
        overdubPlayer.stop();
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
