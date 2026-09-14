@file:Suppress("unused")

package androidx.annotation

import kotlin.reflect.KClass

@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY, AnnotationTarget.CONSTRUCTOR, AnnotationTarget.FILE)
@Retention(AnnotationRetention.BINARY)
annotation class OptIn(vararg val markerClass: KClass<out Annotation>)
