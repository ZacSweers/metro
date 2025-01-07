/*
 * Copyright (C) 2024 Zac Sweers
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.zacsweers.lattice.compiler.ir.transformers

import dev.zacsweers.lattice.compiler.LatticeOrigins
import dev.zacsweers.lattice.compiler.ir.LatticeTransformerContext
import dev.zacsweers.lattice.compiler.ir.copyParameterDefaultValues
import dev.zacsweers.lattice.compiler.ir.createIrBuilder
import dev.zacsweers.lattice.compiler.ir.irCallConstructorWithSameParameters
import dev.zacsweers.lattice.compiler.ir.irExprBodySafe
import dev.zacsweers.lattice.compiler.ir.parameters.Parameter
import dev.zacsweers.lattice.compiler.ir.parameters.Parameters
import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.builders.irGetObject
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.symbols.IrConstructorSymbol
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
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
@OptIn(UnsafeDuringIrConstructionAPI::class)
internal fun generateStaticCreateFunction(
  context: LatticeTransformerContext,
  parentClass: IrClass,
  targetClass: IrClass,
  targetConstructor: IrConstructorSymbol,
  parameters: Parameters<out Parameter>,
  providerFunction: IrFunction?,
  patchCreationParams: Boolean = true,
): IrSimpleFunction {
  val function = parentClass.functions.first { it.origin == LatticeOrigins.FactoryCreateFunction }

  return function.apply {
    if (patchCreationParams) {
      val instanceParam = valueParameters.find { it.origin == LatticeOrigins.InstanceParameter }
      val valueParamsToPatch = valueParameters.filter { it.origin == LatticeOrigins.ValueParameter }
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
@OptIn(UnsafeDuringIrConstructionAPI::class)
internal fun generateStaticNewInstanceFunction(
  context: LatticeTransformerContext,
  parentClass: IrClass,
  sourceParameters: List<IrValueParameter>,
  targetFunction: IrFunction? = null,
  buildBody: IrBuilderWithScope.(IrSimpleFunction) -> IrExpression,
): IrSimpleFunction {
  val function =
    parentClass.functions.first { it.origin == LatticeOrigins.FactoryNewInstanceFunction }

  return function.apply {
    val instanceParam = valueParameters.find { it.origin == LatticeOrigins.InstanceParameter }
    val valueParametersToMap = valueParameters.filter { it.origin == LatticeOrigins.ValueParameter }
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
