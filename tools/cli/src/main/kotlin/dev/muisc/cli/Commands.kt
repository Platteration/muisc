package dev.muisc.cli

import com.github.ajalt.clikt.core.CliktCommand

/**
 * Registry of CLI subcommands, in the order they appear in `muisc --help`: make material, look at it, plan,
 * render, compare, then the utilities.
 *
 * See `docs/CLI.md` for what each one does and an example of every switch.
 */
fun allCommands(): List<CliktCommand> = listOf(
    SynthCommand(),      // synth   — write synthetic test songs
    AnalyzeCommand(),    // analyze — tempo/key/loudness/structure, optional click track
    PlanCommand(),       // plan    — rank every strategy for a pair and explain the scores
    RenderCommand(),     // render  — one transition to WAV + plan + report (+ context render)
    MixCommand(),        // mix     — a whole mini DJ set through the real player
    OrderCommand(),      // order   — smart shuffle: order tracks so every neighbouring pair mixes well
    AbCommand(),         // ab      — every strategy on one pair, with an HTML comparison page
    SweepCommand(),      // sweep   — a parameter grid with CSV and an SVG plot
    ScoreCommand(),      // score   — pairwise best-strategy/score matrix
    CheckCommand(),      // check   — metrics report for any WAV
    LiveCommand(),       // live    — audition the real-time fallback moves
    StemsCommand(),      // stems   — pseudo-stem separation to four WAVs
    PlayCommand(),       // play    — play a WAV through the system device
    GoldensCommand.build(), // goldens check|update — golden-render regression
    EvalCommand(),          // eval    — plan and render sampled pairs of a library into an HTML/CSV/JSON report
    BenchCommand.build(),   // bench analysis — analysis accuracy on synthetic songs with known answers
    RecipeCommand.build(),  // recipe  — list, show, validate, create and plot user-authored transitions
    PresetCommand(),        // preset  — named parameter sets for a strategy
    StyleCommand(),         // style   — listening styles that reshape the preferences (--style)
    PinCommand(),           // pin     — always use a chosen transition for a pair of tracks
    RateCommand(),          // rate    — thumbs up/down that the planner learns from
    LabCommand(),           // lab     — local web page to audition, tweak, sweep and blind-test transitions
)
