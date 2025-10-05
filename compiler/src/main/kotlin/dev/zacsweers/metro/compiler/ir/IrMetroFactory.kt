// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import dev.zacsweers.metro.compiler.MetroAnnotations
import dev.zacsweers.metro.compiler.Symbols
import dev.zacsweers.metro.compiler.Symbols.DaggerSymbols
import dev.zacsweers.metro.compiler.ir.parameters.Parameters
import dev.zacsweers.metro.compiler.ir.parameters.parameters
import dev.zacsweers.metro.compiler.unsafeLazy
import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.builders.irGetObject
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.symbols.IrFunctionSymbol
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.TypeRemapper
import org.jetbrains.kotlin.ir.util.companionObject
import org.jetbrains.kotlin.ir.util.deepCopyWithSymbols
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.ir.util.isFromJava
import org.jetbrains.kotlin.ir.util.isObject
import org.jetbrains.kotlin.ir.util.remapTypes
import org.jetbrains.kotlin.ir.util.simpleFunctions
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.Name

internal sealed interface IrMetroFactory {
  val function: IrFunction
  val factoryClass: IrClass

  val createFunctionNames: Set<Name> get() = setOf(
    Symbols.Names.create
  )

  val isDaggerFactory: Boolean

  context(context: IrMetroContext, scope: IrBuilderWithScope)
  fun invokeCreateExpression(
    typeKey: IrTypeKey,
    computeArgs: IrBuilderWithScope.(createFunction: IrSimpleFunction, parameters: Parameters) -> List<IrExpression?>
  ): IrExpression = with(scope) {
    // Anvil may generate the factory
    val isJava = factoryClass.isFromJava()
    val creatorClass =
      if (isJava || factoryClass.isObject) {
        factoryClass
      } else {
        factoryClass.companionObject()!!
      }
    val createFunction =
      creatorClass
        .simpleFunctions()
        .first {
          it.name in createFunctionNames
        }

    val remapper = createFunction.typeRemapperFor(typeKey.type)
    val finalFunction = createFunction.deepCopyWithSymbols(initialParent = createFunction.parent).also {
      it.parent = createFunction.parent
      it.remapTypes(remapper)
    }

    val parameters = if (isDaggerFactory) {
      // Dagger factories don't copy over qualifiers, so we wanna copy them over here
      val qualifiers = function.parameters.map { it.qualifierAnnotation() }
      createFunction.parameters(remapper)
        .overlayQualifiers(qualifiers)
    } else {
      createFunction.parameters(remapper)
    }

    val args = computeArgs(finalFunction, parameters)
    val createExpression =
      irInvoke(
        dispatchReceiver = if (isJava) null else irGetObject(creatorClass.symbol),
        callee = createFunction.symbol,
        args = args,
        typeHint = factoryClass.typeWith(),
      )

    // Wrap in a metro provider if this is a provider
    return if (isDaggerFactory && factoryClass.defaultType.implementsProviderType()) {
      irInvoke(
        extensionReceiver = createExpression,
        callee = context.metroSymbols.daggerSymbols.asMetroProvider,
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

    override val isAssistedInject: Boolean by unsafeLazy {
      // Check if the factory has the @AssistedMarker annotation
      factoryClass.hasAnnotation(Symbols.ClassIds.metroAssistedMarker)
    }

    override val invokeFunctionSymbol: IrFunctionSymbol by unsafeLazy {
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
    override val createFunctionNames: Set<Name> = setOf(
      Symbols.Names.create, Symbols.Names.createFactoryProvider
    )
    override val isAssistedInject: Boolean by unsafeLazy {
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

internal sealed interface ProviderFactory : IrMetroFactory {
  val typeKey: IrTypeKey
  val callableId: CallableId
  val annotations: MetroAnnotations<IrAnnotation>
  val parameters: Parameters
  val isPropertyAccessor: Boolean
  override val function: IrSimpleFunction

  class Metro(
    override val factoryClass: IrClass,
    override val typeKey: IrTypeKey,
    private val callableMetadata: IrCallableMetadata,
    parametersLazy: Lazy<Parameters>,
  ) : ProviderFactory {
    val mirrorFunction: IrSimpleFunction
      get() = callableMetadata.mirrorFunction

    override val callableId: CallableId
      get() = callableMetadata.callableId

    override val function: IrSimpleFunction
      get() = callableMetadata.function

    override val annotations: MetroAnnotations<IrAnnotation>
      get() = callableMetadata.annotations

    override val isPropertyAccessor: Boolean
      get() = callableMetadata.isPropertyAccessor

    override val parameters by parametersLazy

    override val isDaggerFactory: Boolean = false
  }

  class Dagger(
    override val factoryClass: IrClass,
    override val typeKey: IrTypeKey,
    override val callableId: CallableId,
    override val annotations: MetroAnnotations<IrAnnotation>,
    override val parameters: Parameters,
    override val function: IrSimpleFunction,
    override val isPropertyAccessor: Boolean
  ) : ProviderFactory {
    override val isDaggerFactory: Boolean = true
  }

  companion object {
    context(context: IrMetroContext)
    operator fun invoke(
      sourceTypeKey: IrTypeKey,
      clazz: IrClass,
      mirrorFunction: IrSimpleFunction,
      sourceAnnotations: MetroAnnotations<IrAnnotation>?,
    ): Metro {
      val callableMetadata = clazz.irCallableMetadata(mirrorFunction, sourceAnnotations, isInterop = false)
      val typeKey = sourceTypeKey.copy(qualifier = callableMetadata.annotations.qualifier)

      return Metro(
        factoryClass = clazz,
        typeKey = typeKey,
        callableMetadata = callableMetadata,
        parametersLazy = unsafeLazy { callableMetadata.function.parameters() },
      )
    }
  }
}
