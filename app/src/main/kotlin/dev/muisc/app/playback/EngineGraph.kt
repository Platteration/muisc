package dev.muisc.app.playback

import android.app.ActivityManager
import android.content.Context
import android.media.AudioManager
import dev.muisc.app.di.AppGraph
import dev.muisc.metrics.ArtifactMetrics
import dev.muisc.metrics.Verdict
import dev.muisc.player.AudioSink
import dev.muisc.player.EngineLimits
import dev.muisc.player.RenderGate
import dev.muisc.transitions.DefaultProgramBuilder
import dev.muisc.transitions.DefaultStrategyRegistry
import dev.muisc.transitions.DefaultTransitionRenderer
import dev.muisc.transitions.RenderedTransition
import dev.muisc.transitions.TransitionInput
import dev.muisc.transitions.live.DefaultLivePlanFactory
import dev.muisc.transitions.planner.DefaultTransitionPlanner
import kotlinx.coroutines.launch

/**
 * Builds the whole playback stack in one place; `PlaybackService` calls [create] in `onCreate` and hands the two
 * results to `AppGraph.installPlayback`.
 *
 * Choices made here (DESIGN §2.7, §7.1, §7.2):
 *  - **Engine rate** = `AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE` (usually 48 000), so nothing resamples on the way
 *    to `AudioTrack`; stereo output. Every `TransitionPrefs` that reaches the engine is forced to this format.
 *  - **Limits** = [EngineLimits.LOW_RAM] on a low-RAM device or below a 192 MB heap, else [EngineLimits.PHONE].
 *  - **Catalogue** = `DefaultStrategyRegistry.default()` with the default planner, the shared renderer over the
 *    Android window loader, pseudo-stems (ML stems only when a model is really installed, see [MlStemSeparatorOnnx]).
 *  - **Render gate** = `ArtifactMetrics.installChecks`: a render whose worst verdict is FAIL is never installed.
 */
object EngineGraph {

    /** Stereo everywhere; the engine mixes two decks and `AudioTrack` is opened stereo. */
    const val CHANNELS = 2

    /** Used when the platform will not say what its output rate is. */
    const val DEFAULT_SAMPLE_RATE = 48_000

    /** `ActivityManager.memoryClass` below this (MB) counts as a low-RAM device (DESIGN §7.4). */
    const val LOW_RAM_MEMORY_CLASS = 192

    /**
     * The whole engine, wired. Returns the controller the UI drives and the Lab over the same planner / renderer.
     * Call once per service lifetime; `EngineController.release()` tears everything down.
     */
    fun create(context: Context, appGraph: AppGraph = AppGraph): Pair<EngineController, TransitionLabApi> {
        val app = context.applicationContext
        val sampleRate = engineSampleRate(app)
        val limits = limitsFor(app)

        val streams = AndroidEngineStreamFactory(app)
        val loader = AndroidTrackAudioLoader(app)
        val registry = DefaultStrategyRegistry.default()
        val planner = DefaultTransitionPlanner(registry)
        val renderer = DefaultTransitionRenderer(loader, registry, MlStemSeparatorOnnx.best(app))
        val programBuilder = DefaultProgramBuilder()
        val liveFactory = DefaultLivePlanFactory()
        val gate = InstallCheckGate()

        val analyses = AndroidAnalysisService(
            context = app,
            cache = appGraph.analysisCache,
            streams = streams,
            sampleRate = sampleRate,
            channels = CHANNELS,
        )

        val sinkFactory: (Int, Int) -> AudioSink = { rate, channels -> AudioTrackSink(rate, channels) }

        val controller = EngineControllerImpl(
            context = app,
            sampleRate = sampleRate,
            channels = CHANNELS,
            limits = limits,
            streams = streams,
            planner = planner,
            renderer = renderer,
            liveFactory = liveFactory,
            programBuilder = programBuilder,
            analyses = analyses,
            gate = gate,
            windows = loader,
            registry = registry,
            settings = appGraph.settings,
            sinkFactory = sinkFactory,
            queue = QueueManager(),
            persistence = QueuePersistence(app),
            onSongStarted = { song, playbackContext ->
                appGraph.scope.launch { appGraph.libraryRepository.recordPlay(song.id, playbackContext) }
            },
        )

        val lab = TransitionLabImpl(
            context = app,
            controller = controller,
            analyses = analyses,
            registry = registry,
            planner = planner,
            renderer = renderer,
            streams = streams,
            sampleRate = sampleRate,
            channels = CHANNELS,
            limits = limits,
            settings = appGraph.settings,
            transitionDao = appGraph.db.transitionDao(),
            sinkFactory = sinkFactory,
            prefsProvider = { controller.state.value.transitionPrefs },
        )

        val powerMonitor = PowerModeMonitor(app) { mode -> controller.setPowerMode(mode) }
        powerMonitor.start()
        controller.attachPowerMonitor(powerMonitor)

        // Bring back what was playing last time (paused; the saved position is applied when playback starts).
        controller.restoreLastQueue { ids -> appGraph.db.songDao().byIds(ids) }

        // Keep the library pre-analysed so transitions are planned from real grids, not from placeholders.
        appGraph.scope.launch {
            val onlyWhileCharging = try {
                appGraph.settings.currentUiPrefs().analyseOnlyWhileCharging
            } catch (t: Throwable) {
                true
            }
            AnalysisWorker.enqueuePeriodic(app, onlyWhileCharging)
        }

        return controller to lab
    }

    /** The device's output rate — the rate everything in the engine is rendered at. */
    fun engineSampleRate(context: Context): Int {
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val reported = try {
            manager?.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 0
        } catch (t: Throwable) {
            0
        }
        return if (reported in 8_000..192_000) reported else DEFAULT_SAMPLE_RATE
    }

    /** Memory budget of this device class (DESIGN §2.7). */
    fun limitsFor(context: Context): EngineLimits {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val lowRam = try {
            manager == null || manager.isLowRamDevice || manager.memoryClass < LOW_RAM_MEMORY_CLASS
        } catch (t: Throwable) {
            false
        }
        return if (lowRam) EngineLimits.LOW_RAM else EngineLimits.PHONE
    }
}

/**
 * The phone-side [RenderGate]: `ArtifactMetrics.installChecks` (clicks, level jump, true peak, NaN/Inf, silence
 * gaps, seam identity) on the render plus the windows the strategy saw. A FAIL verdict rejects the render and the
 * coordinator falls to the next candidate; WARN is installed (it is still better than a blind crossfade).
 * Anything thrown by the checks themselves degrades to the cheap report-only check rather than losing the render.
 */
class InstallCheckGate : RenderGate {
    override fun accept(rendered: RenderedTransition, input: TransitionInput): Boolean {
        val report = try {
            ArtifactMetrics.installChecks(rendered, input)
        } catch (t: Throwable) {
            return accept(rendered)
        }
        return report.worst != Verdict.FAIL && accept(rendered)
    }
}
