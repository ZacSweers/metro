// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.graph.explanation

/**
 * Plain text presentation of captured decisions. Rendering never consults compiler or IDE state.
 */
public object BindingExplanationRenderer {
  public fun render(explanation: BindingExplanation): String = buildString {
    appendLine(summary(explanation))
    appendLine()
    appendLine("Candidates:")
    if (explanation.candidates.isEmpty()) appendLine("No candidates for this dependency.")
    for ((index, candidate) in explanation.candidates.withIndex()) {
      if (index != 0) appendLine()
      appendLine(candidateDetails(candidate))
    }
    for (detail in explanation.details) {
      appendLine()
      appendLine(detail)
    }
  }
    .trimEnd()

  public fun summary(explanation: BindingExplanation): String = buildString {
    when (explanation.phase) {
      BindingExplanationPhase.LOOKUP -> Unit
      BindingExplanationPhase.REGISTRATION -> appendLine("Phase: Binding registration")
      BindingExplanationPhase.CANDIDATE_FILTERING -> appendLine("Phase: Candidate filtering")
    }
    val request = explanation.request
    if (request != null) {
      appendLine("Request: ${request.key}")
      request.declaration?.let { declaration ->
        append("Requested by: ").append(declaration.label)
        declaration.source?.let { append(" (").append(it.path).append(')') }
        appendLine()
      }
      if (request.isOptional) appendLine("This request allows an absent binding.")
      if (request.hasDefault) appendLine("This request has a default value.")
    }
    appendLine("Graph: ${explanation.context.label}")
    append(outcome(explanation))
  }

  public fun candidateLabel(candidate: BindingExplanationCandidate): String {
    val status =
      when (candidate.status) {
        BindingCandidateStatus.SELECTED,
        BindingCandidateStatus.CONFLICT -> "Selected"
        BindingCandidateStatus.REJECTED -> "Alternative"
      }
    return "$status: ${candidate.declaration?.label ?: candidate.key}"
  }

  public fun candidateDetails(candidate: BindingExplanationCandidate): String = buildString {
    appendLine(candidateLabel(candidate))
    appendLine(candidate.key)
    appendLine()
    append(reason(candidate.reason))
    for (declaration in candidate.relatedDeclarations) {
      appendLine()
      append("Related: ").append(declaration.label)
      declaration.source?.let { source ->
        append(" (").append(source.path)
        source.line?.let { append(':').append(it) }
        append(')')
      }
    }
    for (detail in candidate.details) {
      appendLine()
      append(detail)
    }
  }

  public fun reason(reason: BindingReason): String =
    when (reason) {
      BindingReason.SELECTED_EXPLICIT -> "Selected explicit binding."
      BindingReason.SELECTED_GENERATED -> "Supplied by the graph or an extension factory."
      BindingReason.SELECTED_MULTIBINDING -> "Included in the collection binding."
      BindingReason.SELECTED_OPTIONAL -> "Selected optional binding declaration."
      BindingReason.SELECTED_IMPLICIT ->
        "Selected class binding after explicit and generated bindings."
      BindingReason.SELECTED_PARENT -> "Supplied by the parent graph."
      BindingReason.ASSISTED_TARGET -> "This assisted type requires an assisted factory."
      BindingReason.CONFLICT -> "Conflicts with another binding at the same precedence."
      BindingReason.QUALIFIER_MISMATCH -> "The binding has a different qualifier."
      BindingReason.EARLIER_OPTIONAL -> "An earlier optional declaration supplies this binding."
      BindingReason.HIGHER_PRECEDENCE -> "A binding with higher precedence supplies this request."
      BindingReason.NOT_VISIBLE -> "This declaration is outside the graph's module dependencies."
      BindingReason.CONTRIBUTION_UNAVAILABLE ->
        "This graph does not include the contributed interface."
      BindingReason.OVERRIDDEN -> "A nearer graph declaration overrides this binding."
      BindingReason.PRIVATE_TO_GRAPH -> "This binding is private to another graph."
      BindingReason.DYNAMIC_REPLACEMENT -> "A dynamic graph input replaces this binding."
      BindingReason.OTHER_GRAPH -> "This binding belongs to another graph."
      BindingReason.NEARER_INPUT -> "A nearer graph factory supplies this input."
      BindingReason.EXCLUDED -> "This graph excludes the contribution."
      BindingReason.INCOMPATIBLE_SCOPE -> "The binding's scope is unavailable in this graph."
      BindingReason.CONTRIBUTION_SCOPE -> "The contribution targets a different scope."
      BindingReason.CONTAINER_UNAVAILABLE ->
        "This graph does not include the binding's container or dependency."
      BindingReason.REPLACED -> "Another contribution replaces this declaration."
      BindingReason.LOWER_PRIORITY -> "A surviving contribution has a higher priority."
      BindingReason.INCOMPATIBLE_MAP_VALUE ->
        "This map binding cannot supply the requested wrapped values."
    }

  private fun outcome(explanation: BindingExplanation): String =
    when (explanation.outcome) {
      BindingExplanationOutcome.SELECTED -> {
        val selected =
          explanation.candidates.firstOrNull { it.status == BindingCandidateStatus.SELECTED }
        if (selected == null) "A binding was selected." else reason(selected.reason)
      }
      BindingExplanationOutcome.MISSING -> "No binding was selected."
      BindingExplanationOutcome.CONFLICT -> reason(BindingReason.CONFLICT)
      BindingExplanationOutcome.INVALID_REQUEST -> "The dependency request is invalid."
      BindingExplanationOutcome.FILTERED ->
        "Candidate filtering completed before dependency lookup."
    }
}
