@file:Suppress("unused", "UNUSED_PARAMETER")

package androidx.media3.session

import android.app.PendingIntent
import android.app.Service
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

class SessionCommand(val customAction: String, val extras: Bundle)

class SessionCommands private constructor() {
    fun buildUpon(): Builder = Builder()
    class Builder {
        fun add(command: SessionCommand): Builder = this
        fun build(): SessionCommands = SessionCommands()
    }
    companion object { val EMPTY: SessionCommands = SessionCommands() }
}

class SessionResult {
    constructor(resultCode: Int)
    constructor(resultCode: Int, extras: Bundle)
    companion object {
        const val RESULT_SUCCESS = 0
        const val RESULT_ERROR_UNKNOWN = -1
        const val RESULT_ERROR_INVALID_STATE = -2
        const val RESULT_ERROR_BAD_VALUE = -3
        const val RESULT_ERROR_NOT_SUPPORTED = -6
    }
}

class LibraryResult<V> private constructor() {
    companion object {
        fun ofItem(item: MediaItem, params: MediaLibraryService.LibraryParams?): LibraryResult<MediaItem> = LibraryResult()
        fun ofItemList(items: List<MediaItem>, params: MediaLibraryService.LibraryParams?): LibraryResult<ImmutableList<MediaItem>> = LibraryResult()
        fun <V> ofError(errorCode: Int): LibraryResult<V> = LibraryResult()
    }
}

open class MediaSession {
    val player: Player get() = throw UnsupportedOperationException()
    fun release() {}

    class ControllerInfo

    class ConnectionResult private constructor() {
        val availableSessionCommands: SessionCommands = SessionCommands.EMPTY
        val availablePlayerCommands: Player.Commands = Player.Commands.Builder().build()
        companion object {
            fun accept(sessionCommands: SessionCommands, playerCommands: Player.Commands): ConnectionResult = ConnectionResult()
        }
    }

    interface Callback {
        fun onConnect(session: MediaSession, controller: ControllerInfo): ConnectionResult =
            ConnectionResult.accept(SessionCommands.EMPTY, Player.Commands.Builder().build())

        fun onCustomCommand(
            session: MediaSession,
            controller: ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> = Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))

        fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = Futures.immediateFuture(mediaItems)
    }
}

abstract class MediaSessionService : Service()

abstract class MediaLibraryService : MediaSessionService() {

    abstract fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession?

    class LibraryParams

    class MediaLibrarySession private constructor() : MediaSession() {

        class Builder(service: MediaLibraryService, player: Player, callback: Callback) {
            fun setId(id: String): Builder = this
            fun setSessionActivity(intent: PendingIntent): Builder = this
            fun build(): MediaLibrarySession = MediaLibrarySession()
        }

        interface Callback : MediaSession.Callback {
            fun onGetLibraryRoot(
                session: MediaLibrarySession,
                browser: ControllerInfo,
                params: LibraryParams?,
            ): ListenableFuture<LibraryResult<MediaItem>> = Futures.immediateFuture(LibraryResult.ofError(SessionResult.RESULT_ERROR_NOT_SUPPORTED))

            fun onGetItem(
                session: MediaLibrarySession,
                browser: ControllerInfo,
                mediaId: String,
            ): ListenableFuture<LibraryResult<MediaItem>> = Futures.immediateFuture(LibraryResult.ofError(SessionResult.RESULT_ERROR_NOT_SUPPORTED))

            fun onGetChildren(
                session: MediaLibrarySession,
                browser: ControllerInfo,
                parentId: String,
                page: Int,
                pageSize: Int,
                params: LibraryParams?,
            ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
                Futures.immediateFuture(LibraryResult.ofError(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
        }
    }
}

/**
 * `androidx.media3.session.SessionToken`.
 *
 * The real class has several constructors; the app only uses the `(Context, ComponentName)` one that
 * points at a [MediaSessionService] subclass.
 */
class SessionToken(context: android.content.Context, serviceComponent: android.content.ComponentName)

/**
 * `androidx.media3.session.MediaController`.
 *
 * Declared as an interface here purely so the stub does not have to spell out every [Player] member;
 * in media3 it is a final class implementing `Player`. The members the app touches -- the nested
 * [Builder] and the static [releaseFuture] -- keep their real shapes.
 */
interface MediaController : Player {

    class Builder(context: android.content.Context, token: SessionToken) {
        fun setApplicationLooper(looper: android.os.Looper): Builder = this
        fun buildAsync(): ListenableFuture<MediaController> = throw UnsupportedOperationException()
    }

    companion object {
        @JvmStatic
        fun releaseFuture(controllerFuture: java.util.concurrent.Future<out MediaController>) {}
    }
}
