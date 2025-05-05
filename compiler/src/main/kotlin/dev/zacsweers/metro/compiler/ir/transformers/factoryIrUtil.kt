// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.transformers

import dev.zacsweers.metro.compiler.Origins
import dev.zacsweers.metro.compiler.Symbols
import dev.zacsweers.metro.compiler.ir.IrMetroContext
import dev.zacsweers.metro.compiler.ir.copyParameterDefaultValues
import dev.zacsweers.metro.compiler.ir.createIrBuilder
import dev.zacsweers.metro.compiler.ir.irCallConstructorWithSameParameters
import dev.zacsweers.metro.compiler.ir.irExprBodySafe
import dev.zacsweers.metro.compiler.ir.parameters.Parameter
import dev.zacsweers.metro.compiler.ir.parameters.Parameters
import dev.zacsweers.metro.compiler.ir.stubExpression
import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.builders.declarations.addFunction
import org.jetbrains.kotlin.ir.builders.irGetObject
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.symbols.IrConstructorSymbol
import org.jetbrains.kotlin.ir.util.copyParametersFrom
import org.jetbrains.kotlin.ir.util.functions
import org.jetbrains.kotlin.ir.util.isObject

/**
 * Implement a static `create()` function for a given [targetConstructor].
 *
 * ```kotlin
 * // Simple
 * fun create(valueProvider: Provider<String>): Example_Factory = Example_Factory(valueProvider)
 *
 * // Generic
 * fun <T> create(valueProvider: Provider<T>): Example_Factory<T> = Example_Factory<T>(valueProvider)
 * ```
 */
internal fun generateStaticCreateFunction(
  context: IrMetroContext,
  parentClass: IrClass,
  targetClass: IrClass,
  targetConstructor: IrConstructorSymbol,
  parameters: Parameters<out Parameter>,
  providerFunction: IrFunction?,
  patchCreationParams: Boolean = true,
): IrSimpleFunction {
  val function = parentClass.functions.first { it.origin == Origins.FactoryCreateFunction }

  return function.apply {
    if (patchCreationParams) {
      val instanceParam = valueParameters.find { it.origin == Origins.InstanceParameter }
      val valueParamsToPatch = valueParameters.filter { it.origin == Origins.ValueParameter }
      context.copyParameterDefaultValues(
        providerFunction = providerFunction,
        sourceParameters = parameters.valueParameters.filterNot { it.isAssisted }.map { it.ir },
        targetParameters = valueParamsToPatch,
        targetGraphParameter = instanceParam,
        wrapInProvider = true,
      )
    }

    body =
      context.pluginContext.createIrBuilder(symbol).run {
        irExprBodySafe(
          symbol,
          if (targetClass.isObject) {
            irGetObject(targetClass.symbol)
          } else {
            irCallConstructorWithSameParameters(function, targetConstructor)
          },
        )
      }
  }
}

/**
 * Generates a static `newInstance()` function into a given [parentClass].
 *
 * ```
 * // Simple
 * fun newInstance(value: T): Example = Example(value)
 *
 * // Generic
 * fun <T> newInstance(value: T): Example<T> = Example<T>(value)
 *
 * // Provider
 * fun newInstance(value: Provider<String>): Example = Example(value)
 * ```
 */
internal fun generateStaticNewInstanceFunction(
  context: IrMetroContext,
  parentClass: IrClass,
  sourceParameters: List<IrValueParameter>,
  targetFunction: IrFunction? = null,
  buildBody: IrBuilderWithScope.(IrSimpleFunction) -> IrExpression,
): IrSimpleFunction {
  val function = parentClass.functions.first { it.origin == Origins.FactoryNewInstanceFunction }

  return function.apply {
    val instanceParam = valueParameters.find { it.origin == Origins.InstanceParameter }
    val valueParametersToMap = valueParameters.filter { it.origin == Origins.ValueParameter }
    context.copyParameterDefaultValues(
      providerFunction = targetFunction,
      sourceParameters = sourceParameters,
      targetParameters = valueParametersToMap,
      targetGraphParameter = instanceParam,
    )

    body =
      context.pluginContext.createIrBuilder(symbol).run {
        irExprBodySafe(symbol, buildBody(this@apply))
      }
  }
}

/**
 * Generates a metadata-visible function in the factory class that matches the signature of the
 * target constructor. This function is used in downstream compilations to read the constructor's
 * signature.
 */
internal fun generateMetadataVisibleConstructorFunction(
  context: IrMetroContext,
  factoryClass: IrClass,
  targetConstructor: IrConstructor,
): IrSimpleFunction {
  val function =
    factoryClass
      .addFunction {
        name = Symbols.Names.constructorFunction
        returnType = targetConstructor.returnType
      }
      .apply {
        copyParametersFrom(targetConstructor)
        // The function's signature already matches the target constructor's signature, all we need
        // this for
        body =
          context.pluginContext.createIrBuilder(symbol).run {
            irExprBodySafe(symbol, stubExpression(context))
          }
      }
  context.pluginContext.metadataDeclarationRegistrar.registerFunctionAsMetadataVisible(function)
  return function
}
