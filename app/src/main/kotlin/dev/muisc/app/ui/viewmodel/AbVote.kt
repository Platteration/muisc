package dev.muisc.app.ui.viewmodel

import dev.muisc.app.data.db.Song
import dev.muisc.app.playback.CustomizationApi
import dev.muisc.app.playback.LabProgress
import dev.muisc.transitions.PlanCandidate
import dev.muisc.transitions.RenderedTransition
import dev.muisc.transitions.custom.Rating
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * A blind comparison of two candidates for the pair: both are rendered, played under the neutral labels X and Y
 * (which one is X is decided by a coin toss), and the vote becomes a rating — up for the winner, down for the
 * other — recorded in the learned preferences. The names are revealed only after the vote.
 */
data class AbTest(
    val first: PlanCandidate,
    val second: PlanCandidate,
    /** True when X is [first]. */
    val xIsFirst: Boolean,
    val renderX: RenderedTransition? = null,
    val renderY: RenderedTransition? = null,
    val rendering: Boolean = true,
    val progress: LabProgress? = null,
    /** 'X' or 'Y' while one is playing. */
    val playing: Char? = null,
    val position: Float = 0f,
    val heardX: Boolean = false,
    val heardY: Boolean = false,
    /** Set once the vote is in: what X and Y were, and what was recorded. */
    val reveal: String? = null,
    val error: String? = null,
    /** True while a vote is being recorded: no second vote is taken (see [castAbVote]). */
    val voting: Boolean = false,
) {
    val x: PlanCandidate get() = if (xIsFirst) first else second
    val y: PlanCandidate get() = if (xIsFirst) second else first
    val ready: Boolean get() = renderX != null && renderY != null
    val canVote: Boolean get() = ready && heardX && heardY && reveal == null && !voting
}

/**
 * The vote of the blind A/B test held in [state] ([LabViewModel.voteAb]), kept out of the ViewModel so the JVM tests
 * can run it. [abOf] and [withAb] read and replace the test inside the screen state [S]. [winner] 'X' or 'Y'
 * records up for it and down for the other; null ("no preference") records nothing. Returns the job recording the
 * vote, or null when this call did not vote.
 *
 * One vote per test: the test is marked [AbTest.voting] in [state] before anything suspends, so a second tap while
 * the first vote is being recorded (a double-tap, or X then Y) finds [AbTest.canVote] false and records nothing.
 * When the vote is in, the names are revealed. When nothing was recorded (the winner's rating failed, so the loser
 * is not rated either) the test is put back with the reason in [AbTest.error] and the user can vote again. When only
 * the loser's rating failed, the winner's is already stored and a second vote would count it twice, so the names
 * are revealed with what was and was not recorded. A vote only ever lands on the test it was cast on: if that test
 * was closed or replaced meanwhile, the newer one is left alone.
 */
fun <S> castAbVote(
    state: MutableStateFlow<S>,
    abOf: (S) -> AbTest?,
    withAb: (S, AbTest) -> S,
    scope: CoroutineScope,
    customization: CustomizationApi,
    a: Song,
    b: Song,
    winner: Char?,
): Job? {
    var claimed: AbTest? = null
    state.update { st ->
        val t = abOf(st)
        claimed = t?.takeIf { it.canVote }
        if (claimed == null) st else withAb(st, t!!.copy(voting = true, error = null))
    }
    val test = claimed ?: return null
    // The test as it is while this vote is recorded (playback position and the like may still change).
    fun sameTest(t: AbTest) = t.voting && t.first == test.first && t.second == test.second && t.xIsFirst == test.xIsFirst
    return scope.launch {
        val names = "X was ${test.x.strategy.displayName}, Y was ${test.y.strategy.displayName}."
        var reveal: String? = null
        var error: String? = null
        if (winner == null) {
            reveal = "$names No preference — nothing recorded."
        } else {
            val (win, lose) = if (winner == 'X') test.x to test.y else test.y to test.x
            val up = customization.rate(a, b, win.strategy.id, Rating.Up)
            if (!up.ok) {
                error = "Could not record the vote: ${up.message}. Nothing was recorded; vote again."
            } else {
                val down = customization.rate(a, b, lose.strategy.id, Rating.Down)
                reveal = if (down.ok) "$names Recorded: ${win.strategy.displayName} up, ${lose.strategy.displayName} down."
                else "$names Recorded: ${win.strategy.displayName} up. Could not record ${lose.strategy.displayName} down: ${down.message}"
            }
        }
        state.update { st ->
            val t = abOf(st)
            if (t == null || !sameTest(t)) st else withAb(st, t.copy(voting = false, reveal = reveal, error = error))
        }
    }
}
