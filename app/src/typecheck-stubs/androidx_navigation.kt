@file:Suppress("unused", "UNUSED_PARAMETER", "PackageDirectoryMismatch")

package androidx.navigation

import android.os.Bundle

open class NavDestination(val route: String? = null) {
    open val id: Int = 0
    open val parent: NavGraph? = null
}

open class NavGraph : NavDestination() {
    open var startDestinationId: Int = 0
    open var startDestinationRoute: String? = null

    open fun findNode(resId: Int): NavDestination? = null

    companion object {
        fun NavGraph.findStartDestination(): NavDestination = NavDestination()
    }
}

open class NavBackStackEntry internal constructor() {
    open val destination: NavDestination = NavDestination()
    open val arguments: Bundle? = null
    open val id: String = ""
}

abstract class NavType<T>(val isNullableAllowed: Boolean) {
    abstract fun put(bundle: Bundle, key: String, value: T)
    abstract fun get(bundle: Bundle, key: String): T?
    abstract fun parseValue(value: String): T

    companion object {
        @JvmField val IntType: NavType<Int> = object : NavType<Int>(false) {
            override fun put(bundle: Bundle, key: String, value: Int) {}
            override fun get(bundle: Bundle, key: String): Int = 0
            override fun parseValue(value: String): Int = 0
        }
        @JvmField val LongType: NavType<Long> = object : NavType<Long>(false) {
            override fun put(bundle: Bundle, key: String, value: Long) {}
            override fun get(bundle: Bundle, key: String): Long = 0L
            override fun parseValue(value: String): Long = 0L
        }
        @JvmField val FloatType: NavType<Float> = object : NavType<Float>(false) {
            override fun put(bundle: Bundle, key: String, value: Float) {}
            override fun get(bundle: Bundle, key: String): Float = 0f
            override fun parseValue(value: String): Float = 0f
        }
        @JvmField val BoolType: NavType<Boolean> = object : NavType<Boolean>(false) {
            override fun put(bundle: Bundle, key: String, value: Boolean) {}
            override fun get(bundle: Bundle, key: String): Boolean = false
            override fun parseValue(value: String): Boolean = false
        }
        @JvmField val StringType: NavType<String?> = object : NavType<String?>(true) {
            override fun put(bundle: Bundle, key: String, value: String?) {}
            override fun get(bundle: Bundle, key: String): String? = null
            override fun parseValue(value: String): String? = value
        }
    }
}

class NavArgumentBuilder {
    var type: NavType<*>? = null
    var nullable: Boolean = false
    var defaultValue: Any? = null
}

class NamedNavArgument internal constructor(val name: String)

fun navArgument(name: String, builder: NavArgumentBuilder.() -> Unit): NamedNavArgument {
    NavArgumentBuilder().builder()
    return NamedNavArgument(name)
}

class NavOptionsBuilder internal constructor() {
    var launchSingleTop: Boolean = false
    var restoreState: Boolean = false

    fun popUpTo(route: String, popUpToBuilder: PopUpToBuilder.() -> Unit = {}) {}
    fun popUpTo(id: Int, popUpToBuilder: PopUpToBuilder.() -> Unit = {}) {}
}

class PopUpToBuilder internal constructor() {
    var inclusive: Boolean = false
    var saveState: Boolean = false
}

open class NavController internal constructor() {
    open val graph: NavGraph = NavGraph()
    open val currentBackStackEntry: NavBackStackEntry? = null
    open val previousBackStackEntry: NavBackStackEntry? = null

    open fun navigate(route: String) {}
    open fun navigate(route: String, builder: NavOptionsBuilder.() -> Unit) {}
    open fun popBackStack(): Boolean = true
    open fun popBackStack(route: String, inclusive: Boolean, saveState: Boolean = false): Boolean = true
    open fun navigateUp(): Boolean = true
}

class NavHostController internal constructor() : NavController()

class NavGraphBuilder internal constructor()
