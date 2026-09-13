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
