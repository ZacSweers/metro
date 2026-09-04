// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.graph.reporting

import dev.zacsweers.metro.compiler.graph.explanation.BindingCandidateStatus
import dev.zacsweers.metro.compiler.graph.explanation.BindingExplanation
import dev.zacsweers.metro.compiler.graph.explanation.BindingExplanationCandidate
import dev.zacsweers.metro.compiler.graph.explanation.BindingExplanationContext
import dev.zacsweers.metro.compiler.graph.explanation.BindingExplanationOutcome
import dev.zacsweers.metro.compiler.graph.explanation.BindingExplanationPhase
import dev.zacsweers.metro.compiler.graph.explanation.BindingExplanationRequest
import dev.zacsweers.metro.compiler.graph.explanation.BindingReason
import dev.zacsweers.metro.compiler.graph.explanation.bindingExplanationId
import dev.zacsweers.metro.compiler.ir.IrContextualTypeKey
import dev.zacsweers.metro.compiler.ir.IrTypeKey
import dev.zacsweers.metro.compiler.ir.graph.GraphNode
import dev.zacsweers.metro.compiler.ir.graph.IrBinding
import dev.zacsweers.metro.compiler.ir.graph.IrBindingStack
import java.util.IdentityHashMap
import org.jetbrains.kotlin.ir.declarations.IrDeclarationWithName

/** One candidate removed during graph construction, before requests reach binding lookup. */
internal data class RejectedBindingDecision(
  val key: IrTypeKey,
  val declaration: IrDeclarationWithName?,
  val reason: BindingReason,
  val ownerGraph: IrTypeKey? = null,
)

/**
 * Records decisions observed while building one graph. Callers create this only for JSON reports.
 * It retains IR references until reporting and never performs a binding lookup. Absent placeholders
 * allow default values and are omitted from selected candidates.
 */
internal class BindingDecisionCapture(private val node: GraphNode.Local) {
  private val lookups = linkedMapOf<LookupIdentity, LookupDecision>()
  /** Keeps the original selection when graph storage uses a generated parent delegate. */
  private val selectionsByBinding = IdentityHashMap<IrBinding, RecordedSelection>()
  /** Wrapped-map rejections apply only to requests with the same contextual type. */
  private val rejectionsByBinding =
    IdentityHashMap<IrBinding, MutableMap<IrContextualTypeKey, List<RejectedBindingDecision>>>()
  private val rejected = linkedSetOf<RejectedBindingDecision>()
  private val registered = mutableListOf<IrBinding>()
  private val optionalDeclarations =
    node.accessors
      .filter { it.isAnnotatedOptionalBinding }
      .mapTo(hashSetOf()) {
        IrBindingStack.Entry.requestedAt(it.contextKey, it.metroFunction.ir).declaration
      }

  /** A registration has no request site; full validation can populate these before lookup. */
  fun registered(binding: IrBinding) {
    registered += binding
  }

  /**
   * [resolvedBinding] associates the original selection with the binding installed in the graph.
   */
  fun selected(
    request: IrContextualTypeKey,
    stack: IrBindingStack,
    binding: IrBinding,
    conflicts: Collection<IrBinding>? = null,
    ownerGraph: IrTypeKey? = null,
    resolvedBinding: IrBinding = binding,
  ) {
    val decision = lookup(request, stack)
    val candidates = (conflicts ?: listOf(binding)).filterNot { it is IrBinding.Absent }
    decision.selected = candidates.map { SelectedBindingDecision(it, ownerGraph) }
    decision.conflicts = candidates.size > 1
    selectionsByBinding[resolvedBinding] = RecordedSelection(decision.selected, decision.conflicts)
    rememberRejections(resolvedBinding, request, decision)
  }

  /**
   * Class lookup may also create member-injection bindings needed by the selected class.
   * [sourceBindings] preserves its declaration when the returned binding delegates to a parent.
   */
  fun selected(
    request: IrContextualTypeKey,
    stack: IrBindingStack,
    bindings: Set<IrBinding>,
    sourceBindings: Set<IrBinding>? = null,
  ) {
    val decision = lookup(request, stack)
    val selected = mutableListOf<SelectedBindingDecision>()
    for (binding in bindings) {
      if (binding is IrBinding.Absent) continue
      if (binding.typeKey == request.typeKey) {
        val sourceBinding = sourceBindings?.firstOrNull { it.typeKey == binding.typeKey } ?: binding
        val ownerGraph = (binding as? IrBinding.GraphDependency)?.token?.ownerGraphKey
        selected += SelectedBindingDecision(sourceBinding, ownerGraph)
      } else {
        registered(binding)
      }
    }
    decision.selected = selected
    decision.conflicts = selected.size > 1
    val selection = RecordedSelection(decision.selected, decision.conflicts)
    for (binding in bindings) {
      if (binding.typeKey == request.typeKey && binding !is IrBinding.Absent) {
        selectionsByBinding[binding] = selection
        rememberRejections(binding, request, decision)
      }
    }
  }

  /** Records a cache hit without replacing an earlier decision or running another lookup. */
  fun reused(request: IrContextualTypeKey, binding: IrBinding, entry: IrBindingStack.Entry) {
    val identity = LookupIdentity(request, request.hasDefault, entry.declaration)
    if (identity in lookups) return
    val decision = LookupDecision()
    val selection = selectionsByBinding[binding]
    if (selection != null) {
      decision.selected = selection.bindings
      decision.conflicts = selection.conflicts
    } else if (binding !is IrBinding.Absent) {
      decision.selected = listOf(SelectedBindingDecision(binding, null))
    }
    decision.rejected += rejectionsByBinding[binding]?.get(request).orEmpty()
    lookups[identity] = decision
  }

  /** Keeps only observed rejections; contextual keys distinguish plain and wrapped map values. */
  private fun rememberRejections(
    binding: IrBinding,
    request: IrContextualTypeKey,
    decision: LookupDecision,
  ) {
    if (decision.rejected.isEmpty()) return
    rejectionsByBinding.getOrPut(binding) { mutableMapOf() }[request] = decision.rejected.toList()
  }

  fun missing(request: IrContextualTypeKey, stack: IrBindingStack) {
    val decision = lookup(request, stack)
    decision.selected = emptyList()
    decision.conflicts = false
  }

  fun rejected(
    request: IrContextualTypeKey,
    stack: IrBindingStack,
    binding: IrBinding,
    reason: BindingReason,
  ) {
    lookup(request, stack).rejected +=
      RejectedBindingDecision(binding.typeKey, binding.reportableDeclaration, reason)
  }

  fun rejected(
    key: IrTypeKey,
    declaration: IrDeclarationWithName?,
    reason: BindingReason,
    ownerGraph: IrTypeKey? = null,
  ) {
    rejected += RejectedBindingDecision(key, declaration, reason, ownerGraph)
  }

  /** Cached parent filtering is replayed into every child report that reuses those results. */
  fun include(decisions: List<RejectedBindingDecision>) {
    rejected += decisions
  }

  /** Formats only recorded work, preserving the lazy lookup coverage of the compiler. */
  fun snapshot(): List<BindingExplanation> {
    val chain = generateSequence<GraphNode>(node) { it.parentGraph }.toList().asReversed()
    val contextParts = chain.map { it.typeKey.render(short = false) }
    val contextId = bindingExplanationId(*contextParts.toTypedArray())
    val context = BindingExplanationContext(contextId, contextParts.joinToString(" -> "))
    // Ancestor ownership uses the same IDs as those graphs' own reports.
    val ownerGraphIds =
      chain
        .mapIndexed { index, graph ->
          graph.typeKey to bindingExplanationId(*contextParts.take(index + 1).toTypedArray())
        }
        .toMap()
    val result = mutableListOf<BindingExplanation>()
    for ((identity, decision) in lookups) {
      val selected =
        decision.selected.map { selection ->
          selection.binding.bindingExplanationCandidate(
            ownerGraphIds,
            selection.ownerGraph,
            decision.conflicts,
          )
        }
      val candidates = selected + decision.rejected.map { it.toExplanationCandidate(ownerGraphIds) }
      val outcome =
        when {
          decision.conflicts -> BindingExplanationOutcome.CONFLICT
          selected.isEmpty() -> BindingExplanationOutcome.MISSING
          else -> BindingExplanationOutcome.SELECTED
        }
      val isOptionalAccessor =
        identity.declaration != null && identity.declaration in optionalDeclarations
      result +=
        BindingExplanation(
          context = context,
          request =
            BindingExplanationRequest(
              key = identity.request.render(short = false, includeQualifier = true),
              declaration = identity.declaration?.bindingExplanationDeclaration(),
              hasDefault = identity.hasDefault,
              // hasDefault already includes the configured default-parameter policy.
              isOptional = identity.hasDefault || isOptionalAccessor,
            ),
          phase = BindingExplanationPhase.LOOKUP,
          outcome = outcome,
          candidates = candidates.sortedBy { it.id },
          details =
            listOf(
              "Records the candidates reached by this lookup; later fallbacks remain unevaluated."
            ),
        )
    }
    if (registered.isNotEmpty()) {
      result +=
        BindingExplanation(
          context = context,
          phase = BindingExplanationPhase.REGISTRATION,
          outcome = BindingExplanationOutcome.SELECTED,
          candidates =
            registered
              .map { it.bindingExplanationCandidate(ownerGraphIds) }
              .distinctBy { it.id }
              .sortedBy { it.id },
          details =
            listOf(
              "Records bindings added directly to this graph, including member-injection dependencies."
            ),
        )
    }
    val filtered =
      rejected.map { it.toExplanationCandidate(ownerGraphIds) } + node.contributionDecisions
    if (filtered.isNotEmpty()) {
      result +=
        BindingExplanation(
          context = context,
          phase = BindingExplanationPhase.CANDIDATE_FILTERING,
          outcome = BindingExplanationOutcome.FILTERED,
          candidates = filtered.distinct().sortedWith(compareBy({ it.id }, { it.reason.name })),
          details = listOf("Records candidates removed while constructing this graph."),
        )
    }
    return result.sortedWith(
      compareBy({ it.phase.name }, { it.request?.key }, { it.request?.declaration?.id })
    )
  }

  private fun lookup(request: IrContextualTypeKey, stack: IrBindingStack): LookupDecision {
    val declaration = stack.entries.lastOrNull()?.declaration
    val identity = LookupIdentity(request, request.hasDefault, declaration)
    return lookups.getOrPut(identity, ::LookupDecision)
  }

  private data class LookupIdentity(
    val request: IrContextualTypeKey,
    val hasDefault: Boolean,
    val declaration: IrDeclarationWithName?,
  )

  private class LookupDecision {
    var selected: List<SelectedBindingDecision> = emptyList()
    var conflicts: Boolean = false
    val rejected = linkedSetOf<RejectedBindingDecision>()
  }

  private class SelectedBindingDecision(val binding: IrBinding, val ownerGraph: IrTypeKey?)

  /** Immutable selection data can be shared by requests that reuse the same binding instance. */
  private class RecordedSelection(
    val bindings: List<SelectedBindingDecision>,
    val conflicts: Boolean,
  )
}

private fun RejectedBindingDecision.toExplanationCandidate(
  ownerGraphIds: Map<IrTypeKey, String>
): BindingExplanationCandidate {
  val declaration = declaration?.bindingExplanationDeclaration()
  val key = key.render(short = false, includeQualifier = true)
  return BindingExplanationCandidate(
    id = bindingExplanationId(key, declaration?.id ?: "generated"),
    key = key,
    status = BindingCandidateStatus.REJECTED,
    reason = reason,
    declaration = declaration,
    ownerGraphId = ownerGraph?.let { ownerGraphIds[it] ?: it.render(short = false) },
  )
}
