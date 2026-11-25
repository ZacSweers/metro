// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import dev.zacsweers.metro.compiler.symbols.Symbols
import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.IrTypeProjection
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.simpleFunctions

/** Interface for assisted factory implementations (Metro or Dagger) */
internal sealed interface AssistedFactoryImpl {
  /** Invoke the create method with the given delegate factory provider */
  context(context: IrMetroContext)
  fun IrBuilderWithScope.invokeCreate(
    delegateFactory: IrExpression,
    factoryType: IrType,
  ): IrExpression

  /** Metro implementation of AssistedFactoryHandler */
  class Metro(private val createFunction: IrSimpleFunction) : AssistedFactoryImpl {

    context(context: IrMetroContext)
    override fun IrBuilderWithScope.invokeCreate(
      delegateFactory: IrExpression,
      factoryType: IrType,
    ): IrExpression {
      val typeArguments =
        (factoryType as? IrSimpleType)?.arguments.orEmpty().map { argument ->
          (argument as IrTypeProjection).type
        }
      return irInvoke(
        dispatchReceiver = dispatchReceiverFor(createFunction),
        callee = createFunction.symbol,
        args = listOf(delegateFactory),
        typeHint = context.metroSymbols.metroProvider.typeWith(factoryType),
        typeArgs = typeArguments,
      )
    }
  }

  /** Dagger implementation of AssistedFactoryHandler */
  class Dagger(daggerImplClass: IrClass) : AssistedFactoryImpl {
    // For Dagger, we need to call the static create method directly
    private val createFunction by irLazy {
      daggerImplClass.requireStaticIshDeclarationContainer().simpleFunctions().first {
        it.isStaticIsh &&
          (it.name == Symbols.Names.create || it.name == Symbols.Names.createFactoryProvider)
      }
    }

    context(context: IrMetroContext)
    override fun IrBuilderWithScope.invokeCreate(
      delegateFactory: IrExpression,
      factoryType: IrType,
    ): IrExpression {
      return with(context.metroSymbols.providerTypeConverter) {
        val targetType = IrContextualTypeKey.from(createFunction)
        irInvoke(
            callee = createFunction.symbol,
            args = listOf(delegateFactory),
            typeHint = createFunction.returnType,
          )
          .convertTo(targetType.wrapInProvider())
      }
    }
  }
}
