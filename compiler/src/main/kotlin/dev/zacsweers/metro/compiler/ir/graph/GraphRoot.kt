// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.graph

import dev.zacsweers.metro.compiler.MetroClassIds
import dev.zacsweers.metro.compiler.ir.IrContextualTypeKey
import dev.zacsweers.metro.compiler.ir.IrMetroContext
import dev.zacsweers.metro.compiler.ir.MetroSimpleFunction
import dev.zacsweers.metro.compiler.ir.hasMetroDefault
import dev.zacsweers.metro.compiler.ir.isAnnotatedWithAny
import dev.zacsweers.metro.compiler.ir.metroFunctionOf
import dev.zacsweers.metro.compiler.ir.overriddenSymbolsSequence
import dev.zacsweers.metro.compiler.ir.parameters.Parameter
import dev.zacsweers.metro.compiler.ir.parameters.parameters
import dev.zacsweers.metro.compiler.ir.regularParameters
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.util.functions

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

/** An implemented lifecycle function and its injected parameters. */
internal data class LifecycleCallback(
  val metroFunction: MetroSimpleFunction,
  val onInit: Boolean,
  val onClose: Boolean,
  val parameters: List<Parameter>,
  val declaration: IrSimpleFunction,
) {
  val roots: List<LifecycleParameter>
    get() = parameters.map {
      LifecycleParameter(it.contextualTypeKey, metroFunction, it, onInit, onClose)
    }
}

/** Keeps a callback parameter's phase and location when several roots request the same key. */
internal data class LifecycleParameter(
  override val contextKey: IrContextualTypeKey,
  override val metroFunction: MetroSimpleFunction,
  val parameter: Parameter,
  val onInit: Boolean,
  val onClose: Boolean,
) : GraphRoot {
  val annotation: String
    get() =
      when {
        onInit && onClose -> "@OnGraphInit and @OnGraphClose"
        onInit -> "@OnGraphInit"
        else -> "@OnGraphClose"
      }
}

context(context: IrMetroContext)
internal fun IrClass.lifecycleCallbacks(): List<LifecycleCallback> =
  functions
    .mapNotNull { function ->
      val source =
        function.overriddenSymbolsSequence().firstOrNull { !it.owner.isFakeOverride }?.owner
          ?: function
      val init = source.isAnnotatedWithAny(setOf(MetroClassIds.onGraphInit))
      val close = source.isAnnotatedWithAny(setOf(MetroClassIds.onGraphClose))
      if (!init && !close) {
        return@mapNotNull null
      }
      val parameters =
        function.parameters().regularParameters.mapIndexed { index, parameter ->
          val sourceParameter = source.regularParameters[index]
          val key = parameter.contextualTypeKey
          parameter.copy(
            ir = sourceParameter,
            contextualTypeKey =
              IrContextualTypeKey(
                key.typeKey,
                key.wrappedType,
                sourceParameter.hasMetroDefault(),
                key.rawType,
              ),
          )
        }
      LifecycleCallback(metroFunctionOf(function), init, close, parameters, source)
    }
    .toList()
