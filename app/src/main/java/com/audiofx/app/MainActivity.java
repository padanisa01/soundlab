package com.audiofx.app;

import android.app.Activity;
import android.content.Intent;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.chaquo.python.PyObject;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;

import java.io.File;
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

    private TextView tvPicked;
    private TextView tvStatus;
    private TextView tvWarmthVal;
    private TextView tvEchoVal;
    private SeekBar seekWarmth;
    private SeekBar seekEcho;
    private Spinner spinnerReverb;
    private Button btnProcess;
    private Button btnPlay;

    private String inputPath;   // cached WAV picked by the user
    private String outputPath;  // processed WAV written by Python
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
                tvPicked.setText("Picked: " + uri.getLastPathSegment());
                setStatus("Ready. Adjust the effects and tap Apply.");
            } catch (Exception e) {
                setStatus("Could not read file: " + e.getMessage());
            }
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
                final String msg = "Done: " + result.get("duration_s") + "s @ "
                        + result.get("sample_rate") + " Hz";
                runOnUiThread(() -> {
                    setStatus(msg);
                    btnPlay.setEnabled(true);
                });
            } catch (Exception e) {
                runOnUiThread(() -> setStatus("Error: " + e.getMessage()));
            } finally {
                runOnUiThread(() -> btnProcess.setEnabled(true));
            }
        }).start();
    }

    private void playOutput() {
        try {
            if (player != null) {
                player.release();
            }
            player = new MediaPlayer();
            player.setDataSource(outputPath);
            player.prepare();
            player.start();
            setStatus("Playing processed audio...");
        } catch (Exception e) {
            setStatus("Playback error: " + e.getMessage());
        }
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
