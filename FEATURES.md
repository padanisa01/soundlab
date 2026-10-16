# AudioFX — Feature List

Living list of what the app does. Update this file when a feature ships
or the plan changes.

## Shipped

### Audio input
- Pick a WAV file from device storage (Storage Access Framework).
- 8-bit and 16-bit PCM supported; stereo is mixed down to mono.
- The input file name is remembered and reused for the MP3 save name.
- A waveform display below the picker shows the picked audio
  (peak amplitudes computed in the Python module).

### Effects (applied in fixed order: Warmth -> Reverb -> Echo)
- **Warmth** slider (0-100%): saturated low-end; 0% is an exact bypass.
- **Reverb** presets: studio, concert_hall, bathroom, cave. Each preset
  synthesizes its own decaying-noise impulse response with per-preset
  decay, pre-delay, brightness, and wet mix.
- **Echo** slider (0-100%): 320 ms feedback delay; 0% is an exact bypass.
- All DSP runs in the Python module (`audiofx`, bundled via Chaquopy);
  NumPy FFT fast path with a pure-Python fallback.

### Playback
- **Play result** plays the processed WAV through MediaPlayer
  (async prepare, so the UI never blocks).
- **Stop** halts playback; enabled only while audio is playing.

### Progress indication
- A horizontal progress bar is shown while Apply effects or Save as MP3
  runs, and hidden when idle.
- While Apply effects runs, the waveform fills left to right with a
  translucent gradient to indicate progress.

### Save
- **Save as MP3**: encodes the processed audio with `lameenc`
  (CBR 192 kbps), in the Python module so it stays unit-testable.
- The output keeps the input's file name with an `.mp3` extension;
  name collisions get a ` (n)` suffix.
- The destination folder is chosen once via the system folder picker
  and reused for every save in the session; it is never persisted
  between app launches.

## Planned / known gaps
- Compressed formats (MP3, etc.) as *input* — v1 is WAV-only.
