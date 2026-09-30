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

These are numbers for the synthetic fixtures only. To score the planner's picks on your own music use
`muisc eval`; for the golden-render regression and the analysis accuracy bench, see `docs/TESTING.md`.

## What the metrics mean

| Metric | Meaning | Fails at |
|---|---|---|
| `clicks` | Sample-level discontinuities not explained by an onset in either source | any |
| `levelJumpDb` | Short-term RMS jump not explained by a source onset | 6 dB |
| `truePeakDbtp` | Inter-sample peak of the render | above -0.5 dBTP |
| `seamIdentity` | The render's first/last frames must equal the deck-gained source samples | mismatch |
| `beatAlignmentMs` / `beatAlignmentP90Ms` | Per master beat, the render's attack against the sources' attacks their beat grids put there, over the beats that have one — **beat-domain renders only** (see below and fixed item 9) | median: WARN 5 ms / 90th percentile: WARN 5 ms, FAIL 12 ms |
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
   outroIntroMinimal 0.35, filterSweep 1.67, beatMatchedBlend 2.75, bassSwap 2.89, and stemSwap 2.25 since item 7
   — its earlier 4.9 was the 1.5 s hole item 7 removed), and the ones above it are
   the ones whose musical content *is* a step (phraseCut 4.2, spectralFreezeBridge 4.1, ambientBridge 4.8,
   loopRollRiser 6.5, echoOut 9.5, brakeStop 178). So 3 LU/s² is the right budget for a fade and is
   not a defect threshold for a cut — a step has unbounded curvature however cleanly it is executed. Raising the
   number until `brakeStop` passes would only stop it catching the thing it exists for. The budget stays, the
   metric stays WARN-only with no FAIL level, and the KDoc now says it is read against the strategy that
   produced it.

7. **Level jumps on the beat-domain blends** (24–29 dB → 5.4–7.0 dB on `beatMatchedBlend`, `bassSwap`,
   `stemSwap` and `harmonicBlend`; `drumBreakBridge` 25.6 / 27.4 → 14.4 / 10.6, which is known failure 3). One
   metric defect shared by all five, and one real defect in two of them. Measured on `t120C → t126Am` (`ab --all`)
   and on `t126_Am → t120_C` (`muisc synth --bpm 126 --key Am` and `--bpm 120 --key C`, then `render --strategy`);
   the planner attaches `tempoGlide` and `textureCarry` to every one of these except `drumBreakBridge` on the
   first pair.
   - *Metric*: every flagged boundary was the kick on a master beat. On `harmonicBlend t126_Am → t120_C` the worst
     (24.47 dB at 19.380 s, master beat 40) rises from -31…-40 dB to -5…-7 dB; both sources rise 33–38 dB at the
     same beat position (A -37 → -4.5 dB, B -42 → -3.9 dB) and the render stays within 2.5 dB of the gain-weighted
     sources from 60 ms before the beat to 200 ms after it. The "explained by a source onset" rule placed the sources' onsets with the splice
     contract's constant offsets, i.e. at ratio 1.0, but a beat-domain render stretches its decks onto the master
     grid for 20–35 s: at the six worst boundaries the mapped A onsets were 59–308 ms and the B onsets 44–271 ms
     from where the render plays them, outside the 40 ms guard. Renders that publish `masterBeat` now map both
     sources through their beat grids (`ArtifactMetrics.MasterBeatMap`: A's beat at the end of the pre-roll on
     master beat 0, B's at the start of the post-roll on master beat K, one matched beat per master beat). On the
     regression test's `beatMatchedBlend` (the metrics fixtures' 120 → 126 BPM pair, 20.23 → 5.95 dB) every kick
     the render plays on a master beat then lies within 2.3 ms of a mapped onset. The check did not go blind: on
     that render it excuses fewer boundaries than before (1195 of 2791 against 1367), and of 477 injected
     6 dB steps (every 50 ms over the stretched body) 84 now read WARN against 78 before; with the old mapping a real
     6 dB step there did not move the reported maximum at all (20.23 dB with and without it).
   - *Real*: `stemSwap` and `drumBreakBridge` build their own master grid, and their windows, B's entry and every
     swap are sized in beats of their body (`geom.bodyBeats`). Under `tempoGlide` they rendered on a grid of
     `glideBars + holdBars` bars whatever the body was, because the modifier re-derived the frames only for plans
     with `BeatDomain` geometry. `stemSwap t120C → t126Am` ran a 48-beat grid over a 44-beat body: B's deck ran
     past the end of its decoded window and from master beat 44.75 the render fell to -54…-86 dB for 1.5 s before
     the post-roll (`silenceGapMs` 601), 6.25 % longer than planned — its 29.08 dB was the drop into that hole, not
     a kick. `drumBreakBridge t126_Am → t120_C` ran 72 beats over 52: from master beat 53 to 68 (about 7.5 s) both
     decks were silent and only the `textureCarry` bed played, at about -41 dB, then it faded to -80 dB before the
     post-roll; 41.6 % longer than planned. A grid shorter than the body failed the other way: `stemSwap
     t126_Am → t120_C` ran 72 beats over 76, the render ended 2.8 % short and its last master beats played B's
     beats 73–74 straight into a post-roll that starts at B's beat 79 — a bar of B skipped at the seam. The modifier
     now fits the glide inside the body
     (`preBars + glideBars + holdBars` = the body's bars) and only `expectedOutputFrames` follows the new grid:
     `lengthErrorPct` 0 and `silenceGapMs` 0 on all three affected renders, `tailContainedDb` -59.5 → -97.0 on
     `stemSwap t120C → t126Am`. Its `beatAlignmentMaxMs` moved from 20.05 to 30.14 ms with the new render (median
     0.62 → 0.60; see known failure 2).
   Regression tests: `ArtifactMetricsTest.beatDomainSourceOnsetsLandOnTheKicksTheRenderPlays`,
   `ArtifactMetricsTest.aLevelStepInsideABeatDomainBlendIsStillDetected`,
   `TempoGlideModifierTest.strategiesWithTheirOwnGridKeepTheirBodyUnderAGlide`.

8. **`bassSwap` and the recipes' EQ delayed both decks by 2.5 ms** (known failure 2's "real offset"). A real
   defect, smaller than the 14.3 ms median said, and the rest of that median was the metric (item 9).
   - *Which deck, how much, constant or drifting*: both, equally, constant. On the `BassSwapStrategyTest` pair
     (120 → 126 BPM, defaults) the render of each deck alone lagged the same deck rendered by `PhaseLockedDeck`
     alone by 101–115 frames (2.3–2.6 ms, cross-correlation of 50 ms windows at every master beat): A 110–115 on
     master beats 0–31, B 101–113 on master beats 0–47, no drift over the overlap. A and B were therefore never
     apart; each was late against the master grid and against the dry pre-roll and post-roll it is spliced
     between. The phase-locked decks, the stretcher and the grid were not involved: the same decks without the
     EQ are what the new test compares against, and `beatMatchedBlend` on the same layout reads 0.18 ms median /
     0.50 ms 90th percentile on `t120C → t126Am` (item 9's metric).
   - *Cause*: the 3-band split was `dsp`'s causal `MultibandCrossover`, whose bands sum to
     `AP(lowHz) AP(highHz) x`, an all-pass: flat in magnitude, but a 2nd-order all-pass at 200 Hz has 2.25 ms of
     group delay at and below its corner, which is where a kick lives. So the EQ delayed the whole deck even with
     every band at unity, and rotated each kick's attack into a ramp. The recipes' 3-band EQ
     (`RecipeRenderer.equalise`, used by `smooth-blend`, `club-bass-swap`, `long-glide`, `radio-segue` and any
     recipe that automates `low`/`mid`/`high`) was the same crossover.
   - *Fix*: a new `dsp` `ZeroPhaseCrossover`: per split, the Butterworth section of the LR4 low-pass run forward
     and backward over the whole deck (the same zero-phase design `PseudoStemSeparator` already uses), and the
     rest by subtraction. Same band magnitudes as before, no phase, and the bands sum back to the deck, so with
     equal band gains the render is the phase-locked deck sample for sample (max difference < 1e-4 in the test).
     Price: a band gained differently from the others starts rising ~2 ms before its transient (the 200 Hz low
     band's impulse response is above 10 % of its peak for 2.1 ms either side). `bassSwap` no longer warms its
     crossover on the dry pre-roll: with the sum exact there is no start-up transient to hide where the lanes
     agree. The real-time fallback (`LiveGraph`, and `LiveOffline` which mirrors it) keeps the causal
     crossover, as it must; nothing there was changed.
   - *Measured* (`ab --all`, old metric, before → after this fix alone), median / max beat alignment:
     `bassSwap` 14.29 / 72.19 → 12.95 / 97.39 on `t120C → t126Am` and 10.73 / 73.77 → 1.06 / 53.31 on
     `t126_Am → t120_C`; `smooth-blend` 19.63 → 1.79 and 4.07 → 1.09 (medians), `club-bass-swap` 22.09 → 11.09 and
     6.98 → 1.11, `long-glide` 14.65 → 2.10 and 4.01 → 1.05. What the old metric still read on `bassSwap
     t120C → t126Am` is item 9. Other metrics this moved, base → final build on the same pairs: `tailContainedDb`
     -16.89 → -152.18 dB (`bassSwap`, `t120C → t126Am`) and -55.20 → -114.85 dB (`t126_Am → t120_C`), -17.35 →
     -36.71 dB on the three EQ recipes on `t120C → t126Am` - the last 100 ms of a render are B's rendered deck
     blending into dry B, and an all-pass copy of B is not B; `levelJumpDb` see known failure 3; `truePeakDbtp`
     -0.81 → -0.58 dBTP on `bassSwap` and the EQ recipes on `t120C → t126Am` (both WARN).
   Regression tests: `BassSwapStrategyTest.theEqMixKeepsBothDecksOnTheirPhaseLockedTiming` (red before: A lagged
   its phase-locked deck by 85-115 frames at every beat), `RecipeRenderAutomationTest.anEqBackAtUnityIsTransparent`
   (red before: 0.74 max difference once every band is back at 0 dB), `ZeroPhaseCrossoverTest`,
   `BeatAlignmentTest.bassSwapIsAsWellAlignedAsTheDryBlend` (red before: 7.21 ms median against 0.87 for
   `beatMatchedBlend`). `BassSwapStrategyTest.renderIsCleanDeterministicAndBeatLocked` now holds `bassSwap`'s
   kicks to `beatMatchedBlend`'s budgets (2 ms median, 3 ms tolerance) instead of the 6 ms that allowed for the
   group delay.

9. **`beatAlignment*` measured the detector, not the render** (metric change: `beatAlignmentMaxMs` is replaced by
   `beatAlignmentP90Ms`; thresholds unchanged). Three defects, all in how the render's onsets were found and
   counted:
   - A 1 ms RMS difference of a 45-155 Hz kick or bassline rises with every half-cycle of the waveform, and the
     detector kept the strongest rise within 50 ms. On `bassSwap t120C → t126Am` (base build) one beat of 49 read
     the attack (0.5 ms); 13 read 2.9-3.3 ms and 19 sat in clusters at 6.5, 9.8-10.8, 13.7-14.9, 18.4-19.2 and
     21.4-22.1 ms - the synthetic kick is a 155 → 45 Hz chirp whose zero crossings fall 3.3, 6.9, 10.7, 14.8, 19.2
     and 24.0 ms after it starts - and of the other 16 (24-72 ms), 14 were from master beat 28 on, where A is in
     its drumless outro with B's kick still EQ'd out, and then B in its own. The attack function is now the rise of
     the *analytic envelope* (no waveform ripple) above its own maximum over the previous 20 ms (kick, bass and
     pads beat against each other - within a beat of `t126_Am`'s own source the envelope dips by 6 dB and
     recovers - and a recovery is not an attack), on 1 ms blocks.
   - Replacement chained: a stronger peak within 50 ms replaced the onset and restarted the 50 ms window, so a run
     of ever-stronger peaks could carry one onset far from its attack (on `t126_Am → t120_C` master beat 2 read
     79.7 ms with the analytic envelope and the old grouping; its attack is at +2 ms). Each master beat now takes
     the strongest attack within ±50 ms of itself.
   - Every beat counted, including beats nobody plays an attack on (an outro of pads and bass; a deck whose kick
     is EQ'd out), which the 100 ms window paired with whatever was nearest. A beat now counts only when the render
     has an attack of at least 10 % of its strongest within ±50 ms *and* a source has one there too; with the
     sources the error is the distance from the render's attack to the nearest source attack, mapped through the
     beat grids. So the metric measures whether the render plays the decks' attacks where the grids put them; an
     attack a source has off its own grid is compared with itself (grid accuracy is `muisc bench analysis`'s
     job, and a grid error is not caught here).
   The maximum became the 90th percentile (nearest rank): a mix has attacks neither source has where two decks'
   material sums - on `bassSwap t120C → t126Am` one beat's strongest rise is 36 ms after the beat and 33.5 ms from
   the nearest source attack; on `stemSwap t120C → t63G` three beats of 45 read 39-47 ms that way - and a few such
   beats are not a timing error, while a deck that is off is off on many beats. It did not go blind:
   `BeatAlignmentTest.oneDeckPlayedLateStillFails` plays B (or A) 15 and 20 ms late in real `beatMatchedBlend`
   and `bassSwap` renders, sources and plan untouched, and the 90th percentile FAILs every time (B late: 15 / 20
   ms read to within 1.5 ms); the same renders on time PASS. With the median in place of the percentile, or with
   beats more than 12 ms from any source attack dropped, that test goes red (both tried). Measured on the
   fixture pairs, base → this build (median / max before, median / 90th percentile after; `ab --all`):

   | strategy | t120C → t126Am | t126_Am → t120_C | t120C → t63G |
   |---|---|---|---|
   | `beatMatchedBlend` | 1.63 / 97.39 → 0.18 / 0.50 | 0.98 / 37.20 → 0.08 / 0.22 | 22.06 / 87.01 → 0.20 / 1.50 |
   | `bassSwap` | 14.29 / 72.19 → 0.21 / 2.29 | 10.73 / 73.77 → 0.09 / 0.22 | 23.42 / 99.77 → 0.19 / 7.30 |
   | `stemSwap` | 0.60 / 30.14 → 0.08 / 0.28 | 1.02 / 37.37 → 0.09 / 0.23 | 4.19 / 68.73 → 0.18 / 1.56 |
   | `drumBreakBridge` | 0.73 / 67.26 → 0.07 / 0.18 | 1.08 / 20.74 → 0.08 / 0.25 | 2.52 / 67.26 → 0.09 / 0.42 |
   | `harmonicBlend` | 1.97 / 97.39 → 0.14 / 0.36 | 1.01 / 16.76 → 0.07 / 0.23 | 24.31 / 87.01 → 0.26 / 1.50 |
   | `recipe:smooth-blend` | 19.63 / 74.73 → 0.13 / 0.43 | 4.07 / 73.80 → 0.08 / 0.21 | 22.96 / 99.52 → 0.18 / 1.57 |
   | `recipe:club-bass-swap` | 22.09 / 74.73 → 0.14 / 0.40 | 6.98 / 66.23 → 0.08 / 0.21 | 20.20 / 99.52 → 0.16 / 3.49 |
   | `recipe:drums-first` | 1.82 / 87.14 → 0.10 / 0.46 | 1.08 / 36.94 → 0.10 / 0.21 | 14.72 / 80.73 → 0.26 / 4.22 |
   | `recipe:long-glide` | 14.65 / 64.38 → 0.06 / 0.30 | 4.01 / 93.04 → 0.07 / 0.20 | 20.09 / 96.03 → 0.22 / 2.20 |
   | `recipe:tension-build` | 10.14 / 87.14 → 0.14 / 0.59 | 2.83 / 36.83 → 0.14 / 0.35 | 4.47 / 82.81 → 0.18 / 0.59 |

   `t126_Am → t120_C` is `muisc synth --bpm 126 --key Am` into `--bpm 120 --key C`. The t120C → t63G column is
   new here. Regression tests: `BeatAlignmentTest.oneDeckPlayedLateStillFails`,
   `BeatAlignmentTest.beatsTheRenderDoesNotArticulateAreNotCounted` (red before: four beats 70 ms from any click
   read as 70.3 ms), `AttacksTest`.

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

2. **`beatAlignmentP90Ms` 7.30 ms on `bassSwap t120C → t63G`** (WARN; every other beat-domain render of the three
   fixture pairs PASSes, see fixed items 8 and 9). Median 0.19 ms: no deck is off. The percentile is scattered
   single beats where the render's strongest attack is 7–31 ms from the nearest source attack (6 of 52 counted
   beats at 7.3 ms or more: 7.3, 7.9, 9.3, 13.3, 28.6, 30.8). In the golden corpus (`GoldenCorpusTest`, its own
   seeds and prefs) `stemSwap t120C_t63G` reads 9.69 ms (WARN). Neither was investigated beyond that.

3. **`levelJumpDb` 5.4–7.0 dB on the beat-domain blends, 10.6–14.4 dB on `drumBreakBridge`** (FAIL at 6). What
   is left once fixed item 7 excuses the kicks and removes the glide's holes, all of it the sources' own dynamics:

   | strategy | t120C → t126Am before | after | t126_Am → t120_C before | after |
   |---|---|---|---|---|
   | `beatMatchedBlend` | 24.25 | 6.93 | 24.31 | 7.01 |
   | `bassSwap` | 24.10 | 6.76 (6.93 since fixed item 8) | 23.00 | 5.44 (6.70 since fixed item 8) |
   | `stemSwap` | 29.08 | 5.56 | 25.62 | 6.84 |
   | `drumBreakBridge` | 25.59 | 14.38 | 27.44 | 10.60 |
   | `harmonicBlend` | 27.48 | 6.93 | 24.47 | 6.29 |

   - On the four blends the worst boundary of each of the eight renders is a step in the sources' own material
     that is no onset by the detector's definition, and the render follows the source through it (0–2 dB apart,
     mostly a constant gain offset): a note end in five (e.g. `harmonicBlend t126_Am → t120_C` at 30.00 s, B
     alone: B -14.5 → -21.4 dB, render -15.4 → -22.5 dB), a rise spread over more than one 3 ms detector block in
     three (e.g. `beatMatchedBlend` and `harmonicBlend t120C → t126Am` at 22.48 s, where the render is B at
     correlation 1.000: B -23.2 → -16.3 dB, render -24.0 → -17.1 dB). On `bassSwap t120C → t126Am` (18.68 s) B's
     own rise reads 6.0 dB by the same statistic and the render 6.76 dB: the difference is a 2 ms dip 8 ms before
     the attack, while B is still being stretched. That is known failure 1's mechanism, and it is not fixed here.
   - On `drumBreakBridge` the flagged rises sit on the off-beats of the drum solo (master beats x.49), where A's
     drum stem plays alone, high-passed (the worst on `t120C → t126Am`, at master beat 32.49, is half a beat into
     the one-bar drum handover, A's drums at 0.98 and B's at 0.19); no gain lane steps at any of them. A's source
     has a hi-hat attack at each — above 4 kHz it rises from -56…-80 dB to -19…-23 dB — but in its full mix,
     where pads and bass sit at -17…-23 dB, the onset detector finds none: the nearest onset of the full mix is
     62–249 ms away. The rule takes its onsets from the sources' full mixes, so an attack that a soloed stem
     exposes has nothing to excuse it. Not an audio defect; not fixed (onsets per stem would need a stem
     separation in the install gate).
   - The beat-domain recipes on `t120C → t126Am` share the metric fix: `smooth-blend` and `club-bass-swap` 9.64 →
     7.22, `drums-first` 17.21 → 11.93, `long-glide` 19.21 → 5.39, `tension-build` 10.06 → 8.18. What remains on
     them has not been investigated.
   - The zero-phase EQ of fixed item 8 moved this metric on the renders that use it, both ways (`ab --all`, base →
     after): `bassSwap` 6.76 → 6.93 (`t120C → t126Am`) and 5.44 → 6.70 (`t126_Am → t120_C`, WARN → FAIL);
     `smooth-blend` and `club-bass-swap` 7.53 → 8.16 and 4.60/4.66 → 8.38 (`t120C → t63G`, WARN → FAIL);
     `long-glide` 8.18 → 8.63, 5.60 → 6.18 (WARN → FAIL) and 7.09 → 4.89. The two looked at are steps both renders
     have, read differently: on `bassSwap t126_Am → t120_C` (30.00 s) a fall reads -15.8, -23.2, -25.9, -27.9 dB in
     consecutive 10 ms windows, where the base render read -15.8, -20.2, -24.5, -26.9;
     on `smooth-blend t120C → t63G` (34.96 s) a rise from -24 dB to -15 dB was followed in the base render by a
     ±3 dB 10 ms ripple (-17.9, -13.5, -19.0, -13.9, …) whose low points held the "post" level of the statistic
     down, and is followed now by a steady -14.7…-17.3 dB. Neither step is excused as a source onset; that is this
     item's and known failure 1's mechanism, and it is not fixed here. (7.53 is what the base build measures for
     `smooth-blend t120C → t126Am` today, not the 7.22 above.)
   - The Lab's 29 dB on `stemSwap` at 34–64 overlap bars could not be reproduced as a long overlap: on the 32- and
     96-bar synthetic 126 → 120 pairs `stemSwap` shortens any `overlapBars` from 34 to 64 to 19 bars ("A has 19
     bars after its phrase start"), and there it measures 25.6 → 6.8 dB; the 29.08 dB on `t120C → t126Am` was the
     silence of fixed item 7.

4. **`truePeakDbtp` above -0.5 dBTP on most strategies** (-0.26 to -0.64; `bassSwap` and the EQ recipes on
   `t120C → t126Am` read -0.58 since fixed item 8, -0.81 before). The fixtures themselves peak at
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
