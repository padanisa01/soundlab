# Audio Mechanic — Feature List

App renamed from AudioFX/SoundLab to **Audio Mechanic** (2026-10-17):
display name in `strings.xml`, two-tone in-app header title
("Audio"/"Mechanic"). Package name `com.audiofx.app` unchanged.
Launcher icon (2026-10-17): wrench over waveform bars with a
horizontal mic below, on dark charcoal — legacy PNG mipmaps
(mdpi–xxxhdpi) plus adaptive icon (`mipmap-anydpi-v26`); also shown
at 34dp in the main-page header (`drawable/ic_app_logo`). Final
artwork: seamless gold-to-silver metallic wrench, rainbow-gradient
mic handle, teal bars/grille. Master at
`icon-concepts/icon-a8-final.png`.

Living list of what the app does. Update this file when a feature ships
or the plan changes.

## Shipped

### Audio input
- Pick a WAV file from device storage (Storage Access Framework).
- **Record**: the red record button captures from the microphone to a
  16-bit PCM WAV take, tried in order: UNPROCESSED @ 48 kHz stereo
  (raw, no AGC or noise suppression — the highest-fidelity path),
  VOICE_RECOGNITION @ 48 kHz stereo, MIC @ 44.1 kHz stereo, MIC @
  44.1 kHz mono. Stereo capture is how Android engages multiple
  built-in mics; apps cannot address individual mics. The take is
  named "Recording YYYY-MM-DD HH-mm.wav" and loaded as the current
  input (stereo is mixed down to mono like any other input).
  RECORD_AUDIO is requested at runtime; a pulsing red button, a REC
  pill, and a live elapsed timer show while recording.
- **Microphone card**: toggles for automatic gain control and noise
  suppression, attached to the capture session via Android's
  AutomaticGainControl / NoiseSuppressor effects (both off by
  default, choices persist). The record-start toast reports which
  effects actually engaged, or that the device doesn't support them.
- **Live effects** toggle: while recording, the mic feeds a Python
  live chain (Warmth -> Reverb -> Echo, params snapshotted from the
  sliders at record start) in 2048-sample blocks. The wet signal
  plays back live for monitoring and is baked into the take (stereo
  16-bit WAV when the device captures stereo; each channel gets its
  own chain with a decorrelated reverb seed so the stereo image
  survives). Monitoring latency is roughly 0.1-0.3 s; use
  headphones, the mic hears the speaker (a best-effort echo
  canceler is attached when the device offers one). Effect sliders
  lock during a live take.
- 8-bit and 16-bit PCM supported; stereo is mixed down to mono.
- The input file name is remembered and reused for the MP3 save name.
- **Normalize** normalizes the current audio so its 99th-percentile
  peak reaches -1 dBFS. Using the 99th percentile (not the absolute
  max) keeps pops and bursts from misleading the gain; the gain is one
  whole-file constant (dynamics preserved) and anything that would
  exceed the target is hard-held at -1 dBFS, so transients are limited,
  never boosted into clipping. The percentile is a code-level
  parameter (`percentile=99.0`), not user-facing. Applies to the
  processed output when there is one, else the input; undoable like any
  effect; the status reports the percentile reference and the target.
- **Normalize** button (transport row): smart-volume icon (level bars
  rising to a target line); same 99th-percentile normalize as before.
- **Live recording waveform**: while recording, the waveform grows bar
  by bar from the mic's live block peaks, with a red record-head line
  at the frontier; selection taps are ignored until the take ends.
- **Save dialog**: Save as MP3 now opens a dialog where you type the
  file name (prefilled from the track name) and see or change the
  destination folder, whether or not one was chosen before.
- **High-contrast mic switches**: on = bright teal thumb on a teal
  track, off = gray thumb on a dark track.
- **Track actions**: the track card is now an Open / Save / Share icon bar; the title lives only in the top status bar.
- **MP3 import**: MP3s can be opened as well as WAVs (decoded to WAV via MediaCodec on import).
- **Save wait screen**: saving shows the same split-screen wait dialog as share (progress on top, 300x250 ad below).
- **Premium**: one-time in-app purchase (`soundlab_premium`, Play Billing) from the hamburger menu; removes the banner and all wait-screen ads.
- **AdMob**: banner fixed at the bottom of the main screen, plus a split-screen share wait dialog (encoding progress on top, 300x250 medium-rectangle ad below, fresh ad every 15s while encoding runs, auto-closed when done). Test ad units for now; swap in the real app ID + ad unit IDs at release.
- **Share**: the hamburger menu's Share action encodes the current
  audio (processed output with all applied effects, or the input
  as-is) as a size-optimized 96 kbps MP3 and opens the system app
  picker. No save required first; shared via FileProvider from the
  app-private cache.
- **Hamburger menu + project status bar**: the top bar holds a hamburger
  menu (Open audio file, Recordings library, Save as MP3) and a status
  bar showing the project name, or `<untitled project - date - time>`
  when nothing is loaded. The old top-of-page Pick/Library buttons and
  the standalone Save button are gone.
- **Effect history panel**: the main page shows the full op history
  (label + timestamp per op) for library entries and picked files alike.
- **Auto-naming**: when a recording still has its auto-generated
  "Recording ..." name, exporting with a user-chosen filename renames
  the project to match. Manual renames are never overridden.
- **Advanced mic settings**: AGC, noise suppression, live effects and
  auto-normalize live in a collapsible panel, collapsed by default
  (expansion persisted).
- **Auto-normalize takes** toggle (on by default): recordings are
  peak-normalized to -1 dBFS right after the take, as an undoable
  history step — the raw mic capture stays recoverable via Undo.
  Picked files are never touched.
- **Trim** (selection row): replaces the selected window with silence
  (5 ms soft edges, no clicks); the selection stays put so you can
  audition or re-trim it. Undoable.
- **Join** (selection row): cuts the selected window out and glues
  the remaining parts together with a 5 ms crossfade at the junction
  (no silence in between, no click). The waveform, duration, and
  track meta reload; the selection clears. Undo restores the removed
  audio and reloads the waveform too.
- A waveform display below the picker shows the picked audio
  (peak amplitudes computed in the Python module), with a seconds
  timeline on the x-axis.
- Tap the waveform to place a start marker; tap again to place the end
  marker (a tap at/before the start moves it). Double-tap clears.
  Pinch to zoom (1x-8x), drag to pan while zoomed; the seconds
  timeline follows the visible range.
- Playback and Apply effects honor the selection: a start point alone
  plays/processes from there to the end of the file; a full window
  plays/processes only inside it (processed audio is spliced back with
  a short crossfade).

### Effects (applied in fixed order: Warmth -> Reverb -> Echo)
- **Warmth** slider (0-100%): saturated low-end; 0% is an exact bypass.
- **Reverb** presets: studio, concert_hall, bathroom, cave. Each preset
  synthesizes its own decaying-noise impulse response with per-preset
  decay, pre-delay, brightness, and wet mix.
- **Echo** slider (0-100%): 320 ms feedback delay; 0% is an exact bypass.
- All DSP runs in the Python module (`audiofx`, bundled via Chaquopy);
  NumPy FFT fast path with a pure-Python fallback.
- **Undo**: every Apply is logged in an in-memory history keyed by input
  filename hash (effects applied, timestamp, output file). Undo removes
  the latest entry and restores the previous output, and can be repeated
  until the original unprocessed audio is back.

### Playback
- **Play result** plays through MediaPlayer (async prepare, so the UI
  never blocks): the processed output when one exists, otherwise the
  raw input — so a selection can be auditioned before any Apply.
  Starts at the selection start when one is set, and stops at the
  selection end. The toast says which source is playing.
- **Pause** freezes playback; **Play** resumes from the pause point.
- **Stop** halts playback; enabled only while audio is playing or paused.
  Play is re-enabled after every stop, so different windows can be
  auditioned back to back.
- While playing, the waveform shows a horizontal progress bar across
  the played range, filling up to the current position, with a
  playhead line.

### Progress indication
- A horizontal progress bar is shown while Apply effects or Save as MP3
  runs, and hidden when idle.
- While Apply effects runs, the waveform fills left to right with a
  translucent gradient to indicate progress — confined to the
  selection window when one is set. The toast reports the
  window Python actually applied, e.g. `(applied 10.0s–20.0s)`.

### Diagnostics and Tune Up
- The Diagnose button (track card) runs twelve checks over the current
  audio on a background thread and shows a report card: clipping, level,
  DC offset, dead air, mains hum (50/60 Hz), noise floor, plosives,
  sibilance, uneven level, abrupt ends, loudness (LUFS), tonal balance.
- Every finding carries a one-tap Fix (a renderable library op, undoable
  like any other) and a "Tip" for next time. Clipping and high noise
  floor are labeled honestly as partial fixes ("reduce", not "remove");
  room reverb is advice-only, never faked.
- Tune Up applies all observed fixes as one composite `tune_up` op in
  canonical DSP order (declip, DC, hum, de-plosive, de-esser, silence
  trim, leveling, EQ, loudness, denoise, fades: each fix placed where it
  cannot undo an earlier one), stored as a single undoable history
  entry with a summary label. The op converges internally over up to 3
  passes run on the in-memory buffer (no temp files; the converged audio
  is adopted directly as the render, skipping the final re-render).
  Diagnosis runs only when the user taps Diagnose: Tune Up and
  individual fixes no longer trigger an automatic re-diagnosis. During the tune-up the waveform
  shows a step tracker overlay: each pass's steps listed grey (pending),
  the active step blinking green with its own 0-100 bar, finished steps
  solid green; the overlay clears when done. After Tune Up the diagnosis
  re-runs so findings visibly turn green.
- Tune-up recipes per content type: Singing (free, default: gentle
  de-esser, no leveling or silence trim, -14 LUFS), Podcast (premium:
  full voice chain, -16 LUFS), Music (premium: loudness/balance/fades,
  -14 LUFS). Detection stays universal; the recipe only decides what
  Tune Up applies. The recipe picker lives in the report dialog, the
  choice persists, locked recipes show an upsell into the existing
  one-time premium purchase (the Tune Up button is guarded too).
  Diagnosis and per-finding Fix buttons are free for everyone.
- LLM executive summary: below the score and recipe picker, the report
  shows a plain-language 2-3 sentence summary of the findings, framed for the selected profile,
  generated by qwen/qwen3.8-27b via Groq (same model as BareLabel).
  Only finding titles/details leave the device, never audio. The key
  comes from GROQ_API_KEY in the git-ignored local.properties via
  BuildConfig; without it the summary card is hidden and everything
  else works. Summaries are cached per findings+profile for the
  session and re-fetched when the profile changes.
- Python: `audiofx/diagnose.py` (checks + `tune_up_fixes`), `audiofx/repair.py`
  (11 fix ops), wired into `render_chain` via a shared `_apply_op`
  dispatcher; `diagnose_file` / `tune_up_build` / `tune_up_converge`
  entry points in `android_bridge.py`.

### Library of recordings
- Every recording becomes a durable project in `filesDir/library/`,
  holding one or more tracks: each track has an immutable pristine WAV
  (`tracks/<uuid>.wav`) plus its own ordered, re-runnable op chain in
  SQLite (`soundlab_library.db`, DB v2), surviving app restarts. Legacy
  single-take entries adopt a "Track 1" in place on first open. Since 2026-10-17 picked/imported files are first-class
  too: opening audio imports a pristine copy as a new entry (named
  after the file), with the same op chain, undo history and exports.
- **Library screen** (`LibraryActivity`, via the header icon or the
  "Open library" button): newest first; rows show name, recorded
  date/time, duration, size, up to 3 effect chips, and a teal dot when
  an MP3 was exported. Tap loads the entry into the waveform screen;
  long-press/overflow offers Rename, Duplicate, Delete (confirm).
  Header shows total size with a warning past 2 GB.
- **Cumulative ops, no generational loss**: Apply / Normalize / Trim /
  Join append an op row with parameters and render in float
  (`render_chain`), writing 16-bit PCM once to the render cache. The
  buffer stays a NumPy float64 array through the whole chain (no
  list/ndarray ping-pong); new ops render incrementally from the cached
  float chain tip, bit-identical to a full re-render, so cost no longer
  grows with history length. Undo pops the last op and re-renders from
  pristine — it works across sessions; the badge shows the persisted op
  count.
- Auto-normalize is op #1, so the pristine stays truly raw. Live-FX
  takes store the baked wet take as pristine with a display-only
  first op (never re-run).
- History is keyed by entry ID: renames never orphan it. Duplicate
  forks an independent copy with a "Forked from" marker. Edits
  auto-persist; there is no save-project step.

### Multi-track recording
- One library entry is a project of N tracks (`tracks` table, DB v3):
  each track has a UUID, an editable name, its own pristine WAV, its
  own op chain, a lock flag, and a mute flag.
- **Track panel** above the waveform: tap a row to select it
  (highlighted), tap the name to rename it, tap the lock toggle to
  lock or unlock it, tap the speaker toggle to mute or unmute it.
- **Waveform header** shows the selected track's name (tap to rename),
  its lock toggle, its mute toggle, and the "+" button, which adds a
  track ("Track N"). The old zoom +/- buttons are gone; pinch to zoom
  on the waveform instead (1x-8x, already supported).
- **Record** targets the selected track when it is empty and
  unlocked, else the first empty unlocked track, else a new track is
  auto-created ("Track N"). Recording never overwrites existing
  audio. Pressing record with no project open creates one first. The
  mic pipeline (AGC/NS/limiter/auto-normalize) applies unchanged to
  each take.
- **Overdub**: when recording starts, the current mix of all
  non-empty, unmuted tracks plays back on a dedicated thread while
  the mic captures, so you can play along with existing tracks. If
  nothing is audible, recording proceeds without playback. No
  latency compensation between playback and capture in this version
  (known limitation); use headphones to avoid the mic picking up
  the monitor.
- **Pause/resume**: while recording, the stop button becomes a pause
  button. Pause halts capture and playback, keeping the partial take;
  tapping record resumes, appending to the same take file, with
  playback restarting from the take-length offset to stay in sync.
  The record head shows a steady paused state. Pause-then-stop with
  zero audio keeps the track empty. Pause affects only the
  recording session, never already-recorded tracks.
- A locked track is immune to change: effects, trim, tune-up, Fix
  buttons, undo, and re-recording all no-op with a "Track locked"
  toast. Lock blocks changes, not playback: locked tracks are
  included in the mix, and diagnosing a locked track (read-only) is
  allowed.
- A muted track is excluded from every mix: the monitor mix during
  recording, the main waveform mix, and the share mixdown. Mute is
  the way to silence a track everywhere; it persists per track.
- **Lazy mix**: the waveform mix is computed in memory for display
  only (no file is written); the mix file is produced on demand for
  playback, overdub, and share. Muting to zero audible tracks clears
  the waveform with no processing at all.
- Effects, undo, diagnose, and tune-up target the selected track
  only. Each track renders incrementally from its own cached chain
  tip, so editing one track never re-renders the others.
- The main waveform and playback always show the current mix of all
  tracks: each track is resampled to the project rate (linear
  interpolation), zero-padded to the longest, summed in float64, and
  peak-normalized to -1 dBFS (`mixdown` in `android_bridge.py`).
  Empty tracks are skipped.
- **Save**: each audible track is written as `<sanitized track
  name>.wav` into the user-chosen folder; empty tracks are skipped.
- **Share**: mixes all tracks down, then the existing 96 kbps MP3
  FileProvider share runs on the mix.
- Not included by design: solo, per-track volume, pan.

### Save
- **Save as MP3**: encodes with `lameenc` (CBR 192 kbps), in the Python
  module so it stays unit-testable. Works with no Apply: saves the
  processed output when there is one, otherwise the input as-is
  (status says "no effects").
- The save dialog offers an editable file name (prefilled from the
  track name) and shows the destination folder with a Choose button,
  whether or not a folder was picked before.
- Saving to an existing MP3 name always asks: **Overwrite** or **Keep
  both** (the ` (n)` suffix). Never silently overwrites.
- The destination folder is chosen via the system folder picker and its
  permission is persisted (`takePersistableUriPermission`), so re-export
  works across app launches.
- Every export of a library entry is logged (file name, folder, op count
  at export time); picked files are not logged.

## Planned / known gaps
- Compressed formats (MP3, etc.) as *input* — v1 is WAV-only.
