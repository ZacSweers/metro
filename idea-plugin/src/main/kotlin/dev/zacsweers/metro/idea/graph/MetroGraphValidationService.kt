// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.graph

import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.util.concurrency.AppExecutorUtil
import dev.zacsweers.metro.compiler.MetroOptions
import dev.zacsweers.metro.idea.MetroIdeProjectService
import dev.zacsweers.metro.idea.index.MetroResolutionService
import dev.zacsweers.metro.idea.model.BindingIndex
import dev.zacsweers.metro.idea.model.KaGraphNode
import java.util.Collections
import java.util.function.Consumer
import org.jetbrains.kotlin.name.ClassId

/**
 * On-demand graph validation. Seals one graph at a time via [KaBindingGraph]. Results are cached
 * against the producing [BindingIndex] instance, so any PSI change drops them. Sealing never
 * happens eagerly.
 */
@Service(Service.Level.PROJECT)
internal class MetroGraphValidationService(private val project: Project) {

  private class Cache(val index: BindingIndex) {
    // An access-ordered LinkedHashMap with removeEldestEntry as an LRU. The bound
    // keeps a long browsing session from retaining every sealed graph until the next PSI change.
    // The synchronized wrapper is required because validateAsync seals on pooled threads and
    // access ordering mutates internal links even on reads.
    val results: MutableMap<ClassId, GraphValidationResult> =
      Collections.synchronizedMap(
        object : LinkedHashMap<ClassId, GraphValidationResult>(8, 0.75f, true) {
          override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<ClassId, GraphValidationResult>
          ): Boolean = size > 8
        }
      )
  }

  @Volatile private var cache: Cache? = null

  /** The cached result for [graph] against the current index, or null if not validated yet. */
  fun cachedResult(element: PsiElement, graph: KaGraphNode): GraphValidationResult? {
    val classId = graph.classId ?: return null
    val index = project.service<MetroResolutionService>().index(element)
    return cacheFor(index).results[classId]
  }

  /**
   * Validates [graph], reusing a cached result when the index is unchanged. Must be called under a
   * read action.
   */
  fun validate(element: PsiElement, graph: KaGraphNode): GraphValidationResult {
    val index = project.service<MetroResolutionService>().index(element)
    val options = moduleOptions(element)
    val classId = graph.classId ?: return KaBindingGraph(index, graph, options).seal()
    return cacheFor(index).results.getOrPut(classId) {
      KaBindingGraph(index, graph, options).seal()
    }
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

  /** Runs [validate] on a background thread and delivers the result on the EDT. */
  fun validateAsync(
    element: PsiElement,
    graph: KaGraphNode,
    onDone: Consumer<GraphValidationResult>,
  ) {
    ReadAction.nonBlocking<GraphValidationResult> { validate(element, graph) }
      .inSmartMode(project)
      .expireWhen { project.isDisposed }
      // Concurrent submissions for the same graph collapse into one computation
      .coalesceBy(this, graph.classId ?: graph)
      .finishOnUiThread(ModalityState.defaultModalityState(), onDone)
      .submit(AppExecutorUtil.getAppExecutorService())
  }

  private fun cacheFor(index: BindingIndex): Cache {
    cache
      ?.takeIf { it.index === index }
      ?.let {
        return it
      }
    return Cache(index).also { cache = it }
  }

  private fun moduleOptions(element: PsiElement): MetroOptions {
    val module = ModuleUtilCore.findModuleForPsiElement(element) ?: return MetroOptions()
    return project.service<MetroIdeProjectService>().state(module).options
  }
}
