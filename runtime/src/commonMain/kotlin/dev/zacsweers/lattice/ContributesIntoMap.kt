package dev.zacsweers.lattice

import kotlin.annotation.AnnotationTarget.CLASS
import kotlin.reflect.KClass

/**
 * TODO doc
 *
 * @param BoundType TODO doc. Emphasize map key and qualifiers
 */
@Target(CLASS)
@Repeatable
public annotation class ContributesIntoMap<BoundType>(
  val scope: KClass<*>,
  val replaces: Array<KClass<*>> = [],
)
