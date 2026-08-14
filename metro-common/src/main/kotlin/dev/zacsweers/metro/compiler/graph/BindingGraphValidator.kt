// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.graph

import androidx.collection.ScatterMap

/**
 * Applies structural graph rules independently of IR or the Analysis API.
 *
 * Frontends provide declaration-derived metadata and remain responsible for source anchors,
 * diagnostic rendering, and choosing when an issue is reported during graph sealing.
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
  private val metadata: (Binding) -> BindingValidationMetadata<TypeKey, Scope, MapKey>,
) {
  private val metadataByKey =
    mutableMapOf<TypeKey, BindingValidationMetadata<TypeKey, Scope, MapKey>>()

  /** Returns all structural issues originating at [binding]. */
  public fun validate(binding: Binding): List<BindingGraphValidationIssue<Binding, Scope, MapKey>> =
    buildList {
      val bindingMetadata = metadataFor(binding)
      val bindingScope = bindingMetadata.scope
      if (bindingScope != null && bindingScope !in graphScopes) {
        add(BindingGraphValidationIssue.IncompatibleScope(binding, bindingScope))
      }

      val multibinding = bindingMetadata.multibinding ?: return@buildList
      if (!multibinding.allowEmpty && multibinding.sourceBindings.isEmpty()) {
        add(BindingGraphValidationIssue.EmptyMultibinding(binding))
      }
      if (multibinding.kind != MultibindingKind.MAP) return@buildList

      val contributionsByMapKey = linkedMapOf<MapKey?, MutableList<Binding>>()
      for (sourceKey in multibinding.sourceBindings) {
        val contribution = bindings[sourceKey] ?: continue
        val mapContribution = metadataFor(contribution).mapContribution ?: continue
        contributionsByMapKey.getOrPut(mapContribution.mapKey, ::mutableListOf) += contribution
      }
      for ((mapKey, contributions) in contributionsByMapKey) {
        if (contributions.size > 1) {
          add(BindingGraphValidationIssue.DuplicateMapKey(binding, mapKey, contributions))
        }
      }
    }

  private fun metadataFor(binding: Binding): BindingValidationMetadata<TypeKey, Scope, MapKey> {
    return metadataByKey.getOrPut(binding.typeKey) { metadata(binding) }
  }
}
