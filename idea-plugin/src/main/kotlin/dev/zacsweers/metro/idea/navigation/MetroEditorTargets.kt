// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.navigation

import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.SmartPsiElementPointer
import dev.zacsweers.metro.idea.compilationContextName
import dev.zacsweers.metro.idea.model.BindingIndex
import dev.zacsweers.metro.idea.model.BindingResolutionSession
import dev.zacsweers.metro.idea.model.GraphContext
import dev.zacsweers.metro.idea.model.GraphPath
import dev.zacsweers.metro.idea.model.KaBinding
import dev.zacsweers.metro.idea.model.KaTypeKey
import dev.zacsweers.metro.idea.model.matchingContext
import org.jetbrains.kotlin.psi.KtFile

/**
 * One binding's navigation pointer and exact key, captured during the editor query's read action.
 */
internal data class MetroBindingTarget(
  val pointer: SmartPsiElementPointer<*>,
  val key: KaTypeKey,
  val text: String,
) {
  override fun toString(): String = text
}

/** A concrete graph choice when the same dependency has different bindings across contexts. */
internal class MetroBindingNavigationChoice(
  val path: GraphPath?,
  val text: String,
  val bindings: List<MetroBindingTarget>,
) {
  override fun toString(): String = text
}

/** A browser row addressed by its graph path and, for binding rows, declaration and key. */
internal class MetroRevealTarget(
  val path: GraphPath,
  val text: String,
  val binding: MetroBindingTarget? = null,
) {
  override fun toString(): String = text
}

/** Durable action results; UI callbacks consume these without keeping an Analysis API session. */
internal class MetroEditorTargets(
  val navigation: List<MetroBindingNavigationChoice>,
  val reveal: List<MetroRevealTarget>,
) {
  companion object {
    val EMPTY = MetroEditorTargets(emptyList(), emptyList())
  }
}

/** Finds the closest indexed declaration at the caret using the caller's module-specific index. */
internal fun metroEditorTargets(
  index: BindingIndex,
  file: KtFile,
  offset: Int,
  pinnedPath: GraphPath?,
): MetroEditorTargets {
  return index.withResolutionSession { session ->
    for (current in metroEditorDeclarations(file, offset)) {
      val graph = index.graphEntryAt(current)
      if (graph != null) {
        val contexts = selectContexts(session.contextsFor(graph), pinnedPath)
        return@withResolutionSession MetroEditorTargets(
          emptyList(),
          contexts.map { MetroRevealTarget(it.path, it.compilationContextName()) },
        )
      }
      val consumers = index.consumerEntriesAt(current)
      if (consumers.isNotEmpty()) {
        val groups = linkedMapOf<GraphPath, BindingContextGroup>()
        val global = mutableListOf<KaBinding>()
        for (consumer in consumers) {
          ProgressManager.checkCanceled()
          val resolution = session.resolveConsumer(consumer)
          global += resolution.uniformBindings.orEmpty()
          for ((context, bindings) in resolution.perContext) {
            groups.getOrPut(context.path) { BindingContextGroup(context) }.bindings += bindings
          }
        }
        return@withResolutionSession actionTargets(
          index,
          groups.values.toList(),
          global,
          pinnedPath,
        )
      }
      val bindings = index.bindingEntriesAt(current)
      if (bindings.isNotEmpty()) {
        val groups = bindingContexts(session, bindings)
        return@withResolutionSession actionTargets(index, groups, bindings, pinnedPath)
      }
    }
    MetroEditorTargets.EMPTY
  }
}

/** Provider declarations can be revealed in every graph whose visible candidates contain them. */
private fun bindingContexts(
  session: BindingResolutionSession,
  bindings: List<KaBinding>,
): List<BindingContextGroup> {
  return buildList {
    for (context in session.allGraphContexts()) {
      ProgressManager.checkCanceled()
      val query = session.queryContext(context) ?: continue
      val visible = bindings.filter { binding ->
        session.bindingsForKey(binding.typeKey, query).any { candidate ->
          candidate.pointer == binding.pointer && candidate.typeKey == binding.typeKey
        }
      }
      if (visible.isNotEmpty()) add(BindingContextGroup(context, visible.toMutableList()))
    }
  }
}

private class BindingContextGroup(
  val context: GraphContext,
  val bindings: MutableList<KaBinding> = mutableListOf(),
)

/**
 * Coalesces identical navigation answers while retaining concrete graph rows for browser reveal.
 */
private fun actionTargets(
  index: BindingIndex,
  groups: List<BindingContextGroup>,
  global: List<KaBinding>,
  pinnedPath: GraphPath?,
): MetroEditorTargets {
  val selectedContexts =
    selectContexts(groups.map { it.context }, pinnedPath).map { it.path }.toSet()
  val selected = groups.filter { it.context.path in selectedContexts }
  val reveal = mutableListOf<MetroRevealTarget>()
  val choices = mutableListOf<MetroBindingNavigationChoice>()
  val identities = mutableListOf<Set<Any>>()
  for (group in selected) {
    ProgressManager.checkCanceled()
    val name = group.context.compilationContextName()
    val bindings = index.distinctBindingDeclarations(group.bindings).map(::bindingTarget)
    val choiceName = if (bindings.isEmpty()) "$name (no binding)" else name
    choices += MetroBindingNavigationChoice(group.context.path, choiceName, bindings)
    identities += index.bindingResolutionIdentities(group.bindings)
    if (bindings.isEmpty()) {
      reveal += MetroRevealTarget(group.context.path, name)
    } else {
      bindings.mapTo(reveal) { binding ->
        MetroRevealTarget(group.context.path, "${binding.text} in $name", binding)
      }
    }
  }
  if (choices.isEmpty()) {
    val bindings = index.distinctBindingDeclarations(global).map(::bindingTarget)
    if (bindings.isNotEmpty())
      choices += MetroBindingNavigationChoice(null, "Metro bindings", bindings)
  } else if (identities.distinct().size == 1) {
    val first = choices.first()
    choices.clear()
    choices += first
  }
  return MetroEditorTargets(choices, reveal.sortedBy { it.text })
}

/** Captures a pointer and a source/module label shared by navigation and explanations. */
internal fun bindingTarget(binding: KaBinding): MetroBindingTarget {
  val module = binding.pointer.element?.let(ModuleUtilCore::findModuleForPsiElement)
  val text = buildString {
    append(binding.typeKey.render(short = true))
    binding.implementationName?.let { append(" -> ").append(it) }
    binding.location()?.let { append(" (").append(it).append(')') }
    module?.name?.let { append(" [").append(it).append(']') }
  }
  return MetroBindingTarget(binding.pointer, binding.typeKey, text)
}

/** Applies editor pin matching, retaining context choices when the pin belongs to another graph. */
internal fun selectContexts(
  contexts: List<GraphContext>,
  pinnedPath: GraphPath?,
): List<GraphContext> {
  if (pinnedPath == null) return contexts
  val matching = contexts.matchingContext(pinnedPath) ?: return contexts
  return listOf(matching)
}
