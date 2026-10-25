// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.graph

import dev.zacsweers.metro.compiler.graph.GraphLifecyclePhase
import dev.zacsweers.metro.compiler.ir.IrContextualTypeKey
import dev.zacsweers.metro.compiler.ir.extensionReceiverParameterCompat
import dev.zacsweers.metro.compiler.ir.regularParameters
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter

/**
 * An `@OnGraphInit` or `@OnGraphClose` function on an assembled graph.
 *
 * A callback is a graph entry point even when it has no parameters.
 */
internal data class LifecycleCallback(
  /** The phases that run this callback. A function with both annotations runs in both. */
  val phases: Set<GraphLifecyclePhase>,
  /** The graph's own member, often a fake override. Generated code calls this one. */
  val graphFunction: IrSimpleFunction,
  /** The annotated declaration that [graphFunction] resolves to. Diagnostics and reports use it. */
  val original: IrSimpleFunction,
  val parameters: List<LifecycleParameter>,
)

/** The callback's annotations as they appear in source, like `@OnGraphInit @OnGraphClose`. */
internal val LifecycleCallback.annotationLabel: String
  get() = phases.joinToString(" ") { "@${it.annotation.shortClassName}" }

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
