// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.graph

/**
 * The outcome of resolving a `@ContributesBinding`-style class's *implicit* bound type from its
 * supertypes (i.e. when no explicit `binding<T>()`/`boundType` is given). Shared so the compiler's
 * aggregation checker and the IDE resolve it identically.
 */
public sealed interface BoundTypeResolution<out T : Any> {
  /** A single bound type was determined. */
  @JvmInline public value class Resolved<T : Any>(public val type: T) : BoundTypeResolution<T>

  /** Multiple supertypes declare a `@DefaultBinding`; the bound type is ambiguous. */
  @JvmInline
  public value class AmbiguousDefaultBinding<T : Any>(public val types: List<T>) :
    BoundTypeResolution<T>

  /** No `@DefaultBinding` and more than one supertype, so no implicit bound type can be picked. */
  @JvmInline
  public value class MultipleSupertypes<T : Any>(public val supertypes: List<T>) :
    BoundTypeResolution<T>

  /** The class has no (non-`Any`) supertype to bind to. */
  public data object NoSupertypes : BoundTypeResolution<Nothing>
}

/**
 * Resolves the implicit bound type of a contributed binding from [supertypesExcludingAny].
 *
 * Priority (mirrors `AggregationChecker`/`ContributionsFirGenerator`): a supertype's
 * `@DefaultBinding<T>` wins (ambiguous if more than one declares it), otherwise the sole supertype
 * is used. Callers handle the explicit `binding<T>()`/`boundType` case before calling this.
 *
 * @param T The "type" model of whatever system (FIR, IR, KA) is being used here.
 * @param defaultBindingOf the `@DefaultBinding<T>` type declared by a supertype, or null if it
 *   declares none.
 */
public fun <T : Any> resolveImplicitBoundType(
  supertypesExcludingAny: List<T>,
  defaultBindingOf: (T) -> T?,
): BoundTypeResolution<T> {
  if (supertypesExcludingAny.isEmpty()) return BoundTypeResolution.NoSupertypes
  val defaultBindings = supertypesExcludingAny.mapNotNull(defaultBindingOf)
  return when {
    defaultBindings.size > 1 -> BoundTypeResolution.AmbiguousDefaultBinding(defaultBindings)
    defaultBindings.size == 1 -> BoundTypeResolution.Resolved(defaultBindings[0])
    supertypesExcludingAny.size == 1 -> BoundTypeResolution.Resolved(supertypesExcludingAny[0])
    else -> BoundTypeResolution.MultipleSupertypes(supertypesExcludingAny)
  }
}
