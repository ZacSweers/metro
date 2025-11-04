// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import dev.zacsweers.metro.compiler.ir.parameters.Parameters
import dev.zacsweers.metro.compiler.ir.parameters.parameters
import dev.zacsweers.metro.compiler.memoize
import dev.zacsweers.metro.compiler.symbols.DaggerSymbols
import dev.zacsweers.metro.compiler.symbols.Symbols
import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.symbols.IrFunctionSymbol
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.TypeRemapper
import org.jetbrains.kotlin.ir.util.deepCopyWithSymbols
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.ir.util.remapTypes
import org.jetbrains.kotlin.ir.util.simpleFunctions
import org.jetbrains.kotlin.name.Name

internal sealed interface IrMetroFactory {
  val function: IrFunction
  val factoryClass: IrClass

  val createFunctionNames: Set<Name>
    get() = setOf(Symbols.Names.create)

  val isDaggerFactory: Boolean

  context(context: IrMetroContext, scope: IrBuilderWithScope)
  fun invokeCreateExpression(
    typeKey: IrTypeKey,
    computeArgs:
      IrBuilderWithScope.(createFunction: IrSimpleFunction, parameters: Parameters) -> List<
          IrExpression?
        >,
  ): IrExpression =
    with(scope) {
      // Anvil may generate the factory
      val creatorClass = factoryClass.requireStaticIshDeclarationContainer()
      val createFunction = creatorClass.simpleFunctions().first { it.name in createFunctionNames }

      val remapper = createFunction.typeRemapperFor(typeKey.type)
      val finalFunction =
        createFunction.deepCopyWithSymbols(initialParent = createFunction.parent).also {
          it.parent = createFunction.parent
          it.remapTypes(remapper)
        }

      val parameters =
        if (isDaggerFactory) {
          // Dagger factories don't copy over qualifiers, so we wanna copy them over here
          val qualifiers = function.parameters.map { it.qualifierAnnotation() }
          createFunction.parameters(remapper).overlayQualifiers(qualifiers)
        } else {
          createFunction.parameters(remapper)
        }

      val args = computeArgs(finalFunction, parameters)
      val createExpression =
        irInvoke(callee = createFunction.symbol, args = args, typeHint = factoryClass.typeWith())

      // Wrap in a metro provider if this is a provider
      return if (isDaggerFactory && factoryClass.defaultType.implementsProviderType()) {
        irInvoke(
            extensionReceiver = createExpression,
            callee = context.metroSymbols.requireDaggerSymbols().asMetroProvider,
          )
          .apply { typeArguments[0] = factoryClass.typeWith() }
      } else {
        createExpression
      }
    }
}

internal sealed interface ClassFactory : IrMetroFactory {
  val invokeFunctionSymbol: IrFunctionSymbol
  val targetFunctionParameters: Parameters
  val isAssistedInject: Boolean

  context(context: IrMetroContext)
  fun remapTypes(typeRemapper: TypeRemapper): ClassFactory

  class MetroFactory(
    override val factoryClass: IrClass,
    override val targetFunctionParameters: Parameters,
  ) : ClassFactory {
    override val function: IrSimpleFunction = targetFunctionParameters.ir!! as IrSimpleFunction
    override val isDaggerFactory: Boolean = false

    override val isAssistedInject: Boolean by memoize {
      // Check if the factory has the @AssistedMarker annotation
      factoryClass.hasAnnotation(Symbols.ClassIds.metroAssistedMarker)
    }

    override val invokeFunctionSymbol: IrFunctionSymbol by memoize {
      factoryClass.requireSimpleFunction(Symbols.StringNames.INVOKE)
    }

    context(context: IrMetroContext)
    override fun remapTypes(typeRemapper: TypeRemapper): MetroFactory {
      if (factoryClass.typeParameters.isEmpty()) return this

      // TODO can we pass the remapper in?
      val newFunction =
        function.deepCopyWithSymbols(factoryClass).also { it.remapTypes(typeRemapper) }
      return MetroFactory(factoryClass, newFunction.parameters())
    }
  }

  class DaggerFactory(
    private val metroContext: IrMetroContext,
    override val factoryClass: IrClass,
    override val targetFunctionParameters: Parameters,
  ) : ClassFactory {
    override val function: IrConstructor = targetFunctionParameters.ir!! as IrConstructor
    override val createFunctionNames: Set<Name> =
      setOf(Symbols.Names.create, Symbols.Names.createFactoryProvider)
    override val isAssistedInject: Boolean by memoize {
      // Check if the constructor has an @AssistedInject annotation
      function.hasAnnotation(DaggerSymbols.ClassIds.DAGGER_ASSISTED_INJECT)
    }

    override val invokeFunctionSymbol: IrFunctionSymbol
      get() = factoryClass.requireSimpleFunction(Symbols.StringNames.GET)

    override val isDaggerFactory: Boolean = true

    context(context: IrMetroContext)
    override fun remapTypes(typeRemapper: TypeRemapper): DaggerFactory {
      if (factoryClass.typeParameters.isEmpty()) return this

      // TODO can we pass the remapper in?
      val newFunction =
        function.deepCopyWithSymbols(factoryClass).also { it.remapTypes(typeRemapper) }
      return DaggerFactory(metroContext, factoryClass, newFunction.parameters())
    }
  }
}
