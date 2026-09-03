// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.graph.reporting

import dev.zacsweers.metro.compiler.graph.explanation.BindingCandidateStatus
import dev.zacsweers.metro.compiler.graph.explanation.BindingDeclaration
import dev.zacsweers.metro.compiler.graph.explanation.BindingExplanationCandidate
import dev.zacsweers.metro.compiler.graph.explanation.BindingReason
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.irAttribute
import org.jetbrains.kotlin.name.ClassId

/** Records removals during merging. Its private membership set leaves merge behavior unchanged. */
internal class ContributionDecisionCapture(
  present: Set<ClassId>,
  private val graph: BindingDeclaration?,
  private val sources: Map<ClassId, ClassId>,
) {
  private val remaining = present.toMutableSet()
  private val decisions = mutableListOf<BindingExplanationCandidate>()
  private val replacements = mutableMapOf<ClassId, MutableSet<BindingDeclaration>>()

  /** Called while reading each surviving contribution's existing replacement annotations. */
  fun replaces(declaration: BindingDeclaration, target: ClassId) {
    replacements.getOrPut(target, ::linkedSetOf).add(declaration)
  }

  fun excluded(target: ClassId, origins: Collection<ClassId>, nested: Collection<ClassId>) {
    val related = listOfNotNull(graph)
    remove(target, BindingReason.EXCLUDED, related)
    for (origin in origins) remove(origin, BindingReason.EXCLUDED, related)
    for (child in nested) remove(child, BindingReason.EXCLUDED, related)
  }

  fun replaced(target: ClassId, origins: Collection<ClassId>) {
    val related = replacements[target].orEmpty().sortedBy { it.id }
    remove(target, BindingReason.REPLACED, related)
    for (origin in origins) remove(origin, BindingReason.REPLACED, related)
  }

  private fun remove(id: ClassId, reason: BindingReason, related: List<BindingDeclaration>) {
    if (!remaining.remove(id)) return
    val name = id.asSingleFqName().asString()
    val sourceName = (sources[id] ?: id).asSingleFqName().asString()
    decisions +=
      BindingExplanationCandidate(
        id = name,
        key = name,
        status = BindingCandidateStatus.REJECTED,
        reason = reason,
        declaration = BindingDeclaration(sourceName, sourceName),
        relatedDeclarations = related,
      )
  }

  fun snapshot(): List<BindingExplanationCandidate> = decisions.sortedBy { it.id }
}

/** Carries report-only merge decisions from synthetic graph creation into its graph node. */
internal var IrClass.contributionDecisions: List<BindingExplanationCandidate>? by
  irAttribute(copyByDefault = false)
