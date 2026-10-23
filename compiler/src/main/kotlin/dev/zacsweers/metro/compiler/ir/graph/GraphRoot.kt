// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.graph

import dev.zacsweers.metro.compiler.graph.GraphLifecyclePhase
import dev.zacsweers.metro.compiler.ir.IrContextualTypeKey
import dev.zacsweers.metro.compiler.ir.MetroSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter

/** A callback is an entry point even when it has no dependency requests. */
internal data class LifecycleCallback(
  val phase: GraphLifecyclePhase,
  val function: IrSimpleFunction,
  val declaration: IrSimpleFunction,
  val parameters: List<LifecycleParameter>,
)

/** Keeps the parameter's source while the key uses its type in the assembled graph. */
internal data class LifecycleParameter(val source: IrValueParameter, val key: IrContextualTypeKey)

internal sealed interface GraphRoot {
  val contextKey: IrContextualTypeKey
  val metroFunction: MetroSimpleFunction
}

internal data class GraphAccessor(
  override val contextKey: IrContextualTypeKey,
  override val metroFunction: MetroSimpleFunction,
  val isAnnotatedOptionalBinding: Boolean,
  val declaringGraph: String? = null,
  val declaringType: String? = null,
  val origin: String? = null,
) : GraphRoot

internal data class InjectorFunction(
  override val contextKey: IrContextualTypeKey,
  override val metroFunction: MetroSimpleFunction,
) : GraphRoot
