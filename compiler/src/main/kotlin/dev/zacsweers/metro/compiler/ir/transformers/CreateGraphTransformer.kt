// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.transformers

import dev.zacsweers.metro.compiler.Symbols
import dev.zacsweers.metro.compiler.expectAsOrNull
import dev.zacsweers.metro.compiler.ir.IrMetroContext
import dev.zacsweers.metro.compiler.ir.createIrBuilder
import dev.zacsweers.metro.compiler.ir.implements
import dev.zacsweers.metro.compiler.ir.irInvoke
import dev.zacsweers.metro.compiler.ir.metroGraphOrFail
import dev.zacsweers.metro.compiler.ir.rawType
import dev.zacsweers.metro.compiler.ir.requireSimpleFunction
import dev.zacsweers.metro.compiler.reportCompilerBug
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irCallConstructor
import org.jetbrains.kotlin.ir.builders.irGetObject
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.types.IrSimpleType
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
 * Covers replacing createGraphFactory() compiler intrinsics with calls to the real graph factory.
 */
internal object CreateGraphTransformer {
  fun visitCall(expression: IrCall, metroContext: IrMetroContext): IrExpression? =
    context(metroContext) {
      val callee = expression.symbol.owner
      when (callee.symbol) {
        metroContext.symbols.metroCreateGraphFactory -> {
          // Get the called type
          val type =
            expression.typeArguments[0]
              ?: reportCompilerBug(
                "Missing type argument for ${metroContext.symbols.metroCreateGraphFactory.owner.name}"
              )
          // Already checked in FIR
          val rawType = type.rawType()
          val parentDeclaration = rawType.parentAsClass
          val companion = parentDeclaration.companionObject()!!

          val factoryImpl = rawType.nestedClasses.find { it.name == Symbols.Names.MetroImpl }
          if (factoryImpl != null) {
            // Replace it with a call directly to the factory creator
            return metroContext.createIrBuilder(expression.symbol).run {
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
            return metroContext.createIrBuilder(expression.symbol).run {
              irGetObject(companion.symbol)
            }
          } else {
            val factoryFunction =
              companion.functions.single {
                // Note we don't filter on Origins.MetroGraphFactoryCompanionGetter, because
                // sometimes a user may have already defined one. An FIR checker will validate that
                // any such function is valid, so just trust it if one is found
                it.name == Symbols.Names.factory
              }
            // Replace it with a call directly to the factory function
            return metroContext.createIrBuilder(expression.symbol).run {
              irCall(callee = factoryFunction.symbol, type = type).apply {
                dispatchReceiver = irGetObject(companion.symbol)
              }
            }
          }
        }

        metroContext.symbols.metroCreateGraph -> {
          // Get the called type
          val type =
            expression.typeArguments[0]
              ?: reportCompilerBug(
                "Missing type argument for ${metroContext.symbols.metroCreateGraph.owner.name}"
              )
          // Already checked in FIR
          val rawType = type.rawType()
          val companion = rawType.companionObject()!!

          val companionIsTheGraph = companion.implements(rawType.classIdOrFail)
          if (companionIsTheGraph) {
            return metroContext.createIrBuilder(expression.symbol).run {
              val metroGraph = rawType.metroGraphOrFail
              irCallConstructor(
                metroGraph.primaryConstructor!!.symbol,
                type.expectAsOrNull<IrSimpleType>()?.arguments.orEmpty().map { it.typeOrFail },
              )
            }
          }

          val factoryFunction =
            companion.functions.singleOrNull {
              it.hasAnnotation(Symbols.FqNames.GraphFactoryInvokeFunctionMarkerClass)
            } ?: reportCompilerBug("Cannot find a graph factory function for ${rawType.kotlinFqName}")
          // Replace it with a call directly to the create function
          return metroContext.createIrBuilder(expression.symbol).run {
            irCall(callee = factoryFunction.symbol, type = type).apply {
              dispatchReceiver = irGetObject(companion.symbol)
            }
          }
        }
      }

      return null
    }
}
