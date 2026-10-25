// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.graph

import dev.zacsweers.metro.compiler.MetroClassIds
import dev.zacsweers.metro.compiler.graph.GraphLifecyclePhase
import dev.zacsweers.metro.compiler.ir.IrMetroContext
import dev.zacsweers.metro.compiler.ir.allSupertypesSequence
import dev.zacsweers.metro.compiler.ir.buildBlockBody
import dev.zacsweers.metro.compiler.ir.createIrBuilder
import dev.zacsweers.metro.compiler.ir.finalizeFakeOverride
import dev.zacsweers.metro.compiler.ir.graph.expressions.BindingExpressionGenerator
import dev.zacsweers.metro.compiler.ir.graph.expressions.GraphExpressionGenerator
import dev.zacsweers.metro.compiler.ir.graph.expressions.irRuntimeTraceSpan
import dev.zacsweers.metro.compiler.ir.irGetProperty
import dev.zacsweers.metro.compiler.ir.irInvoke
import dev.zacsweers.metro.compiler.ir.irLambda
import dev.zacsweers.metro.compiler.ir.rawTypeOrNull
import dev.zacsweers.metro.compiler.ir.thisReceiverOrFail
import dev.zacsweers.metro.compiler.ir.trackFunctionCall
import dev.zacsweers.metro.compiler.ir.typeAsProviderArgument
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.ir.builders.IrBlockBodyBuilder
import org.jetbrains.kotlin.ir.builders.IrBlockBuilder
import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.builders.IrStatementsBuilder
import org.jetbrains.kotlin.ir.builders.declarations.addFunction
import org.jetbrains.kotlin.ir.builders.irAs
import org.jetbrains.kotlin.ir.builders.irBlock
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irGetField
import org.jetbrains.kotlin.ir.builders.irIfThen
import org.jetbrains.kotlin.ir.builders.irIfThenElse
import org.jetbrains.kotlin.ir.builders.irIs
import org.jetbrains.kotlin.ir.builders.irTemporary
import org.jetbrains.kotlin.ir.builders.irTry
import org.jetbrains.kotlin.ir.builders.irWhile
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.irAttribute
import org.jetbrains.kotlin.ir.types.defaultType
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.functions
import org.jetbrains.kotlin.ir.util.hasAnnotation

/** The `GraphLifecycleState` field of a closeable graph. Its extensions read it to register. */
internal var IrClass.lifecycleStateField: IrField? by irAttribute(copyByDefault = false)

/** The function that suspend creators call to run a graph's suspend init callbacks. */
private var IrClass.lifecycleInitFunction: IrSimpleFunction? by irAttribute(copyByDefault = false)

/**
 * Declares `metroInit()` the first time something needs it.
 *
 * It starts with an empty body so the class stays valid if graph generation stops early.
 * [IrGraphLifecycleGenerator] fills in the body. A graph can inherit a function with the same name,
 * so this picks a name that none of its supertypes use.
 */
context(context: IrMetroContext)
private fun IrClass.getOrCreateLifecycleInitFunction(): IrSimpleFunction {
  lifecycleInitFunction?.let {
    return it
  }
  val function =
    addFunction(
        unusedFunctionName("metroInit"),
        context.irBuiltIns.unitType,
        visibility = DescriptorVisibilities.INTERNAL,
      )
      .apply {
        isSuspend = true
        body = context.createIrBuilder(symbol).irBlockBody {}
      }
  lifecycleInitFunction = function
  return function
}

/** Returns [base], or [base] with a number appended, that no function of this class uses. */
context(context: IrMetroContext)
private fun IrClass.unusedFunctionName(base: String): String {
  val usedNames = buildSet {
    for (type in allSupertypesSequence(excludeSelf = false)) {
      type.rawTypeOrNull()?.functions?.forEach { add(it.name.asString()) }
    }
  }
  var name = base
  var suffix = 2
  while (name in usedNames) {
    name = "$base$suffix"
    suffix++
  }
  return name
}

/**
 * True when this graph class inherits a suspend `@OnGraphInit` callback.
 *
 * Creators run before the graph's node exists, and synthetic graphs don't have fake overrides yet,
 * so this reads the functions declared on the graph's supertypes.
 */
context(context: IrMetroContext)
private fun IrClass.hasSuspendInitCallback(): Boolean =
  allSupertypesSequence(excludeSelf = false).any { type ->
    val supertype = type.rawTypeOrNull() ?: return@any false
    supertype.functions.any { it.isSuspend && it.hasAnnotation(MetroClassIds.onGraphInit) }
  }

/**
 * Returns the graph built by [construction], after its suspend init callbacks run.
 *
 * Suspend init callbacks can't run in the constructor, so a suspend creator calls `metroInit()` on
 * the new instance before it returns it. Other creators, and graphs without suspend init callbacks,
 * return [construction] unchanged.
 */
context(context: IrMetroContext)
internal fun IrBuilderWithScope.irInitializedGraph(
  graph: IrClass,
  construction: IrExpression,
  isSuspendCreator: Boolean,
): IrExpression {
  if (!isSuspendCreator || !graph.hasSuspendInitCallback()) {
    return construction
  }
  val init = graph.getOrCreateLifecycleInitFunction()
  return irBlock(resultType = construction.type) {
    val instance = irTemporary(construction, "graph")
    +irInvoke(dispatchReceiver = irGet(instance), callee = init.symbol)
    +irGet(instance)
  }
}

/**
 * Generates the lifecycle code of one graph.
 *
 * Init callbacks run at the end of the constructor. If one of them suspends, they all run in
 * `metroInit()` instead, which every suspend creator calls. FIR and [GraphNodes] reject non-suspend
 * ways to create such a graph.
 *
 * A closeable extension registers with its parent's state after init. Closing a graph closes its
 * registered children first and then runs its own close callbacks.
 */
internal class IrGraphLifecycleGenerator(
  context: IrMetroContext,
  private val node: GraphNode.Local,
  private val bindingGraph: IrBindingGraph,
  private val expressionGeneratorFactory: GraphExpressionGenerator.Factory,
  /** This graph's state. Null when the graph isn't closeable. */
  private val stateField: IrField?,
  private val parentGraphParam: IrValueParameter?,
  private val parentGraphField: IrField?,
) : IrMetroContext by context {
  private val graphClass = node.metroGraphOrFail
  private val graphName = node.sourceGraph.name.asString()
  private val parentStateField = parentGraphParam?.type?.rawTypeOrNull()?.lifecycleStateField
  private val hasSuspendInit = node.suspendInit != null
  private val hasInitCallbacks =
    node.lifecycleCallbacks.any { GraphLifecyclePhase.INIT in it.phases }

  /** Rejects construction when the parent is closed, before any of this graph's code runs. */
  fun IrBuilderWithScope.irCheckParentOpen(): IrExpression? {
    val parentState = parentStateField ?: return null
    val parentGraph = checkNotNull(parentGraphParam)
    return irInvoke(
      callee = metroSymbols.graphLifecycleCheckOpen,
      dispatchReceiver = irGetField(irGet(parentGraph), parentState),
    )
  }

  /**
   * Runs non-suspend init callbacks and registers with the parent at the end of the constructor.
   */
  fun IrStatementsBuilder<*>.finishConstructor(receiver: IrValueParameter) {
    if (hasSuspendInit) {
      return
    }
    if (hasInitCallbacks) {
      +irLifecyclePhase(GraphLifecyclePhase.INIT, receiver, isSuspend = false) {
        invokeCallbacks(GraphLifecyclePhase.INIT, receiver)
      }
    }
    registerWithParent(receiver)
  }

  /** Fills in `metroInit()` with the init callbacks when one of them suspends. */
  fun implementInitFunction() {
    if (!hasSuspendInit) {
      return
    }
    val init = graphClass.getOrCreateLifecycleInitFunction()
    val receiver = checkNotNull(init.dispatchReceiverParameter)
    init.buildBlockBody {
      +irLifecyclePhase(GraphLifecyclePhase.INIT, receiver, isSuspend = true) {
        invokeCallbacks(GraphLifecyclePhase.INIT, receiver)
      }
      registerWithParent(receiver)
    }
  }

  /**
   * Implements `close()`. Only the first call runs cleanup. A suspend `close()` runs it through
   * `runGraphCleanup` so cancelling the caller doesn't stop cleanup partway through.
   */
  fun implementClose() {
    val close = node.lifecycleClose ?: return
    val state = checkNotNull(stateField)
    close.finalizeFakeOverride(graphClass.thisReceiverOrFail)
    val receiver = checkNotNull(close.dispatchReceiverParameter)
    close.buildBlockBody {
      val isFirstClose =
        irInvoke(
          callee = metroSymbols.graphLifecycleMarkClosed,
          dispatchReceiver = irGetField(irGet(receiver), state),
        )
      val cleanup =
        if (close.isSuspend) {
          irRunGraphCleanup(close) { +irClosePhase(receiver, state, isSuspend = true) }
        } else {
          irClosePhase(receiver, state, isSuspend = false)
        }
      +irIfThen(irBuiltIns.unitType, isFirstClose, releaseFromParentAfter(receiver, cleanup))
    }
  }

  private fun IrBuilderWithScope.irClosePhase(
    receiver: IrValueParameter,
    state: IrField,
    isSuspend: Boolean,
  ): IrExpression =
    irLifecyclePhase(GraphLifecyclePhase.CLOSE, receiver, isSuspend) {
      closeChildren(receiver, state, isSuspend)
      invokeCallbacks(GraphLifecyclePhase.CLOSE, receiver)
    }

  /** Registers this graph with its parent so that closing the parent closes it too. */
  private fun IrStatementsBuilder<*>.registerWithParent(receiver: IrValueParameter) {
    val state = stateField ?: return
    val parentState = parentState(receiver) ?: return
    +irInvoke(
      callee = metroSymbols.graphLifecycleAddChild,
      dispatchReceiver = parentState,
      args = listOf(irGet(receiver), irGetField(irGet(receiver), state)),
    )
  }

  /** Removes this graph from its parent once [cleanup] finishes, even if cleanup throws. */
  private fun IrBuilderWithScope.releaseFromParentAfter(
    receiver: IrValueParameter,
    cleanup: IrExpression,
  ): IrExpression {
    val parentState = parentState(receiver) ?: return cleanup
    val release =
      irInvoke(
        callee = metroSymbols.graphLifecycleRemoveChild,
        dispatchReceiver = parentState,
        args = listOf(irGet(receiver)),
      )
    return irTry(irBuiltIns.unitType, cleanup, catches = emptyList(), finallyExpression = release)
  }

  private fun IrBuilderWithScope.parentState(receiver: IrValueParameter): IrExpression? {
    val parentState = parentStateField ?: return null
    val parentGraph = irGetField(irGet(receiver), checkNotNull(parentGraphField))
    return irGetField(parentGraph, parentState)
  }

  /**
   * Closes the children registered with this graph in registration order. A suspend graph can own
   * both kinds of closeable graph. A non-suspend graph only owns `CloseableGraph` children.
   */
  private fun IrBlockBuilder.closeChildren(
    receiver: IrValueParameter,
    state: IrField,
    isSuspend: Boolean,
  ) {
    val anyType = irBuiltIns.anyType
    val children =
      irTemporary(
        irInvoke(
          callee = metroSymbols.graphLifecycleTakeChildren,
          dispatchReceiver = irGetField(irGet(receiver), state),
        ),
        "children",
      )
    val iteratorFunction =
      irBuiltIns.listClass.owner.functions.single { it.name.asString() == "iterator" }
    val iterator =
      irTemporary(
        irInvoke(
          callee = iteratorFunction.symbol,
          dispatchReceiver = irGet(children),
          typeHint = irBuiltIns.iteratorClass.typeWith(anyType),
        ),
        "iterator",
      )
    val iteratorClass = irBuiltIns.iteratorClass.owner
    val hasNext = iteratorClass.functions.single { it.name.asString() == "hasNext" }
    val next = iteratorClass.functions.single { it.name.asString() == "next" }
    +irWhile().apply {
      condition = irInvoke(callee = hasNext.symbol, dispatchReceiver = irGet(iterator))
      body =
        irBlock(resultType = irBuiltIns.unitType) {
          val child =
            irTemporary(
              irInvoke(
                callee = next.symbol,
                dispatchReceiver = irGet(iterator),
                typeHint = anyType,
              ),
              "child",
            )
          val closeableType = metroSymbols.closeableGraph.defaultType
          val closeCloseable =
            irInvoke(
              callee = metroSymbols.closeableGraphClose,
              dispatchReceiver = irAs(irGet(child), closeableType),
            )
          if (isSuspend) {
            val suspendType = metroSymbols.suspendCloseableGraph.defaultType
            val closeSuspending =
              irInvoke(
                callee = metroSymbols.suspendCloseableGraphClose,
                dispatchReceiver = irAs(irGet(child), suspendType),
              )
            +irIfThenElse(
              irBuiltIns.unitType,
              irIs(irGet(child), suspendType),
              closeSuspending,
              closeCloseable,
            )
          } else {
            +closeCloseable
          }
        }
    }
  }

  /** Builds `runGraphCleanup { content }`, which finishes even if the caller is cancelled. */
  private fun IrBuilderWithScope.irRunGraphCleanup(
    owner: IrSimpleFunction,
    content: IrBlockBodyBuilder.() -> Unit,
  ): IrExpression {
    val block =
      irLambda(
        parent = owner,
        receiverParameter = null,
        valueParameters = emptyList(),
        returnType = irBuiltIns.unitType,
        suspend = true,
      ) {
        content()
      }
    return irInvoke(callee = metroSymbols.runGraphCleanup, args = listOf(block))
  }

  /** Wraps one lifecycle phase in a runtime trace span when tracing is enabled. */
  private fun IrBuilderWithScope.irLifecyclePhase(
    phase: GraphLifecyclePhase,
    receiver: IrValueParameter,
    isSuspend: Boolean,
    content: IrBlockBuilder.() -> Unit,
  ): IrExpression =
    irTraced(receiver, "$graphName.${phase.name.lowercase()}", phase.traceKind, isSuspend) {
      irBlock(resultType = irBuiltIns.unitType, body = content)
    }

  /**
   * Calls each [phase] callback on [receiver] in declaration order. Each callback's arguments are
   * resolved from the binding graph right before that callback runs.
   */
  private fun IrBlockBuilder.invokeCallbacks(
    phase: GraphLifecyclePhase,
    receiver: IrValueParameter,
  ) {
    val generator = expressionGeneratorFactory.create(receiver)
    for (callback in node.lifecycleCallbacks) {
      if (phase !in callback.phases) {
        continue
      }
      trackFunctionCall(node.sourceGraph, callback.original)
      val name = "$graphName.${callback.original.name}"
      val isSuspend = callback.graphFunction.isSuspend
      +irTraced(receiver, name, phase.callbackTraceKind, isSuspend) {
        val arguments =
          callback.parameters.map { parameter ->
            val key = parameter.key
            val binding = bindingGraph.requireBinding(key)
            // An optional parameter without a binding passes no argument, so its default applies.
            if (binding is IrBinding.Absent) {
              return@map null
            }
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
          callee = callback.graphFunction.symbol,
          dispatchReceiver = irGet(receiver),
          args = arguments,
        )
      }
    }
  }

  /**
   * Wraps [body] in a span on the graph's trace context. Extension spans keep the path of the graph
   * that owns them.
   */
  private fun IrBuilderWithScope.irTraced(
    receiver: IrValueParameter,
    name: String,
    kind: String,
    isSuspend: Boolean,
    body: IrBuilderWithScope.() -> IrExpression,
  ): IrExpression {
    val traceContextProperty = graphClass.runtimeTraceContextProperty ?: return body()
    val traced =
      irRuntimeTraceSpan(
        traceContext = irGetProperty(irGet(receiver), traceContextProperty),
        qualifier = null,
        type = name,
        contextualType = null,
        kind = kind,
        returnType = irBuiltIns.unitType,
        isSuspend = isSuspend,
        body = body,
      )
    return traced ?: body()
  }
}
