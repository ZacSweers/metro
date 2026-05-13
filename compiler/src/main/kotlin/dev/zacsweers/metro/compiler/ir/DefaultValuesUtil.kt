// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import dev.zacsweers.metro.compiler.OptionalBindingBehavior
import dev.zacsweers.metro.compiler.ir.parameters.Parameters
import dev.zacsweers.metro.compiler.ir.parameters.wrapInProvider
import dev.zacsweers.metro.compiler.symbols.Symbols
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
import org.jetbrains.kotlin.ir.util.classId
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
  containerParameter: IrValueParameter?,
  wrapInProvider: Boolean = false,
  isTopLevelFunction: Boolean = false,
) {
  if (sourceParameters.isEmpty()) return
  check(sourceParameters.size == targetParameters.size) {
    "Source parameters (${sourceParameters.size}) and target parameters (${targetParameters.size}) must be the same size! Function: ${sourceParameters.first().parent.kotlinFqName}\nSource: ${sourceParameters.map { "${it.name}: ${it.type}" }}\nTarget: ${targetParameters.map { "${it.name}: ${it.type}" }}"
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
          return IrGetValueImpl(SYNTHETIC_OFFSET, SYNTHETIC_OFFSET, containerParameter!!.symbol)
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

  val isDisabled = context.options.optionalBindingBehavior == OptionalBindingBehavior.DISABLED

  for ((index, parameter) in sourceParameters.withIndex()) {
    // If we did get assisted parameters, do copy them over (i.e. top-level function injection)
    if (isDisabled && sourceMetroParameters[parameter.name]?.isAssisted != true) continue
    val defaultValue = parameter.defaultValue ?: continue

    val targetParameter = targetParameters[index]
    val remappingData = RemappingData(parameter.parent, targetParameter.parent)
    if (wrapInProvider) {
      // When the source parameter is itself a Function0 treated as a provider intrinsic
      // (enableFunctionProviders), the default expression is already a () -> T lambda and
      // can be passed directly to provider(fn: () -> T): Provider<T>. Otherwise, we wrap
      // the default expression in a lambda so `provider { <expr> }` produces Provider<T>.
      val isFunctionProvider =
        context.options.enableFunctionProviders &&
          parameter.type.rawTypeOrNull()?.classId == Symbols.ClassIds.function0
      val valueType =
        if (isFunctionProvider) {
          parameter.type.requireSimpleType(parameter).arguments[0].typeOrFail
        } else {
          parameter.type
        }
      val provider =
        IrCallImpl.fromSymbolOwner(
            SYNTHETIC_OFFSET,
            SYNTHETIC_OFFSET,
            valueType.wrapInProvider(context.metroSymbols.metroProvider),
            context.metroSymbols.metroProviderFunction,
          )
          .apply {
            typeArguments[0] = valueType
            val remappedDefault =
              defaultValue.expression
                .deepCopyWithSymbols(initialParent = parameter.parent)
                .transform(transformer, remappingData)
            arguments[0] =
              if (isFunctionProvider) {
                remappedDefault
              } else {
                irLambda(
                  parent = targetParameter.parent,
                  valueParameters = emptyList(),
                  returnType = valueType,
                  receiverParameter = null,
                ) {
                  +irReturn(remappedDefault)
                }
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
