// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.transformers

import dev.zacsweers.metro.compiler.expectAs
import dev.zacsweers.metro.compiler.expectAsOrNull
import dev.zacsweers.metro.compiler.ir.IrMetroContext
import dev.zacsweers.metro.compiler.ir.graph.IrDynamicGraphGenerator
import dev.zacsweers.metro.compiler.ir.graph.generatedDynamicGraphData
import dev.zacsweers.metro.compiler.ir.implements
import dev.zacsweers.metro.compiler.ir.irInvoke
import dev.zacsweers.metro.compiler.ir.metroGraphOrFail
import dev.zacsweers.metro.compiler.ir.rawType
import dev.zacsweers.metro.compiler.ir.requireSimpleFunction
import dev.zacsweers.metro.compiler.ir.withIrBuilder
import dev.zacsweers.metro.compiler.mapToSet
import dev.zacsweers.metro.compiler.reportCompilerBug
import dev.zacsweers.metro.compiler.symbols.Symbols
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irCallConstructor
import org.jetbrains.kotlin.ir.builders.irGetObject
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrVararg
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.typeOrFail
import org.jetbrains.kotlin.ir.util.classIdOrFail
import org.jetbrains.kotlin.ir.util.companionObject
import org.jetbrains.kotlin.ir.util.functions
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.ir.util.isObject
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.util.nestedClasses
import org.jetbrains.kotlin.ir.util.parentAsClass
import org.jetbrains.kotlin.ir.util.primaryConstructor

/**
 * Covers replacing `createGraph()` and `createGraphFactory()` compiler intrinsics with calls to the
 * real graphs or graph factories.
 */
internal class CreateGraphTransformer(
  metroContext: IrMetroContext,
  private val dynamicGraphGenerator: IrDynamicGraphGenerator,
) : IrMetroContext by metroContext {

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
        val companion = parentDeclaration.companionObject()!!

        val factoryImpl = rawType.nestedClasses.find { it.name == Symbols.Names.Impl }
        if (factoryImpl != null) {
          // Replace it with a call directly to the factory creator
          return withIrBuilder(expression.symbol) {
            if (factoryImpl.isObject) {
              irGetObject(factoryImpl.symbol)
            } else {
              irInvoke(
                callee = companion.requireSimpleFunction(Symbols.StringNames.FACTORY),
                typeArgs = type.expectAsOrNull<IrSimpleType>()?.arguments?.map { it.typeOrFail },
              )
            }
          }
        }

        val companionIsTheFactory = companion.implements(rawType.classIdOrFail)

        if (companionIsTheFactory) {
          withIrBuilder(expression.symbol) { irGetObject(companion.symbol) }
        } else {
          val factoryFunction =
            companion.functions.single {
              // Note we don't filter on Origins.MetroGraphFactoryCompanionGetter, because
              // sometimes a user may have already defined one. An FIR checker will validate that
              // any such function is valid, so just trust it if one is found
              it.name == Symbols.Names.factory
            }

          // Replace it with a call directly to the factory function
          withIrBuilder(expression.symbol) {
            irCall(callee = factoryFunction.symbol, type = type).apply {
              dispatchReceiver = irGetObject(companion.symbol)
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
        val companion = rawType.companionObject()!!

        val companionIsTheGraph = companion.implements(rawType.classIdOrFail)
        if (companionIsTheGraph) {
          withIrBuilder(expression.symbol) {
            irCallConstructor(
              rawType.metroGraphOrFail.primaryConstructor!!.symbol,
              type.expectAsOrNull<IrSimpleType>()?.arguments.orEmpty().map { it.typeOrFail },
            )
          }
        } else {
          val factoryFunction =
            companion.functions.singleOrNull {
              it.hasAnnotation(Symbols.FqNames.GraphFactoryInvokeFunctionMarkerClass)
            }
              ?: reportCompilerBug(
                "Cannot find a graph factory function for ${rawType.kotlinFqName}"
              )
          // Replace it with a call directly to the create function
          withIrBuilder(expression.symbol) {
            irCall(callee = factoryFunction.symbol, type = type).apply {
              dispatchReceiver = irGetObject(companion.symbol)
            }
          }
        }
      }
      else -> null
    }
  }

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

    val containerTypes =
      varargArg.elements.mapToSet { element ->
        // Each element should be an expression whose type is the container type
        element.expectAs<IrExpression>().type
      }

    // Generate or retrieve the dynamic graph class
    val dynamicGraph =
      dynamicGraphGenerator.getOrBuildDynamicGraph(
        targetType = targetType,
        containerTypes = containerTypes,
        isFactory = isFactory,
        context = context,
      )

    // Replace with constructor call or factory creation
    return withIrBuilder(expression.symbol) {
      if (isFactory) {
        // For factories, create an instance of the factory impl
        val factoryImplData =
          dynamicGraph.generatedDynamicGraphData
            ?: reportCompilerBug("Dynamic graph factory missing generatedDynamicGraphData")
        val factoryImpl =
          factoryImplData.factoryImpl
            ?: reportCompilerBug("Dynamic graph factory missing factoryImpl")

        irCallConstructor(factoryImpl.primaryConstructor!!.symbol, emptyList()).apply {
          // Pass the container instances as constructor arguments
          varargArg.elements.forEachIndexed { index, element ->
            arguments[index] = element.expectAs<IrExpression>()
          }
        }
      } else {
        // For non-factories, directly create the graph
        irCallConstructor(dynamicGraph.primaryConstructor!!.symbol, emptyList()).apply {
          // Pass the container instances as constructor arguments
          varargArg.elements.forEachIndexed { index, element ->
            arguments[index] = element.expectAs<IrExpression>()
          }
        }
      }
    }
  }
}
