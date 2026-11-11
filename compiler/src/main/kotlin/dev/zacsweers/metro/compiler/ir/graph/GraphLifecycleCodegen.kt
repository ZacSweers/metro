// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.graph

import dev.zacsweers.metro.compiler.MetroClassIds
import dev.zacsweers.metro.compiler.Origins
import dev.zacsweers.metro.compiler.graph.GraphLifecyclePhase
import dev.zacsweers.metro.compiler.ir.IrMetroContext
import dev.zacsweers.metro.compiler.ir.allSupertypesSequence
import dev.zacsweers.metro.compiler.ir.buildBlockBody
import dev.zacsweers.metro.compiler.ir.createIrBuilder
import dev.zacsweers.metro.compiler.ir.finalizeFakeOverride
import dev.zacsweers.metro.compiler.ir.graph.expressions.BindingExpressionGenerator
import dev.zacsweers.metro.compiler.ir.graph.expressions.GraphExpressionGenerator
import dev.zacsweers.metro.compiler.ir.graph.expressions.GraphTraceContextAccessor
import dev.zacsweers.metro.compiler.ir.graph.expressions.irRuntimeTraceSpan
import dev.zacsweers.metro.compiler.ir.irInvoke
import dev.zacsweers.metro.compiler.ir.irLambda
import dev.zacsweers.metro.compiler.ir.rawTypeOrNull
import dev.zacsweers.metro.compiler.ir.sourceGraphIfMetroGraph
import dev.zacsweers.metro.compiler.ir.thisReceiverOrFail
import dev.zacsweers.metro.compiler.ir.trackFunctionCall
import dev.zacsweers.metro.compiler.ir.typeAsProviderArgument
import dev.zacsweers.metro.compiler.reportCompilerBug
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.ir.builders.IrBlockBodyBuilder
import org.jetbrains.kotlin.ir.builders.IrBlockBuilder
import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.builders.IrStatementsBuilder
import org.jetbrains.kotlin.ir.builders.declarations.addFunction
import org.jetbrains.kotlin.ir.builders.declarations.buildVariable
import org.jetbrains.kotlin.ir.builders.irBlock
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irGetField
import org.jetbrains.kotlin.ir.builders.irIfThen
import org.jetbrains.kotlin.ir.builders.irTemporary
import org.jetbrains.kotlin.ir.builders.irTry
import org.jetbrains.kotlin.ir.builders.parent
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.impl.IrCatchImpl
import org.jetbrains.kotlin.ir.irAttribute
import org.jetbrains.kotlin.ir.util.functions
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.name.Name

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
 * The first suspend `@OnGraphInit` callback that this graph class inherits, or null. When there is
 * one, every init callback runs in `metroInit()` instead of the constructor.
 *
 * This is the only place that decides where init runs, so creators and [IrGraphLifecycleGenerator]
 * always agree. Creators run before the graph's node exists, and synthetic graphs don't have fake
 * overrides yet, so this reads the functions declared on the graph's supertypes.
 */
context(context: IrMetroContext)
internal fun IrClass.suspendInitCallback(): IrSimpleFunction? =
  allSupertypesSequence(excludeSelf = false).firstNotNullOfOrNull { type ->
    type.rawTypeOrNull()?.functions?.firstOrNull {
      it.isSuspend && it.hasAnnotation(MetroClassIds.onGraphInit)
    }
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
  val runsInitInConstructor = graph.suspendInitCallback() == null
  if (!isSuspendCreator || runsInitInConstructor) {
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
 * Fails when [accessor] would return [graph] without running its suspend init callbacks.
 *
 * Validation reports such an accessor to the user first, so reaching one here is a compiler bug.
 */
context(context: IrMetroContext)
internal fun checkAccessorRunsInit(graph: IrClass, accessor: IrSimpleFunction) {
  val suspendInit = graph.suspendInitCallback() ?: return
  if (!accessor.isSuspend) {
    reportCompilerBug(
      "${accessor.kotlinFqName} returns ${graph.kotlinFqName} without running its suspend " +
        "@OnGraphInit callback '${suspendInit.name}'.",
    )
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
 *
 * The generated code for a closeable extension looks roughly like this. A root graph skips the
 * `parent` calls. The `trace` spans only appear when runtime tracing is on.
 *
 * ```kotlin
 * class ChildGraphImpl(parent: AppGraphImpl) : ChildGraph {
 *   private val metroLifecycle = GraphLifecycleState()
 *
 *   init {
 *     parent.metroLifecycle.checkOpen()
 *     // ...binding fields...
 *     // With a suspend init callback, the next two statements move to metroInit().
 *     trace("ChildGraph.init") { trace("ChildGraph.start") { start(database) } }
 *     // Also closes this graph if the parent closed while it was starting.
 *     parent.metroLifecycle.addCloseableChild(this, metroLifecycle)
 *   }
 *
 *   // Only generated with a suspend init callback. Suspend creators call it before returning the
 *   // graph, like `ChildGraphImpl(this).also { it.metroInit() }`.
 *   internal suspend fun metroInit() { ... }
 *
 *   override fun close() {
 *     if (metroLifecycle.markClosed()) {
 *       try {
 *         // A suspend close() runs this block inside runGraphCleanup { }.
 *         trace("ChildGraph.close") {
 *           // A suspend close() calls closeGraphChildren(metroLifecycle), which also awaits the
 *           // suspend close() of SuspendCloseableGraph children.
 *           metroLifecycle.closeChildren()
 *           try {
 *             trace("ChildGraph.stop") { stop(database) }
 *           } catch (failure: Throwable) {
 *             metroLifecycle.recordCloseFailure(failure)
 *           }
 *         }
 *       } finally {
 *         parent.metroLifecycle.removeChild(metroLifecycle)
 *       }
 *       metroLifecycle.throwCloseFailure()
 *     }
 *   }
 * }
 * ```
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
  // Extensions and dynamic graphs are generated classes, so spans use the graph they implement.
  private val graphName = node.sourceGraph.sourceGraphIfMetroGraph.name.asString()
  private val parentStateField = parentGraphParam?.type?.rawTypeOrNull()?.lifecycleStateField
  private val hasSuspendInit = graphClass.suspendInitCallback() != null
  private val hasInitCallbacks =
    node.lifecycleCallbacks.any { it.phase == GraphLifecyclePhase.INIT }

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
    registerWithParent(receiver, isSuspend = false)
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
      registerWithParent(receiver, isSuspend = true)
    }
  }

  /**
   * Implements `close()`. Only the first call runs cleanup. A suspend `close()` runs it through
   * `runGraphCleanup` so cancelling the caller doesn't stop cleanup partway through.
   *
   * A child or callback that throws doesn't stop cleanup either. The graph's state records each
   * failure and `close()` throws the first one after every step has run.
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
      // The recorded failure is thrown from close() itself. Throwing it through runGraphCleanup
      // would let kotlinx-coroutines' stack trace recovery replace it with a copy that has no
      // suppressed exceptions.
      val throwFailure =
        irInvoke(
          callee = metroSymbols.graphLifecycleThrowCloseFailure,
          dispatchReceiver = irGetField(irGet(receiver), state),
        )
      val firstClose =
        irBlock(resultType = irBuiltIns.unitType) {
          +releaseFromParentAfter(receiver, cleanup)
          +throwFailure
        }
      +irIfThen(irBuiltIns.unitType, isFirstClose, firstClose)
    }
  }

  private fun IrBuilderWithScope.irClosePhase(
    receiver: IrValueParameter,
    state: IrField,
    isSuspend: Boolean,
  ): IrExpression =
    irLifecyclePhase(GraphLifecyclePhase.CLOSE, receiver, isSuspend) {
      closeChildren(receiver, state, isSuspend)
      // A callback that throws is recorded so the callbacks after it still run.
      invokeCallbacks(GraphLifecyclePhase.CLOSE, receiver) { callback ->
        irRecordingCloseFailure(receiver, state, callback)
      }
    }

  /** Runs [step] and records what it throws with this graph's state instead of rethrowing it. */
  private fun IrBuilderWithScope.irRecordingCloseFailure(
    receiver: IrValueParameter,
    state: IrField,
    step: IrExpression,
  ): IrExpression {
    val failure =
      buildVariable(
        parent = parent,
        startOffset = startOffset,
        endOffset = endOffset,
        origin = Origins.FirstParty.CATCH_PARAMETER,
        name = Name.identifier("failure"),
        type = irBuiltIns.throwableType,
      )
    val record =
      irInvoke(
        callee = metroSymbols.graphLifecycleRecordCloseFailure,
        dispatchReceiver = irGetField(irGet(receiver), state),
        args = listOf(irGet(failure)),
      )
    val catch = IrCatchImpl(startOffset, endOffset, failure, record)
    return irTry(irBuiltIns.unitType, step, catches = listOf(catch), finallyExpression = null)
  }

  /**
   * Registers this graph with its parent so that closing the parent closes it too.
   *
   * The parent can close while this graph runs its init callbacks. Registration then fails, and the
   * runtime closes this graph first because nothing else would. A graph whose `close()` suspends
   * can only be closed that way from `metroInit()`, which [isSuspend] marks. Its constructor has
   * nowhere to await `close()`, so registration there only fails.
   */
  private fun IrStatementsBuilder<*>.registerWithParent(
    receiver: IrValueParameter,
    isSuspend: Boolean,
  ) {
    val state = stateField ?: return
    val parentState = parentState(receiver) ?: return
    val closeSuspends = checkNotNull(node.lifecycleClose).isSuspend
    val child = irGet(receiver)
    val childState = irGetField(irGet(receiver), state)
    val registration =
      if (!closeSuspends) {
        irInvoke(
          callee = metroSymbols.graphLifecycleAddCloseableChild,
          dispatchReceiver = parentState,
          args = listOf(child, childState),
        )
      } else if (isSuspend) {
        irInvoke(
          callee = metroSymbols.addSuspendCloseableChild,
          args = listOf(parentState, child, childState),
        )
      } else {
        irInvoke(
          callee = metroSymbols.graphLifecycleAddChild,
          dispatchReceiver = parentState,
          args = listOf(child, childState),
        )
      }
    +registration
  }

  /** Stops the parent from tracking this graph once [cleanup] finishes, even if cleanup throws. */
  private fun IrBuilderWithScope.releaseFromParentAfter(
    receiver: IrValueParameter,
    cleanup: IrExpression,
  ): IrExpression {
    val state = stateField ?: return cleanup
    val parentState = parentState(receiver) ?: return cleanup
    val release =
      irInvoke(
        callee = metroSymbols.graphLifecycleRemoveChild,
        dispatchReceiver = parentState,
        args = listOf(irGetField(irGet(receiver), state)),
      )
    return irTry(irBuiltIns.unitType, cleanup, catches = emptyList(), finallyExpression = release)
  }

  private fun IrBuilderWithScope.parentState(receiver: IrValueParameter): IrExpression? {
    val parentState = parentStateField ?: return null
    val parentGraph = irGetField(irGet(receiver), checkNotNull(parentGraphField))
    return irGetField(parentGraph, parentState)
  }

  /**
   * Closes the children registered with this graph. A suspend graph can own both kinds of closeable
   * graph, so its runtime helper awaits the children whose `close()` suspends. A non-suspend graph
   * only owns `CloseableGraph` children.
   */
  private fun IrBlockBuilder.closeChildren(
    receiver: IrValueParameter,
    state: IrField,
    isSuspend: Boolean,
  ) {
    val ownState = irGetField(irGet(receiver), state)
    +if (isSuspend) {
      irInvoke(callee = metroSymbols.closeGraphChildren, args = listOf(ownState))
    } else {
      irInvoke(callee = metroSymbols.graphLifecycleCloseChildren, dispatchReceiver = ownState)
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
   * resolved from the binding graph right before that callback runs. [wrap] decorates each call,
   * including the code that resolves its arguments.
   */
  private fun IrBlockBuilder.invokeCallbacks(
    phase: GraphLifecyclePhase,
    receiver: IrValueParameter,
    wrap: IrBuilderWithScope.(IrExpression) -> IrExpression = { it },
  ) {
    val generator = expressionGeneratorFactory.create(receiver)
    for (callback in node.lifecycleCallbacks) {
      if (callback.phase != phase) {
        continue
      }
      trackFunctionCall(node.sourceGraph, callback.original)
      val name = "$graphName.${callback.original.name}"
      val isSuspend = callback.graphFunction.isSuspend
      val invocation =
        irTraced(receiver, name, phase.callbackTraceKind, isSuspend) {
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
      +wrap(invocation)
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
    val accessor =
      GraphTraceContextAccessor(
        context = this@IrGraphLifecycleGenerator,
        thisReceiver = receiver,
        traceContextProperty = graphClass.runtimeTraceContextProperty,
        // Lifecycle code runs in the graph class itself, never in a shard.
        shardContext = null,
      )
    val traceContext = accessor.traceContextExpression() ?: return body()
    val traced =
      irRuntimeTraceSpan(
        traceContext = traceContext,
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
