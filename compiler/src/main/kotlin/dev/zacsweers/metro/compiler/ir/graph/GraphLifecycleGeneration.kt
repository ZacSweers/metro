// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.graph

import dev.zacsweers.metro.compiler.MetroClassIds
import dev.zacsweers.metro.compiler.NameAllocator
import dev.zacsweers.metro.compiler.Origins
import dev.zacsweers.metro.compiler.fir.Keys
import dev.zacsweers.metro.compiler.ir.IrMetroContext
import dev.zacsweers.metro.compiler.ir.createIrBuilder
import dev.zacsweers.metro.compiler.ir.finalizeFakeOverride
import dev.zacsweers.metro.compiler.ir.graph.expressions.GraphExpressionGenerator
import dev.zacsweers.metro.compiler.ir.implements
import dev.zacsweers.metro.compiler.ir.irGetProperty
import dev.zacsweers.metro.compiler.ir.irInvoke
import dev.zacsweers.metro.compiler.ir.irLambda
import dev.zacsweers.metro.compiler.ir.metroGraphOrFail
import dev.zacsweers.metro.compiler.ir.regularParameters
import dev.zacsweers.metro.compiler.ir.requireSimpleFunction
import dev.zacsweers.metro.compiler.ir.setDispatchReceiver
import dev.zacsweers.metro.compiler.ir.thisReceiverOrFail
import dev.zacsweers.metro.compiler.ir.typeAsProviderArgument
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.builders.declarations.addField
import org.jetbrains.kotlin.ir.builders.declarations.addFunction
import org.jetbrains.kotlin.ir.builders.irBlock
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.builders.irBoolean
import org.jetbrains.kotlin.ir.builders.irCallConstructor
import org.jetbrains.kotlin.ir.builders.irEquals
import org.jetbrains.kotlin.ir.builders.irExprBody
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irGetField
import org.jetbrains.kotlin.ir.builders.irIfThen
import org.jetbrains.kotlin.ir.builders.irNull
import org.jetbrains.kotlin.ir.builders.irReturn
import org.jetbrains.kotlin.ir.builders.irReturnUnit
import org.jetbrains.kotlin.ir.builders.irString
import org.jetbrains.kotlin.ir.builders.irTemporary
import org.jetbrains.kotlin.ir.builders.parent
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclarationWithName
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.impl.IrTryImpl
import org.jetbrains.kotlin.ir.irAttribute
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.types.defaultType
import org.jetbrains.kotlin.ir.util.constructors
import org.jetbrains.kotlin.ir.util.copyTo
import org.jetbrains.kotlin.ir.util.functions
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.util.patchDeclarationParents

/** Holds generated declarations that creation paths and lifecycle bodies share. */
internal class GraphLifecycleGeneration(
  val graph: IrClass,
  val isSuspendCloseable: Boolean,
  val closed: IrField,
  val initialize: IrSimpleFunction,
  val close: IrSimpleFunction,
) {
  var children: IrField? = null
    private set

  context(context: IrMetroContext)
  fun children(): IrField {
    children?.let {
      return it
    }
    val composite =
      if (isSuspendCloseable) {
        checkNotNull(context.metroSymbols.compositeSuspendCloseable)
      } else {
        context.metroSymbols.compositeCloseable
      }
    return graph
      .addField(
        graph.lifecycleFieldName("children"),
        composite.defaultType,
        DescriptorVisibilities.PRIVATE,
      )
      .apply {
        isFinal = true
        initializer =
          context.createIrBuilder(symbol).run {
            irExprBody(irCallConstructor(composite.constructors.single(), emptyList()))
          }
        children = this
      }
  }
}

internal var IrClass.graphLifecycleGeneration: GraphLifecycleGeneration? by
  irAttribute(copyByDefault = false)

private fun IrClass.lifecycleFieldName(suggestion: String): String {
  val allocator = NameAllocator(mode = NameAllocator.Mode.COUNT)
  for (declaration in declarations.filterIsInstance<IrDeclarationWithName>()) {
    allocator.reserveName(declaration.name.asString())
  }
  return allocator.newName(suggestion)
}

context(context: IrMetroContext)
internal fun IrClass.prepareGraphLifecycle(
  suspendInit: Boolean = false,
): GraphLifecycleGeneration? {
  graphLifecycleGeneration?.let {
    return it
  }
  val suspendClose = implements(MetroClassIds.suspendCloseableGraph)
  val supportsLifecycle = suspendClose || implements(MetroClassIds.closeableGraph)
  if (!supportsLifecycle) {
    return null
  }
  val atomicBoolean = context.metroSymbols.atomicBoolean
  val closed =
    addField(
        lifecycleFieldName("closed"),
        atomicBoolean.defaultType,
        DescriptorVisibilities.PRIVATE,
      )
      .apply {
        isFinal = true
        initializer =
          context.createIrBuilder(symbol).run {
            irExprBody(
              irCallConstructor(atomicBoolean.constructors.single(), emptyList()).apply {
                arguments[0] = irBoolean(false)
              },
            )
          }
      }
  val initialize =
    functions.singleOrNull { it.origin == Origins.GraphLifecycle }
      ?: addFunction {
        name = Keys.GraphLifecycle.initializeName
        returnType = context.irBuiltIns.unitType
        visibility = DescriptorVisibilities.INTERNAL
        isSuspend = suspendInit
        origin = Origins.GraphLifecycle
      }
        .apply {
          setDispatchReceiver(this@prepareGraphLifecycle.thisReceiverOrFail.copyTo(this))
          if (context.options.generateClassesInIr) {
            context.metadataDeclarationRegistrarCompat.registerFunctionAsMetadataVisible(this)
          }
        }
  return GraphLifecycleGeneration(
      this,
      suspendClose,
      closed,
      initialize,
      functions.single { it.name.asString() == "close" && it.regularParameters.isEmpty() },
    )
    .also { graphLifecycleGeneration = it }
}

/** Completes initialization before returning or registering a newly constructed graph. */
context(context: IrMetroContext)
internal fun IrBuilderWithScope.initializeGraph(
  constructorCall: IrConstructorCall,
  suspendInit: Boolean = false,
): IrExpression {
  val graph = constructorCall.symbol.owner.parent as IrClass
  val lifecycle = graph.prepareGraphLifecycle(suspendInit)
  val parentParameter =
    constructorCall.symbol.owner.regularParameters.firstOrNull()?.takeIf {
      it.origin == Origins.ParentGraphParam
    }
  val parentLifecycle = parentParameter?.type?.classOrNull?.owner?.prepareGraphLifecycle()
  if (lifecycle == null && parentLifecycle == null) {
    return constructorCall
  }
  return irBlock(resultType = constructorCall.type) {
    val parent = parentLifecycle?.let {
      irTemporary(checkNotNull(constructorCall.arguments[0]), "parent").also { variable ->
        constructorCall.arguments[0] = irGet(variable)
        val isClosed =
          irInvoke(
            callee = context.metroSymbols.atomicBoolean.owner.requireSimpleFunction("load"),
            dispatchReceiver = irGetField(irGet(variable), it.closed),
          )
        +irIfThen(
          context.irBuiltIns.unitType,
          isClosed,
          irInvoke(
            callee = context.metroSymbols.stdlibErrorFunction,
            args = listOf(irString("Cannot create a graph extension after its parent is closed.")),
          ),
        )
      }
    }
    val instance = irTemporary(constructorCall, "graph")
    if (lifecycle != null) {
      +irInvoke(callee = lifecycle.initialize.symbol, dispatchReceiver = irGet(instance))
      val runtimeAvailable =
        parentLifecycle?.isSuspendCloseable != true ||
          context.metroSymbols.compositeSuspendCloseable != null
      val ownsChild = parentLifecycle != null && parent != null
      if (ownsChild && runtimeAvailable) {
        checkNotNull(parentLifecycle)
        checkNotNull(parent)
        val children = parentLifecycle.children()
        val callback =
          irLambda(
            parent = this@initializeGraph.parent,
            receiverParameter = null,
            valueParameters = emptyList(),
            returnType = context.irBuiltIns.unitType,
            suspend = parentLifecycle.isSuspendCloseable,
          ) {
            +irInvoke(callee = lifecycle.close.symbol, dispatchReceiver = irGet(instance))
          }
        +irInvoke(
          callee = children.type.classOrNull!!.owner.requireSimpleFunction("add"),
          dispatchReceiver = irGetField(irGet(parent), children),
          args = listOf(irGet(instance), callback),
        )
      }
    }
    +irGet(instance)
  }
}

context(context: IrMetroContext)
internal fun implementGraphLifecycle(
  node: GraphNode.Local,
  bindingGraph: IrBindingGraph,
  expressionFactory: GraphExpressionGenerator.Factory,
) {
  val graph = node.metroGraphOrFail
  val lifecycle = graph.graphLifecycleGeneration ?: return

  fun IrBuilderWithScope.callbacks(receiver: IrValueParameter, onInit: Boolean): IrExpression {
    return irBlock(resultType = context.irBuiltIns.unitType) {
      for (callback in node.lifecycleCallbacks) {
        val runsInPhase =
          if (onInit) {
            callback.onInit
          } else {
            callback.onClose
          }
        if (!runsInPhase) {
          continue
        }
        val function = callback.metroFunction.ir
        val arguments =
          callback.parameters.map { parameter ->
            val binding = bindingGraph.findBinding(parameter.typeKey)
            if (binding == null) {
              null
            } else {
              typeAsProviderArgument(
                parameter.contextualTypeKey,
                expressionFactory
                  .create(receiver)
                  .generateBindingCode(binding, parameter.contextualTypeKey),
                isAssisted = false,
                isGraphInstance = false,
              )
            }
          }
        val call =
          irInvoke(callee = function.symbol, dispatchReceiver = irGet(receiver), args = arguments)
        +traceLifecycle(
          graph,
          receiver,
          callback.declaration.kotlinFqName.asString(),
          if (onInit) {
            "OnGraphInit"
          } else {
            "OnGraphClose"
          },
          function.isSuspend,
          call,
        )
      }
    }
  }

  lifecycle.initialize.apply {
    val receiver = checkNotNull(dispatchReceiverParameter)
    body =
      context.createIrBuilder(symbol).irBlockBody {
        +traceLifecycle(
          graph,
          receiver,
          "initialize",
          "Graph Initialization",
          isSuspend,
          callbacks(receiver, onInit = true),
        )
      }
  }

  lifecycle.close.apply {
    if (isFakeOverride) {
      finalizeFakeOverride(graph.thisReceiverOrFail)
    }
    val receiver = checkNotNull(dispatchReceiverParameter)
    body =
      context.createIrBuilder(symbol).irBlockBody {
        val wonClose =
          irInvoke(
            callee =
              context.metroSymbols.atomicBoolean.owner.requireSimpleFunction("compareAndSet"),
            dispatchReceiver = irGetField(irGet(receiver), lifecycle.closed),
            args = listOf(irBoolean(false), irBoolean(true)),
          )
        +irIfThen(context.irBuiltIns.unitType, irEquals(wonClose, irBoolean(false)), irReturnUnit())

        fun IrBuilderWithScope.cleanup(): IrExpression {
          val cleanup =
            traceLifecycle(
              graph,
              receiver,
              "close",
              "Graph Close",
              lifecycle.isSuspendCloseable,
              irBlock(resultType = context.irBuiltIns.unitType) {
                lifecycle.children?.let { children ->
                  +irInvoke(
                    callee = children.type.classOrNull!!.owner.requireSimpleFunction("close"),
                    dispatchReceiver = irGetField(irGet(receiver), children),
                  )
                }
                +callbacks(receiver, onInit = false)
              },
            )
          val parentProperty = graph.parentGraphInstanceProperty
          val parentLifecycle =
            parentProperty?.backingField?.type?.classOrNull?.owner?.graphLifecycleGeneration
          if (parentLifecycle == null || parentProperty == null) {
            return cleanup
          }
          val children = parentLifecycle.children()
          val remove =
            irInvoke(
              callee = children.type.classOrNull!!.owner.requireSimpleFunction("remove"),
              dispatchReceiver =
                irGetField(irGetProperty(irGet(receiver), parentProperty), children),
              args = listOf(irGet(receiver)),
            )
          return IrTryImpl(startOffset, endOffset, context.irBuiltIns.unitType).apply {
            tryResult = cleanup
            finallyExpression = remove
          }
        }

        if (lifecycle.isSuspendCloseable) {
          val cleanup =
            irLambda(
              parent = parent,
              receiverParameter = null,
              valueParameters = emptyList(),
              returnType = context.irBuiltIns.unitType,
              suspend = true,
            ) {
              +cleanup()
            }
          +irInvoke(
            callee = checkNotNull(context.metroSymbols.runGraphClose),
            args = listOf(cleanup),
          )
        } else {
          +cleanup()
        }
      }
  }
}

context(context: IrMetroContext)
private fun IrBuilderWithScope.traceLifecycle(
  graph: IrClass,
  receiver: IrValueParameter,
  name: String,
  kind: String,
  isSuspend: Boolean,
  expression: IrExpression,
): IrExpression {
  val traceContext = graph.runtimeTraceContextProperty ?: return expression
  val trace =
    if (isSuspend) {
      context.metroSymbols.metroTraceContextTraceSuspend
    } else {
      context.metroSymbols.metroTraceContextTrace
    }
  if (trace == null) {
    return expression
  }
  val block =
    irLambda(parent, null, emptyList(), context.irBuiltIns.unitType, suspend = isSuspend) {
      +irReturn(expression)
    }
  block.function.body?.patchDeclarationParents(block.function)
  return irInvoke(
    callee = trace,
    dispatchReceiver = irGetProperty(irGet(receiver), traceContext),
    typeHint = context.irBuiltIns.unitType,
    typeArgs = listOf(context.irBuiltIns.unitType),
    args = listOf(irNull(), irString(name), irNull(), irString(kind), block),
  )
}
