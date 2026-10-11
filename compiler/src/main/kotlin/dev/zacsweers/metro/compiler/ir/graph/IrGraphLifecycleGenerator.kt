// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.graph

import dev.zacsweers.metro.compiler.asName
import dev.zacsweers.metro.compiler.graph.GraphCloseKind
import dev.zacsweers.metro.compiler.graph.GraphLifecyclePhase
import dev.zacsweers.metro.compiler.ir.IrMetroContext
import dev.zacsweers.metro.compiler.ir.buildBlockBody
import dev.zacsweers.metro.compiler.ir.createIrBuilder
import dev.zacsweers.metro.compiler.ir.finalizeFakeOverride
import dev.zacsweers.metro.compiler.ir.graph.expressions.BindingExpressionGenerator
import dev.zacsweers.metro.compiler.ir.graph.expressions.GraphExpressionGenerator
import dev.zacsweers.metro.compiler.ir.irExprBodySafe
import dev.zacsweers.metro.compiler.ir.irGetProperty
import dev.zacsweers.metro.compiler.ir.irInvoke
import dev.zacsweers.metro.compiler.ir.irLambda
import dev.zacsweers.metro.compiler.ir.rawTypeOrNull
import dev.zacsweers.metro.compiler.ir.setDispatchReceiver
import dev.zacsweers.metro.compiler.ir.thisReceiverOrFail
import dev.zacsweers.metro.compiler.ir.typeAsProviderArgument
import dev.zacsweers.metro.compiler.reportCompilerBug
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.builders.IrStatementsBuilder
import org.jetbrains.kotlin.ir.builders.declarations.addFunction
import org.jetbrains.kotlin.ir.builders.declarations.addProperty
import org.jetbrains.kotlin.ir.builders.irBlock
import org.jetbrains.kotlin.ir.builders.irCallConstructor
import org.jetbrains.kotlin.ir.builders.irEqeqeq
import org.jetbrains.kotlin.ir.builders.irExprBody
import org.jetbrains.kotlin.ir.builders.irFalse
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irIfThen
import org.jetbrains.kotlin.ir.builders.irNull
import org.jetbrains.kotlin.ir.builders.irReturnUnit
import org.jetbrains.kotlin.ir.builders.irString
import org.jetbrains.kotlin.ir.builders.irTemporary
import org.jetbrains.kotlin.ir.builders.irTry
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrDeclarationParent
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.expressions.IrBlockBody
import org.jetbrains.kotlin.ir.expressions.IrDelegatingConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrReturn
import org.jetbrains.kotlin.ir.irAttribute
import org.jetbrains.kotlin.ir.util.copyTo
import org.jetbrains.kotlin.ir.util.functions

/** The `GraphLifecycle` property of a closeable graph, which its graph extensions use. */
internal var IrClass.graphLifecycleProperty: IrProperty? by irAttribute(copyByDefault = false)

/** The function that runs this graph's init callbacks. */
private var IrClass.graphInitFunction: IrSimpleFunction? by irAttribute(copyByDefault = false)

/**
 * Returns the function that runs this graph's init callbacks, adding an empty one if it doesn't
 * exist yet. Parents can reference it before this graph generates its body.
 *
 * A suspending one isn't private because whatever creates the graph calls it after the constructor
 * returns. A synchronous one runs at the end of the constructor.
 */
context(context: IrMetroContext)
internal fun IrClass.getOrCreateGraphInitFunction(isSuspend: Boolean): IrSimpleFunction {
  graphInitFunction?.let {
    return it
  }
  val visibility =
    if (isSuspend) {
      DescriptorVisibilities.INTERNAL
    } else {
      DescriptorVisibilities.PRIVATE
    }
  return addFunction(
      name = "metroInit",
      returnType = context.irBuiltIns.unitType,
      visibility = visibility,
      isSuspend = isSuspend,
    )
    .apply { setDispatchReceiver(thisReceiverOrFail.copyTo(this)) }
    .also { graphInitFunction = it }
}

/** The suspending init function that whatever creates this graph must call, if it has one. */
internal val IrClass.suspendGraphInitFunction: IrSimpleFunction?
  get() = graphInitFunction?.takeIf { it.isSuspend }

/** Calls [initFunction] on the graph that [creation] returns, then returns that graph. */
internal fun IrBuilderWithScope.irCreateAndInitGraph(
  creation: IrExpression,
  initFunction: IrSimpleFunction?,
): IrExpression {
  if (initFunction == null) return creation
  return irBlock(resultType = creation.type) {
    val graph = irTemporary(creation, nameHint = "graph")
    +irInvoke(dispatchReceiver = irGet(graph), callee = initFunction.symbol)
    +irGet(graph)
  }
}

/** Rewrites a graph creator whose body returns a new graph so it also runs [initFunction]. */
context(context: IrMetroContext)
internal fun IrSimpleFunction.callInitOnCreatedGraph(initFunction: IrSimpleFunction) {
  val statements = (body as? IrBlockBody)?.statements
  val creation =
    (statements?.singleOrNull() as? IrReturn)?.value
      ?: reportCompilerBug("Expected '$name' to only return a new graph")
  body =
    context.createIrBuilder(symbol).run {
      irExprBodySafe(irCreateAndInitGraph(creation, initFunction))
    }
}

/**
 * Generates a graph's lifecycle members: the `GraphLifecycle` property that tracks whether it's
 * closed, the function that runs its init callbacks, and `close()`.
 */
internal class IrGraphLifecycleGenerator(
  context: IrMetroContext,
  private val graphClass: IrClass,
  private val node: GraphNode.Local,
  private val bindingGraph: IrBindingGraph,
  private val parentGraphParam: IrValueParameter?,
  private val parentGraphInstanceProperty: IrProperty?,
  private val traceContextProperty: IrProperty?,
) : IrMetroContext by context {
  private val lifecycle = node.lifecycle
  private val graphName = node.lifecycleGraphName
  private val initCallbacks = lifecycle.callbacks.filter { it.phase == GraphLifecyclePhase.INIT }
  private val closeCallbacks = lifecycle.callbacks.filter { it.phase == GraphLifecyclePhase.CLOSE }
  private val initSuspends = bindingGraph.lifecycleValidation?.initSuspendReason != null

  /** The parent's lifecycle property, when this graph extends a closeable graph. */
  private val parentLifecycleProperty: IrProperty? =
    parentGraphInstanceProperty?.backingField?.type?.rawTypeOrNull()?.graphLifecycleProperty

  /** The parent closes this graph when both are closeable. */
  private val tracksWithParent = parentLifecycleProperty != null && lifecycle.isCloseable

  private var lifecycleProperty: IrProperty? = null

  private val initFunction: IrSimpleFunction? by lazy {
    val needsInitFunction = initCallbacks.isNotEmpty() || tracksWithParent
    if (needsInitFunction) {
      graphClass.getOrCreateGraphInitFunction(isSuspend = initSuspends)
    } else {
      null
    }
  }

  /** Adds this graph's `GraphLifecycle` property when it's closeable. */
  fun addLifecycleProperty() {
    if (!lifecycle.isCloseable) return
    val type = metroSymbols.graphLifecycleType
    lifecycleProperty =
      graphClass
        .addProperty {
          name = "metroLifecycle".asName()
          visibility = DescriptorVisibilities.PRIVATE
        }
        .apply {
          addBackingFieldCompat {
            this.type = type
            visibility = DescriptorVisibilities.PRIVATE
          }
            .apply {
              isFinal = true
              initializer =
                createIrBuilder(symbol).run {
                  irExprBody(
                    irCallConstructor(metroSymbols.graphLifecycleConstructor, emptyList()).apply {
                      arguments[0] = irString(graphName)
                    },
                  )
                }
            }
        }
        .also { graphClass.graphLifecycleProperty = it }
  }

  /** Statements that run at the end of the constructor, after ordinary initialization. */
  fun constructorTailStatements(): List<InitStatement> {
    val function = initFunction ?: return emptyList()
    if (function.isSuspend) return emptyList()
    return listOf { thisReceiver ->
      irInvoke(dispatchReceiver = irGet(thisReceiver), callee = function.symbol)
    }
  }

  /**
   * Checks that the parent graph is still open before anything else in [constructor] runs. This
   * reads the constructor parameter because the parent property isn't initialized yet.
   */
  fun addParentOpenCheck(constructor: IrConstructor) {
    val parentLifecycle = parentLifecycleProperty ?: return
    val parentParam = parentGraphParam ?: return
    val body = constructor.body as? IrBlockBody ?: return
    val statements = body.statements.toList()
    val delegatingCallIndex = statements.indexOfFirst { it is IrDelegatingConstructorCall }
    constructor.buildBlockBody {
      for ((index, statement) in statements.withIndex()) {
        +statement
        if (index == delegatingCallIndex) {
          +irInvoke(
            dispatchReceiver = irGetProperty(irGet(parentParam), parentLifecycle),
            callee = metroSymbols.graphLifecycleCheckOpen,
          )
        }
      }
    }
  }

  /** Implements the init function and `close()`. */
  fun implement(expressionGeneratorFactory: GraphExpressionGenerator.Factory) {
    initFunction?.let { implementInit(it, expressionGeneratorFactory) }
    lifecycle.closeFunction?.let { implementClose(it, expressionGeneratorFactory) }
  }

  private fun implementInit(
    function: IrSimpleFunction,
    expressionGeneratorFactory: GraphExpressionGenerator.Factory,
  ) {
    val receiver = function.dispatchReceiverParameter!!
    function.buildBlockBody {
      if (initCallbacks.isNotEmpty()) {
        +traced(
          name = "$graphName init",
          phase = GraphLifecyclePhase.INIT,
          callable = null,
          isSuspend = function.isSuspend,
          parent = function,
          receiver = receiver,
        ) { lambdaParent ->
          for (callback in initCallbacks) {
            +callbackCall(
              callback,
              receiver,
              function.isSuspend,
              lambdaParent,
              expressionGeneratorFactory,
            )
          }
        }
      }
      if (tracksWithParent) {
        // Track this graph once its init succeeds, before it's returned to the caller.
        +irInvoke(
          dispatchReceiver = parentLifecycle(receiver),
          callee = metroSymbols.graphLifecycleAddChild,
          args = listOf(irGet(receiver)),
        )
      }
    }
    // Synthetic factories for graph extensions and dynamic graphs return the new graph, so they
    // also need to run its suspending init before returning it.
    if (function.isSuspend) {
      syntheticFactoryFunction()?.callInitOnCreatedGraph(function)
    }
  }

  private fun syntheticFactoryFunction(): IrSimpleFunction? {
    val factoryImpl =
      graphClass.generatedGraphExtensionData?.factoryImpl
        ?: graphClass.generatedDynamicGraphData?.factoryImpl
        ?: return null
    return factoryImpl.functions.firstOrNull {
      !it.isFakeOverride && it.overriddenSymbols.isNotEmpty()
    }
  }

  private fun implementClose(
    function: IrSimpleFunction,
    expressionGeneratorFactory: GraphExpressionGenerator.Factory,
  ) {
    function.finalizeFakeOverride(graphClass.thisReceiverOrFail)
    val receiver = function.dispatchReceiverParameter!!
    val property = lifecycleProperty ?: reportCompilerBug("No lifecycle property for $graphName")
    val isSuspend = lifecycle.closeKind == GraphCloseKind.SUSPEND
    function.buildBlockBody {
      // Only the first call closes the graph. Later calls return right away.
      val markClosed =
        irInvoke(
          dispatchReceiver = irGetProperty(irGet(receiver), property),
          callee = metroSymbols.graphLifecycleMarkClosed,
        )
      +irIfThen(
        type = irBuiltIns.unitType,
        condition = irEqeqeq(markClosed, irFalse()),
        thenPart = irReturnUnit(),
      )

      val cleanup =
        traced(
          name = "$graphName close",
          phase = GraphLifecyclePhase.CLOSE,
          callable = null,
          isSuspend = isSuspend,
          parent = function,
          receiver = receiver,
        ) { lambdaParent ->
          if (isSuspend) {
            +closeSuspending(receiver, property, lambdaParent, expressionGeneratorFactory)
          } else {
            +irInvoke(
              dispatchReceiver = irGetProperty(irGet(receiver), property),
              callee = metroSymbols.graphLifecycleCloseChildren,
            )
            for (callback in closeCallbacks) {
              +callbackCall(callback, receiver, false, lambdaParent, expressionGeneratorFactory)
            }
          }
        }

      if (tracksWithParent) {
        // Stop tracking this graph in its parent once this attempt finishes, even if it fails.
        val untrack =
          irInvoke(
            dispatchReceiver = parentLifecycle(receiver),
            callee = metroSymbols.graphLifecycleRemoveChild,
            args = listOf(irGet(receiver)),
          )
        +irTry(irBuiltIns.unitType, cleanup, emptyList(), untrack)
      } else {
        +cleanup
      }
    }
  }

  /** Closes children and runs close callbacks in `NonCancellable` through runtime-coroutines. */
  private fun IrBuilderWithScope.closeSuspending(
    receiver: IrValueParameter,
    property: IrProperty,
    parent: IrDeclarationParent,
    expressionGeneratorFactory: GraphExpressionGenerator.Factory,
  ): IrExpression {
    val closeSuspending =
      metroSymbols.graphLifecycleCloseSuspending
        ?: reportCompilerBug("runtime-coroutines is required for SuspendCloseableGraph")
    val cleanup =
      irLambda(
        parent = parent,
        receiverParameter = null,
        valueParameters = emptyList(),
        returnType = irBuiltIns.unitType,
        suspend = true,
      ) { lambda ->
        for (callback in closeCallbacks) {
          +callbackCall(callback, receiver, true, lambda, expressionGeneratorFactory)
        }
      }
    return irInvoke(
      extensionReceiver = irGetProperty(irGet(receiver), property),
      callee = closeSuspending,
      args = listOf(cleanup),
    )
  }

  /** Calls [callback] on this graph with its injected arguments. */
  private fun IrBuilderWithScope.callbackCall(
    callback: LifecycleCallback,
    receiver: IrValueParameter,
    isSuspend: Boolean,
    parent: IrDeclarationParent,
    expressionGeneratorFactory: GraphExpressionGenerator.Factory,
  ): IrExpression {
    val callableName = callback.function.lifecycleCallbackDeclaration.name.asString()
    return traced(
      name = "${callback.phase.annotationName} ${callback.function.lifecycleCallbackName}",
      phase = callback.phase,
      callable = callableName,
      isSuspend = isSuspend,
      parent = parent,
      receiver = receiver,
    ) {
      val generator = expressionGeneratorFactory.create(receiver)
      val args =
        callback.parameters.map { root ->
          val binding = bindingGraph.requireBinding(root.contextKey)
          typeAsProviderArgument(
            root.contextKey,
            generator.generateBindingCode(binding, contextualTypeKey = root.contextKey),
            isAssisted = false,
            isGraphInstance = false,
            actualIsSuspendProvider =
              BindingExpressionGenerator.AccessType.of(root.contextKey).isSuspendProvider,
          )
        }
      +irInvoke(
        dispatchReceiver = irGet(receiver),
        callee = callback.function.ir.symbol,
        args = args,
      )
    }
  }

  /**
   * Runs [content] in a lifecycle trace span when runtime tracing is on, or as a plain block
   * otherwise. [content] gets the declaration parent to use for any lambdas it creates.
   */
  private fun IrBuilderWithScope.traced(
    name: String,
    phase: GraphLifecyclePhase,
    callable: String?,
    isSuspend: Boolean,
    parent: IrDeclarationParent,
    receiver: IrValueParameter,
    content: IrStatementsBuilder<*>.(IrDeclarationParent) -> Unit,
  ): IrExpression {
    val traceContext = traceContextProperty
    if (traceContext == null) {
      return irBlock(resultType = irBuiltIns.unitType) { content(parent) }
    }
    val traceFunction =
      if (isSuspend) {
        metroSymbols.metroTraceContextTraceLifecycleSuspend!!
      } else {
        metroSymbols.metroTraceContextTraceLifecycle!!
      }
    val block =
      irLambda(
        parent = parent,
        receiverParameter = null,
        valueParameters = emptyList(),
        returnType = irBuiltIns.unitType,
        suspend = isSuspend,
      ) { lambda ->
        content(lambda)
      }
    return irInvoke(
      dispatchReceiver = irGetProperty(irGet(receiver), traceContext),
      callee = traceFunction,
      typeHint = irBuiltIns.unitType,
      typeArgs = listOf(irBuiltIns.unitType),
      args =
        listOf(
          irString(name),
          irString(phase.name.lowercase()),
          callable?.let(::irString) ?: irNull(),
          block,
        ),
    )
  }

  private fun IrBuilderWithScope.parentLifecycle(receiver: IrValueParameter): IrExpression {
    val parentProperty = parentGraphInstanceProperty!!
    val parentLifecycle = parentLifecycleProperty!!
    return irGetProperty(irGetProperty(irGet(receiver), parentProperty), parentLifecycle)
  }
}
