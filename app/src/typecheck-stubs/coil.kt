@file:Suppress("unused", "UNUSED_PARAMETER")

package coil

import android.content.Context
import android.graphics.drawable.Drawable
import coil.request.ImageRequest
import coil.request.ImageResult

/** Coil 2.x `ImageLoader`. */
interface ImageLoader {
    suspend fun execute(request: ImageRequest): ImageResult
    fun enqueue(request: ImageRequest): Any
    fun shutdown()

    class Builder(context: Context) {
        fun crossfade(enable: Boolean): Builder = this
        fun crossfade(durationMillis: Int): Builder = this
        fun allowHardware(enable: Boolean): Builder = this
        fun respectCacheHeaders(enable: Boolean): Builder = this
        fun memoryCachePolicy(policy: coil.request.CachePolicy): Builder = this
        fun diskCachePolicy(policy: coil.request.CachePolicy): Builder = this
        fun build(): ImageLoader = throw UnsupportedOperationException("typecheck stub")
    }
}

/** Implemented by `Application` so Coil can pick up app-wide defaults. */
interface ImageLoaderFactory {
    fun newImageLoader(): ImageLoader
}

/** `coil.imageLoader` extension on Context (coil-base `Contexts.kt`). */
val Context.imageLoader: ImageLoader
    get() = throw UnsupportedOperationException("typecheck stub")
