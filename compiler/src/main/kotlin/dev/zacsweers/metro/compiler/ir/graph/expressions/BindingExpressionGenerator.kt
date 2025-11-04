// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.graph.expressions

import dev.zacsweers.metro.compiler.ir.IrContextualTypeKey
import dev.zacsweers.metro.compiler.ir.IrMetroContext
import dev.zacsweers.metro.compiler.ir.IrTypeKey
import dev.zacsweers.metro.compiler.ir.graph.IrBinding
import dev.zacsweers.metro.compiler.ir.graph.IrBindingGraph
import dev.zacsweers.metro.compiler.ir.instanceFactory
import dev.zacsweers.metro.compiler.ir.irInvoke
import dev.zacsweers.metro.compiler.ir.irLambda
import dev.zacsweers.metro.compiler.ir.parameters.wrapInProvider
import dev.zacsweers.metro.compiler.tracing.Tracer
import org.jetbrains.kotlin.ir.builders.IrBlockBodyBuilder
import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.builders.irReturn
import org.jetbrains.kotlin.ir.builders.parent
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.util.classId

internal abstract class BindingExpressionGenerator<T : IrBinding>(context: IrMetroContext) :
  IrMetroContext by context {
  abstract val thisReceiver: IrValueParameter
  abstract val bindingGraph: IrBindingGraph
  abstract val parentTracer: Tracer

  enum class AccessType {
    INSTANCE,
    // note: maybe rename this to PROVIDER_LIKE or PROVIDER_OR_FACTORY
    PROVIDER,
  }

  context(scope: IrBuilderWithScope)
  abstract fun generateBindingCode(
    binding: T,
    contextualTypeKey: IrContextualTypeKey,
    accessType: AccessType =
      if (contextualTypeKey.requiresProviderInstance) {
        AccessType.PROVIDER
      } else {
        AccessType.INSTANCE
      },
    fieldInitKey: IrTypeKey? = null,
  ): IrExpression

  /**
   * Transforms an expression to match the target contextual type.
   *
   * This handles both:
   * 1. Access type transformation (INSTANCE <-> PROVIDER)
   * 2. Provider framework conversion (e.g., Metro Provider -> Dagger Lazy)
   *
   * Both `actual` and `requested` are inferred by default:
   * - `actual` is inferred from the expression's type (Provider/Lazy = PROVIDER, else INSTANCE)
   * - `requested` is inferred from contextualTypeKey.requiresProviderInstance
   *
   * @param contextualTypeKey The target type with framework information
   * @param actual The current access type (inferred from expression type by default)
   * @param requested The desired access type (inferred from contextualTypeKey by default)
   * @param useInstanceFactory Whether to use InstanceFactory for INSTANCE->PROVIDER (vs lambda)
   */
  context(scope: IrBuilderWithScope)
  protected fun IrExpression.toTargetType(
    contextualTypeKey: IrContextualTypeKey,
    actual: AccessType = run {
      val classId = type.classOrNull?.owner?.classId
      val isProviderType =
        classId in metroSymbols.providerTypes || classId in metroSymbols.lazyTypes
      if (isProviderType) {
        AccessType.PROVIDER
      } else {
        AccessType.INSTANCE
      }
    },
    requested: AccessType =
      if (contextualTypeKey.requiresProviderInstance) {
        AccessType.PROVIDER
      } else {
        AccessType.INSTANCE
      },
    useInstanceFactory: Boolean = true,
  ): IrExpression {
    // Step 1: Transform access type (INSTANCE <-> PROVIDER)
    val accessTransformed =
      when (requested) {
        actual -> this
        AccessType.PROVIDER -> {
          if (useInstanceFactory) {
            // actual is an instance, wrap it
            wrapInInstanceFactory(contextualTypeKey.typeKey.type)
          } else {
            scope.wrapInProviderFunction(contextualTypeKey.typeKey.type) { this@toTargetType }
          }
        }
        AccessType.INSTANCE -> {
          // actual is a provider but we want instance
          unwrapProvider(contextualTypeKey.typeKey.type)
        }
      }

    // Step 2: Convert provider if needed (e.g., Metro -> Dagger)
    // Only do this if we're in PROVIDER mode (or transformed to it)
    val finalAccessType = if (requested == AccessType.PROVIDER) requested else actual
    return if (finalAccessType == AccessType.PROVIDER) {
      with(scope) {
        with(metroSymbols.providerTypeConverter) { accessTransformed.convertTo(contextualTypeKey) }
      }
    } else {
      accessTransformed
    }
  }

  context(scope: IrBuilderWithScope)
  protected fun IrExpression.wrapInInstanceFactory(type: IrType): IrExpression {
    return with(scope) { instanceFactory(type, this@wrapInInstanceFactory) }
  }

  protected fun IrBuilderWithScope.wrapInProviderFunction(
    type: IrType,
    returnExpression: IrBlockBodyBuilder.(function: IrSimpleFunction) -> IrExpression,
  ): IrExpression {
    val lambda =
      irLambda(parent = this.parent, receiverParameter = null, emptyList(), type, suspend = false) {
        +irReturn(returnExpression(it))
      }
    return irInvoke(
      dispatchReceiver = null,
      callee = metroSymbols.metroProviderFunction,
      typeHint = type.wrapInProvider(metroSymbols.metroProvider),
      typeArgs = listOf(type),
      args = listOf(lambda),
    )
  }

  context(scope: IrBuilderWithScope)
  protected fun IrExpression.unwrapProvider(type: IrType): IrExpression {
    return with(scope) {
      irInvoke(this@unwrapProvider, callee = metroSymbols.providerInvoke, typeHint = type)
    }
  }
}
