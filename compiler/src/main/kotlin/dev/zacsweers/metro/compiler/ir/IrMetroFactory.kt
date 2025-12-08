// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import dev.zacsweers.metro.compiler.ir.parameters.Parameters
import dev.zacsweers.metro.compiler.ir.parameters.parameters
import dev.zacsweers.metro.compiler.ir.parameters.wrapInProvider
import dev.zacsweers.metro.compiler.memoize
import dev.zacsweers.metro.compiler.reportCompilerBug
import dev.zacsweers.metro.compiler.symbols.DaggerSymbols
import dev.zacsweers.metro.compiler.symbols.Symbols
import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrDeclarationWithVisibility
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.symbols.IrFunctionSymbol
import org.jetbrains.kotlin.ir.util.TypeRemapper
import org.jetbrains.kotlin.ir.util.classId
import org.jetbrains.kotlin.ir.util.deepCopyWithSymbols
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.ir.util.remapTypes
import org.jetbrains.kotlin.ir.util.simpleFunctions
import org.jetbrains.kotlin.name.Name

internal sealed interface IrMetroFactory {
  val function: IrFunction

  /**
   * The real, non-synthetic function for invocation. Used when direct function calls are used, as
   * it may have extra metadata like JvmName annotations.
   */
  val realDeclaration: IrDeclaration?
  val factoryClass: IrClass

  fun supportsDirectInvocation(from: IrDeclarationWithVisibility): Boolean

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
  ): IrExpression {
    val expr =
      invokeCreatorExpression(
        typeKey = typeKey,
        functionPredicate = { it.name in createFunctionNames },
        computeArgs = computeArgs,
      )
    with(scope) {
      // Wrap in a metro provider if this is a provider
      return if (isDaggerFactory && factoryClass.defaultType.implementsProviderType()) {
        with(context.metroSymbols.providerTypeConverter) {
          val type = typeKey.type.wrapInProvider(context.metroSymbols.metroProvider)
          expr.convertTo(
            type.asContextualTypeKey(null, false, false, null),
            providerType =
              typeKey.type.wrapInProvider(
                context.metroSymbols.requireDaggerSymbols().jakartaSymbols.jakartaProvider
              ),
          )
        }
      } else {
        expr
      }
    }
  }

  context(context: IrMetroContext, scope: IrBuilderWithScope)
  fun invokeNewInstanceExpression(
    typeKey: IrTypeKey,
    name: Name,
    computeArgs:
      IrBuilderWithScope.(createFunction: IrSimpleFunction, parameters: Parameters) -> List<
          IrExpression?
        >,
  ): IrExpression {
    return invokeCreatorExpression(
      typeKey = typeKey,
      functionPredicate = { it.name == name },
      computeArgs = computeArgs,
    )
  }

  context(context: IrMetroContext, scope: IrBuilderWithScope)
  private fun invokeCreatorExpression(
    typeKey: IrTypeKey,
    functionPredicate: (IrFunction) -> Boolean,
    computeArgs:
      IrBuilderWithScope.(targetFunction: IrSimpleFunction, parameters: Parameters) -> List<
          IrExpression?
        >,
  ): IrExpression =
    with(scope) {
      // Anvil may generate the factory
      val creatorClass = factoryClass.requireStaticIshDeclarationContainer()
      val createFunction =
        creatorClass.simpleFunctions().firstOrNull(functionPredicate)
          ?: reportCompilerBug(
            "No matching creator function found for ${factoryClass.classId} with typeKey $typeKey. Available are ${creatorClass.simpleFunctions().joinToString { it.name.asString() }}"
          )

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
      return irInvoke(
        callee = createFunction.symbol,
        args = args,
        typeHint = finalFunction.returnType,
      )
    }
}

internal sealed class ClassFactory : IrMetroFactory {
  abstract val invokeFunctionSymbol: IrFunctionSymbol
  abstract val targetFunctionParameters: Parameters
  abstract val isAssistedInject: Boolean

  /**
   * The actual constructor to call for direct invocation. For MetroFactory, this is the injectable
   * constructor. For DaggerFactory, this is the [function] cast to IrConstructor.
   */
  abstract val targetConstructor: IrConstructor?

  override val realDeclaration: IrConstructor?
    get() = targetConstructor

  /**
   * Returns true if the constructor itself can be called directly (not via factory static method).
   * This requires the constructor to be public and accessible.
   */
  override fun supportsDirectInvocation(from: IrDeclarationWithVisibility): Boolean {
    return targetConstructor?.isVisibleTo(from) == true
  }

  context(context: IrMetroContext)
  abstract fun remapTypes(typeRemapper: TypeRemapper): ClassFactory

  class MetroFactory(
    override val factoryClass: IrClass,
    override val targetFunctionParameters: Parameters,
    override val targetConstructor: IrConstructor?,
  ) : ClassFactory() {
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
      return MetroFactory(factoryClass, newFunction.parameters(), targetConstructor)
    }
  }

  class DaggerFactory(
    private val metroContext: IrMetroContext,
    override val factoryClass: IrClass,
    override val targetConstructor: IrConstructor,
    override val targetFunctionParameters: Parameters,
  ) : ClassFactory() {
    override val function: IrConstructor = targetConstructor
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
        targetConstructor.deepCopyWithSymbols(factoryClass).also { it.remapTypes(typeRemapper) }
      return DaggerFactory(metroContext, factoryClass, targetConstructor, newFunction.parameters())
    }
  }
}
