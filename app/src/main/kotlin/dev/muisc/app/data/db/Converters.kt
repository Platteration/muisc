package dev.muisc.app.data.db

import androidx.room.TypeConverter
import dev.muisc.transitions.PlaybackContext

/**
 * Room type converters. Kept explicit (rather than relying on Room's implicit enum handling) so the stored form is
 * documented and stable: enums are stored by name and unknown names fall back to a safe default instead of crashing.
 * Registered on [MuiscDatabase]; only used by DAO methods that take or return these types.
 */
class Converters {
    @TypeConverter
    fun playbackContextToString(value: PlaybackContext?): String? = value?.name

    @TypeConverter
    fun stringToPlaybackContext(value: String?): PlaybackContext? =
        value?.let { name -> PlaybackContext.entries.firstOrNull { it.name == name } ?: PlaybackContext.QUEUE }

    /** Lists of strings are stored newline-separated (no entry may contain a newline; paths and ids never do). */
    @TypeConverter
    fun stringListToString(value: List<String>?): String? = value?.joinToString("\n")

    @TypeConverter
    fun stringToStringList(value: String?): List<String>? =
        value?.let { s -> if (s.isEmpty()) emptyList() else s.split('\n') }
}
