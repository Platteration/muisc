package dev.muisc.app

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import dev.muisc.app.di.AppGraph
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Application entry point: wires [AppGraph], kicks off an incremental library scan when the storage permission is
 * already granted, and keeps the library in sync with MediaStore (content observer → debounced rescan) and with
 * the library settings (min duration / blacklist change → full rescan). The UI calls `AppGraph.requestScan()` itself
 * right after the permission is granted for the first time.
 */
class MuiscApplication : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()
        AppGraph.init(this)
        AppGraph.requestScan(full = false)
        observeMediaStore()
        observeLibrarySettings()
    }

    /** Debounced rescan on MediaStore changes: wait for a quiet second, then scan incrementally. */
    private fun observeMediaStore() {
        AppGraph.scope.launch {
            AppGraph.scanner.changes().conflate().collectLatest {
                delay(1_500)
                AppGraph.requestScan(full = false)
            }
        }
    }

    /** A change to the scan-relevant settings (min duration, blacklist) needs a full re-read. */
    private fun observeLibrarySettings() {
        AppGraph.scope.launch {
            AppGraph.settings.uiPrefs
                .map { it.minDurationSec to it.blacklist }
                .distinctUntilChanged()
                .drop(1)
                .collectLatest { AppGraph.requestScan(full = true) }
        }
    }

    /** Coil defaults shared by every image on screen. */
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .crossfade(true)
            .build()
}
