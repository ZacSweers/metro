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
  /** Extra sentences, like which generic provider derived a selected binding. */
  val details: List<String> = emptyList(),
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
    BindingTier.DERIVED -> BindingReason.SELECTED_DERIVED
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
  val lessSpecific = selection?.lessSpecificDerivedBindings.orEmpty().toSet()
  val ambiguousDerived = selection?.ambiguousDerivedBindings.orEmpty().toSet()
  val allCandidates = (selected + lessSpecific + ambiguousDerived + candidates).toList()
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
        // Matches the compiler, which reports every ambiguous derived match as a conflict.
        binding in ambiguousDerived -> BindingReason.CONFLICT
        isSelected -> checkNotNull(selection).tier.selectionReason()
        binding in lessSpecific -> BindingReason.LESS_SPECIFIC
        rejection != null -> rejection
        selection?.tier == BindingTier.OPTIONAL && binding is KaBinding.CustomWrapper ->
          BindingReason.EARLIER_OPTIONAL
        else -> BindingReason.HIGHER_PRECEDENCE
      }
    val related =
      when (reason) {
        BindingReason.CONFLICT -> {
          val conflicting = ambiguousDerived.ifEmpty { selected }
          conflicting.filter { it !== binding }
        }

        BindingReason.HIGHER_PRECEDENCE,
        BindingReason.LESS_SPECIFIC,
        BindingReason.EARLIER_OPTIONAL -> selected.toList()
        else -> emptyList()
      }

    val details = listOfNotNull(binding.derivation?.explanationDetail())
    BindingCandidateExplanation(binding, isSelected, reason, rejection, related, details)
  }
  return BindingExplanation(context, consumer, selection?.tier, explanations)
}
