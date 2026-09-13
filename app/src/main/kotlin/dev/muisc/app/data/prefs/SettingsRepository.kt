package dev.muisc.app.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.muisc.transitions.TransitionPrefs
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

/** App colour theme. BLACK is the AMOLED variant of DARK. */
enum class ThemeMode { SYSTEM, LIGHT, DARK, BLACK }

/** Now Playing screen layout (see DESIGN.md §8). */
enum class NowPlayingLayout { NORMAL, CARD, BLUR, ADAPTIVE, MINIMAL }

/** Everything the UI and the scanner read from settings that is not part of [TransitionPrefs]. */
data class UiPrefs(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    /** Material You colours from the wallpaper (API 31+). */
    val dynamicColor: Boolean = true,
    val nowPlayingLayout: NowPlayingLayout = NowPlayingLayout.NORMAL,
    /** Files shorter than this are not shown in the library. */
    val minDurationSec: Int = 30,
    /** Absolute folder paths (no trailing slash) whose files are excluded from the library. */
    val blacklist: Set<String> = emptySet(),
    /** Batch analysis runs only while charging (else: battery not low and no power-save). */
    val analyseOnlyWhileCharging: Boolean = true,
    /** Skip the transition between consecutive tracks of the same album inside a playlist. */
    val keepAlbumFlowInPlaylists: Boolean = false,
)

/** Bookkeeping for incremental MediaStore scans (not user-facing). */
data class ScanState(
    /** `MediaStore.getGeneration` at the end of the last scan (API 30+), -1 when unknown. */
    val lastGeneration: Long = -1L,
    /** Largest `DATE_MODIFIED` (epoch seconds) seen in the last scan; 0 = never scanned. */
    val lastDateModifiedSec: Long = 0L,
    /** Wall-clock end of the last completed scan, epoch milliseconds (0 = never). */
    val lastScanAtMs: Long = 0L,
) {
    val hasScanned: Boolean get() = lastScanAtMs > 0L
}

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "muisc_settings")

/**
 * DataStore-backed settings. UI preferences are stored as individual keys; the engine's [TransitionPrefs] are stored
 * as one JSON blob (kotlinx.serialization) so they round-trip unchanged between phone and CLI. Reads are [Flow]s
 * that emit the current value and every later change; an unreadable store yields defaults rather than crashing.
 */
class SettingsRepository(context: Context) {
    private val store: DataStore<Preferences> = context.applicationContext.settingsDataStore

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** Raw preferences with IO failures mapped to an empty store (defaults). */
    private val safeData: Flow<Preferences> = store.data.catch { e ->
        if (e is IOException) emit(emptyPreferences()) else throw e
    }

    val uiPrefs: Flow<UiPrefs> = safeData.map { it.toUiPrefs() }.distinctUntilChanged()

    val transitionPrefs: Flow<TransitionPrefs> = safeData.map { it.toTransitionPrefs() }.distinctUntilChanged()

    val scanState: Flow<ScanState> = safeData.map { it.toScanState() }.distinctUntilChanged()

    suspend fun currentUiPrefs(): UiPrefs = uiPrefs.first()
    suspend fun currentTransitionPrefs(): TransitionPrefs = transitionPrefs.first()
    suspend fun currentScanState(): ScanState = scanState.first()

    /** Applies [transform] to the current UI prefs atomically. */
    suspend fun updateUi(transform: (UiPrefs) -> UiPrefs) {
        store.edit { p ->
            val next = transform(p.toUiPrefs())
            p[Keys.THEME] = next.theme.name
            p[Keys.DYNAMIC_COLOR] = next.dynamicColor
            p[Keys.NOW_PLAYING_LAYOUT] = next.nowPlayingLayout.name
            p[Keys.MIN_DURATION_SEC] = next.minDurationSec
            p[Keys.BLACKLIST] = next.blacklist
            p[Keys.ANALYSE_ONLY_WHILE_CHARGING] = next.analyseOnlyWhileCharging
            p[Keys.KEEP_ALBUM_FLOW_IN_PLAYLISTS] = next.keepAlbumFlowInPlaylists
        }
    }

    /** Replaces the stored [TransitionPrefs]. */
    suspend fun update(prefs: TransitionPrefs) {
        store.edit { p -> p[Keys.TRANSITION_PREFS_JSON] = json.encodeToString(TransitionPrefs.serializer(), prefs) }
    }

    /** Applies [transform] to the current [TransitionPrefs] atomically. */
    suspend fun updateTransition(transform: (TransitionPrefs) -> TransitionPrefs) {
        store.edit { p ->
            val next = transform(p.toTransitionPrefs())
            p[Keys.TRANSITION_PREFS_JSON] = json.encodeToString(TransitionPrefs.serializer(), next)
        }
    }

    suspend fun updateScanState(state: ScanState) {
        store.edit { p ->
            p[Keys.SCAN_GENERATION] = state.lastGeneration
            p[Keys.SCAN_DATE_MODIFIED] = state.lastDateModifiedSec
            p[Keys.SCAN_AT] = state.lastScanAtMs
        }
    }

    // ---- mapping ----

    private fun Preferences.toUiPrefs(): UiPrefs {
        val d = UiPrefs()
        return UiPrefs(
            theme = enumOrDefault(this[Keys.THEME], ThemeMode.entries, d.theme),
            dynamicColor = this[Keys.DYNAMIC_COLOR] ?: d.dynamicColor,
            nowPlayingLayout = enumOrDefault(this[Keys.NOW_PLAYING_LAYOUT], NowPlayingLayout.entries, d.nowPlayingLayout),
            minDurationSec = this[Keys.MIN_DURATION_SEC] ?: d.minDurationSec,
            blacklist = this[Keys.BLACKLIST] ?: d.blacklist,
            analyseOnlyWhileCharging = this[Keys.ANALYSE_ONLY_WHILE_CHARGING] ?: d.analyseOnlyWhileCharging,
            keepAlbumFlowInPlaylists = this[Keys.KEEP_ALBUM_FLOW_IN_PLAYLISTS] ?: d.keepAlbumFlowInPlaylists,
        )
    }

    private fun Preferences.toTransitionPrefs(): TransitionPrefs {
        val raw = this[Keys.TRANSITION_PREFS_JSON] ?: return TransitionPrefs()
        return try {
            json.decodeFromString(TransitionPrefs.serializer(), raw)
        } catch (e: Exception) {
            TransitionPrefs()
        }
    }

    private fun Preferences.toScanState(): ScanState = ScanState(
        lastGeneration = this[Keys.SCAN_GENERATION] ?: -1L,
        lastDateModifiedSec = this[Keys.SCAN_DATE_MODIFIED] ?: 0L,
        lastScanAtMs = this[Keys.SCAN_AT] ?: 0L,
    )

    private fun <E : Enum<E>> enumOrDefault(name: String?, values: List<E>, default: E): E =
        values.firstOrNull { it.name == name } ?: default

    private object Keys {
        val THEME = stringPreferencesKey("ui.theme")
        val DYNAMIC_COLOR = booleanPreferencesKey("ui.dynamicColor")
        val NOW_PLAYING_LAYOUT = stringPreferencesKey("ui.nowPlayingLayout")
        val MIN_DURATION_SEC = intPreferencesKey("library.minDurationSec")
        val BLACKLIST = stringSetPreferencesKey("library.blacklist")
        val ANALYSE_ONLY_WHILE_CHARGING = booleanPreferencesKey("analysis.onlyWhileCharging")
        val KEEP_ALBUM_FLOW_IN_PLAYLISTS = booleanPreferencesKey("transitions.keepAlbumFlowInPlaylists")
        val TRANSITION_PREFS_JSON = stringPreferencesKey("transitions.prefsJson")
        val SCAN_GENERATION = longPreferencesKey("scan.generation")
        val SCAN_DATE_MODIFIED = longPreferencesKey("scan.dateModifiedSec")
        val SCAN_AT = longPreferencesKey("scan.atMs")
    }
}
