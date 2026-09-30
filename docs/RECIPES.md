# Transition recipes

A **recipe** is a transition written as data: a small JSON file that says, bar by bar, what happens to the song
that is ending (deck **A**) and the song that is starting (deck **B**): their levels, their three-band EQ, a
high-pass and a low-pass filter, how much of each is sent to an echo or a reverb, and (optionally) the level of each
stem. It also says how the two songs are lined up in time and which pairs of songs it suits.

Every valid recipe becomes a transition technique of its own, with the strategy id `recipe:<id>`, next to the
built-in ones. Its **knobs** (`vars`) become the technique's parameters, so they can be tuned like any other.

This page documents the format and what `RecipeValidator` checks. The format itself is defined in
`engine/transitions/src/main/kotlin/dev/muisc/transitions/recipe/TransitionRecipe.kt`.

## Where recipes live

- **Built-in recipes** ship inside the engine (`engine/transitions/src/main/resources/recipes/`, listed in
  `index.txt`):

  | id | tempo | what it is |
  |---|---|---|
  | `smooth-blend` | match | long beat-matched blend; B's bass takes over partway, A's treble dips as it leaves |
  | `club-bass-swap` | match | B comes in with its bass off; the basses swap on a chosen bar |
  | `drums-first` | match | stem handover: B's drums first, then the bass, then melody and vocals (needs stems) |
  | `long-glide` | glide | 32-bar blend while the tempo glides from A's to B's |
  | `tension-build` | match | resonant high-pass build with a swelling echo on A, B drops in on the downbeat |
  | `filter-handoff` | none | A's high-pass sweeps up while B's low-pass opens; no time-stretching |
  | `echo-wash` | none | A's echo rises, A is cut on the phrase line, the echoes ring on under B |
  | `reverb-freeze-bridge` | none | A dissolves into a darkening reverb that freezes; B rises out of the wash |
  | `radio-segue` | none | short, gentle fade with no beat-matching |

  `muisc recipe show <id>` prints any of them, and `muisc recipe new my-copy --from <id>` copies one to edit.

- **Your recipes** are the `*.json` files directly in the user recipe directory: `~/.muisc/recipes` on a computer
  (the CLI takes `--recipes-dir` to use another one); on Android, the directory the app gives the library. Files
  whose name starts with `.` are ignored.

- **Same id as a built-in:** your recipe replaces (shadows) the built-in, and the library reports a warning so you
  know. If your file has errors it does not replace anything: the built-in stays in use.

- **Broken files never block the others.** A file that cannot be read, is not valid JSON, or contains a recipe
  with errors is reported and skipped; every other recipe still loads. That includes a file nested absurdly deep:
  more than 64 levels of `{` / `[` (a recipe needs about five), or an expression with more than 64 levels of
  parentheses, function calls and signs, is reported with its line and column like any other mistake. If two of
  your files use the same id, the first by file name whose recipe has no errors is used and the others are reported.

- **Saving** (from the library, e.g. `muisc recipe new`, the Lab or the app's import) writes the file that holds
  the recipe in use for that id (whatever its name); if no file holds the id, it writes `<id>.json`; if one file
  holds it but has errors (a draft being fixed), it writes that file. When more than one file holds the id, saving
  refuses and names the files, so you can delete or rename the ones you do not want; nothing is written. It writes
  a temporary file next to the target and renames it over the target, so a crash never leaves a half-written
  recipe, and it refuses to overwrite a `<id>.json` that holds a different recipe or cannot be read.

## A complete example

This is the built-in `club-bass-swap`. JSON cannot hold comments, so the notes follow the file.

```json
{
  "id": "club-bass-swap",
  "name": "Club bass swap",
  "description": "The club DJ's EQ mix. ...",
  "author": "Muisc",
  "ambition": 0.55,
  "tags": ["bass-swap", "eq", "club", "beat-matched"],
  "vars": {
    "len": { "default": 16, "min": 8, "max": 32, "label": "Length", "unit": "bars", "doc": "How many bars both songs play together", "integer": true },
    "swapBar": { "default": 8, "min": 2, "max": 30, "label": "Swap bar", "unit": "bar", "doc": "The bar on which the basses swap (moved inside the blend when the blend is shorter)", "integer": true },
    "swapBeats": { "default": 1, "min": 1, "max": 4, "label": "Swap length", "unit": "beats", "doc": "How many beats the new bass takes to come up before the old one is cut", "integer": true }
  },
  "timing": { "lengthBars": "len", "tempo": "match", "align": "phrase", "settleBars": 2, "holdBars": 2 },
  "rules": { "minEnergyDelta": -0.5, "maxEnergyDelta": 0.5, "baseScore": 0.65 },
  "a": {
    "level": [
      { "at": 0, "v": 1, "curve": "equalPower" },
      { "at": "bars", "v": 0 }
    ],
    "low": [
      { "at": "clamp(swapBar, 1, bars - 1)", "v": 0, "curve": "step" },
      { "at": "clamp(swapBar, 1, bars - 1)", "v": "off" }
    ]
  },
  "b": {
    "level": [
      { "at": 0, "v": 0, "curve": "equalPower" },
      { "at": "bars", "v": 1 }
    ],
    "low": [
      { "at": "clamp(swapBar, 1, bars - 1) - swapBeats * beat", "v": "off" },
      { "at": "clamp(swapBar, 1, bars - 1)", "v": 0 }
    ]
  }
}
```

- **`vars`** declares three knobs. `len` is the overlap length; `timing.lengthBars` is simply `"len"`.
- **`timing`**: B is beat-matched to A (`match`), the transition starts at a phrase, and after the 16-bar overlap B
  takes 2 bars to ride back to its own tempo (`settleBars`) and holds it for 2 more (`holdBars`), so the whole
  transition (`total`) is 20 bars.
- **`a.level`** starts at 1 (full level) at bar 0 and falls to 0 at `bars` along an equal-power curve (the curve
  belongs to the first point: it shapes the move from that point to the next). **`b.level`** is the mirror image;
  the pair is the classic constant-power crossfade.
- **`a.low`** is two points at the same bar: a vertical step. A's low band holds 0 dB until the swap bar and is
  then `off` (−120 dB). `clamp(swapBar, 1, bars - 1)` keeps the swap inside the overlap whatever the knobs say.
- **`b.low`** holds `off` until `swapBeats` beats before the swap bar (a lane holds its first value before its
  first point), then rises to 0 dB exactly on the swap bar, and holds 0 dB after its last point.
- Every lane of A is untouched at bar 0 and every lane of B is untouched at `total`: the boundary rule below.

## Fields

### The recipe

| field | type | default | meaning |
|---|---|---|---|
| `id` | text | required | lowercase letters, digits and dashes (`[a-z0-9-]+`); also the file name and the strategy id `recipe:<id>` |
| `name` | text | required | what the player and the Lab show; must not be blank |
| `description` | text | `""` | for listeners: what it sounds like and when it works |
| `author` | text | `""` | |
| `version` | whole number | 1 | the recipe's own revision |
| `format` | whole number | 1 | recipe format version; newer formats than the engine knows are refused |
| `ambition` | 0..1 | 0.5 | how showy it is (0 = invisible, 1 = a showpiece); compared with the listener's energy preference |
| `tags` | list of text | `[]` | |
| `vars` | object | `{}` | knobs, by name (below) |
| `timing` | object | see below | how the songs are lined up |
| `rules` | object | see below | which pairs it suits |
| `a`, `b` | object | untouched | the outgoing and the incoming deck |
| `modifiers` | list of text | `[]` | modifier ids the recipe **requires**: if one is not installed, the recipe is not used. The list does not choose what is attached (see below) |

**Modifiers.** As with the built-in techniques, every installed modifier that accepts the pair is attached to a
recipe (unless the listener or a preset has turned it off), whether the recipe lists it or not; listing one only
makes the recipe unavailable where that modifier is missing. `textureCarry` attaches to recipes this way.
`tempoGlide` never attaches to a recipe (it only works on the built-in beat-matched techniques), and the validator
warns when a recipe lists it: for a tempo glide, set `timing.tempo` to `"glide"`.

### Knobs (`vars`)

Each entry is `"name": { ... }`. Names use letters, digits and `_` (no dots, dashes or spaces), must not start
with a digit, and cannot be a built-in name (`bars`, `settle`, `hold`, `total`, `beat`, `off`) or a function name.

| field | default | meaning |
|---|---|---|
| `default`, `min`, `max` | required | numbers with `min <= default <= max` |
| `label`, `unit`, `doc` | `""` | shown next to the slider |
| `integer` | false | values are rounded to whole numbers (bar positions usually want this) |

A value set for a knob is clamped into `min..max` (and rounded when `integer`).

### `timing`

| field | default | meaning |
|---|---|---|
| `lengthBars` | 16 | the overlap, in bars, 1..64. Available in expressions as `bars` |
| `tempo` | `"match"` | `"match"`, `"glide"` or `"none"` (see *Tempo modes*) |
| `align` | `"phrase"` | where on A the transition may start: `"phrase"` (start of an 8-bar phrase; a downbeat when phrases are unknown) or `"downbeat"` |
| `bEntryOffsetBars` | 0 | bars B enters relative to its mix-in cue; negative starts earlier, inside B's intro (rounded to whole bars) |
| `bEntersAtBar` | 0 | `none` tempo only: the bar at which B's entry downbeat lands (not negative) |
| `settleBars` | 2 | `match` / `glide`: bars for B alone to ride back to its own tempo after the overlap (not negative) |
| `holdBars` | 2 | bars held at B's own tempo before the seam (not negative) |
| `lowHz`, `highHz` | 200, 4000 | the 3-band EQ crossovers; `20 <= lowHz < highHz <= 20000` |

The whole timeline is `total` bars long: `bars + settle + hold` in `match` / `glide`, and
`max(bars, bEntersAtBar) + hold` in `none`.

**Whole bars in `match` / `glide`.** The master grid moves in whole bars: the overlap, `settleBars` and `holdBars`
are rounded to whole bars, the hold is at least 1, and the overlap is shortened when A or B has too little music
for it. Lanes, effect settings and the EQ crossovers are then evaluated with the `bars`, `settle`, `hold` and
`total` actually rendered (the plan notes say so when they differ), so a lane written against `bars` or `total`
still lands on the end of the overlap and on the seam. A knob used directly in a lane is not adjusted, and a
position written as a plain number stays where it is; the validator warns when one of the three timing fields is
rendered as a different number of bars at a checked setting, or uses a knob that is not a whole-number knob.

### `rules`

| field | default | meaning |
|---|---|---|
| `requiresBeatMatch` | true for `match` / `glide`, false for `none` | needs confident beat grids on both songs |
| `maxStretchPercent` | `match` / `glide`: the listener's max-stretch setting; `none`: no limit (nothing is stretched) | largest tempo difference, in percent, it accepts (not negative) |
| `maxKeyDistance` | any | largest Camelot distance (after the best allowed pitch shift) it accepts (not negative) |
| `outro` | any | allowed outro types of A: `HARD_STOP`, `FADE_OUT`, `BEAT_OUTRO`, `AMBIENT_OUTRO`, `VOCAL_OUTRO`, `UNKNOWN` |
| `intro` | any | allowed intro types of B: `BEAT_INTRO`, `AMBIENT_INTRO`, `VOCAL_INTRO`, `COLD_START`, `SILENCE`, `UNKNOWN` |
| `minEnergyDelta`, `maxEnergyDelta` | −1, 1 | accepted range of B's energy minus A's (each −1..1, min ≤ max) |
| `baseScore` | 0.6 | 0..1: its score when every rule is met, before the planner's weights |

A rule that is not met rules the recipe out for that pair; the planner then picks another technique.

### A deck (`a`, `b`)

| field | meaning |
|---|---|
| `level`, `low`, `mid`, `high`, `hpf`, `lpf` | lanes (below) |
| `resonance` | filter resonance (Q) for both filters, 0.5..6; default 0.707 |
| `echo` | `{ "send": lane, "beats": 0.75, "feedback": 0.7, "dampHz": 4000, "returnLevel": 1 }` — a tempo-synced feedback delay. `beats` > 0 (in beats of the master tempo; 0.75 = a dotted eighth), `feedback` 0..0.95, `dampHz` 20..20000 (low-pass in the loop), `returnLevel` 0..2 |
| `reverb` | `{ "send": lane, "freeze": lane, "decaySec": 3, "dampHz": 6000, "returnLevel": 1 }` — `decaySec` > 0, `dampHz` 20..20000, `returnLevel` 0..2 |
| `stems` | `{ "drums": lane, "bass": lane, "vocals": lane, "other": lane }` — per-stem levels in dB |

Echo and reverb sends are taken **before** the deck's level fader, so a deck can be silenced while its echo or
reverb keeps ringing.

## Lanes

A lane is a list of points `{ "at": <bar>, "v": <value>, "curve": "linear" }` (`curve` is optional). `at` counts
bars from the start of the transition (bar 0). Before its first point a lane holds the first value; after its
last point it holds the last value; between two points it moves along the **earlier** point's curve. An empty (or
missing) lane leaves the deck untouched. Points are used in time order; write them in time order too (the
validator warns when they are not).

| lane | unit | range | neutral (untouched) |
|---|---|---|---|
| `level` | linear gain | 0..2 | 1 |
| `low`, `mid`, `high` | dB | −120..+12 (`"off"` = −120) | 0 |
| `hpf` | Hz | 20..20000 | 20 (open) |
| `lpf` | Hz | 20..20000 | 20000 (open) |
| `echo.send`, `reverb.send` | amount | 0..1 | 0 |
| `reverb.freeze` | switch | 0..1 (≥ 0.5 = frozen) | 0 |
| `stems.drums`, `.bass`, `.vocals`, `.other` | dB | −120..+12 | 0 |

`hpf` and `lpf` move on a logarithmic scale, so halfway between 20 Hz and 20000 Hz is about 630 Hz.

### Curves

| curve | shape from this point to the next |
|---|---|
| `linear` | straight line |
| `equalPower` | sine-shaped; a falling lane on one deck paired with a rising one on the other keeps the power constant (the classic −3 dB crossfade) |
| `sCurve` | slow start, slow end (smoothstep) |
| `exp` | slow start, fast end |
| `step` | holds this point's value, then jumps at the next point |

Two points at the same bar make a vertical step: at that bar, the later one wins.

## Expressions

Anywhere a number goes you can write an expression in quotes: `"bars - 1"`, `"swapBar + beat"`,
`"max(4, bars / 2)"`, `"-bassCut"`. Operators `+ - * /` and parentheses; functions `min`, `max`,
`clamp(x, lo, hi)`, `abs`, `round`, `floor`, `ceil`. Names are your knobs plus these built-ins:

| name | value | usable in |
|---|---|---|
| `beat` | one beat in bars (0.25 in 4/4) | everywhere |
| `off` | −120 (silence in dB) | everywhere |
| `bars` | the overlap length | everything except `lengthBars` |
| `settle`, `hold` | `settleBars`, `holdBars` | `bEntersAtBar`, `bEntryOffsetBars`, the EQ crossovers, lanes, deck settings |
| `total` | the whole timeline | `bEntryOffsetBars`, the EQ crossovers, lanes, deck settings |

Parentheses, function calls and signs (`-`, `+`) may nest up to 64 levels deep, counting the expression itself as
the first level; deeper is an error.

## The boundary rule

A transition is spliced between the two songs: just before it the listener hears A exactly as recorded, just
after it they hear B exactly as recorded. So:

- **At bar 0, deck A must be untouched**: level 1, every EQ band and stem at 0 dB, both filters open, both sends
  at 0 and the reverb not frozen. The transition then starts with A exactly as it was playing.
- **At `total`, deck B must be untouched** in the same way, so B's song continues seamlessly.
- A lane may not **jump** right at those edges either (for example a vertical step at bar 0, or a `step` curve
  that only reaches neutral at `total`): that would be heard as a click at the splice.

Breaking the boundary rule is an error. Two related things are warnings, because the engine copes:

- Deck A is only played during the overlap, so its `level` should reach 0 by `bars`. If it does not, the renderer
  stops A with a short declick fade.
- Echo and reverb sends, and the reverb freeze, should be back to 0 at least one bar before `total`, so the tail can
  die away. A vertical step down at `total - 1` counts (at that bar the later point wins). If not, the renderer
  cuts the tail with a fade.

When a render breaks one of these (deck B is checked where the rendered segment actually ends), the render report
carries a `boundary rule:` warning, and the plan notes say it too.

## Tempo modes

- **`match`** — beat-matched: B is time-stretched onto A's tempo for the overlap, then rides back to its own tempo
  over `settleBars` and holds it for `holdBars`.
- **`glide`** — beat-matched, with the master tempo gliding from A's tempo to B's across the overlap.
- **`none`** — no stretching: the timeline follows A's bars and B plays at its own tempo, its entry downbeat
  landing at `bEntersAtBar`. Works for any pair; if B enters after the overlap, the validator warns about the gap.

## Validation

`muisc recipe validate <file>...` reads each file and explains every problem with its path (`a.level[2].v`), and
the line and column in the file. It exits with 1 when any file has errors.

Reading the file reports JSON mistakes (with line and column, including objects and lists nested more than 64
levels deep), unknown keys (with a *did you mean* suggestion), and values of the wrong type. Then the recipe is checked:

**Errors** (the recipe is not used): an id that is not `[a-z0-9-]+`; a blank name; `ambition`, `baseScore` or the
energy deltas out of range; `minEnergyDelta` above `maxEnergyDelta`; negative `maxStretchPercent` or
`maxKeyDistance`; a format newer than the engine; knob names that are not identifiers or are built-in names; a knob
with `min` above `max` or its default outside its range; unknown names or syntax errors in any expression (every
one is reported, not just the first; nesting deeper than 64 levels is one); a lane value outside its range; a point before bar 0 or after `total`; the
boundary rule; echo feedback outside 0..0.95 or echo `beats` not above 0; `resonance` outside 0.5..6; `dampHz`
outside 20..20000; `returnLevel` outside 0..2; reverb `decaySec` not above 0; an unknown modifier; and anything the
timing checks refuse (overlap outside 1..64 bars, negative settle/hold/bEntersAtBar, crossovers out of order).

**Warnings** (the recipe works): A still audible at `bars`; a send or freeze still on within the last bar; stem
lanes (they need stem separation: without an ML separator Muisc uses pseudo-stems, which leak, so a stem handover
sounds more like a staggered EQ mix); a knob no expression uses; a knob whose `min` equals its `max`; a whole-number
knob with fractional limits; points out of time order; `bEntersAtBar` set outside `none` tempo; a gap between A and
B in `none` tempo; a modifier listed twice; `tempoGlide` in `modifiers`; in `match` / `glide`, a `lengthBars`,
`settleBars` or `holdBars` that is rendered as a different number of whole bars, or that uses a knob that is not a
whole-number knob. When installing a file would replace a built-in, `validate` says so.

**Every setting.** A recipe has to work wherever the knobs are set, not only at their defaults. The checks that
depend on the knobs run at the defaults, with each knob alone at its minimum and at its maximum (the others at
their defaults), with every knob at its minimum and with every knob at its maximum. A problem that only appears
away from the defaults names the setting, for example:

```
error at line 24, column 5 (b.low): with swapBar = 31: deck B must end exactly as its song continues, ...
```

Other combinations of knob values are not checked, so keep positions inside the transition with expressions like
`clamp(swapBar, 1, bars - 1)` rather than relying on the ranges alone.

## Working with recipes from the command line

```
muisc recipe list                                  # built-in and user recipes, with their status
muisc recipe show smooth-blend                     # resolved summary: timing, knobs, rules, lanes, problems
muisc recipe show smooth-blend --set len=24        # ... at other knob settings
muisc recipe lanes club-bass-swap --set swapBar=4  # every lane that does something: points and a small plot
muisc recipe new my-swap --from club-bass-swap     # copy a recipe into ~/.muisc/recipes/my-swap.json
muisc recipe new my-mix                            # a simple starter blend to edit
muisc recipe validate ~/.muisc/recipes/my-mix.json # check it
muisc render A.wav B.wav -o out.wav --strategy recipe:my-mix --set len=24 --context 8   # listen to it
```

`show` and `lanes` also accept a path to a `.json` file instead of an id, so a file can be inspected before it is
installed. Every `recipe` subcommand takes `--recipes-dir DIR` to use another user recipe directory; `new` takes
`--dir DIR` to write somewhere else and `--name` for the display name. `new` never overwrites an existing recipe.
