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
- **Advanced mic settings**: AGC, noise suppression, limiter, live
  effects and auto-normalize live in a collapsible panel, collapsed by
  default (expansion persisted).
- **Limiter** (on by default): lookahead peak limiter on the mic capture
  path (both dry and live-FX recording), ceiling -1 dBFS, 5 ms lookahead,
  linked stereo — loud peaks can never clip a take, and it is
  bit-transparent when nothing clips. Python reference
  (`audiofx/limiter.py`, unit-tested incl. zero-overshoot guarantee)
  ported 1:1 to `LiveLimiter.java`; differential check passes
  bit-for-bit.
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

### Library of recordings
- Every recording becomes a durable project in `filesDir/library/`:
  one immutable pristine WAV (`takes/<uuid>.wav`) plus an ordered,
  re-runnable op chain in SQLite (`soundlab_library.db`), surviving
  app restarts. Since 2026-10-17 picked/imported files are first-class
  too: opening audio imports a pristine copy as a new entry (named
  after the file), with the same op chain, undo history and exports.
- **Library screen** (`LibraryActivity`, via the header icon or the
  "Open library" button): newest first; rows show name, recorded
  date/time, duration, size, up to 3 effect chips, and a teal dot when
  an MP3 was exported. Tap loads the entry into the waveform screen;
  long-press/overflow offers Rename, Duplicate, Delete (confirm).
  Header shows total size with a warning past 2 GB.
- **Cumulative ops, no generational loss**: Apply / Normalize / Trim /
  Join append an op row with parameters and re-render the whole chain
  from pristine in float (`render_chain`), writing 16-bit PCM once to
  the render cache. Undo pops the last op and re-renders — it works
  across sessions; the badge shows the persisted op count.
- Auto-normalize is op #1, so the pristine stays truly raw. Live-FX
  takes store the baked wet take as pristine with a display-only
  first op (never re-run).
- History is keyed by entry ID: renames never orphan it. Duplicate
  forks an independent copy with a "Forked from" marker. Edits
  auto-persist; there is no save-project step.

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
