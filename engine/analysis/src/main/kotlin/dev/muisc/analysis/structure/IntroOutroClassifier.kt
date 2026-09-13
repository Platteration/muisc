package dev.muisc.analysis.structure

import dev.muisc.analysis.model.BeatGrid
import dev.muisc.analysis.model.IntroType
import dev.muisc.analysis.model.OutroType
import dev.muisc.analysis.model.Section

/**
 * Classifies how a track starts and ends from its sections and per-beat activity.
 *
 * "First section" / "last section" are the first / last entries of the section list; the *body* reference energy
 * is the median beat energy outside the first (and, with ≥ 3 sections, the last) section, or of the whole track
 * when there is only one section. `present` means ≥ [BeatActivity.DRUMS_PRESENT], `absent` means
 * < [BeatActivity.DRUMS_ABSENT]; in between is undecided and falls through to the next rule.
 *
 * Intro decision table (first matching row wins):
 *
 * | IntroType     | condition                                                                                     |
 * |---------------|-----------------------------------------------------------------------------------------------|
 * | UNKNOWN       | no sections / no valid beats / zero energy everywhere (digital silence)                       |
 * | SILENCE       | `trimStartFrame` > [silenceSec] s AND first-section energy < [nearSilentEnergy]               |
 * | COLD_START    | drums present AND energy ≥ [coldStartFrac]·body within the first [coldStartBeats] beats       |
 * | VOCAL_INTRO   | bar features given, first-section vocals ≥ VOCALS_PRESENT and drums absent                   |
 * | AMBIENT_INTRO | first-section drums absent                                                                    |
 * | BEAT_INTRO    | first-section drums ≥ DRUMS_ABSENT and energy < [beatIntroFrac]·body                          |
 * | UNKNOWN       | otherwise (e.g. drums start a few beats late at full level)                                   |
 *
 * Outro decision table. The caller's silence-analysis flags take precedence over content, in this order:
 * FADE_OUT > HARD_STOP > content-based. Rationale: a detected fade is the strongest evidence of an intentional
 * ending (and the planner must mix before it); a hard stop means the level does not decay before the file ends,
 * so even a "beat outro" cannot be ridden out — the planner needs to know that before it needs to know whether
 * drums were playing.
 *
 * | OutroType      | condition                                                                                    |
 * |----------------|----------------------------------------------------------------------------------------------|
 * | UNKNOWN        | no sections / no valid beats / zero energy (flags are still honoured: FADE_OUT / HARD_STOP win even then) |
 * | FADE_OUT       | `fadeDetected`                                                                               |
 * | HARD_STOP      | `hardStop`                                                                                   |
 * | VOCAL_OUTRO    | bar features given, last-section vocals ≥ VOCALS_PRESENT and drums absent                    |
 * | AMBIENT_OUTRO  | last-section drums absent, OR drums absent over the last bar (they left before the end)       |
 * | BEAT_OUTRO     | drums present over the last bar and last-section drums ≥ DRUMS_ABSENT                        |
 * | UNKNOWN        | otherwise                                                                                    |
 */
class IntroOutroClassifier(
    /** Leading trim (seconds) above which a near-silent first section is a SILENCE intro. */
    val silenceSec: Double = 1.0,
    /** First-section energy below this is "near silent". */
    val nearSilentEnergy: Float = 0.1f,
    /** Beats inspected for a cold start. */
    val coldStartBeats: Int = 2,
    /** Fraction of the body energy the first beats must reach for COLD_START. */
    val coldStartFrac: Float = 0.7f,
    /** First-section energy below this fraction of the body marks a BEAT_INTRO. */
    val beatIntroFrac: Float = 0.85f,
) {
    fun classifyIntro(sections: List<Section>, activity: BeatActivity, grid: BeatGrid, trimStartFrame: Long, sampleRate: Int): IntroType {
        if (sections.isEmpty() || activity.beatCount == 0 || grid.isEmpty) return IntroType.UNKNOWN
        val first = sections.first()
        val firstE = activity.meanEnergy(first.startBeat, first.endBeat)
        val firstD = activity.meanDrums(first.startBeat, first.endBeat)
        val firstV = activity.meanVocals(first.startBeat, first.endBeat)
        val body = bodyEnergy(sections, activity)
        if (body <= 0f) return IntroType.UNKNOWN // digital silence: nothing to classify
        val trimSec = trimStartFrame.toDouble() / sampleRate
        if (trimSec > silenceSec && firstE < nearSilentEnergy) return IntroType.SILENCE
        val headE = activity.meanEnergy(first.startBeat, first.startBeat + coldStartBeats)
        val headD = activity.meanDrums(first.startBeat, first.startBeat + coldStartBeats)
        if (headD >= BeatActivity.DRUMS_PRESENT && headE >= coldStartFrac * body) return IntroType.COLD_START
        if (activity.hasVocals && firstV >= BeatActivity.VOCALS_PRESENT && firstD < BeatActivity.DRUMS_ABSENT) return IntroType.VOCAL_INTRO
        if (firstD < BeatActivity.DRUMS_ABSENT) return IntroType.AMBIENT_INTRO
        if (firstE < beatIntroFrac * body) return IntroType.BEAT_INTRO
        return IntroType.UNKNOWN
    }

    fun classifyOutro(sections: List<Section>, activity: BeatActivity, grid: BeatGrid, fadeDetected: Boolean, hardStop: Boolean): OutroType {
        if (fadeDetected) return OutroType.FADE_OUT
        if (hardStop) return OutroType.HARD_STOP
        if (sections.isEmpty() || activity.beatCount == 0 || grid.isEmpty) return OutroType.UNKNOWN
        val last = sections.last()
        val lastD = activity.meanDrums(last.startBeat, last.endBeat)
        val lastV = activity.meanVocals(last.startBeat, last.endBeat)
        // last bar = last beatsPerBar valid beats
        if (bodyEnergy(sections, activity) <= 0f) return OutroType.UNKNOWN // digital silence
        val end = lastValidBeat(activity) + 1
        val tailD = activity.meanDrums(end - grid.beatsPerBar, end)
        if (activity.hasVocals && lastV >= BeatActivity.VOCALS_PRESENT && lastD < BeatActivity.DRUMS_ABSENT) return OutroType.VOCAL_OUTRO
        if (lastD < BeatActivity.DRUMS_ABSENT || tailD < BeatActivity.DRUMS_ABSENT) return OutroType.AMBIENT_OUTRO
        if (tailD >= BeatActivity.DRUMS_PRESENT) return OutroType.BEAT_OUTRO
        return OutroType.UNKNOWN
    }

    /** Median beat energy of the body (see class KDoc). */
    internal fun bodyEnergy(sections: List<Section>, activity: BeatActivity): Float {
        val n = activity.beatCount
        val from = if (sections.size >= 2) sections.first().endBeat else 0
        val to = if (sections.size >= 3) sections.last().startBeat else n
        val m = activity.medianEnergy(from, to)
        return if (m > 0f) m else activity.medianEnergy(0, n)
    }

    private fun lastValidBeat(activity: BeatActivity): Int {
        var k = activity.beatCount - 1
        while (k > 0 && !activity.valid[k]) k--
        return k
    }
}
