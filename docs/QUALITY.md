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

and, for the other two pairs the sections below use:

```
muisc synth --bpm 126 --key Am --out pair/
muisc synth --bpm 120 --key C --out pair/
muisc ab pair/t126_Am_32bars_i4o4_s0_44100.wav pair/t120_C_32bars_i4o4_s0_44100.wav --all -o ab2/
muisc ab fixtures/t120C.wav fixtures/t63G.wav --all -o ab3/
```

Every number below was measured on the code of commit 4c11164, each command with a fresh analysis cache and profile
(`--cache-dir`, `--profile-dir`). A "before" value is the one recorded when that item was fixed and is kept as
history; it was not measured again. A few diagnostic numbers (where in a render a metric's worst reading sits, the
per-beat errors, the late-deck injections in known failure 2) come from a print-only probe added to a local copy of
`muisc render` and `muisc check` for this measurement, which is not part of the repository; each is labelled
"(probe)". Where a change is credited with moving a number, the same command was run on the commit before it and on
the commit itself.

These are numbers for the synthetic fixtures only. To score the planner's picks on your own music use
`muisc eval`; for the golden-render regression and the analysis accuracy bench, see `docs/TESTING.md`.

## What the metrics mean

| Metric | Meaning | Fails at |
|---|---|---|
| `clicks` | Sample-level discontinuities not explained by an onset in either source | any |
| `levelJumpDb` | Short-term RMS jump not explained by a source onset | 6 dB |
| `truePeakDbtp` | Inter-sample peak of the render | above -0.5 dBTP |
| `clipping` | Samples at or beyond full scale (magnitude 1.0 or more) | any |
| `dcOffsetDb` | Largest per-channel DC offset | above -50 dBFS |
| `seamIdentity` | The render's first/last frames must equal the deck-gained source samples | mismatch |
| `beatAlignmentMs` / `beatAlignmentP90Ms` | Per master beat, the render's attack against the sources' attacks their beat grids put there, over the beats that have one, and with sources also its attacks above 2 kHz — **beat-domain renders only** (see below and fixed item 9) | median: WARN 5 ms / 90th percentile: WARN 5 ms, FAIL 12 ms |
| `loudnessSmoothness` | Second derivative of short-term loudness; WARN only, never a FAIL | 3 LU/s² |
| `stereoCorrelationMin` | Lowest left/right correlation over 400 ms windows; WARN only, never a FAIL | -0.3 |
| `tailContainedDb` | Effect tails must not spill past the segment | audible spill (-40 dB) |

`beatAlignment*` is produced only when the plan publishes a `masterBeat` lane, which is a strategy's statement
that both decks are slaved to a `MasterGrid`. `echoOut`, `phraseCut`, `filterSweep`, `brakeStop` and
`loopRollRiser` never stretch a deck — B runs at its own tempo — so they publish their A-beat ruler as `beatsA`
(informational, for the Lab) and have no beat-alignment metric at all.

## The four-track shuffle mix

`t120C → t126Am → t140Fs → t63G`, `--context shuffle`, through the real `ProgramPlayer`:

| # | transition | strategy | verdict | worst metrics |
|---|---|---|---|---|
| 1 | t120C → t126Am | `echoOut` | FAIL | `tailContainedDb` -39.12, `levelJumpDb` 4.70, `loudnessSmoothness` 11.19 |
| 2 | t126Am → t140Fs | `phraseCut` | WARN | `levelJumpDb` 5.49, `loudnessSmoothness` 17.15 |
| 3 | t140Fs → t63G | `echoOut` | FAIL | `levelJumpDb` 10.69, `loudnessSmoothness` 6.12, `stereoCorrelationMin` -0.40 |

Program output: 7 segments, 6 seams, 1:55.43. `clicks = 0`, `levelJumpDb = 2.78` (PASS), `truePeakDbtp = -0.94`
(WARN). `muisc check set.wav` over the whole set, with no sources to excuse anything, also reports `clicks = 0`. Its
`levelJumpDb` is 6.92 (FAIL), at 110.10 s (probe), inside t63G's body at t63G's own 55.24 s: `muisc check
fixtures/t63G.wav` reads 6.78 dB at that same place, a rise in t63G's outro with no onset within 1.8 s, and without
sources nothing excuses a track's own dynamics (known failure 9). The same command also WARNs
`loudnessSmoothness` 11.93 and `stereoCorrelationMin` -0.67; where in the set either sits was not probed.

All three transitions are the planner's favourites: `mix` prints no `room:` and no `dropped` line, and t126Am plays
6.90 s on its own between them (its frames 699 714 to 1 003 920; `phraseCut` cuts on t126Am's phrase start 51,
24.76 s). That is the analyser's doing, not the room-aware order's. With analysis version 2 the analyser put
t126Am's `mixOutBeat` at beat 16 (7.62 s), at the start of its DROP section, and `phraseCut`, which cuts at the
first phrase start at or after it, cut there: its segment left t126Am at frame 247 998, before the `echoOut` had
handed the song over at frame 615 804, so the program builder dropped it (see "fixed", item 3). At edf5f67, the
parent of the analysis-version-3 commit 4a06022, this command plays 6 segments, two of the three transitions, and
t126Am into t140Fs body to body. Analysis version 3 puts `mixOutBeat` at beat 27 (13.33 s), and from 4a06022 on
the command plays all three. The set is byte-identical at 4a06022, at c31ca43 (room-aware order) and its parent,
at 6e03aed (analysis version 4) and its parent, at f0ea2bf and at 4c11164. The same analyser change moved
transition 1's `tailContainedDb` from -41.23 (WARN) at edf5f67 to -39.12 (FAIL) at 4a06022 (known failure 5).

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

2. **Level jumps through `echoOut`** (34.9 → 4.70 dB on pair 1, 27.0 → 10.69 dB on pair 3; pair 1 is inside the
   6 dB budget, pair 3 is not — see known failure 1). Two real defects and one metric defect.
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
   Dropping made short songs lose their transitions: a 30 s song between two others could lose one side and
   played into the next with a hard cut. `mix` and the `TransitionCoordinator` now order the candidates with
   `DefaultProgramBuilder.roomOrder` before trying them: first the ones that leave A its body after the
   transition into it and B its body before one of the plans of B's own next transition, then the ones that keep
   both bodies but leave B's next transition no room, then the ones that would starve A or B (alike). The
   planner's order is kept within each group; a pinned candidate (a stored pin, a `--preset` session pin, the
   app's one-off pick) stays first unless it would starve a track; and when every candidate starves one, the
   planner's order comes back unchanged. `mix` hands a leg that still starves a track to the builder without a
   render, reports it `dropped`, and plans the next pair as if no transition came before it. The coordinator
   judges B's next transition by what it will actually play (its installed or ready render, else the candidates
   it would try), reuses a retained render only when it leaves A its body, never renders a candidate that would
   starve a track (the pair plays body to body, state "No room for a transition"), and drops a render or live
   plan out of B that a newly installed transition into B would leave without its body. Measured with `mix`
   (playlist context) on the same files at c31ca43 and at its parent, both on analysis version 3, kept / planned
   transitions: a 15 s song between two 3-minute ones 1/2 → 2/2, five short songs 2/4 → 4/4, a 30 s and a 24 s
   song between a 2½-minute and a 3¾-minute one 0/3 → 3/3; two sets of four 2½–4-minute songs are byte-identical.
   The fixture shuffle mix above is 3/3 before and after; it got its third transition from analysis version 3,
   not from this change. At 4c11164 the three short-song sets still play 2/2, 4/4 and 3/3. Regression tests:
   `CliSmokeTest."mix keeps the transitions on both sides of a short track"`,
   `CliSmokeTest."mix plays a pinned transition that costs only the next transition"`,
   `CliSmokeTest."mix drops only the leg that cannot fit, not the one after it"`,
   `TransitionCoordinatorTest.aShortMiddleTrackKeepsItsBodyAndBothTransitions`.

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
   guard is restored. In `ab --all` on the three pairs `phraseCut` reads 0 and every strategy PASSes; the largest
   reading is 4.5e-7 (`recipe:reverb-freeze-bridge` on t120C → t126Am).

6. **`loudnessSmoothness`.** Re-derived from the measurement rather than adjusted. On the 120→126 pair it WARNs
   on 18 of the 23 renders of `ab --all`. Inside the 3 LU/s² budget: crossfade 1.21, outroIntroMinimal 1.12,
   filterSweep 1.73, stemSwap 1.47 (its 4.9 before fixed item 7 was the 1.5 s hole item 7 removed) and
   `recipe:reverb-freeze-bridge` 2.55. Above it and not investigated: beatMatchedBlend, bassSwap and harmonicBlend
   3.26, drumBreakBridge 4.44, and the recipes `filter-handoff` 3.25, `smooth-blend` and `drums-first` 3.26,
   `long-glide` 3.32, `echo-wash` 3.90, `club-bass-swap` 4.02, `tension-build` 5.50 and `radio-segue` 6.04. Above
   it, the ones whose musical content *is* a step: phraseCut 4.72, spectralFreezeBridge 4.65, ambientBridge 4.09,
   loopRollRiser 6.10, echoOut 11.19, brakeStop 183.08. So 3 LU/s² is a budget for a fade and is not a defect
   threshold for a cut — a step has unbounded curvature however cleanly it is executed. Raising the number until
   `brakeStop` passes would only stop it catching the thing it exists for. The budget stays, the metric stays
   WARN-only with no FAIL level, and the KDoc now says it is read against the strategy that produced it.

7. **Level jumps on the beat-domain blends** (24–29 dB → 5.37–9.99 dB on `beatMatchedBlend`, `bassSwap`,
   `stemSwap` and `harmonicBlend`; `drumBreakBridge` 25.6 / 27.4 → 13.88 / 10.49, which is known failure 3). One
   metric defect shared by all five, and one real defect in two of them. Measured on `t120C → t126Am` (`ab --all`)
   and on `t126_Am → t120_C` (`muisc synth --bpm 126 --key Am` and `--bpm 120 --key C`, then `ab --all`); the
   planner attaches `tempoGlide` and `textureCarry` to every one of these except `drumBreakBridge` on the first
   pair. The diagnosis below is of the renders before the fix, as recorded then.
   - *Metric*: every flagged boundary was the kick on a master beat. On `harmonicBlend t126_Am → t120_C` the worst
     (24.47 dB at 19.380 s, master beat 40) rises from -31…-40 dB to -5…-7 dB; both sources rise 33–38 dB at the
     same beat position (A -37 → -4.5 dB, B -42 → -3.9 dB) and the render stays within 2.5 dB of the gain-weighted
     sources from 60 ms before the beat to 200 ms after it. The "explained by a source onset" rule placed the sources' onsets with the splice
     contract's constant offsets, i.e. at ratio 1.0, but a beat-domain render stretches its decks onto the master
     grid for 20–35 s: at the six worst boundaries the mapped A onsets were 59–308 ms and the B onsets 44–271 ms
     from where the render plays them, outside the 40 ms guard. Renders that publish `masterBeat` now map both
     sources through their beat grids (`ArtifactMetrics.MasterBeatMap`: A's beat at the end of the pre-roll on
     master beat 0, B's at the start of the post-roll on master beat K, one matched beat per master beat). On the
     regression test's `beatMatchedBlend` (the metrics fixtures' 120 → 126 BPM pair, 20.23 → 5.95 dB, both read
     at 4c11164 (probe) with the old and the new mapping) every kick the render plays on a master beat then lies
     within 2.34 ms of a mapped onset. The check did not go blind: on that render it excuses fewer boundaries than
     the old mapping (1195 of 2791 against 1367), and of 477 injected 6 dB steps (every 50 ms over the stretched
     body) 84 read WARN against 78 with the old mapping; with the old mapping a real 6 dB step there does not
     move the reported maximum at all (20.23 dB with and without it).
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
     `lengthErrorPct` 0 and `silenceGapMs` 0 on all three affected renders at 4c11164, and `tailContainedDb` on
     `stemSwap t120C → t126Am` -59.5 → -97.0 as recorded, -97.03 at edf5f67 and -87.82 from analysis version 3
     (4a06022) on. Its beat alignment at 4c11164 is 0.11 ms median / 0.28 ms 90th percentile (item 9's metric; the
     old metric read a 30.14 ms maximum right after this fix).
   Regression tests: `ArtifactMetricsTest.beatDomainSourceOnsetsLandOnTheKicksTheRenderPlays`,
   `ArtifactMetricsTest.aLevelStepInsideABeatDomainBlendIsStillDetected`,
   `TempoGlideModifierTest.strategiesWithTheirOwnGridKeepTheirBodyUnderAGlide`.

8. **`bassSwap` and the recipes' EQ delayed both decks by 2.5 ms** (the "real offset" of the beat-alignment known
   failure of the time). A real defect, smaller than the 14.3 ms median said, and the rest of that median was the
   metric (item 9).
   - *Which deck, how much, constant or drifting*: both, equally, constant. On the `BassSwapStrategyTest` pair
     (120 → 126 BPM, defaults) the render of each deck alone lagged the same deck rendered by `PhaseLockedDeck`
     alone by 101–115 frames (2.3–2.6 ms, cross-correlation of 50 ms windows at every master beat): A 110–115 on
     master beats 0–31, B 101–113 on master beats 0–47, no drift over the overlap. A and B were therefore never
     apart; each was late against the master grid and against the dry pre-roll and post-roll it is spliced
     between. The phase-locked decks, the stretcher and the grid were not involved: the same decks without the
     EQ are what the new test compares against, and `beatMatchedBlend` on the same layout reads 0.09 ms median /
     0.31 ms 90th percentile on `t120C → t126Am` at 4c11164 (item 9's metric).
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
   - *Measured when the fix landed* (`ab --all`, old metric, before → after this fix alone; that metric no longer
     exists, so these are history), median / max beat alignment:
     `bassSwap` 14.29 / 72.19 → 12.95 / 97.39 on `t120C → t126Am` and 10.73 / 73.77 → 1.06 / 53.31 on
     `t126_Am → t120_C`; `smooth-blend` 19.63 → 1.79 and 4.07 → 1.09 (medians), `club-bass-swap` 22.09 → 11.09 and
     6.98 → 1.11, `long-glide` 14.65 → 2.10 and 4.01 → 1.05. What the old metric still read on `bassSwap
     t120C → t126Am` is item 9. At 4c11164, with item 9's metric, median / 90th percentile: `bassSwap` 0.07 / 0.28
     on `t120C → t126Am` and 0.09 / 0.22 on `t126_Am → t120_C`; the three recipes are in item 9's table. Other
     metrics this moved, before (as recorded) → 4c11164 on the same pairs: `tailContainedDb` -16.89 → -179.69 dB
     (`bassSwap`, `t120C → t126Am`) and -55.20 → -108.38 dB (`t126_Am → t120_C`), -17.35 → -178.48 /
     -178.48 / -173.89 dB on `smooth-blend` / `club-bass-swap` / `long-glide` on `t120C → t126Am` - the last 100 ms
     of a render are B's rendered deck blending into dry B, and an all-pass copy of B is not B; `levelJumpDb` see
     known failure 3; `truePeakDbtp` -0.81 → -0.67 dBTP on `bassSwap` and the EQ recipes on `t120C → t126Am`
     (WARN).
   Regression tests: `BassSwapStrategyTest.theEqMixKeepsBothDecksOnTheirPhaseLockedTiming` (red before: A lagged
   its phase-locked deck by 85-115 frames at every beat), `RecipeRenderAutomationTest.anEqBackAtUnityIsTransparent`
   (red before: 0.74 max difference once every band is back at 0 dB), `ZeroPhaseCrossoverTest`,
   `BeatAlignmentTest.bassSwapIsAsWellAlignedAsTheDryBlend` (red before: 7.21 ms median against 0.87 for
   `beatMatchedBlend`). `BassSwapStrategyTest.renderIsCleanDeterministicAndBeatLocked` now holds `bassSwap`'s
   kicks to `beatMatchedBlend`'s budgets (2 ms median, 3 ms tolerance) instead of the 6 ms that allowed for the
   group delay.

9. **`beatAlignment*` measured the detector, not the render** (metric change: `beatAlignmentMaxMs` is replaced by
   `beatAlignmentP90Ms`; thresholds unchanged). Three defects, all in how the render's onsets were found and
   counted. The per-beat numbers in the first two are the base build's, as recorded:
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
   The maximum became the 90th percentile (nearest rank): a render can have its strongest attack on a beat well
   away from every source attack - at 4c11164 (probe) three beats of 59 on `bassSwap t120C → t63G` read 13.0–23.5
   ms that way, and eight of 45 on `stemSwap t120C → t63G` read 7.0–49.4 ms (known failure 2) - and a few such
   beats are not a timing error, while a deck that is off is off on many beats.
   Since 383c3c4 each counted beat is also checked above 2 kHz (LR4 high-pass, 5 ms look-back): every render
   attack there of at least 10 % of the render's strongest and 25 % of the strongest near the beat must lie within
   2 ms of a mapped source attack of at least 5 % of that source's strongest, and one that does not counts as its
   distance to the nearest mapped source attack of at least 10 % of its source's strongest (of at least 5 % only
   when there is none at 10 %); these
   errors count only when at least 10 % of the counted beats, and at least 2, have one, and a beat's error is then
   the larger of the two. The full band hears one attack where both decks play on a beat: the on-time deck's
   comes first and the late deck's is a rise over the first one's kick, so a deck played late was seen only on the
   beats it plays alone (as recorded when the check was added, `BeatAlignmentTest`'s A 20 ms late read 11.05 ms on
   `recipe:smooth-blend` and 9.66 ms on `recipe:long-glide` without it). The check changes no value of `ab --all`
   on `t120C → t126Am` or `t120C → t63G` (every cell is the same at 6a1fc2c, its parent, and at 4c11164); in the
   golden corpus it moves `stemSwap t120C_t63G` from 9.69 to 10.86 ms (`muisc goldens update --strategies
   stemSwap` into a scratch directory, at 6a1fc2c and at 4c11164).
   It does not go blind: `BeatAlignmentTest.oneDeckPlayedLateStillFails` plays B, or A, 15, 20 and 40 ms late in
   real `beatMatchedBlend`, `bassSwap`, `recipe:smooth-blend`, `recipe:long-glide` and `recipe:club-bass-swap`
   renders, sources and plan untouched, and the 90th percentile FAILs every time and reads the offset to within
   1.5 ms; the same renders on time PASS. With the median in place of the percentile, or with beats more than 12 ms
   from any source attack dropped, that test went red when the percentile was introduced (both tried). Late-deck
   injection on the CLI's own plans, and where it still misses, is known failure 2. Measured on the fixture pairs,
   base → 4c11164 (median / max before, as recorded when the percentile was introduced; median / 90th percentile
   at 4c11164; `ab --all`):

   | strategy | t120C → t126Am | t126_Am → t120_C | t120C → t63G |
   |---|---|---|---|
   | `beatMatchedBlend` | 1.63 / 97.39 → 0.09 / 0.31 | 0.98 / 37.20 → 0.08 / 0.23 | 22.06 / 87.01 → 0.19 / 0.86 |
   | `bassSwap` | 14.29 / 72.19 → 0.07 / 0.28 | 10.73 / 73.77 → 0.09 / 0.22 | 23.42 / 99.77 → 0.21 / 2.46 |
   | `stemSwap` | 0.60 / 30.14 → 0.11 / 0.28 | 1.02 / 37.37 → 0.08 / 0.28 | 4.19 / 68.73 → 0.28 / 11.79 |
   | `drumBreakBridge` | 0.73 / 67.26 → 0.07 / 0.26 | 1.08 / 20.74 → 0.08 / 0.25 | 2.52 / 67.26 → 0.07 / 0.35 |
   | `harmonicBlend` | 1.97 / 97.39 → 0.08 / 0.33 | 1.01 / 16.76 → 0.10 / 0.21 | 24.31 / 87.01 → 0.18 / 0.84 |
   | `recipe:smooth-blend` | 19.63 / 74.73 → 0.10 / 0.25 | 4.07 / 73.80 → 0.07 / 0.22 | 22.96 / 99.52 → 0.18 / 0.73 |
   | `recipe:club-bass-swap` | 22.09 / 74.73 → 0.10 / 0.25 | 6.98 / 66.23 → 0.07 / 0.20 | 20.20 / 99.52 → 0.18 / 0.70 |
   | `recipe:drums-first` | 1.82 / 87.14 → 0.11 / 0.25 | 1.08 / 36.94 → 0.08 / 0.18 | 14.72 / 80.73 → 0.21 / 3.20 |
   | `recipe:long-glide` | 14.65 / 64.38 → 0.13 / 0.24 | 4.01 / 93.04 → 0.08 / 0.19 | 20.09 / 96.03 → 0.15 / 0.68 |
   | `recipe:tension-build` | 10.14 / 87.14 → 0.12 / 0.63 | 2.83 / 36.83 → 0.12 / 0.39 | 4.47 / 82.81 → 0.14 / 0.46 |

   `t126_Am → t120_C` is `muisc synth --bpm 126 --key Am` into `--bpm 120 --key C`. Regression tests:
   `BeatAlignmentTest.oneDeckPlayedLateStillFails`,
   `BeatAlignmentTest.beatsTheRenderDoesNotArticulateAreNotCounted` (red before: four beats 70 ms from any click
   read as 70.3 ms), `BeatAlignmentTest.anOddHighBandAttackOnOneBeatIsNotCounted`, `AttacksTest`.

## Known failures on the fixture set

These are real, reproducible, and open. They are recorded here rather than hidden because the whole point of the
metrics is to make transition quality measurable while it is tuned.

1. **`levelJumpDb` 10.69 dB on `echoOut` t140Fs → t63G** (FAIL, threshold 6). Over-reporting of the effect, and
   not fixed. The flagged boundary is a rise at output frame 74 970 (1.700 s, probe), 107 ms before the cut
   (the "A cut into the echo" marker at frame 79 698), where the render goes from -20.4 to -8.1 dB (10 ms windows)
   while A at the same frames stays at -21.0 to -18.4 dB: the difference, render minus A, is at -8.5 dB. It is
   the delay's first repeat of A's attack at 1.37 s (A's 10 ms level peaks at -4.9 dB at 1.39 s), one delay period
   later (`plan`: "0.75-beat echo = 321.4 ms at 140.0 BPM"); the `echoTail` lane starts at 1.38 s, before the cut.
   The nearest source onset is 104.9 ms away, so nothing excuses it: the rule excuses a step where a source has an
   onset, and the delay puts A's attack where A has none. The next repeat, at 2.020 s, reads 6.83 dB (probe). In
   the mix at edf5f67, before analysis version 3, this transition read 10.64. This item used to describe the
   boundary as A's own note end inside a dry pre-roll; A has no step there, and the render there is not A alone.
   Doing this properly means mapping the sources' onsets through the effect as well, or comparing the render's
   step profile against the sources', not against a set of frames.

2. **`beatAlignmentP90Ms` 11.79 ms on `stemSwap t120C → t63G`** (WARN, 0.21 ms under the 12 ms FAIL; every
   other beat-domain render of the three fixture pairs PASSes, see fixed items 8 and 9). Median 0.28 ms: no deck
   is off. Eight of the 45 counted beats are 7.0 ms or more from the nearest source attack (probe: 7.0, 8.7, 11.7,
   11.8, 19.8, 27.9, 46.3, 49.4 ms, on master beats 18–20 and 27–31), all in the full band; the high band is off
   on one beat and does not count. In the golden corpus (`GoldenCorpusTest`, its own seeds and prefs) the same
   strategy and pair read 10.86 ms (WARN). Not investigated beyond that.
   The metric also has a blind spot, which its KDoc states: a deck with no high-band attacks during the overlap is
   judged only where its full-band attack is the strongest, and a late one can pass. Injected on the CLI's own
   plans (probe: each deck rendered alone through the strategy and its modifiers, the two summed with one delayed
   by 15, 20 or 40 ms, scored against the untouched sources and plan) on the five beat-domain strategies and five
   beat-domain recipes over five pairs (the three above, t63G → t120C and t63G → t126Am), 288 of the 300
   injections FAIL. The 12 that pass all have t63G as the late deck: A 15 ms late on `beatMatchedBlend`,
   `bassSwap` and `harmonicBlend` t63G → t120C (90th percentile 0.21, 4.09 and 0.21 ms); A 15, 20 and 40 ms late
   on `recipe:drums-first` t63G → t120C (0.29–0.30 ms) and t63G → t126Am (0.20–2.45 ms); and B 15, 20 and 40 ms late
   on `drumBreakBridge` t120C → t63G (0.32–0.35 ms). A late A on `harmonicBlend t120C → t126Am` FAILs at all three
   offsets (14.93, 19.98 and 39.93 ms), and so does a late A on `recipe:drums-first` on the three fixture pairs.
   Why t63G escapes was not traced beat by beat.

3. **`levelJumpDb` up to 9.99 dB on the beat-domain blends, 10.49–15.71 dB on `drumBreakBridge`** (FAIL at 6).
   What is left once fixed item 7 excuses the kicks and removes the glide's holes. Before = as recorded when item 7
   was fixed; 4c11164 = `ab --all` now. Item 7 was not measured on `t120C → t63G`, so that pair has no before:

   | strategy | t120C → t126Am before | 4c11164 | t126_Am → t120_C before | 4c11164 | t120C → t63G 4c11164 |
   |---|---|---|---|---|---|
   | `beatMatchedBlend` | 24.25 | 5.45 | 24.31 | 6.99 | 4.09 |
   | `bassSwap` | 24.10 | 5.61 | 23.00 | 6.67 | 3.85 |
   | `stemSwap` | 29.08 | 9.99 | 25.62 | 6.79 | 6.74 |
   | `drumBreakBridge` | 25.59 | 13.88 | 27.44 | 10.49 | 15.71 |
   | `harmonicBlend` | 27.48 | 5.37 | 24.47 | 6.26 | 4.70 |

   - On seven of the eight blend renders the worst boundary is a step in a source's own material that is no onset
     by the detector's definition, and the render follows the source through it (probe, 10 ms levels): at 30.00 s
     on `beatMatchedBlend`, `bassSwap` and `harmonicBlend t126_Am → t120_C` B falls from -15.9 to -22.7 dB and the
     render from -15.4…-15.9 to -22.6…-23.3 dB; at 29.17 s on `stemSwap t126_Am → t120_C` B falls from -24.9 to
     -32.7 dB and the render from -25.1 to -33.0 dB; at 7.98 s on `beatMatchedBlend` and `bassSwap t120C →
     t126Am` both sources fall (A -26.8 → -32.7, B -27.3 → -31.5 dB) and the render with them; at 17.15 s on
     `harmonicBlend t120C → t126Am` the render is B, level for level (-21.1 → -15.8 dB).
   - `stemSwap t120C → t126Am` is the exception, and the largest: 9.99 dB, a fall at 18.67 s from -11.5 to
     -21.8 dB where neither source's full mix falls (A -17.7 → -14.2, B -6.8 → -8.6 dB) and no lane steps (the
     drum swap is at 8.04 s, the bass swap at 21.01 s). Two lanes are mid-ramp there: the rest crossfade (lanes
     `gainA.rest` and `gainB.rest` in the plan `muisc render` writes) takes A's rest down along the equal-power law
     from 6.07 s and B's rest up linearly from 8.04 s, both ending at 21.48 s, so at 18.67 s A's rest plays at
     0.28 (-11.0 dB) and B's at 0.79 (-2.0 dB), each moving by 0.03 dB or less per 10 ms. The render there plays
     B's drums, both decks' rest and A's bass, so a full-mix comparison cannot say whether a stem's own decay
     explains it. It read 5.56 at edf5f67 and reads 9.99 from 4a06022 on: analysis version 3 moved it. Not
     investigated further.
   - On `drumBreakBridge` the flagged rises sit on the off-beats of the drum solo (probe: master beat 30.49 at
     15.34 s on `t120C → t126Am`, 31.49 at 15.32 s on `t126_Am → t120_C`), where A's drum stem plays without
     either deck's other stems (over the `textureCarry` bed on the second pair), high-passed at 275–400 Hz, before
     the drum handover; no gain lane steps at either. A's source has a hi-hat
     attack at each: above 4 kHz it rises from below -64 dB to -20.5 dB (t120C) and from -74 dB to -19.9 dB
     (t126_Am) within 10 ms, while its full mix rises by 5.4 and 3.3 dB and the nearest source onset is 50.8 and
     68.4 ms away. The rule takes its onsets from the sources' full mixes, so an attack that a soloed stem exposes has
     nothing to excuse it. Not an audio defect; not fixed (onsets per stem would need a stem separation in the
     install gate).
   - On `t120C → t63G` two of the five FAIL: `drumBreakBridge` at 15.71 dB, the largest reading of this metric in
     the three `ab --all` runs, and `stemSwap` at 6.74 dB. The other three read 3.85–4.70 (PASS or WARN). Neither
     render was probed: where their worst boundaries sit, and whether the soloed-stem explanation above covers the
     15.71, is not known. Two runs of the same command with fresh caches gave the same `metrics.csv` byte for byte.
   - The beat-domain recipes on `t120C → t126Am` share the metric fix: `smooth-blend` and `club-bass-swap` 9.64 →
     5.42, `drums-first` 17.21 → 5.42, `long-glide` 19.21 → 5.27, `tension-build` 10.06 → 5.42 (before as
     recorded, after at 4c11164). On `t126_Am → t120_C` `smooth-blend`, `club-bass-swap` and `drums-first` read
     6.35, 6.44 and 6.46 (FAIL) and `tension-build` 12.25; on `t120C → t63G` `tension-build` reads 8.81. What
     remains on them has not been investigated.
   - The zero-phase EQ of fixed item 8 moved this metric on the renders that use it, both ways, when it landed
     (`ab --all`, base → after, as recorded): `bassSwap` 6.76 → 6.93 (`t120C → t126Am`) and 5.44 → 6.70
     (`t126_Am → t120_C`); `smooth-blend` and `club-bass-swap` 7.53 → 8.16 and 4.60/4.66 → 8.38 (`t120C → t63G`);
     `long-glide` 8.18 → 8.63, 5.60 → 6.18 and 7.09 → 4.89. At 4c11164 the same cells read `bassSwap` 5.61 and
     6.67; `smooth-blend` and `club-bass-swap` 5.13 and 3.76 on `t120C → t63G`; `long-glide` 5.27, 5.60 and 4.68.
   - Outside the beat-domain blends `ab --all` FAILs this metric on `phraseCut` (6.25 on `t120C → t126Am`, 6.18 on
     `t120C → t63G`), `ambientBridge` (7.00, `t120C → t126Am`), `recipe:filter-handoff` (6.20, `t120C → t126Am`)
     and `recipe:echo-wash` (6.26 on `t126_Am → t120_C`, 6.81 on `t120C → t63G`). Not investigated.
   - The Lab's 29 dB on `stemSwap` at 34–64 overlap bars could not be reproduced as a long overlap: on the 32- and
     96-bar synthetic 126 → 120 pairs `stemSwap` with `overlapBars` 34, 48 or 64 renders the same 19-bar plan as
     with no `--set` (the same WAV byte for byte; its plan note reads "overlap shortened from 34 to 19 bars: A has 19
     bars after its phrase start, B 27 bars after its mix-in" on the 32-bar pair and "…, B 91 bars after its
     mix-in" on the 96-bar pair, from 48 and from 64 likewise, where the default plan's reads "from 24 to 19"), and
     there it measures 6.79 dB (32 bars) and 6.66 dB (96 bars) at 4c11164, 25.6 before fixed item 7; the 29.08 dB
     on `t120C → t126Am` was the silence of fixed item 7.

4. **`truePeakDbtp` above -0.5 dBTP, and `clipping`** (FAIL). On `t120C → t126Am` most beat-domain renders read
   -0.67 dBTP (WARN) and `phraseCut`, `filterSweep`, `ambientBridge` and `recipe:tension-build` FAIL (-0.15 to
   -0.44); on `t126_Am → t120_C` nothing FAILs (the highest is -0.92). On `t120C → t63G` twelve renders read above
   0 dBTP (+0.85 to +1.37) and the same twelve clip (15 to 409 samples at or beyond full scale):
   `beatMatchedBlend`, `bassSwap`, `stemSwap`, `harmonicBlend`, `filterSweep`, `loopRollRiser`,
   `spectralFreezeBridge` and the recipes `smooth-blend`, `club-bass-swap`, `drums-first`, `long-glide` and
   `tension-build`. In every one of them the full-scale samples lie in the last 93 ms of the written WAV (the first
   91–93 ms, the last 48–80 ms before the end), which is the 4096-frame post-roll, B verbatim. The fixtures
   themselves peak at +1.14 to +1.41 dBTP (`muisc check`), the CLI's default prefs match every deck to -14 LUFS
   (`TransitionPrefs.targetLufs`; t63G measures -15.0 LUFS, so its deck is raised by about 1 dB), and the splice
   contract requires the guard regions to be the deck-gained source verbatim — so the limiter is not allowed to
   bring those frames down, and the metric measures the whole render. The player's own master limiter handles this at playback; the metric and the contract
   disagree about who owns the guard.

5. **`tailContainedDb` -39.12 dB on `echoOut` t120C → t126Am** (FAIL at -40; -41.23 WARN at edf5f67, FAIL from
   analysis version 3, 4a06022, on). The echo's release ends at `cut + tail`, which is `GUARD_FRAMES` (4096
   frames, 92.9 ms) before the end of the segment (the "tail released" marker at frame 456 137 of 460 233), while
   the containment window is 100 ms — so the measurement always overlaps the last few milliseconds of the release.
   The render also reports that the limiter reached into the post-roll and was restored verbatim with a 256-frame
   blend before it. Why the new analysis pushes it over -40 has not been investigated.

6. **`stereoCorrelationMin`** (WARN only): -0.40 on `echoOut` t140Fs → t63G in the mix; -0.60 on
   `drumBreakBridge` and -0.72 on `brakeStop` on `t120C → t126Am`; on `t120C → t63G` 17 of the 23 renders WARN,
   from -0.33 to -0.72 (those two among them, `beatMatchedBlend`, `bassSwap`, `harmonicBlend` and four recipes at
   -0.67). Nothing on `t126_Am → t120_C` WARNs. Not investigated.

7. **`tailContainedDb` on `loopRollRiser`, `brakeStop` and `drumBreakBridge`** (FAIL at -40): `loopRollRiser`
   -18.22, -17.08 and -16.78 dB (`t120C → t126Am`, `t126_Am → t120_C`, `t120C → t63G`), `brakeStop` -18.30 and
   -17.08 dB (the first two), `drumBreakBridge` -26.91 dB (`t120C → t63G`). Not investigated. (`loopRollRiser` no
   longer plays in the shuffle mix above, where this item used to be recorded.)

8. **`dcOffsetDb` above -50 dBFS** (FAIL): `phraseCut` -49.26 and -49.90 (`t120C → t126Am`, `t120C → t63G`),
   `ambientBridge` -45.39 and -46.43 (the same two pairs), `recipe:reverb-freeze-bridge` -42.12, -45.52 and -42.81
   dBFS (all three pairs). Not investigated.

9. **`muisc check` without sources** (FAIL): `muisc check set.wav` FAILs `levelJumpDb` at 6.92 dB, and so does
   `muisc check` on each fixture alone: t120C 6.01, t126Am 6.34, t140Fs 9.34, t63G 6.78 dB. The set's reading is
   t63G's own rise (see the shuffle mix above); without sources the metric has no onsets to excuse a track's own
   dynamics, so it flags the source material, not a transition. The set's other non-PASS readings are WARNs:
   `truePeakDbtp` -0.94, `loudnessSmoothness` 11.93, above every fixture alone (6.18–8.33), and
   `stereoCorrelationMin` -0.67, the same value as t120C alone (-0.6699 in both). Where the last two sit in the set
   was not probed.

## Honest limits

- The fixtures are synthetic. They have exact grids and clean spectra, so they flatter beat tracking and key
  detection; real music will be harder in ways this corpus cannot show.
- Pseudo-stems are signal processing, not source separation. `stemSwap` with them behaves like a staggered
  three-band EQ mix, and is scored accordingly.
- "Every song into every song" is guaranteed only as "a plan always exists and the render is checked". For a
  genuinely incompatible pair that plan is an echo-out, an ambient bridge or a crossfade.
- Several numbers above are measurements of the *metric*, not of the audio. Where that is the case it is said so
  explicitly; where a number is still unexplained it is in the "known failures" list rather than in "fixed".
