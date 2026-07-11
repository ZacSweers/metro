// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.graph

import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.Project
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.psi.PsiElement
import dev.zacsweers.metro.compiler.MetroOptions
import dev.zacsweers.metro.idea.MetroIdeProjectService
import dev.zacsweers.metro.idea.index.MetroResolutionService
import dev.zacsweers.metro.idea.model.BindingIndex
import dev.zacsweers.metro.idea.model.GraphContext
import dev.zacsweers.metro.idea.model.GraphPath
import dev.zacsweers.metro.idea.model.KaGraphNode
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Consumer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A retained validation result plus whether the index changed since it was produced. */
internal class CachedValidation(val result: GraphValidationResult, val stale: Boolean)

/**
 * On-demand graph validation. Seals one graph context at a time via [KaBindingGraph]. Results are
 * retained per concrete parent path and marked stale when the index they were sealed against is
 * invalidated. Sealing never happens eagerly.
 */
@Service(Service.Level.PROJECT)
internal class MetroGraphValidationService(
  private val project: Project,
  private val scope: CoroutineScope,
) {

  private class CachedEntry(val result: GraphValidationResult, val index: BindingIndex)

  private class ValidationInput(
    val graphElement: PsiElement,
    val index: BindingIndex,
    val context: GraphContext,
  )

  private fun cacheKey(context: GraphContext): GraphPath? {
    val hasLocalGraph = context.path.segments.any { it.classId == null }
    return context.path.takeUnless { hasLocalGraph }
  }

  // An access-ordered LinkedHashMap with removeEldestEntry as an LRU. The bound keeps a long
  // browsing session from retaining every sealed graph forever. The synchronized wrapper is
  // required because async validation seals on pooled threads and access ordering mutates
  // internal links even on reads.
  private val results: MutableMap<GraphPath, CachedEntry> =
    Collections.synchronizedMap(
      object : LinkedHashMap<GraphPath, CachedEntry>(8, 0.75f, true) {
        override fun removeEldestEntry(
          eldest: MutableMap.MutableEntry<GraphPath, CachedEntry>
        ): Boolean = size > 8
      }
    )

  /** In-flight validations by graph, so repeat requests coalesce into one computation. */
  private val inFlight = ConcurrentHashMap<Any, Job>()

  /** Drops all retained results. */
  fun clearResults() {
    results.clear()
  }

  /**
   * The last result for [context], or null if it was never validated. Results survive index
   * invalidation so the outcome stays visible. [CachedValidation.stale] flags that the code may
   * have changed since the run.
   */
  fun cachedResult(element: PsiElement, context: GraphContext): CachedValidation? {
    val key = cacheKey(context) ?: return null
    val entry = results[key] ?: return null
    val input = validationInput(element, context)
    return CachedValidation(entry.result, stale = entry.index !== input.index)
  }

  /**
   * Validates one concrete [context], reusing the cached result only when the index is unchanged.
   * Must be called under a read action.
   */
  fun validate(element: PsiElement, context: GraphContext): GraphValidationResult {
    return validate(validationInput(element, context))
  }

  private fun validate(input: ValidationInput): GraphValidationResult {
    val options = moduleOptions(input.graphElement)
    val index = input.index
    val context = input.context
    val graphName = context.graph.classId?.asFqNameString() ?: context.graph.name ?: "<unknown>"
    val queryContext =
      checkNotNull(index.queryContext(context)) { "Graph declaration disappeared: $graphName" }
    val key = cacheKey(context) ?: return KaBindingGraph(index, queryContext, options).seal()
    results[key]
      ?.takeIf { it.index === index }
      ?.let {
        return it.result
      }
    val result = KaBindingGraph(index, queryContext, options).seal()
    results[key] = CachedEntry(result, index)
    return result
  }

  /**
   * Validates [graph] and every extension it creates, transitively. Extensions seal before their
   * parents, mirroring the compiler's traversal, and the returned results keep that order with
   * [graph]'s own result last. Must be called under a read action.
   */
  fun validateWithExtensions(element: PsiElement, graph: KaGraphNode): List<GraphValidationResult> {
    val graphElement = graph.pointer.element ?: element
    val index = project.service<MetroResolutionService>().index(graphElement)
    val currentGraph = index.graphFor(graph) ?: graph
    return validateWithExtensions(graphElement, index.contextsFor(currentGraph))
  }

  /** Validates one concrete graph path and the extension paths it creates. */
  fun validateWithExtensions(
    element: PsiElement,
    context: GraphContext,
  ): List<GraphValidationResult> {
    return validateWithExtensions(element, listOf(context))
  }

  private fun validateWithExtensions(
    fallbackElement: PsiElement,
    roots: List<GraphContext>,
  ): List<GraphValidationResult> {
    val results = mutableListOf<GraphValidationResult>()
    val visited = mutableSetOf<GraphPath>()

    fun visit(context: GraphContext) {
      val input = validationInput(fallbackElement, context)
      if (!visited.add(input.context.path)) return
      for (extension in input.index.extensionContextsOf(input.context)) {
        visit(extension)
      }
      results += validate(input)
    }

    roots.forEach(::visit)
    return results
  }

  private fun validationInput(
    fallbackElement: PsiElement,
    context: GraphContext,
  ): ValidationInput {
    val graphElement = context.graph.pointer.element ?: fallbackElement
    val index = project.service<MetroResolutionService>().index(graphElement)
    val currentContext = index.findContext(context.path) ?: context
    return ValidationInput(graphElement, index, currentContext)
  }

  /** Runs [validate] for one context in a smart-mode read action and delivers it on the EDT. */
  fun validateAsync(
    element: PsiElement,
    context: GraphContext,
    onDone: Consumer<GraphValidationResult>,
  ) {
    launchCoalesced(context.path) {
      val result =
        withBackgroundProgress(project, progressTitle(context.graph)) {
          smartReadAction(project) { validate(element, context) }
        }
      withContext(Dispatchers.EDT) { onDone.accept(result) }
    }
  }

  /** Runs [validateWithExtensions] like [validateAsync]. */
  fun validateWithExtensionsAsync(
    element: PsiElement,
    graph: KaGraphNode,
    onDone: Consumer<List<GraphValidationResult>>,
  ) {
    launchCoalesced(graph) {
      val results =
        withBackgroundProgress(project, progressTitle(graph)) {
          smartReadAction(project) { validateWithExtensions(element, graph) }
        }
      withContext(Dispatchers.EDT) { onDone.accept(results) }
    }
  }

  private fun progressTitle(graph: KaGraphNode): String =
    "Validating Metro graph ${graph.name ?: ""}".trimEnd()

  /** Launches [block], cancelling any in-flight run for the same graph request. */
  private fun launchCoalesced(key: Any, block: suspend CoroutineScope.() -> Unit) {
    val job =
      scope.launch(start = CoroutineStart.LAZY) {
        try {
          block()
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          logger<MetroGraphValidationService>().warn("Metro graph validation failed", e)
        }
      }
    inFlight.put(key, job)?.cancel()
    job.invokeOnCompletion { inFlight.remove(key, job) }
    job.start()
  }

  private fun moduleOptions(element: PsiElement): MetroOptions {
    val module = ModuleUtilCore.findModuleForPsiElement(element) ?: return MetroOptions()
    return project.service<MetroIdeProjectService>().state(module).options
  }
}
