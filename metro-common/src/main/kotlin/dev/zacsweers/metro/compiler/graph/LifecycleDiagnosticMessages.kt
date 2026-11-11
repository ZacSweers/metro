// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.graph

import org.jetbrains.kotlin.name.CallableId

/**
 * FIR checks lifecycle declarations. IR checks the assembled graphs after merging contributions and
 * resolving extension bindings.
 */
public object LifecycleDiagnosticMessages {
  public const val CLOSE_IS_GENERATED: String =
    "Metro implements close(). Move this cleanup into an @OnGraphClose callback."

  public const val CALLBACK_MUST_BE_MEMBER: String = "Lifecycle callbacks must be member functions."

  public const val CALLBACK_MUST_HAVE_BODY: String = "Lifecycle callbacks must have a body."

  public const val CALLBACK_MUST_RETURN_UNIT: String = "Lifecycle callbacks must return Unit."

  public const val CALLBACK_CANNOT_BE_PRIVATE: String = "Lifecycle callbacks can't be private."

  public const val CALLBACK_CANNOT_HAVE_EXTENSION_RECEIVER: String =
    "Lifecycle callbacks can't have an extension receiver."

  public const val CALLBACK_CANNOT_HAVE_CONTEXT_PARAMETERS: String =
    "Lifecycle callbacks can't have context parameters."

  public const val CALLBACK_CANNOT_HAVE_TYPE_PARAMETERS: String =
    "Lifecycle callbacks can't have type parameters."

  /** A final class or an object has no subtypes, so no graph ever calls its callbacks. */
  public fun callbackOwnerIsFinal(owner: String): String =
    "'$owner' is final, so no graph can inherit this lifecycle callback. Declare it on a graph or on an interface that the graph extends."

  /** Graphs include binding containers without extending them, so their callbacks never run. */
  public fun callbackOwnerIsBindingContainer(owner: String): String =
    "'$owner' is a binding container, so no graph inherits this lifecycle callback. Declare it on a graph or on an interface that the graph extends."

  public fun conflictingCallbackAnnotations(callback: String): String =
    "'$callback' can't serve as both @OnGraphInit and @OnGraphClose. Use a separate function for each phase."

  public fun overrideMustRepeatAnnotation(annotation: String): String =
    "An override of a lifecycle callback must repeat @$annotation."

  public fun callbackIsCalledByMetro(callback: String): String =
    "Graph lifecycle callback '$callback' should not be invoked directly."

  public fun graphMustBeCloseable(graph: String): String =
    "'$graph' has lifecycle callbacks, so it must implement CloseableGraph or SuspendCloseableGraph."

  public fun suspendCloseNeedsSuspendGraph(graph: String, callback: String): String =
    "Suspend @OnGraphClose callback '$callback' requires '$graph' to implement SuspendCloseableGraph."

  /**
   * Explains why the graph's creation function must suspend. [factoryFunction] is that function, or
   * null when the graph has no factory.
   */
  public fun suspendInitNeedsSuspendFactory(
    graph: String,
    callback: String,
    factoryFunction: CallableId?,
  ): String {
    val reason = "because @OnGraphInit callback '$callback' suspends."
    val factoryClass = factoryFunction?.className
    if (factoryFunction == null || factoryClass == null) {
      return "'$graph' needs a graph factory with a suspend creation function $reason"
    }
    val function = factoryClass.child(factoryFunction.callableName)
    return "'$function()' must be a suspend function $reason"
  }

  /**
   * Explains why [accessor] can't return [child] directly. Only the extension's suspend factory
   * runs its suspend init callbacks.
   */
  public fun suspendInitNeedsFactoryCall(
    accessor: String,
    child: String,
    callback: String,
  ): String =
    "'$accessor' can't create '$child' directly because @OnGraphInit callback '$callback' suspends. Create '$child' through its graph factory."

  /** Names the close contract a closeable parent needs for an extension it creates. */
  public fun parentCannotCloseExtension(
    parent: String,
    child: String,
    suspendClose: Boolean,
  ): String {
    val required = requiredCloseContract(suspendClose)
    return "'$parent' creates closeable extension '$child', so it must implement $required."
  }

  /**
   * Explains why [parent] must be closeable. Closing [ancestor] closes the graphs below it, but it
   * can't reach [child] through a graph that doesn't track its extensions.
   */
  public fun parentBreaksCloseChain(
    parent: String,
    child: String,
    ancestor: String,
    suspendClose: Boolean,
  ): String {
    val required = requiredCloseContract(suspendClose)
    return "'$parent' creates closeable extension '$child' but isn't closeable, so closing '$ancestor' can't close '$child'. '$parent' must implement $required."
  }

  /** The interfaces that let a graph close an extension whose `close()` may suspend. */
  private fun requiredCloseContract(suspendClose: Boolean): String =
    if (suspendClose) {
      "SuspendCloseableGraph"
    } else {
      "CloseableGraph or SuspendCloseableGraph"
    }

  /** Formats a callback's lifecycle annotation as it appears in source, like `@OnGraphInit`. */
  public fun annotationLabel(phase: GraphLifecyclePhase): String =
    "@${phase.annotation.shortClassName}"

  /**
   * Shows a callback parameter in its callback's signature, like `AppGraph.stop(…, database)`. The
   * other parameters show as `…`. The compiler and the IDE start traces of callback parameters with
   * this.
   */
  public fun parameterSignature(
    owner: String,
    callback: String,
    parameter: String,
    index: Int,
    parameterCount: Int,
  ): String {
    val before =
      if (index > 0) {
        "…, "
      } else {
        ""
      }
    val after =
      if (index < parameterCount - 1) {
        ", …"
      } else {
        ""
      }
    return "$owner.$callback($before$parameter$after)"
  }
}
