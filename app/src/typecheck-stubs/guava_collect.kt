@file:Suppress("unused", "UNUSED_PARAMETER")

package com.google.common.collect

class ImmutableList<E> private constructor(private val backing: List<E>) : List<E> by backing {
    companion object {
        fun <E> copyOf(items: Collection<E>): ImmutableList<E> = ImmutableList(items.toList())
        fun <E> of(): ImmutableList<E> = ImmutableList(emptyList())
    }
}
