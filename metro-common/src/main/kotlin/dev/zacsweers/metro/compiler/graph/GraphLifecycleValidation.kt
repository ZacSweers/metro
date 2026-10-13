// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.graph

import dev.zacsweers.metro.compiler.diagnostics.MetroDiagnosticId
import dev.zacsweers.metro.compiler.diagnostics.MetroSeverity
import dev.zacsweers.metro.compiler.diagnostics.Note

/** When a graph lifecycle callback runs. */
public enum class GraphLifecyclePhase(public val annotationName: String) {
  INIT("@OnGraphInit"),
  CLOSE("@OnGraphClose"),
}

/** How a closeable graph closes. */
public enum class GraphCloseKind {
  /** The graph implements `CloseableGraph`. */
  SYNC,
  /** The graph implements `SuspendCloseableGraph`. */
  SUSPEND,
}

/** One injected parameter of a lifecycle callback. */
public data class GraphLifecycleParameter<ContextualTypeKey, Source>(
  val name: String,
  val contextKey: ContextualTypeKey,
  val source: Source,
)

/** An `@OnGraphInit` or `@OnGraphClose` callback, normalized by a frontend. */
public data class GraphLifecycleCallback<ContextualTypeKey, Source>(
  val phase: GraphLifecyclePhase,
  /** The declaring type and function, such as `AppGraph.shutdown`. */
  val name: String,
  val isSuspend: Boolean,
  val parameters: List<GraphLifecycleParameter<ContextualTypeKey, Source>>,
  val source: Source,
)

/** How a graph that isn't a graph extension gets created. Parents check their extensions. */
public sealed interface GraphCreator<out Source> {
  /** `createGraph()`, a companion `invoke()`, or `createDynamicGraph()`. */
  public data object None : GraphCreator<Nothing>

  /** A graph factory function. */
  public data class Factory<Source>(
    val functionName: String,
    val isSuspend: Boolean,
    val source: Source,
  ) : GraphCreator<Source>
}

/** Somewhere a parent graph creates one of its graph extensions. */
public data class GraphExtensionCreationSite<Source>(
  /** The accessor or factory function name. */
  val name: String,
  /** False for an accessor that returns the graph extension directly. */
  val isFactory: Boolean,
  val isSuspend: Boolean,
  val source: Source,
)

/** Why a graph's init or close needs a suspend context. */
public data class GraphLifecycleSuspendReason<TypeKey, ContextualTypeKey, Source>(
  val callback: GraphLifecycleCallback<ContextualTypeKey, Source>,
  /** The parameter that needs a suspend context, or null when the callback itself suspends. */
  val parameter: GraphLifecycleParameter<ContextualTypeKey, Source>?,
  val path: SuspendBindingPath<TypeKey, ContextualTypeKey>?,
) {
  /** A sentence fragment like `Parameter 'database' of @OnGraphInit AppGraph.migrate needs ...`. */
  val description: String
    get() {
      val callbackRender = "${callback.phase.annotationName} ${callback.name}"
      return if (parameter == null) {
        "$callbackRender is a suspend function"
      } else {
        "Parameter '${parameter.name}' of $callbackRender depends on suspend bindings"
      }
    }
}

/** A frontend-independent graph lifecycle issue. */
public data class GraphLifecycleIssue<TypeKey, ContextualTypeKey, Source>(
  val diagnosticId: MetroDiagnosticId,
  val severity: MetroSeverity,
  val title: String,
  /** Where the issue should be reported. Null means the graph declaration. */
  val source: Source?,
  val notes: List<Note> = emptyList(),
  /** The callback parameter this issue is about, if any. Frontends use it to start a trace. */
  val callback: GraphLifecycleCallback<ContextualTypeKey, Source>? = null,
  val parameter: GraphLifecycleParameter<ContextualTypeKey, Source>? = null,
  /** A path from [parameter]'s binding to a suspend binding. */
  val path: SuspendBindingPath<TypeKey, ContextualTypeKey>? = null,
)

/** Shared lifecycle validation output. */
public data class GraphLifecycleValidationResult<TypeKey, ContextualTypeKey, Source>(
  val issues: List<GraphLifecycleIssue<TypeKey, ContextualTypeKey, Source>>,
  /** Why init callbacks need a suspend context, or null when they don't. */
  val initSuspendReason: GraphLifecycleSuspendReason<TypeKey, ContextualTypeKey, Source>?,
  /** Why close callbacks need a suspend context, or null when they don't. */
  val closeSuspendReason: GraphLifecycleSuspendReason<TypeKey, ContextualTypeKey, Source>?,
)
