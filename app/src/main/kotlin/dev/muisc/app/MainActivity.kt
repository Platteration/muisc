package dev.muisc.app

import android.content.ComponentName
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import dev.muisc.app.ui.MuiscApp

/**
 * Single activity. Edge-to-edge; all chrome is Compose. On start it connects a [MediaController] to the playback
 * service so the service (and with it the real EngineController) is up while the UI is visible.
 */
class MainActivity : ComponentActivity() {

    private var controllerFuture: ListenableFuture<MediaController>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { MuiscApp() }
    }

    override fun onStart() {
        super.onStart()
        connectToPlaybackService()
    }

    override fun onStop() {
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        super.onStop()
    }

    private fun connectToPlaybackService() {
        if (controllerFuture != null) return
        try {
            // Resolved by name so this file compiles before the service class lands.
            val token = SessionToken(this, ComponentName(this, PLAYBACK_SERVICE_CLASS))
            val future = MediaController.Builder(this, token).buildAsync()
            controllerFuture = future
            future.addListener({
                // Nothing to do with the controller itself: the engine is driven through AppGraph.engineController.
                runCatching { future.get() }
            }, ContextCompat.getMainExecutor(this))
        } catch (_: Exception) {
            // Service not available (e.g. before the playback package is integrated): UI keeps working with the NoOp controller.
            controllerFuture = null
        }
    }

    private companion object {
        const val PLAYBACK_SERVICE_CLASS = "dev.muisc.app.playback.PlaybackService"
    }
}
