@file:Suppress("unused", "UNUSED_PARAMETER")

package coil.size

enum class Scale { FILL, FIT }

class Size private constructor() {
    companion object {
        @JvmField val ORIGINAL: Size = Size()
    }
}
