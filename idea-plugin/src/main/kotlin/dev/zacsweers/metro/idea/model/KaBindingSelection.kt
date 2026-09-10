// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.model

import dev.zacsweers.metro.compiler.graph.selectBinding
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

  // The compiler rejects these requests during FIR injection-site checks, before graph lookup.
  if (indexedTier == BindingTier.ASSISTED_TARGET) {
    val target = candidates.first { it.selectionTier(unqualified) == BindingTier.ASSISTED_TARGET }
    return KaBindingSelection(BindingTier.ASSISTED_TARGET, listOf(target))
  }

  return selectBinding(
    registered = registered@{
        if (indexedTier == BindingTier.EXPLICIT) {
          val explicit = candidates.filter { it.selectionTier(unqualified) == BindingTier.EXPLICIT }
          val nearest = nearestExplicitBindings(explicit, plan)
          return@registered KaBindingSelection(BindingTier.EXPLICIT, nearest)
        }
        val generated = plan.generatedBindings.forKey(typeKey) ?: return@registered null
        KaBindingSelection(BindingTier.GENERATED_GRAPH, listOf(generated))
      },
    multibinding = multibinding@{
        if (collectionId == null) return@multibinding null
        val contributions =
          if (visibleCandidates == null) {
            multibindingContributions(collectionId, plan)
          } else {
            visibleCandidates.filter { it.multibindingId != null }
          }
        if (contributions.isEmpty() && indexedTier != BindingTier.MULTIBINDING) {
          return@multibinding null
        }
        val declarations = candidates.filterIsInstance<KaBinding.Multibinding>()
        KaBindingSelection(
          BindingTier.MULTIBINDING,
          contributions.ifEmpty { declarations },
          contributions,
          declarations,
        )
      },
    optional = optional@{
        if (indexedTier != BindingTier.OPTIONAL) return@optional null
        // The compiler uses the first optional declaration without reporting duplicates.
        val declaration = candidates.first { it.selectionTier(unqualified) == BindingTier.OPTIONAL }
        KaBindingSelection(BindingTier.OPTIONAL, listOf(declaration))
      },
    implicit = implicit@{
        if (indexedTier != BindingTier.IMPLICIT) return@implicit null
        val implicit = candidates.filter { it.selectionTier(unqualified) == BindingTier.IMPLICIT }
        KaBindingSelection(BindingTier.IMPLICIT, implicit)
      },
  )
}

/**
 * Keeps explicit declarations from the closest graph that owns this key. All bindings at that graph
 * survive so validation can report duplicates.
 */
private fun BindingIndex.nearestExplicitBindings(
  candidates: List<KaBinding>,
  plan: BindingIndex.GraphQueryPlan,
): List<KaBinding> {
  val chain = plan.structure.queryContext.graphContext.chain
  if (candidates.size < 2 || chain.size < 2) {
    return candidates
  }

  // Included dependency accessors retain the compiler's duplicate checks against inherited
  // bindings.
  val allDirectDeclarations = candidates.all {
    when (it) {
      is KaBinding.Provided,
      is KaBinding.Alias -> true
      is KaBinding.BoundInstance -> !it.isGraphInput && !it.isBindingContainerInput
      else -> false
    }
  }
  if (!allDirectDeclarations) {
    return candidates
  }

  for ((graphIndex, graph) in chain.withIndex()) {
    checkCanceledEvery(graphIndex)
    val owned = candidates.filter { isBindingOwnedByGraph(it, graph, plan) }
    if (owned.isNotEmpty()) {
      return owned
    }
  }
  return candidates
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
