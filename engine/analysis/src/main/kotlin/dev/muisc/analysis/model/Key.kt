package dev.muisc.analysis.model

import kotlinx.serialization.Serializable

enum class Mode { MAJOR, MINOR }

/** Pitch class 0..11 (C=0) plus mode. */
@Serializable
data class MusicalKey(val tonic: Int, val mode: Mode) {
    init { require(tonic in 0..11) }
    val name: String get() = NAMES[tonic] + if (mode == Mode.MAJOR) " major" else " minor"
    val shortName: String get() = NAMES[tonic] + if (mode == Mode.MAJOR) "" else "m"

    /** Camelot wheel code, e.g. "8A" (A minor) / "8B" (C major). */
    val camelot: Camelot get() = Camelot.of(this)

    companion object {
        val NAMES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
        fun parse(s: String): MusicalKey {
            val t = s.trim()
            val minor = t.endsWith("m") || t.lowercase().endsWith("minor")
            val root = t.removeSuffix("minor").removeSuffix("major").removeSuffix("m").trim().replace("♯", "#").replace("♭", "b")
            val idx = when (root.uppercase()) {
                "DB" -> 1; "EB" -> 3; "GB" -> 6; "AB" -> 8; "BB" -> 10
                else -> NAMES.indexOfFirst { it.equals(root, ignoreCase = true) }
            }
            require(idx >= 0) { "unknown key '$s'" }
            return MusicalKey(idx, if (minor) Mode.MINOR else Mode.MAJOR)
        }
    }
}

/** Camelot wheel position: number 1..12 and letter A (minor) / B (major). */
@Serializable
data class Camelot(val number: Int, val minor: Boolean) {
    val code: String get() = "$number${if (minor) "A" else "B"}"

    /**
     * Harmonic distance for mixing: 0 = same key, 1 = adjacent number or relative major/minor,
     * 2 = two steps, ... up to 6 (opposite side). Distance ≤ 1 is the classic "harmonic mixing" rule.
     */
    fun distanceTo(other: Camelot): Int {
        val d = Math.floorMod(number - other.number, 12).let { minOf(it, 12 - it) }
        return if (minor == other.minor) d else d + 1
    }

    companion object {
        // Camelot number for major keys by tonic pitch class: C=8B, G=9B, D=10B, ... (circle of fifths)
        private val MAJOR_NUMBER = intArrayOf(8, 3, 10, 5, 12, 7, 2, 9, 4, 11, 6, 1)
        fun of(key: MusicalKey): Camelot = when (key.mode) {
            Mode.MAJOR -> Camelot(MAJOR_NUMBER[key.tonic], false)
            Mode.MINOR -> Camelot(MAJOR_NUMBER[Math.floorMod(key.tonic + 3, 12)], true) // relative major is 3 semitones up
        }
        fun parse(code: String): Camelot {
            val c = code.trim().uppercase()
            val minor = c.endsWith("A")
            val n = c.dropLast(1).toInt()
            require(n in 1..12)
            return Camelot(n, minor)
        }
    }
}

@Serializable
data class KeyEstimate(
    val key: MusicalKey,
    /** Correlation strength 0..1 of the best profile. */
    val strength: Float,
    val secondBest: MusicalKey? = null,
    val secondStrength: Float = 0f,
    /** Normalised 12-bin chroma profile (C..B) the estimate was made from. */
    val chroma: FloatArray = FloatArray(12),
) {
    val camelot: Camelot get() = key.camelot
    override fun equals(other: Any?): Boolean = other is KeyEstimate && other.key == key && other.strength == strength && other.secondBest == secondBest
    override fun hashCode(): Int = key.hashCode() * 31 + strength.hashCode()
}
