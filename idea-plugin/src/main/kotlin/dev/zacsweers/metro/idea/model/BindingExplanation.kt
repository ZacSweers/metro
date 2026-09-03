// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.model

import dev.zacsweers.metro.compiler.graph.BindingTier
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
  val reason: String,
  val rejection: BindingRejection?,
)

/** Membership decisions shared by graph queries and the binding explanation. */
internal enum class BindingRejection(val description: String) {
  NOT_VISIBLE("This declaration is outside the graph's module dependencies."),
  CONTRIBUTION_UNAVAILABLE("This graph does not include the contributed interface."),
  OVERRIDDEN("A nearer graph declaration overrides this binding."),
  PRIVATE_TO_GRAPH("This binding is private to another graph."),
  DYNAMIC_REPLACEMENT("A dynamic graph input replaces this binding."),
  OTHER_GRAPH("This binding belongs to another graph."),
  NEARER_INPUT("A nearer graph factory supplies this input."),
  EXCLUDED("This graph excludes the contribution."),
  INCOMPATIBLE_SCOPE("The binding's scope is unavailable in this graph."),
  CONTRIBUTION_SCOPE("The contribution targets a different scope."),
  CONTAINER_UNAVAILABLE("This graph does not include the binding's container or dependency."),
  REPLACED("Another contribution replaces this declaration."),
  LOWER_PRIORITY("A surviving contribution has a higher priority."),
}

internal fun BindingTier.selectionDescription(): String =
  when (this) {
    BindingTier.ASSISTED_TARGET -> "This assisted type requires an assisted factory."
    BindingTier.EXPLICIT -> "Selected explicit binding."
    BindingTier.GENERATED_GRAPH -> "Supplied by the graph or an extension factory."
    BindingTier.MULTIBINDING -> "Included in the collection binding."
    BindingTier.OPTIONAL -> "Selected optional binding declaration."
    BindingTier.IMPLICIT -> "Selected class binding after explicit and generated bindings."
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
        binding.typeKey.qualifier != consumer.key.qualifier ->
          "The binding has a different qualifier."
        assistedTarget && binding in selected -> "This assisted type requires an assisted factory."
        isSelected && conflicts -> "Conflicts with another binding at the same precedence."
        isSelected -> checkNotNull(selection).tier.selectionDescription()
        rejection != null -> rejection.description
        selection?.tier == BindingTier.OPTIONAL && binding is KaBinding.CustomWrapper ->
          "An earlier optional declaration supplies this binding."
        else -> "A binding with higher precedence supplies this request."
      }
    BindingCandidateExplanation(binding, isSelected, reason, rejection)
  }
  return BindingExplanation(context, consumer, selection?.tier, explanations)
}
