// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.graph

/** The collection kind represented by a multibinding node. */
public enum class MultibindingKind {
  SET,
  MAP,
}

/** Metadata needed to validate one multibinding without inspecting frontend-native types. */
public data class MultibindingValidationMetadata<TypeKey>(
  val kind: MultibindingKind,
  val allowEmpty: Boolean,
  val sourceBindings: Collection<TypeKey>,
)

/** Metadata for a binding that contributes an element to a map multibinding. */
public data class MapContributionValidationMetadata<MapKey : Any>(val mapKey: MapKey?)

/** How a binding participates in assisted injection validation. */
public enum class AssistedBindingKind {
  /** An assisted-injected type that can only be created through an assisted factory. */
  TARGET,
  /** An assisted factory, which is allowed to use its encapsulated target binding. */
  FACTORY,
}

/** Frontend-normalized facts used by [BindingGraphValidator]. */
public data class BindingValidationMetadata<TypeKey, Scope : Any, MapKey : Any>(
  val scope: Scope? = null,
  val multibinding: MultibindingValidationMetadata<TypeKey>? = null,
  val mapContribution: MapContributionValidationMetadata<MapKey>? = null,
  val assistedKind: AssistedBindingKind? = null,
)

/** A frontend-independent structural binding graph failure. */
public sealed interface GraphValidationIssue<
  out Binding,
  out Scope : Any,
  out MapKey : Any,
> {
  /** A scoped binding requested by a graph with no matching scope. */
  public data class IncompatibleScope<Binding, Scope : Any>(
    val binding: Binding,
    val bindingScope: Scope,
  ) : GraphValidationIssue<Binding, Scope, Nothing>

  /** A multibinding with no contributions when empty collections are forbidden. */
  public data class EmptyMultibinding<Binding>(val binding: Binding) :
    GraphValidationIssue<Binding, Nothing, Nothing>

  /** Multiple contributions to one map multibinding use the same map key. */
  public data class DuplicateMapKey<Binding, MapKey : Any>(
    val multibinding: Binding,
    val mapKey: MapKey?,
    val contributions: List<Binding>,
  ) : GraphValidationIssue<Binding, Nothing, MapKey>

  /** An assisted-injected target requested directly or by a non-factory binding. */
  public data class InvalidAssistedInjection<Binding>(
    val binding: Binding,
    val requestingBinding: Binding?,
  ) : GraphValidationIssue<Binding, Nothing, Nothing>
}
