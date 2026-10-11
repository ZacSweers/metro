// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.graph

import androidx.collection.ScatterMap
import dev.zacsweers.metro.compiler.diagnostics.MetroDiagnosticId
import dev.zacsweers.metro.compiler.diagnostics.MetroSeverity
import dev.zacsweers.metro.compiler.diagnostics.Note

/**
 * Applies Metro's graph lifecycle rules independently of IR or the Analysis API.
 *
 * Frontends supply normalized callbacks and binding facts. They anchor and render the returned
 * issues with their own declarations and binding stacks. Checks that need a parent and its graph
 * extensions together live in [validateGraphExtensionCreation].
 */
public class GraphLifecycleValidator<
  Type : Any,
  TypeKey : BaseTypeKey<Type, *, TypeKey>,
  ContextualTypeKey : BaseContextualTypeKey<Type, TypeKey, ContextualTypeKey>,
  Binding : BaseBinding<Type, TypeKey, ContextualTypeKey>,
  Source,
>(
  private val graphName: String,
  private val closeKind: GraphCloseKind?,
  private val callbacks: List<GraphLifecycleCallback<ContextualTypeKey, Source>>,
  /** Null for graph extensions. Their parents check how they're created. */
  private val creator: GraphCreator<Source>?,
  private val bindings: ScatterMap<TypeKey, Binding>,
  /** Returns a path to a suspend binding when resolving a request needs a suspend context. */
  private val suspendPath: (ContextualTypeKey) -> SuspendBindingPath<TypeKey, ContextualTypeKey>?,
  /**
   * Keys requested by accessors, injectors, graph extensions, and other roots besides callbacks.
   */
  private val nonCallbackRootKeys: Collection<TypeKey>,
  /** The binding that a binding passes through to, such as an alias target. */
  private val delegateKey: (Binding) -> TypeKey?,
  /** The element bindings of a multibinding, or null for other bindings. */
  private val multibindingElements: (Binding) -> Collection<TypeKey>?,
  /** Whether every request for a binding gets the same value, like a scoped binding. */
  private val isReused: (Binding) -> Boolean,
  /** Whether a binding's value comes from outside this graph, like a graph input. */
  private val isExternallySupplied: (Binding) -> Boolean,
  private val functionProvidersEnabled: Boolean,
  private val checkCanceled: () -> Unit = {},
) {
  public fun validate(): GraphLifecycleValidationResult<TypeKey, ContextualTypeKey, Source> {
    val issues = mutableListOf<GraphLifecycleIssue<TypeKey, ContextualTypeKey, Source>>()
    if (callbacks.isNotEmpty() && closeKind == null) {
      issues +=
        GraphLifecycleIssue(
          diagnosticId = MetroDiagnosticId.MISSING_CLOSEABLE_GRAPH,
          severity = MetroSeverity.ERROR,
          title =
            "'$graphName' has @OnGraphInit or @OnGraphClose callbacks, so it must implement " +
              "CloseableGraph or SuspendCloseableGraph.",
          source = null,
        )
      return GraphLifecycleValidationResult(issues, null, null)
    }

    val initReason = suspendReason(GraphLifecyclePhase.INIT)
    val closeReason = suspendReason(GraphLifecyclePhase.CLOSE)
    if (closeKind == GraphCloseKind.SYNC && closeReason != null) {
      issues +=
        reasonIssue(
          id = MetroDiagnosticId.SUSPEND_CLOSE_REQUIRED,
          title =
            "${closeReason.description}, so '$graphName' must implement SuspendCloseableGraph " +
              "instead of CloseableGraph.",
          reason = closeReason,
          source = closeReason.parameter?.source ?: closeReason.callback.source,
        )
    }
    if (creator != null && initReason != null) {
      creatorIssue(creator, initReason)?.let(issues::add)
    }
    validateParameterBindings(issues)
    return GraphLifecycleValidationResult(issues, initReason, closeReason)
  }

  /** Finds the first callback, or callback parameter, that makes [phase] need a suspend context. */
  private fun suspendReason(
    phase: GraphLifecyclePhase,
  ): GraphLifecycleSuspendReason<TypeKey, ContextualTypeKey, Source>? {
    val phaseCallbacks = callbacks.filter { it.phase == phase }
    val suspendCallback = phaseCallbacks.firstOrNull { it.isSuspend }
    if (suspendCallback != null) {
      return GraphLifecycleSuspendReason(suspendCallback, parameter = null, path = null)
    }
    for (callback in phaseCallbacks) {
      for (parameter in callback.parameters) {
        val path = suspendPath(parameter.contextKey) ?: continue
        return GraphLifecycleSuspendReason(callback, parameter, path)
      }
    }
    return null
  }

  private fun creatorIssue(
    creator: GraphCreator<Source>,
    reason: GraphLifecycleSuspendReason<TypeKey, ContextualTypeKey, Source>,
  ): GraphLifecycleIssue<TypeKey, ContextualTypeKey, Source>? {
    val prefix = "${reason.description}, so creating '$graphName' suspends."
    return when (creator) {
      GraphCreator.None ->
        reasonIssue(
          id = MetroDiagnosticId.GRAPH_INIT_SUSPEND_REQUIRED,
          title =
            "$prefix Declare a graph factory whose function is suspend and create '$graphName' " +
              "with it.",
          reason = reason,
          source = null,
        )
      is GraphCreator.Factory if !creator.isSuspend ->
        reasonIssue(
          id = MetroDiagnosticId.GRAPH_INIT_SUSPEND_REQUIRED,
          title = "$prefix Make '${creator.functionName}' a suspend function.",
          reason = reason,
          source = creator.source,
        )
      is GraphCreator.Factory -> null
    }
  }

  private fun reasonIssue(
    id: MetroDiagnosticId,
    title: String,
    reason: GraphLifecycleSuspendReason<TypeKey, ContextualTypeKey, Source>,
    source: Source?,
  ): GraphLifecycleIssue<TypeKey, ContextualTypeKey, Source> =
    GraphLifecycleIssue(
      diagnosticId = id,
      severity = MetroSeverity.ERROR,
      title = title,
      source = source,
      callback = reason.callback,
      parameter = reason.parameter,
      path = reason.path,
    )

  private fun validateParameterBindings(
    issues: MutableList<GraphLifecycleIssue<TypeKey, ContextualTypeKey, Source>>,
  ) {
    var nonCloseReachable: Set<TypeKey>? = null
    for (callback in callbacks) {
      for (parameter in callback.parameters) {
        checkCanceled()
        unscopedParameterIssue(callback, parameter)?.let(issues::add)
        if (callback.phase != GraphLifecyclePhase.CLOSE) continue
        val reachable =
          nonCloseReachable ?: computeNonCloseReachable().also { nonCloseReachable = it }
        closeOnlyBindingIssue(callback, parameter, reachable)?.let(issues::add)
      }
    }
  }

  private fun unscopedParameterIssue(
    callback: GraphLifecycleCallback<ContextualTypeKey, Source>,
    parameter: GraphLifecycleParameter<ContextualTypeKey, Source>,
  ): GraphLifecycleIssue<TypeKey, ContextualTypeKey, Source>? {
    val contextKey = parameter.contextKey
    // Provider and lazy requests resolve explicitly, so they don't hide a discarded instance.
    if (contextKey.isDeferrable) return null
    val binding = resolve(contextKey.typeKey) ?: return null
    val typeRender = contextKey.typeKey.render(short = true)
    val site = "parameter '${parameter.name}' of ${callback.phase.annotationName} ${callback.name}"
    val elements = multibindingElements(binding)
    if (elements != null) {
      val hasUnscopedElement = elements.any { key -> resolve(key)?.let(isReused) == false }
      if (!hasUnscopedElement) return null
      return warning(
        id = MetroDiagnosticId.UNSCOPED_LIFECYCLE_PARAMETER,
        title =
          "$typeRender includes unscoped bindings and is injected into $site. Those instances " +
            "may be discarded after the callback.",
        help = "scope the bindings contributed to $typeRender",
        callback = callback,
        parameter = parameter,
      )
    }
    if (isReused(binding)) return null
    val providerRender =
      if (functionProvidersEnabled) {
        "() -> $typeRender"
      } else {
        "Provider<$typeRender>"
      }
    return warning(
      id = MetroDiagnosticId.UNSCOPED_LIFECYCLE_PARAMETER,
      title =
        "Unscoped $typeRender is injected into $site. This instance may be discarded after the " +
          "callback.",
      help = "request $providerRender or Lazy<$typeRender> to resolve $typeRender explicitly",
      callback = callback,
      parameter = parameter,
    )
  }

  private fun closeOnlyBindingIssue(
    callback: GraphLifecycleCallback<ContextualTypeKey, Source>,
    parameter: GraphLifecycleParameter<ContextualTypeKey, Source>,
    nonCloseReachable: Set<TypeKey>,
  ): GraphLifecycleIssue<TypeKey, ContextualTypeKey, Source>? {
    val binding = resolve(parameter.contextKey.typeKey) ?: return null
    if (binding.typeKey in nonCloseReachable) return null
    val typeRender = parameter.contextKey.typeKey.render(short = true)
    val resolving = "Resolving parameter '${parameter.name}' of ${callback.name}"
    val elements = multibindingElements(binding)
    if (elements != null) {
      val hasCloseOnlyElement = elements.any { key ->
        val element = resolve(key) ?: return@any false
        val isElementCloseOnly = element.typeKey !in nonCloseReachable
        isElementCloseOnly && !isExternallySupplied(element)
      }
      if (!hasCloseOnlyElement) return null
      return warning(
        id = MetroDiagnosticId.CLOSE_ONLY_BINDING,
        title =
          "$typeRender includes bindings that are reachable only from @OnGraphClose callbacks in " +
            "$graphName. $resolving may create them for the first time during graph close.",
        callback = callback,
        parameter = parameter,
      )
    }
    if (isExternallySupplied(binding)) return null
    return warning(
      id = MetroDiagnosticId.CLOSE_ONLY_BINDING,
      title =
        "$typeRender is reachable only from @OnGraphClose callbacks in $graphName. $resolving may " +
          "create $typeRender for the first time during graph close.",
      callback = callback,
      parameter = parameter,
    )
  }

  /** Everything requested outside close callbacks, including init callbacks. */
  private fun computeNonCloseReachable(): Set<TypeKey> {
    val reachable = HashSet<TypeKey>()
    val queue = ArrayDeque<TypeKey>()
    queue.addAll(nonCallbackRootKeys)
    for (callback in callbacks) {
      if (callback.phase != GraphLifecyclePhase.INIT) continue
      callback.parameters.mapTo(queue) { it.contextKey.typeKey }
    }
    while (queue.isNotEmpty()) {
      checkCanceled()
      val key = queue.removeFirst()
      if (!reachable.add(key)) continue
      val binding = bindings[key] ?: continue
      binding.dependencies.mapTo(queue) { it.typeKey }
    }
    return reachable
  }

  /** Follows aliases and similar pass-through bindings to the binding that creates the value. */
  private fun resolve(key: TypeKey): Binding? {
    var current = bindings[key] ?: return null
    val seen = mutableSetOf(key)
    while (true) {
      val next = delegateKey(current) ?: return current
      if (!seen.add(next)) return current
      current = bindings[next] ?: return current
    }
  }

  private fun warning(
    id: MetroDiagnosticId,
    title: String,
    callback: GraphLifecycleCallback<ContextualTypeKey, Source>,
    parameter: GraphLifecycleParameter<ContextualTypeKey, Source>,
    help: String? = null,
  ): GraphLifecycleIssue<TypeKey, ContextualTypeKey, Source> =
    GraphLifecycleIssue(
      diagnosticId = id,
      severity = MetroSeverity.WARNING,
      title = title,
      source = parameter.source,
      notes = listOfNotNull(help?.let(Note::help)),
      callback = callback,
      parameter = parameter,
    )
}

/**
 * Checks how a parent graph creates one of its graph extensions.
 *
 * A graph extension whose init needs a suspend context can only be created through a suspend
 * factory function. A `CloseableGraph` parent can't close an extension whose cleanup suspends.
 *
 * @param extensionInitSuspendReason why the extension's init suspends, or null when it doesn't.
 */
public fun <TypeKey, ContextualTypeKey, Source> validateGraphExtensionCreation(
  parentName: String,
  parentCloseKind: GraphCloseKind?,
  extensionName: String,
  extensionCloseKind: GraphCloseKind?,
  extensionInitSuspendReason: String?,
  sites: List<GraphExtensionCreationSite<Source>>,
): List<GraphLifecycleIssue<TypeKey, ContextualTypeKey, Source>> {
  val issues = mutableListOf<GraphLifecycleIssue<TypeKey, ContextualTypeKey, Source>>()
  if (extensionInitSuspendReason != null) {
    for (site in sites) {
      val fix =
        when {
          !site.isFactory ->
            "'${site.name}' can't create it directly. Create it through a " +
              "@GraphExtension.Factory whose function is suspend."
          !site.isSuspend -> "Make '${site.name}' a suspend function."
          else -> continue
        }
      issues +=
        GraphLifecycleIssue(
          diagnosticId = MetroDiagnosticId.GRAPH_INIT_SUSPEND_REQUIRED,
          severity = MetroSeverity.ERROR,
          title = "$extensionInitSuspendReason, so creating '$extensionName' suspends. $fix",
          source = site.source,
        )
    }
  }
  val closesSuspendingExtension =
    parentCloseKind == GraphCloseKind.SYNC && extensionCloseKind == GraphCloseKind.SUSPEND
  if (closesSuspendingExtension) {
    issues +=
      GraphLifecycleIssue(
        diagnosticId = MetroDiagnosticId.SUSPEND_CLOSE_REQUIRED,
        severity = MetroSeverity.ERROR,
        title =
          "'$parentName' closes '$extensionName', which implements SuspendCloseableGraph, so " +
            "'$parentName' must implement SuspendCloseableGraph instead of CloseableGraph.",
        // This is about the parent, so it's reported on the parent graph.
        source = null,
      )
  }
  return issues
}
