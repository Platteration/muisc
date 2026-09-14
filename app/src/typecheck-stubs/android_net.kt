@file:Suppress("UNUSED_PARAMETER", "unused")

package android.net

class Uri private constructor() : Comparable<Uri> {
    val path: String? = null
    val scheme: String? = null
    val lastPathSegment: String? = null
    fun buildUpon(): Builder = Builder()
    override fun compareTo(other: Uri): Int = 0
    override fun toString(): String = ""

    class Builder {
        fun appendPath(newSegment: String): Builder = this
        fun appendQueryParameter(key: String, value: String?): Builder = this
        fun build(): Uri = parse("")
    }

    companion object {
        @JvmStatic fun parse(s: String): Uri = Uri()
        @JvmStatic fun fromFile(file: java.io.File): Uri = Uri()
        @JvmField val EMPTY: Uri = Uri()
    }
}
