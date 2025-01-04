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

import dev.zacsweers.lattice.compiler.LatticeOrigin
import dev.zacsweers.lattice.compiler.LatticeOrigins
import dev.zacsweers.lattice.compiler.LatticeSymbols
import dev.zacsweers.lattice.compiler.ifNotEmpty
import dev.zacsweers.lattice.compiler.ir.LatticeTransformerContext
import dev.zacsweers.lattice.compiler.ir.copyParameterDefaultValues
import dev.zacsweers.lattice.compiler.ir.createIrBuilder
import dev.zacsweers.lattice.compiler.ir.irCallConstructorWithSameParameters
import dev.zacsweers.lattice.compiler.ir.irExprBodySafe
import dev.zacsweers.lattice.compiler.ir.parameters.ConstructorParameter
import dev.zacsweers.lattice.compiler.ir.parameters.Parameter
import dev.zacsweers.lattice.compiler.ir.parameters.Parameters
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.builders.declarations.addFunction
import org.jetbrains.kotlin.ir.builders.declarations.addValueParameter
import org.jetbrains.kotlin.ir.builders.irGetObject
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrTypeParameter
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.symbols.IrConstructorSymbol
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.util.copyTypeParameters
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
  context: LatticeTransformerContext,
  parentClass: IrClass,
  targetClass: IrClass,
  targetClassParameterized: IrType,
  targetConstructor: IrConstructorSymbol,
  parameters: Parameters<out Parameter>,
  providerFunction: IrFunction?,
  patchCreationParams: Boolean = true,
): IrSimpleFunction {
  return parentClass
    .addFunction(LatticeSymbols.StringNames.create, targetClassParameterized)
    .apply {
      val thisFunction = this
      this.copyTypeParameters(targetClass.typeParameters)
      this.origin = LatticeOrigin
      this.visibility = DescriptorVisibilities.PUBLIC

      val instanceParam =
        parameters.instance?.let {
          addValueParameter(it.name, it.providerType, LatticeOrigins.InstanceParameter)
        }
      parameters.extensionReceiver?.let {
        addValueParameter(it.name, it.providerType, LatticeOrigins.ReceiverParameter)
      }
      parameters.valueParameters
        .filterNot { it.isAssisted }
        .map {
          addValueParameter(it.name, it.providerType, LatticeOrigins.ValueParameter).also { irParam
            ->
            it.typeKey.qualifier?.let {
              // Copy any qualifiers over so they're retrievable during dependency graph
              // resolution
              irParam.annotations += it.ir
            }
          }
        }

      if (patchCreationParams) {
        val valueParamsToPatch =
          valueParameters.filter { it.origin == LatticeOrigins.ValueParameter }
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
              irCallConstructorWithSameParameters(thisFunction, targetConstructor)
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
 * @JvmStatic // JVM only
 * fun newInstance(value: T): Example = Example(value)
 *
 * // Generic
 * @JvmStatic // JVM only
 * fun <T> newInstance(value: T): Example<T> = Example<T>(value)
 *
 * // Provider
 * @JvmStatic // JVM only
 * fun newInstance(value: Provider<String>): Example = Example(value)
 * ```
 */
// TODO need to support either calling JvmDefault or DefaultImpls?
internal fun generateStaticNewInstanceFunction(
  context: LatticeTransformerContext,
  parentClass: IrClass,
  name: String,
  returnType: IrType,
  parameters: Parameters<ConstructorParameter>,
  sourceParameters: List<IrValueParameter>,
  targetFunction: IrFunction? = null,
  sourceTypeParameters: List<IrTypeParameter> = emptyList(),
  buildBody: IrBuilderWithScope.(IrSimpleFunction) -> IrExpression,
): IrSimpleFunction {
  return parentClass
    .addFunction(
      name,
      returnType,
      origin = LatticeOrigin,
      visibility = DescriptorVisibilities.PUBLIC,
    )
    .apply {
      sourceTypeParameters.ifNotEmpty { this@apply.copyTypeParameters(this) }

      val newInstanceParameters = parameters.with(this)

      val instanceParam =
        newInstanceParameters.instance?.let {
          addValueParameter(it.name, it.originalType, LatticeOrigin)
        }
      newInstanceParameters.extensionReceiver?.let {
        addValueParameter(it.name, it.originalType, LatticeOrigin)
      }

      val valueParametersToMap =
        newInstanceParameters.valueParameters.map {
          addValueParameter(it.name, it.originalType, LatticeOrigin)
        }

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
