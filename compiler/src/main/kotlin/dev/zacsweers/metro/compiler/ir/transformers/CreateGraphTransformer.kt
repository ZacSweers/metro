// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.transformers

import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.compiler.expectAs
import dev.zacsweers.metro.compiler.expectAsOrNull
import dev.zacsweers.metro.compiler.ir.IrMetroContext
import dev.zacsweers.metro.compiler.ir.IrScope
import dev.zacsweers.metro.compiler.ir.IrTypeKey
import dev.zacsweers.metro.compiler.ir.createAndAddTemporaryVariable
import dev.zacsweers.metro.compiler.ir.getOrCreateGraphFactoryImplClassShell
import dev.zacsweers.metro.compiler.ir.getOrCreateGraphImplClassShell
import dev.zacsweers.metro.compiler.ir.graph.IrDynamicGraphGenerator
import dev.zacsweers.metro.compiler.ir.graph.generatedDynamicGraphData
import dev.zacsweers.metro.compiler.ir.implements
import dev.zacsweers.metro.compiler.ir.irInvoke
import dev.zacsweers.metro.compiler.ir.isExternalParent
import dev.zacsweers.metro.compiler.ir.metroGraphOrFail
import dev.zacsweers.metro.compiler.ir.rawType
import dev.zacsweers.metro.compiler.ir.requireSimpleFunction
import dev.zacsweers.metro.compiler.ir.thisReceiverOrFail
import dev.zacsweers.metro.compiler.ir.withIrBuilder
import dev.zacsweers.metro.compiler.reportCompilerBug
import dev.zacsweers.metro.compiler.symbols.Symbols
import dev.zacsweers.metro.compiler.tracing.TraceScope
import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.builders.irBlock
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irCallConstructor
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irGetObject
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrVararg
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.typeOrFail
import org.jetbrains.kotlin.ir.util.classIdOrFail
import org.jetbrains.kotlin.ir.util.companionObject
import org.jetbrains.kotlin.ir.util.constructors
import org.jetbrains.kotlin.ir.util.functions
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.ir.util.isObject
import org.jetbrains.kotlin.ir.util.isStatic
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.util.nestedClasses
import org.jetbrains.kotlin.ir.util.parentAsClass
import org.jetbrains.kotlin.ir.util.primaryConstructor

/**
 * Covers replacing `createGraph()` and `createGraphFactory()` compiler intrinsics with calls to the
 * real graphs or graph factories.
 */
@Inject
@SingleIn(IrScope::class)
internal class CreateGraphTransformer(
  metroContext: IrMetroContext,
  private val dynamicGraphGenerator: IrDynamicGraphGenerator,
  traceScope: TraceScope,
) : IrMetroContext by metroContext, TraceScope by traceScope {

  private val IrCall.targetGraphType: IrType
    get() = typeArguments[0] ?: reportCompilerBug("Missing type argument for ${symbol.owner.name}")

  context(context: TransformerContextAccess)
  fun visitCall(expression: IrCall): IrExpression? {
    val callee = expression.symbol.owner
    return when (callee.symbol) {
      metroSymbols.metroCreateDynamicGraphFactory -> {
        handleDynamicGraphCreation(expression, isFactory = true, context = context)
      }
      metroSymbols.metroCreateGraphFactory -> {
        // Get the called type
        val type = expression.targetGraphType
        // Already checked in FIR
        val rawType = type.rawType()
        val parentDeclaration = rawType.parentAsClass
        val companion = parentDeclaration.companionObject()

        val companionIsTheFactory = companion?.implements(rawType.classIdOrFail) == true
        val companionFactoryFunction =
          companion?.functions?.singleOrNull { it.name == Symbols.Names.factory }
        var factoryImpl = rawType.nestedClasses.find { it.name == Symbols.Names.Impl }
        val needsHiddenFactory =
          factoryImpl == null && !companionIsTheFactory && companionFactoryFunction == null
        if (needsHiddenFactory && !rawType.isExternalParent) {
          factoryImpl = rawType.getOrCreateGraphFactoryImplClassShell()
        }
        // Hidden factory objects remain available when graph companions are omitted.
        if (factoryImpl?.isObject == true) {
          return withIrBuilder(expression.symbol) { irGetObject(factoryImpl.symbol) }
        }

        val factoryCompanion =
          companion
            ?: reportCompilerBug(
              "Cannot find a graph factory implementation for ${rawType.kotlinFqName}"
            )
        if (factoryImpl != null) {
          // Replace it with a call directly to the factory creator
          return withIrBuilder(expression.symbol) {
            irInvoke(
              callee = factoryCompanion.requireSimpleFunction(Symbols.StringNames.FACTORY),
              dispatchReceiver = companionReceiver(factoryCompanion),
              typeArgs = type.expectAsOrNull<IrSimpleType>()?.arguments?.map { it.typeOrFail },
            )
          }
        }

        if (companionIsTheFactory) {
          withIrBuilder(expression.symbol) { irGetObject(factoryCompanion.symbol) }
        } else {
          val factoryFunction =
            companionFactoryFunction
              ?: reportCompilerBug(
                "Cannot find a graph factory implementation for ${rawType.kotlinFqName}"
              )
          // Replace it with a call directly to the factory function
          withIrBuilder(expression.symbol) {
            irCall(callee = factoryFunction.symbol, type = type).apply {
              dispatchReceiver = companionReceiver(factoryCompanion)
            }
          }
        }
      }
      metroSymbols.metroCreateDynamicGraph -> {
        handleDynamicGraphCreation(expression, isFactory = false, context = context)
      }
      metroSymbols.metroCreateGraph -> {
        // Get the called type
        val type = expression.targetGraphType
        // Already checked in FIR
        val rawType = type.rawType()
        val companion = rawType.companionObject()
        val companionIsTheGraph = companion?.implements(rawType.classIdOrFail) == true
        // Resolve the producer's declaration shape so compiler modes can differ across modules.
        val staticCreator =
          rawType.functions.singleOrNull {
            it.isStatic && it.hasAnnotation(Symbols.FqNames.GraphFactoryInvokeFunctionMarkerClass)
          }
        val companionCreator =
          companion?.functions?.singleOrNull {
            it.hasAnnotation(Symbols.FqNames.GraphFactoryInvokeFunctionMarkerClass)
          }
        val factoryFunction = staticCreator ?: companionCreator
        // A local companion delegate needs construction before its singleton is initialized.
        val initializesCompanionDelegate = companionIsTheGraph && !rawType.isExternalParent
        if (initializesCompanionDelegate || factoryFunction == null) {
          val graphImpl =
            if (options.generateClassesInIr) {
              rawType.getOrCreateGraphImplClassShell()
            } else {
              rawType.metroGraphOrFail
            }
          withIrBuilder(expression.symbol) {
            irCallConstructor(
              // Metadata registrars can represent the sole hidden constructor as secondary.
              graphImpl.constructors.single().symbol,
              type.expectAsOrNull<IrSimpleType>()?.arguments.orEmpty().map { it.typeOrFail },
            )
          }
        } else {
          withIrBuilder(expression.symbol) {
            irCall(callee = factoryFunction.symbol, type = type).apply {
              type.expectAsOrNull<IrSimpleType>()?.arguments.orEmpty().forEachIndexed {
                index,
                argument ->
                typeArguments[index] = argument.typeOrFail
              }
              if (factoryFunction.dispatchReceiverParameter != null) {
                dispatchReceiver = companionReceiver(factoryFunction.parentAsClass)
              }
            }
          }
        }
      }
      else -> null
    }
  }

  /** Matches shared constructor layouts while preserving the call site's argument evaluation. */
  private fun handleDynamicGraphCreation(
    expression: IrCall,
    isFactory: Boolean,
    context: TransformerContextAccess,
  ): IrExpression {
    // Get the target type from type argument
    val targetType = expression.targetGraphType

    // Extract container types from vararg
    // The first argument is the vararg parameter
    val varargArg =
      expression.arguments[0]?.expectAs<IrVararg>()
        ?: reportCompilerBug("Expected vararg argument for dynamic graph creation")

    val containerExpressions = varargArg.elements.map { it.expectAs<IrExpression>() }
    val containerTypeKeys = containerExpressions.map { IrTypeKey(it.type) }

    val nearestDeclaration = expression.symbol.owner

    // Generate or retrieve the dynamic graph class
    val dynamicGraph =
      dynamicGraphGenerator.getOrBuildDynamicGraph(
        targetType = targetType,
        containerTypeKeys = containerTypeKeys,
        isFactory = isFactory,
        context = context,
        containingFunction = nearestDeclaration,
        sourceExpression = expression,
      )

    val graphData =
      dynamicGraph.generatedDynamicGraphData
        ?: reportCompilerBug("Dynamic graph missing generatedDynamicGraphData")
    val implementation =
      if (isFactory) {
        graphData.factoryImpl ?: reportCompilerBug("Dynamic graph factory missing factoryImpl")
      } else {
        dynamicGraph
      }
    val constructor = implementation.primaryConstructor!!.symbol
    val needsReordering = containerTypeKeys != graphData.containerTypeKeys
    // Temporary variables belong to the caller's scope, including property initializers.
    val scopeOwner =
      context.currentScopeAccess?.scope?.scopeOwnerSymbol ?: context.currentFileAccess.symbol

    return withIrBuilder(scopeOwner) {
      if (needsReordering) {
        val sourceIndices = containerTypeKeys.withIndex().associate { (index, key) -> key to index }
        val argumentOrder = graphData.containerTypeKeys.map(sourceIndices::getValue)
        irBlock(resultType = expression.type) {
          // Evaluate every original argument once, from left to right, before arranging the
          // constructor arguments to match the implementation shared by these call sites.
          val containers = containerExpressions.mapIndexed { index, value ->
            createAndAddTemporaryVariable(value, nameHint = "container$index")
          }
          +irCallConstructor(constructor, emptyList()).apply {
            argumentOrder.forEachIndexed { index, sourceIndex ->
              arguments[index] = irGet(containers[sourceIndex])
            }
          }
        }
      } else {
        irCallConstructor(constructor, emptyList()).apply {
          containerExpressions.forEachIndexed { index, value ->
            arguments[index] = value
          }
        }
      }
    }
  }

  context(context: TransformerContextAccess)
  /**
   * Returns the dispatch receiver for calls to functions on [companion].
   *
   * Most rewritten `createGraph*()` calls happen outside the target graph companion, so the call
   * needs an object access receiver. When the intrinsic appears inside that same companion though,
   * the current dispatch receiver is already the companion instance. Reusing `this` keeps the
   * rewritten call scoped like a normal companion member call instead of manufacturing a new object
   * access inside the companion body.
   */
  private fun IrBuilderWithScope.companionReceiver(companion: IrClass): IrExpression {
    val currentClass = context.currentClassAccess?.irElement as? IrClass
    val currentFunction = context.currentFunctionAccess?.irElement as? IrFunction
    return if (currentClass == companion) {
      irGet(currentFunction?.dispatchReceiverParameter ?: companion.thisReceiverOrFail)
    } else {
      irGetObject(companion.symbol)
    }
  }
}
