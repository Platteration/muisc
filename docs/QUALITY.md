# Transition quality: measured state

Every render is scored by `engine:metrics` (`ArtifactMetrics`). This file records what the metrics say today on
the synthetic fixture set, so the numbers you are tuning against are written down rather than assumed. Regenerate
it any time with:

```
muisc synth --set --out fixtures/
muisc mix fixtures/t120C.wav fixtures/t126Am.wav fixtures/t140Fs.wav fixtures/t63G.wav -o set.wav --context shuffle
muisc ab fixtures/t120C.wav fixtures/t126Am.wav --all -o ab/
muisc check set.wav
```

## What the metrics mean

| Metric | Meaning | Fails at |
|---|---|---|
| `clicks` | Sample-level discontinuities not explained by an onset in either source | any |
| `levelJumpDb` | Short-term RMS jump not explained by a source onset | 6 dB |
| `truePeakDbtp` | Inter-sample peak of the render | above -0.5 dBTP |
| `seamIdentity` | The render's first/last frames must equal the deck-gained source samples | mismatch |
| `beatAlignmentMs` / `beatAlignmentMaxMs` | Detected onsets in the render against the master beat grid — **beat-domain renders only** (see below) | median 5 ms / max 12 ms |
| `loudnessSmoothness` | Second derivative of short-term loudness; WARN only, never a FAIL | 3 LU/s² |
| `tailContainedDb` | Effect tails must not spill past the segment | audible spill |

`beatAlignment*` is produced only when the plan publishes a `masterBeat` lane, which is a strategy's statement
that both decks are slaved to a `MasterGrid`. `echoOut`, `phraseCut`, `filterSweep`, `brakeStop` and
`loopRollRiser` never stretch a deck — B runs at its own tempo — so they publish their A-beat ruler as `beatsA`
(informational, for the Lab) and have no beat-alignment metric at all.

## The four-track shuffle mix

`t120C → t126Am → t140Fs → t63G`, `--context shuffle`, through the real `ProgramPlayer`:

| # | transition | strategy | verdict | worst metrics |
|---|---|---|---|---|
| 1 | t120C → t126Am | `echoOut` | WARN | `levelJumpDb` 5.09, `loudnessSmoothness` 9.50, `tailContainedDb` -41.2 |
| 2 | t126Am → t140Fs | `phraseCut` | WARN | `loudnessSmoothness` 23.9 |
| 3 | t140Fs → t63G | `echoOut` | FAIL | `levelJumpDb` 10.64, `loudnessSmoothness` 6.10, `stereoCorrelationMin` -0.35 |

Program output: 6 segments, 5 seams, 1:56.8. `clicks = 0`, `levelJumpDb = 2.66` (PASS), `truePeakDbtp = -0.94`
(WARN). `muisc check set.wav` over the whole two minutes, with no sources to excuse anything, also reports
`clicks = 0`.

Transition 2 is planned and rendered but **not installed**: `phraseCut` wants to cut t126Am at 5.6 s while the
`echoOut` before it hands the track over at 13.9 s, so the program builder drops it and plays that pair
body-to-body (see "fixed", item 3). The underlying cause is upstream of the transition engine — the analyser puts
t126Am's `mixOutBeat` at beat 16 of 64, at the start of its DROP section rather than near its outro, and
`phraseCut` correctly cuts at the first phrase start at or after it.

## Fixed since the last measurement

1. **Clicks in the assembled program** (`clicks = 2` → `0`). Not a player defect: the program is continuous
   sample by sample, verified over its whole length. Both clicks were `ArtifactMetrics.evaluateProgramOutput`
   reporting its own analysis. It sliced the program at `seam ± 50 ms` and high-passed each slice from a zeroed
   filter state, so the first millisecond of a region was a step out of silence into whatever was playing —
   0.2 of first difference on material sitting at 0.35, which is a click by every criterion the detector has.
   The same false click was then counted twice because the mix listed one seam frame twice (two segments met at
   the same output frame, with the zero-length body of item 3 between them). Now each inspected window is read
   with 50 ms of context on both sides that is filtered but not counted, and seam frames are de-duplicated.
   Regression test: `ArtifactMetricsTest.programSeamsAreInspectedWithContextAndOnlyOnce`.

2. **Level jumps through `echoOut`** (34.9 → 5.09 dB on pair 1, 27.0 → 10.6 dB on pair 3; pair 1 is now inside
   the 6 dB budget, pair 3 is not — see known failure 1). Two real defects and one metric defect.
   - *Real*: the tail died before B arrived. A feedback delay loses `feedback` per repeat **plus** whatever its
     loop filters take out of the material, and the second term dominated: a nominal 0.72 feedback (-2.9 dB a
     repeat) decayed at -5.2 dB a repeat, so one bar after the cut the tail was 26 dB down — inaudible — and the
     segment played a second of near-silence before slamming B in at full level. The wet send is now ridden up
     across the gap (new param `tailAtEntryDb`, default -9 dB, capped at 18 dB of make-up) and released again
     over the bar after B's entry, which is the move a DJ makes with the other hand. What you hear is a longer,
     slower echo instead of a signal that disappears.
   - *Real*: A's window ended exactly at the cut, so the documented "10 ms declick" multiplied frames that were
     not there and both the dry path and the delay's input ended in a step. The step came back out of the delay
     line one delay period later as a click (`clicks = 1` on the 140 BPM pair once the ride made the tail loud
     enough to cross the threshold). The window now runs `CUT_FADE_FRAMES` past the cut and the delay is fed
     post-fader.
   - *Metric*: `Signals.onsetFrames` started at block `lookBack`, so a buffer that begins at an attack had no
     onset at frame 0 — and a strategy's B window begins exactly on B's entry downbeat. The one onset every
     render is built around was invisible, and the level check scored it as an artifact. "No history" now means
     silence, so a buffer that starts above the floor starts with an onset.
   Regression tests: `EchoOutStrategyTest.theTailIsStillThereWhenBEnters`,
   `ArtifactMetricsTest.aBufferThatStartsLoudStartsWithAnOnset`.

3. **Zero-length body segment.** Worse than recorded: the body was *negative* and silently clamped, so the
   program played t126Am's 13.9 s mark and then immediately its 5.6 s mark — an eight-second jump backwards
   inside a song the listener never heard on its own. The two transitions around a track are planned
   independently and nothing reconciled them. `DefaultProgramBuilder` now guarantees every track at least
   `minBodyFrames` = one bar of its own tempo, and never more than half of what the track has to give (so a
   four-second interlude may still have a transition on each side). When a body would be shorter, the *outgoing*
   render is dropped — it is the causally later decision, and in the player the incoming transition is already
   installed by the time the next one is planned — and if that is still not enough, the incoming one goes too.
   Regression test: `DefaultProgramBuilderTest.aTrackIsNeverScheduledWithoutRoomToPlay`.

4. **Beat alignment on non-beat-domain strategies** (60–85 ms FAIL → metric absent). A metric bug. The lane id
   `masterBeat` was being used for two different things: the grid both decks are locked to, and "A's beats, for
   the ruler". Measuring the render's onsets against A's grid past the point where A stops governing the audio
   measures the echo's repeats or B's unrelated tempo. The five non-beat-domain strategies now publish `beatsA`
   and the metric is simply not computed for them; `ArtifactMetrics.MASTER_BEAT_LANE` says so in its KDoc.

5. **`seamIdentity` on `phraseCut`** (WARN 3e-4 → PASS 0). Never actually `0` — that was the CLI printing
   0.0003 to three decimals. The real cause was a disagreement between two constants: `RenderReports` restored a
   guard region only when the limiter had moved it by more than `GUARD_TOLERANCE = 1e-3`, while the metric warns
   at 1e-4 and fails at 1e-3. A render whose loud side sits inside the post-roll — a hard cut straight into B's
   downbeat — had the limiter shaving ~3e-4 off B's verbatim frames, which `finalize` considered untouched and
   the metric flagged for ever. `GUARD_TOLERANCE` is now 1e-6 (float noise), so any real gain reduction in a
   guard is restored. Every strategy in `ab --all` now reports `seamIdentity = 0`.

6. **`loudnessSmoothness`.** Re-derived from the measurement rather than adjusted. It does *not* warn on nearly
   every transition: on the 120→126 pair every strategy that blends is inside the 3 LU/s² budget (crossfade 1.71,
   outroIntroMinimal 0.35, filterSweep 1.67, beatMatchedBlend 2.75, bassSwap 2.89), and the ones above it are
   the ones whose musical content *is* a step (phraseCut 4.2, spectralFreezeBridge 4.1, ambientBridge 4.8,
   stemSwap 4.9, loopRollRiser 6.5, echoOut 9.5, brakeStop 178). So 3 LU/s² is the right budget for a fade and is
   not a defect threshold for a cut — a step has unbounded curvature however cleanly it is executed. Raising the
   number until `brakeStop` passes would only stop it catching the thing it exists for. The budget stays, the
   metric stays WARN-only with no FAIL level, and the KDoc now says it is read against the strategy that
   produced it.

## Known failures on the fixture set

These are real, reproducible, and open. They are recorded here rather than hidden because the whole point of the
metrics is to make transition quality measurable while it is tuned.

1. **`levelJumpDb` 10.6 dB on `echoOut` t140Fs → t63G** (FAIL, threshold 6). Over-reporting, and not fixed. The
   flagged boundary is at output frame 74 970 — 107 ms *before* the cut, inside the dry pre-roll, where the
   render is A verbatim. t140Fs drops about 10 dB between its beats and the metric measures that drop, because
   its "explained by the source" rule excuses a step that sits on a source *onset* and has nothing to say about
   a source's note *end*. (The same material one delay period later, inside the echo, is flagged for the same
   reason once the boundary before it is excused.) The obvious symmetric fix — a transient detector that reports
   falls as well as rises — was tried and reverted: on percussive material a fall is true after almost every
   note, the exclusion set covered nearly every 20 ms block, and the check went blind (a deliberately injected
   6 dB step stopped being detected). Doing this properly means comparing the render's step profile against the
   sources' own, not against a set of frames. The same limit puts the whole-program `muisc check` at 6.98 dB,
   where there are no sources at all to excuse the tracks' dynamics.

2. **`beatAlignmentMaxMs` on beat-domain strategies** (20–97 ms against a 12 ms budget) — newly visible now that
   the metric is scoped to the renders it means something for. The medians are excellent (beatMatchedBlend 1.63,
   harmonicBlend 1.97, stemSwap 0.62, drumBreakBridge 0.73), so the decks *are* locked; the maximum is dominated
   by master beats that the render does not articulate at all, which the 100 ms search window then pairs with a
   neighbouring event. A 97 ms outlier next to a 1.6 ms median is not a timing error. The statistic needs to be
   robust (a high percentile, or "beats the render actually articulates") before the max is worth believing.
   `bassSwap` is the one to look at first: its median is 14.3 ms, which is a real offset, not an outlier.

3. **`levelJumpDb` 24–29 dB on the long beat-domain blends** (`beatMatchedBlend`, `bassSwap`, `stemSwap`,
   `drumBreakBridge`, `harmonicBlend`). Unchanged from before and not investigated in this pass.

4. **`truePeakDbtp` above -0.5 dBTP on most strategies** (-0.26 to -0.64). The fixtures themselves peak at
   +1.1 dBTP, the CLI's default prefs apply no deck gain, and the splice contract requires the guard regions to
   be the source verbatim — so the limiter is not allowed to bring those frames down, and the metric measures the
   whole render. The player's own master limiter handles this at playback; the metric and the contract disagree
   about who owns the guard.

5. **`tailContainedDb` -41.2 dB on `echoOut` t120C → t126Am** (WARN, fails at -40). The echo's release ramp ends
   at `cut + tail`, which is only `GUARD_FRAMES` (93 ms) before the end of the segment, while the containment
   window is 100 ms — so the measurement always overlaps the last few milliseconds of the release.

6. **`stereoCorrelationMin` -0.35 on `echoOut` t140Fs → t63G**, -0.60 to -0.72 on `drumBreakBridge` and
   `brakeStop`. Not investigated.

## Honest limits

- The fixtures are synthetic. They have exact grids and clean spectra, so they flatter beat tracking and key
  detection; real music will be harder in ways this corpus cannot show.
- Pseudo-stems are signal processing, not source separation. `stemSwap` with them behaves like a staggered
  three-band EQ mix, and is scored accordingly.
- "Every song into every song" is guaranteed only as "a plan always exists and the render is checked". For a
  genuinely incompatible pair that plan is an echo-out, an ambient bridge or a crossfade.
- Several numbers above are measurements of the *metric*, not of the audio. Where that is the case it is said so
  explicitly; where a number is still unexplained it is in the "known failures" list rather than in "fixed".
