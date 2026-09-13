package dev.muisc.app.ui.nowplaying

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import dev.muisc.analysis.model.BeatGrid

/**
 * Placeholder waveform strip: draws beat ticks (downbeats taller, phrase starts accented) from the track's
 * [BeatGrid] when an analysis is cached, plus the playback cursor and an optional planned transition region.
 * Tapping seeks. Until real waveform peaks exist, the strip shows a flat baseline.
 */
@Composable
fun WaveformStrip(
    positionMs: Long,
    durationMs: Long,
    modifier: Modifier = Modifier,
    grid: BeatGrid? = null,
    sampleRate: Int = 44_100,
    /** Start/end of the planned transition in ms, or null. */
    transitionRegionMs: Pair<Long, Long>? = null,
    onSeek: ((Long) -> Unit)? = null,
) {
    val tickColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
    val downbeatColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
    val phraseColor = MaterialTheme.colorScheme.primary
    val cursorColor = MaterialTheme.colorScheme.primary
    val playedColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
    val regionColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.3f)
    val baseline = MaterialTheme.colorScheme.outlineVariant

    val seekModifier = if (onSeek != null && durationMs > 0) {
        Modifier.pointerInput(durationMs) {
            detectTapGestures { offset ->
                val fraction = (offset.x / size.width).coerceIn(0f, 1f)
                onSeek((fraction * durationMs).toLong())
            }
        }
    } else Modifier

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .then(seekModifier),
    ) {
        val w = size.width
        val h = size.height
        val mid = h / 2f
        if (durationMs <= 0) {
            drawLine(baseline, Offset(0f, mid), Offset(w, mid), strokeWidth = 2f)
            return@Canvas
        }
        val msToX = { ms: Long -> (ms.toFloat() / durationMs.toFloat()) * w }

        // Played part
        drawRect(playedColor, topLeft = Offset(0f, 0f), size = androidx.compose.ui.geometry.Size(msToX(positionMs).coerceIn(0f, w), h))

        // Planned transition region
        if (transitionRegionMs != null) {
            val x0 = msToX(transitionRegionMs.first).coerceIn(0f, w)
            val x1 = msToX(transitionRegionMs.second).coerceIn(0f, w)
            if (x1 > x0) drawRect(regionColor, topLeft = Offset(x0, 0f), size = androidx.compose.ui.geometry.Size(x1 - x0, h))
        }

        drawLine(baseline, Offset(0f, mid), Offset(w, mid), strokeWidth = 2f)

        // Beat ticks (thinned when there are more beats than pixels)
        if (grid != null && !grid.isEmpty && sampleRate > 0) {
            val frames = grid.beatFrames
            val step = maxOf(1, frames.size / (w / 3f).toInt().coerceAtLeast(1))
            var i = 0
            while (i < frames.size) {
                val ms = frames[i] * 1000L / sampleRate
                val x = msToX(ms)
                if (x in 0f..w) {
                    val phrase = grid.isPhraseStart(i)
                    val down = grid.isDownbeat(i)
                    val half = when {
                        phrase -> h * 0.45f
                        down -> h * 0.32f
                        else -> h * 0.16f
                    }
                    val color: Color = when {
                        phrase -> phraseColor
                        down -> downbeatColor
                        else -> tickColor
                    }
                    drawLine(color, Offset(x, mid - half), Offset(x, mid + half), strokeWidth = if (down) 2f else 1f)
                }
                i += step
            }
        }

        // Cursor
        val cx = msToX(positionMs).coerceIn(0f, w)
        drawLine(cursorColor, Offset(cx, 0f), Offset(cx, h), strokeWidth = 3f)
    }
}
