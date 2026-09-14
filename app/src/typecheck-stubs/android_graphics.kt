@file:Suppress("UNUSED_PARAMETER", "unused")

package android.graphics

import java.io.InputStream

class Bitmap {
    val width: Int = 0
    val height: Int = 0
    val isRecycled: Boolean = false
    fun recycle() {}
    fun getPixel(x: Int, y: Int): Int = 0

    enum class Config { ALPHA_8, RGB_565, ARGB_4444, ARGB_8888, RGBA_F16, HARDWARE }

    companion object {
        @JvmStatic fun createBitmap(width: Int, height: Int, config: Config): Bitmap = Bitmap()
        @JvmStatic fun createScaledBitmap(src: Bitmap, dstWidth: Int, dstHeight: Int, filter: Boolean): Bitmap = Bitmap()
    }
}

object BitmapFactory {
    class Options {
        @JvmField var inJustDecodeBounds: Boolean = false
        @JvmField var inSampleSize: Int = 1
        @JvmField var outWidth: Int = 0
        @JvmField var outHeight: Int = 0
        @JvmField var inPreferredConfig: Bitmap.Config? = null
    }

    @JvmStatic fun decodeByteArray(data: ByteArray, offset: Int, length: Int): Bitmap? = null
    @JvmStatic fun decodeByteArray(data: ByteArray, offset: Int, length: Int, opts: Options?): Bitmap? = null
    @JvmStatic fun decodeStream(stream: InputStream?): Bitmap? = null
    @JvmStatic fun decodeStream(stream: InputStream?, outPadding: Rect?, opts: Options?): Bitmap? = null
    @JvmStatic fun decodeFile(pathName: String): Bitmap? = null
    @JvmStatic fun decodeFileDescriptor(fd: java.io.FileDescriptor, outPadding: Rect?, opts: Options?): Bitmap? = null
}

class Rect(var left: Int = 0, var top: Int = 0, var right: Int = 0, var bottom: Int = 0)
