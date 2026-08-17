// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.graph

import androidx.collection.ScatterMap

/**
 * Applies structural graph rules independently of IR or the Analysis API.
 *
 * Frontends provide small accessors over their native binding types and remain responsible for
 * source anchors, diagnostic rendering, and choosing when an issue is reported during graph
 * sealing. The accessor shape keeps the common no-issue path free of per-binding allocations.
 */
public class BindingGraphValidator<
  Type : Any,
  TypeKey : BaseTypeKey<Type, *, TypeKey>,
  ContextualTypeKey : BaseContextualTypeKey<Type, TypeKey, ContextualTypeKey>,
  Binding : BaseBinding<Type, TypeKey, ContextualTypeKey>,
  Scope : Any,
  MapKey : Any,
>(
  private val bindings: ScatterMap<TypeKey, Binding>,
  private val graphScopes: Set<Scope>,
  private val scopeOf: (Binding) -> Scope?,
  private val assistedKindOf: (Binding) -> AssistedBindingKind?,
  private val multibindingOf: (Binding) -> MultibindingValidationMetadata<TypeKey>?,
  private val mapContributionOf: (Binding) -> MapContributionValidationMetadata<MapKey>?,
  private val rootKeys: Set<TypeKey> = emptySet(),
  private val reverseAdjacency: Map<TypeKey, Set<TypeKey>> = emptyMap(),
) {
  /** Reports every structural issue originating at [binding] to [onIssue]. */
  public fun validate(
    binding: Binding,
    onIssue: (GraphValidationIssue<Binding, Scope, MapKey>) -> Unit,
  ) {
    val bindingScope = scopeOf(binding)
    if (bindingScope != null && bindingScope !in graphScopes) {
      onIssue(GraphValidationIssue.IncompatibleScope(binding, bindingScope))
    }

    if (assistedKindOf(binding) == AssistedBindingKind.TARGET) {
      for (requestingKey in reverseAdjacency[binding.typeKey].orEmpty()) {
        val requestingBinding = bindings[requestingKey] ?: continue
        if (assistedKindOf(requestingBinding) != AssistedBindingKind.FACTORY) {
          onIssue(GraphValidationIssue.InvalidAssistedInjection(binding, requestingBinding))
        }
      }
      if (binding.typeKey in rootKeys) {
        onIssue(GraphValidationIssue.InvalidAssistedInjection(binding, requestingBinding = null))
      }
    }

    val multibinding = multibindingOf(binding) ?: return
    if (!multibinding.allowEmpty && multibinding.sourceBindings.isEmpty()) {
      onIssue(GraphValidationIssue.EmptyMultibinding(binding))
    }
    if (multibinding.kind != MultibindingKind.MAP) {
      return
    }

    val contributionsByMapKey = linkedMapOf<MapKey?, MutableList<Binding>>()
    for (sourceKey in multibinding.sourceBindings) {
      val contribution = bindings[sourceKey] ?: continue
      val mapContribution = mapContributionOf(contribution) ?: continue
      contributionsByMapKey.getOrPut(mapContribution.mapKey, ::mutableListOf) += contribution
    }
    for ((mapKey, contributions) in contributionsByMapKey) {
      if (contributions.size > 1) {
        onIssue(GraphValidationIssue.DuplicateMapKey(binding, mapKey, contributions))
      }
    }
  }
}
