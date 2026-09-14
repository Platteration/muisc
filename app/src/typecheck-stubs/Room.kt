@file:Suppress("unused", "UNUSED_PARAMETER")

package androidx.room

import android.content.Context
import kotlin.reflect.KClass

@Target(AnnotationTarget.CLASS)
annotation class Entity(
    val tableName: String = "",
    val indices: Array<Index> = [],
    val inheritSuperIndices: Boolean = false,
    val primaryKeys: Array<String> = [],
    val foreignKeys: Array<ForeignKey> = [],
    val ignoredColumns: Array<String> = [],
)

@Target(AnnotationTarget.PROPERTY, AnnotationTarget.FIELD)
annotation class PrimaryKey(val autoGenerate: Boolean = false)

@Target(AnnotationTarget.PROPERTY, AnnotationTarget.FIELD)
annotation class ColumnInfo(
    val name: String = INHERIT_FIELD_NAME,
    val defaultValue: String = VALUE_UNSPECIFIED,
    val index: Boolean = false,
) {
    companion object {
        const val INHERIT_FIELD_NAME = "[field-name]"
        const val VALUE_UNSPECIFIED = "[value-unspecified]"
    }
}

@Target(AnnotationTarget.PROPERTY, AnnotationTarget.FIELD, AnnotationTarget.FUNCTION)
annotation class Ignore

@Target(AnnotationTarget.PROPERTY, AnnotationTarget.FIELD, AnnotationTarget.VALUE_PARAMETER)
annotation class Embedded(val prefix: String = "")

@Target(AnnotationTarget.PROPERTY, AnnotationTarget.FIELD)
annotation class Relation(
    val entity: KClass<*> = Any::class,
    val parentColumn: String,
    val entityColumn: String,
)

annotation class Index(vararg val value: String, val unique: Boolean = false, val name: String = "")

annotation class ForeignKey(
    val entity: KClass<*>,
    val parentColumns: Array<String>,
    val childColumns: Array<String>,
    val onDelete: Int = NO_ACTION,
    val onUpdate: Int = NO_ACTION,
    val deferred: Boolean = false,
) {
    companion object {
        const val NO_ACTION = 1
        const val RESTRICT = 2
        const val SET_NULL = 3
        const val SET_DEFAULT = 4
        const val CASCADE = 5
    }
}

@Target(AnnotationTarget.CLASS)
annotation class Dao

@Target(AnnotationTarget.FUNCTION)
annotation class Query(val value: String)

@Target(AnnotationTarget.FUNCTION)
annotation class RawQuery(val observedEntities: Array<KClass<*>> = [])

@Target(AnnotationTarget.FUNCTION)
annotation class Insert(val entity: KClass<*> = Any::class, val onConflict: Int = OnConflictStrategy.ABORT)

@Target(AnnotationTarget.FUNCTION)
annotation class Upsert(val entity: KClass<*> = Any::class)

@Target(AnnotationTarget.FUNCTION)
annotation class Update(val entity: KClass<*> = Any::class, val onConflict: Int = OnConflictStrategy.ABORT)

@Target(AnnotationTarget.FUNCTION)
annotation class Delete(val entity: KClass<*> = Any::class)

@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
annotation class Transaction

@Target(AnnotationTarget.FUNCTION)
annotation class MapInfo(val keyColumn: String = "", val valueColumn: String = "")

object OnConflictStrategy {
    const val REPLACE = 1
    const val ABORT = 3
    const val IGNORE = 5
}

@Target(AnnotationTarget.CLASS)
annotation class Database(
    val entities: Array<KClass<*>> = [],
    val views: Array<KClass<*>> = [],
    val version: Int,
    val exportSchema: Boolean = true,
    val autoMigrations: Array<AutoMigration> = [],
)

annotation class AutoMigration(val from: Int, val to: Int, val spec: KClass<*> = Any::class)

@Target(AnnotationTarget.FUNCTION)
annotation class TypeConverter

@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY, AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.FIELD)
annotation class TypeConverters(vararg val value: KClass<*>)

@Target(AnnotationTarget.CLASS)
annotation class DatabaseView(val value: String = "", val viewName: String = "")

abstract class RoomDatabase {
    open fun clearAllTables() {}
    open fun close() {}
    open val isOpen: Boolean get() = false
    open val openHelper: Any get() = Any()
    open val invalidationTracker: InvalidationTracker get() = InvalidationTracker()

    open suspend fun <R> withTransactionInternal(block: suspend () -> R): R = block()

    open fun runInTransaction(body: Runnable) {}
    open fun <V> runInTransaction(body: java.util.concurrent.Callable<V>): V = body.call()

    abstract class Callback

    enum class JournalMode { AUTOMATIC, TRUNCATE, WRITE_AHEAD_LOGGING }

    class Builder<T : RoomDatabase> internal constructor() {
        fun addCallback(callback: Callback): Builder<T> = this
        fun addTypeConverter(converter: Any): Builder<T> = this
        fun addMigrations(vararg migrations: Any): Builder<T> = this
        fun allowMainThreadQueries(): Builder<T> = this
        fun setJournalMode(mode: JournalMode): Builder<T> = this
        fun setQueryExecutor(executor: java.util.concurrent.Executor): Builder<T> = this
        fun setTransactionExecutor(executor: java.util.concurrent.Executor): Builder<T> = this
        fun enableMultiInstanceInvalidation(): Builder<T> = this
        fun fallbackToDestructiveMigration(): Builder<T> = this
        fun fallbackToDestructiveMigrationOnDowngrade(): Builder<T> = this
        fun fallbackToDestructiveMigrationFrom(vararg startVersions: Int): Builder<T> = this
        fun createFromAsset(assetPath: String): Builder<T> = this
        fun build(): T = throw UnsupportedOperationException("typecheck stub")
    }
}

class InvalidationTracker {
    fun addObserver(observer: Any) {}
    fun removeObserver(observer: Any) {}
}

object Room {
    @JvmStatic
    fun <T : RoomDatabase> databaseBuilder(context: Context, klass: Class<T>, name: String): RoomDatabase.Builder<T> =
        RoomDatabase.Builder()

    @JvmStatic
    fun <T : RoomDatabase> inMemoryDatabaseBuilder(context: Context, klass: Class<T>): RoomDatabase.Builder<T> =
        RoomDatabase.Builder()
}

/** `androidx.room:room-ktx` — runs [block] inside a database transaction on the Room transaction dispatcher. */
suspend fun <R> RoomDatabase.withTransaction(block: suspend () -> R): R = block()
