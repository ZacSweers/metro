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
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.psi.PsiElement
import dev.zacsweers.metro.compiler.MetroOptions
import dev.zacsweers.metro.idea.MetroIdeProjectService
import dev.zacsweers.metro.idea.index.MetroResolutionService
import dev.zacsweers.metro.idea.model.BindingIndex
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
import org.jetbrains.kotlin.name.ClassId

/** A retained validation result plus whether the index changed since it was produced. */
internal class CachedValidation(val result: GraphValidationResult, val stale: Boolean)

/**
 * On-demand graph validation. Seals one graph at a time via [KaBindingGraph]. Results are retained
 * per graph and marked stale when the index they were sealed against is invalidated. Sealing never
 * happens eagerly.
 */
@Service(Service.Level.PROJECT)
internal class MetroGraphValidationService(
  private val project: Project,
  private val scope: CoroutineScope,
) {

  private class CachedEntry(val result: GraphValidationResult, val index: BindingIndex)

  /** ClassId alone is ambiguous: same-FQN graphs can exist in different modules' files. */
  private data class GraphCacheKey(val classId: ClassId, val file: VirtualFile?)

  private fun cacheKey(graph: KaGraphNode): GraphCacheKey? {
    val classId = graph.classId ?: return null
    return GraphCacheKey(classId, graph.pointer.virtualFile)
  }

  // An access-ordered LinkedHashMap with removeEldestEntry as an LRU. The bound keeps a long
  // browsing session from retaining every sealed graph forever. The synchronized wrapper is
  // required because async validation seals on pooled threads and access ordering mutates
  // internal links even on reads.
  private val results: MutableMap<GraphCacheKey, CachedEntry> =
    Collections.synchronizedMap(
      object : LinkedHashMap<GraphCacheKey, CachedEntry>(8, 0.75f, true) {
        override fun removeEldestEntry(
          eldest: MutableMap.MutableEntry<GraphCacheKey, CachedEntry>
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
   * The last result for [graph], or null if it was never validated. Results survive index
   * invalidation so the outcome stays visible; [CachedValidation.stale] flags that the code may
   * have changed since the run.
   */
  fun cachedResult(element: PsiElement, graph: KaGraphNode): CachedValidation? {
    val key = cacheKey(graph) ?: return null
    val entry = results[key] ?: return null
    val index = project.service<MetroResolutionService>().index(element)
    return CachedValidation(entry.result, stale = entry.index !== index)
  }

  /**
   * Validates [graph], reusing the cached result only when the index is unchanged. Must be called
   * under a read action.
   */
  fun validate(element: PsiElement, graph: KaGraphNode): GraphValidationResult {
    val index = project.service<MetroResolutionService>().index(element)
    val options = moduleOptions(element)
    val key = cacheKey(graph) ?: return KaBindingGraph(index, graph, options).seal()
    results[key]
      ?.takeIf { it.index === index }
      ?.let {
        return it.result
      }
    val result = KaBindingGraph(index, graph, options).seal()
    results[key] = CachedEntry(result, index)
    return result
  }

  /**
   * Validates [graph] and every extension it creates, transitively. Extensions seal before their
   * parents, mirroring the compiler's traversal, and the returned results keep that order with
   * [graph]'s own result last. Must be called under a read action.
   */
  fun validateWithExtensions(element: PsiElement, graph: KaGraphNode): List<GraphValidationResult> {
    val index = project.service<MetroResolutionService>().index(element)
    val results = mutableListOf<GraphValidationResult>()
    val visited = mutableSetOf<KaGraphNode>()

    fun visit(node: KaGraphNode) {
      if (!visited.add(node)) return
      for (extension in index.extensionsOf(node)) {
        visit(extension)
      }
      results += validate(element, node)
    }

    visit(graph)
    return results
  }

  /** Runs [validate] in a cancellable smart-mode read action and delivers the result on the EDT. */
  fun validateAsync(
    element: PsiElement,
    graph: KaGraphNode,
    onDone: Consumer<GraphValidationResult>,
  ) {
    launchCoalesced(graph) {
      val result =
        withBackgroundProgress(project, progressTitle(graph)) {
          smartReadAction(project) { validate(element, graph) }
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

  /** Launches [block] on the service scope, cancelling any in-flight run for the same graph. */
  private fun launchCoalesced(graph: KaGraphNode, block: suspend CoroutineScope.() -> Unit) {
    val key: Any = cacheKey(graph) ?: graph
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
