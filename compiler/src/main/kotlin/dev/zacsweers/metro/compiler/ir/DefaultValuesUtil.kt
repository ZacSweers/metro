// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import dev.zacsweers.metro.compiler.OptionalDependencyBehavior
import dev.zacsweers.metro.compiler.ir.parameters.Parameters
import dev.zacsweers.metro.compiler.ir.parameters.wrapInProvider
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.builders.irReturn
import org.jetbrains.kotlin.ir.declarations.IrDeclarationParent
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrFunctionExpression
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.impl.IrCallImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrGetValueImpl
import org.jetbrains.kotlin.ir.expressions.impl.fromSymbolOwner
import org.jetbrains.kotlin.ir.types.typeOrFail
import org.jetbrains.kotlin.ir.util.SYNTHETIC_OFFSET
import org.jetbrains.kotlin.ir.util.deepCopyWithSymbols
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.util.setDeclarationsParent
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.visitors.IrTransformer

/**
 * Remaps default value expressions from [sourceParameters] to [targetParameters].
 *
 * This works for both simple scalar values, complex expressions, instance references, and
 * back-references to other parameters. Part of supporting that is a local
 * [IrElementTransformerVoid] that remaps those references to the new parameters.
 */
context(context: IrMetroContext)
internal fun copyParameterDefaultValues(
  providerFunction: IrFunction?,
  sourceMetroParameters: Parameters,
  sourceParameters: List<IrValueParameter>,
  targetParameters: List<IrValueParameter>,
  targetGraphParameter: IrValueParameter?,
  wrapInProvider: Boolean = false,
  isTopLevelFunction: Boolean = false,
) {
  if (sourceParameters.isEmpty()) return
  check(sourceParameters.size == targetParameters.size) {
    "Source parameters (${sourceParameters.size}) and target parameters (${targetParameters.size}) must be the same size! Function: ${sourceParameters.first().parent.kotlinFqName}"
  }

  /**
   * [deepCopyWithSymbols] doesn't appear to remap lambda function parents, so we do it in our
   * transformation.
   */
  class RemappingData(val initialParent: IrDeclarationParent, val newParent: IrDeclarationParent)

  val transformer =
    object : IrTransformer<RemappingData>() {
      override fun visitExpression(expression: IrExpression, data: RemappingData): IrExpression {
        if (isTopLevelFunction) {
          // https://youtrack.jetbrains.com/issue/KT-81656
          expression.startOffset = SYNTHETIC_OFFSET
          expression.endOffset = SYNTHETIC_OFFSET
        }
        return super.visitExpression(expression, data)
      }

      override fun visitGetValue(expression: IrGetValue, data: RemappingData): IrExpression {
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
                // Unpack the provider type
                newGet.type.requireSimpleType().arguments[0].typeOrFail,
                context.metroSymbols.providerInvoke,
              )
              .apply { this.dispatchReceiver = newGet }
          } else {
            newGet
          }
        }
        return super.visitGetValue(expression, data)
      }

      override fun visitFunctionExpression(
        expression: IrFunctionExpression,
        data: RemappingData,
      ): IrElement {
        if (expression.function.parent == data.initialParent) {
          // remap the lambda's parent
          expression.function.setDeclarationsParent(data.newParent)
        }
        return super.visitFunctionExpression(expression, data)
      }
    }

  val isDisabled = context.options.optionalDependencyBehavior == OptionalDependencyBehavior.DISABLED

  for ((index, parameter) in sourceParameters.withIndex()) {
    // If we did get assisted parameters, do copy them over (i.e. top-level function injection)
    if (isDisabled && sourceMetroParameters[parameter.name]?.isAssisted != true) continue
    val defaultValue = parameter.defaultValue ?: continue

    val targetParameter = targetParameters[index]
    val remappingData = RemappingData(parameter.parent, targetParameter.parent)
    if (wrapInProvider) {
      val provider =
        IrCallImpl.fromSymbolOwner(
            SYNTHETIC_OFFSET,
            SYNTHETIC_OFFSET,
            parameter.type.wrapInProvider(context.metroSymbols.metroProvider),
            context.metroSymbols.metroProviderFunction,
          )
          .apply {
            typeArguments[0] = parameter.type
            arguments[0] =
              irLambda(
                parent = targetParameter.parent,
                valueParameters = emptyList(),
                returnType = parameter.type,
                receiverParameter = null,
              ) {
                +irReturn(
                  defaultValue.expression
                    .deepCopyWithSymbols(initialParent = parameter.parent)
                    .transform(transformer, remappingData)
                )
              }
          }
      targetParameter.defaultValue =
        defaultValue.deepCopyWithSymbols(initialParent = parameter.parent).apply {
          expression = provider
        }
    } else {
      targetParameter.defaultValue =
        defaultValue
          .deepCopyWithSymbols(initialParent = parameter.parent)
          .transform(transformer, remappingData)
    }
  }
}
