package dev.muisc.transitions.custom

import dev.muisc.transitions.PairFeatures
import java.io.File

/**
 * What the user has taught the planner, handed to [dev.muisc.transitions.planner.DefaultTransitionPlanner]:
 *
 *  - [presets] resolves the ids in `prefs.activePresets` and in pins (built-ins only by default);
 *  - [pins] finds the user's pin for an ordered pair ([PairPin]);
 *  - [learner] supplies the learned multiplier per (strategy, context bucket) ([FeedbackLearner]).
 *
 * [NONE] has no pins and no learner, so with it the planner scores exactly as it did before customization existed
 * (a `prefs.activePresets` entry naming a built-in preset still resolves).
 */
class PlannerCustomization(
    val presets: PresetLookup = PresetLookup.BUILT_IN,
    val pins: PinLookup = PinLookup.NONE,
    val learner: FeedbackLearner? = null,
) {
    /** The learned factor for [strategyId] on a pair with [features]; neutral without a learner. */
    fun learned(strategyId: String, features: PairFeatures): LearnedFactor = learner?.factor(strategyId, features) ?: LearnedFactor.NEUTRAL

    companion object {
        val NONE: PlannerCustomization = PlannerCustomization()
    }
}

/**
 * The on-disk layout of a user's customization directory (the CLI's `--profile-dir`, default `~/.muisc`):
 *
 * ```
 * <dir>/presets/<id>.json    user presets        (FilePresetStore)
 * <dir>/styles/<id>.json     user styles         (FileStyleStore)
 * <dir>/pins.json            pinned pairs        (FilePinStore)
 * <dir>/feedback.json        rating tallies      (FileFeedbackStore)
 * ```
 *
 * Nothing is created until something is saved. Saving a pin or a rating also creates an empty hidden lock file next
 * to it (`.pins.json.lock`, `.feedback.json.lock`), which lets several programs share the directory safely.
 * Several [UserProfile]s (or programs) may use one directory at once: pins and ratings are re-read when their
 * files change and saved with a read-modify-write under that lock, so none of them overwrites another's changes.
 */
class UserProfile(val dir: File) {
    val presets: FilePresetStore = FilePresetStore(File(dir, "presets"))
    val styles: FileStyleStore = FileStyleStore(File(dir, "styles"))
    val pins: FilePinStore = FilePinStore(File(dir, "pins.json"))
    val feedback: FileFeedbackStore by lazy { FileFeedbackStore(File(dir, "feedback.json")) }

    /** Built-in and user presets. */
    val presetLookup: PresetLookup get() = presets.withBuiltIns()

    /** A built-in or user style. */
    fun style(id: String): StyleProfile? = styles.find(id)

    /**
     * The planner customization of this profile. [sessionPin], when given, applies to every pair and takes precedence
     * over the stored pins (the CLI's `--preset`).
     */
    fun customization(sessionPin: PairPin? = null): PlannerCustomization = PlannerCustomization(
        presets = presetLookup,
        pins = if (sessionPin == null) pins else PinLookup.chain(PinLookup.always(sessionPin), pins),
        learner = feedback.learner,
    )

    /** Reads every part of the profile and returns the problems found (files and entries that were skipped). */
    fun warnings(): List<String> {
        presets.list(); styles.list(); pins.list()
        return presets.warnings + styles.warnings + pins.warnings + feedback.warnings
    }
}
