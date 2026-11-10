// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.graph

import dev.zacsweers.metro.compiler.graph.GraphLifecyclePhase
import dev.zacsweers.metro.compiler.graph.LifecycleDiagnosticMessages
import dev.zacsweers.metro.compiler.ir.IrContextualTypeKey
import dev.zacsweers.metro.compiler.ir.IrMetroContext
import dev.zacsweers.metro.compiler.ir.extensionReceiverParameterCompat
import dev.zacsweers.metro.compiler.ir.hasMetroDefault
import dev.zacsweers.metro.compiler.ir.overriddenSymbolsSequence
import dev.zacsweers.metro.compiler.ir.regularParameters
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.util.parentAsClass

/**
 * An `@OnGraphInit` or `@OnGraphClose` function on an assembled graph.
 *
 * A callback is a graph entry point even when it has no parameters.
 */
internal data class LifecycleCallback(
  /** The lifecycle annotation on the original declaration determines when this callback runs. */
  val phases: Set<GraphLifecyclePhase>,
  /** The graph's own member, often a fake override. Generated code calls this one. */
  val graphFunction: IrSimpleFunction,
  /** The annotated declaration that [graphFunction] resolves to. Diagnostics and reports use it. */
  val original: IrSimpleFunction,
  val parameters: List<LifecycleParameter>,
)

/** The callback's lifecycle annotation as it appears in source. */
internal val LifecycleCallback.annotationLabel: String
  get() = LifecycleDiagnosticMessages.annotationLabel(phases)

/** A callback parameter. [key] uses the parameter's type as seen from the assembled graph. */
internal data class LifecycleParameter(val source: IrValueParameter, val key: IrContextualTypeKey)

/** True for a `close()` that takes no arguments, which Metro implements on closeable graphs. */
internal val IrSimpleFunction.hasCloseSignature: Boolean
  get() {
    if (name.asString() != "close") {
      return false
    }
    return regularParameters.isEmpty() && extensionReceiverParameterCompat == null
  }

/**
 * Shows the parameter at [index] in this callback's signature, like `AppGraph.stop(…, database)`.
 */
internal fun IrSimpleFunction.parameterSignature(index: Int): String =
  LifecycleDiagnosticMessages.parameterSignature(
    owner = parentAsClass.kotlinFqName.asString(),
    callback = name.asString(),
    parameter = regularParameters[index].name.asString(),
    index = index,
    parameterCount = regularParameters.size,
  )

/**
 * True when the parameter at [index] has a default that Metro uses. Overrides can't declare their
 * own defaults, so this also checks the functions that this one overrides.
 */
context(context: IrMetroContext)
internal fun IrSimpleFunction.parameterHasMetroDefault(index: Int): Boolean {
  if (regularParameters[index].hasMetroDefault()) {
    return true
  }
  return overriddenSymbolsSequence().any {
    it.owner.regularParameters.getOrNull(index)?.hasMetroDefault() == true
  }
}
