# AudioFX — Feature List

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
- **Normalize** button (track card): peak-normalizes the current audio
  to -1 dBFS with a single whole-file gain change (dynamics
  preserved). Applies to the processed output when there is one,
  else the input; undoable like any effect; the status reports the
  before/after peak.
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

### Save
- **Save as MP3**: encodes with `lameenc` (CBR 192 kbps), in the Python
  module so it stays unit-testable. Works with no Apply: saves the
  processed output when there is one, otherwise the input as-is
  (status says "no effects").
- The output keeps the input's file name with an `.mp3` extension;
  name collisions get a ` (n)` suffix.
- The destination folder is chosen once via the system folder picker
  and reused for every save in the session; it is never persisted
  between app launches.

## Planned / known gaps
- Compressed formats (MP3, etc.) as *input* — v1 is WAV-only.
