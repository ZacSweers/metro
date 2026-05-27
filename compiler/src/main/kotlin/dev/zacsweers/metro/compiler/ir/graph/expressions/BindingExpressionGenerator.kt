// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.graph.expressions

import dev.zacsweers.metro.compiler.ir.*
import dev.zacsweers.metro.compiler.ir.graph.IrBinding
import dev.zacsweers.metro.compiler.ir.graph.IrBindingGraph
import dev.zacsweers.metro.compiler.ir.parameters.wrapInProvider
import dev.zacsweers.metro.compiler.tracing.TraceScope
import org.jetbrains.kotlin.ir.builders.*
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.util.classId
import org.jetbrains.kotlin.ir.util.constructors

internal abstract class BindingExpressionGenerator<T : IrBinding>(
  context: IrMetroContext,
  traceScope: TraceScope,
) : IrMetroContext by context, TraceScope by traceScope {
  abstract val thisReceiver: IrValueParameter
  abstract val bindingGraph: IrBindingGraph

  enum class AccessType {
    INSTANCE,
    // note: maybe rename this to PROVIDER_LIKE or PROVIDER_OR_FACTORY
    PROVIDER;

    companion object {
      fun of(contextKey: IrContextualTypeKey): AccessType {
        return if (contextKey.isWrappedInProvider) {
          PROVIDER
        } else {
          INSTANCE
        }
      }
    }
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
   * @param allowPropertyGetter Whether to allow wrapping property getter calls in InstanceFactory.
   *   Normally this would eagerly init the getter, but for graph extension GETTER properties this
   *   is intentional since the getter lazily creates the extension.
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
    allowPropertyGetter: Boolean = false,
  ): IrExpression {
    val accessTransformed =
      when (requested) {
        actual -> this
        PROVIDER -> {
          if (useInstanceFactory) {
            // actual is an instance, wrap it
            wrapInInstanceFactory(contextualTypeKey.typeKey.type, allowPropertyGetter)
          } else {
            scope.wrapInProviderFunction(contextualTypeKey.typeKey.type) { this@toTargetType }
          }
        }
        INSTANCE -> {
          // actual is a provider but we want instance
          unwrapProvider(contextualTypeKey.typeKey.type)
        }
      }

    // Wrap in TracedProvider if runtime tracing is enabled.
    val maybeTraced =
      if (
        options.enableRuntimeTracing &&
          requested == AccessType.PROVIDER &&
          contextualTypeKey.typeKey.classId != metroSymbols.tracer
      ) {
        with(scope) {
          val tracerInstance = generateTracerBindingCode()
          irCallConstructor(
              metroSymbols.tracedProvider.constructors.first { it.owner.isPrimary },
              listOf(contextualTypeKey.typeKey.type),
            )
            .apply {
              arguments[0] = tracerInstance
              arguments[1] = irString(contextualTypeKey.typeKey.toString())
              arguments[2] = accessTransformed
            }
        }
      } else {
        accessTransformed
      }

    // Convert provider if needed (e.g., Metro -> Dagger)
    val finalAccessType = if (requested == AccessType.PROVIDER) requested else actual
    return if (finalAccessType == AccessType.PROVIDER) {
      with(scope) {
        with(metroSymbols.providerTypeConverter) { maybeTraced.convertTo(contextualTypeKey) }
      }
    } else {
      maybeTraced
    }
  }

  context(scope: IrBuilderWithScope)
  abstract fun generateTracerBindingCode(): IrExpression

  context(scope: IrBuilderWithScope)
  protected fun IrExpression.wrapInInstanceFactory(
    type: IrType,
    allowPropertyGetter: Boolean = false,
  ): IrExpression {
    return with(scope) { instanceFactory(type, this@wrapInInstanceFactory, allowPropertyGetter) }
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

  context(scope: IrBuilderWithScope)
  protected fun maybeWrapInTracedProviderAndInvoke(
    directExpr: IrExpression,
    contextualTypeKey: IrContextualTypeKey,
  ): IrExpression {
    if (!options.enableRuntimeTracing || contextualTypeKey.typeKey.classId == metroSymbols.tracer.owner.classId) {
      return directExpr
    }

    return with(scope) {
      // Wrap the expression in the provider
      val lambdaProvider = wrapInProviderFunction(contextualTypeKey.typeKey.type) { directExpr }

      // Invoke the TracedProvider
      val tracerInstance = generateTracerBindingCode()
      val tracedProvider = irCallConstructor(
        metroSymbols.tracedProvider.owner.constructors.first { it.isPrimary }.symbol,
        listOf(contextualTypeKey.typeKey.type)
      ).apply {
        arguments[0] = tracerInstance
        arguments[1] = irString(contextualTypeKey.typeKey.toString())
        arguments[2] = lambdaProvider
      }
      tracedProvider.unwrapProvider(contextualTypeKey.typeKey.type)
    }
  }
}
