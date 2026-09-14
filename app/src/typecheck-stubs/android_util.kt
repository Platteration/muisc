@file:Suppress("UNUSED_PARAMETER", "unused")

package android.util

class Size(private val w: Int, private val h: Int) {
    fun getWidth(): Int = w
    fun getHeight(): Int = h
}

object Log {
    @JvmStatic fun v(tag: String, msg: String): Int = 0
    @JvmStatic fun d(tag: String, msg: String): Int = 0
    @JvmStatic fun i(tag: String, msg: String): Int = 0
    @JvmStatic fun w(tag: String, msg: String): Int = 0
    @JvmStatic fun w(tag: String, msg: String, t: Throwable?): Int = 0
    @JvmStatic fun e(tag: String, msg: String): Int = 0
    @JvmStatic fun e(tag: String, msg: String, t: Throwable?): Int = 0
}
