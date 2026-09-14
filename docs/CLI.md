# `muisc` — the transition workbench

The CLI is the desktop loop for developing and tuning transitions: analyse tracks, see why the planner picks what
it picks, render a transition (or a whole DJ set), compare every strategy on one pair, sweep a parameter, and check
the numbers. It runs the same engine code the Android app runs, so what you hear here is what the phone plays.

```
./gradlew :tools:cli:installDist
export PATH="$PWD/tools/cli/build/install/muisc/bin:$PATH"
muisc --help
```

Common options on every command: `--rate` (engine sample rate, default 44100), `--channels`, `--seed`
(deterministic), `--prefs file.json` and `--set-pref key=value` (transition preferences), `--cache-dir`
(analysis cache, default `~/.muisc/analysis` or `$MUISC_CACHE`), `--debug` for stack traces.

## Getting material

```
muisc synth --set --out fixtures/         # the four-song fixture set used by the tests
muisc synth --bpm 128 --key Am --bars 32 --intro 4 --outro 4 --fade --out fixtures/
```

Synthetic songs are deterministic and carry known tempo, key, beat grid and structure, so they are the fastest way
to hear a change without hunting for real music that triggers it.

## Understanding a track

```
muisc analyze song.flac                   # tempo, grid, key/Camelot, loudness, edges, cues, sections
muisc analyze song.flac --json            # the full TrackAnalysis
muisc analyze song.flac --click grid.wav  # the song with a click on every detected beat — check the grid by ear
```

If a transition lands off-beat, listen to `--click` first: almost always the grid is the problem, not the strategy.

## Choosing a transition

```
muisc plan a.flac b.flac                  # every strategy ranked, with sub-scores and plain-English reasons
muisc plan a.flac b.flac --top 3 --previous bassSwap
```

The ranking prints the compatibility breakdown (tempo, key, energy, vocals, grid, structure, room, stems), the
score arithmetic, the chosen bars and frames, and the planner's notes for each candidate.

## Rendering

```
muisc render a.flac b.flac -o t.wav                                   # the planner's best choice
muisc render a.flac b.flac --strategy bassSwap --set swapBar=12 -o t.wav
muisc render a.flac b.flac --strategy echoOut --modifier textureCarry --context 20 -o t.wav
```

Writes `t.wav` (the transition segment), `t.plan.json`, `t.report.json` (render report plus metrics) and, with
`--context N`, `t.context.wav`: N seconds of A, the segment and N seconds of B played through the real program
player, so you hear the actual seams rather than the segment in isolation.

## A whole set

```
muisc mix playlist/*.flac -o set.wav --context shuffle --report set.json
```

Analyses every track, plans each consecutive pair (with the variety penalty), renders, applies the gating rule for
the context, and writes the set plus a per-transition report and the seam map. `--context album` respects album
order and renders no transitions at all, which is the behaviour the app uses when you play an album.

## Comparing and tuning

```
muisc ab a.flac b.flac -o ab/                       # one WAV per strategy + metrics.csv + index.html
muisc ab a.flac b.flac -o ab/ --all                 # include the ones the planner blocked
muisc sweep a.flac b.flac --strategy bassSwap --param swapBar=4:16:7 -o sweep/
muisc score library/ --csv matrix.csv --html        # pairwise best strategy and score over a folder
```

`ab` is the fastest way to decide which technique suits a pair; `sweep` is how you tune one technique's parameters
against a metric.

## Checking

```
muisc check out.wav                                  # metrics on any WAV
muisc check out.wav --a a.flac --b b.flac --plan out.plan.json   # adds the seam and beat-alignment metrics
muisc goldens check                                  # regression over the synthetic fixture set
muisc goldens update                                 # accept new goldens after an intended change
```

See `docs/QUALITY.md` for what each metric means, its thresholds, and the failures that are currently open.

## Other

```
muisc live a.flac b.flac --kind bassSwap -o live.wav   # audition the real-time fallback moves
muisc stems song.flac -o stems/                        # the four pseudo-stems
muisc play out.wav                                     # play through the system audio device
```
