package dev.zacsweers.metro.compiler.ir.graph.expressions

import dev.zacsweers.metro.compiler.ir.IrBinding
import dev.zacsweers.metro.compiler.ir.IrBindingGraph
import dev.zacsweers.metro.compiler.ir.IrContextualTypeKey
import dev.zacsweers.metro.compiler.ir.IrMetroContext
import dev.zacsweers.metro.compiler.ir.IrTypeKey
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
    contextualTypeKey: IrContextualTypeKey = binding.contextualTypeKey,
    accessType: AccessType =
      if (contextualTypeKey.requiresProviderInstance) {
        AccessType.PROVIDER
      } else {
        AccessType.INSTANCE
      },
    fieldInitKey: IrTypeKey? = null,
  ): IrExpression

  // TODO move transformMetroProvider into this too
  context(scope: IrBuilderWithScope)
  protected fun IrExpression.transformAccessIfNeeded(
    requested: AccessType,
    actual: AccessType,
    type: IrType,
    useInstanceFactory: Boolean = true,
  ): IrExpression {
    return when (requested) {
      actual -> this
      AccessType.PROVIDER -> {
        if (useInstanceFactory) {
          // actual is an instance, wrap it
          wrapInInstanceFactory(type)
        } else {
          scope.wrapInProviderFunction(type) { this@transformAccessIfNeeded }
        }
      }
      AccessType.INSTANCE -> {
        // actual is a provider but we want instance
        unwrapProvider(type)
      }
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