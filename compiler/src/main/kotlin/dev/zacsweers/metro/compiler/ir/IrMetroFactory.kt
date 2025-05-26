// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import dev.zacsweers.metro.compiler.MetroAnnotations
import dev.zacsweers.metro.compiler.Symbols
import dev.zacsweers.metro.compiler.asName
import dev.zacsweers.metro.compiler.ir.IrAnnotation
import dev.zacsweers.metro.compiler.ir.IrMetroContext
import dev.zacsweers.metro.compiler.ir.IrTypeKey
import dev.zacsweers.metro.compiler.ir.getConstBooleanArgumentOrNull
import dev.zacsweers.metro.compiler.ir.implementsProviderType
import dev.zacsweers.metro.compiler.ir.irInvoke
import dev.zacsweers.metro.compiler.ir.parameters.ConstructorParameter
import dev.zacsweers.metro.compiler.ir.parameters.Parameters
import dev.zacsweers.metro.compiler.ir.parameters.parameters
import dev.zacsweers.metro.compiler.ir.requireSimpleFunction
import dev.zacsweers.metro.compiler.metroAnnotations
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
import org.jetbrains.kotlin.ir.util.classId
import org.jetbrains.kotlin.ir.util.classIdOrFail
import org.jetbrains.kotlin.ir.util.companionObject
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.getAnnotation
import org.jetbrains.kotlin.ir.util.getAnnotationStringValue
import org.jetbrains.kotlin.ir.util.isFromJava
import org.jetbrains.kotlin.ir.util.isObject
import org.jetbrains.kotlin.ir.util.simpleFunctions
import org.jetbrains.kotlin.name.CallableId

internal sealed interface IrMetroFactory {
  val function: IrFunction
}

internal sealed interface ClassFactory : IrMetroFactory {
  val factoryClass: IrClass
  val invokeFunctionSymbol: IrFunctionSymbol
  val targetFunctionParameters: Parameters<ConstructorParameter>

  fun IrBuilderWithScope.invokeCreateExpression(
    computeArgs: IrBuilderWithScope.(createFunction: IrSimpleFunction) -> List<IrExpression?>
  ): IrExpression

  class MetroFactory(
    override val factoryClass: IrClass,
    override val targetFunctionParameters: Parameters<ConstructorParameter>,
  ) : ClassFactory {
    override val function: IrSimpleFunction = targetFunctionParameters.ir!! as IrSimpleFunction

    override val invokeFunctionSymbol: IrFunctionSymbol by unsafeLazy {
      factoryClass.requireSimpleFunction(Symbols.StringNames.INVOKE)
    }

    override fun IrBuilderWithScope.invokeCreateExpression(
      computeArgs: IrBuilderWithScope.(IrSimpleFunction) -> List<IrExpression?>
    ): IrExpression {
      // Invoke its factory's create() function
      val creatorClass =
        if (factoryClass.isObject) {
          factoryClass
        } else {
          factoryClass.companionObject()!!
        }
      val createFunction = creatorClass.requireSimpleFunction(Symbols.StringNames.CREATE)
      val args = computeArgs(createFunction.owner)
      return irInvoke(
        dispatchReceiver = irGetObject(creatorClass.symbol),
        callee = createFunction,
        args = args,
        typeHint = factoryClass.typeWith(),
      )
    }
  }

  class DaggerFactory(
    private val metroContext: IrMetroContext,
    override val factoryClass: IrClass,
    override val targetFunctionParameters: Parameters<ConstructorParameter>,
  ) : ClassFactory {
    override val function: IrConstructor = targetFunctionParameters.ir!! as IrConstructor
    override val invokeFunctionSymbol: IrFunctionSymbol
      get() = factoryClass.requireSimpleFunction(Symbols.StringNames.GET)

    override fun IrBuilderWithScope.invokeCreateExpression(
      computeArgs: IrBuilderWithScope.(createFunction: IrSimpleFunction) -> List<IrExpression?>
    ): IrExpression {
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
            it.name == Symbols.Names.create || it.name == Symbols.Names.createFactoryProvider
          }
          .symbol
      val args = computeArgs(createFunction.owner)
      val createExpression =
        irInvoke(
          dispatchReceiver = if (isJava) null else irGetObject(creatorClass.symbol),
          callee = createFunction,
          args = args,
          typeHint = factoryClass.typeWith(),
        )

      // Wrap in a metro provider if this is a provider
      return if (factoryClass.defaultType.implementsProviderType(metroContext)) {
        irInvoke(
            extensionReceiver = createExpression,
            callee = metroContext.symbols.daggerSymbols.asMetroProvider,
          )
          .apply { putTypeArgument(0, factoryClass.typeWith()) }
      } else {
        createExpression
      }
    }
  }
}

internal class ProviderFactory(
  val context: IrMetroContext,
  sourceTypeKey: IrTypeKey,
  val clazz: IrClass,
  sourceCallable: IrSimpleFunction?,
  sourceAnnotations: MetroAnnotations<IrAnnotation>?,
) : IrMetroFactory {
  val callableId: CallableId
  override val function: IrSimpleFunction
  val annotations: MetroAnnotations<IrAnnotation>
  val typeKey: IrTypeKey
  val isPropertyAccessor: Boolean

  init {
    val providesCallableIdAnno =
      clazz.getAnnotation(Symbols.FqNames.ProvidesCallableIdClass)
        ?: error(
          "No @ProvidesCallableId found on class ${clazz.classId}. This is a bug in the Metro compiler."
        )
    val callableName = providesCallableIdAnno.getAnnotationStringValue("callableName")
    callableId = CallableId(clazz.classIdOrFail.parentClassId!!, callableName.asName())
    isPropertyAccessor =
      providesCallableIdAnno.getConstBooleanArgumentOrNull(
        Symbols.StringNames.IS_PROPERTY_ACCESSOR.asName()
      ) ?: false
    function =
      sourceCallable
        ?: context.pluginContext.referenceFunctions(callableId).firstOrNull()?.owner
        ?: error("No matching provider function found for $callableId")
    annotations = sourceAnnotations ?: function.metroAnnotations(context.symbols.classIds)
    typeKey = sourceTypeKey.copy(qualifier = annotations.qualifier)
  }

  val parameters by unsafeLazy { function.parameters(context) }
}