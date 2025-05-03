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
   * If this binding is a multibinding, this will be a list of bindings that are aggregated into
   * this one. These aggregated bindings will _not_ be separately available on the graph.
   */
  val aggregatedBindings: Set<BaseBinding<Type, TypeKey, ContextualTypeKey>>
    get() = emptySet()

  /**
   * If true, indicates this binding is purely informational and should not be stored in the graph
   * itself.
   */
  val isTransient: Boolean
    get() = false

  fun renderLocationDiagnostic(): String
}
