// RENDER_DIAGNOSTICS_FULL_TEXT

package dev.zacsweers.metro

import kotlin.reflect.KClass

typealias NestedPrimitiveArrays = Array<IntArray>

// Source stand-ins expose declaration defaults to the checker without generating a graph.
@Target(AnnotationTarget.CLASS)
annotation class DependencyGraph(
  val scope: KClass<*> = <!KNOWN_KOTLINC_BUG_WARNING!>NestedPrimitiveArrays::class<!>,
  val additionalScopes: Array<KClass<*>> = [<!KNOWN_KOTLINC_BUG_WARNING!>NestedPrimitiveArrays::class<!>, IntArray::class, String::class],
  val excludes: Array<KClass<*>> = [NestedPrimitiveArrays::class],
  val bindingContainers: Array<KClass<*>> = [NestedPrimitiveArrays::class],
)

// Only scope defaults carry scope keys; declaration lists stay quiet.
@Target(AnnotationTarget.CLASS)
annotation class ContributesTo(
  val scope: KClass<*> = <!KNOWN_KOTLINC_BUG_WARNING!>NestedPrimitiveArrays::class<!>,
  val replaces: Array<KClass<*>> = [NestedPrimitiveArrays::class],
)
