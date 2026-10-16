package com.audiofx.app;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.documentfile.provider.DocumentFile;

import com.chaquo.python.PyObject;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

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
    private Button btnPlay;
    private Button btnSave;

    private String inputPath;   // cached WAV picked by the user
    private String inputFileName = "audio.wav";
    private String outputPath;  // processed WAV written by Python
    private Uri saveFolderUri;  // session-only; never persisted
    private MediaPlayer player;

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
        btnPlay = findViewById(R.id.btn_play);
        btnSave = findViewById(R.id.btn_save);

        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(
                this, R.array.reverb_presets, android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerReverb.setAdapter(adapter);

        seekWarmth.setOnSeekBarChangeListener(sliderListener(tvWarmthVal));
        seekEcho.setOnSeekBarChangeListener(sliderListener(tvEchoVal));
        updateSliderLabels();

        findViewById(R.id.btn_pick).setOnClickListener(v -> pickAudio());
        btnProcess.setOnClickListener(v -> processAudio());
        btnPlay.setOnClickListener(v -> playOutput());
        btnSave.setOnClickListener(v -> onSaveClicked());

        outputPath = new File(getCacheDir(), "output.wav").getAbsolutePath();
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
                tvPicked.setText("Picked: " + inputFileName);
                setStatus("Ready. Adjust the effects and tap Apply.");
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
        btnProcess.setEnabled(false);
        setStatus("Processing...");
        new Thread(() -> {
            try {
                if (!Python.isStarted()) {
                    Python.start(new AndroidPlatform(this));
                }
                Python py = Python.getInstance();
                PyObject result = py.getModule("audiofx.android_bridge").callAttr(
                        "process_file", inputPath, outputPath, warmth, preset, echo);
                // process_file returns a Python dict. PyObject.get(String) does
                // getattr(), which returns null for dict keys, so read values
                // via dict.get() instead.
                final String msg = "Done: " + result.callAttr("get", "duration_s") + "s @ "
                        + result.callAttr("get", "sample_rate") + " Hz";
                runOnUiThread(() -> {
                    setStatus(msg);
                    btnPlay.setEnabled(true);
                    btnSave.setEnabled(true);
                });
            } catch (Exception e) {
                runOnUiThread(() -> setStatus("Error: " + e.getMessage()));
            } finally {
                runOnUiThread(() -> btnProcess.setEnabled(true));
            }
        }).start();
    }

    private void playOutput() {
        File f = new File(outputPath);
        if (!f.exists() || f.length() < 44) {
            setStatus("No output to play (file missing or empty).");
            return;
        }
        setStatus("Creating player...");
        try {
            if (player != null) {
                player.release();
                player = null;
            }
            player = new MediaPlayer();
            // If the UI freezes on one of these status lines, the blocking
            // call is the one AFTER the last line shown.
            setStatus("Opening " + (f.length() / 1024) + " KB audio...");
            player.setDataSource(outputPath);
            final boolean[] prepared = {false};
            player.setOnPreparedListener(mp -> {
                prepared[0] = true;
                mp.start();
                setStatus("Playing processed audio...");
            });
            player.setOnCompletionListener(mp -> setStatus("Playback finished."));
            player.setOnErrorListener((mp, what, extra) -> {
                setStatus("Playback error: " + what + "/" + extra);
                return true;
            });
            player.prepareAsync();
            setStatus("Preparing audio...");
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                if (!prepared[0]) {
                    setStatus("Still preparing after 15s (" + (f.length() / 1024)
                            + " KB file) - prepareAsync never called back.");
                }
            }, 15000);
        } catch (Exception e) {
            setStatus("Playback failed: " + e);
        }
    }

    private void onSaveClicked() {
        if (outputPath == null || !(new File(outputPath).exists())) {
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
                        "encode_wav_to_mp3", outputPath, tmpMp3.getAbsolutePath());
                String savedAs = copyToSaveFolder(tmpMp3, mp3Name);
                runOnUiThread(() -> setStatus("Saved: " + savedAs));
            } catch (Exception e) {
                runOnUiThread(() -> setStatus("Save failed: " + e.getMessage()));
            } finally {
                runOnUiThread(() -> btnSave.setEnabled(true));
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
