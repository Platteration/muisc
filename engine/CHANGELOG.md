# Engine contract changelog

Frozen contracts live in `engine/audio`, `engine/analysis/.../model`, `engine/transitions` (top-level files) and
`engine/dsp/.../stems/Stems.kt`. Any change to them is recorded here. Additive fields with defaults are
pre-approved; anything else needs a one-line request below and the lead's approval.

## 0.1.0 — bootstrap and design adoption

- `TrackAnalysis` schema v2: added `outroKey`, `introKey`, `outroLtasDb`, `introLtasDb`, `tuningCents`, `onsetFrames`,
  `textureMagnitude` (all defaulted). Caches keyed by version are invalidated.
- `TrackAnalyzer`: added the streaming overload `analyze(stream: PcmStream, ...)` with a default body.
- `RenderReport`: added `renderKey`, `metrics`, `ratioTrace` (defaulted).
- `FadeLaw` enum added next to `Params` (shared by the core substrate and live plans).
- `Segment.Live(from, to, plan: LivePlan)` added; `live/LivePlan.kt` defines `LivePlan`, `LiveNode`, `LivePoint`,
  `Deck`, `LivePlanFactory`.
- `ProgramBuilder` interface added to `PlaybackProgram.kt`.
- `MlStemSeparator` interface added to `Stems.kt`.
- `GaplessInfo` and `EngineStreamFactory` added to `AudioDecoder.kt`.
- New modules `engine:metrics` and `engine:player` (empty until their work packages land).

## Unreleased — user customization (presets, styles, pins, learned weights)

- `TransitionPrefs`: added `activePresets: Map<String, String> = emptyMap()` (strategy id → preset id). Defaulted, so
  existing JSON decodes unchanged; the JSON the app and CLI write gains an `activePresets` key when they encode
  defaults. Not part of `RenderKey` (the preset's values reach the key through `plan.params`).

## 0.2.0 — user-customizable transitions

- `TrackAnalysis.contentHash` (defaulted, additive): a hash of the decoded audio, set by `DefaultTrackAnalyzer`.
  `TrackAnalysis.identity` (computed, not serialized) is `contentHash` when present, else `fingerprint`.
- The planner's tie-break jitter and pinned pairs are keyed by `identity` instead of `fingerprint`. Behaviour change:
  plans no longer change when a file's modification time changes (copy, backup restore, touch); for tracks analysed
  after this change the jitter values differ from before, so near-tied candidates may rank in a different order than
  they did under the old keys. Analyses cached before the change have no `contentHash` and keep the old keys.
- Recipe format (`dev.muisc.transitions.recipe`), `sdk.StrategyTraits`, and the customization package
  (`dev.muisc.transitions.custom`) added; `TransitionPrefs.activePresets` (defaulted, additive).
- `TransitionPrefs.excludedTechniques: Set<Technique> = emptySet()` (defaulted, additive; a missing key decodes to
  empty). New `sdk.Technique` (echo, reverb, filter, stems, tempoGlide, generated) and
  `StrategyTraits.techniques(params, beatsPerBar)` (default null: "not declared"). The planner skips a strategy that
  declares an excluded technique for the params it would use. `RecipeStrategy` declares them from the resolved
  recipe; built-in strategies declare none and are still excluded by id. Style files gain an `exclude` key.
- The live ladder (`DefaultLivePlanFactory`) now obeys `disabledStrategies` and `excludedTechniques`: a rung that is
  disabled, or would use an excluded technique (`echoOut` echo, `filterSweep` filter, `bassSwap` tempo glide when B
  has to be nudged), is skipped. Crossfade is never skipped. Behaviour change: under the purist style, or with those
  strategies disabled, a skip or a missed deadline now falls back to a phrase cut or a crossfade.
- `TransitionCoordinator` reuses a retained render only while the planner still ranks its strategy.
- `FileFeedbackStore` and `FilePinStore` re-read their file under an inter-process lock before each write and write
  atomically; a hidden `.<file>.lock` is created next to each. A running store sees ratings saved by other
  processes. `FileFeedbackStore.refresh()` added.
- Recipes: JSON nested deeper than 64 levels and expressions nested deeper than 64 levels are reported as problems.
  `RecipeLibrary.save` targets the file in use and refuses while several files hold the id. In match/glide, lanes
  are evaluated with the bars actually rendered.
- `ArtifactMetrics` maps source onsets through the beat grids for renders that publish `masterBeat`, so a kick that
  both sources play is no longer reported as a level jump (`levelJumpDb` on the beat-domain blends drops from
  24-29 dB to 5-14 dB on the fixtures). `TempoGlideModifier` fits its glide into the body of `stemSwap` and
  `drumBreakBridge` (`BeatDomain.PARAM_BODY_BEATS`), which used to render past their windows.

## 0.3.0 — smart shuffle, learning from skips, short tracks, analyzer and beat-alignment fixes

- `TrackAnalysis.CURRENT_VERSION` 2 -> 4 (3: metrical-level check, seeded weighted grid fit, peak-hold transient
  envelope; 4: the metrical-level check no longer halves backbeats, boom-chick or oom-pah, and needs hat-like
  off-beats and a halved tempo of at least 55 BPM). Every cache keyed on the version re-analyses, and every
  `RenderKey` changes, so pre-rendered transitions render again. New public API with defaults: `MetricalLevel` /
  `MetricalCheck`, `TempoEstimator.halved()`, `OnsetFeatures.rawMidOdf`, extra trailing parameters on
  `TransientAligner`, `GridFitter` and `RhythmAnalyzer`.
- `dsp.filter.ZeroPhaseCrossover` (new). `bassSwap` and the recipes' 3-band EQ use it instead of the causal crossover;
  the live fallback still uses the causal one. Renders of those strategies and recipes change.
- Metrics: `beatAlignmentMaxMs` is removed and replaced by `beatAlignmentP90Ms`; `beatAlignment*` now compare the
  render's articulated beats with the sources' attacks, and are absent when no beat has both.
  `ArtifactMetrics.beatAlignment` takes an optional input. `Signals.onsetTimesSec` (internal) was removed. With
  sources, every counted beat is also checked above 2 kHz, so a deck played late on beats both decks share is caught
  (a beat's error is the larger of the full-band and high-band errors, and high-band errors count only when at least
  10 % of the beats, and at least 2, have one). Values can only rise, and only when sources are given. A late deck
  with no high-band attacks in the overlap can still pass.
- `PlanCandidate.pinned: Boolean = false` (additive, defaulted; part of equality and `copy()`), set by
  `DefaultTransitionPlanner` for a used pin (stored, session or one-off).
- `DefaultProgramBuilder.room(...)`, `roomOrder(...)` and `Room` (new): candidates that leave both neighbours their
  minimum body first, a pin first unless it would starve a track, the planner's order unchanged when every candidate
  starves. `build` and `bodySegment` are unchanged.
- `TransitionCoordinator`: new constructor parameter `onTransitionSkipped: ((TransitionSkip) -> Unit)? = null` and
  `TransitionSkip`; candidates are tried in room order, a candidate that would starve a track is never rendered (the
  pair plays body to body, `Gated("No room for a transition")`), `noteUserSkip` reads the position on the caller's
  thread.
- `FeedbackLearner` / `FileFeedbackStore`: implicit (skip) tallies. A `feedback.json` that holds skips is written as
  version 2, which earlier engines cannot read (they keep it aside as `.corrupt`); files without skips stay version 1.
  New `FileFeedbackStore.reset(strategyId, backup)`: a reset under the store's lock that keeps a `.bak` copy.
- `dev.muisc.transitions.sequence` (new): `SetSequencer`, `PairCostModel`, `SequenceItem`, `SequenceOptions`,
  `SequenceResult`, `EnergyArc`.
