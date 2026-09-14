@file:Suppress("UNUSED_PARAMETER", "unused")

package androidx.datastore.preferences.core

import androidx.datastore.core.DataStore

open class Preferences {
    class Key<T>(val name: String)

    open operator fun <T> get(key: Key<T>): T? = null
    open fun <T> contains(key: Key<T>): Boolean = false
}

class MutablePreferences : Preferences() {
    override operator fun <T> get(key: Key<T>): T? = null
    operator fun <T> set(key: Key<T>, value: T) {}
    fun <T> remove(key: Key<T>) {}
    fun clear() {}
}

fun booleanPreferencesKey(name: String): Preferences.Key<Boolean> = Preferences.Key(name)
fun intPreferencesKey(name: String): Preferences.Key<Int> = Preferences.Key(name)
fun longPreferencesKey(name: String): Preferences.Key<Long> = Preferences.Key(name)
fun doublePreferencesKey(name: String): Preferences.Key<Double> = Preferences.Key(name)
fun stringPreferencesKey(name: String): Preferences.Key<String> = Preferences.Key(name)
fun stringSetPreferencesKey(name: String): Preferences.Key<Set<String>> = Preferences.Key(name)

fun emptyPreferences(): Preferences = Preferences()

suspend fun DataStore<Preferences>.edit(transform: suspend (MutablePreferences) -> Unit): Preferences {
    transform(MutablePreferences())
    return emptyPreferences()
}
