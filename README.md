# Muisc

A local music player for Android in the spirit of Retro Music Player, with one signature feature: a **transition
engine that acts like a personal DJ**. When you play a playlist or shuffle your library, each song glides into the
next: beat-matched blends, slow tempo glides, bass and stem swaps, filter sweeps, echo-outs, loop rolls into the
drop, carried-over textures, ambient bridges. Albums played in order are left exactly as the artist sequenced them:
gapless, untouched.

The engine is pure Kotlin/JVM, so every transition technique can be developed, rendered and measured on a desktop
with the `muisc` command-line tool, and the Android app reuses the identical code.

## Repository layout

| Module | What it is |
|---|---|
| `engine/audio` | Planar float `AudioBuffer`, seekable `PcmStream` decoders (JVM: WAV/AIFF/MP3/FLAC/OGG), WAV I/O, synthetic test songs with known tempo, key and structure |
| `engine/dsp` | FFT/STFT, windows, mel filterbank, RBJ biquads, Linkwitz-Riley crossovers, state-variable filter, sinc and variable-rate resamplers, WSOLA time-stretch and pitch shift, vinyl brake, loop roll, delay, FDN reverb with freeze, spectral freeze, shaped noise, granulator, oscillators, HPSS, pseudo-stems, BS.1770 loudness, true-peak limiter, artifact detector |
| `engine/analysis` | Track analysis: onset detection, tempo, Ellis beat tracking, rigid/flex beat grids, downbeats and phrases, key (Krumhansl-Schmuckler with tuning estimation), loudness, per-bar features, long-term spectra, structure segmentation, intro/outro classification, mix-in/mix-out cues |
| `engine/transitions` | The transition SDK: `TransitionStrategy` and `TransitionModifier` contracts, the beat-domain substrate (master grid, phase-locked decks), 14 strategies and 2 modifiers, pair compatibility analysis, the planner with its escalation ladder, the renderer, live-transition plans, program building |
| `engine/metrics` | Numeric quality metrics for renders (clicks, level jumps, true peak, seam identity, beat alignment) and golden-render fingerprints |
| `engine/player` | Sample-accurate program player (album gapless, rendered transitions, live DJ moves), seam fading, transition coordinator, JVM sinks |
| `tools/cli` | `muisc` command line: analyze, plan, render, mix, ab, sweep, score, check, live, stems, synth |
| `app` | Android app: Jetpack Compose UI, MediaStore library, Room, Media3 session, custom AudioTrack engine |
| `docs` | `DESIGN.md` (the frozen architecture), `DECISIONS.md`, `ANDROID_BUILD_NOTES.md`, `CLI.md` |

## Building

Engine modules and the CLI need only a JDK 17+ (JDK 21 is used for development):

```
./gradlew build            # compiles and runs every engine test
./gradlew :tools:cli:installDist
tools/cli/build/install/muisc/bin/muisc --help
```

The Android app is included automatically when an Android SDK is found (`sdk.dir` in `local.properties` or
`ANDROID_HOME`). See `docs/ANDROID_BUILD_NOTES.md` before the first build.

## How a transition is made

1. **Analysis** (once per track, cached): tempo and beat grid, downbeats and 8-bar phrases, key and Camelot code,
   loudness, energy and band balance per bar, sections, intro/outro type, mix-in/mix-out cue points, the outro's
   spectral texture.
2. **Pair features**: tempo relation (same/half/double time), stretch needed, key distance, best pitch shift,
   loudness and energy deltas, vocal clash, spectral similarity, room to mix on each side.
3. **Planning**: every strategy scores the pair from those features; the planner applies your preferences
   (adventurousness, per-strategy weights, max stretch, key lock), a variety penalty, and attaches modifiers
   (tempo glide, texture carry). The ranking is an escalation ladder: beat-domain blends first, tempo-agnostic DJ
   moves next, structural transitions, then an ambient bridge and finally a plain crossfade. Every pair gets a plan.
4. **Rendering ahead of time**: the transition segment (A's tail mixed into B's head) is rendered while A still
   plays, checked for artifacts, and spliced sample-accurately between A's body and B's body. If a render is not
   ready (skip, queue edit) the player performs a live DJ move instead.
5. **Album rule**: a queue built from an album in track order never gets transitions; shuffle and playlists do.
   Both behaviours are adjustable in Settings.

## Strategies

`crossfade`, `outroIntroMinimal`, `phraseCut`, `beatMatchedBlend`, `bassSwap`, `stemSwap`, `drumBreakBridge`,
`filterSweep`, `echoOut`, `loopRollRiser`, `harmonicBlend`, `spectralFreezeBridge`, `ambientBridge`, `brakeStop`,
plus the modifiers `tempoGlide` and `textureCarry`. Each is one file under
`engine/transitions/src/main/kotlin/dev/muisc/transitions/strategies` with typed, documented parameters, and each
has tests on synthetic songs. Adding a technique means adding one file and registering it.

## Experimenting

Render every strategy on a pair and compare them side by side, sweep a parameter and plot a metric, or render a
whole playlist the way the DJ would play it:

```
muisc synth --set --out fixtures/          # deterministic test songs
muisc analyze song.flac --click grid.wav   # hear the detected beat grid
muisc plan a.flac b.flac                   # ranked strategies with reasons
muisc render a.flac b.flac --strategy bassSwap --set swapBar=12 --context 20 -o swap.wav
muisc ab a.flac b.flac -o ab/              # one WAV per strategy + metrics.csv + index.html
muisc mix playlist/*.flac -o set.wav       # a full mini DJ set with a report
```

See `docs/CLI.md` for every command.

## Status

Engine, analysis, transitions, metrics, player and CLI are built and tested on the JVM. The Android app is written
against those modules but has not yet been compiled with an Android SDK; the first local build is expected to need a
short fix-up pass (see `docs/ANDROID_BUILD_NOTES.md`). Neural stem separation is an interface with a documented
integration point; the shipped pseudo-stems are signal-processing approximations.
