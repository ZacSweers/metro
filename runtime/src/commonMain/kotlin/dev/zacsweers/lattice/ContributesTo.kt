package dev.zacsweers.lattice

import kotlin.annotation.AnnotationTarget.CLASS
import kotlin.reflect.KClass

/** TODO doc */
@Target(CLASS)
public annotation class ContributesTo(val scope: KClass<*>, val replaces: Array<KClass<*>> = [])
