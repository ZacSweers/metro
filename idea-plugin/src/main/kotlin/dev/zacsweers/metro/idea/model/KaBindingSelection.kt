// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.model

import dev.zacsweers.metro.compiler.graph.BindingTier
import dev.zacsweers.metro.compiler.graph.selectBindingTier
import dev.zacsweers.metro.idea.checkCanceledEvery

/** Selected declarations, with collection inputs retained for graph validation. */
internal class KaBindingSelection(
  val tier: BindingTier,
  val bindings: List<KaBinding>,
  val multibindingContributions: List<KaBinding> = emptyList(),
  val multibindingDeclarations: List<KaBinding.Multibinding> = emptyList(),
)

/**
 * Selects the same binding tier for validation and editor queries. [visibleCandidates] lets editor
 * queries reuse their module-filtered candidates. Collection nodes and parent edges are built by
 * the validator after selection.
 */
internal fun BindingIndex.selectBindingsForKey(
  contextKey: KaContextualTypeKey,
  plan: BindingIndex.GraphQueryPlan,
  visibleCandidates: List<KaBinding>? = null,
): KaBindingSelection? {
  val typeKey = contextKey.typeKey
  val instance = plan.generatedBindings.instance(typeKey)
  if (instance != null) {
    return KaBindingSelection(BindingTier.GENERATED_GRAPH, listOf(instance))
  }

  val collectionId = contextKey.multibindingId()
  val unqualified = typeKey.qualifier == null
  var indexedTier: BindingTier? = null
  val candidates = visibleCandidates ?: bindingsForKey(typeKey, plan)
  for ((index, candidate) in candidates.withIndex()) {
    checkCanceledEvery(index)
    val candidateTier = candidate.selectionTier(unqualified)
    if (candidateTier == BindingTier.MULTIBINDING && collectionId == null) continue
    val previousTier = indexedTier
    if (previousTier == null || candidateTier < previousTier) indexedTier = candidateTier
  }

  var generated: KaBinding? = null
  var contributions: List<KaBinding> = emptyList()
  val tier =
    selectBindingTier { candidateTier ->
      when (candidateTier) {
        BindingTier.GENERATED_GRAPH -> {
          generated = plan.generatedBindings.forKey(typeKey)
          generated != null
        }
        BindingTier.MULTIBINDING -> {
          if (collectionId == null) {
            false
          } else {
            contributions =
              if (visibleCandidates == null) {
                multibindingContributions(collectionId, plan)
              } else {
                visibleCandidates.filter { it.multibindingId != null }
              }
            contributions.isNotEmpty() || indexedTier == BindingTier.MULTIBINDING
          }
        }
        else -> candidateTier == indexedTier
      }
    } ?: return null

  val declarations =
    if (tier == BindingTier.MULTIBINDING) {
      candidates.filterIsInstance<KaBinding.Multibinding>()
    } else {
      emptyList()
    }
  val selected =
    when (tier) {
      BindingTier.GENERATED_GRAPH -> listOf(checkNotNull(generated))
      BindingTier.MULTIBINDING -> contributions.ifEmpty { declarations }
      // The compiler uses the first optional declaration without reporting duplicates.
      BindingTier.OPTIONAL,
      BindingTier.ASSISTED_TARGET ->
        listOf(candidates.first { it.selectionTier(unqualified) == tier })
      BindingTier.EXPLICIT,
      BindingTier.IMPLICIT -> candidates.filter { it.selectionTier(unqualified) == tier }
    }
  return KaBindingSelection(tier, selected, contributions, declarations)
}

private fun KaBinding.selectionTier(unqualified: Boolean): BindingTier {
  if (multibindingId != null) return BindingTier.MULTIBINDING
  return when (this) {
    is KaBinding.Multibinding -> BindingTier.MULTIBINDING
    // An explicit provider cannot make direct injection of an unqualified assisted target valid.
    is KaBinding.ConstructorInjected ->
      if (unqualified && isAssisted) BindingTier.ASSISTED_TARGET else BindingTier.IMPLICIT
    is KaBinding.AssistedFactory -> BindingTier.IMPLICIT
    is KaBinding.CustomWrapper -> BindingTier.OPTIONAL
    else -> BindingTier.EXPLICIT
  }
}
