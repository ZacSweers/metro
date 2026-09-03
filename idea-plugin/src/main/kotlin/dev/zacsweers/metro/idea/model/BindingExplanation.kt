// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.model

import dev.zacsweers.metro.compiler.graph.explanation.BindingExplanationRenderer
import dev.zacsweers.metro.compiler.graph.explanation.BindingReason
import dev.zacsweers.metro.idea.checkCanceledEvery

/** The selection for one request and concrete graph path, captured without sealing the graph. */
internal class BindingExplanation(
  val context: GraphContext,
  val consumer: ConsumerEntry,
  val tier: BindingTier?,
  val candidates: List<BindingCandidateExplanation>,
) {
  val selected: List<KaBinding>
    get() = candidates.filter { it.selected }.map { it.binding }
}

/** Retains declaration pointers through the binding so every candidate remains navigable. */
internal class BindingCandidateExplanation(
  val binding: KaBinding,
  val selected: Boolean,
  val reasonCode: BindingReason,
  val rejection: BindingRejection?,
  val relatedBindings: List<KaBinding> = emptyList(),
) {
  val reason: String
    get() = BindingExplanationRenderer.reason(reasonCode)
}

/** Membership decisions shared by graph queries and the binding explanation. */
internal typealias BindingRejection = BindingReason

internal fun BindingTier.selectionReason(): BindingReason =
  when (this) {
    BindingTier.ASSISTED_TARGET -> BindingReason.ASSISTED_TARGET
    BindingTier.EXPLICIT -> BindingReason.SELECTED_EXPLICIT
    BindingTier.GENERATED_GRAPH -> BindingReason.SELECTED_GENERATED
    BindingTier.MULTIBINDING -> BindingReason.SELECTED_MULTIBINDING
    BindingTier.OPTIONAL -> BindingReason.SELECTED_OPTIONAL
    BindingTier.IMPLICIT -> BindingReason.SELECTED_IMPLICIT
  }

/** Formats captured selection decisions without reading declaration PSI. */
internal fun explainBindingSelection(
  context: GraphContext,
  consumer: ConsumerEntry,
  selection: KaBindingSelection?,
  candidates: List<KaBinding>,
  rejectionFor: (KaBinding) -> BindingRejection?,
): BindingExplanation {
  val selected = selection?.bindings.orEmpty().toSet()
  val allCandidates = (selected + candidates).toList()
  val tierCanConflict =
    selection?.tier == BindingTier.EXPLICIT || selection?.tier == BindingTier.IMPLICIT
  val conflicts = tierCanConflict && selected.size > 1
  val explanations = allCandidates.mapIndexed { index, binding ->
    checkCanceledEvery(index)
    val assistedTarget = binding is KaBinding.ConstructorInjected && binding.isAssisted
    val isSelected = binding in selected && !assistedTarget
    val rejection = rejectionFor(binding)
    val reason =
      when {
        binding.typeKey.qualifier != consumer.key.qualifier -> BindingReason.QUALIFIER_MISMATCH
        assistedTarget && binding in selected -> BindingReason.ASSISTED_TARGET
        isSelected && conflicts -> BindingReason.CONFLICT
        isSelected -> checkNotNull(selection).tier.selectionReason()
        rejection != null -> rejection
        selection?.tier == BindingTier.OPTIONAL && binding is KaBinding.CustomWrapper ->
          BindingReason.EARLIER_OPTIONAL
        else -> BindingReason.HIGHER_PRECEDENCE
      }
    val related =
      when (reason) {
        BindingReason.CONFLICT -> selected.filter { it !== binding }
        BindingReason.HIGHER_PRECEDENCE,
        BindingReason.EARLIER_OPTIONAL -> selected.toList()
        else -> emptyList()
      }
    BindingCandidateExplanation(binding, isSelected, reason, rejection, related)
  }
  return BindingExplanation(context, consumer, selection?.tier, explanations)
}
