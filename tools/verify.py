#!/usr/bin/env python3
"""Verify the audiofx Python module without any JVM or Android SDK.

1. Compile-check every .py under app/src/main/python (proves the module the
   APK ships is syntactically valid and importable).
2. Run the full unittest suite.
3. Static-check the Android/Gradle scaffold: required files exist and contain
   their key markers (plugin ids, manifest package, Chaquopy bootstrap, UI).

Exit 0 only if all three pass. Also wired into Gradle as `gradle testPython`.
"""

import compileall
import os
import sys
import unittest

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PY_SRC = os.path.join(ROOT, "app", "src", "main", "python")
TESTS_DIR = os.path.join(ROOT, "tests")

SCAFFOLD_CHECKS = [
    # (relative path, required marker)
    ("settings.gradle", 'rootProject.name = "AudioFX"'),
    ("settings.gradle", "mavenCentral()"),
    ("build.gradle", 'id "com.chaquo.python"'),
    ("build.gradle", "17.1.0"),
    ("build.gradle", "testPython"),
    ("app/build.gradle", 'id "com.chaquo.python"'),
    ("app/build.gradle", "chaquopy"),
    ("app/build.gradle", "minSdk 24"),
    ("app/build.gradle", "abiFilters"),
    ("app/build.gradle", 'install "numpy"'),
    ("app/build.gradle", 'install "lameenc"'),
    ("app/build.gradle", "documentfile"),
    ("app/build.gradle", 'namespace "com.audiofx.app"'),
    ("app/src/main/AndroidManifest.xml", ".MainActivity"),
    ("app/src/main/java/com/audiofx/app/MainActivity.java", "AndroidPlatform"),
    ("app/src/main/java/com/audiofx/app/WaveformView.java", "class WaveformView"),
    ("app/src/main/java/com/audiofx/app/WaveformView.java", "startProgressAnimation"),
    ("app/src/main/java/com/audiofx/app/MainActivity.java", "audiofx.android_bridge"),
    ("app/src/main/java/com/audiofx/app/MainActivity.java", "process_file"),
    ("app/src/main/java/com/audiofx/app/MainActivity.java", "ACTION_OPEN_DOCUMENT_TREE"),
    ("app/src/main/java/com/audiofx/app/MainActivity.java", "btn_save"),
    ("app/src/main/java/com/audiofx/app/MainActivity.java", "encode_wav_to_mp3"),
    ("app/src/main/java/com/audiofx/app/MainActivity.java", "waveform_peaks"),
    ("app/src/main/java/com/audiofx/app/MainActivity.java", "btn_stop"),
    ("app/src/main/AndroidManifest.xml", "RECORD_AUDIO"),
    ("app/src/main/java/com/audiofx/app/AudioRecorder.java", "class AudioRecorder"),
    ("app/src/main/java/com/audiofx/app/AudioRecorder.java", "AutomaticGainControl"),
    ("app/src/main/java/com/audiofx/app/AudioRecorder.java", "NoiseSuppressor"),
    ("app/src/main/res/layout/activity_main.xml", "switch_agc"),
    ("app/src/main/res/layout/activity_main.xml", "switch_ns"),
    ("app/src/main/res/layout/activity_main.xml", "ic_mic"),
    ("app/src/main/java/com/audiofx/app/MainActivity.java", "btn_record"),
    ("app/src/main/res/layout/activity_main.xml", "btn_record"),
    ("app/src/main/java/com/audiofx/app/MainActivity.java", "progress_bar"),
    ("app/src/main/java/com/audiofx/app/MainActivity.java", "btn_undo"),
    ("app/src/main/java/com/audiofx/app/MainActivity.java", "EffectHistoryDb"),
    ("app/src/main/java/com/audiofx/app/MainActivity.java", "onSelectionChanged"),
    ("app/src/main/java/com/audiofx/app/MainActivity.java", "selStartSec"),
    ("app/src/main/java/com/audiofx/app/MainActivity.java", "scheduleStopAt"),
    ("app/src/main/java/com/audiofx/app/WaveformView.java", "OnSelectionListener"),
    ("app/src/main/java/com/audiofx/app/WaveformView.java", "ScaleGestureDetector"),
    ("app/src/main/java/com/audiofx/app/WaveformView.java", "setDuration"),
    ("app/src/main/python/audiofx/android_bridge.py", "encode_wav_to_mp3"),
    ("app/src/main/python/audiofx/android_bridge.py", "waveform_peaks"),
    ("app/src/main/python/audiofx/android_bridge.py", "start_sec"),
    ("app/src/main/java/com/audiofx/app/MainActivity.java", "btn_play_pause"),
    ("app/src/main/java/com/audiofx/app/MainActivity.java", "pausePlayback"),
    ("app/src/main/java/com/audiofx/app/WaveformView.java", "startProgressAnimation(float fromFrac"),
    ("app/src/main/java/com/audiofx/app/MainActivity.java", "isPlayingInput"),
    ("app/src/main/java/com/audiofx/app/WaveformView.java", "zoomBy"),
    ("app/src/main/res/layout/activity_main.xml", "btn_play_pause"),
    ("app/src/main/res/layout/activity_main.xml", "seek_warmth"),
    ("app/src/main/res/layout/activity_main.xml", "seek_echo"),
    ("app/src/main/res/layout/activity_main.xml", "btn_save"),
    ("app/src/main/res/layout/activity_main.xml", "btn_stop"),
    ("app/src/main/res/layout/activity_main.xml", "btn_undo"),
    ("app/src/main/res/layout/activity_main.xml", "progress_bar"),
    ("app/src/main/res/layout/activity_main.xml", "WaveformView"),
    ("app/src/main/res/layout/activity_main.xml", "chip_hall"),
    ("app/src/main/res/layout/activity_main.xml", "toast_view"),
    ("app/src/main/res/layout/activity_main.xml", "undo_badge"),
    ("app/src/main/res/layout/activity_main.xml", "sel_chip_text"),
    ("app/src/main/res/layout/activity_main.xml", "tv_source"),
    ("app/src/main/res/layout/activity_main.xml", "tv_time_cur"),
    ("app/src/main/res/values/colors.xml", "sl_teal"),
    ("app/src/main/res/values/themes.xml", "Theme.SoundLab"),
    ("app/src/main/res/drawable/bg_apply.xml", "gradient"),
    ("app/src/main/res/drawable/ic_play.xml", "path"),
]


def main():
    ok = True

    print("== 1. Python compile check ==")
    if compileall.compile_dir(PY_SRC, quiet=1, force=True):
        print("compileall OK:", PY_SRC)
    else:
        print("compileall FAILED")
        ok = False

    print("== 2. Unit tests ==")
    sys.path.insert(0, PY_SRC)
    suite = unittest.TestLoader().discover(TESTS_DIR, top_level_dir=ROOT)
    result = unittest.TextTestRunner(verbosity=1).run(suite)
    if not result.wasSuccessful():
        ok = False

    print("== 3. Android scaffold static check ==")
    for rel, marker in SCAFFOLD_CHECKS:
        path = os.path.join(ROOT, rel)
        if not os.path.isfile(path):
            print("MISSING FILE:", rel)
            ok = False
            continue
        with open(path, encoding="utf-8") as f:
            content = f.read()
        if marker not in content:
            print("MARKER NOT FOUND in %s: %r" % (rel, marker))
            ok = False
    if ok:
        print("scaffold OK")

    print("VERIFY " + ("PASS" if ok else "FAIL"))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
