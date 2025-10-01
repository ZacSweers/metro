// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.graph

internal interface BaseBinding<
  Type : Any,
  TypeKey : BaseTypeKey<Type, *, *>,
  ContextualTypeKey : BaseContextualTypeKey<Type, TypeKey, *>,
> {
  val contextualTypeKey: ContextualTypeKey
  val typeKey: TypeKey
    get() = contextualTypeKey.typeKey

  val dependencies: List<ContextualTypeKey>

  /**
   * If true, indicates this binding is purely informational and should not be stored in the graph
   * itself.
   */
  val isTransient: Boolean
    get() = false

  val isImplicitlyDeferrable: Boolean
    get() = contextualTypeKey.isDeferrable

  fun renderLocationDiagnostic(short: Boolean = false): LocationDiagnostic
  fun renderDescriptionDiagnostic(short: Boolean = false, underlineTypeKey: Boolean = false): String
}

internal data class LocationDiagnostic(
  val location: String,
  val description: String?,
)
