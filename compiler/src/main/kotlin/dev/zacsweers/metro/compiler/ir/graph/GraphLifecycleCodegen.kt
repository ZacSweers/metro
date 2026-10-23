// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.graph

import dev.zacsweers.metro.compiler.graph.GraphLifecyclePhase
import dev.zacsweers.metro.compiler.ir.IrMetroContext
import dev.zacsweers.metro.compiler.ir.graph.expressions.BindingExpressionGenerator
import dev.zacsweers.metro.compiler.ir.graph.expressions.GraphExpressionGenerator
import dev.zacsweers.metro.compiler.ir.irInvoke
import dev.zacsweers.metro.compiler.ir.irLambda
import dev.zacsweers.metro.compiler.ir.trackFunctionCall
import dev.zacsweers.metro.compiler.ir.typeAsProviderArgument
import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.builders.irBlock
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irGetField
import org.jetbrains.kotlin.ir.builders.irNull
import org.jetbrains.kotlin.ir.builders.irReturn
import org.jetbrains.kotlin.ir.builders.irString
import org.jetbrains.kotlin.ir.builders.irTemporary
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclarationParent
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.irAttribute

/** Factory bodies can be built before the contributed callbacks are known. */
private var IrClass.pendingLifecycleCreators: MutableList<(IrSimpleFunction) -> Unit>? by
  irAttribute(copyByDefault = false)
private var IrClass.lifecycleInitializer: IrSimpleFunction? by irAttribute(copyByDefault = false)

/** Patches the graph's suspend factory bodies once its initialization code is available. */
internal fun IrClass.finishLifecycleCreators(initializer: IrSimpleFunction?) {
  lifecycleInitializer = initializer
  if (initializer != null) {
    pendingLifecycleCreators?.forEach { it(initializer) }
  }
  pendingLifecycleCreators = null
}

/** A suspending creator returns the constructed instance after its init callbacks finish. */
context(scope: IrBuilderWithScope)
internal fun IrExpression.awaitGraphInit(graph: IrClass, suspendCreator: Boolean): IrExpression {
  if (!suspendCreator) {
    return this
  }
  val construction = this
  val result = scope.irBlock(resultType = type) { +construction }
  fun insertInit(initializer: IrSimpleFunction) {
    result.statements[0] =
      scope.irBlock(resultType = construction.type) {
        val instance = irTemporary(construction, "graph")
        +irInvoke(dispatchReceiver = irGet(instance), callee = initializer.symbol)
        +irGet(instance)
      }
  }
  val initializer = graph.lifecycleInitializer
  if (initializer != null) {
    insertInit(initializer)
  } else {
    val creators =
      graph.pendingLifecycleCreators
        ?: mutableListOf<(IrSimpleFunction) -> Unit>().also { graph.pendingLifecycleCreators = it }
    creators += ::insertInit
  }
  return result
}

/** Resolves each argument immediately before invoking its callback. */
context(context: IrMetroContext)
internal fun IrBuilderWithScope.lifecycleCalls(
  phase: GraphLifecyclePhase,
  node: GraphNode.Local,
  bindings: IrBindingGraph,
  receiver: IrValueParameter,
  factory: GraphExpressionGenerator.Factory,
  before: (IrBuilderWithScope.() -> IrExpression)? = null,
): IrExpression {
  val init = phase == GraphLifecyclePhase.INIT
  val action =
    if (init) {
      "init"
    } else {
      "close"
    }
  val kind =
    if (init) {
      "GraphInit"
    } else {
      "GraphClose"
    }
  val suspend =
    if (init) {
      node.suspendInit != null
    } else {
      node.lifecycleClose?.isSuspend == true
    }
  val graphName = node.sourceGraph.name.asString()
  return traceLifecycle(node, receiver, "$graphName.$action", kind, suspend) {
    irBlock(resultType = context.irBuiltIns.unitType) {
      before?.invoke(this)?.let { +it }
      val generator = factory.create(receiver)
      for (callback in node.lifecycleCallbacks) {
        if (callback.phase != phase) {
          continue
        }
        trackFunctionCall(node.sourceGraph, callback.declaration)
        +traceLifecycle(
          node,
          receiver,
          "$graphName.${callback.declaration.name}",
          "On$kind",
          callback.function.isSuspend,
        ) {
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
          irInvoke(
            callee = callback.function.symbol,
            dispatchReceiver = irGet(receiver),
            args = arguments,
          )
        }
      }
    }
  }
}

/** Uses the graph's trace context so extension spans retain the path of the owning graph. */
context(context: IrMetroContext)
private fun IrBuilderWithScope.traceLifecycle(
  node: GraphNode.Local,
  receiver: IrValueParameter,
  name: String,
  kind: String,
  suspend: Boolean,
  body: IrBuilderWithScope.() -> IrExpression,
): IrExpression {
  val field = node.metroGraphOrFail.runtimeTraceContextProperty?.backingField ?: return body()
  val trace =
    if (suspend) {
      context.metroSymbols.metroTraceContextTraceSuspend
    } else {
      context.metroSymbols.metroTraceContextTrace
    }
  if (trace == null) {
    return body()
  }
  val unit = context.irBuiltIns.unitType
  val block =
    irLambda(
      scope.scopeOwnerSymbol.owner as IrDeclarationParent,
      null,
      emptyList(),
      unit,
      suspend,
    ) {
      +irReturn(body())
    }
  return irInvoke(
    dispatchReceiver = irGetField(irGet(receiver), field),
    callee = trace,
    typeHint = unit,
    typeArgs = listOf(unit),
    args = listOf(irNull(), irString(name), irNull(), irString(kind), block),
  )
}
