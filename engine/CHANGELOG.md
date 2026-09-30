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
