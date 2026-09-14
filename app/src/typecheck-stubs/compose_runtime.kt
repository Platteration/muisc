@file:Suppress("unused", "UNUSED_PARAMETER")

package androidx.compose.runtime

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.reflect.KProperty

/**
 * NOTE: without the Compose compiler plugin `@Composable` is an ordinary annotation, so
 * `@Composable () -> Unit` and `() -> Unit` are the same type here. Composition, recomposition,
 * remember-keys and snapshot state are NOT modelled: these declarations exist so that names,
 * arities and app-level types can be checked.
 */
@Target(
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY_GETTER,
    AnnotationTarget.TYPE,
    AnnotationTarget.TYPE_PARAMETER,
    AnnotationTarget.EXPRESSION,
    AnnotationTarget.PROPERTY,
)
@Retention(AnnotationRetention.SOURCE)
annotation class Composable

@Target(AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY_GETTER)
annotation class ReadOnlyComposable

@Target(AnnotationTarget.CLASS)
annotation class Stable

@Target(AnnotationTarget.CLASS)
annotation class Immutable

@Target(AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY, AnnotationTarget.CLASS)
annotation class NonRestartableComposable

@Target(AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY, AnnotationTarget.CLASS)
annotation class DisallowComposableCalls

interface State<out T> {
    val value: T
}

interface MutableState<T> : State<T> {
    override var value: T
    operator fun component1(): T
    operator fun component2(): (T) -> Unit
}

interface FloatState : State<Float> {
    val floatValue: Float
}

interface MutableFloatState : FloatState, MutableState<Float> {
    override var floatValue: Float
}

interface IntState : State<Int> {
    val intValue: Int
}

interface MutableIntState : IntState, MutableState<Int> {
    override var intValue: Int
}

interface LongState : State<Long> {
    val longValue: Long
}

interface MutableLongState : LongState, MutableState<Long> {
    override var longValue: Long
}

private class MutableStateImpl<T>(override var value: T) : MutableState<T> {
    override fun component1(): T = value
    override fun component2(): (T) -> Unit = { value = it }
}

private class MutableFloatStateImpl(override var floatValue: Float) : MutableFloatState {
    override var value: Float
        get() = floatValue
        set(v) { floatValue = v }
    override fun component1(): Float = floatValue
    override fun component2(): (Float) -> Unit = { floatValue = it }
}

private class MutableIntStateImpl(override var intValue: Int) : MutableIntState {
    override var value: Int
        get() = intValue
        set(v) { intValue = v }
    override fun component1(): Int = intValue
    override fun component2(): (Int) -> Unit = { intValue = it }
}

private class MutableLongStateImpl(override var longValue: Long) : MutableLongState {
    override var value: Long
        get() = longValue
        set(v) { longValue = v }
    override fun component1(): Long = longValue
    override fun component2(): (Long) -> Unit = { longValue = it }
}

interface SnapshotMutationPolicy<T>

fun <T> structuralEqualityPolicy(): SnapshotMutationPolicy<T> = object : SnapshotMutationPolicy<T> {}
fun <T> referentialEqualityPolicy(): SnapshotMutationPolicy<T> = object : SnapshotMutationPolicy<T> {}
fun <T> neverEqualPolicy(): SnapshotMutationPolicy<T> = object : SnapshotMutationPolicy<T> {}

fun <T> mutableStateOf(value: T, policy: SnapshotMutationPolicy<T> = structuralEqualityPolicy()): MutableState<T> =
    MutableStateImpl(value)

fun mutableFloatStateOf(value: Float): MutableFloatState = MutableFloatStateImpl(value)
fun mutableIntStateOf(value: Int): MutableIntState = MutableIntStateImpl(value)
fun mutableLongStateOf(value: Long): MutableLongState = MutableLongStateImpl(value)

fun <T> mutableStateListOf(): SnapshotStateList<T> = SnapshotStateList()
fun <T> mutableStateListOf(vararg elements: T): SnapshotStateList<T> = SnapshotStateList<T>().also { it.addAll(elements) }
fun <K, V> mutableStateMapOf(): SnapshotStateMap<K, V> = SnapshotStateMap()

class SnapshotStateList<T> : MutableList<T> by mutableListOf()
class SnapshotStateMap<K, V> : MutableMap<K, V> by mutableMapOf()

operator fun <T> State<T>.getValue(thisObj: Any?, property: KProperty<*>): T = value
operator fun <T> MutableState<T>.setValue(thisObj: Any?, property: KProperty<*>, value: T) { this.value = value }

fun <T> derivedStateOf(calculation: () -> T): State<T> = object : State<T> {
    override val value: T get() = calculation()
}

@Composable fun <T> remember(calculation: () -> T): T = calculation()
@Composable fun <T> remember(key1: Any?, calculation: () -> T): T = calculation()
@Composable fun <T> remember(key1: Any?, key2: Any?, calculation: () -> T): T = calculation()
@Composable fun <T> remember(key1: Any?, key2: Any?, key3: Any?, calculation: () -> T): T = calculation()
@Composable fun <T> remember(vararg keys: Any?, calculation: () -> T): T = calculation()

@Composable fun <T> rememberUpdatedState(newValue: T): State<T> = object : State<T> {
    override val value: T get() = newValue
}

@Composable fun rememberCoroutineScope(getContext: () -> CoroutineContext = { EmptyCoroutineContext }): CoroutineScope =
    CoroutineScope(Dispatchers.Main)

@Composable fun LaunchedEffect(key1: Any?, block: suspend CoroutineScope.() -> Unit) {}
@Composable fun LaunchedEffect(key1: Any?, key2: Any?, block: suspend CoroutineScope.() -> Unit) {}
@Composable fun LaunchedEffect(key1: Any?, key2: Any?, key3: Any?, block: suspend CoroutineScope.() -> Unit) {}
@Composable fun LaunchedEffect(vararg keys: Any?, block: suspend CoroutineScope.() -> Unit) {}

class DisposableEffectResult {
    fun dispose() {}
}

class DisposableEffectScope {
    fun onDispose(onDisposeEffect: () -> Unit): DisposableEffectResult = DisposableEffectResult()
}

@Composable fun DisposableEffect(key1: Any?, effect: DisposableEffectScope.() -> DisposableEffectResult) {}
@Composable fun DisposableEffect(key1: Any?, key2: Any?, effect: DisposableEffectScope.() -> DisposableEffectResult) {}
@Composable fun DisposableEffect(vararg keys: Any?, effect: DisposableEffectScope.() -> DisposableEffectResult) {}

@Composable fun SideEffect(effect: () -> Unit) {}

@Composable fun <T> key(vararg keys: Any?, block: () -> T): T = block()

@Composable fun <T> produceState(initialValue: T, producer: suspend ProduceStateScope<T>.() -> Unit): State<T> =
    mutableStateOf(initialValue)

@Composable fun <T> produceState(initialValue: T, key1: Any?, producer: suspend ProduceStateScope<T>.() -> Unit): State<T> =
    mutableStateOf(initialValue)

interface ProduceStateScope<T> : MutableState<T>, CoroutineScope {
    suspend fun awaitDispose(onDispose: () -> Unit): Nothing
}

@Composable fun <T> Flow<T>.collectAsState(initial: T): State<T> = mutableStateOf(initial)
@Composable fun <T> StateFlow<T>.collectAsState(): State<T> = mutableStateOf(value)

fun <T> snapshotFlow(block: () -> T): Flow<T> = kotlinx.coroutines.flow.flow { emit(block()) }

abstract class CompositionLocal<T> internal constructor() {
    @get:Composable
    open val current: T get() = throw UnsupportedOperationException("typecheck stub")
}

class ProvidableCompositionLocal<T> internal constructor(private val default: () -> T) : CompositionLocal<T>() {
    override val current: T get() = default()
    infix fun provides(value: T): ProvidedValue<T> = ProvidedValue(this, value)
    infix fun providesDefault(value: T): ProvidedValue<T> = ProvidedValue(this, value)
}

class ProvidedValue<T> internal constructor(val compositionLocal: CompositionLocal<T>, val value: T)

fun <T> compositionLocalOf(
    policy: SnapshotMutationPolicy<T> = structuralEqualityPolicy(),
    defaultFactory: () -> T,
): ProvidableCompositionLocal<T> = ProvidableCompositionLocal(defaultFactory)

fun <T> staticCompositionLocalOf(defaultFactory: () -> T): ProvidableCompositionLocal<T> =
    ProvidableCompositionLocal(defaultFactory)

@Composable fun CompositionLocalProvider(vararg values: ProvidedValue<*>, content: @Composable () -> Unit) {
    content()
}
