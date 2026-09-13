# Muisc — Final Architecture & Design (DJ Transition Engine + Retro-style player)

Status: **FROZEN for implementation.** This document is the merge of the winning "dsp-quality" design with the
engine-robustness ideas of "mobile-robustness" and the iteration tooling of "experimentation-velocity", with every
error flagged by the three judges corrected (see §13 for the correction log). It is written so that several
engineers can implement disjoint work packages in parallel against the interfaces in §2, which are frozen.

The repository already contains a bootstrapped skeleton (`engine:audio|dsp|analysis|transitions`, `tools:cli`,
`:app`, packages `dev.muisc.*`) with frozen interfaces and passing tests. **This design builds on that skeleton
verbatim**; it does not rename modules or packages. Additions to frozen files are listed in §2.10 and are the only
edits to committed contracts allowed.

---

## 0. Architecture position

**Render-ahead is the v1 architecture**, amended in five ways:

1. **Beat-domain rendering.** A transition is defined on a `MasterGrid` of *output beat frames*; each deck is a
   `PhaseLockedDeck` (deck audio + deck `BeatGrid` + time-stretcher) slaved to that grid with a PLL term that
   cancels accumulated source-position error. Beat-matching, half/double-time matching and the user's "slowly
   change tempo" are one mechanism (the grid's per-beat BPM curve), not per-strategy alignment code.
2. **One `ProgramPlayer`, one seam algorithm.** Album gapless playback, rendered transitions and live transitions
   are all `Segment`s of one `PlaybackProgram` walked by one pure-JVM player. The code path that plays every album
   is the code path that plays every transition. The same player writes WAV in the CLI and feeds `AudioTrack`.
3. **Splice contract + deterministic resampler + linear seam fade.** A rendered segment starts with A's samples at
   `aExitFrame` (deck gain applied, stretch ratio 1.0) and ends with B's samples up to `bEntryFrame` (ratio 1.0).
   Body and render read the *same* position-deterministic `ResamplingPcmStream`, so the samples on both sides of
   a seam are identical by construction on lossless input and identical-decoder output on lossy input. Every seam
   additionally gets a **5 ms linear (equal-gain) micro-fade** — a mathematical no-op on identical signals, and an
   inaudible declick on slightly different ones. (Equal-power would add a +3 dB bump on identical signals; linear
   is the correct law for coherent material.)
4. **Live fallbacks are real DJ moves.** When no render is ready (skip, deadline miss, queue edit) the player
   executes a `LivePlan` on the audio thread: crossfade, phrase cut, bass swap, filter sweep or echo-out, with a
   cheap variable-rate resampler so sub-2 % tempo matches can be beat-locked live. Strategies never run live;
   `LivePlanFactory` derives a live plan from the same analyses.
5. **Streaming analysis and bounded memory.** Analysis never holds a spectrogram; renders are capped by
   `EngineLimits`; stems are computed for the transition windows only.

A real-time two-deck engine (Mixxx-style) was rejected: every strategy would have to fit a phone CPU budget,
look-ahead strategies (texture carry, ambient bridge, loop-roll) become awkward, and CLI/phone parity is lost.

---

## 1. Gradle module layout

All `engine:*` and `tools:*` modules are Kotlin/JVM (`jvmToolchain(21)`, Java 17 bytecode), depend only on Maven
Central, and contain no `android.*`/`androidx.*` references (enforced by `ArchTest` in each module). `:app` is
included by `settings.gradle.kts` only when an Android SDK directory is present (existing logic; no network probe).

| Module | Package root | Depends on | Kind | Purpose |
|---|---|---|---|---|
| `:engine:audio` (exists) | `dev.muisc.audio` | mp3spi, jflac, vorbisspi, (+ AAC SPI, see §7.1) | pure JVM | `AudioBuffer`, `PcmStream`, `AudioDecoder`, `WavIo`, `Synth`, **new:** `ResamplingPcmStream`, `GaplessInfo` |
| `:engine:dsp` (exists) | `dev.muisc.dsp` | audio | pure JVM | Primitives (§6), `Stems`/`StemSeparator`/`PseudoStemSeparator`, `qa.ArtifactDetector` |
| `:engine:analysis` (exists) | `dev.muisc.analysis` | audio, dsp, serialization | pure JVM | `TrackAnalysis` model, `DefaultTrackAnalyzer` (streaming), feature extractors, `FileAnalysisCache` |
| `:engine:transitions` (exists) | `dev.muisc.transitions` | audio, dsp, analysis, serialization | pure JVM | Strategy SDK, `core/` beat-domain substrate, `strategies/`, `modifiers/`, `planner/`, `live/`, `DefaultTransitionRenderer`, `RenderKey` |
| `:engine:metrics` (new) | `dev.muisc.metrics` | audio, dsp, analysis, transitions | pure JVM | `ArtifactMetrics`, `MetricsReport`, thresholds, `GoldenFingerprint` — used verbatim by tests, CLI and the phone Lab |
| `:engine:player` (new) | `dev.muisc.player` | audio, dsp, transitions, coroutines | pure JVM | `ProgramPlayer`, `SeamFader`, `LiveExecutor`, `TransitionCoordinator`, `EngineLimits`, `AudioSink`, `Clock` |
| `:engine:lab` (new) | `dev.muisc.lab` | all engine modules | pure JVM | `LabSession` (re-render/compare/sweep API shared by CLI and app), CSV/SVG/HTML reporters |
| `:engine:testkit` (new) | `dev.muisc.testkit` | audio, dsp, analysis, transitions, metrics | pure JVM, `testFixtures`-style | Synthetic songs/pairs, `StubAnalyzer`, `StrategyContract` harness, golden compare |
| `:tools:cli` (exists) | `dev.muisc.cli` | all engine modules, clikt | pure JVM | `muisc` commands (§11), zero-dependency `lab serve` HTTP UI |
| `:app` (exists) | `dev.muisc.app` | all engine modules, AndroidX, Media3, Room, DataStore | Android | Player app (§8) |

Dependency arrows (strictly downward, enforced by Gradle `api`/`implementation` and `ArchTest`):

```
audio ← dsp ← analysis ← transitions ← metrics ← lab ← cli
                              ↑            ↑
                            player ────────┘ (player depends on transitions + dsp; lab depends on player)
app ← {audio, dsp, analysis, transitions, metrics, player, lab}
```

`transitions` never depends on `metrics` or `player`; strategies know nothing about scoring or playback.

---

## 2. Core data model and interfaces (FROZEN)

Conventions: **float32 planar** audio (`AudioBuffer`, `Array<FloatArray>` per channel); **frames are `Long`**
positions / `Int` lengths at the engine sample rate; **musical positions are `Double` beats**; seconds appear only
in UI/lane visualisation. The engine sample rate is `TransitionPrefs.sampleRate` (44 100 default on the JVM;
`AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE`, normally 48 000, on Android). All frame positions in `TrackAnalysis`,
`TransitionPlan` and `PlaybackProgram` are **decoder-relative engine-rate frames**: frame 0 is the first PCM frame
the platform decoder emits after gapless trimming, resampled to the engine rate. The analysis cache is keyed by
`fingerprint + sampleRate + version`, and the fingerprint includes the decoder identity (§3.3).

### 2.1 Audio (`dev.muisc.audio`, committed + additions)

Committed and unchanged: `AudioBuffer`, `PcmStream` (+ `readAll`, `readRange`, `BufferPcmStream`), `AudioDecoder`,
`AudioSourceId`, `AudioFormatInfo`, `CompositeDecoder`, `WavIo`, `Synth`, `SyntheticSong`.

Additions (WP0):

```kotlin
package dev.muisc.audio

/** Encoder priming/padding in NATIVE frames (LAME/Xing for MP3, iTunSMPB for AAC; 0/0 for lossless). */
data class GaplessInfo(val encoderDelayFrames: Int, val encoderPaddingFrames: Int) { companion object { val NONE = GaplessInfo(0, 0) } }

/**
 * Position-deterministic sample-rate converter. Output frame n depends only on the input around n * (inRate/outRate)
 * and never on where reading started: `seek(n)` re-primes the polyphase history from input frame
 * floor(n*ratio) - taps, so two instances positioned independently produce identical samples.
 * Identity (no filtering, pass-through) when inner.sampleRate == targetRate.
 */
class ResamplingPcmStream(val inner: PcmStream, val targetRate: Int, val kernel: SincKernelSpec = SincKernelSpec.DEFAULT) : PcmStream
data class SincKernelSpec(val taps: Int = 32, val phases: Int = 512, val kaiserBeta: Double = 9.0) { companion object { val DEFAULT = SincKernelSpec() } }

/** Opens a track at the engine format: decoder → gapless trim → ResamplingPcmStream → optional channel adapt. One instance per reader. */
interface EngineStreamFactory { fun open(source: AudioSourceId, sampleRate: Int, channels: Int): PcmStream }
```

### 2.2 Analysis model (`dev.muisc.analysis.model`, committed, unchanged)

`TrackAnalysis`, `TempoEstimate`, `TempoCandidate`, `LoudnessInfo`, `Section`, `SectionLabel`, `OutroType`,
`IntroType`, `Cues`, `BarFeatures`, `BeatGrid` (`GridKind.RIGID|FLEX`, `frameOfBeat`, `beatAtFrame`, downbeat and
phrase helpers), `MusicalKey`, `Camelot` (`distanceTo`), `KeyEstimate`. `TrackAnalysis.extra: Map<String, Double>`
carries experimental numbers (e.g. `"tuningCents"`, `"introKeyTonic"`, `"outroKeyTonic"`, `"outroKeyMode"`,
`"introKeyMode"`, `"outroLtas.<i>"` is NOT allowed — vectors go in versioned fields, see §2.10).

Analysis interfaces (`dev.muisc.analysis`): `TrackAnalyzer`, `AnalysisProgress`, `AnalysisCache`,
`MemoryAnalysisCache` — committed. Addition (WP0, default method so no implementer breaks):

```kotlin
interface TrackAnalyzer {
    fun analyze(audio: AudioBuffer, sourceId: String, fingerprint: String, progress: AnalysisProgress = AnalysisProgress.NONE): TrackAnalysis
    /** Streaming entry point (the one production code uses): reads the stream once, never holds the whole track. */
    fun analyze(stream: PcmStream, sourceId: String, fingerprint: String, progress: AnalysisProgress = AnalysisProgress.NONE): TrackAnalysis =
        analyze(stream.readAll(), sourceId, fingerprint, progress)
}
```

`DefaultTrackAnalyzer` implements the streaming overload natively and the buffer overload via `BufferPcmStream`.

### 2.3 Stems (`dev.muisc.dsp.stems`, committed + one addition)

`StemKind`, `StemQuality {PSEUDO, ML}`, `Stems` (invariant: sum == original within −60 dBFS), `StemSeparator`.
Addition:

```kotlin
/** Integration point for neural separation (Android: ONNX Runtime + Demucs-class model chosen by the user).
 *  Contract: input any length ≤ 120 s at 44.1/48 kHz stereo; output four stems of identical shape; `other` MUST be
 *  computed as original - (drums+bass+vocals) so the Stems invariant holds regardless of model residual. */
interface MlStemSeparator : StemSeparator { val modelId: String; val available: Boolean }
```

`PseudoStemSeparator` (in `dev.muisc.dsp.stems`) is the always-available implementation (§6.8).

### 2.4 Transition SDK (`dev.muisc.transitions`, committed, unchanged)

`FrameRange`, `Applicability`, `StemNeed`, `AutomationLane`/`LanePoint`, `TransitionPlan` (with the splice
contract in its KDoc), `StemProvider`, `TransitionInput`, `RenderContext`, `Marker`, `RenderReport`,
`RenderedTransition`, `TransitionStrategy` (`applicability` / `plan` / `render`), `TransitionModifier`
(`applicability` / `adjustPlan` / `apply`), `ParamSpec` (`DoubleSpec|IntSpec|BoolSpec|ChoiceSpec`), `Params`,
`PairFeatures`, `TempoRelation`, `PairAnalyzer`, `PlanCandidate`, `RankedPlans`, `TransitionPlanner`,
`TrackAudioLoader`, `TransitionRenderer`, `StrategyRegistry`, `PlaybackContext`, `TrackRef`, `TransitionPrefs`,
`TransitionGating`, `Segment`, `PlaybackProgram`.

**Precise meaning of "unity gain" in the splice contract.** Each deck has a *deck gain*
`DeckGain.of(analysis, prefs) = clamp(prefs.targetLufs − analysis.loudness.integratedLufs, −12, +6) dB`
(0 dB when `prefs.targetLufs` is NaN). `ProgramPlayer` applies the deck gain to every `Segment.Body`; the renderer
applies the identical gain to both decks before any processing. "Unity" in the contract therefore means "deck gain
applied, nothing else". In `PlaybackContext.ALBUM` all tracks of the album share one gain (that of the loudest
track) so album dynamics are preserved.

Typed parameter access — thin delegate layer on the committed `ParamSpec`/`Params` (no reflection):

```kotlin
package dev.muisc.transitions.sdk

abstract class ParamSet(val strategyId: String) {
    val specs: List<ParamSpec>                       // in declaration order
    protected fun double(id: String, label: String, default: Double, min: Double, max: Double, unit: String = "", doc: String = ""): ParamSpec.DoubleSpec
    protected fun int(id: String, label: String, default: Int, min: Int, max: Int, unit: String = "", doc: String = ""): ParamSpec.IntSpec
    protected fun bool(id: String, label: String, default: Boolean, doc: String = ""): ParamSpec.BoolSpec
    protected fun choice(id: String, label: String, default: String, choices: List<String>, doc: String = ""): ParamSpec.ChoiceSpec
}
// usage inside a strategy:  object P : ParamSet("bassSwap") { val overlapBars = int("overlapBars", "Overlap", 16, 4, 64, "bars") }
//                          val bars = params.int(P.overlapBars)
```

### 2.5 Beat-domain substrate (`dev.muisc.transitions.core`, new, frozen signatures)

```kotlin
package dev.muisc.transitions.core

enum class GlideCurve { LINEAR, S_CURVE, EXP }

/** Output beat frames F_0..F_K with per-beat BPM. Built by MasterGrid.build; consumed by PhaseLockedDeck. Never drifts: rounding residual is carried in a Double. */
class MasterGrid(val sampleRate: Int, val beatFrames: LongArray, val bpmPerBeat: DoubleArray, val beatsPerBar: Int = 4) {
    val beatCount: Int
    fun frameOfBeat(beat: Double): Long
    fun beatAtFrame(frame: Long): Double
    companion object {
        /** Constant tempo grid of `beats` beats at `bpm` starting at frame 0 of the output. */
        fun constant(sampleRate: Int, bpm: Double, beats: Int, beatsPerBar: Int = 4): MasterGrid
        /** Glide from bpmFrom to bpmTo over glideBeats (curve), then hold bpmTo for holdBeats. */
        fun glide(sampleRate: Int, bpmFrom: Double, bpmTo: Double, glideBeats: Int, holdBeats: Int, curve: GlideCurve, beatsPerBar: Int = 4): MasterGrid
        /** Reads `grid.*` params written by TempoGlide.adjustPlan (see §4 modifiers); falls back to constant(A.bpm). */
        fun fromPlan(plan: TransitionPlan, a: TrackAnalysis, b: TrackAnalysis, f: PairFeatures, prefs: TransitionPrefs): MasterGrid
    }
}

enum class StretchMode { RESAMPLE, WSOLA, PHASE_VOCODER }

/** Chooses the stretcher: |ratio-1| < 0.02 → RESAMPLE (pitch drifts < 35 cents) unless prefs.keyLock forces WSOLA; else WSOLA; PHASE_VOCODER only when params say so. */
object StretcherSelector { fun select(ratio: Double, prefs: TransitionPrefs, forcePv: Boolean = false): StretchMode }

/**
 * Renders deck audio so that deck beat j_k lands exactly on master beat k for k in [0, beats).
 * ratio_k = deckPeriod_k / masterPeriod_k, corrected each beat by e_k / masterPeriod_k where e_k is the stretcher's
 * accumulated source-position error (PLL). Output length is exactly master.frameOfBeat(beats) - master.frameOfBeat(0).
 * `onsetsFrames` (deck ODF onsets) pin WSOLA frames so transients are copied once.
 */
class PhaseLockedDeck(val deck: AudioBuffer, val deckGrid: BeatGrid, val deckStartBeat: Double, val onsetFrames: LongArray, val mode: StretchMode, val sampleRate: Int) {
    fun render(master: MasterGrid, fromMasterBeat: Int, beats: Int): AudioBuffer
    /** Ratio actually used per master beat (for RenderReport / rateTrackingErr). */
    val ratioTrace: DoubleArray
}

object DeckGain { fun of(analysis: TrackAnalysis, prefs: TransitionPrefs): Float /* dB */ }

enum class FadeLaw { LINEAR, EQUAL_POWER, S_CURVE, EXP }
/** Rule: EQUAL_POWER between two different tracks (always uncorrelated at sample level, beat-matched or not); LINEAR only for coherent material (a signal into a processed copy of itself, loop repeats, seams). */
object CrossfadeLaw { fun gains(x: Double, law: FadeLaw): Pair<Float, Float> }

/** Frame-domain gain/parameter automation used by strategies; converted to AutomationLane (seconds) for the plan. */
class Lane(val id: String) { fun add(frame: Long, value: Double, shape: FadeLaw = FadeLaw.LINEAR): Lane; fun valueAt(frame: Long): Double; fun toAutomationLane(sampleRate: Int): AutomationLane }
```

### 2.6 Live transitions (`dev.muisc.transitions.live`, new, frozen)

```kotlin
package dev.muisc.transitions.live

@Serializable data class LivePoint(val frame: Int, val value: Float, val shape: FadeLaw = FadeLaw.LINEAR)

/** Real-time-safe effect nodes. Every node is O(1) per sample, allocation-free after construction, no seeks. */
@Serializable sealed interface LiveNode {
    @Serializable data class Gain(val deck: Deck, val points: List<LivePoint>) : LiveNode
    /** Variable-rate resample of B (|ratio-1| ≤ 0.02) ramping to 1.0 by `settleFrame` so the seam is at ratio 1.0. */
    @Serializable data class Rate(val ratio: Double, val settleFrame: Int) : LiveNode
    /** LR4 low band (< splitHz) of A fades out and B's fades in over swapFrames starting at atFrame. */
    @Serializable data class LowSwap(val atFrame: Int, val swapFrames: Int, val splitHz: Double = 200.0) : LiveNode
    @Serializable data class Sweep(val deck: Deck, val highPass: Boolean, val fromHz: Double, val toHz: Double, val q: Double, val fromFrame: Int, val toFrame: Int) : LiveNode
    /** Feedback delay on A's dry signal after cutFrame; tail contained within the segment. */
    @Serializable data class Echo(val cutFrame: Int, val delayFrames: Int, val feedback: Float, val dampHz: Double) : LiveNode
}
enum class Deck { A, B }

@Serializable data class LivePlan(
    val kind: String,                       // "crossfade" | "phraseCut" | "bassSwap" | "filterSweep" | "echoOut"
    val aFromFrame: Long, val aToFrame: Long, val bFromFrame: Long,
    val outputFrames: Int, val nodes: List<LiveNode>,
) { /** B body resumes at bFromFrame + framesConsumedFromB(outputFrames, Rate node) */ fun bExitFrame(): Long }

/** Builds a LivePlan from analyses only; always succeeds (crossfade needs nothing). */
interface LivePlanFactory { fun plan(a: TrackRef, b: TrackRef, f: PairFeatures?, aNowFrame: Long, prefs: TransitionPrefs, fadeSecOverride: Double? = null): LivePlan }
```

Additions to committed `Segment` (recorded in CHANGELOG, §2.10):

```kotlin
sealed interface Segment {
    data class Body(...)           // unchanged
    data class Rendered(...)       // unchanged
    data class LiveCrossfade(...)  // unchanged (kept as the zero-analysis fallback)
    /** NEW: a real-time DJ move executed by LiveExecutor; graph is pre-built off the audio thread. */
    data class Live(val from: TrackRef, val to: TrackRef, val plan: LivePlan) : Segment
}
```

### 2.7 Player (`dev.muisc.player`, new, frozen)

```kotlin
package dev.muisc.player

interface AudioSink : AutoCloseable {          // Android: AudioTrack; JVM: WAV file or javax.sound
    val sampleRate: Int; val channels: Int
    fun write(interleaved: FloatArray, frames: Int)   // blocking; this is the clock
    fun latencyFrames(): Int; fun pause(); fun flush(); fun resume()
}
interface Clock { fun nowNanos(): Long }

data class EngineLimits(val maxRenderedSec: Double, val maxRenderedAlive: Int, val ringSec: Double, val blockFrames: Int, val seamFadeFrames: Int, val inexactSeamFadeFrames: Int) {
    companion object {
        val DESKTOP = EngineLimits(90.0, 3, 4.0, 1024, 240, 960)
        val PHONE = EngineLimits(48.0, 2, 2.0, 1024, 240, 960)
        val LOW_RAM = EngineLimits(20.0, 1, 1.5, 1024, 240, 960)
    }
}

sealed interface EngineCommand { data object Play; data object Pause; data class Seek(val frame: Long); data class ReplaceTail(val fromSegmentIndex: Int, val segments: List<Segment>, val prebuilt: Map<Int, LiveGraph>); data object Skip; data class SetProgram(val program: PlaybackProgram) }

/** Walks a PlaybackProgram sample-accurately. Called only from the audio thread; commands arrive via an SPSC queue and apply at block boundaries. Allocation-free after `prepare`. */
class ProgramPlayer(val sampleRate: Int, val channels: Int, val limits: EngineLimits, val streams: EngineStreamFactory, val prefs: TransitionPrefs) {
    fun submit(cmd: EngineCommand)
    fun render(dst: Array<FloatArray>, frames: Int): Int        // 0 at end of program
    val position: ProgramPosition                                // segmentIndex, frameInSegment, nowPlaying(TrackRef), trackFrame
    val events: EventQueue<PlayerEvent>                          // SegmentStarted, TrackChanged, Underrun, Ended (drained by the coordinator thread)
}
class SeamFader(val fadeFrames: Int)                              // LINEAR law, keeps the last fadeFrames of the previous segment
class LiveGraph(plan: LivePlan, sampleRate: Int, channels: Int)   // built off-thread; process(aBlock, bBlock, out, n)

/** Plan → render → install state machine (coordinator thread, not the audio thread). */
class TransitionCoordinator(val planner: TransitionPlanner, val liveFactory: LivePlanFactory, val renderer: TransitionRenderer, val player: ProgramPlayer, val analyses: AnalysisService, val metrics: RenderGate, val limits: EngineLimits, val clock: Clock, val scope: CoroutineScope) {
    fun onQueue(context: PlaybackContext, items: List<TrackRef>, currentIndex: Int)
    fun onPlayerEvent(e: PlayerEvent)
    fun onUserSkip(); fun onUserSeek(frame: Long); fun onTrimMemory(level: Int); fun setPowerMode(mode: PowerMode)
    val state: StateFlow<CoordinatorState>                       // for the queue badges: GATED(reason) | PLANNED(strategy, score) | RENDERING(progress) | READY | LIVE(kind, reason) | FAILED(reason)
}
enum class PowerMode { NORMAL, SAVER, STRICT_SAVER }
interface AnalysisService { suspend fun analysis(track: AudioSourceId, urgent: Boolean): TrackAnalysis }
interface RenderGate { fun accept(rendered: RenderedTransition, input: TransitionInput): Boolean } // runs ArtifactMetrics.installChecks
```

### 2.8 Metrics (`dev.muisc.metrics`, new, frozen)

```kotlin
package dev.muisc.metrics

enum class Verdict { PASS, WARN, FAIL }
data class Metric(val id: String, val value: Double, val unit: String, val verdict: Verdict, val warnAt: Double, val failAt: Double)
data class MetricsReport(val metrics: List<Metric>) { val worst: Verdict; fun value(id: String): Double?; fun toJson(): String; fun toCsvRow(): String }

object ArtifactMetrics {
    /** Full set (tests, CLI `check`, Lab). `sources` enables bassCancellation / seamIdentity / beatAlignment. */
    fun evaluate(rendered: RenderedTransition, input: TransitionInput?, aGrid: MasterGrid? = null): MetricsReport
    /** Cheap subset run on the phone before a render is installed: clicks, levelJump, truePeak, nanInf, silenceGap, seamIdentity. */
    fun installChecks(rendered: RenderedTransition, input: TransitionInput): MetricsReport
    fun evaluateProgramOutput(pcm: AudioBuffer, seams: List<Long>): MetricsReport   // ProgramPlayer tests
}
data class GoldenFingerprint(val planJson: String, val rmsEnvelope20ms: FloatArray, val bandEnvelopes: Array<FloatArray>, val logSpec64Per250ms: Array<FloatArray>, val pcm16Sha256: String, val metrics: MetricsReport)
```

### 2.9 Gating rule (`TransitionGating`, committed) and its extension

The committed rule stands: `enabled` master switch; `ALBUM` → no transition unless `allowInAlbums` or different
album; `SHUFFLE` → transition unless `keepAlbumFlowInShuffle && sameAlbum`; `PLAYLIST`/`QUEUE` → yes; `SINGLE` →
no. `PlaybackContext` is a property of the queue, set when it is built. Two additive refinements live in the
coordinator (not in the frozen object), each returning a reason string shown as a queue badge:

- **Segue protection**: never transition when A.outro == `HARD_STOP` and A's trailing silence < 50 ms and B is the
  next track of the same album (hidden tracks, live albums), in any context.
- **Consecutive album pair inside a playlist**: skipped when `keepAlbumFlowInShuffle` is set (the same pref is
  reused for playlists; label in Settings: "Keep album flow for consecutive album tracks").

### 2.10 Permitted edits to committed files (CHANGELOG.md entries, all in WP0)

1. `PlaybackProgram.kt`: add `Segment.Live` and its branch in `totalFrames`.
2. `TrackAnalyzer.kt`: add the `analyze(stream: PcmStream, …)` default method.
3. `TransitionStrategy.kt` — `RenderReport`: add `val renderKey: String = ""`, `val metrics: Map<String, Double> = emptyMap()`, `val ratioTrace: FloatArray = FloatArray(0)` (defaults keep JSON compatibility).
4. `TrackAnalysis.kt`: add `val outroLtasDb: FloatArray = FloatArray(0)`, `val introLtasDb: FloatArray = FloatArray(0)`, `val outroKey: KeyEstimate? = null`, `val introKey: KeyEstimate? = null`, `val tuningCents: Float = 0f`, `val onsetFrames: LongArray = LongArray(0)` (ODF peaks, needed by WSOLA transient pinning), `val textureMagnitude: FloatArray = FloatArray(0)` (per-bin median magnitude of the last 15 s, 513 bins @ 22.05 kHz, §4 TextureCarry). Bump `CURRENT_VERSION` to 2.
5. `Stems.kt`: add `MlStemSeparator`.
6. `Commands.kt`: `allCommands()` populated by WP13.

Any other change to a committed contract requires a one-line "interface change request" in
`engine/CHANGELOG.md` approved by the lead; additive fields with defaults are pre-approved.

---

## 3. Analysis pipeline (`dev.muisc.analysis`)

### 3.1 Front-end (streaming)

`DefaultTrackAnalyzer.analyze(stream)` reads the engine-rate stream in 4096-frame blocks once. Two branches:

- **Engine-rate stereo branch**: BS.1770 loudness (integrated/short-term/true-peak), LTAS (31 third-octave bands via
  a 2048-point real FFT every 4th block, accumulated in dB), silence trim.
- **22 050 Hz mono branch**: mono mix → half-band FIR decimator (44.1 k) or polyphase sinc (48 k) → **STFT-A**
  (Hann 1024, hop 256 = 11.6 ms, 46 ms window) feeding ODF, 40-band log-mel, 4-band bar energy, streaming HPSS;
  **STFT-B** (Hann 4096, hop 1024) feeding chroma only.

Nothing retains a spectrogram. Retained per-frame arrays: ODF (86 Hz), 4 band energies (86 Hz), low-band flux,
chroma (21.5 Hz, 12 floats), HPSS percussive ratio (86 Hz), harmonic-band vocal cue (86 Hz). A 6-minute track costs
about 1.5 MB of feature memory; peak analysis RSS < 12 MB. Cost target: ≤ 2.5× real time on a Cortex-A53 class core
(4-minute track ≤ 10 s), ≤ 0.3× real time on desktop. Honest note: HPSS median filtering (17×17 on 513 bins) is the
dominant cost, not decoding.

### 3.2 Features

| Feature | Algorithm | Parameters / notes |
|---|---|---|
| Silence trim | 10 ms RMS with hysteresis: music starts at the first window > −55 dBFS followed by 200 ms averaging > −50 dBFS; symmetric at the tail. `trimStartFrame/trimEndFrame` in engine frames. Trailing-silence < 50 ms is recorded in `extra["trailingSilenceMs"]`. | −55/−50 dBFS, 200 ms hangover |
| Loudness / true peak | ITU-R BS.1770-4: K-weighting (high-shelf +4 dB @ 1681 Hz, RLB high-pass 38 Hz), 400 ms blocks 75 % overlap, absolute gate −70 LUFS, relative gate −10 LU; short-term 3 s at 100 ms hop; loudness range (EBU R128); true peak by 4× oversampling (BS.1770 Annex 2 polyphase FIR). Validated against the ITU/EBU test vectors (997 Hz sine @ −23 LUFS etc.). | standard |
| Onset detection function | SuperFlux: 40 mel bands 30 Hz–8 kHz from STFT-A, log compression `log(1 + 10·M)`, half-wave-rectified difference against the 3-band max-filtered spectrum 2 frames back, normalised by a running 5 s max. `onsetFrames` = ODF peaks above 0.3 × local (2 s) max with parabolic sub-frame interpolation, converted to engine frames. Low-band flux (20–150 Hz) kept separately for downbeats. | window 46 ms, hop 11.6 ms |
| Tempo | Autocorrelation of the mean-removed ODF over lags 0.25–1.5 s (40–240 BPM) in 8 s windows hopped 2 s; comb weighting at 1×,2×,3×,4× lags for metrical consistency; log-Gaussian prior centred 120 BPM, σ = 0.9 octave (Ellis 2007); global BPM = weighted median of per-window peaks, refined by parabolic interpolation. `alternates` always holds 2× and ½× with their raw scores; `confidence` = peak height / mean ACF. `extra["tempoStability"]` = 1 − CV of window BPMs. | prior 120 BPM, σ 0.9 oct |
| Beat tracking | Ellis 2007 dynamic programming on the unit-normalised ODF: `C(t) = O(t) + max_τ [α·F(τ, τp(t)) + C(t−τ)]`, `F = −(log(τ/τp))²`, α = 680, τ ∈ [τp/2, 2τp]; local period τp(t) from the tempogram (median-smoothed over 6 s) so slow drift is tracked; backtrace from the best end; each beat refined to the ODF maximum within ±20 ms (parabolic). | α 680, refine ±20 ms |
| Grid fitting | Least-squares beat index → frame. Residual RMS < 8 ms → `RIGID` (t0, period; bpm from the fit); else `FLEX` (DP beats, 3-point median). `confidence` = mean ODF at beats / mean ODF, times tempo confidence. Beat-matched strategies are gated at `confidence ≥ 0.5` via `PairFeatures.beatMatchable`. | 8 ms |
| Downbeats | Beat-synchronous features: low-band flux, spectral change (cosine distance of adjacent beat-averaged mel spectra), chroma change, ODF strength. For meter 4 (and 3 as a candidate kept only if ≥ 1.3× better) score each phase φ: `S(φ) = 0.4·lowFlux + 0.3·specChange + 0.2·chromaChange + 0.1·odf` averaged at beats `4k+φ`; `downbeatPhase = argmax`; re-evaluated per structural section so phase flips are caught; downbeat confidence in `extra["downbeatConfidence"]` = (best − second)/best. | weights above |
| Phrases | Foote novelty on beat-synchronous (mel 20 + chroma 12 + bands 4) with a 16-beat checkerboard kernel; peaks quantised to downbeats; `phraseStartBeat` = the downbeat that maximises the 32-beat periodicity of the novelty curve; `phraseBars` = 8 unless the 16-beat period scores > 1.5× higher (then 4). | kernel 16 beats |
| Tuning + chroma | From STFT-B peaks 55 Hz–1.76 kHz: tuning offset = mode of peak deviations from equal temperament in a ±50 cent histogram (`tuningCents`); chroma by harmonic-weighted peak mapping (4 harmonics, weight 0.8ᵏ), 36 bins folded to 12 after applying the tuning correction. | A4 = 440 × 2^(tuningCents/1200) |
| Key | Mean chroma correlated with 24 Temperley key profiles (Krumhansl–Kessler as a `ChoiceSpec` switch); `strength` = r_best, `secondBest`; Camelot via the committed table. Computed for the whole track and for the last 30 s (`outroKey`) and the first 30 s (`introKey`) — the parts that actually overlap. | |
| Bar features | Per bar of the grid: RMS energy (normalised to the loudest bar), sub/bass/mid/high energies (< 60, 60–250, 250–4 000, > 4 000 Hz; the high band tops out at 11 kHz at the analysis rate — documented), percussiveness = HPSS percussive/(H+P), vocal activity (heuristic below). | |
| Vocal activity (no ML) | HPSS harmonic component band-limited 200 Hz–4 kHz, pitch salience by sub-harmonic summation, spectral-flatness gate (< 0.3), and mid/side ratio on stereo (vocals are centre-dominant); combined 0..1 per bar with confidence 0.4 recorded in `extra["vocalConfidence"]`. With ML stems available: vocal stem RMS per bar, confidence 0.95. | |
| HPSS (shared) | Median filtering of STFT-A magnitude (17 frames × 17 bins), soft Wiener masks p = 2, streaming with 8-frame look-ahead. Feeds percussiveness, vocal cue and `PseudoStemSeparator`. | 17 × 17 |
| Spectral balance | `ltasDb` (whole track), `outroLtasDb` (last 30 s), `introLtasDb` (first 30 s): 31 third-octave bands 20 Hz–20 kHz on the engine-rate branch; `brightnessHz` = spectral centroid. `textureMagnitude` = per-bin median magnitude of STFT-A over the last 15 s of music (TextureCarry's noise target). | 31 bands |
| Structure | Beat-synchronous vectors → cosine self-similarity → Foote novelty with 32-beat kernel → boundaries (min 4 bars apart) snapped to bars → agglomerative merge of similar sections (cosine > 0.85) → labels by energy/drums/vocals quantiles (INTRO/OUTRO first/last low-energy sections, BREAKDOWN interior low, DROP after the largest energy jump, else VERSE/CHORUS by repetition count). | |
| Intro/outro type | Outro: `FADE_OUT` if short-term loudness falls ≥ 8 LU monotonically over the last 12 s; `HARD_STOP` if the last 2 s are ≥ median − 3 dB and trailing silence < 200 ms; `BEAT_OUTRO` if percussiveness of the last 8 bars > 0.5; `VOCAL_OUTRO` if vocal activity > 0.5 there; else `AMBIENT_OUTRO`. Intro: `SILENCE` if > 2 s leading silence; `COLD_START` if bar-1 energy ≥ median − 2 dB; `BEAT_INTRO` if percussiveness > 0.5 over the first 8 bars; `VOCAL_INTRO` if vocals > 0.5; else `AMBIENT_INTRO`. | |
| Cues | `mixOutBeat` = last phrase start where drums (percussiveness > 0.4) are still present, else the phrase start ≥ 8 bars before the fade start; `mixInBeat` = first phrase start with drums present (else first downbeat with energy ≥ 0.35); `firstDownbeat`/`lastDownbeat`; `dropBeat` = phrase boundary with the largest positive energy jump. | |

### 3.3 Fingerprint and caching

`fingerprint = "<sizeBytes>:<mtimeMs>:<xxhash64 of first and last 1 MiB>:<decoderId>"`, where `decoderId` is
`"javasound"` on the JVM and the MediaCodec component name (e.g. `c2.android.mp3.decoder`) on Android — different
decoders may emit different priming, so their frame timelines are cached separately. `AnalysisCache.get(fingerprint,
sampleRate, version)` (committed). JVM: `FileAnalysisCache` at `~/.muisc/analysis/<sha1(fingerprint)>-<rate>-v<version>.json.gz`.
Android: Room `track_analysis(fingerprint, sample_rate, version, json BLOB gzip, analysed_at)`; ~6–20 KB per track.
Bumping `CURRENT_VERSION` invalidates lazily; the CLI `analyze --diff` prints old vs new so estimator changes are
visible. Pseudo-stems are never cached (cheap for the ≤ 60 s a transition touches). ML stems are cached as
16-bit WAV per stem under the app cache with a 500 MB LRU (`stem_asset` table).

---

## 4. Transition strategy catalogue

Common substrate used by every strategy (in `dev.muisc.transitions.core`): `MasterGrid`, `PhaseLockedDeck`,
`StretcherSelector`, `DeckGain`, `CrossfadeLaw`, `Lane`, plus `dsp` primitives. Conventions:

- Lengths are in **bars of the master grid** (= A's bars until a glide changes it). `overlapBars` = bars during
  which both decks are audible. Defaults scale from `prefs.preferredOverlapBars` (16) and `prefs.energy`.
- Default cut/entry points: A's exit region starts at `a.grid.previousPhraseStart(mixOutBeat)`; B enters at
  `b.grid.frameOfBeat(mixInBeat)` (a downbeat), with `entryOffsetBars` as a common param.
- Every strategy applies `DeckGain` to both decks, ends with the true-peak limiter at −1 dBTP (bypassed when the
  segment never exceeds −1 dBTP, so bit-identity at the seams holds), and pads its `aWindow`/`bWindow` by
  `2048 + resampler taps` frames so guard regions are exact.
- Crossfade law: `EQUAL_POWER` between the two tracks; `LINEAR` only for coherent material (dry → wet of the same
  deck, loop repeats). Any gain lane that reaches −∞ does so over ≥ 5 ms.
- Effect tails (delay, reverb) are contained inside the segment: the plan extends `expectedOutputFrames`, it never
  lets a tail spill into B's body.
- Every strategy records its main automations as `AutomationLane`s and `Marker`s; the Lab plots them for free.

Analysis features needed are listed per strategy as **Needs**. Failure modes are honest.

#### 1. `crossfade` — Equal-power crossfade (floor)
- **When**: always applicable; floor score 0.05. Live-capable (`LivePlan.kind = "crossfade"`).
- **DSP**: A's last `fadeSec` before `trimEndFrame` and B from `mixInBeat` (or `trimStartFrame`), equal-power
  gains, deck gains, limiter.
- **Params**: `fadeSec` 4 (1–12), `bStartAtMixIn` true.
- **Needs**: trim, loudness (cues optional).
- **Fails**: vocals over vocals; beat clash if both are rhythmic — hence the floor score.

#### 2. `outroIntroMinimal` — Respect the producers' outro/intro
- **When**: A `FADE_OUT`/`AMBIENT_OUTRO`, B `AMBIENT_INTRO`/`VOCAL_INTRO`/`SILENCE`. Live-capable (gain lanes only).
- **DSP**: overlap 2–6 s timed so B's first downbeat lands `holdAfterLufs` after A's short-term loudness crosses
  −30 LUFS; B enters with a 1-bar fade; no stretch, no EQ.
- **Params**: `overlapSec` 4 (1–8), `thresholdLufs` −30, `bFadeBars` 1.
- **Needs**: loudness short-term curve, edge types, cues.
- **Fails**: a long fade-out with a loud cold intro sounds like a gap; planner steers those to `phraseCut`.

#### 3. `phraseCut` — Phrase-aligned hard cut
- **When**: B `COLD_START`/`BEAT_INTRO`, or tempos incompatible; grid confidence ≥ 0.5 on A (B optional). Live-capable.
- **DSP**: cut A at a phrase start ≥ `mixOutBeat`, 16 ms equal-power micro-blend into B's `mixInBeat` downbeat;
  optional FDN reverb tail of A (`tailMs`) mixed under B's first bar; optional 1-beat pre-cut duck.
- **Params**: `tailMs` 400 (0–1500), `cutOnPhrase` true, `preDuckDb` 0.
- **Needs**: grid, phrases, cues, edge types.
- **Fails**: wrong downbeat phase → cut on beat 3 (mitigated by `downbeatConfidence` weight).

#### 4. `beatMatchedBlend` — Classic phrase-aligned blend
- **When**: `beatMatchable`, `stretchPercent ≤ prefs.maxStretchPercent`, room ≥ 8 bars each side.
- **DSP**: master grid at A's BPM (or glide via modifier); A and B `PhaseLockedDeck`s (B's `mixInBeat` on master
  beat 0 of the overlap); equal-power blend over `overlapBars` with a 3-band EQ lane (B's lows −12 dB until
  `bassInBar`, A's highs −6 dB from the midpoint); vocal-aware ducking of A when B's vocal activity rises.
- **Params**: `overlapBars` 16 (8–32), `bassInBar` 8, `eqDepthDb` 12, `vocalDuckDb` 6, `law` EQUAL_POWER.
- **Needs**: grid (both), cues, bar features (bass/vocals), loudness, onsets.
- **Fails**: WSOLA smearing above ~6 % stretch; out-of-phase kicks if downbeats are wrong (`bassCancellation` metric).

#### 5. `bassSwap` — EQ mix with low-band handover
- **When**: as 4; also live-capable when `|tempoRatio−1| ≤ 0.02` (rate node, no WSOLA).
- **DSP**: LR4 3-band split (200 Hz / 4 kHz, all-pass-compensated); B enters with lows cut; at `swapBar` downbeat
  A's low band fades out and B's fades in over `swapBeats`; mids/highs equal-power over the remaining bars.
- **Params**: `overlapBars` 16, `swapBar` 8, `swapBeats` 1 (1–4), `lowHz` 200, `highHz` 4000.
- **Needs**: grid, cues, low-end share, loudness.
- **Fails**: two heavy sub-bass tracks at the swap beat can still overlap for one beat — the swap is deliberately
  short; wrong phase → swap on a weak beat.

#### 6. `stemSwap` — Stem-by-stem handover
- **When**: beat-matchable; stems `BOTH`; scores higher with `ML` quality (× 1.0) than `PSEUDO` (× 0.8).
- **DSP**: over `overlapBars`: B's drums replace A's drums first (`drumsSwapBar`, 1-beat crossfade of the drum
  stems), then bass swap (`bassSwapBar`, as strategy 5 on the bass stems), then A's `other` and vocals duck out as
  B's come in (`vocalCrossBars`); A's vocal is ducked whenever B's vocal activity > 0.5.
- **Params**: `overlapBars` 24, `drumsSwapBar` 4, `bassSwapBar` 12, `vocalCrossBars` 8, `duckDb` 9.
- **Needs**: grid, stems, bar features (drums/vocals), cues.
- **Fails**: pseudo-stems leak — with `PSEUDO` this sounds like a staggered 3-band EQ mix, and it is scored as such.

#### 7. `drumBreakBridge` — Drums-only bridge
- **When**: beat-matchable; A `BEAT_OUTRO` or B `BEAT_INTRO`; stems available (`PSEUDO` OK because drums are the
  cleanest pseudo-stem).
- **DSP**: A's non-drum stems fade over `breakBars`, leaving A's drums alone for `soloBars`; B's drums enter
  phase-locked and take over (1-bar crossfade); then B's other stems fade in from `mixInBeat`. Optional HPF ramp on
  A's drums during the solo.
- **Params**: `breakBars` 4, `soloBars` 4, `bFillBars` 4, `hpfToHz` 400.
- **Needs**: grid, stems, cues.
- **Fails**: harmonic residue in the drum stem; when both tracks are drum-light the "break" is thin — gated on
  percussiveness > 0.5.

#### 8. `filterSweep` — Resonant sweep out / open in
- **When**: any pair with a usable grid on A; tempos need not match (B enters at a phrase start, unstretched unless
  ≤ 2 %). Live-capable.
- **DSP**: SVF high-pass on A sweeps 20 Hz → `hpfToHz` over `sweepBars` (exponential cutoff ramp, Q `resonance`),
  while B's low-pass opens 300 Hz → 20 kHz over the same bars; equal-power gains; B lands on the downbeat at the
  sweep end.
- **Params**: `sweepBars` 8 (2–16), `hpfToHz` 4000, `resonance` 2.0 (0.7–6), `bLpfFromHz` 300.
- **Needs**: grid A, cues, phrases.
- **Fails**: high resonance + loud lows can overshoot (limiter catches; Q clamped at 6); masks key clashes but not
  rhythmic clashes when tempos are far apart.

#### 9. `echoOut` — Beat-synced echo tail into B
- **When**: A has a grid; works for any tempo relation (B is unstretched). Live-capable.
- **DSP**: at A's last phrase end the dry signal is cut; a feedback delay (delay = `delayBeats` × 60/bpm_A —
  choices 0.5, 0.75 (dotted eighth), 1.0 beat; feedback `feedback` 0.6–0.85; one-pole LPF `dampHz` and HPF 120 Hz in
  the loop) rings out for `tailBars`; B enters on the next downbeat under the tail; the tail is contained in the
  segment (its length is computed from the feedback decay to −60 dB).
- **Params**: `delayBeats` 0.75 {0.5, 0.75, 1.0}, `feedback` 0.72, `dampHz` 4000, `tailBars` 2, `bEnterOnBeat` 0.
- **Needs**: grid A, phrases, cues.
- **Fails**: at very low BPM a 1-beat delay sounds like a slap; the planner prefers 0.5 beat above 80 BPM… below.

#### 10. `loopRollRiser` — Loop roll + riser into the drop
- **When**: B `BEAT_INTRO`/`COLD_START` or has a `dropBeat`; A has a grid; tempos may differ (loop is tempo-agnostic,
  B unstretched).
- **DSP**: capture A's last bar into memory; loop it with halving lengths (1, ½, ¼, ⅛, 1/16 bar) over `rollBars`;
  synthesised riser = band-passed shaped noise with cutoff rising 200 Hz → 8 kHz plus a pitch-gliding sine, ADSR;
  HPF ramp on the loop; B lands on the next downbeat (`dropBeat` if within 4 bars of `mixInBeat`) with a 1-beat
  silence gap option (`gapBeats`).
- **Params**: `rollBars` 1 (1–2), `riserDb` −8, `riserCurve` EXP, `gapBeats` 0 (0–1), `hpfToHz` 800.
- **Needs**: grid A, downbeats, cues (B dropBeat), onsets.
- **Fails**: gimmicky when overused — variety penalty and `prefs.energy` gate it; wrong downbeat makes the roll
  start off-grid.

#### 11. `harmonicBlend` — Key-aware long blend with pitch shift
- **When**: beat-matchable, `camelotDistanceAfterShift ≤ 1` with `|bestPitchShiftSemitones| ≤ prefs.maxPitchShiftSemitones`,
  key strengths ≥ 0.6 on both outro/intro keys.
- **DSP**: pitch-shift B's head by `bestPitchShiftSemitones` (resample × WSOLA) ramping back to 0 over the last
  `settleBars` so the seam is unshifted; long equal-power blend (`overlapBars` 24–32) with vocal-aware ducking;
  optional 3-band EQ.
- **Params**: `overlapBars` 24, `maxShift` 1 (0–2), `settleBars` 4, `vocalDuckDb` 6.
- **Needs**: grid, outro/intro keys + strengths, bar features, loudness.
- **Fails**: key detection wrong on modal/atonal tracks → wrong shift (gated on strength; user can zero the key
  weight); the shift-back ramp is audible on sustained pads if `settleBars` is too short.

#### 12. `spectralFreezeBridge` — Freeze A's last chord, B under it
- **When**: A `AMBIENT_OUTRO`/`VOCAL_OUTRO` or `HARD_STOP` with a sustained last chord (harmonic energy high,
  onset density < 0.5/s in the last 2 s); any tempo relation.
- **DSP**: STFT spectral freeze (Paulstretch-style: hold magnitude at `freezeBeat`, random per-bin phase advance)
  of A's last 400 ms, held for `holdBeats` of B's grid with an LPF darkening lane; B enters at `mixInBeat` under
  the freeze; freeze released with `releaseBars` as B's band energy rises.
- **Params**: `holdBeats` 8, `releaseBars` 2, `darkenToHz` 2000, `freezeDb` −6.
- **Needs**: edge types, bar features (harmonic energy), B grid/cues.
- **Fails**: a noisy or percussive last moment freezes into hiss (gated on harmonic share > 0.6).

#### 13. `ambientBridge` — Generated bridge for incompatible pairs
- **When**: always applicable (no tempo/key gate); floor score 0.15, rising to 0.6 when `stretchPercent > 12` or
  `camelotDistanceAfterShift ≥ 3` or A `HARD_STOP` into B `AMBIENT_INTRO`.
- **DSP**: A's last chord into an FDN reverb freeze (feedback → 1.0, damping bypassed, input muted at the freeze
  point) + TextureCarry bed + a low pad (sine + PolyBLEP saw stack, ADSR) on a pitch class common to A's outro key
  and B's intro key (else B's tonic), `bridgeSec` 4–16 s; B enters at `mixInBeat` with its own intro under a 2-bar
  fade; no stretch, no beat-matching.
- **Params**: `bridgeSec` 8 (3–16), `padDb` −18, `padOctave` 2, `reverbSize` 0.8, `textureDb` −20.
- **Needs**: outro/intro keys, LTAS (texture), cues, edge types.
- **Fails**: wrong key → wrong pad note (pad is omitted when both key strengths < 0.5); this is the honest "musical
  floor", not a great transition.

#### 14. `brakeStop` — Vinyl brake into a cold open
- **When**: B `COLD_START`/`BEAT_INTRO`, `prefs.energy ≥ 0.5`, at most once per `cooldown` transitions; any tempo.
- **DSP**: variable-rate resample of A with ratio ramping 1 → 0 over `brakeSec` (exponential), LPF tracking the
  rate; B starts on `mixInBeat` at the frame the brake reaches −60 dBFS (+ `gapMs`); optional spinback variant
  (ratio → −1 over 0.5 s).
- **Params**: `brakeSec` 1.5 (0.8–3), `gapMs` 120, `spinback` false, `cooldown` 6.
- **Needs**: cues B, edge types; nothing from A's grid.
- **Fails**: fatigue when frequent (cooldown enforced); silly on ballads (gated on A percussiveness > 0.4).

#### Modifiers (`dev.muisc.transitions.modifiers`, `TransitionModifier`)

**`tempoGlide`** (the user's "slowly change tempo"). `applicability` > 0 when the base strategy is beat-domain (4, 5,
6, 7, 11) and `0.5 % < stretchPercent ≤ prefs.maxStretchPercent × 2` (a glide tolerates more total change because
each beat only moves a little). `adjustPlan` writes `grid.mode=glide`, `grid.glideBars`, `grid.curve`, `grid.holdBars`
into `plan.params` and extends both windows; `MasterGrid.fromPlan` builds the glide grid; the base strategy's
`PhaseLockedDeck`s follow it, so B is pinned to the grid throughout and ends at ratio 1.0 (`holdBars` ≥ 2 at B's
tempo before the seam). `apply` only adds the `masterBpm` lane and a marker. Params: `glideBars` 16 (8–64),
`curve` S_CURVE, `holdBars` 2, `mode` {keyLock, vinyl}. Failure: WSOLA phasiness on sustained material when the
instantaneous ratio exceeds ~8 %; the planner caps total change at 16 % and prefers longer glides for bigger changes.
Segment length scales with the glide (a 32-bar glide at 100 BPM is 77 s) — `EngineLimits.maxRenderedSec` caps
`glideBars` per device class instead of a global constant.

**`textureCarry`** (the user's "carry over a noise/texture"). `applicability` rises with A's texture level (median
magnitude energy of `textureMagnitude` relative to A's tail RMS) and with `spectralSimilarity < 0.8` (the bed
glues dissimilar spectra). `apply` synthesises the bed from `a.textureMagnitude` (mode `NOISE`: seeded white noise
→ STFT → multiply by the median-magnitude target → ISTFT; mode `FREEZE`: spectral freeze of A's last 400 ms;
mode `OTHER`: the `other` pseudo-stem granulated with 120–300 ms Tukey grains, 6 voices), optionally morphs its
third-octave envelope from `a.outroLtasDb` toward `b.introLtasDb` over the segment, fades it up over A's last
`riseBars`, holds it through the seam region, and releases it with `g(t) = clamp(1 − E_B(t)/E_ref, 0, 1)` where
`E_B(t)` is B's measured per-bar band energy (sub+bass+mid) — the bed literally drops off as the new song picks up.
Params: `mode` NOISE, `textureDb` −18, `riseBars` 4, `releaseCurve` EXP, `morphToB` true, `darkenHz` 0. Failure:
on very clean/quiet outros the bed is audible as added hiss (gated on texture level > −45 dBFS).

Composition: the planner may attach both modifiers; catalogue coverage is 14 strategies × {glide} × {texture}.

---

## 5. Planner (`dev.muisc.transitions.planner`)

### 5.1 PairFeatures (committed data class; `DefaultPairAnalyzer` computes it)

```
r0 = B.bpm / A.bpm
candidates: SAME r0, DOUBLE 2·r0 (B in double time), HALF r0/2 — choose the one minimising |ln r|; tempoRatio = r,
tempoRelation; stretchPercent = 100·|ln r|   (symmetric measure)
camelotDistance = A.outroKey.camelot.distanceTo(B.introKey.camelot)   (whole-track keys if section keys are null)
bestPitchShiftSemitones = argmin_{s ∈ [-S..S]} distance(A.outroKey, shift(B.introKey, s)), ties → 0, S = ceil(prefs.maxPitchShiftSemitones)
   (shifting B by +1 semitone moves its Camelot NUMBER by +7 mod 12; letter unchanged)
camelotDistanceAfterShift = distance at bestPitchShiftSemitones
loudnessDeltaLu = B.integratedLufs − A.integratedLufs
energyDelta = mean(B.bars.energy[head 8 bars]) − mean(A.bars.energy[tail 8 bars])                    (−1..1)
vocalClash = max over (i in A tail 8 bars, j in B head 8 bars) of vocalActivity_A[i] · vocalActivity_B[j]
spectralSimilarity = cosine(A.outroLtasDb − mean, B.introLtasDb − mean)
outroBeatsAvailable = lastBeat(A) − mixOutBeat(A) (or beats between trimEnd−16 bars and trimEnd if mixOut = −1)
introBeatsAvailable = beats from mixInBeat(B) to the end of B's first section (≥ 8 bars when unknown)
gridConfidenceA/B, keyStrengthA/B (outro/intro key strengths), lowEndShareA/B = (sub+bass)/energy over tail/head bars
```

### 5.2 Compatibility sub-scores (all 0..1)

```
s_tempo  = exp(−(stretchPercent / σ_t)²),  σ_t = prefs.maxStretchPercent / 1.5      (HALF/DOUBLE: × 0.85)
s_key    = {0: 1.0, 1: 0.85, 2: 0.5, 3: 0.35, ≥4: 0.1}[camelotDistanceAfterShift]  blended toward 0.6 by (1 − min(keyStrengthA, keyStrengthB))
s_energy = exp(−(loudnessDeltaLu / 6)²) · (1 − 0.5·|energyDelta|)
s_vocal  = 1 − vocalClash
s_grid   = min(gridConfidenceA, gridConfidenceB)                                     (0..1)
s_struct = table_s[outro][intro]   (a 6×6 OutroType×IntroType prior per strategy, e.g. beatMatchedBlend[BEAT_OUTRO][BEAT_INTRO]=1.0,
                                     phraseCut[*][COLD_START]=1.0, outroIntroMinimal[FADE_OUT][AMBIENT_INTRO]=1.0, ambientBridge[HARD_STOP][AMBIENT_INTRO]=0.9)
s_room   = min(1, outroBeatsAvailable / needed_s) · min(1, introBeatsAvailable / needed_s)
s_stems  = 1.0 (ML) | 0.8 (PSEUDO) | 0 (needs stems, none)                             (only for stem strategies)
```

Each strategy's `applicability()` returns `fit_s = Σ_i w_{s,i}·s_i` with its own weight vector (`Σ w = 1`) and hard
gates as `blockers` (e.g. `beatMatchedBlend`: `beatMatchable && stretchPercent ≤ maxStretchPercent && s_room ≥ 0.5`).
Floors: `crossfade` returns `max(fit, 0.05)` and is never blocked; `ambientBridge` returns `max(fit, 0.15)` and has no
tempo/key gate.

### 5.3 Final score and ranking (`DefaultTransitionPlanner`)

```
score_s = fit_s · prefs.strategyWeights[s] (default 1) · energyPref_s · variety_s · modifierBonus_s · jitter
energyPref_s = 1 − |prefs.energy − ambition_s| · 0.5          (ambition ∈ [0,1] per strategy: crossfade 0.1 … loopRollRiser/brakeStop 0.9)
variety_s    = 1 − prefs.varietyPenalty · [s == previousStrategyId]   (brakeStop/loopRollRiser: also 0 when used within `cooldown`)
modifierBonus= 1 + 0.15·applicability(tempoGlide) + 0.10·applicability(textureCarry)   (modifiers with applicability > 0.3 are attached)
jitter       = 1 + 0.05·uniform(−1,1; seed = hash(a.fingerprint, b.fingerprint, seed))  (same pair → same plan unless reshuffled)
disabledStrategies are removed (crossfade cannot be disabled). No minimum-score cut-off exists: the ranking is the ladder.
```

`RankedPlans` contains every strategy with `applicability.applicable`, best first, each with its `plan()` already
computed; `Planner` guarantees ≥ 2 candidates (crossfade + ambientBridge always qualify).

### 5.4 Escalation ladder (guarantee)

Beat-domain family (4, 5, 6, 7, 11) → tempo-agnostic beat-aware (8, 9, 10, 14, 3) → structural (2, 12) →
`ambientBridge` (13) → `crossfade` (1). The ladder is realised by the gates and floors above and by the
coordinator: if a candidate fails to render (exception, `RenderGate` rejection, deadline, cancellation) the
coordinator moves to the next candidate with `excluded += id`; the terminal candidates need no render
(`ambientBridge` is cheap; `crossfade` is a `LivePlan`). `PlannerPropertyTest.everyPairPlans` (random analyses,
n = 500) asserts ≥ 2 candidates, crossfade always present, no beat-domain strategy when `stretchPercent > maxStretch`
or `!beatMatchable`, and determinism per seed. The honest framing shown in the Lab: "a click-free transition always
exists; for incompatible pairs it is an echo-out, an ambient bridge or a crossfade".

`LivePlanFactory` mirrors the ladder for the live path: `bassSwap` (if `|r−1| ≤ 0.02` and beatMatchable) →
`filterSweep`/`echoOut` (grid on A) → `phraseCut` (grid + cold B) → `crossfade` (always).

---

## 6. DSP primitives (`dev.muisc.dsp`)

All primitives are stateful objects with `reset()` and `process(in, out, n)` (or pure functions on
`FloatArray`s), allocation-free after construction, seeded randomness only (`Xoshiro128`), table generation via
`StrictMath` (twiddles, windows, sinc kernels, profiles) so tables are bit-identical on HotSpot and ART.

| Package | Primitive | Choice |
|---|---|---|
| `fft` (in flight) | `Fft`, `RealFft`, `Stft`, `Istft`, `Spectrogram`, `StftFrameSink` | Iterative radix-2 with precomputed twiddles/bit-reversal, real FFT via N/2 packing, sizes 256–65 536; streaming STFT with sink callback; ISTFT with √Hann at 75 % overlap, COLA-normalised |
| `window` (in flight) | `Window` | Hann, √Hann, Hamming, Blackman–Harris 4-term, Kaiser(β), Tukey(α) |
| `mel` (in flight) | `MelFilterbank` | Triangular bands, log compression |
| `filter` (in flight) | `Biquad`/`BiquadFilter`/`BiquadCascade`, `LinkwitzRileyCrossover`, `MultibandCrossover`, `OnePole`, `StateVariableFilter` | RBJ cookbook, TDF-II, coefficient interpolation per 64-sample block; LR4 = 2 × Butterworth-2 (Q = 1/√2) per band, 3-way with all-pass-compensated low band so the sum is magnitude-flat (tested to −80 dB); SVF = Zavalishin TPT with exponential cutoff ramps, Q ≤ 6 |
| `resample` (in flight) | `SincKernel(taps=32, phases=512, β=9)`, `Resampler`, `StreamingResampler`, `VariableRateResampler` | Polyphase windowed sinc; **512 phases with linear inter-phase interpolation** (the 64/128-phase default must be raised: interpolation error ≈ −90 dB needs ≥ 512 phases at 32 taps); `StreamingResampler.alignTo(outputFrame)` primes history deterministically; variable-rate version takes a per-block ratio ramp (vinyl stretch, brake, pitch shift) |
| `gain` (in flight) | `Curves` (LINEAR, EQUAL_POWER, S_CURVE, EXP), `GainRamp` | Per-sample linear ramps between block-evaluated points; no transcendental calls per sample |
| `stretch` | `TimeStretcher` interface, `WsolaStretcher`, `PhaseVocoderStretcher`, `PitchShifter` | WSOLA: frame 30 ms, synthesis hop 15 ms, ±10 ms search by normalised cross-correlation on a 4:1 decimated envelope refined at full rate; **transient pinning**: when an `onsetFrames` entry falls inside the next frame, search radius → 0 and the frame boundary snaps so the onset is copied once; stereo uses one lag from the mid signal; ratio 0.75–1.33 (planner refuses beyond). Phase vocoder (identity phase locking, Laroche & Dolson 1999) behind `PHASE_VOCODER` for sustained pads (AmbientBridge). PitchShifter = resample 2^(−s/12) ∘ WSOLA 2^(s/12), ±3 semitones |
| `hpss` | `Hpss` | Median filtering 17 × 17 of STFT magnitude, soft Wiener masks p = 2, streaming with 8-frame look-ahead; optional 3-component HRP (Driedger 2014, β = 2) exposing a true residual |
| `stems` | `PseudoStemSeparator` | BASS = LR4 low band (< 150 Hz); DRUMS = percussive part (HPSS) of the mid+high band; VOCALS = centre-extracted (mid − side-weighted) harmonic part 200 Hz–4 kHz × 0.5 (confidence-weighted, may be near-silent); OTHER = original − (drums+bass+vocals) so the invariant always holds |
| `loudness` | `Bs1770Meter`, `TruePeakMeter` | K-weighting biquads, gated integration, short-term/momentary, LRA, 4× oversampled true peak |
| `dynamics` | `TruePeakLimiter` | Look-ahead brick-wall: **5 ms look-ahead**, attack computed from the peak within the look-ahead window (no intermodulation on sub-bass), 80 ms release, 1 dB soft knee, ceiling −1 dBTP, stereo-linked, 4× oversampled detector; bypass flag reports whether any gain reduction occurred |
| `fx` | `Delay`, `FdnReverb`, `SpectralFreeze`, `Granulator` | Fractional delay (Lagrange 3rd order), feedback loop with one-pole LPF + HPF, ping-pong option; **Jot 8-line FDN**, Hadamard mixing, prime delay lengths (1021…4093 × fs/48k), per-line one-pole damping, pre-delay, `freeze()` = feedback 1.0 + damping bypass + input mute (stable, non-metallic); spectral freeze = hold magnitude + random-walk phase per bin on an 8k FFT; granulator 120–300 ms Tukey grains, 4–8 voices, seeded jitter |
| `noise` | `ShapedNoise`, `BiquadBankNoise` | Seeded white → STFT → multiply by a target magnitude (31-band LTAS interpolated to bins, or `textureMagnitude` directly) → ISTFT; live variant = 10 peaking biquads at octave centres |
| `synth` | `Oscillators`, `Adsr` | Sine, PolyBLEP saw, ADSR (riser, pad) |
| `qa` (in flight) | `ArtifactDetector` | Click / level-jump / clipping / NaN / DC / silence-gap detection on a buffer (used by `metrics` and by `RenderReport.warnings`) |
| `util` | `Xoshiro128`, `Autocorrelation` (FFT-based), `MedianFilter`, `DcBlock`, `Db`, `Interp`, `Mix` | |

Property tests per primitive (§9): LR4 sum flat ±0.05 dB; resampler round-trip SNR ≥ 90 dB and alias rejection
≥ 80 dB; alignment determinism (start anywhere, identical samples); WSOLA click train keeps every click within ±2 ms
at pinned onsets and output length = ratio × input ± 1 frame; limiter never exceeds the ceiling on the oversampled
detector; STFT/ISTFT identity; FDN energy bounded for feedback ≤ 1; BS.1770 within 0.1 LU of the test vectors;
HPSS separates click + tone with > 15 dB rejection.

---

## 7. Real-time engine on Android (`dev.muisc.player` + `dev.muisc.app.playback`)

### 7.1 Decoding
- `MediaCodecPcmStream : PcmStream` (`app/playback/MediaCodecPcmStream.kt`): `MediaExtractor` + `MediaCodec` in
  synchronous mode on a dedicated decoder thread per stream instance; output converted to planar float
  (`ENCODING_PCM_FLOAT` when the codec offers it, else int16/24 → float). Frame positions count PCM frames the
  decoder emits (decoder-relative). `decoderId` = codec name.
- **Gapless**: our own parser reads LAME/Xing (MP3) and iTunSMPB (AAC) tags → `GaplessInfo`; if `MediaFormat`
  carries `encoder-delay`/`encoder-padding` (AOSP decoders trim themselves) the decoder is trusted and our trim is
  skipped (a decode-once check on first use verifies whether leading frames are already trimmed). FLAC/WAV/OGG need
  no trim.
- **Exact positioning**: `seek(frame)` is exact for FLAC/WAV/OGG (seek tables / granule positions) via
  `SEEK_TO_PREVIOUS_SYNC` + counted discard. For MP3/AAC, extractor sample times are not frame-exact across OEM
  decoders, so `seek` never trusts them for seam-critical positions: it **decodes from frame 0 and discards** when
  `frame < position` or when the stream is opened by the renderer/analyser (50–100× real time: 90 s ≈ 1 s on a
  little core, started ≥ 30 s ahead). User seeks in the body use extractor seek + timestamp estimate and mark the
  stream `inexact`; a seam following an inexact stream gets the 20 ms seam fade (`inexactSeamFadeFrames`) instead
  of 5 ms. A decoded twice is bit-identical on the same codec, which is what the splice contract needs.
- Two `PcmStream` instances per track when needed: the body deck (sequential, ring-buffered) and the renderer/
  analyser (`TrackAudioLoader.load`), never shared. Each is wrapped in `ResamplingPcmStream` to the engine rate.
- JVM side (`engine:audio`): WAV/AIFF natively, MP3/FLAC/OGG via the committed SPI providers, AAC via a JAAD-based
  SPI (`com.tianscar.javasound:javasound-aac`, verify availability on Maven Central in WP13; fallback: `ffmpeg`
  pipe when on PATH). These are desktop iteration decoders, never shipped in the APK.

### 7.2 Output and threads
| Thread | Priority | Role |
|---|---|---|
| **Audio** | `THREAD_PRIORITY_URGENT_AUDIO` | `ProgramPlayer.render(block, 1024)` → interleave → `AudioTrack.write` (blocking = the clock). No allocation, no locks; SPSC command queue drained at block boundaries. |
| **Decoder-A/B** | `THREAD_PRIORITY_AUDIO` | One per open body stream: MediaCodec → float → resample → `PcmRing` (SPSC, `EngineLimits.ringSec`), blocks when full. The next track's decoder opens 20 s ahead. |
| **Coordinator/Render** | `THREAD_PRIORITY_DEFAULT + THREAD_PRIORITY_LESS_FAVORABLE` (nice +1: stays in the foreground cgroup, below UI) | `TransitionCoordinator`: analyses (urgent), planning, `TransitionRenderer.render` block-cooperative with cancellation, `RenderGate`, program install. Single-thread coroutine dispatcher. |
| **Batch analysis** | `THREAD_PRIORITY_BACKGROUND` (WorkManager `CoroutineWorker`) | Library pre-analysis under charging/idle constraints. |
| **Main/Session** | normal | `MuiscPlayer`, MediaSession, Compose. |

`AudioTrack`: `ENCODING_PCM_FLOAT`, engine rate = `PROPERTY_OUTPUT_SAMPLE_RATE`, `USAGE_MEDIA`/`CONTENT_TYPE_MUSIC`,
buffer = `max(4 × getMinBufferSize, 120 ms)`; underruns counted (`getUnderrunCount`), three in a minute → buffer
doubled for the session (max 400 ms). Honest latency budget: **pause** is immediate (`AudioTrack.pause(); flush()`),
**skip/seek** become audible after the queued buffer drains: ≤ buffer size + one block ≈ 150–200 ms (≤ 450 ms after
an underrun bump). Not a low-latency app; no Oboe/AAudio.

### 7.3 ProgramPlayer and seams
Per block: (1) apply pending commands; (2) pull from the current segment — `Body` from its ring (deck gain applied),
`Rendered` from the `AudioBuffer`, `Live` through its pre-built `LiveGraph` reading both rings, `LiveCrossfade`
via a `LiveGraph` with two gain nodes; (3) on a segment end inside the block, switch to the next segment and let
`SeamFader` (LINEAR, 240 frames) blend the retained tail of the previous segment with the head of the new one;
(4) master: pause/resume 10 ms ramps, audio-focus duck gain, the true-peak limiter (transparent below −1 dBTP, so
album playback is bit-exact when nothing exceeds it). Position reporting: during `Rendered`/`Live` the reported
track is A until the plan's handover marker (`Marker("B enters")` frame, or the midpoint), then B; a normal
`TrackChanged` event fires there so MediaSession and UI behave as usual.

### 7.4 Transition life-cycle (`TransitionCoordinator`)
- **Plan** as soon as `(current, next)` is known and gating passes: analyses from cache, else `urgent` analysis on
  the coordinator thread (≤ 10 s worst case; the queue's next track is normally known minutes ahead); `Planner.plan`
  (< 5 ms) → state `PLANNED` (queue badge "Next: Bass swap · 0.82").
- **Render** when `remainingA ≤ max(90 s, 4 × estimatedRenderMs)`; typical phone cost 2–6 s (WSOLA strategies),
  8–15 s with pseudo-stems; the render is block-cooperative and cancellable. `RenderGate.accept` runs the install
  checks (clicks, level jumps, true peak, seam identity); rejection → next candidate.
- **Install** atomically via `ReplaceTail`: `Body(A, 0, trimEnd)` → `Body(A, …, aExitFrame) + Rendered + Body(B,
  bEntryFrame, …)`, allowed only while the cursor is < `aExitFrame − 2 s`. **Deadline** = `aExitFrame − 5 s`; a miss
  installs the `LivePlanFactory` result (a real DJ move, or a crossfade) and logs the reason (`transition_log`).
- **Skip while A plays**: if a render is ready and `now < aExitFrame − 8 bars` → "DJ skip": jump A to
  `aExitFrame − 4 bars` (on a downbeat, 1-beat live crossfade inside A) and play the transition; else live
  transition from `now` (fade `prefs`-controlled, 1.5 s default) into B at `mixInBeat`; in-flight render cancelled.
- **Skip during a transition**: abandon the segment; B's body starts at the frame the plan maps the current output
  position to (`ratioTrace` for stretched plans), 20 ms seam; back-skip restarts A at `aExitFrame` (A's ring is
  kept alive 5 s after handover).
- **Seek in A**: 10 ms fade-out, flush ring, `seek`, refill (≤ 150 ms, silence if starved), 10 ms fade-in. Seeking
  past `aExitFrame` drops the render; re-plan if > 20 s remain, else live fallback.
- **Queue change**: cancel render (cooperative), drop `Rendered`, re-plan after a 500 ms debounce. The last rendered
  segment is retained (`maxRenderedAlive`) so A→B→A does not re-render.
- **Pause**: audio thread stops writing; render continues (bounded).
- **Memory**: `EngineLimits.PHONE` by default; `LOW_RAM` when `ActivityManager.isLowRamDevice` or `memoryClass < 192`
  (segment cap 20 s, one alive, glide bars capped accordingly); `onTrimMemory(RUNNING_LOW)` cancels batch work and
  drops the *next* rendered segment in favour of a live plan. Peak render working set: two windows (≤ 32 s each) +
  stems for windows only + block buffers ≈ 40 MB above baseline; analysis < 12 MB.
- **Power**: `SAVER` (system power-save or < 15 % battery) restricts the ladder to live-capable strategies and
  disables batch work; `STRICT_SAVER` (user toggle) disables transitions.

### 7.5 Battery
Foreground service only while playing (`FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK`), foreground dropped 30 s after
pause. Batch analysis: WorkManager unique periodic + expedited-on-library-change, constraints `requiresCharging ||
(batteryNotLow && !powerSave)` (setting "analyse only while charging", default on), one track at a time,
checkpoint per track, exponential back-off. Playing-time analysis is limited to the next 2 queued tracks. Renders
are never persisted; plans and reasons are (`transition_log`). Wake lock: none beyond the service and `AudioTrack`
except a `PARTIAL_WAKE_LOCK` held for the duration of a render that must finish (≤ 15 s, released in `finally`).

---

## 8. Android app structure (`dev.muisc.app`)

Kotlin, Jetpack Compose (Material 3), single Activity, `navigation-compose`, Media3 `MediaLibraryService` with
`MuiscPlayer : SimpleBasePlayer` adapting the engine (notification, lock screen, Bluetooth AVRCP, headset buttons,
Android Auto browse tree) — ExoPlayer is **not** on the audio path (its single-stream `AudioProcessor` chain cannot
mix two decks). Room (KSP) for library and caches, DataStore Preferences for settings, **manual DI** (`AppGraph`
application-scoped lazy singletons, ~150 lines; the app is written blind, fewer annotation processors = fewer
late build failures; Hilt can be introduced later without touching the engine).

Packages (all under `app/src/main/kotlin/dev/muisc/app/`):

- `data/`: `MediaStoreScanner` (`MediaStore.Audio.Media`, `IS_MUSIC`, min duration setting, blacklisted folders;
  incremental via `MediaStore.getGeneration` (API 30+) or `DATE_MODIFIED`; `ContentObserver` → debounced rescan);
  `db/` entities `Song, Album, Artist, Genre, Playlist, PlaylistEntry, PlayHistory, TrackAnalysisEntity,
  StemAsset, TransitionLog, PairOverride, StrategyPreset`; `RoomAnalysisCache : AnalysisCache`; `prefs/` DataStore
  → `TransitionPrefs` (serialised JSON) + UI prefs.
- `playback/`: `PlaybackService` (`MediaLibraryService`), `MuiscPlayer`, `EngineController` (owns `ProgramPlayer`,
  `AudioTrackSink`, `TransitionCoordinator`, `QueueManager` with `PlaybackContext`), `MediaCodecPcmStream`,
  `AndroidEngineStreamFactory`, `GaplessTagParser`, `AudioFocusHandler` (GAIN; LOSS → pause+abandon;
  LOSS_TRANSIENT → pause+auto-resume; CAN_DUCK → −12 dB over 200 ms), `BecomingNoisyReceiver`,
  `AnalysisWorker` (WorkManager), `MlStemSeparatorOnnx` (optional; no-op when no model is installed).
- `ui/`: `theme/` (Light/Dark/Black AMOLED/Material You; Now Playing layouts Normal, Card, Blur, Adaptive colour
  via `Palette`), `library/` (Songs, Albums, Artists, Playlists incl. smart lists, Genres, Folders tree, Search),
  `detail/` (AlbumDetail, ArtistDetail), `nowplaying/` (artwork, seek, waveform strip with beat grid and the planned
  transition region, "Next transition" chip), `queue/` (drag-reorder; per-edge badge: gated(reason) / analysing /
  planned(strategy, score) / rendering / ready / live(reason)), `lab/`, `settings/` (Look, Audio, Transitions,
  Library, Now Playing, Backup).
- **Transition Lab** (`ui/lab`, backed by `dev.muisc.lab.LabSession`): pick A/B or "current + next"; both analyses
  (grid, key, LUFS, sections, edge types); "Verify grid" click audition (click track mixed on beats); ranked plans
  with sub-scores and reasons; parameter editor generated from `ParamSpec`s; lane/marker plot; "Render & audition"
  (renders through the same renderer, plays a program of A-tail (context sec) + segment + B-head through the normal
  engine); A/B compare at the same cursor; metrics chips (`MetricsReport`); thumbs up/down (nudges
  `strategyWeights[s]` by ±0.1); "Pin for this pair" (`PairOverride`), "Save as default", export (WAV + plan JSON +
  analysis JSON + `renderKey` to `Music/Muisc/Renders`) — reproducible on the CLI.

---

## 9. Testing strategy

**Synthetic signals (`dev.muisc.testkit`)** — deterministic, seeded, built on the committed `Synth`/`SyntheticSong`:
`ClickTrack(bpm, phaseMs, swing, accentEvery, driftPpm)`, `KickHatLoop(bpm)` (50 Hz decaying sine kick, noise hat,
kick-only on beat 1), `TempoRamp(bpmFrom, bpmTo)`, `ToneSequence(key, progression I–IV–V–I, harmonics 1..6 at
0.7ᵏ)`, `Detuned(cents)`, `VocalLike` (formant-filtered pulse train), `AmbientPad`, `PinkNoise`, `Sweep`,
`FadeOutTrack`, `ColdEndTrack`, `SilenceWrapped`, and `SyntheticSong(bpm, key, structure, introType, outroType)`.
`Fixtures.pairMatrix()` = 6 canonical songs (100/126/128/140/174 BPM; keys 8A/9A/3B; each edge type) → 30 ordered
pairs. `StubAnalyzer` produces `TrackAnalysis` from synthetic ground truth so strategy authors are unblocked before
the real analyser lands.

**Artifact metrics (`ArtifactMetrics`)** — every metric has PASS/WARN/FAIL thresholds:
- `clicks`: HPF 8 kHz, first difference, any 1 ms window > 20 dB above the local 50 ms RMS at a position that is not
  an onset in either source (FAIL > 0).
- `levelJumpDb`: 10 ms RMS jump > 6 dB not explained by a source onset (WARN 4, FAIL 6).
- `truePeakDbtp` (FAIL > −0.5), `clipping` (|x| ≥ 1.0), `dcOffsetDb` (FAIL > −50), `nanInf`, `silenceGapMs`
  (> 50 ms below −70 dBFS where none is planned).
- `seamIdentity`: first/last 2048 frames of the render vs deck-gained source samples: max abs diff ≤ 1e−6 (FAIL) and
  cross-correlation ≥ 0.999.
- `beatAlignmentMs`: ODF of the render (parabolic sub-frame peaks) vs `MasterGrid` beats over the overlap: median
  ≤ 5 ms, max ≤ 12 ms for WSOLA plans; max ≤ 3 ms for RESAMPLE plans and at pinned onsets.
- `rateTrackingErr`: `ratioTrace` vs planned ratio curve, max |Δ| ≤ 0.5 % (glides).
- `bassCancellation`: low-band (< 150 Hz) energy of the mix vs sum of the deck low bands over the overlap: FAIL if
  < −6 dB (out-of-phase kicks).
- `loudnessSmoothness`: 2nd derivative of short-term LUFS, WARN > 3 LU/s².
- `stereoCorrelationMin` (WARN < −0.3), `tailContained` (no energy > −60 dBFS after `expectedOutputFrames`).

**Module tests**
- `dsp`: FFT vs naive DFT (1e−5); resampler SNR/alias/alignment; LR4 sum; biquad response vs analytic; WSOLA click
  train; limiter ceiling; BS.1770 vectors; HPSS rejection; FDN stability; spectral freeze/ISTFT identity.
- `analysis`: tempo ±0.5 BPM over 60–200 BPM incl. 30 ppm drift (octave errors only when flagged in `alternates`);
  beats ±12 ms; downbeat phase exact on `KickHatLoop`; key top-1 on all 24 `ToneSequence` keys and under ±30 cents
  detune; silence trim ±1 window; edge types on `SyntheticSong` variants; structure boundaries ±1 bar on ABAB;
  streaming vs buffer overload produce equal analyses; cost regression (4-minute synthetic ≤ 3 s on CI).
- `transitions`: **`StrategyContractTest`** parameterised over `StrategyRegistry.strategies × Fixtures.pairMatrix()`
  with default params: no exception, no NaN, true peak ≤ −1 dBTP, `clicks == 0`, `seamIdentity` PASS, `levelJumpDb`
  < 6, output length within ±1 % of `expectedOutputFrames`, tails contained. A new strategy file is covered
  automatically. Random-param property test within declared ranges (n = 200 per strategy). Beat-alignment test for
  every beat-domain strategy on `KickHatLoop` pairs (120→128, 126→63 half-time, 100→140 glide). Planner property
  tests (§5.4). Determinism: rendering the same plan twice → bit-identical PCM.
- **Golden renders**: for each strategy × 12 synthetic pairs × seed, `GoldenFingerprint` stored under
  `engine/transitions/src/test/resources/golden/<strategy>/<pair>.json` (plan JSON, 20 ms RMS envelope, 4 band
  envelopes, 64-bin log-spectral fingerprint per 250 ms, PCM16 SHA-256, metrics). Default mode: plan equality +
  envelope RMSE < 0.5 dB + fingerprint tolerance 1 dB + metrics not worse; `-Pmuisc.goldenStrict=true` requires
  the hash. `./gradlew updateGoldens` rewrites and prints a metric diff. No FLAC/LFS.
- `player`: `ProgramPlayer` with `BufferPcmStream`s and a capturing `AudioSink`: exact frame counts; album gapless
  sample-continuity (output == concatenation); seam no-op on identical signals; skip/seek/pause during every segment
  kind at random positions (property test) with `evaluateProgramOutput`; deadline miss → live plan; cancellation on
  queue change; `EngineLimits` respected; `TransitionCoordinator` state machine with a fake `Clock` and fake renderer
  (ready / late / failing / cancelled).
- `cli`: smoke — `render` of every strategy on generated fixtures, `check` passes, `ab`/`sweep` produce CSV/HTML.
- Android (run locally by the user): decoder determinism (decode twice → identical), exact seek for FLAC/WAV,
  gapless LAME/AAC albums measured on a `CapturingAudioSink`, MediaSession command round-trips, audio-focus state
  machine (Robolectric). Manual matrix: Bluetooth (LDAC/AAC), wired, speaker; Doze overnight; low-RAM profile.

**Determinism policy**: table generation via `StrictMath`; hot loops use only mul/add (no FMA is emitted by
Kotlin/JVM); seeded `Xoshiro128` from `RenderKey`; no parallelism inside a render. Result: bit-identical renders
within a platform, and across CLI ↔ phone for lossless sources with the same engine rate. For MP3/AAC the platforms
decode differently, so cross-platform comparison uses the fingerprint tolerance, never the hash.

`RenderKey = sha256(a.fingerprint, b.fingerprint, TrackAnalysis.CURRENT_VERSION, strategyId, modifiers, resolved
params JSON, ENGINE_VERSION, prefs{sampleRate, channels, targetLufs, keyLock, maxStretchPercent}, seed)` — stamped in
`RenderReport.renderKey`, printed by the CLI and included in Lab exports.

---

## 10. CLI (`dev.muisc.cli`, clikt)

`muisc analyze <file> [--json] [--diff] [--click out.wav]` · `muisc plan <a> <b> [--prefs p.json] [--seed n]` (ranked
strategies, sub-scores, reasons) · `muisc render <a> <b> [--strategy id] [--modifier id]... [--set id=value]...
[--preset p.json] [--context 20] [--seed n] -o out.wav` (writes `out.wav`, `out.plan.json`, `out.metrics.json`,
`out.html`; `--context` renders A-tail + segment + B-head through `ProgramPlayer` so the seams are the real ones) ·
`muisc ab <a> <b> [--all | --strategies x,y]` (one WAV per strategy, `metrics.csv`, one HTML with level-matched
players) · `muisc sweep <a> <b> --strategy id --param p=lo:hi:steps [--param q=…]` (grid renders, `sweep.csv`, SVG
plots, 2-D heat-map) · `muisc score <dir> --csv matrix.csv [--html]` (pairwise compatibility matrix) ·
`muisc check out.wav [--a a --b b --plan out.plan.json]` (metrics report) · `muisc stems <file> --out dir/` ·
`muisc goldens check|update` · `muisc play <wav>` · `muisc lab serve <a> <b> [--port 8765]` (JDK `HttpServer`
single-page UI: auto-generated sliders from `ParamSpec`, re-render on change, waveform/lane plots, metrics, audio
player). Analysis cache under `~/.muisc/analysis`. Every render prints its `RenderKey`.

---

## 11. Work breakdown (parallel work packages, exact file ownership)

Rules: a package owns the listed files and their tests only; the shared touch points are `gradle/libs.versions.toml`,
`settings.gradle.kts` (WP0 only), `DefaultStrategyRegistry.kt` (append one line per strategy; WP7 owns the file,
WP8/WP9 append), and `Commands.kt` (WP13). Merge conflicts elsewhere are a process failure.

| WP | Owner lane | Depends on | Files (under the repo root) |
|---|---|---|---|
| **WP0 Freeze** (lead, first, ~1 day) | lead | — | `engine/CHANGELOG.md`; `engine/audio/src/main/kotlin/dev/muisc/audio/{ResamplingPcmStream.kt, GaplessInfo.kt, EngineStreamFactory.kt}` (interfaces + stub bodies); `engine/analysis/.../TrackAnalyzer.kt` (+default method); `engine/analysis/.../model/TrackAnalysis.kt` (+fields, version 2); `engine/dsp/.../stems/Stems.kt` (+`MlStemSeparator`); `engine/transitions/.../TransitionStrategy.kt` (+`RenderReport` fields); `engine/transitions/.../PlaybackProgram.kt` (+`Segment.Live`); `engine/transitions/src/main/kotlin/dev/muisc/transitions/{sdk/ParamSet.kt, core/MasterGrid.kt, core/PhaseLockedDeck.kt, core/StretcherSelector.kt, core/DeckGain.kt, core/CrossfadeLaw.kt, core/Lane.kt, live/LivePlan.kt}` (signatures + TODO bodies); new modules `engine/metrics`, `engine/player`, `engine/lab`, `engine/testkit` with `build.gradle.kts` and interface files `metrics/.../{ArtifactMetrics.kt, MetricsReport.kt, GoldenFingerprint.kt}`, `player/.../{AudioSink.kt, Clock.kt, EngineLimits.kt, EngineCommand.kt, ProgramPlayer.kt, TransitionCoordinator.kt}`, `lab/.../LabSession.kt`; `settings.gradle.kts` includes; `ArchTest.kt` template copied into every engine module |
| **WP1 DSP-A** (in flight, worktree-1) | dsp | WP0 | `engine/dsp/.../fft/{Fft.kt, Stft.kt}`, `window/Window.kt`, `mel/MelFilterbank.kt`, `qa/ArtifactDetector.kt` |
| **WP2 DSP-B** (in flight, worktree-2) | dsp | WP0 | `engine/dsp/.../filter/{Biquad.kt, LinkwitzRiley.kt, OnePole.kt, StateVariableFilter.kt}`, `gain/Curves.kt`, `resample/{Resampler.kt (phases → 512, alignTo), VariableRateResampler.kt}`; then `engine/audio/.../ResamplingPcmStream.kt` body |
| **WP3 DSP-C** | dsp | WP1, WP2 | `engine/dsp/.../stretch/{TimeStretcher.kt, WsolaStretcher.kt, PhaseVocoderStretcher.kt, PitchShifter.kt}`, `hpss/Hpss.kt`, `stems/PseudoStemSeparator.kt` |
| **WP4 DSP-D** | dsp | WP1, WP2 | `engine/dsp/.../loudness/{Bs1770Meter.kt, TruePeakMeter.kt}`, `dynamics/TruePeakLimiter.kt`, `fx/{Delay.kt, FdnReverb.kt, SpectralFreeze.kt, Granulator.kt}`, `noise/{ShapedNoise.kt, BiquadBankNoise.kt}`, `synth/{Oscillators.kt, Adsr.kt}`, `util/{Xoshiro128.kt, Autocorrelation.kt, MedianFilter.kt, DcBlock.kt, Db.kt, Interp.kt, Mix.kt}` |
| **WP5 Analysis-A (rhythm)** | analysis | WP1 (can start on a naive DFT stub) | `engine/analysis/src/main/kotlin/dev/muisc/analysis/{frontend/StreamingFrontend.kt, frontend/Decimator.kt, rhythm/OnsetDetector.kt, rhythm/TempoEstimator.kt, rhythm/BeatTracker.kt, rhythm/GridFitter.kt, rhythm/DownbeatEstimator.kt, rhythm/PhraseEstimator.kt}` |
| **WP6 Analysis-B (tonal/level/structure + assembly)** | analysis | WP1, WP4 (loudness) | `engine/analysis/.../{level/SilenceTrimmer.kt, level/LoudnessAnalyzer.kt, tonal/TuningEstimator.kt, tonal/ChromaExtractor.kt, tonal/KeyEstimator.kt, spectral/BarFeatureExtractor.kt, spectral/SpectrumProfiler.kt, structure/StructureAnalyzer.kt, structure/EdgeClassifier.kt, structure/CueFinder.kt, DefaultTrackAnalyzer.kt, FileAnalysisCache.kt, Fingerprint.kt}` |
| **WP7 Transition core** | transitions | WP2, WP3 | `engine/transitions/.../core/{MasterGrid.kt, PhaseLockedDeck.kt, StretcherSelector.kt, DeckGain.kt, CrossfadeLaw.kt, Lane.kt, WindowPadding.kt}`, `DefaultTransitionRenderer.kt`, `DefaultPairAnalyzer.kt`, `DefaultStrategyRegistry.kt`, `RenderKey.kt`, `JvmTrackAudioLoader.kt`, `LazyStemProvider.kt` |
| **WP8 Strategies-1** | transitions | WP7 (+ `StubAnalyzer` from WP11) | `engine/transitions/.../strategies/{Crossfade.kt, OutroIntroMinimal.kt, PhraseCut.kt, BeatMatchedBlend.kt, BassSwap.kt, FilterSweep.kt, EchoOut.kt}` + one test class each |
| **WP9 Strategies-2** | transitions | WP7, WP3, WP4 | `engine/transitions/.../strategies/{StemSwap.kt, DrumBreakBridge.kt, LoopRollRiser.kt, HarmonicBlend.kt, SpectralFreezeBridge.kt, AmbientBridge.kt, BrakeStop.kt}`, `modifiers/{TempoGlide.kt, TextureCarry.kt}` + tests |
| **WP10 Planner + live** | transitions | WP0 (model only) | `engine/transitions/.../planner/{CompatibilityScores.kt, StructTables.kt, DefaultTransitionPlanner.kt, Ladder.kt, PlanExplanation.kt}`, `live/{DefaultLivePlanFactory.kt}` + `PlannerPropertyTest.kt` |
| **WP11 Metrics + testkit** | quality | WP1, WP4 | `engine/metrics/src/main/kotlin/dev/muisc/metrics/{ArtifactMetrics.kt, MetricsReport.kt, Thresholds.kt, GoldenFingerprint.kt, SeamIdentity.kt, BeatAlignment.kt, BassCancellation.kt}`; `engine/testkit/src/main/kotlin/dev/muisc/testkit/{Signals.kt, SyntheticSongs.kt, Fixtures.kt, StubAnalyzer.kt, StrategyContract.kt, GoldenCompare.kt, CapturingAudioSink.kt}` |
| **WP12 Player** | engine | WP0, WP2 | `engine/player/src/main/kotlin/dev/muisc/player/{ProgramPlayer.kt, PcmRing.kt, SeamFader.kt, LiveGraph.kt, LiveExecutor.kt, TransitionCoordinator.kt, CoordinatorState.kt, EngineLimits.kt, EngineCommand.kt, EventQueue.kt, WavFileSink.kt, JavaSoundSink.kt}` + tests with fake clock/sink |
| **WP13 CLI + lab** | tools | WP7–WP12 (incrementally; `analyze`/`render` first) | `engine/lab/src/main/kotlin/dev/muisc/lab/{LabSession.kt, Experiment.kt, Sweep.kt, CsvWriter.kt, SvgPlot.kt, HtmlReport.kt}`; `tools/cli/src/main/kotlin/dev/muisc/cli/{Commands.kt, AnalyzeCommand.kt, PlanCommand.kt, RenderCommand.kt, AbCommand.kt, SweepCommand.kt, ScoreCommand.kt, CheckCommand.kt, StemsCommand.kt, GoldensCommand.kt, PlayCommand.kt, LabServeCommand.kt, JvmDecoders.kt}` |
| **WP14 Android media** | android | WP12 | `app/src/main/kotlin/dev/muisc/app/playback/{PlaybackService.kt, MuiscPlayer.kt, EngineController.kt, QueueManager.kt, MediaCodecPcmStream.kt, AndroidEngineStreamFactory.kt, GaplessTagParser.kt, AudioTrackSink.kt, AudioFocusHandler.kt, BecomingNoisyReceiver.kt, AnalysisWorker.kt, RoomAnalysisCache.kt, MlStemSeparatorOnnx.kt}` |
| **WP15 Android data + UI** | android | WP0 (model only) | `app/src/main/kotlin/dev/muisc/app/{MuiscApplication.kt, MainActivity.kt, di/AppGraph.kt, data/MediaStoreScanner.kt, data/db/*.kt, data/prefs/*.kt, ui/theme/*.kt, ui/library/*.kt, ui/detail/*.kt, ui/nowplaying/*.kt, ui/queue/*.kt, ui/lab/*.kt, ui/settings/*.kt, ui/Navigation.kt}` |

**Phasing**: Phase 0 = WP0 (blocking, ~1 day). Phase 1 (parallel) = WP1–WP6, WP10, WP11, WP12, WP15, and WP13's
`analyze`/`render` skeleton. Phase 2 = WP7, WP8 (milestone: CLI renders a bass swap between two real files with
metrics green), WP14 (milestone: album gapless on a phone through `ProgramPlayer`). Phase 3 = WP9, remaining CLI
commands, Lab UI, goldens corpus. Phase 4 = phone integration (skip/seek semantics, battery), tuning with
`ab`/`sweep`, ML stems (user-provided model).

**Honest hard parts**: beat/downbeat tracking on real music without ML (~85 % / ~70 %; mitigated by confidence
gates, per-section phase re-estimation, click audition, Lab pinning); WSOLA above ~6–8 % on dense mixes (resample
shortcut, transient pinning, caps, longer glides, PV behind a flag); pseudo-stems leak (scored as EQ mixes until an
ML separator lands); MediaCodec behaviour across OEMs (decode-from-zero, own gapless parsing, instrumented
determinism tests the user runs); render deadlines on little cores (live ladder, logged reasons); the Android module
cannot be compiled here (thin, mainstream APIs, manual DI, expect a local fix-up pass).

---

## 12. Glossary of frozen identifiers (quick reference)

Strategy ids: `crossfade`, `outroIntroMinimal`, `phraseCut`, `beatMatchedBlend`, `bassSwap`, `stemSwap`,
`drumBreakBridge`, `filterSweep`, `echoOut`, `loopRollRiser`, `harmonicBlend`, `spectralFreezeBridge`,
`ambientBridge`, `brakeStop`. Modifier ids: `tempoGlide`, `textureCarry`. Reserved plan param prefix: `grid.*`.
Live plan kinds: `crossfade`, `phraseCut`, `bassSwap`, `filterSweep`, `echoOut`. Metric ids as in §9.
`ENGINE_VERSION` constant lives in `dev.muisc.transitions.RenderKey`.

---

## 13. Correction log (errors found by the judges, and what this document does about them)

| Flagged error | Correction here |
|---|---|
| TextureCarry residual `X − H − P` is identically zero under Wiener masks | Texture source = per-bin median magnitude of A's last 15 s (`textureMagnitude`), spectral freeze, or the `other` pseudo-stem; 3-component HRP exposed in `Hpss` for a true residual (§4 modifiers, §6) |
| Crossfade-law rationale inverted | Equal-power between two different tracks always; linear only for coherent material and seams (§2.5, §4) |
| 64-phase resampler cannot reach 90 dB SNR | `SincKernelSpec(32 taps, 512 phases, β 9)`; in-flight default must be raised (§2.1, §6) |
| "Sample-aligned downbeats" / ±8 ms test below ODF resolution | PLL bounds accumulated drift; WSOLA jitter ≤ ±10 ms except at pinned onsets; tests use parabolic sub-frame peaks with ±12 ms (WSOLA) / ±3 ms (resample) tolerances (§9) |
| "Same bytes on CLI and phone" | Bit-identity intra-platform and cross-platform for lossless sources only; fingerprint tolerance for lossy; `StrictMath` tables, seeded PRNG (§9) |
| EchoOut 3/16 beat; "+7 semitone" Camelot confusion | Delay choices 0.5 / 0.75 (dotted eighth) / 1 beat; +1 semitone = +7 Camelot numbers (§4, §5.1) |
| No tuning estimation before chroma folding | `TuningEstimator` (±50 cent histogram) precedes folding; section keys for outro/intro (§3.2) |
| BassSwap "LIVE_OK" while built on WSOLA | Live bass swap only via the `Rate` node when `|r−1| ≤ 0.02`; strategies never run live (§2.6, §4) |
| `DeckSource.readAt` random access on the audio thread; graph built on the audio thread | Sequential ring-buffered body streams; separate stream instances for renderer/analyser; `LiveGraph` pre-built off-thread and handed over by command (§7.3) |
| Loudness normalisation vs bit-identical seams | `DeckGain` is a pure function of analysis+prefs applied identically by player and renderer; "unity" defined accordingly (§2.4) |
| No memory ceiling / low-RAM / onTrimMemory | `EngineLimits` (PHONE/LOW_RAM), stems for windows only, streaming analysis, `onTrimMemory` handling (§2.7, §7.4) |
| Render thread at BACKGROUND (+ "Thread.yield") | Coordinator thread at `DEFAULT + LESS_FAVORABLE` (foreground cgroup); batch analysis only at BACKGROUND (§7.2) |
| 30 s analysis per track, no fast path | Streaming analyser ≤ 10 s on an A53 for 4 minutes; next-2-tracks policy; batch pre-analysis; live ladder on misses (§3.1, §7.4). EDGES scope rejected (see DECISIONS.md) |
| 64-frame equal-power seam fade | 240-frame (5 ms) **linear** seam fade, 960 frames after inexact seeks (§0, §7.3) |
| Seek choreography unspecified | §7.4 |
| MediaExtractor seek trusted for MP3/AAC seams | Decode-from-zero for seam-critical positions; decoder-relative frames; decoderId in fingerprint (§7.1, §3.3) |
| Network probe in `settings.gradle.kts`; Hilt written blind | Existing SDK-directory detection kept; manual DI (§1, §8) |
| Latency claims inconsistent with deep buffers (other designs) | Honest budget: pause immediate, skip/seek ≤ buffer + block (§7.2) |
| Seek-based live loop node (other design) | Loop roll captures A's last bar into memory; not a live node (§4 #10) |
| Freeverb freeze; 1.5 ms limiter look-ahead (other design) | Jot FDN with damping bypass in freeze; 5 ms look-ahead limiter (§6) |
| FLAC goldens in Git LFS (other design) | `GoldenFingerprint` JSON, PCM16 hash in strict mode (§9) |
| Planner `minScore` below the floor scores (other design) | No cut-off; floors 0.05/0.15; ladder = ranking (§5.3) |
| Full spectrogram held in memory (other design) | Streaming front-end, per-frame consumption (§3.1) |
| Seconds-based scheduling (other design) | Frames everywhere; seconds only in `LanePoint`/UI (§2) |
| 24 s segment cap kills long glides (other design) | Cap scales per device class; glide bars limited by `maxRenderedSec` (§4 modifiers, §7.4) |
| Sealed FxNode in a frozen core makes new techniques cross-cutting (other design) | Strategies own their DSP (batch `render`); only the tiny live node set is sealed (§2.6) |
