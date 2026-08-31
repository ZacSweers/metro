// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.graph

import androidx.collection.ScatterMap

/**
 * Applies structural graph rules independently of IR or the Analysis API.
 *
 * Frontends provide small accessors over their native binding types and remain responsible for
 * source anchors, diagnostic rendering, and choosing when an issue is reported during graph
 * sealing. Scalar accessors avoid allocating metadata for each binding or map contribution.
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
  private val multibindingKindOf: (Binding) -> MultibindingKind?,
  private val multibindingAllowsEmpty: (Binding) -> Boolean,
  private val multibindingSourceKeys: (Binding) -> Collection<TypeKey>,
  private val isMapContribution: (Binding) -> Boolean,
  private val mapKeyOf: (Binding) -> MapKey?,
  private val rootKeys: Set<TypeKey> = emptySet(),
  private val reverseAdjacency: Map<TypeKey, Set<TypeKey>> = emptyMap(),
) {
  /**
   * Reports every structural issue originating at [binding] to [onIssue].
   *
   * @param ensureActive cancellation callback polled while checking dependents and contributions.
   */
  public fun validate(
    binding: Binding,
    ensureActive: () -> Unit = {},
    onIssue: (GraphValidationIssue<Binding, Scope, MapKey>) -> Unit,
  ) {
    val bindingScope = scopeOf(binding)
    if (bindingScope != null && bindingScope !in graphScopes) {
      onIssue(GraphValidationIssue.IncompatibleScope(binding, bindingScope))
    }

    if (assistedKindOf(binding) == AssistedBindingKind.TARGET) {
      for (requestingKey in reverseAdjacency[binding.typeKey].orEmpty()) {
        ensureActive()
        val requestingBinding = bindings[requestingKey] ?: continue
        if (assistedKindOf(requestingBinding) != AssistedBindingKind.FACTORY) {
          onIssue(GraphValidationIssue.InvalidAssistedInjection(binding, requestingBinding))
        }
      }
      if (binding.typeKey in rootKeys) {
        onIssue(GraphValidationIssue.InvalidAssistedInjection(binding, requestingBinding = null))
      }
    }

    val multibindingKind = multibindingKindOf(binding) ?: return
    val sourceKeys = multibindingSourceKeys(binding)
    if (!multibindingAllowsEmpty(binding) && sourceKeys.isEmpty()) {
      onIssue(GraphValidationIssue.EmptyMultibinding(binding))
    }
    if (multibindingKind != MultibindingKind.MAP) {
      return
    }

    val contributionsByMapKey = mutableMapOf<MapKey?, MutableList<Binding>>()
    for (sourceKey in sourceKeys) {
      ensureActive()
      val contribution = bindings[sourceKey] ?: continue
      if (!isMapContribution(contribution)) continue
      contributionsByMapKey.getOrPut(mapKeyOf(contribution), ::mutableListOf) += contribution
    }
    for ((mapKey, contributions) in contributionsByMapKey) {
      ensureActive()
      if (contributions.size > 1) {
        onIssue(GraphValidationIssue.DuplicateMapKey(binding, mapKey, contributions))
      }
    }
  }
}
