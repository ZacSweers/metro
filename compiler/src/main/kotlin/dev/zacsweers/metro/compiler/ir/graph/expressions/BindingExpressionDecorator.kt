// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.graph.expressions

import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.compiler.ir.IrContextualTypeKey
import dev.zacsweers.metro.compiler.ir.IrMetroContext
import dev.zacsweers.metro.compiler.ir.graph.isRuntimeTracingInfra
import dev.zacsweers.metro.compiler.ir.graph.runtimeTraceContextualType
import dev.zacsweers.metro.compiler.ir.graph.runtimeTraceQualifier
import dev.zacsweers.metro.compiler.ir.graph.runtimeTraceType
import dev.zacsweers.metro.compiler.ir.graph.sharding.ShardExpressionContext
import dev.zacsweers.metro.compiler.ir.irGetProperty
import dev.zacsweers.metro.compiler.ir.irInvoke
import dev.zacsweers.metro.compiler.ir.irLambda
import dev.zacsweers.metro.compiler.ir.stripProvider
import dev.zacsweers.metro.compiler.ir.wrapInProvider
import dev.zacsweers.metro.compiler.reportCompilerBug
import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.builders.irCallConstructor
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irNull
import org.jetbrains.kotlin.ir.builders.irReturn
import org.jetbrains.kotlin.ir.builders.irString
import org.jetbrains.kotlin.ir.builders.parent
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.util.constructors

/**
 * Adds optional behavior around the binding expressions produced by [BindingExpressionGenerator].
 *
 * Binding-specific code still creates the raw expression (constructor calls, provider function
 * calls, factory calls, scoped provider fields, and so on). This decorator is called only after
 * that expression has reached one of Metro's shared conversion points:
 *
 * - [BindingExpressionGenerator.maybeTraceDirectExpression] has a direct invocation expression for
 *   the requested binding, such as `RealService(...)` or `provideString()`.
 * - [BindingExpressionGenerator.toTargetType] has a provider-ish expression (a factory, an
 *   `InstanceFactory`, or provider property), before converting it back to the requested provider
 *   framework.
 *
 * Runtime tracing is implemented here so individual binding branches do not need tracing-specific
 * code.
 *
 * - Direct values become `MetroTraceContext.trace { value }`
 * - Newly-created provider values become `TracedProvider(...)` before any final
 *   Dagger/Javax/Jakarta/Guice provider conversion.
 * - Reads of generated provider properties are not wrapped again; those fields are expected to have
 *   been initialized with the traced provider already.
 */
internal interface BindingExpressionDecorator {
  /** Creates the graph-local decorator that can read graph-specific state such as trace context. */
  fun forGraph(scope: GraphBindingExpressionScope): GraphBindingExpressionDecorator

  /** No-op policy used when no expression-level feature needs to observe generated bindings. */
  object None : BindingExpressionDecorator {
    override fun forGraph(scope: GraphBindingExpressionScope): GraphBindingExpressionDecorator {
      return GraphBindingExpressionDecorator.None
    }
  }
}

/**
 * Creates runtime-tracing decorators for generated graph implementations.
 *
 * This object is compilation-scoped and intentionally does not know how to read a graph's
 * `MetroTraceContext`; each graph supplies that through [GraphBindingExpressionScope].
 */
@Inject
internal class RuntimeTracingBindingExpressionDecorator(private val context: IrMetroContext) :
  BindingExpressionDecorator {
  override fun forGraph(scope: GraphBindingExpressionScope): GraphBindingExpressionDecorator {
    return TraceExpressionDecorator(context, scope.traceContextAccessor)
  }
}

/** Per-graph inputs required to decorate expressions generated for that graph implementation. */
internal data class GraphBindingExpressionScope(val traceContextAccessor: TraceContextAccessor)

/** Provides the generated graph's `MetroTraceContext` expression, or `null` when none exists. */
internal fun interface TraceContextAccessor {
  context(scope: IrBuilderWithScope)
  fun traceContextExpression(): IrExpression?
}

/** Applies graph-local decoration after shared binding expression conversion points. */
internal interface GraphBindingExpressionDecorator {
  /**
   * Called by [BindingExpressionGenerator.maybeTraceDirectExpression] for direct values that will
   * be returned as instances, such as injected constructor calls, `@Provides` calls, and aggregate
   * multibinding getters.
   */
  context(scope: IrBuilderWithScope)
  fun decorateDirectExpression(
    expression: IrExpression,
    request: DirectExpressionRequest,
  ): IrExpression {
    return expression
  }

  /**
   * Called by [BindingExpressionGenerator.toTargetType] after Metro has produced provider-shaped
   * access and before the provider expression is converted to Dagger, Javax, Jakarta, Guice, or
   * function-provider types.
   */
  context(scope: IrBuilderWithScope)
  fun decorateProviderExpression(
    expression: IrExpression,
    request: ProviderExpressionRequest,
  ): IrExpression {
    return expression
  }

  object None : GraphBindingExpressionDecorator
}

/**
 * Describes the binding value represented by a direct expression.
 *
 * [contextualTypeKey] supplies the canonical trace type, the requested contextual type, and the
 * qualifier. [bindingKind] is the implementation kind, such as `Provided` or `ConstructorInjected`,
 * recorded as optional metadata.
 */
internal data class DirectExpressionRequest(
  val contextualTypeKey: IrContextualTypeKey,
  val bindingKind: String?,
)

/**
 * Describes the binding value represented by a provider expression.
 *
 * [origin] distinguishes newly-created providers from reads of cached provider properties, which
 * matters because generated provider properties are decorated when their field initializer is
 * emitted. Interop conversion still runs after decoration, so a Dagger/Javax/Jakarta/Guice wrapper
 * delegates to the traced Metro provider rather than bypassing tracing.
 */
internal data class ProviderExpressionRequest(
  val contextualTypeKey: IrContextualTypeKey,
  val bindingKind: String?,
  val origin: ProviderExpressionOrigin,
  val providerType: IrType? = null,
)

/**
 * Identifies how a provider expression was produced.
 *
 * Runtime tracing uses this to avoid wrapping a generated provider property a second time. The
 * provider property itself should still be traced; the field initializer is where that tracing
 * wrapper is emitted.
 */
internal enum class ProviderExpressionOrigin {
  /** A newly generated provider expression, such as a factory call or `InstanceFactory`. */
  NewExpression,
  /** A read of a stored provider property. */
  ProviderProperty,
}

/**
 * Reads the current graph's generated trace context property.
 *
 * Main graph code reads directly from `this`. Shard code reaches the graph through its shard
 * context, and switching-provider code may need one extra hop through the owning shard.
 */
internal class GraphTraceContextAccessor(
  context: IrMetroContext,
  private val thisReceiver: IrValueParameter,
  private val traceContextProperty: IrProperty?,
  private val shardContext: ShardExpressionContext?,
) : TraceContextAccessor, IrMetroContext by context {
  context(scope: IrBuilderWithScope)
  override fun traceContextExpression(): IrExpression? =
    with(scope) {
      val traceContextProperty = traceContextProperty ?: return null

      fun graphAccess(): IrExpression {
        val graphProperty =
          shardContext?.graphProperty
            ?: reportCompilerBug(
              "Shard ${shardContext?.currentShardIndex} requires graph access but has no graph property"
            )
        return irGetProperty(irGet(thisReceiver), graphProperty)
      }

      if (shardContext == null) {
        return@with irGetProperty(irGet(thisReceiver), traceContextProperty)
      }

      val graph =
        if (shardContext.isSwitchingProvider) {
          val base = graphAccess()
          shardContext.shardGraphProperty?.let { irGetProperty(base, it) } ?: base
        } else {
          graphAccess()
        }
      irGetProperty(graph, traceContextProperty)
    }
}

/** Adds Metro runtime tracing around generated direct and provider binding expressions. */
private class TraceExpressionDecorator(
  context: IrMetroContext,
  private val traceContextAccessor: TraceContextAccessor,
) : GraphBindingExpressionDecorator, IrMetroContext by context {
  context(scope: IrBuilderWithScope)
  override fun decorateDirectExpression(
    expression: IrExpression,
    request: DirectExpressionRequest,
  ): IrExpression {
    val traceContext = traceContextFor(request.contextualTypeKey) ?: return expression
    val traceFunction = metroSymbols.metroTraceContextTrace!!
    val bindingType = request.contextualTypeKey.typeKey.type
    val qualifier = request.contextualTypeKey.runtimeTraceQualifier()
    val type = request.contextualTypeKey.runtimeTraceType()
    val contextualType = request.contextualTypeKey.runtimeTraceContextualType()

    return with(scope) {
      val qualifierExpression = qualifier.toNullableStringExpression()
      val contextualTypeExpression = contextualType.toNullableStringExpression()
      val kindExpression = request.bindingKind.toNullableStringExpression()
      val traceBlock =
        irLambda(
          parent = parent,
          receiverParameter = null,
          valueParameters = emptyList(),
          returnType = bindingType,
          suspend = false,
        ) {
          +irReturn(expression)
        }
      irInvoke(
        dispatchReceiver = traceContext,
        callee = traceFunction,
        typeHint = bindingType,
        typeArgs = listOf(bindingType),
        args =
          listOf(
            // qualifier
            qualifierExpression,
            // type
            irString(type),
            // contextualType
            contextualTypeExpression,
            // kind
            kindExpression,
            // block
            traceBlock,
          ),
      )
    }
  }

  context(scope: IrBuilderWithScope)
  override fun decorateProviderExpression(
    expression: IrExpression,
    request: ProviderExpressionRequest,
  ): IrExpression {
    if (request.origin == ProviderExpressionOrigin.ProviderProperty) return expression
    val traceContext = traceContextFor(request.contextualTypeKey) ?: return expression
    return expression.toTracedMetroProvider(
      contextualTypeKey = request.contextualTypeKey,
      traceContext = traceContext,
      bindingKind = request.bindingKind,
      providerType = request.providerType,
    )
  }

  context(scope: IrBuilderWithScope)
  private fun traceContextFor(contextualTypeKey: IrContextualTypeKey): IrExpression? {
    if (contextualTypeKey.isRuntimeTracingInfra) return null
    return traceContextAccessor.traceContextExpression()
  }

  /**
   * Converts a provider-valued expression to Metro's `Provider` type before tracing it.
   *
   * `TracedProvider` delegates to Metro `Provider<T>`, so provider expressions from Dagger, Javax,
   * Jakarta, Guice, or function providers must be normalized before decoration. The regular
   * provider conversion path converts the traced Metro provider back to the requested provider or
   * lazy type afterward.
   */
  context(scope: IrBuilderWithScope)
  private fun IrExpression.toTracedMetroProvider(
    contextualTypeKey: IrContextualTypeKey,
    traceContext: IrExpression,
    bindingKind: String?,
    providerType: IrType?,
  ): IrExpression {
    val traceContextualTypeKey = contextualTypeKey.providerValueContextualTypeKey()
    val providerValueType = traceContextualTypeKey.toIrType()
    val canonicalTraceProviderTarget = traceContextualTypeKey.wrapInProvider()
    val metroProvider =
      with(scope) {
        with(metroSymbols.providerTypeConverter) {
          if (providerType == null) {
            this@toTracedMetroProvider.convertTo(canonicalTraceProviderTarget)
          } else {
            this@toTracedMetroProvider.convertTo(
              canonicalTraceProviderTarget,
              providerType = providerType,
            )
          }
        }
      }
    return metroProvider.wrapInTracedProvider(
      contextualTypeKey = traceContextualTypeKey,
      providerValueType = providerValueType,
      traceContext = traceContext,
      bindingKind = bindingKind,
    )
  }

  /** Returns the value key inside a provider request, preserving inner wrappers like `Lazy<T>`. */
  private fun IrContextualTypeKey.providerValueContextualTypeKey(): IrContextualTypeKey {
    return if (isWrappedInProvider) {
      stripProvider()
    } else {
      this
    }
  }

  context(scope: IrBuilderWithScope)
  private fun String?.toNullableStringExpression() =
    with(scope) {
      if (this@toNullableStringExpression == null) {
        irNull()
      } else {
        irString(this@toNullableStringExpression)
      }
    }

  context(scope: IrBuilderWithScope)
  private fun IrExpression.wrapInTracedProvider(
    contextualTypeKey: IrContextualTypeKey,
    providerValueType: IrType,
    traceContext: IrExpression,
    bindingKind: String?,
  ): IrExpression {
    val tracedProvider = metroSymbols.tracedProvider!!
    val qualifier = contextualTypeKey.runtimeTraceQualifier()
    val type = contextualTypeKey.runtimeTraceType()
    return with(scope) {
      val qualifierExpression = qualifier.toNullableStringExpression()
      val kindExpression = bindingKind.toNullableStringExpression()
      irCallConstructor(
          tracedProvider.constructors.first { it.owner.isPrimary },
          listOf(providerValueType),
        )
        .apply {
          // traceContext
          arguments[0] = traceContext
          // qualifier
          arguments[1] = qualifierExpression
          // type
          arguments[2] = irString(type)
          // kind
          arguments[3] = kindExpression
          // provider
          arguments[4] = this@wrapInTracedProvider
        }
    }
  }
}
