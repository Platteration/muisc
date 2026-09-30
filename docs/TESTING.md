# Testing Muisc

Five layers, from the fastest and narrowest to the one no machine can do for you. Each section says how to run it,
how to read what it prints, and what it cannot tell you.

| Layer | What it answers | Where |
|---|---|---|
| Unit and contract tests | Does each piece do what its code says? | `./gradlew test` |
| Golden corpus | Did any strategy or recipe change how it sounds on known material? | `GoldenCorpusTest` (about 80 s) |
| `muisc eval` | How do the planner's picks score on *my* music? | CLI |
| `muisc bench analysis` | How accurate are tempo, beats, downbeats, key and trim? | CLI + `AnalysisBenchTest` (12 songs, about 40 s) |
| The Lab's blind test | Which one sounds better? | `muisc lab` |

(Times measured on a shared 4-core Linux container; yours will differ.)

The engine modules and the CLI build without Android (`settings.gradle.kts` includes `:app` only when an SDK is
found), so everything below runs on any machine with a JDK.

## 1. Unit and contract tests

```
./gradlew test                         # every module
./gradlew :engine:transitions:test     # one module
./gradlew :tools:cli:test --tests '*EvalCommandTest*'
```

- **Unit tests** sit next to the code in every module (`engine/*/src/test`, `tools/cli/src/test`). The DSP and
  analysis tests check against signals with known answers (a sine's pitch, a click track's beats, a synthetic
  song's key).
- **`StrategyContractTest`** (`engine/transitions`) renders every built-in strategy *and every shipped recipe*
  over the 12 ordered pairs of four synthetic songs and checks, for every pair the strategy says it applies to:
  a valid plan, a render length within ±1 % of the plan, a clean splice, no click, peak ≤ 0 dBFS, no NaN/Inf, and
  a bit-identical second render. A new strategy or recipe is covered as soon as it is registered.
- **Metric tests** (`engine/metrics`) check each metric on constructed signals, including that deliberately
  injected defects are caught: a click, a sustained level step, clipping, DC, NaN, a silent hole, a zeroed seam,
  a tail leaking past the segment.
- **CLI tests** (`tools/cli`) run every command in-process on synthetic WAVs in a temp directory and check the
  files it writes and its errors. They never read `~/.muisc`: each test passes its own `--cache-dir` and
  `--profile-dir`.

A failing test is a finding. `AGENTS.md` §4 is binding here: never delete an assertion, widen a tolerance or skip
a test to get green.

## 2. The golden corpus

A committed fingerprint of every registered strategy and every built-in recipe, rendered over four synthetic
pairs, compared on every test run.

```
./gradlew :tools:cli:test --tests '*GoldenCorpusTest*'
```

**What is stored.** `engine/transitions/src/test/resources/golden/<strategy>/<pair>.json` holds a
`GoldenFingerprint` per rendered (strategy, pair): the plan JSON, the 20 ms RMS envelope, the same envelope in four
bands (sub / bass / mid / high), a 64-band log spectrum every 250 ms, the metric report, and a SHA-256 of the
render as 16-bit PCM. No audio. Envelopes and spectra are stored rounded to 0.01 dB (which can move any RMSE by at
most 0.005 dB, a hundredth of the tolerance), one array per line, so a golden change reads as a diff. `corpus.json`
next to them records which strategies and pairs the corpus covers and, for every cell, whether it rendered or was
not applicable (with the blocker). About 4.5 MB in the working tree for 23 strategies × 4 pairs.

**The pairs** (`GoldenCorpus.PAIRS`):

| Pair | Covers |
|---|---|
| `t120C → t126Am` | +5 % tempo, relative keys, B with an ambient intro: the beat-matched, harmonic case |
| `t120C → t63G` | half-time partner with a faded outro: the tempo-relation and ambient case |
| `t120C → t140Fs` | +16.7 % tempo, distant keys, cold-start B: the case beat-matching must refuse |
| `t124D0 → t128Bm0` | drums end to end on both sides, relative keys: the club case (`drumBreakBridge`, `recipe:tension-build` and `recipe:club-bass-swap` apply only here) |

The first three are the first three pairs `muisc goldens` walks, rendered the same way, so for the built-in
strategies `muisc goldens check --pairs 3` compares against the same files. The test also asserts that every
strategy renders on at least one pair, so a strategy with no golden cannot slip in.

**What fails.** The comparison is `GoldenCompare` in its default (non-strict) mode:

- the plan must be identical (a different plan means the strategy decided differently — the audio numbers after
  that are only context);
- the RMS envelope and each band envelope within 0.5 dB RMSE, and the same length within one 20 ms block;
- the log spectrum within 1.0 dB RMSE, with the same number of frames;
- no metric verdict worse than in the golden (PASS → WARN is a regression);
- a cell that rendered must still render (and one that was not applicable must still not be), a strategy
  added to or removed from the registry must be reflected in the goldens, and no render may throw.

The PCM hash is only compared in strict mode, which this test does not use: it differs across platforms and JVMs.

**Reading a failure.** One paragraph per problem, naming the strategy, the pair and every feature that moved:

```
crossfade / t120C_t126Am: plan differs from the golden plan; rms envelope length 284 != golden 309 blocks; sub envelope RMSE 1.09721 dB > 0.5 dB; ...
    golden MISMATCH
      plan: DIFFERENT
      rms envelope RMSE: 0.331713 dB (tol 0.5)
      band envelope RMSE: sub 1.09721, bass 0.482848, mid 0.214506, high 0.850752
      log spectrum: RMSE 0.556669 dB, max 5.01650 dB (tol 1.0)
      metrics: no regression
```

That one came from shortening the crossfade's default length from 6 s to 5.5 s: the plan changed, the render got
25 blocks (0.5 s) shorter, and the sub band moved most. To hear the change, render the pair before and after with
`muisc synth --set` and `muisc render ... --strategy <id>`.

**Updating after an intended change.** When you *meant* to change how something sounds (a new default, a better
curve, a new recipe, a new strategy), listen to it first, then rewrite the goldens and review the diff:

```
MUISC_UPDATE_GOLDENS=1 ./gradlew :tools:cli:test --tests '*GoldenCorpusTest*' --rerun
git diff --stat engine/transitions/src/test/resources/golden
```

The test prints one line per changed cell (`new`, `updated` with the issues, `removed`, `status` changes); the
output is in `tools/cli/build/test-results/test/TEST-dev.muisc.cli.GoldenCorpusTest.xml`. `--rerun` is needed
because Gradle does not see the environment variable, or the golden files, as inputs of the test task: without it
an up-to-date test is skipped and nothing is rewritten. Commit the golden changes with the change that caused them
and say in the pull request which cells moved and why.

Updating the goldens to make an **unintended** change go away is exactly what `AGENTS.md` §4 forbids: it turns the
regression test off for the thing that just regressed. If you did not expect a cell to move, find out why first.

`muisc goldens update` (the CLI command) writes the built-in strategies only, over all twelve fixture pairs, in the
older full-precision format. It does not touch `corpus.json`; the next test-mode update removes the files the
corpus does not cover.

**What it cannot tell you.** Whether the old render or the new one sounds better. A golden only says *something
changed*. It also only covers synthetic material with exact grids and default parameters.

## 3. `muisc eval` — your own music

```
muisc eval ~/Music/some-playlist --pairs 40 --seed 1 --out eval-out
muisc eval a.flac b.mp3 c.wav --pairs 6 --fail-on 10%
```

What it does:

1. Analyses every audio file under the arguments (directories are walked recursively; results go to the analysis
   cache, so the second run is fast). A file that cannot be decoded is listed as skipped; it never stops the run.
2. Summarises the analysis: tempo confidence, grid confidence and key strength as min / p10 / median / p90 / max,
   and the tracks below the gates the planner uses (grid < 0.5 blocks beat-matching, key strength < 0.6 blocks
   `harmonicBlend`; tempo confidence < 0.5 is reported, nothing gates on it).
3. Samples `--pairs` ordered pairs from `--seed`: half are consecutive pairs of a shuffled order (planned with the
   previous pair's strategy, so the variety penalty works as in a real shuffle), a quarter the hardest by tempo
   stretch, a quarter the hardest by key distance. The same seed gives the same pairs.
4. Plans each pair with the same planner as `muisc render` (your profile, `--style`, `--preset` and `--set-pref`
   included), renders the planner's pick and measures it with the full metric set.
5. Writes to `--out` (default `./muisc-eval`):
   - `report.html` — one self-contained page (no scripts, nothing fetched): headline rates, the analysis summary,
     what the planner chose and how each strategy fared, PASS/WARN/FAIL per metric, the worst renders with the
     command to reproduce each one, and every pair.
   - `results.csv` — one row per pair: tracks, why it was picked, stretch, Camelot distance, strategy, score,
     verdict, render time, every metric's value and verdict, and the reproduce command.
   - `results.json` — the same plus the summary and the analysis distributions.
   - `prefs.json` — the effective preferences; every reproduce command passes it back with `--prefs`.

**Reading it.** Start with the FAIL rate and the worst renders. Each worst render has a command such as

```
muisc render /music/a.flac /music/b.flac --seed 1 --previous echoOut --prefs eval-out/prefs.json --profile-dir ~/.muisc --cache-dir ~/.muisc/analysis -o eval-out/repro/pair-007.wav --context 8
```

which re-renders exactly that transition (same seed, previous strategy, prefs, profile and cache) and writes the
segment and an 8-second context render to listen to. Then look at "What the planner chose": a strategy that is
picked often and fails often is where tuning pays. The analysis section explains many failures before you listen:
a track with a low grid confidence cannot be beat-matched, so a pair with it will fall back to a cut or an echo.

**Exit status.** `--fail-on RATE` (`0.1` or `10%`) makes the command exit non-zero when the FAIL rate exceeds it,
after the files are written. The FAIL rate counts pairs whose worst metric is FAIL plus renders that threw. Without
`--fail-on` the exit status is zero whatever the rates.

**What it cannot tell you.** Metrics find clicks, level jumps, peaks, seam errors and beat misalignment. A render
that passes every metric can still be a dull or badly placed transition, and a WARN can sound fine. Listen to the
worst ones and to a few that passed.

## 4. `muisc bench analysis` — analysis accuracy

```
muisc bench analysis                  # 24 songs: every key once
muisc bench analysis --songs 48 --seed 7 --all
```

Generates synthetic songs with known ground truth — tempo 60–180 BPM, all 24 keys, 0–8 bars of pad-only intro,
0–4 bars of outro (sometimes faded), leading and trailing silence, and a detune of up to ±40 cents (made by
resampling, so tempo and pitch move together and the ground truth is scaled to match) — runs the real analyzer
on each and prints:

```
analysis bench: 12 synthetic songs, seed 1
  tempo within 1 %         11/12 (91.7 %)
  octave errors            1/12 (8.3 %)
  beat F-measure (±70 ms)  mean 0.896, min 0.392; F ≥ 0.9 in 9/12 (75.0 %)
  downbeat phase           10/12 (83.3 %)
  key exact                12/12 (100.0 %)
  ...
```

followed by a table of the failing cases (`--all` lists every case) with the true and estimated tempo, the beat
F-measure, the downbeat accuracy, the true and estimated key, the trim error, the tuning error and the grid
confidence. Definitions:

- **tempo**: within 1 % of the truth; an **octave error** is an estimate at 2× or ½× (within 3 %).
- **beat F-measure**: the grid as the engine uses it (tracked beats, extended by the grid's own extrapolation) is
  matched one to one against every true beat, pad-only intro included, within ±70 ms.
- **downbeat phase**: right when ≥ 90 % of the true downbeats that have a matched beat fall on a beat the grid
  calls a downbeat.
- **key**: exact, relative (same Camelot number, other mode) or wrong.
- **trim error**: `|trimStart − true leading silence|`; a case fails above 50 ms.

The songs are generated in memory; nothing is written and the analysis cache is not used, so every run measures
the analyzer as it is now. `--rate` does not apply: the bench always runs at 44.1 kHz.

**The floor test.** `AnalysisBenchTest` runs a fixed set of 12 songs (seed 1) and asserts the accuracy the analyzer
achieved when the floor was set (the numbers above). It is a floor, not a target: raise it when the analyzer
improves; if a change lowers it, that is a regression to explain, not a number to edit.

**What it cannot tell you.** How the analyzer does on real recordings. Synthetic songs have exact grids, clean
spectra and textbook chord progressions; they flatter beat tracking and key detection. The bench catches
regressions and shows the known weak spots (slow tempos read as double time, long drumless intros); use
`muisc eval`'s analysis section for your library.

## 5. The Lab's blind test

`muisc lab` is being built alongside these tools and is documented in `docs/CLI.md`. Its blind test plays you
renders of the same pair without saying which is which and asks which you prefer; see `docs/CLI.md` for how to
start it and where the answers are kept. It is the only layer that measures what the others cannot: whether a
change sounds better. Use it before accepting a golden update that changes how a strategy sounds, and when tuning
a recipe's defaults.

## What none of this can tell you

How it actually sounds. The unit tests prove the code does what it says, the goldens prove it still does what it
did, the metrics find specific defects, and the bench measures the analysis against synthetic truth. None of them
has ears. A transition can be sample-exact, click-free, level-matched and on the beat and still be the wrong
move for those two songs. Listen — on the speakers or headphones the music is played on, at a normal volume, in
context (the `--context` renders), and blind where you can.
