// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.graph

import dev.zacsweers.metro.compiler.graph.GraphLifecyclePhase
import dev.zacsweers.metro.compiler.ir.IrContextualTypeKey
import dev.zacsweers.metro.compiler.ir.MetroSimpleFunction
import dev.zacsweers.metro.compiler.ir.overriddenSymbolsSequence
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.util.parentAsClass

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

/**
 * A parameter of an `@OnGraphInit` or `@OnGraphClose` callback. Metro resolves it like an accessor.
 */
internal data class LifecycleCallbackParameter(
  override val contextKey: IrContextualTypeKey,
  /** The callback's fake override on the graph implementation. */
  override val metroFunction: MetroSimpleFunction,
  val parameter: IrValueParameter,
  val phase: GraphLifecyclePhase,
) : GraphRoot

/** An `@OnGraphInit` or `@OnGraphClose` callback that the generated graph calls. */
internal data class LifecycleCallback(
  val phase: GraphLifecyclePhase,
  /** The callback's fake override on the graph implementation. */
  val function: MetroSimpleFunction,
  val parameters: List<LifecycleCallbackParameter>,
)

/**
 * The most specific source declaration of a lifecycle callback, which is the implementation that
 * runs.
 */
internal val MetroSimpleFunction.lifecycleCallbackDeclaration: IrSimpleFunction
  get() {
    if (!ir.isFakeOverride) return ir
    return ir.overriddenSymbolsSequence().firstOrNull { !it.owner.isFakeOverride }?.owner ?: ir
  }

/** The callback's declaring type and name, like `AppGraph.shutdown`. */
internal val MetroSimpleFunction.lifecycleCallbackName: String
  get() {
    val declaration = lifecycleCallbackDeclaration
    return "${declaration.parentAsClass.name.asString()}.${declaration.name.asString()}"
  }

/** The source parameter for this root, used to anchor diagnostics. */
internal val LifecycleCallbackParameter.sourceParameter: IrValueParameter
  get() {
    val declaration = metroFunction.lifecycleCallbackDeclaration
    return declaration.parameters.getOrNull(parameter.indexInParameters) ?: parameter
  }
