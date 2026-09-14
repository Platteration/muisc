@file:Suppress("UNUSED_PARAMETER", "unused")

package androidx.datastore.core

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

interface DataStore<T> {
    val data: Flow<T>
    suspend fun updateData(transform: suspend (T) -> T): T
}

class FakeDataStore<T>(private val value: T) : DataStore<T> {
    override val data: Flow<T> get() = flowOf(value)
    override suspend fun updateData(transform: suspend (T) -> T): T = transform(value)
}
