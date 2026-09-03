// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.explanation

import com.intellij.openapi.progress.ProgressManager
import dev.zacsweers.metro.compiler.graph.explanation.BindingCandidateStatus
import dev.zacsweers.metro.compiler.graph.explanation.BindingExplanation as ExplanationSnapshot
import dev.zacsweers.metro.compiler.graph.explanation.BindingExplanationCandidate
import dev.zacsweers.metro.compiler.graph.explanation.BindingExplanationContext
import dev.zacsweers.metro.compiler.graph.explanation.BindingExplanationOutcome
import dev.zacsweers.metro.compiler.graph.explanation.BindingExplanationPhase
import dev.zacsweers.metro.compiler.graph.explanation.BindingExplanationRenderer
import dev.zacsweers.metro.compiler.graph.explanation.BindingExplanationRequest
import dev.zacsweers.metro.compiler.graph.explanation.BindingReason
import dev.zacsweers.metro.idea.compilationContextName
import dev.zacsweers.metro.idea.model.BindingExplanation
import dev.zacsweers.metro.idea.model.BindingIndex
import dev.zacsweers.metro.idea.model.GraphPath
import dev.zacsweers.metro.idea.navigation.MetroBindingTarget
import dev.zacsweers.metro.idea.navigation.bindingTarget
import dev.zacsweers.metro.idea.navigation.metroEditorDeclarations
import dev.zacsweers.metro.idea.navigation.selectContexts
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedDeclaration

/** Captured display text and source choices for one dependency in one concrete graph context. */
internal class MetroBindingExplanation(
  val path: GraphPath,
  val text: String,
  val summary: String,
  val candidates: List<MetroBindingCandidate>,
  val copyText: String,
  val snapshot: ExplanationSnapshot,
) {
  override fun toString(): String = text
}

/** A candidate's decision stays readable while its source pointer remains navigable. */
internal class MetroBindingCandidate(
  val target: MetroBindingTarget,
  val selected: Boolean,
  val text: String,
  val details: String,
  val snapshot: BindingExplanationCandidate,
) {
  override fun toString(): String = text
}

/** Explains dependency sites at the caret using the same operation-local plans as navigation. */
internal fun metroBindingExplanations(
  index: BindingIndex,
  file: KtFile,
  offset: Int,
  pinnedPath: GraphPath?,
): List<MetroBindingExplanation> {
  return index.withResolutionSession { session ->
    for (declaration in metroEditorDeclarations(file, offset)) {
      val consumers = index.consumerEntriesAt(declaration)
      if (consumers.isNotEmpty()) {
        return@withResolutionSession buildList {
          for (consumer in consumers) {
            ProgressManager.checkCanceled()
            val resolution = session.resolveConsumer(consumer)
            val contexts = selectContexts(resolution.perContext.keys.toList(), pinnedPath)
            for (context in contexts) {
              ProgressManager.checkCanceled()
              val query = session.queryContext(context) ?: continue
              add(captureExplanation(index.explainBindings(session, consumer, query)))
            }
          }
        }
          .sortedBy { it.text }
      }
      val isBinding = index.bindingEntriesAt(declaration).isNotEmpty()
      if (isBinding || index.graphEntryAt(declaration) != null) break
    }
    emptyList()
  }
}

/** Captures all PSI-derived labels before the dialog or chooser reaches the EDT. */
private fun captureExplanation(explanation: BindingExplanation): MetroBindingExplanation {
  val consumer = explanation.consumer
  val declaration = consumer.pointer.element
  val requestName = (declaration as? KtNamedDeclaration)?.name ?: "dependency"
  val contextName = explanation.context.compilationContextName()
  val sources = BindingExplanationSources(consumer.pointer.project)
  val candidates =
    explanation.candidates
      .map { candidate ->
        ProgressManager.checkCanceled()
        val target = bindingTarget(candidate.binding)
        val source = sources.declaration(candidate.binding.pointer, target.text)
        val status =
          when {
            candidate.reasonCode == BindingReason.CONFLICT -> BindingCandidateStatus.CONFLICT
            candidate.selected -> BindingCandidateStatus.SELECTED
            else -> BindingCandidateStatus.REJECTED
          }
        val snapshot =
          BindingExplanationCandidate(
            id = sources.candidateId(candidate.binding, source),
            key = candidate.binding.typeKey.render(short = false),
            status = status,
            reason = candidate.reasonCode,
            declaration = source,
            ownerGraphId = candidate.binding.ownerGraphId?.let(sources::graphId),
            relatedDeclarations =
              candidate.relatedBindings.map { related ->
                sources.declaration(related.pointer, bindingTarget(related).text)
              },
          )
        MetroBindingCandidate(
          target,
          candidate.selected,
          BindingExplanationRenderer.candidateLabel(snapshot),
          BindingExplanationRenderer.candidateDetails(snapshot),
          snapshot,
        )
      }
      .sortedWith(compareByDescending<MetroBindingCandidate> { it.selected }.thenBy { it.text })
  val outcome =
    when {
      candidates.any { it.snapshot.status == BindingCandidateStatus.CONFLICT } ->
        BindingExplanationOutcome.CONFLICT
      candidates.any { it.selected } -> BindingExplanationOutcome.SELECTED
      candidates.any { it.snapshot.reason == BindingReason.ASSISTED_TARGET } ->
        BindingExplanationOutcome.INVALID_REQUEST
      else -> BindingExplanationOutcome.MISSING
    }
  val snapshot =
    ExplanationSnapshot(
      context = BindingExplanationContext(sources.contextId(explanation.context.path), contextName),
      phase = BindingExplanationPhase.LOOKUP,
      outcome = outcome,
      candidates = candidates.map { it.snapshot },
      request =
        BindingExplanationRequest(
          key = consumer.contextKey.render(short = false),
          declaration = sources.declaration(consumer.pointer, requestName),
          hasDefault = consumer.contextKey.hasDefault,
          isOptional = consumer.isOptional,
        ),
    )
  val summary = BindingExplanationRenderer.summary(snapshot)
  val copyText =
    BindingExplanationRenderer.render(snapshot) +
      "\n\nThis explanation is a snapshot. Run the action again after code changes."
  return MetroBindingExplanation(
    explanation.context.path,
    "$requestName: ${consumer.contextKey.render(short = true)} in $contextName",
    summary,
    candidates,
    copyText,
    snapshot,
  )
}
