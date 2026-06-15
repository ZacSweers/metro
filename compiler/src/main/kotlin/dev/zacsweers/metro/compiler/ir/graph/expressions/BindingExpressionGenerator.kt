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
import dev.zacsweers.metro.compiler.symbols.Symbols
import dev.zacsweers.metro.compiler.tracing.TraceScope
import org.jetbrains.kotlin.ir.builders.IrBlockBodyBuilder
import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.builders.irCallConstructor
import org.jetbrains.kotlin.ir.builders.irNull
import org.jetbrains.kotlin.ir.builders.irReturn
import org.jetbrains.kotlin.ir.builders.irString
import org.jetbrains.kotlin.ir.builders.parent
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

    val traceContext =
      if (requested == AccessType.PROVIDER) traceContextFor(contextualTypeKey) else null
    val maybeTraced =
      if (traceContext == null) {
        accessTransformed
      } else {
        accessTransformed.wrapInTracedProvider(contextualTypeKey, traceContext) ?: accessTransformed
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

  /**
   * Returns the IR property access for the generated `metroTraceContext` property.
   *
   * `TracedProvider` construction uses this as its `traceContext` argument. Main graph generation
   * reads `this.metroTraceContext`; shard and switching-provider generation read the same property
   * through their graph reference. Returns `null` for bootstrap generators that run while
   * `metroTraceContext` itself is being initialized.
   */
  context(scope: IrBuilderWithScope)
  abstract fun generateTraceContextCode(): IrExpression?

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

  /**
   * Wraps a direct instance expression in a `MetroTraceContext.trace` call when tracing is enabled.
   *
   * This is only for code paths that already have a `T` expression from direct constructor or
   * provider-function invocation. Provider-valued access is decorated separately with
   * `TracedProvider`, so this avoids creating a temporary `Provider<T>` just to trace and invoke
   * it.
   *
   * Returns [directExpr] unchanged when this graph has no trace context or when [contextualTypeKey]
   * is part of Metro's tracing infrastructure.
   */
  context(scope: IrBuilderWithScope)
  protected fun maybeTraceDirectExpression(
    directExpr: IrExpression,
    contextualTypeKey: IrContextualTypeKey,
  ): IrExpression {
    val traceContext = traceContextFor(contextualTypeKey) ?: return directExpr
    val traceFunction = metroSymbols.metroTraceContextTrace ?: return directExpr
    val bindingType = contextualTypeKey.typeKey.type
    val traceName = contextualTypeKey.render(short = true, includeQualifier = true)
    val qualifierName = contextualTypeKey.typeKey.qualifier?.render(short = true)
    val bindingName = contextualTypeKey.render(short = true, includeQualifier = false)

    return with(scope) {
      val qualifierExpression =
        if (qualifierName == null) {
          irNull()
        } else {
          irString(qualifierName)
        }
      val traceBlock =
        irLambda(
          parent = parent,
          receiverParameter = null,
          valueParameters = emptyList(),
          returnType = bindingType,
          suspend = false,
        ) {
          +irReturn(directExpr)
        }
      irInvoke(
        dispatchReceiver = traceContext,
        callee = traceFunction,
        typeHint = bindingType,
        typeArgs = listOf(bindingType),
        args =
          listOf(
            // name
            irString(traceName),
            // qualifier
            qualifierExpression,
            // binding
            irString(bindingName),
            // kind
            irNull(),
            // block
            traceBlock,
          ),
      )
    }
  }

  context(scope: IrBuilderWithScope)
  private fun traceContextFor(contextualTypeKey: IrContextualTypeKey): IrExpression? {
    if (contextualTypeKey.isTracingInfrastructure) return null
    return generateTraceContextCode()
  }

  private val IrContextualTypeKey.isTracingInfrastructure: Boolean
    get() {
      val classId = typeKey.classId
      return classId == Symbols.ClassIds.tracer || classId == Symbols.ClassIds.metroTraceContext
    }

  context(scope: IrBuilderWithScope)
  private fun IrExpression.wrapInTracedProvider(
    contextualTypeKey: IrContextualTypeKey,
    traceContext: IrExpression,
  ): IrExpression? {
    val tracedProvider = metroSymbols.tracedProvider ?: return null
    return with(scope) {
      irCallConstructor(
          tracedProvider.constructors.first { it.owner.isPrimary },
          listOf(contextualTypeKey.typeKey.type),
        )
        .apply {
          // traceContext
          arguments[0] = traceContext
          // name
          arguments[1] = irString(contextualTypeKey.render(short = true, includeQualifier = true))
          // provider
          arguments[2] = this@wrapInTracedProvider
        }
    }
  }
}
