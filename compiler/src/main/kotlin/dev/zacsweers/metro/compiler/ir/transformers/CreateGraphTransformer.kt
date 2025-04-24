// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.transformers

import dev.zacsweers.metro.compiler.Symbols
import dev.zacsweers.metro.compiler.ir.IrMetroContext
import dev.zacsweers.metro.compiler.ir.createIrBuilder
import dev.zacsweers.metro.compiler.ir.implements
import dev.zacsweers.metro.compiler.ir.rawType
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irGetObject
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.util.classIdOrFail
import org.jetbrains.kotlin.ir.util.companionObject
import org.jetbrains.kotlin.ir.util.functions
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.util.nestedClasses
import org.jetbrains.kotlin.ir.util.parentAsClass

/**
 * Covers replacing createGraphFactory() compiler intrinsics with calls to the real graph factory.
 */
internal object CreateGraphTransformer {
  fun visitCall(expression: IrCall, metroContext: IrMetroContext): IrElement? {
    val callee = expression.symbol.owner
    when (callee.symbol) {
      metroContext.symbols.metroCreateGraphFactory -> {
        // Get the called type
        val type =
          expression.typeArguments[0]
            ?: error(
              "Missing type argument for ${metroContext.symbols.metroCreateGraphFactory.owner.name}"
            )
        // Already checked in FIR
        val rawType = type.rawType()
        val parentDeclaration = rawType.parentAsClass
        val companion = parentDeclaration.companionObject()!!

        // If there's no $$Impl class, the companion object is the impl
        val companionIsTheFactory =
          companion.implements(metroContext.pluginContext, rawType.classIdOrFail) &&
            rawType.nestedClasses.singleOrNull { it.name == Symbols.Names.MetroImpl } == null

        if (companionIsTheFactory) {
          return metroContext.pluginContext.createIrBuilder(expression.symbol).run {
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
          return metroContext.pluginContext.createIrBuilder(expression.symbol).run {
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
            ?: error(
              "Missing type argument for ${metroContext.symbols.metroCreateGraph.owner.name}"
            )
        // Already checked in FIR
        val rawType = type.rawType()
        val companion = rawType.companionObject()!!
        val factoryFunction =
          companion.functions.singleOrNull {
            it.hasAnnotation(Symbols.FqNames.GraphFactoryInvokeFunctionMarkerClass)
          } ?: error("Cannot find a graph factory function for ${rawType.kotlinFqName}")
        // Replace it with a call directly to the create function
        return metroContext.pluginContext.createIrBuilder(expression.symbol).run {
          irCall(callee = factoryFunction.symbol, type = type).apply {
            dispatchReceiver = irGetObject(companion.symbol)
          }
        }
      }
    }

    return null
  }
}
