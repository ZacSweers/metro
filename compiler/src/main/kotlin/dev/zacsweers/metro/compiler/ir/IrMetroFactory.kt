// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import dev.zacsweers.metro.compiler.CompanionMode
import dev.zacsweers.metro.compiler.ir.parameters.Parameters
import dev.zacsweers.metro.compiler.ir.parameters.parameters
import dev.zacsweers.metro.compiler.ir.parameters.remapTypes as remapParameterTypes
import dev.zacsweers.metro.compiler.ir.parameters.wrapInProvider
import dev.zacsweers.metro.compiler.ir.transformers.factoryHelperDeclarationContainer
import dev.zacsweers.metro.compiler.memoize
import dev.zacsweers.metro.compiler.proto.SignatureCarrier
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
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.defaultType
import org.jetbrains.kotlin.ir.util.TypeRemapper
import org.jetbrains.kotlin.ir.util.classId
import org.jetbrains.kotlin.ir.util.deepCopyWithSymbols
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.ir.util.parentAsClass
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

  /** The class that declares [factoryClass]'s `create()` and `newInstance`-style helpers. */
  val helperOwner: IrClass

  fun supportsDirectInvocation(from: IrDeclarationWithVisibility): Boolean

  val createFunctionNames: Set<Name>
    get() = setOf(Symbols.Names.create)

  val isDaggerFactory: Boolean

  val creatorTypeArguments: List<IrType>?
    get() = null

  context(context: IrMetroContext, scope: IrBuilderWithScope)
  fun invokeCreateExpression(
    typeKey: IrTypeKey,
    computeArgs:
      IrBuilderWithScope.(createFunction: IrSimpleFunction, parameters: Parameters) -> List<
          IrExpression?,
        >,
  ): IrExpression {
    // A reified provider's factory can't call it, so the graph must never instantiate it.
    if (this is ProviderFactory.Metro && isReified) {
      reportCompilerBug(
        "Reified provider $callableId must be called directly for $typeKey. Its factory can't " +
          "call it and must never be instantiated.",
      )
    }
    val expr =
      invokeCreatorExpression(
        typeKey = typeKey,
        expectedCreatorDescription = Symbols.Names.create.asString(),
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
                context.metroSymbols.requireDaggerSymbols().jakartaSymbols.jakartaProvider,
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
          IrExpression?,
        >,
  ): IrExpression {
    return invokeCreatorExpression(
      typeKey = typeKey,
      expectedCreatorDescription = name.asString(),
      functionPredicate = { it.name == name },
      computeArgs = computeArgs,
    )
  }

  /**
   * Returns the arguments `create()` would receive for [typeKey], without calling it. They're the
   * dependency providers a factory instance holds.
   *
   * Some providers can't use their factory. A reified provider's factory can't be instantiated, for
   * example. The graph reads these providers itself so the provider it builds holds the same things a
   * factory would.
   */
  context(context: IrMetroContext, scope: IrBuilderWithScope)
  fun createArguments(
    typeKey: IrTypeKey,
    computeArgs:
      IrBuilderWithScope.(createFunction: IrSimpleFunction, parameters: Parameters) -> List<
          IrExpression?,
        >,
  ): List<IrExpression?> {
    val creator =
      resolveCreator(
        typeKey = typeKey,
        expectedCreatorDescription = Symbols.Names.create.asString(),
        functionPredicate = { it.name in createFunctionNames },
      )
    return scope.computeArgs(creator.remappedFunction, creator.parameters)
  }

  context(context: IrMetroContext, scope: IrBuilderWithScope)
  private fun invokeCreatorExpression(
    typeKey: IrTypeKey,
    expectedCreatorDescription: String,
    functionPredicate: (IrFunction) -> Boolean,
    computeArgs:
      IrBuilderWithScope.(targetFunction: IrSimpleFunction, parameters: Parameters) -> List<
          IrExpression?,
        >,
  ): IrExpression =
    with(scope) {
      val creator = resolveCreator(typeKey, expectedCreatorDescription, functionPredicate)
      val args = computeArgs(creator.remappedFunction, creator.parameters)
      return irInvoke(
        callee = creator.function.symbol,
        args = args,
        typeHint = creator.remappedFunction.returnType,
        typeArgs = creator.typeArguments,
      )
    }

  /**
   * Finds the creator function that [functionPredicate] accepts. It also remaps a copy of it to
   * this factory's type arguments, which is what callers read parameter types from.
   */
  context(context: IrMetroContext)
  private fun resolveCreator(
    typeKey: IrTypeKey,
    expectedCreatorDescription: String,
    functionPredicate: (IrFunction) -> Boolean,
  ): ResolvedCreator {
    val creatorClass = helperOwner
    // Companion-block helpers can share a name with instance members of the factory class.
    val createFunction =
      creatorClass.simpleFunctions().firstOrNull { it.isStaticIsh && functionPredicate(it) }
        ?: reportCompilerBug(
          "No matching creator function for '$expectedCreatorDescription' found in ${factoryClass.classId} with typeKey $typeKey. Available are ${creatorClass.simpleFunctions().joinToString { it.name.asString() }}",
        )

    val finalFunction =
      createFunction.deepCopyWithSymbols(initialParent = createFunction.parent).apply {
        parent = createFunction.parent
      }
    val factory = this@IrMetroFactory
    val concreteTypes = creatorTypeArguments
    val remapper =
      if (concreteTypes != null) {
        val isAppliedView =
          factory is ProviderFactory.Metro && factory.functionTypeArguments != null
        val sizesDiffer = concreteTypes.size != finalFunction.typeParameters.size
        if (isAppliedView && sizesDiffer) {
          reportCompilerBug(
            "Creator ${finalFunction.name} in ${factoryClass.classId} declares " +
              "${finalFunction.typeParameters.size} type parameters but got " +
              "${concreteTypes.size} type arguments",
          )
        }
        typeRemapperFor(concreteTypes, finalFunction)
      } else {
        // Inferring from the key only works when the creator's type parameters are the key's
        // type arguments. A generic provider's function type parameters can sit anywhere in it.
        val hasFunctionTypeParameters =
          factory is ProviderFactory.Metro && factory.functionTypeParameters.isNotEmpty()
        if (hasFunctionTypeParameters) {
          reportCompilerBug(
            "Generic provider factory ${factoryClass.classId} needs explicit creator type " +
              "arguments for $typeKey",
          )
        }
        finalFunction.typeRemapperFor(typeKey.type)
      }
    finalFunction.remapTypes(remapper)
    val typeArguments =
      finalFunction.typeParameters.map { typeParameter ->
        remapper.remapType(typeParameter.defaultType)
      }

    val parameters =
      if (isDaggerFactory) {
        // Dagger factories don't copy over qualifiers, so we wanna copy them over here
        val qualifiers = function.parameters.map { it.qualifierAnnotation() }
        finalFunction.parameters().overlayQualifiers(qualifiers)
      } else {
        finalFunction.parameters()
      }

    return ResolvedCreator(
      function = createFunction,
      remappedFunction = finalFunction,
      typeArguments = typeArguments,
      parameters = parameters,
    )
  }
}

/**
 * A factory's creator function, like `create()` or `newInstance()`. [function] is the declaration
 * to call. [remappedFunction] is a copy with the factory's type arguments applied, and [parameters]
 * are that copy's parameters. [typeArguments] are the type arguments for the call.
 */
private class ResolvedCreator(
  val function: IrSimpleFunction,
  val remappedFunction: IrSimpleFunction,
  val typeArguments: List<IrType>,
  val parameters: Parameters,
)

/** Uses the generated factory's dependency signature when the source has default parameters. */
context(context: IrMetroContext)
internal fun Parameters.withDaggerFactorySignature(
  factoryClass: IrClass,
  newInstanceName: Name,
): Parameters {
  val sourceFunction = checkNotNull(ir)
  if (sourceFunction.parameters.none { it.defaultValue != null }) {
    return this
  }

  val newInstanceFunction =
    factoryClass
      .requireStaticIshDeclarationContainer()
      .requireStaticIshFunction(newInstanceName.asString())
  val declaringType =
    if (sourceFunction is IrConstructor) {
      sourceFunction.returnType
    } else {
      dispatchReceiverParameter?.type ?: sourceFunction.parentAsClass.defaultType
    }
  val remapper = newInstanceFunction.typeRemapperFor(declaringType)
  val factoryParameters = newInstanceFunction.regularParameters
  // Generated provider methods put their module instance before the source parameters.
  val parameterOffset = factoryParameters.size - regularParameters.size
  val parameters = regularParameters.mapIndexed { index, parameter ->
    val factoryParameter = factoryParameters[index + parameterOffset]
    val factoryType = remapper.remapType(factoryParameter.type)
    val contextKey =
      factoryType.asContextualTypeKey(
        qualifierAnnotation = parameter.typeKey.qualifier,
        hasDefault = factoryParameter.hasMetroDefault(),
        patchMutableCollections = false,
        declaration = factoryParameter,
      )
    parameter.copy(contextualTypeKey = contextKey)
  }
  return copy(regularParameters = parameters)
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
    val signatureCarrier: SignatureCarrier,
    /** The producer's mode, which can differ from this compilation's mode. */
    private val companionMode: CompanionMode,
  ) : ClassFactory() {
    override val function: IrSimpleFunction = targetFunctionParameters.ir!! as IrSimpleFunction
    override val isDaggerFactory: Boolean = false

    override val helperOwner: IrClass by memoize {
      factoryClass.factoryHelperDeclarationContainer(companionMode)
    }

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

      val sourceClass = factoryClass.parentAsClass
      val concreteTypes = sourceClass.typeParameters.map { typeRemapper.remapType(it.defaultType) }
      val functionTypeRemapper =
        typeRemapperFor(
          concreteTypes,
          sourceClass,
          factoryClass,
          function,
        )
      val remappedParameters = targetFunctionParameters.remapParameterTypes(functionTypeRemapper)
      return MetroFactory(
        factoryClass,
        remappedParameters,
        targetConstructor,
        signatureCarrier,
        companionMode,
      )
    }
  }

  class DaggerFactory(
    private val metroContext: IrMetroContext,
    override val factoryClass: IrClass,
    override val targetConstructor: IrConstructor,
    constructorParameters: Parameters,
  ) : ClassFactory() {
    override val targetFunctionParameters: Parameters by memoize {
      context(metroContext) {
        constructorParameters.withDaggerFactorySignature(factoryClass, Symbols.Names.newInstance)
      }
    }

    override val function: IrConstructor = targetConstructor

    // Dagger and Anvil record no helper owner, so this reads the factory's shape.
    override val helperOwner: IrClass by memoize {
      factoryClass.requireStaticIshDeclarationContainer()
    }

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
