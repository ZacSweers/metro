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
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.platform.util.progress.reportRawProgress
import com.intellij.psi.PsiElement
import dev.zacsweers.metro.compiler.MetroOptions
import dev.zacsweers.metro.idea.MetroDaemonRestartService
import dev.zacsweers.metro.idea.index.MetroResolutionService
import dev.zacsweers.metro.idea.index.retryCancelledIndexBuild
import dev.zacsweers.metro.idea.model.BindingIndex
import dev.zacsweers.metro.idea.model.BindingResolutionSession
import dev.zacsweers.metro.idea.model.GraphContext
import dev.zacsweers.metro.idea.model.GraphPath
import dev.zacsweers.metro.idea.model.GraphQueryContext
import dev.zacsweers.metro.idea.model.IndexGenerationToken
import dev.zacsweers.metro.idea.model.KaGraphDeclaration
import java.util.IdentityHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Consumer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.annotations.TestOnly

/** A retained validation result plus whether the index changed since it was produced. */
internal class CachedValidation(val result: KaGraphValidationResult, val stale: Boolean)

/**
 * Validates explicit and opt-in automatic requests through [KaBindingGraph]. Results are retained
 * per concrete parent path and marked stale when the index they were sealed against is invalidated.
 */
@Service(Service.Level.PROJECT)
internal class MetroGraphValidationService(
  private val project: Project,
  private val scope: CoroutineScope,
) {

  private class CachedEntry(
    val result: KaGraphValidationResult,
    val generationToken: IndexGenerationToken,
    val runVersion: Long,
  )

  private class PublishedResults(
    val clearVersion: Long,
    val entries: Map<GraphPath, CachedEntry>,
  ) {
    fun cleared(): PublishedResults = PublishedResults(clearVersion + 1, emptyMap())

    fun with(bundle: ValidationResultBundle): PublishedResults {
      if (bundle.clearVersion != clearVersion || bundle.entries.isEmpty()) return this

      val updated = LinkedHashMap(entries)
      var changed = false
      for ((path, entry) in bundle.entries) {
        val current = updated[path]
        if (current != null && current.runVersion > entry.runVersion) continue
        updated.remove(path)
        updated[path] = entry
        changed = true
      }
      if (!changed) return this
      val iterator = updated.entries.iterator()
      while (updated.size > MAX_CACHED_RESULTS && iterator.hasNext()) {
        iterator.next()
        iterator.remove()
      }
      return PublishedResults(clearVersion, updated.toMap())
    }
  }

  private class ValidationResultBundle(
    val clearVersion: Long,
    val runVersion: Long,
    val entries: Map<GraphPath, CachedEntry>,
  )

  /** A finished computation waiting to be added to the shared result cache. */
  private class CompletedValidation<T>(
    val requestPath: GraphPath,
    val result: T,
    val bundle: ValidationResultBundle,
  )

  /** Captured graph inputs and private cache state for one validation run. */
  private class CapturedValidation(
    val traversal: ValidationTraversal,
    val workspace: ValidationWorkspace,
  )

  private class ValidationWorkspace(
    private val initial: PublishedResults,
    private val runVersion: Long,
  ) {
    private val results = linkedMapOf<GraphPath, CachedEntry>()
    private val publishableResults = linkedMapOf<GraphPath, CachedEntry>()

    fun cached(path: GraphPath, generationToken: IndexGenerationToken): KaGraphValidationResult? {
      val entry = results[path] ?: initial.entries[path]
      return entry?.takeIf { it.generationToken === generationToken }?.result
    }

    fun cache(
      path: GraphPath,
      result: KaGraphValidationResult,
      generationToken: IndexGenerationToken,
    ) {
      val entry = CachedEntry(result, generationToken, runVersion)
      results[path] = entry
      publishableResults.remove(path)
      publishableResults[path] = entry
      if (publishableResults.size > MAX_CACHED_RESULTS) {
        val oldest = publishableResults.entries.iterator()
        oldest.next()
        oldest.remove()
      }
    }

    fun finish(): ValidationResultBundle {
      return ValidationResultBundle(
        initial.clearVersion,
        runVersion,
        publishableResults.toMap(),
      )
    }
  }

  /** Tracks one validation request so replacements can suppress its results and callbacks. */
  private class ValidationRequest(
    val path: GraphPath,
    val token: Any,
    val publishProgress: (GraphValidationProgress) -> Unit,
  )

  private class ActiveValidation(
    val token: Any,
    val job: Job,
    val progress: GraphValidationProgress,
  )

  private class ValidationActivity(val byPath: Map<GraphPath, ActiveValidation>) {
    fun starting(path: GraphPath, validation: ActiveValidation): ValidationActivity {
      return ValidationActivity(byPath + (path to validation))
    }

    fun progressing(
      path: GraphPath,
      token: Any,
      progress: GraphValidationProgress,
    ): ValidationActivity {
      val validation = byPath[path] ?: return this
      if (validation.token !== token) return this
      return ValidationActivity(
        byPath + (path to ActiveValidation(token, validation.job, progress))
      )
    }

    fun completed(path: GraphPath, token: Any): ValidationActivity {
      val validation = byPath[path] ?: return this
      if (validation.token !== token) return this
      return ValidationActivity(byPath - path)
    }
  }

  private fun cacheKey(context: GraphContext): GraphPath? {
    val hasLocalGraph = context.path.segments.any { it.classId == null }
    return context.path.takeUnless { hasLocalGraph }
  }

  private val publishedResults = AtomicReference(PublishedResults(0, emptyMap()))
  private val validationActivity = AtomicReference(ValidationActivity(emptyMap()))
  private val validationRunVersion = AtomicLong()
  /** Keeps asynchronous result publication atomic with request replacement. */
  private val validationRequestLock = Any()
  private val validationProgressNotificationPending = AtomicBoolean()
  private val validationProgressListeners =
    CopyOnWriteArrayList<(List<GraphValidationProgress>) -> Unit>()
  private val resultListeners = CopyOnWriteArrayList<() -> Unit>()
  private val resultNotificationPending = AtomicBoolean()
  private val beforeValidationPublicationObserver =
    AtomicReference<((GraphPath, Long) -> Unit)?>(null)
  private val beforeGraphSealObserver = AtomicReference<((GraphPath) -> Unit)?>(null)

  /** Drops all retained results. */
  fun clearResults() {
    publishedResults.updateAndGet(PublishedResults::cleared)
    resultsChanged()
  }

  /** Reads retained results and their freshness without scheduling index or presentation work. */
  internal fun retainedResults(): List<CachedValidation> {
    val entries = publishedResults.get().entries.values
    if (entries.isEmpty()) return emptyList()
    val resolution = project.service<MetroResolutionService>()
    val staleByGeneration = IdentityHashMap<IndexGenerationToken, Boolean>()
    return entries.map { entry ->
      val stale =
        staleByGeneration.getOrPut(entry.generationToken) {
          !resolution.isCurrentGeneration(entry.generationToken)
        }
      CachedValidation(entry.result, stale)
    }
  }

  /** Delivers coalesced result publication and clear events on the EDT. */
  internal fun addResultListener(parentDisposable: Disposable, listener: () -> Unit) {
    resultListeners += listener
    Disposer.register(parentDisposable) { resultListeners -= listener }
  }

  private fun resultsChanged() {
    project.service<MetroDaemonRestartService>().requestRestart()
    resultNotificationPending.set(true)
    scheduleValidationProgressNotification()
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
    return validationActivity.get().byPath.values.any { it.progress.covers(path) }
  }

  /** Lets tests pause after computation to control result publication order. */
  @TestOnly
  internal fun setBeforeValidationPublicationObserver(observer: ((GraphPath, Long) -> Unit)?) {
    beforeValidationPublicationObserver.set(observer)
  }

  /**
   * Lets tests pause after input capture to inspect write access and cancellation during a seal.
   */
  @TestOnly
  internal fun setBeforeGraphSealObserver(observer: ((GraphPath) -> Unit)?) {
    beforeGraphSealObserver.set(observer)
  }

  /**
   * The last result for [context], or null if it was never validated. Results survive index
   * invalidation so the outcome stays visible. [CachedValidation.stale] flags that the code may
   * have changed since the run.
   */
  fun cachedResult(element: PsiElement, context: GraphContext): CachedValidation? {
    val key = cacheKey(context) ?: return null
    val entry = publishedResults.get().entries[key] ?: return null
    val contextElement = context.contextPointer.element ?: element
    val resolutionService = project.service<MetroResolutionService>()
    val presentationIndex = resolutionService.presentationIndex(contextElement)
    val stale =
      entry.generationToken !== presentationIndex.generationToken ||
        !resolutionService.isCurrent(presentationIndex)
    return CachedValidation(entry.result, stale)
  }

  /**
   * Validates one concrete [context], reusing the cached result only when the index is unchanged.
   * Must be called under a read action.
   */
  fun validate(element: PsiElement, context: GraphContext): KaGraphValidationResult {
    return publishCompletedValidation(computeValidation(captureValidation(element, context)))
  }

  /** Keeps this computation's cache entries private until the caller publishes the result. */
  private fun computeValidation(
    captured: CapturedValidation
  ): CompletedValidation<KaGraphValidationResult> {
    val result = validate(captured.traversal.inputs.last(), captured.workspace)
    return CompletedValidation(captured.traversal.requestPath, result, captured.workspace.finish())
  }

  private fun captureValidation(
    element: PsiElement,
    context: GraphContext,
    includeExtensions: Boolean = false,
    allowIndexBuild: Boolean = true,
  ): CapturedValidation {
    val workspace = validationWorkspace()
    val traversal =
      ValidationInputCapture(project, workspace::cached, allowIndexBuild)
        .capture(element, context, includeExtensions)
    return CapturedValidation(traversal, workspace)
  }

  private fun captureValidation(
    element: PsiElement,
    graph: KaGraphDeclaration,
  ): CapturedValidation {
    val workspace = validationWorkspace()
    val traversal = ValidationInputCapture(project, workspace::cached).capture(element, graph)
    return CapturedValidation(traversal, workspace)
  }

  /**
   * Inspects the same module-aware lookup used by a graph seal without retaining or caching it.
   * Returns null if the requested graph path disappeared. Must be called under a read action.
   */
  fun <T> debugLookup(
    element: PsiElement,
    context: GraphContext,
    block:
      (
        BindingIndex,
        BindingResolutionSession,
        GraphQueryContext,
        MetroOptions,
        KaBindingLookup,
      ) -> T,
  ): T? {
    val capture = ValidationInputCapture(project)
    val input = capture.lookup(element, context) ?: return null
    val lookup =
      KaBindingLookup(input.session, input.queryContext, input.options) { parentContext ->
        capture.lookup(element, parentContext)
      }
    return try {
      block(input.index, input.session, input.queryContext, input.options, lookup)
    } finally {
      lookup.clear()
    }
  }

  private fun validate(
    input: ValidationInput,
    workspace: ValidationWorkspace,
  ): KaGraphValidationResult {
    val index = input.index
    if (input is ValidationInput.Cached) return input.result
    input as ValidationInput.Unsealed
    val context = input.context
    val key = cacheKey(context)
    if (key != null) {
      val cached = workspace.cached(key, index.generationToken)
      if (cached != null) return cached
    }

    // Extension children seal first, mirroring the compiler's traversal, so any keys they
    // delegate upward are validated in this seal through the reservations below. Cached child
    // results still carry their reservations, so cache hits stay correct. Each child resolves
    // through its own declaration module so per-module options and library views apply.
    val reservations = mutableListOf<ReservedParentKey>()
    var childFailed = false
    var incompleteChild: KaGraphValidationResult.Incomplete? = null
    for (child in input.children) {
      ProgressManager.checkCanceled()
      val childResult = validate(child, workspace)
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
          ProgressManager.checkCanceled()
          beforeGraphSealObserver.get()?.invoke(context.path)
          ProgressManager.checkCanceled()
          KaBindingGraph(
              input.session,
              input.queryContext,
              input.options,
              input.sources,
              reservations,
            ) { parentContext ->
              input.parents[parentContext.path]
            }
            .seal()
        }
      }
    // Expected analysis limits are stable for this immutable index and should not rerun on every
    // gutter refresh. Internal errors stay uncached so transient plugin failures can retry. A
    // parent sealed without a crashed child's reservations must also retry once the child does.
    if (key != null && result !is KaGraphValidationResult.InternalError && !childFailed) {
      workspace.cache(key, result, index.generationToken)
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
    return publishCompletedValidation(
      computeValidationWithExtensions(captureValidation(element, graph), onProgress)
    )
  }

  /** Validates one concrete graph path and the extension paths it creates. */
  fun validateWithExtensions(
    element: PsiElement,
    context: GraphContext,
    onProgress: (GraphValidationProgress) -> Unit = {},
  ): List<KaGraphValidationResult> {
    return publishCompletedValidation(
      computeValidationWithExtensions(
        captureValidation(element, context, includeExtensions = true),
        onProgress,
      )
    )
  }

  /** Computes a captured child-first traversal without reading source declarations. */
  private fun computeValidationWithExtensions(
    captured: CapturedValidation,
    onProgress: (GraphValidationProgress) -> Unit,
  ): CompletedValidation<List<KaGraphValidationResult>> {
    val results = validateTraversal(captured.traversal, captured.workspace, onProgress)
    return CompletedValidation(captured.traversal.requestPath, results, captured.workspace.finish())
  }

  private fun validateTraversal(
    traversal: ValidationTraversal,
    workspace: ValidationWorkspace,
    onProgress: (GraphValidationProgress) -> Unit,
  ): List<KaGraphValidationResult> {
    val traversalResults = ArrayList<KaGraphValidationResult>(traversal.inputs.size)
    for ((index, input) in traversal.inputs.withIndex()) {
      ProgressManager.checkCanceled()
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
      traversalResults += validate(input, workspace)
    }
    return traversalResults
  }

  /**
   * Captures under a cancellable read action, seals outside it, and delivers results on the EDT.
   */
  fun validateAsync(
    element: PsiElement,
    context: GraphContext,
    onDone: Consumer<KaGraphValidationResult>,
  ): Job {
    return launchLatestValidation(context.path, context.graph) { request ->
      val completed =
        withBackgroundProgress(project, progressTitle(context.graph)) {
          reportRawProgress { reporter ->
            val captured = retryCancelledIndexBuild {
              smartReadAction(project) {
                request.publishProgress(
                  GraphValidationProgress(
                    requestPath = context.path,
                    graphName = graphDisplayName(context.graph),
                    completed = 0,
                    total = 1,
                  )
                )
                reporter.details("Validating ${graphDisplayName(context.graph)}")
                reporter.fraction(0.0)
                captureValidation(element, context)
              }
            }
            withContext(Dispatchers.Default) { computeValidation(captured) }
          }
        }
      if (publishCompletedValidationIfCurrent(request, completed)) {
        withContext(Dispatchers.EDT) {
          if (isCurrentValidation(request)) onDone.accept(completed.result)
        }
      }
    }
  }

  /**
   * Captures the graph and its extensions together, then seals their immutable inputs outside read
   * access. Results are cached after computation, and [onDone] runs on the EDT.
   */
  fun validateWithExtensionsAsync(
    graph: KaGraphDeclaration,
    onDone: Consumer<List<KaGraphValidationResult>>,
  ): Job {
    // Use the same path as validateAsync so either entry point replaces an older request.
    val requestPath = GraphPath(listOf(graph.declarationId))
    return launchLatestValidation(requestPath, graph) { request ->
      val completed =
        withBackgroundProgress(project, progressTitle(graph)) {
          reportRawProgress { reporter ->
            val captured = retryCancelledIndexBuild {
              smartReadAction(project) {
                val element =
                  graph.pointer.element
                    ?: throw CancellationException("Metro graph is no longer available")
                captureValidation(element, graph)
              }
            }
            withContext(Dispatchers.Default) {
              computeValidationWithExtensions(captured) { progress ->
                request.publishProgress(progress)
                reporter.details(progress.message)
                reporter.fraction(progress.fraction)
              }
            }
          }
        }
      if (publishCompletedValidationIfCurrent(request, completed)) {
        withContext(Dispatchers.EDT) {
          if (isCurrentValidation(request)) onDone.accept(completed.result)
        }
      }
    }
  }

  /**
   * Captures this concrete path and its extensions together, then seals outside read access.
   * Results are cached after computation, and [onDone] runs on the EDT. When [showProgress] is
   * false, progress remains available to Metro listeners. Automatic requests set [allowIndexBuild]
   * to false so an intervening edit cancels capture until refreshed graph data is published.
   */
  fun validateWithExtensionsAsync(
    context: GraphContext,
    showProgress: Boolean = true,
    allowIndexBuild: Boolean = true,
    onDone: Consumer<List<KaGraphValidationResult>>,
  ): Job {
    return launchLatestValidation(context.path, context.graph) { request ->
      val completed =
        if (showProgress) {
          withBackgroundProgress(project, progressTitle(context.graph)) {
            reportRawProgress { reporter ->
              computeContextWithExtensions(context, allowIndexBuild) { progress ->
                request.publishProgress(progress)
                reporter.details(progress.message)
                reporter.fraction(progress.fraction)
              }
            }
          }
        } else {
          computeContextWithExtensions(context, allowIndexBuild, request.publishProgress)
        }
      if (publishCompletedValidationIfCurrent(request, completed)) {
        withContext(Dispatchers.EDT) {
          if (isCurrentValidation(request)) onDone.accept(completed.result)
        }
      }
    }
  }

  /** Shares the capture and seal sequence with quiet, automatic validation requests. */
  private suspend fun computeContextWithExtensions(
    context: GraphContext,
    allowIndexBuild: Boolean,
    onProgress: (GraphValidationProgress) -> Unit,
  ): CompletedValidation<List<KaGraphValidationResult>> {
    val captured = retryCancelledIndexBuild {
      smartReadAction(project) {
        val element =
          context.contextPointer.element
            ?: throw CancellationException("Metro graph context is no longer available")
        captureValidation(
          element,
          context,
          includeExtensions = true,
          allowIndexBuild = allowIndexBuild,
        )
      }
    }
    return withContext(Dispatchers.Default) {
      computeValidationWithExtensions(captured, onProgress)
    }
  }

  private fun progressTitle(graph: KaGraphDeclaration): String =
    "Validating Metro graph ${graphDisplayName(graph)}"

  private fun graphDisplayName(graph: KaGraphDeclaration): String {
    return graph.name ?: graph.classId?.asFqNameString() ?: "<unknown>"
  }

  private fun launchLatestValidation(
    key: GraphPath,
    graph: KaGraphDeclaration,
    block: suspend CoroutineScope.(ValidationRequest) -> Unit,
  ): Job {
    val token = Any()
    val job =
      scope.launch(start = CoroutineStart.LAZY) {
        try {
          block(
            ValidationRequest(key, token) { progress ->
              publishValidationProgress(key, token, progress)
            }
          )
        } catch (e: ProcessCanceledException) {
          throw e
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          logger<MetroGraphValidationService>().warn("Metro graph validation failed", e)
        }
      }
    val validation =
      ActiveValidation(
        token,
        job,
        GraphValidationProgress(requestPath = key, graphName = graphDisplayName(graph)),
      )
    val previous = startValidation(key, validation)
    previous?.job?.cancel()
    scheduleValidationProgressNotification()
    job.invokeOnCompletion {
      if (completeValidation(key, token)) {
        scheduleValidationProgressNotification()
      }
    }
    job.start()
    return job
  }

  private fun startValidation(
    path: GraphPath,
    validation: ActiveValidation,
  ): ActiveValidation? {
    return synchronized(validationRequestLock) {
      val previous = validationActivity.getAndUpdate { it.starting(path, validation) }
      previous.byPath[path]
    }
  }

  private fun completeValidation(path: GraphPath, token: Any): Boolean {
    val previous = validationActivity.getAndUpdate { it.completed(path, token) }
    return previous.byPath[path]?.token === token
  }

  private fun publishValidationProgress(
    path: GraphPath,
    token: Any,
    progress: GraphValidationProgress,
  ) {
    val previous = validationActivity.getAndUpdate { it.progressing(path, token, progress) }
    val changed = previous.byPath[path]?.token === token
    if (changed) scheduleValidationProgressNotification()
  }

  private fun isCurrentValidation(request: ValidationRequest): Boolean {
    return validationActivity.get().byPath[request.path]?.token === request.token
  }

  /** Adds a completed result to the cache before returning it. */
  private fun <T> publishCompletedValidation(completed: CompletedValidation<T>): T {
    beforeValidationPublicationObserver
      .get()
      ?.invoke(completed.requestPath, completed.bundle.runVersion)
    publish(completed.bundle)
    return completed.result
  }

  /** Only the latest active request may update the result cache. */
  private suspend fun publishCompletedValidationIfCurrent(
    request: ValidationRequest,
    completed: CompletedValidation<*>,
  ): Boolean {
    val coroutineContext = currentCoroutineContext()
    coroutineContext.ensureActive()
    beforeValidationPublicationObserver
      .get()
      ?.invoke(completed.requestPath, completed.bundle.runVersion)
    coroutineContext.ensureActive()
    val published =
      synchronized(validationRequestLock) {
        coroutineContext.ensureActive()
        if (!isCurrentValidation(request)) {
          false
        } else {
          publish(completed.bundle)
          true
        }
      }
    coroutineContext.ensureActive()
    return published && isCurrentValidation(request)
  }

  private fun publish(bundle: ValidationResultBundle) {
    if (bundle.entries.isEmpty()) return
    while (true) {
      val previous = publishedResults.get()
      val updated = previous.with(bundle)
      if (updated === previous) return
      if (publishedResults.compareAndSet(previous, updated)) {
        resultsChanged()
        return
      }
    }
  }

  private fun validationWorkspace(): ValidationWorkspace {
    return ValidationWorkspace(publishedResults.get(), validationRunVersion.incrementAndGet())
  }

  /** Queues one EDT update for the latest progress, even when a traversal reports many graphs. */
  private fun scheduleValidationProgressNotification() {
    if (validationProgressListeners.isEmpty() && resultListeners.isEmpty()) return
    if (!validationProgressNotificationPending.compareAndSet(false, true)) return
    ApplicationManager.getApplication().invokeLater {
      // Clear first so completion during listener callbacks can still queue an update.
      validationProgressNotificationPending.set(false)
      if (project.isDisposed) return@invokeLater
      if (resultNotificationPending.getAndSet(false)) {
        for (listener in resultListeners.toList()) listener()
      }
      val snapshot = validationProgressSnapshot()
      for (listener in validationProgressListeners.toList()) {
        listener(snapshot)
      }
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
      listener(validationProgressSnapshot())
    }
  }

  private fun validationProgressSnapshot(): List<GraphValidationProgress> {
    return validationActivity.get().byPath.values.map(ActiveValidation::progress).sortedBy {
      it.requestPath.toString()
    }
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
