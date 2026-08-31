// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.graph

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.platform.util.progress.reportRawProgress
import com.intellij.psi.PsiElement
import dev.zacsweers.metro.compiler.MetroOptions
import dev.zacsweers.metro.idea.MetroIdeProjectService
import dev.zacsweers.metro.idea.index.MetroResolutionService
import dev.zacsweers.metro.idea.index.retryCancelledIndexBuild
import dev.zacsweers.metro.idea.model.BindingIndex
import dev.zacsweers.metro.idea.model.GraphContext
import dev.zacsweers.metro.idea.model.GraphPath
import dev.zacsweers.metro.idea.model.GraphQueryContext
import dev.zacsweers.metro.idea.model.KaGraphDeclaration
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Consumer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A retained validation result plus whether the index changed since it was produced. */
internal class CachedValidation(val result: KaGraphValidationResult, val stale: Boolean)

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

  private class CachedEntry(val result: KaGraphValidationResult, val index: BindingIndex)

  /** A current graph context interpreted using the compilation that owns it. */
  private class ValidationInput(
    val contextElement: PsiElement,
    val index: BindingIndex,
    val context: GraphContext,
  )

  private class ValidationTraversal(
    val requestPath: GraphPath,
    val inputs: List<ValidationInput>,
  )

  private fun cacheKey(context: GraphContext): GraphPath? {
    val hasLocalGraph = context.path.segments.any { it.classId == null }
    return context.path.takeUnless { hasLocalGraph }
  }

  /**
   * Contexts visited during active traversals stay available until those traversals finish. The
   * normal cache limit applies again afterward so one large graph does not raise it forever.
   */
  private val retainFloor = AtomicInteger(0)
  private val activeTraversals = AtomicInteger(0)

  // An access-ordered LinkedHashMap with removeEldestEntry as an LRU. The bound keeps a long
  // browsing session from retaining every sealed graph forever. The synchronized wrapper is
  // required because async validation seals on pooled threads and access ordering mutates
  // internal links even on reads.
  private val results: MutableMap<GraphPath, CachedEntry> =
    Collections.synchronizedMap(
      object : LinkedHashMap<GraphPath, CachedEntry>(8, 0.75f, true) {
        override fun removeEldestEntry(
          eldest: MutableMap.MutableEntry<GraphPath, CachedEntry>
        ): Boolean = size > maxOf(MAX_CACHED_RESULTS, retainFloor.get())
      }
    )

  /** In-flight validations by stable graph path, so repeat requests coalesce into one run. */
  private val inFlight = ConcurrentHashMap<GraphPath, Job>()
  private val validationProgress = ConcurrentHashMap<GraphPath, GraphValidationProgress>()
  private val validationProgressListeners =
    CopyOnWriteArrayList<(List<GraphValidationProgress>) -> Unit>()

  /** Drops all retained results. */
  fun clearResults() {
    results.clear()
    if (activeTraversals.get() == 0) {
      retainFloor.set(0)
    }
  }

  internal fun addValidationProgressListener(
    parentDisposable: Disposable,
    listener: (List<GraphValidationProgress>) -> Unit,
  ) {
    validationProgressListeners += listener
    Disposer.register(parentDisposable) { validationProgressListeners -= listener }
    notifyValidationProgressListener(listener)
  }

  internal fun isValidationRunning(path: GraphPath): Boolean {
    return validationProgress.values.any { it.covers(path) }
  }

  /**
   * The last result for [context], or null if it was never validated. Results survive index
   * invalidation so the outcome stays visible. [CachedValidation.stale] flags that the code may
   * have changed since the run.
   */
  fun cachedResult(element: PsiElement, context: GraphContext): CachedValidation? {
    val key = cacheKey(context) ?: return null
    val entry = results[key] ?: return null
    val contextElement = context.contextPointer.element ?: element
    val currentIndex = project.service<MetroResolutionService>().cachedIndex(contextElement)
    return CachedValidation(entry.result, stale = entry.index !== currentIndex)
  }

  /**
   * Validates one concrete [context], reusing the cached result only when the index is unchanged.
   * Must be called under a read action.
   */
  fun validate(element: PsiElement, context: GraphContext): KaGraphValidationResult {
    return validate(validationInput(element, context))
  }

  /**
   * Inspects the same module-aware lookup used by a graph seal without retaining or caching it.
   * Returns null if the requested graph path disappeared. Must be called under a read action.
   */
  fun <T> debugLookup(
    element: PsiElement,
    context: GraphContext,
    block: (BindingIndex, GraphQueryContext, MetroOptions, KaBindingLookup) -> T,
  ): T? {
    val input = validationInputOrNull(element, context) ?: return null
    val queryContext = input.index.queryContext(input.context) ?: return null
    val options = moduleOptions(input.contextElement)
    val lookup =
      KaBindingLookup(input.index, queryContext, options) { parentContext ->
        parentGraphLookup(input.contextElement, parentContext)
      }
    return try {
      block(input.index, queryContext, options, lookup)
    } finally {
      lookup.clear()
    }
  }

  private fun validate(input: ValidationInput): KaGraphValidationResult {
    val index = input.index
    val context = input.context
    val key = cacheKey(context)
    if (key != null) {
      results[key]
        ?.takeIf { it.index === index }
        ?.let {
          return it.result
        }
    }

    // Extension children seal first, mirroring the compiler's traversal, so any keys they
    // delegate upward are validated in this seal through the reservations below. Cached child
    // results still carry their reservations, so cache hits stay correct. Each child resolves
    // through its own declaration module so per-module options and library views apply.
    val reservations = mutableListOf<ReservedParentKey>()
    var childFailed = false
    var incompleteChild: KaGraphValidationResult.Incomplete? = null
    for (extensionContext in index.extensionContextsOf(context)) {
      val childInput = validationInputOrNull(input.contextElement, extensionContext) ?: continue
      val childResult = validate(childInput)
      when (childResult) {
        is KaGraphValidationResult.Completed -> {
          for ((reservedKey, binding) in childResult.parentReservations) {
            reservations +=
              ReservedParentKey(reservedKey, childResult.context.graph.pointer, binding)
          }
        }
        is KaGraphValidationResult.Incomplete -> {
          if (incompleteChild == null) incompleteChild = childResult
        }
        is KaGraphValidationResult.InternalError -> childFailed = true
      }
    }

    val graphName = context.graph.classId?.asFqNameString() ?: context.graph.name ?: "<unknown>"
    val incompleteExtension = incompleteChild
    val result =
      if (incompleteExtension != null) {
        val childName =
          incompleteExtension.graph.classId?.asFqNameString()
            ?: incompleteExtension.graph.name
            ?: "<unknown>"
        KaGraphValidationResult.Incomplete(
          context,
          "Extension graph $childName is incomplete: ${incompleteExtension.reason}",
        )
      } else {
        runGraphValidation(context, graphName) {
          val options = moduleOptions(input.contextElement)
          val queryContext =
            checkNotNull(index.queryContext(context)) {
              "Graph declaration disappeared: $graphName"
            }
          KaBindingGraph(index, queryContext, options, reservations) { parentContext ->
              parentGraphLookup(input.contextElement, parentContext)
            }
            .seal()
        }
      }
    // Expected analysis limits are stable for this immutable index and should not rerun on every
    // gutter refresh. Internal errors stay uncached so transient plugin failures can retry. A
    // parent sealed without a crashed child's reservations must also retry once the child does.
    if (key != null && result !is KaGraphValidationResult.InternalError && !childFailed) {
      results[key] = CachedEntry(result, index)
    }
    return result
  }

  /**
   * Validates [graph] and every extension it creates, transitively. Extensions seal before their
   * parents, mirroring the compiler's traversal, and the returned results keep that order with
   * [graph]'s own result last. Must be called under a read action.
   */
  fun validateWithExtensions(
    element: PsiElement,
    graph: KaGraphDeclaration,
    onProgress: (GraphValidationProgress) -> Unit = {},
  ): List<KaGraphValidationResult> {
    val declarationElement = graph.pointer.element ?: element
    val index = project.service<MetroResolutionService>().currentIndex(declarationElement)
    val currentGraph =
      index.graphFor(graph)
        ?: throw CancellationException("Metro graph declaration is no longer current")
    val requestPath = GraphPath(listOf(currentGraph.declarationId))
    val traversal =
      validationTraversal(declarationElement, requestPath, index.contextsFor(currentGraph))
    return validateTraversal(traversal, onProgress)
  }

  /** Validates one concrete graph path and the extension paths it creates. */
  fun validateWithExtensions(
    element: PsiElement,
    context: GraphContext,
    onProgress: (GraphValidationProgress) -> Unit = {},
  ): List<KaGraphValidationResult> {
    val traversal = validationTraversal(element, context.path, listOf(context))
    return validateTraversal(traversal, onProgress)
  }

  private fun validationTraversal(
    declarationFallback: PsiElement,
    requestPath: GraphPath,
    rootContexts: List<GraphContext>,
  ): ValidationTraversal {
    val inputs = mutableListOf<ValidationInput>()
    val visited = mutableSetOf<GraphPath>()

    fun visit(context: GraphContext) {
      val input = validationInput(declarationFallback, context)
      if (!visited.add(input.context.path)) return
      for (extension in input.index.extensionContextsOf(input.context)) {
        visit(extension)
      }
      inputs += input
    }

    rootContexts.forEach(::visit)
    return ValidationTraversal(requestPath, inputs)
  }

  private fun validateTraversal(
    traversal: ValidationTraversal,
    onProgress: (GraphValidationProgress) -> Unit,
  ): List<KaGraphValidationResult> {
    activeTraversals.incrementAndGet()
    try {
      val traversalResults = ArrayList<KaGraphValidationResult>(traversal.inputs.size)
      for ((index, input) in traversal.inputs.withIndex()) {
        val graphName =
          input.context.graph.name ?: input.context.graph.classId?.asFqNameString() ?: "<unknown>"
        onProgress(
          GraphValidationProgress(
            requestPath = traversal.requestPath,
            graphName = graphName,
            completed = index,
            total = traversal.inputs.size,
          )
        )
        traversalResults += validate(input)
        retainFloor.updateAndGet { floor -> maxOf(floor, traversalResults.size + 1) }
      }
      return traversalResults
    } finally {
      if (activeTraversals.decrementAndGet() == 0) {
        retainFloor.set(0)
        synchronized(results) {
          val entries = results.entries.iterator()
          while (results.size > MAX_CACHED_RESULTS && entries.hasNext()) {
            entries.next()
            entries.remove()
          }
        }
      }
    }
  }

  /** Parent binding analysis follows the parent's own module, index, and compiler options. */
  private fun parentGraphLookup(
    declarationFallback: PsiElement,
    context: GraphContext,
  ): ParentGraphLookup? {
    val input = validationInputOrNull(declarationFallback, context) ?: return null
    val queryContext = input.index.queryContext(input.context) ?: return null
    return ParentGraphLookup(input.index, queryContext, moduleOptions(input.contextElement))
  }

  private fun validationInputOrNull(
    declarationFallback: PsiElement,
    context: GraphContext,
  ): ValidationInput? {
    val contextElement = context.contextPointer.element ?: declarationFallback
    val index = project.service<MetroResolutionService>().currentIndex(contextElement)
    val currentContext = index.findContext(context.path) ?: return null
    val currentContextElement = currentContext.contextPointer.element ?: return null
    return ValidationInput(currentContextElement, index, currentContext)
  }

  private fun validationInput(
    declarationFallback: PsiElement,
    context: GraphContext,
  ): ValidationInput {
    val contextElement = context.contextPointer.element ?: declarationFallback
    val index = project.service<MetroResolutionService>().currentIndex(contextElement)
    val currentContext =
      index.findContext(context.path)
        ?: throw CancellationException("Metro graph context is no longer current")
    val currentContextElement =
      currentContext.contextPointer.element
        ?: throw CancellationException("Metro graph context is no longer available")
    return ValidationInput(currentContextElement, index, currentContext)
  }

  /** Runs [validate] for one context in a smart-mode read action and delivers it on the EDT. */
  fun validateAsync(
    element: PsiElement,
    context: GraphContext,
    onDone: Consumer<KaGraphValidationResult>,
  ) {
    launchCoalesced(context.path, context.graph) { publish ->
      val result =
        withBackgroundProgress(project, progressTitle(context.graph)) {
          reportRawProgress { reporter ->
            retryCancelledIndexBuild {
              smartReadAction(project) {
                publish(
                  GraphValidationProgress(
                    requestPath = context.path,
                    graphName = graphDisplayName(context.graph),
                    completed = 0,
                    total = 1,
                  )
                )
                reporter.details("Validating ${graphDisplayName(context.graph)}")
                reporter.fraction(0.0)
                validate(element, context)
              }
            }
          }
        }
      withContext(Dispatchers.EDT) { onDone.accept(result) }
    }
  }

  /** Runs [validateWithExtensions] like [validateAsync]. */
  fun validateWithExtensionsAsync(
    element: PsiElement,
    graph: KaGraphDeclaration,
    onDone: Consumer<List<KaGraphValidationResult>>,
  ) {
    // Keyed by the root path so this coalesces with validateAsync for the same graph and stays
    // stable across index rebuilds, unlike the declaration instance.
    val requestPath = GraphPath(listOf(graph.declarationId))
    launchCoalesced(requestPath, graph) { publish ->
      val results =
        withBackgroundProgress(project, progressTitle(graph)) {
          reportRawProgress { reporter ->
            retryCancelledIndexBuild {
              smartReadAction(project) {
                validateWithExtensions(element, graph) { progress ->
                  publish(progress)
                  reporter.details(progress.message)
                  reporter.fraction(progress.fraction)
                }
              }
            }
          }
        }
      withContext(Dispatchers.EDT) { onDone.accept(results) }
    }
  }

  /** Runs [validateWithExtensions] for one concrete graph path like [validateAsync]. */
  fun validateWithExtensionsAsync(
    element: PsiElement,
    context: GraphContext,
    onDone: Consumer<List<KaGraphValidationResult>>,
  ) {
    launchCoalesced(context.path, context.graph) { publish ->
      val results =
        withBackgroundProgress(project, progressTitle(context.graph)) {
          reportRawProgress { reporter ->
            retryCancelledIndexBuild {
              smartReadAction(project) {
                validateWithExtensions(element, context) { progress ->
                  publish(progress)
                  reporter.details(progress.message)
                  reporter.fraction(progress.fraction)
                }
              }
            }
          }
        }
      withContext(Dispatchers.EDT) { onDone.accept(results) }
    }
  }

  private fun progressTitle(graph: KaGraphDeclaration): String =
    "Validating Metro graph ${graphDisplayName(graph)}"

  private fun graphDisplayName(graph: KaGraphDeclaration): String {
    return graph.name ?: graph.classId?.asFqNameString() ?: "<unknown>"
  }

  /** Launches [block], cancelling any in-flight run for the same graph request. */
  private fun launchCoalesced(
    key: GraphPath,
    graph: KaGraphDeclaration,
    block: suspend CoroutineScope.((GraphValidationProgress) -> Unit) -> Unit,
  ) {
    val job =
      scope.launch(start = CoroutineStart.LAZY) {
        try {
          block { progress -> publishValidationProgress(key, progress) }
        } catch (e: ProcessCanceledException) {
          throw e
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          logger<MetroGraphValidationService>().warn("Metro graph validation failed", e)
        }
      }
    inFlight.put(key, job)?.cancel()
    publishValidationProgress(
      key,
      GraphValidationProgress(requestPath = key, graphName = graphDisplayName(graph)),
    )
    job.invokeOnCompletion {
      if (inFlight.remove(key, job)) {
        publishValidationProgress(key, null)
      }
    }
    job.start()
  }

  private fun publishValidationProgress(key: GraphPath, progress: GraphValidationProgress?) {
    if (progress == null) {
      validationProgress.remove(key)
    } else {
      validationProgress[key] = progress
    }
    notifyValidationProgressListeners()
  }

  private fun notifyValidationProgressListeners() {
    val application = ApplicationManager.getApplication()
    if (!application.isDispatchThread) {
      application.invokeLater {
        if (!project.isDisposed) notifyValidationProgressListeners()
      }
      return
    }
    val snapshot = validationProgress.values.sortedBy { it.requestPath.toString() }
    for (listener in validationProgressListeners.toList()) {
      listener(snapshot)
    }
  }

  private fun notifyValidationProgressListener(listener: (List<GraphValidationProgress>) -> Unit) {
    val application = ApplicationManager.getApplication()
    if (!application.isDispatchThread) {
      application.invokeLater {
        if (!project.isDisposed && listener in validationProgressListeners) {
          notifyValidationProgressListener(listener)
        }
      }
      return
    }
    if (listener in validationProgressListeners) {
      listener(validationProgress.values.sortedBy { it.requestPath.toString() })
    }
  }

  private fun moduleOptions(declarationElement: PsiElement): MetroOptions {
    val module = ModuleUtilCore.findModuleForPsiElement(declarationElement) ?: return MetroOptions()
    return project.service<MetroIdeProjectService>().state(module).options
  }

  private companion object {
    const val MAX_CACHED_RESULTS = 64
  }
}

/** Runs one graph seal while keeping plugin failures separate from Metro graph diagnostics. */
internal fun runGraphValidation(
  context: GraphContext,
  graphName: String,
  onInternalError: (Throwable) -> Unit = { cause ->
    logger<MetroGraphValidationService>()
      .error("Metro graph validation failed for $graphName", cause)
  },
  validate: () -> KaGraphValidationResult.Completed,
): KaGraphValidationResult {
  return try {
    validate()
  } catch (e: ProcessCanceledException) {
    throw e
  } catch (e: CancellationException) {
    throw e
  } catch (e: IncompleteGraphAnalysis) {
    KaGraphValidationResult.Incomplete(context, e.reason)
  } catch (e: Exception) {
    onInternalError(e)
    KaGraphValidationResult.InternalError(context, e)
  }
}
