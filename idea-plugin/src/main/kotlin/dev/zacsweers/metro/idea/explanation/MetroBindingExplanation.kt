// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.explanation

import com.intellij.openapi.progress.ProgressManager
import dev.zacsweers.metro.idea.compilationContextName
import dev.zacsweers.metro.idea.model.BindingExplanation
import dev.zacsweers.metro.idea.model.BindingIndex
import dev.zacsweers.metro.idea.model.GraphPath
import dev.zacsweers.metro.idea.model.selectionDescription
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
) {
  override fun toString(): String = text
}

/** A candidate's decision stays readable while its source pointer remains navigable. */
internal class MetroBindingCandidate(
  val target: MetroBindingTarget,
  val selected: Boolean,
  val text: String,
  val details: String,
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
  val summary = buildString {
    appendLine("Request: ${consumer.contextKey.render(short = false)}")
    append("Requested by: ").append(requestName)
    consumer.pointer.virtualFile?.name?.let { append(" (").append(it).append(')') }
    appendLine()
    appendLine("Graph: $contextName")
    append(explanation.tier?.selectionDescription() ?: "No binding was selected.")
  }
  val candidates =
    explanation.candidates
      .map { candidate ->
        ProgressManager.checkCanceled()
        val target = bindingTarget(candidate.binding)
        val status = if (candidate.selected) "Selected" else "Alternative"
        val text = "$status: ${target.text}"
        val details = buildString {
          appendLine(text)
          appendLine(candidate.binding.typeKey.render(short = false))
          appendLine()
          append(candidate.reason)
        }
        MetroBindingCandidate(target, candidate.selected, text, details)
      }
      .sortedWith(compareByDescending<MetroBindingCandidate> { it.selected }.thenBy { it.text })
  val copyText = buildString {
    appendLine(summary)
    appendLine()
    appendLine("Candidates:")
    if (candidates.isEmpty()) appendLine("No candidates for this dependency.")
    for (candidate in candidates) {
      appendLine(candidate.details)
      appendLine()
    }
    append("This explanation is a snapshot. Run the action again after code changes.")
  }
  return MetroBindingExplanation(
    explanation.context.path,
    "$requestName: ${consumer.contextKey.render(short = true)} in $contextName",
    summary,
    candidates,
    copyText,
  )
}
