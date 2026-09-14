@file:Suppress("unused", "UNUSED_PARAMETER")

package coil.request

import android.content.Context
import android.graphics.drawable.Drawable
import coil.size.Scale

enum class CachePolicy { ENABLED, READ_ONLY, WRITE_ONLY, DISABLED }

class ImageRequest private constructor() {
    val context: Context get() = throw UnsupportedOperationException("typecheck stub")
    val data: Any? get() = null

    class Builder(context: Context) {
        fun data(data: Any?): Builder = this
        fun size(size: Int): Builder = this
        fun size(width: Int, height: Int): Builder = this
        fun scale(scale: Scale): Builder = this
        fun crossfade(enable: Boolean): Builder = this
        fun crossfade(durationMillis: Int): Builder = this
        fun allowHardware(enable: Boolean): Builder = this
        fun allowRgb565(enable: Boolean): Builder = this
        fun memoryCacheKey(key: String?): Builder = this
        fun diskCacheKey(key: String?): Builder = this
        fun memoryCachePolicy(policy: CachePolicy): Builder = this
        fun diskCachePolicy(policy: CachePolicy): Builder = this
        fun placeholder(drawableResId: Int): Builder = this
        fun placeholder(drawable: Drawable?): Builder = this
        fun error(drawableResId: Int): Builder = this
        fun error(drawable: Drawable?): Builder = this
        fun fallback(drawableResId: Int): Builder = this
        fun fallback(drawable: Drawable?): Builder = this
        fun build(): ImageRequest = ImageRequest()
    }
}

sealed class ImageResult {
    abstract val drawable: Drawable?
    abstract val request: ImageRequest
}

class SuccessResult(
    override val drawable: Drawable,
    override val request: ImageRequest,
) : ImageResult()

class ErrorResult(
    override val drawable: Drawable?,
    override val request: ImageRequest,
    val throwable: Throwable,
) : ImageResult()
