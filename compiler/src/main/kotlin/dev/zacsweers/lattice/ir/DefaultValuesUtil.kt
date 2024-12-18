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
package dev.zacsweers.lattice.ir

import dev.zacsweers.lattice.transformers.LatticeTransformerContext
import dev.zacsweers.lattice.transformers.wrapInProvider
import org.jetbrains.kotlin.ir.builders.irReturn
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.impl.IrCallImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrGetValueImpl
import org.jetbrains.kotlin.ir.expressions.impl.fromSymbolOwner
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.util.SYNTHETIC_OFFSET
import org.jetbrains.kotlin.ir.util.deepCopyWithoutPatchingParents
import org.jetbrains.kotlin.ir.util.dumpKotlinLike
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid

/**
 * Remaps default value expressions from [sourceParameters] to [factoryParameters].
 *
 * This works for both simple scalar values, complex expressions, instance references, and
 * back-references to other parameters. Part of supporting that is a local
 * [IrElementTransformerVoid] that remaps those references to the new parameters.
 */
@OptIn(UnsafeDuringIrConstructionAPI::class)
internal fun LatticeTransformerContext.patchFactoryCreationParameters(
  providerFunction: IrFunction?,
  sourceParameters: List<IrValueParameter>,
  factoryParameters: List<IrValueParameter>,
  factoryComponentParameter: IrValueParameter?,
  wrapInProvider: Boolean = false,
) {
  if (sourceParameters.isEmpty()) return
  check(sourceParameters.size == factoryParameters.size) {
    "Source parameters (${sourceParameters.size}) and factory parameters (${factoryParameters.size}) must be the same size! Function: ${sourceParameters.first().parent.kotlinFqName}"
  }
  if (debug) {
    logVerbose(
      buildString {
        appendLine("Patching factory creation parameters")
        appendLine("  Source params:")
        for ((index, param) in sourceParameters.withIndex()) {
          appendLine("    $index. ${param.dumpKotlinLike()}")
        }
        appendLine("  Factory params:")
        for ((index, param) in factoryParameters.withIndex()) {
          appendLine("    $index. ${param.dumpKotlinLike()}")
        }
      }
    )
  }
  val transformer =
    object : IrElementTransformerVoid() {
      override fun visitGetValue(expression: IrGetValue): IrExpression {
        // Check if the expression is the instance receiver
        if (expression.symbol == providerFunction?.dispatchReceiverParameter?.symbol) {
          return IrGetValueImpl(
            SYNTHETIC_OFFSET,
            SYNTHETIC_OFFSET,
            factoryComponentParameter!!.symbol,
          )
        }
        val index = sourceParameters.indexOfFirst { it.symbol == expression.symbol }
        if (index != -1) {
          val newGet =
            IrGetValueImpl(SYNTHETIC_OFFSET, SYNTHETIC_OFFSET, factoryParameters[index].symbol)
          return if (wrapInProvider) {
            // Need to call invoke on the get
            IrCallImpl.fromSymbolOwner(
                SYNTHETIC_OFFSET,
                SYNTHETIC_OFFSET,
                newGet.type,
                symbols.providerInvoke,
              )
              .apply { this.dispatchReceiver = newGet }
          } else {
            newGet
          }
        }
        return super.visitGetValue(expression)
      }
    }

  for ((index, parameter) in sourceParameters.withIndex()) {
    val defaultValue = parameter.defaultValue ?: continue

    if (wrapInProvider) {
      val targetParam = factoryParameters[index]
      val provider =
        IrCallImpl.fromSymbolOwner(
            SYNTHETIC_OFFSET,
            SYNTHETIC_OFFSET,
            parameter.type.wrapInProvider(symbols.latticeProvider),
            symbols.latticeProviderFunction,
          )
          .apply {
            putTypeArgument(0, parameter.type)
            putValueArgument(
              0,
              irLambda(
                context = pluginContext,
                parent = targetParam.parent,
                valueParameters = emptyList(),
                returnType = parameter.type,
                receiverParameter = null,
              ) { function ->
                +irReturn(
                  defaultValue.expression
                    .deepCopyWithoutPatchingParents()
                    .transform(transformer, null)
                )
              },
            )
          }
      targetParam.defaultValue =
        defaultValue.deepCopyWithoutPatchingParents().apply { expression = provider }
    } else {
      factoryParameters[index].defaultValue =
        defaultValue.deepCopyWithoutPatchingParents().transform(transformer, null)
    }
  }
}
