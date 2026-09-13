package dev.muisc.app.di

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import dev.muisc.analysis.AnalysisCache
import dev.muisc.app.data.LibraryRepository
import dev.muisc.app.data.MediaStoreScanner
import dev.muisc.app.data.RoomAnalysisCache
import dev.muisc.app.data.ScanResult
import dev.muisc.app.data.db.MuiscDatabase
import dev.muisc.app.data.prefs.SettingsRepository
import dev.muisc.app.playback.EngineController
import dev.muisc.app.playback.TransitionLabApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Library scan status for the UI (progress banner in the library screens, "Rescan" in Settings). */
sealed interface ScanStatus {
    data object Idle : ScanStatus
    data object Running : ScanStatus
    data class Done(val result: ScanResult, val atMs: Long) : ScanStatus
}

/**
 * Application-scoped singletons, created lazily on first use (manual DI; see DESIGN.md §8). Initialise once from
 * `MuiscApplication.onCreate` via [init]. The playback layer (`PlaybackService`) plugs its real [EngineController]
 * and [TransitionLabApi] in through [installPlayback]; until then the UI talks to delegating stand-ins that buffer
 * commands and start the service, so screens can be built and used before the service binds.
 */
object AppGraph {
    /** Fully-qualified class name of the playback service (referenced by name: the service is a later work package). */
    const val PLAYBACK_SERVICE_CLASS = "dev.muisc.app.playback.PlaybackService"

    private lateinit var app: Application

    /** Process-wide scope for background work that outlives any screen (scans, prefs propagation). */
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun init(application: Application) {
        app = application
    }

    val context: Context
        get() {
            check(::app.isInitialized) { "AppGraph.init(application) must be called from Application.onCreate" }
            return app
        }

    val db: MuiscDatabase by lazy { MuiscDatabase.build(context) }
    val settings: SettingsRepository by lazy { SettingsRepository(context) }
    val libraryRepository: LibraryRepository by lazy { LibraryRepository(db) }
    val scanner: MediaStoreScanner by lazy { MediaStoreScanner(context, db, settings) }
    val analysisCache: AnalysisCache by lazy { RoomAnalysisCache(db.analysisDao(), db.songDao()) }

    private val delegatingController by lazy { DelegatingEngineController(scope) { startPlaybackService() } }
    private val delegatingLab by lazy { DelegatingTransitionLab({ startPlaybackService() }) }

    /** The controller the UI, the notification and the media session adapter drive. Stable for the process lifetime. */
    val engineController: EngineController get() = delegatingController

    /** The Transition Lab entry point. Stable for the process lifetime. */
    val lab: TransitionLabApi get() = delegatingLab

    /** True while a real playback implementation is installed. */
    val playbackInstalled: StateFlow<Boolean> get() = delegatingController.installed

    /** Called by the playback service once its engine is ready. Buffered UI commands are replayed in order. */
    fun installPlayback(controller: EngineController, lab: TransitionLabApi) {
        delegatingController.install(controller)
        delegatingLab.install(lab)
    }

    /** Called by the playback service on destroy; the UI falls back to the idle state until the next install. */
    fun uninstallPlayback(controller: EngineController? = null, lab: TransitionLabApi? = null) {
        delegatingController.uninstall(controller)
        delegatingLab.uninstall(lab)
    }

    /**
     * Starts the playback service (by class name, so this module compiles before the service exists). Uses plain
     * `startService`: the service promotes itself to foreground when playback actually starts, which avoids the
     * "must call startForeground" crash for a service started only to bind the engine.
     */
    fun startPlaybackService() {
        try {
            val intent = Intent().setClassName(context, PLAYBACK_SERVICE_CLASS)
            context.startService(intent)
        } catch (e: Exception) {
            // Service missing or background-start restriction: the delegating controller keeps its buffer.
        }
    }

    // ---- library scanning ----

    private val scanStatusFlow = MutableStateFlow<ScanStatus>(ScanStatus.Idle)
    val scanStatus: StateFlow<ScanStatus> = scanStatusFlow.asStateFlow()
    private var scanJob: Job? = null

    /** True when the app may read audio files (READ_MEDIA_AUDIO on 33+, READ_EXTERNAL_STORAGE below). */
    fun hasLibraryPermission(): Boolean = ContextCompat.checkSelfPermission(context, libraryPermission()) == PackageManager.PERMISSION_GRANTED

    /** The runtime permission the UI must request before the library can be scanned. */
    fun libraryPermission(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_AUDIO
        else Manifest.permission.READ_EXTERNAL_STORAGE

    /**
     * Runs a library scan in the background (no-op without permission; a full scan requested while an incremental
     * one runs waits for it). The UI observes [scanStatus].
     */
    fun requestScan(full: Boolean = false) {
        if (!hasLibraryPermission()) return
        val running = scanJob
        scanJob = scope.launch {
            running?.join()
            scanStatusFlow.value = ScanStatus.Running
            val result = try {
                scanner.scan(full)
            } catch (e: Exception) {
                ScanResult(skipped = true, error = e.message ?: e.javaClass.simpleName)
            }
            scanStatusFlow.value = ScanStatus.Done(result, System.currentTimeMillis())
        }
    }
}
