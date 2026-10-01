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

The `identity` line (and the `identity` key of `--json`) is a hash of the decoded audio. Pins are keyed by it, so a
pin follows the music when a file is copied, touched or re-tagged. `muisc pin set A B STRATEGY --identities` and
`pin clear --identities` take identities instead of files. `pin list` prints the identities in full. If you pass an
analysis `fingerprint` from the cache instead, it is replaced by that track's identity and the command says so. A
value that matches no analysed track is still stored, with a warning.

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

`--set` goes on top of the values the planner uses for the pair: the defaults, the active preset,
`paramOverrides`, and the pair's pin if it is for that strategy (the pin's preset, then its own values). A pinned
pair therefore keeps everything the pin set except the values you change. The same layering applies to
`--modifier`, to `--strategy` for a strategy the planner did not rank (blocked or disabled), and to every point of
`muisc sweep`.

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

Every song plays at least one bar of itself (half of itself if it is shorter than two bars) between the transitions
around it. When the planner's favourite would not leave that (a short song, or a transition that exits before the
previous one has handed the song over), `mix` plays the best-ranked candidate that does, and says so on a `room:`
line. A pinned strategy (`muisc pin`, `--preset`) keeps first place as long as it leaves both songs their bar, even
when the next pair then cannot fit a transition; it gives way only when it would not (the `room:` line then says
`(pinned)`). A transition that still has to be dropped, because no candidate leaves both songs their bar, is
reported with `dropped`, that pair plays body to body, and the next pair is planned as if no transition came
before it.

```
muisc order playlist/ --seed 3                      # smart shuffle: the order, each pair's cost, total vs random
muisc order playlist/*.flac --arc peak --variety 0  # warm-up → peak → cool-down, best order found
muisc order playlist/ --json                        # the same as JSON (nothing else printed)
muisc mix playlist/*.flac --order smart --context shuffle -o set.wav   # render the smart order
```

`order` runs the engine's set sequencer (`dev.muisc.transitions.sequence.SetSequencer`, the one behind the app's
smart shuffle) over the files: a pair cost from the pair features (tempo stretch, key distance, energy and
loudness jump, grid confidence, outro/intro fit, room, vocal clash, and a penalty when no beat-matched move is
possible), a greedy start and 2-opt / or-opt local search up to 500 tracks, a windowed online pass above. It prints
the order with the cost of each neighbouring pair, the total, and the median total of 200 seeded random orders of
the same files. Up to 24 tracks the pair costs also blend in the planner's best score. `--arc` is `flat`
(default), `rising`, `wave` or `peak`; `--variety` (0..1, default 0.3) adds seeded noise to the costs so each seed
gives a different order. A file that cannot be analysed is placed without cost information (its pairs cost 0.5)
and listed as "not analysed". The CLI reads no tags, so the same-artist rule does not apply here. `mix --order
smart` plays the order `order` prints for the same seed; it is refused for `--context album`. `eval --order smart`
draws its consecutive half from smart orders instead of random shuffles (labelled `smart` in the report).

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

```
muisc eval ~/Music/ --pairs 60 -o eval/          # sample pairs of a library, plan, render, metrics: report.html + CSV/JSON
muisc eval ~/Music/ --fail-on 10% --worst 20     # exit non-zero when more than 10 % of the renders FAIL a metric
muisc bench analysis --songs 24 --all            # tempo/beat/downbeat/key/trim accuracy on synthetic songs
```

`eval` is how a change is judged against your own music rather than the synthetic fixtures: every worst render in
the report comes with the `muisc render` command that reproduces it. `bench analysis` measures the analyzer against
songs with known answers. See `docs/TESTING.md` for both, and for the golden corpus that covers every strategy and
built-in recipe.

## Other

```
muisc live a.flac b.flac --kind bassSwap -o live.wav   # audition the real-time fallback moves
muisc stems song.flac -o stems/                        # the four pseudo-stems
muisc play out.wav                                     # play through the system audio device
```

## Lab

```
muisc lab --fixtures                          # the four synthetic songs: works with no music at all
muisc lab ~/Music/set/ one.flac --port 0      # load a folder and a file; 0 picks a free port
muisc lab --fixtures --style club --profile-dir /tmp/try   # any shared option works as elsewhere
```

The Transition Lab is a local web page for designing, tuning and testing transitions by ear. It prints its address
(default `http://127.0.0.1:8765/`) and serves until Ctrl-C. Everything runs through the same wiring as the other
commands: the analysis cache, the 14 strategies plus every usable recipe in `<profile>/recipes`, the planner with
your pins, presets and learned weights, the real renderer and, for what you hear, the real program player.

What the page does:

- **Pair.** Pick A and B (or add a file or folder by path). The summary shows tempo, stretch, keys with Camelot
  codes and distance, loudness, the outro/intro edges, grid confidence and the rating context the pair falls in.
- **Design.** The planner's ranking with scores; "Why" opens the score formula, the eight sub-scores, the reasons,
  blockers, learned weight, pin and preset notes. "Not ranked" lists skipped strategies with the reason and a
  "Try anyway" button. Choosing one builds its controls from its parameter specs (slider and number for numbers,
  checkbox, drop-down), with modifiers either the planner's choice or yours. **Render** plays N seconds of A, the
  segment and N seconds of B through the player (the context render), draws the waveform with the segment shaded,
  the seams, the markers and every automation lane (each normalised to its own range, toggled in the legend), and
  lists every metric with PASS/WARN/FAIL. From here: save the values as a preset, load one, pin the choice to the
  pair, rate the render.
- **Slots.** Every render keeps a slot. Switching slots keeps the playback position (and keeps playing), so two
  versions can be compared at the same moment. Keys: Space play/pause, 1–9 switch slot; on the focused waveform
  ←/→ seek 2 s, PageUp/PageDown jump to a second before the previous/next seam, Home/End.
- **Recipe editor.** Start from a blank template or any built-in or user recipe, edit the JSON, and it is
  validated as you type: every problem with severity, path, line and column; clicking one selects that line.
  The recipe's knobs become sliders and the lanes are plotted at those values. **Render without saving** renders
  the text as it is; **Save to my recipes** writes `<profile>/recipes/<id>.json` (refused while it has errors)
  and the new `recipe:<id>` strategy appears in the ranking at once. If one of your recipes already has that id,
  nothing is written until you confirm: the page names the recipe and its file and offers **Replace it** or
  **Cancel**. It does not ask when you save again the user recipe you loaded into the editor with the same id. The
  blank template starts with an id no recipe or file uses yet (`my-transition`, then `my-transition-2`, ...).
- **Blind test.** Choose 2–4 candidates (strategies, presets, or the recipe in the editor). They are rendered and
  shuffled under the names X, Y, Z, W; the page shows only their waveforms until you vote. Your pick is rated up
  and every other candidate down in the pair's rating context (a candidate with the same strategy as the pick is
  left unrated), so the planner learns from it; the page then reveals which was which with the weights before
  and after. One vote per test.
- **Sweep.** One numeric parameter over a range (2–12 renders), a chosen metric plotted against it with its
  warn/fail thresholds; every point is playable.
- **Library.** Presets (use, delete), pins (clear) and what was learned from your ratings.
- **Style.** The style menu replaces the `--style` the Lab was started with; the two are never applied together.
  The other options still apply, and `--set-pref` still wins. **As started** keeps the command line's prefs.

Renders are written to a temporary session folder (printed at start) and deleted when the Lab stops. That includes
Ctrl-C or SIGTERM while it is still writing the fixtures or analysing at startup. Only files
from that folder are served. The server listens on 127.0.0.1 only, so other machines cannot reach it. Because a
web page open in your browser can still send requests to 127.0.0.1, the Lab also refuses requests whose `Host`
is not `127.0.0.1`/`localhost`/`[::1]` with its port (DNS rebinding), requests whose `Origin` is another site,
and API posts that are not `application/json` (cross-site forms). Tracks are only ever read from paths you type
or pass on the command line; the audio itself is never served, only its waveform peaks.

The page uses no external fonts, scripts or images and runs under `script-src 'self'`. Light and dark follow the
system setting. Measured WCAG contrast of text on its background: light theme — body text 14.6–16.6:1, secondary
text 5.5–6.7:1, links and accent 6.2–6.7:1, primary button text 6.7:1, PASS/WARN/FAIL badges 7.3/7.5/6.8:1;
dark theme — body text 11.2–16.0:1, secondary text 5.8–8.3:1, accent 6.8–7.4:1, primary button text 7.4:1,
badges 8.7/9.0/8.0:1. Control outlines are 3.3:1 (light) and 3.7:1 (dark); lane colours are at least 4.3:1
against the waveform background in both themes. The recipe-save question uses body text on the panel (16.6:1 light,
14.6:1 dark) inside a warning-coloured frame (8.6:1 light, 11.6:1 dark), and its buttons are the ordinary ones.
