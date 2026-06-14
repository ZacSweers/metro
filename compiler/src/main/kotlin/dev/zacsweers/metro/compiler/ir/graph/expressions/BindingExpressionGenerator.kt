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
import dev.zacsweers.metro.compiler.ir.supportsTracing
import dev.zacsweers.metro.compiler.symbols.Symbols
import dev.zacsweers.metro.compiler.tracing.TraceScope
import org.jetbrains.kotlin.ir.builders.IrBlockBodyBuilder
import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.builders.irCallConstructor
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
  abstract val traceGraphName: String
  abstract val traceGraphPath: String

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

    val maybeTraced =
      if (shouldTraceProvider(contextualTypeKey, requested)) {
        accessTransformed.wrapInTracedProvider(contextualTypeKey) ?: accessTransformed
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

  /**
   * Emits IR for the AndroidX tracer value used by runtime tracing.
   *
   * This should resolve a normal graph binding supplied by user code; Metro does not synthesize a
   * tracer binding. Callers should only invoke this after [tracingAvailable] has confirmed the
   * tracing symbols are present.
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
    if (!shouldTraceDirectExpression(contextualTypeKey)) {
      return directExpr
    }

    return with(scope) {
      val lambdaProvider = wrapInProviderFunction(contextualTypeKey.typeKey.type) { directExpr }
      val tracedProvider =
        lambdaProvider.wrapInTracedProvider(contextualTypeKey) ?: return directExpr
      tracedProvider.unwrapProvider(contextualTypeKey.typeKey.type)
    }
  }

  private fun shouldTraceProvider(
    contextualTypeKey: IrContextualTypeKey,
    requested: AccessType,
  ): Boolean {
    val providerRequested = requested == AccessType.PROVIDER
    if (!providerRequested) return false
    if (!tracingAvailable()) return false
    return !contextualTypeKey.isTracingInfrastructure
  }

  private fun shouldTraceDirectExpression(contextualTypeKey: IrContextualTypeKey): Boolean {
    if (!tracingAvailable()) return false
    return !contextualTypeKey.isTracingInfrastructure
  }

  private fun tracingAvailable(): Boolean {
    if (!options.enableRuntimeTracing) return false
    if (!platform.supportsTracing()) return false
    if (metroSymbols.tracer == null) return false
    if (metroSymbols.metroTraceContext == null) return false
    if (metroSymbols.tracedProvider == null) return false
    return true
  }

  private val IrContextualTypeKey.isTracingInfrastructure: Boolean
    get() {
      val classId = typeKey.classId
      return classId == Symbols.ClassIds.tracer || classId == Symbols.ClassIds.metroTraceContext
    }

  context(scope: IrBuilderWithScope)
  private fun IrExpression.wrapInTracedProvider(
    contextualTypeKey: IrContextualTypeKey
  ): IrExpression? {
    val tracedProvider = metroSymbols.tracedProvider ?: return null
    val traceContext = generateTraceContextCode() ?: return null
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

  /**
   * Emits a temporary `MetroTraceContext` instance for traced provider wrappers.
   *
   * TODO replace this per-use construction with a generated graph field. For now, this keeps the
   * cleanup focused while still passing graph metadata into the runtime helper.
   */
  context(scope: IrBuilderWithScope)
  private fun generateTraceContextCode(): IrExpression? {
    val metroTraceContext = metroSymbols.metroTraceContext ?: return null
    return with(scope) {
      irCallConstructor(metroTraceContext.constructors.first { it.owner.isPrimary }, emptyList())
        .apply {
          // tracer
          arguments[0] = generateTracerBindingCode()
          // category
          arguments[1] = irString("dev.zacsweers.metro")
          // graphName
          arguments[2] = irString(traceGraphName)
          // graphPath
          arguments[3] = irString(traceGraphPath)
        }
    }
  }
}
