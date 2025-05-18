// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import dev.zacsweers.metro.compiler.ir.parameters.wrapInProvider
import org.jetbrains.kotlin.ir.builders.irReturn
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.impl.IrCallImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrGetValueImpl
import org.jetbrains.kotlin.ir.expressions.impl.fromSymbolOwner
import org.jetbrains.kotlin.ir.util.SYNTHETIC_OFFSET
import org.jetbrains.kotlin.ir.util.deepCopyWithoutPatchingParents
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid

/**
 * Remaps default value expressions from [sourceParameters] to [targetParameters].
 *
 * This works for both simple scalar values, complex expressions, instance references, and
 * back-references to other parameters. Part of supporting that is a local
 * [IrElementTransformerVoid] that remaps those references to the new parameters.
 */
internal fun IrMetroContext.copyParameterDefaultValues(
  providerFunction: IrFunction?,
  sourceParameters: List<IrValueParameter>,
  targetParameters: List<IrValueParameter>,
  targetGraphParameter: IrValueParameter?,
  wrapInProvider: Boolean = false,
) {
  if (sourceParameters.isEmpty()) return
  check(sourceParameters.size == targetParameters.size) {
    "Source parameters (${sourceParameters.size}) and target parameters (${targetParameters.size}) must be the same size! Function: ${sourceParameters.first().parent.kotlinFqName}"
  }
  val transformer =
    object : IrElementTransformerVoid() {
      override fun visitGetValue(expression: IrGetValue): IrExpression {
        // Check if the expression is the instance receiver
        if (expression.symbol == providerFunction?.dispatchReceiverParameter?.symbol) {
          return IrGetValueImpl(SYNTHETIC_OFFSET, SYNTHETIC_OFFSET, targetGraphParameter!!.symbol)
        }
        val index = sourceParameters.indexOfFirst { it.symbol == expression.symbol }
        if (index != -1) {
          val newGet =
            IrGetValueImpl(SYNTHETIC_OFFSET, SYNTHETIC_OFFSET, targetParameters[index].symbol)
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
      val targetParam = targetParameters[index]
      val provider =
        IrCallImpl.fromSymbolOwner(
            SYNTHETIC_OFFSET,
            SYNTHETIC_OFFSET,
            parameter.type.wrapInProvider(symbols.metroProvider),
            symbols.metroProviderFunction,
          )
          .apply {
            typeArguments[0] = parameter.type
            arguments[0] =
              irLambda(
                context = pluginContext,
                parent = targetParam.parent,
                valueParameters = emptyList(),
                returnType = parameter.type,
                receiverParameter = null,
              ) {
                +irReturn(
                  defaultValue.expression
                    .deepCopyWithoutPatchingParents()
                    .transform(transformer, null)
                )
              }
          }
      targetParam.defaultValue =
        defaultValue.deepCopyWithoutPatchingParents().apply { expression = provider }
    } else {
      targetParameters[index].defaultValue =
        defaultValue.deepCopyWithoutPatchingParents().transform(transformer, null)
    }
  }
}
