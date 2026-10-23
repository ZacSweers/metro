// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.graph

import dev.zacsweers.metro.compiler.graph.GraphLifecyclePhase
import dev.zacsweers.metro.compiler.ir.IrMetroContext
import dev.zacsweers.metro.compiler.ir.graph.expressions.BindingExpressionGenerator
import dev.zacsweers.metro.compiler.ir.graph.expressions.GraphExpressionGenerator
import dev.zacsweers.metro.compiler.ir.irInvoke
import dev.zacsweers.metro.compiler.ir.trackFunctionCall
import dev.zacsweers.metro.compiler.ir.typeAsProviderArgument
import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.builders.irBlock
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.expressions.IrExpression

/** Resolves each argument immediately before invoking its callback. */
context(context: IrMetroContext)
internal fun IrBuilderWithScope.lifecycleCalls(
  phase: GraphLifecyclePhase,
  node: GraphNode.Local,
  bindings: IrBindingGraph,
  receiver: IrValueParameter,
  factory: GraphExpressionGenerator.Factory,
): IrExpression =
  irBlock(resultType = context.pluginContext.irBuiltIns.unitType) {
    val generator = factory.create(receiver)
    for (callback in node.lifecycleCallbacks) {
      if (callback.phase != phase) {
        continue
      }
      trackFunctionCall(node.sourceGraph, callback.declaration)
      val arguments =
        callback.parameters.map { parameter ->
          val key = parameter.key
          val binding = bindings.requireBinding(key)
          typeAsProviderArgument(
            key,
            generator.generateBindingCode(binding, contextualTypeKey = key),
            isAssisted = false,
            isGraphInstance = false,
            actualIsSuspendProvider =
              BindingExpressionGenerator.AccessType.of(key).isSuspendProvider,
          )
        }
      +irInvoke(
        callee = callback.function.symbol,
        dispatchReceiver = irGet(receiver),
        args = arguments,
      )
    }
  }
