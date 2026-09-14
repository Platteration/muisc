@file:Suppress("UNUSED_PARAMETER", "unused")

package androidx.datastore.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.FakeDataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty

fun preferencesDataStore(name: String): ReadOnlyProperty<Context, DataStore<Preferences>> =
    object : ReadOnlyProperty<Context, DataStore<Preferences>> {
        override fun getValue(thisRef: Context, property: KProperty<*>): DataStore<Preferences> =
            FakeDataStore(emptyPreferences())
    }
