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
import dev.zacsweers.metro.compiler.ir.parameters.wrapInSuspendProvider
import dev.zacsweers.metro.compiler.tracing.TraceScope
import org.jetbrains.kotlin.ir.builders.IrBlockBodyBuilder
import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.builders.irCallConstructor
import org.jetbrains.kotlin.ir.builders.irReturn
import org.jetbrains.kotlin.ir.builders.parent
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.util.classId

internal abstract class BindingExpressionGenerator<T : IrBinding>(
  context: IrMetroContext,
  traceScope: TraceScope,
  internal val expressionDecorator: GraphBindingExpressionDecorator,
) : IrMetroContext by context, TraceScope by traceScope {
  abstract val thisReceiver: IrValueParameter
  abstract val bindingGraph: IrBindingGraph

  enum class AccessType {
    INSTANCE,
    // note: maybe rename this to PROVIDER_LIKE or PROVIDER_OR_FACTORY
    PROVIDER,
    SUSPEND_PROVIDER;

    companion object {
      fun of(contextKey: IrContextualTypeKey): AccessType {
        return when {
          contextKey.isWrappedInSuspendProvider -> SUSPEND_PROVIDER
          contextKey.isWrappedInProvider -> PROVIDER
          else -> INSTANCE
        }
      }
    }
  }

  context(scope: IrBuilderWithScope)
  abstract fun generateBindingCode(
    binding: T,
    contextualTypeKey: IrContextualTypeKey,
    accessType: AccessType =
      when {
        contextualTypeKey.isWrappedInSuspendProvider -> AccessType.SUSPEND_PROVIDER
        contextualTypeKey.requiresProviderInstance -> AccessType.PROVIDER
        else -> AccessType.INSTANCE
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
   * @param bindingKind Optional diagnostic label for the binding implementation that produced this
   *   expression. When present, it is attached as tracing metadata.
   * @param providerOrigin Where a provider-valued result came from. Decorators can use this to
   *   avoid repeating work that happened while a stored provider property was initialized.
   */
  context(scope: IrBuilderWithScope)
  protected fun IrExpression.toTargetType(
    contextualTypeKey: IrContextualTypeKey,
    actual: AccessType = run {
      val classId = type.classOrNull?.owner?.classId
      when {
        classId in metroSymbols.suspendProviderTypes -> AccessType.SUSPEND_PROVIDER
        classId in metroSymbols.providerTypes || classId in metroSymbols.lazyTypes ->
          AccessType.PROVIDER
        else -> AccessType.INSTANCE
      }
    },
    requested: AccessType =
      when {
        contextualTypeKey.isWrappedInSuspendProvider -> AccessType.SUSPEND_PROVIDER
        contextualTypeKey.requiresProviderInstance -> AccessType.PROVIDER
        else -> AccessType.INSTANCE
      },
    useInstanceFactory: Boolean = true,
    allowPropertyGetter: Boolean = false,
    bindingKind: String? = null,
    providerOrigin: ProviderExpressionOrigin = ProviderExpressionOrigin.NewExpression,
    providerType: IrType? = null,
  ): IrExpression {
    // Step 1: Transform access type
    val accessTransformed =
      when (requested) {
        actual -> this
        PROVIDER -> {
          check(actual == INSTANCE) { "Unsupported access type conversion: $actual -> $requested" }
          if (useInstanceFactory) {
            // actual is an instance, wrap it
            wrapInInstanceFactory(contextualTypeKey.typeKey.type, allowPropertyGetter)
          } else {
            scope.wrapInProviderFunction(contextualTypeKey.typeKey.type) {
              this@toTargetType
            }
          }
        }
        SUSPEND_PROVIDER -> {
          when (actual) {
            INSTANCE -> {
              // Wrap instance expression in a suspend provider lambda
              scope.wrapInSuspendProviderFunction(contextualTypeKey.typeKey.type) {
                this@toTargetType
              }
            }
            PROVIDER -> {
              // Adapt Provider<T> -> SuspendProvider<T> via SyncSuspendProvider intrinsic
              // (allocation-free typed view; avoids a captured suspend lambda).
              with(scope) { wrapInSyncSuspendProvider(contextualTypeKey.typeKey.type) }
            }
            else -> this
          }
        }
        INSTANCE -> {
          when (actual) {
            PROVIDER -> unwrapProvider(contextualTypeKey.typeKey.type)
            SUSPEND_PROVIDER -> unwrapSuspendProvider(contextualTypeKey.typeKey.type)
            else -> this
          }
        }
      }

    // Step 2: Convert provider if needed (e.g., Metro -> Dagger)
    // Only do this if we're in PROVIDER mode (or transformed to it)
    // SuspendProvider doesn't need framework conversion (no Dagger/Javax equivalent) but does get
    // its own decoration hook (like TracedSuspendProvider when runtime tracing is enabled).
    val maybeTraced =
      when (requested) {
        AccessType.PROVIDER ->
          expressionDecorator.decorateProviderExpression(
            accessTransformed,
            ProviderExpressionRequest(
              contextualTypeKey = contextualTypeKey,
              bindingKind = bindingKind,
              origin = providerOrigin,
              providerType = providerType,
            ),
          )
        AccessType.SUSPEND_PROVIDER ->
          expressionDecorator.decorateSuspendProviderExpression(
            accessTransformed,
            ProviderExpressionRequest(
              contextualTypeKey = contextualTypeKey,
              bindingKind = bindingKind,
              origin = providerOrigin,
            ),
          )
        else -> accessTransformed
      }

    val finalAccessType = if (requested == AccessType.PROVIDER) requested else actual
    return if (finalAccessType == AccessType.PROVIDER) {
      // NOTE: SUSPEND_PROVIDER results are deliberately NOT converted here. These expressions
      // also initialize graph fields typed SuspendProvider<T>; converting to the requested
      // `suspend () -> T` function type at this layer produces type-mismatched field
      // initializers (invalid IR on JS). The function-type adaptation happens at the consumer
      // boundary in typeAsProviderArgument instead.
      with(scope) {
        with(metroSymbols.providerTypeConverter) {
          if (providerType == null) {
            maybeTraced.convertTo(contextualTypeKey)
          } else {
            maybeTraced.convertTo(contextualTypeKey, providerType = providerType)
          }
        }
      }
    } else {
      maybeTraced
    }
  }

  /**
   * Resolves the user-provided AndroidX `Tracer` binding through the normal binding graph.
   *
   * Metro uses this to initialize the generated `metroTraceContext` property. It does not
   * synthesize a tracer binding, so callers should only invoke this after
   * [runtimeTracingAvailable][dev.zacsweers.metro.compiler.ir.runtimeTracingAvailable] has
   * confirmed that tracing can be generated.
   */
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

  protected fun IrBuilderWithScope.wrapInSuspendProviderFunction(
    type: IrType,
    returnExpression: IrBlockBodyBuilder.(function: IrSimpleFunction) -> IrExpression,
  ): IrExpression {
    val lambda =
      irLambda(parent = this.parent, receiverParameter = null, emptyList(), type, suspend = true) {
        +irReturn(returnExpression(it))
      }
    return irInvoke(
      dispatchReceiver = null,
      callee = metroSymbols.metroSuspendProviderFunction,
      typeHint = type.wrapInSuspendProvider(),
      typeArgs = listOf(type),
      args = listOf(lambda),
    )
  }

  /**
   * Wraps a `Provider<T>` expression as a `SuspendProvider<T>` via the `SyncSuspendProvider`
   * value-class intrinsic. Allocation-free at runtime and avoids the captured-lambda allocation of
   * [wrapInSuspendProviderFunction] for the common Provider→SuspendProvider adaption.
   */
  context(scope: IrBuilderWithScope)
  protected fun IrExpression.wrapInSyncSuspendProvider(type: IrType): IrExpression {
    val provider = this
    return with(scope) {
      irCallConstructor(metroSymbols.metroSyncSuspendProviderConstructor, listOf(type)).apply {
        arguments[0] = provider
      }
    }
  }

  context(scope: IrBuilderWithScope)
  protected fun IrExpression.unwrapProvider(type: IrType): IrExpression {
    return with(scope) {
      irInvoke(this@unwrapProvider, callee = metroSymbols.providerInvoke, typeHint = type)
    }
  }

  context(scope: IrBuilderWithScope)
  protected fun IrExpression.unwrapSuspendProvider(type: IrType): IrExpression {
    return with(scope) {
      irInvoke(
        this@unwrapSuspendProvider,
        callee = metroSymbols.suspendProviderInvoke,
        typeHint = type,
      )
    }
  }

  /**
   * Wraps a direct instance expression in a `MetroTraceContext.trace` call when tracing is enabled.
   *
   * This is only for code paths that already have a `T` expression from direct constructor or
   * provider-function invocation. Provider-valued access is decorated separately with
   * `TracedProvider`, so this avoids creating a temporary `Provider<T>` just to trace and invoke
   * it.
   *
   * Returns [directExpr] unchanged when this graph's expression decorators do not need to observe
   * the direct value.
   */
  context(scope: IrBuilderWithScope)
  protected fun maybeTraceDirectExpression(
    directExpr: IrExpression,
    contextualTypeKey: IrContextualTypeKey,
    bindingKind: String?,
    /** True when [directExpr] contains suspend calls, so the trace wrapper must be suspend. */
    isSuspend: Boolean = false,
  ): IrExpression {
    return expressionDecorator.decorateDirectExpression(
      directExpr,
      DirectExpressionRequest(
        contextualTypeKey = contextualTypeKey,
        bindingKind = bindingKind,
        isSuspend = isSuspend,
      ),
    )
  }
}
