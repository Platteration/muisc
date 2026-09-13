# Muisc — Key Decisions and Rejected Alternatives

Companion to `DESIGN.md`. Each entry: the decision, why, and what was rejected.

## Architecture

1. **Render-ahead with beat-domain rendering (MasterGrid + PhaseLockedDeck with a PLL term).**
   Why: beat-matching, half/double-time and tempo glide become one mechanism; unbounded CPU for WSOLA/HPSS/FDN;
   deterministic renders shared by CLI, tests and phone.
   Rejected: a real-time two-deck engine (Mixxx-style) — every strategy would need a phone CPU budget, look-ahead
   strategies become awkward, CLI/phone parity is lost. Rejected: per-strategy alignment code (each beat strategy
   re-implementing drift correction).

2. **Build on the committed `dev.muisc.*` skeleton; no renames.** Additive-only edits to frozen files, recorded in
   `engine/CHANGELOG.md`. Why: the repo builds and passes tests today; the brief's parallel-work premise depends on
   frozen contracts. Rejected: the `com.muisc`/`:core-api` and `engine:model` layouts proposed by two designs (a
   rewrite of the bootstrap for no functional gain).

3. **One pure-JVM `ProgramPlayer` (`engine:player`) for album gapless, live and rendered segments, one seam
   algorithm.** Why: the album path is exercised every minute, so gapless bugs and seam bugs are the same bug; the
   CLI's `--context` render uses the exact seams the phone produces. Rejected: Android-only sequencer (untestable
   here); three separate splice paths.

4. **Strategies are batch functions (`plan` + `render(TransitionInput)`), each owning its DSP; live transitions are a
   small sealed `LiveNode` set.** Why: a new technique is one file plus one registry line, with no shared executor
   to block on; parallel work stays disjoint. Rejected: a declarative lane-graph executor with a sealed `FxNode`
   set in the frozen core — every new trick would edit the frozen contract and one hot file. Rejected: streaming
   `TransitionGraph.process()` strategies — more code per strategy, contradicts the frozen batch interface, and the
   only benefit (running strategies live) is covered by `LivePlanFactory`.

5. **Live fallbacks are real DJ moves (crossfade, phrase cut, bass swap via a rate node, filter sweep, echo-out),
   never a strategy running live.** Why: rapid skipping through a playlist would otherwise be a dumb crossfade
   most of the time; the live node set is tiny and allocation-free. Rejected: single `LiveCrossfader` only.

## Audio and DSP

6. **Splice contract + position-deterministic `ResamplingPcmStream` + 5 ms LINEAR seam fade.** Why: identical
   samples on both sides of a seam require the resampler's polyphase phase to be a function of absolute input
   index; a linear fade is a no-op on identical signals, equal-power is not (+3 dB bump). Rejected: 64-frame
   equal-power micro-fade (too short for bass content and wrong law); "approximately equal" seams.

7. **Deck gain as a pure function of analysis + prefs, applied identically by player and renderer.** Why: loudness
   normalisation inside the render would otherwise break seam identity. Rejected: `Segment.Body.gainDb` (would change
   a frozen class) and normalising only inside the segment.

8. **Stretcher selection: resample below 2 % ratio deviation, WSOLA with ODF transient pinning above, phase vocoder
   behind a flag.** Why: the most common near-match case gets zero WSOLA artefacts (< 35 cents drift); pinning
   prevents doubled kicks. Rejected: WSOLA everywhere; phase vocoder as the default (phasey on drums).

9. **Resampler = 32 taps × 512 phases, Kaiser β 9, linear inter-phase interpolation.** Why: the ≥ 90 dB SNR test is
   otherwise unreachable (64–128 phases give ~65–75 dB). Rejected: cubic phase interpolation with fewer phases
   (more per-sample work on the phone).

10. **Jot FDN with damping bypass for freeze; 5 ms look-ahead true-peak limiter.** Rejected: Freeverb freeze
    (metallic, loses highs); 1.5 ms look-ahead (intermodulation on sub-bass).

11. **Crossfade law: equal-power between two tracks, linear only for coherent material.** Rejected: p-law for
    "coherent beat-matched blends" — two different songs are uncorrelated at sample level even when beat-matched.

12. **Pseudo-stems = LR4 crossover + HPSS + centre extraction, with `other` defined as the residual.** Why: the
    Stems sum invariant holds by construction; works today with no model. ML stems are an interface
    (`MlStemSeparator`) for a user-provided ONNX model, run charging-only on transition windows. Rejected: real-time
    or full-track ML separation (minutes per track on a phone CPU; storage and battery).

13. **TextureCarry texture = per-bin median STFT magnitude of A's last 15 s, released by B's measured band energy.**
    Rejected: `X − H − P` residual (identically zero under Wiener masks); fixed release curves.

## Analysis

14. **Streaming analysis from one 1024/256 STFT at 22.05 kHz mono (plus a lazy 4096 chroma STFT and an engine-rate
    branch for loudness/LTAS); never hold a spectrogram.** Why: a 6-minute spectrogram is 127–250 MB and would OOM a
    phone. Rejected: shared in-memory spectrogram; 2048-point ODF window at 22.05 kHz (93 ms smears onsets).

15. **Whole-track analysis only (no EDGES scope).** Why: the frozen `TrackAnalysis`/`BeatGrid` are whole-track;
    phrases, structure and cues need the whole track; streaming makes the full pass cheap enough (≤ 10 s on an
    A53 for 4 minutes) and the next track is normally known minutes ahead. Rejected: an edges-only fast path (would
    need a second grid representation and a scope field in the frozen model). Revisit if field data shows deadline
    misses dominate.

16. **Ellis 2007 DP beat tracker with tempogram-derived local period, then RIGID-vs-FLEX grid fit at 8 ms residual;
    beat-domain strategies gated on grid confidence ≥ 0.5.** Rejected: ML beat trackers (no models here); rigid-only
    grids (fail on live drummers).

17. **Tuning estimation before chroma folding; section keys (outro of A, intro of B) drive Camelot distance;
    Temperley profiles with Krumhansl as a switch.** Rejected: fixed A4 = 440 folding.

18. **Fingerprint includes the decoder identity; all frames are decoder-relative.** Why: MP3/AAC priming differs
    per decoder. Rejected: absolute file-timeline frames.

## Planner

19. **Weighted sum of eight 0..1 sub-scores with per-strategy weights and hard gates, user weights, energy
    preference, variety penalty, seeded jitter; no minimum-score cut-off; floors 0.05 (crossfade) and 0.15
    (ambientBridge).** Why: the ranking itself is the escalation ladder, so every pair always has ≥ 2 candidates
    and the coordinator can descend on failure. Rejected: a `minScore` threshold (contradicts the floors); ML
    ranking (no training data).

20. **TempoGlide and TextureCarry are modifiers composed onto base strategies, communicating through reserved
    `grid.*` plan params.** Why: 14 × 2 × 2 catalogue coverage without duplicating strategies; matches the frozen
    `TransitionModifier` contract. Rejected: standalone glide strategy only.

21. **Gating is a property of the queue (`PlaybackContext`), extended by segue protection and the consecutive-album-
    pair rule in the coordinator with reason strings.** Rejected: inferring context per pair.

## Android

22. **MediaCodec decoding with decode-from-zero for seam-critical MP3/AAC positions, own LAME/iTunSMPB gapless
    parsing, two stream instances per track.** Rejected: trusting `MediaExtractor.seekTo` timestamps for seams.

23. **`AudioTrack` float, 4 × min buffer (≥ 120 ms), underrun-adaptive; honest latency (pause immediate, skip
    ≤ buffer + block).** Rejected: Oboe/AAudio low-latency path (unneeded for a music player); 250–400 ms deep
    buffer with a "60 ms" claim.

24. **Coordinator/render thread at `DEFAULT + LESS_FAVORABLE`; only batch analysis at `BACKGROUND`.** Why: Android
    priorities are nice values (higher = less favourable) and `BACKGROUND` moves the thread to the throttled cgroup.

25. **`EngineLimits` device classes (PHONE / LOW_RAM), stems for windows only, `onTrimMemory` drops the next render,
    SAVER/STRICT_SAVER power modes.** Rejected: a single global 24 s cap (kills long glides) and no cap at all.

26. **Media3 `SimpleBasePlayer` + `MediaLibraryService`, manual DI, Room + DataStore.** Rejected: ExoPlayer on the
    audio path (single-stream processor chain); Hilt (annotation-processing surface in a module compiled blind).

## Quality and tooling

27. **Separate `engine:metrics` (with `bassCancellation`, `beatAlignmentMs`, `rateTrackingErr`, `seamIdentity`)
    shared verbatim by tests, CLI `check`, the install gate on the phone and the Lab.** Rejected: test-only checks.

28. **Golden = plan JSON + envelopes + log-spectral fingerprint + metrics, PCM16 hash only in strict mode.**
    Rejected: FLAC goldens in Git LFS (no JVM FLAC encoder, no LFS here).

29. **Determinism: `StrictMath` for tables, seeded Xoshiro from `RenderKey`, no intra-render parallelism; byte
    identity claimed only within a platform or for lossless sources.** Rejected: claiming phone/CLI byte identity
    for MP3/AAC.

30. **CLI iteration loop: `ab`, `sweep`, `lab serve` (JDK HttpServer), inline SVG/HTML reports; `StrategyContractTest`
    auto-covers new strategies; `StubAnalyzer` unblocks strategy authors.** Rejected: a desktop GUI toolkit; waiting
    for the analyser before writing strategies.
