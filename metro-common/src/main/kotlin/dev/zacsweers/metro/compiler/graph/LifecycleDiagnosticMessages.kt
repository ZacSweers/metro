// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.graph

/**
 * Graph lifecycle wording shared by FIR and IR. FIR checks the declarations it can see. IR repeats
 * the graph-level checks only for graphs whose contributions are merged in IR.
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

  public fun overrideMustRepeatAnnotation(annotation: String): String =
    "An override of a lifecycle callback must repeat @$annotation."

  public fun callbackIsCalledByMetro(callback: String): String =
    "Only Metro can call lifecycle callback '$callback'."

  public fun graphMustBeCloseable(graph: String): String =
    "'$graph' has lifecycle callbacks, so it must implement CloseableGraph or SuspendCloseableGraph."

  public fun suspendCloseNeedsSuspendGraph(graph: String, callback: String): String =
    "Suspend @OnGraphClose callback '$callback' requires '$graph' to implement SuspendCloseableGraph."

  public fun suspendInitNeedsSuspendFactory(graph: String, callback: String): String =
    "Suspend @OnGraphInit callback '$callback' requires '$graph' to have a graph factory with a " +
      "suspend creation function."

  public fun syncParentCannotCloseSuspendChild(parent: String, child: String): String =
    "CloseableGraph '$parent' can't close SuspendCloseableGraph extension '$child'. Make '$parent' " +
      "implement SuspendCloseableGraph."
}
