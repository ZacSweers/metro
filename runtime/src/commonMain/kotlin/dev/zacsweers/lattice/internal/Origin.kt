package dev.zacsweers.lattice.internal

import kotlin.annotation.AnnotationTarget.CLASS
import kotlin.annotation.AnnotationTarget.PROPERTY
import kotlin.reflect.KClass

/** Marker for generated component interface to link their origin. */
@Target(CLASS, PROPERTY) public annotation class Origin(val value: KClass<*>)
