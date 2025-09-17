// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.transformers

import dev.zacsweers.metro.compiler.Origins
import dev.zacsweers.metro.compiler.Symbols
import dev.zacsweers.metro.compiler.asName
import dev.zacsweers.metro.compiler.fir.MetroDiagnostics
import dev.zacsweers.metro.compiler.generatedClass
import dev.zacsweers.metro.compiler.ir.IrMetroContext
import dev.zacsweers.metro.compiler.ir.assignConstructorParamsToFields
import dev.zacsweers.metro.compiler.ir.createIrBuilder
import dev.zacsweers.metro.compiler.ir.finalizeFakeOverride
import dev.zacsweers.metro.compiler.ir.findInjectableConstructor
import dev.zacsweers.metro.compiler.ir.generateDefaultConstructorBody
import dev.zacsweers.metro.compiler.ir.instanceFactory
import dev.zacsweers.metro.compiler.ir.irExprBodySafe
import dev.zacsweers.metro.compiler.ir.irInvoke
import dev.zacsweers.metro.compiler.ir.isAnnotatedWithAny
import dev.zacsweers.metro.compiler.ir.isExternalParent
import dev.zacsweers.metro.compiler.ir.parameters.Parameter
import dev.zacsweers.metro.compiler.ir.parameters.Parameter.AssistedParameterKey.Companion.toAssistedParameterKey
import dev.zacsweers.metro.compiler.ir.parameters.parameters
import dev.zacsweers.metro.compiler.ir.rawType
import dev.zacsweers.metro.compiler.ir.regularParameters
import dev.zacsweers.metro.compiler.ir.reportCompat
import dev.zacsweers.metro.compiler.ir.setDispatchReceiver
import dev.zacsweers.metro.compiler.ir.singleAbstractFunction
import dev.zacsweers.metro.compiler.ir.thisReceiverOrFail
import dev.zacsweers.metro.compiler.ir.transformers.AssistedFactoryTransformer.AssistedFactoryFunction.Companion.toAssistedFactoryFunction
import dev.zacsweers.metro.compiler.ir.typeRemapperFor
import dev.zacsweers.metro.compiler.reportCompilerBug
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.builders.declarations.addConstructor
import org.jetbrains.kotlin.ir.builders.declarations.addFunction
import org.jetbrains.kotlin.ir.builders.declarations.addValueParameter
import org.jetbrains.kotlin.ir.builders.declarations.buildClass
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irGetField
import org.jetbrains.kotlin.ir.builders.irGetObject
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.symbols.IrTypeParameterSymbol
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.IrTypeProjection
import org.jetbrains.kotlin.ir.types.defaultType
import org.jetbrains.kotlin.ir.types.typeOrFail
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.TypeRemapper
import org.jetbrains.kotlin.ir.util.addChild
import org.jetbrains.kotlin.ir.util.addFakeOverrides
import org.jetbrains.kotlin.ir.util.classId
import org.jetbrains.kotlin.ir.util.classIdOrFail
import org.jetbrains.kotlin.ir.util.copyTo
import org.jetbrains.kotlin.ir.util.copyTypeParametersFrom
import org.jetbrains.kotlin.ir.util.createThisReceiverParameter
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.functions
import org.jetbrains.kotlin.ir.util.isFromJava
import org.jetbrains.kotlin.ir.util.isStatic
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.util.parentAsClass
import org.jetbrains.kotlin.ir.util.primaryConstructor
import org.jetbrains.kotlin.ir.util.simpleFunctions
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.SpecialNames

internal class AssistedFactoryTransformer(
  context: IrMetroContext,
  private val injectConstructorTransformer: InjectConstructorTransformer,
) : IrMetroContext by context {

  private val implsCache = mutableMapOf<ClassId, AssistedFactoryImpl>()

  fun visitClass(declaration: IrClass) {
    val isAssistedFactory = declaration.isAnnotatedWithAny(symbols.assistedFactoryAnnotations)
    if (isAssistedFactory) {
      getOrGenerateImplClass(declaration)
    }
  }

  internal fun getOrGenerateImplClass(declaration: IrClass): AssistedFactoryImpl {
    val classId: ClassId = declaration.classIdOrFail
    implsCache[classId]?.let {
      return it
    }

    val isExternal = declaration.isExternalParent

    // Check for Dagger interop first for external declarations
    if (isExternal && options.enableDaggerRuntimeInterop) {
      if (declaration.isFromJava()) {
        val daggerImplClassId = classId.generatedClass("_Impl")
        val daggerImplClass = pluginContext.referenceClass(daggerImplClassId)?.owner
        if (daggerImplClass != null) {
          val daggerImpl = AssistedFactoryImpl.Dagger(daggerImplClass)
          implsCache[classId] = daggerImpl
          return daggerImpl
        } else {
          reportCompat(
            declaration,
            MetroDiagnostics.METRO_ERROR,
            "Could not find Dagger impl for external factory class ${classId.asFqNameString()}",
          )
        }
      }
    }

    // Find the SAM function - for external use metadata as hint, for in-compilation get directly
    val samFunction = declaration.singleAbstractFunction()

    // Generate impl class header (same for both external and in-compilation)
    val implClass = generateImplClassHeader(declaration, isExternal)

    val returnType = samFunction.returnType
    val targetType = returnType.rawType()

    // Always generate companion + create() stub (for both external and in-compilation)
    val companionDeclarations =
      generateCompanionDeclarations(implClass, declaration, targetType, isExternal, samFunction)

    val implementation =
      if (isExternal) {
        // For external declarations, generate stubs only (no bodies, no constructor)
        AssistedFactoryImpl.Metro(companionDeclarations.createFunction)
      } else {
        // For in-compilation, add constructor and implement bodies
        val injectConstructor =
          targetType.findInjectableConstructor(onlyUsePrimaryConstructor = false)!!

        // Add constructor
        implClass
          .addConstructor {
            visibility = DescriptorVisibilities.PRIVATE
            isPrimary = true
          }
          .apply {
            val factoryClassId =
              targetType.classIdOrFail.createNestedClassId(Symbols.Names.MetroFactory)
            val factoryParamType = pluginContext.referenceClass(factoryClassId)!!.defaultType
            addValueParameter(Symbols.Names.delegateFactory, factoryParamType)
            body = generateDefaultConstructorBody()
          }

        // Implement the body in a second pass since we need the full structure
        implementImplClass(
          implClass,
          declaration,
          samFunction,
          targetType,
          injectConstructor,
          companionDeclarations,
        )

        AssistedFactoryImpl.Metro(companionDeclarations.createFunction)
      }

    implsCache[classId] = implementation
    return implementation
  }

  private fun generateImplClassHeader(declaration: IrClass, isExternal: Boolean): IrClass {
    val implClass =
      pluginContext.irFactory
        .buildClass {
          name = Symbols.Names.MetroImpl
          kind = ClassKind.CLASS
          visibility = DescriptorVisibilities.PUBLIC
        }
        .apply {
          superTypes = listOf(declaration.defaultType)
          typeParameters = copyTypeParametersFrom(declaration)
          createThisReceiverParameter()
          // Only add as child for in-compilation, not for external
          if (!isExternal) {
            declaration.addChild(this)
            addFakeOverrides(irTypeSystemContext)
          } else {
            parent = declaration
          }
        }
    return implClass
  }

  /** Data class to model the components of the generated companion object */
  data class ImplCompanionDeclarations(
    val companion: IrClass,
    val createFunction: IrSimpleFunction,
  )

  private fun generateCompanionDeclarations(
    implClass: IrClass,
    declaration: IrClass,
    targetType: IrClass,
    isExternal: Boolean,
    samFunction: IrSimpleFunction,
  ): ImplCompanionDeclarations {
    val companion =
      pluginContext.irFactory
        .buildClass {
          name = SpecialNames.DEFAULT_NAME_FOR_COMPANION_OBJECT
          kind = ClassKind.OBJECT
          visibility = DescriptorVisibilities.PUBLIC
          origin = Origins.Default
          isCompanion = true
        }
        .apply {
          createThisReceiverParameter()
          implClass.addChild(this)
        }

    val companionReceiver = companion.thisReceiverOrFail

    companion
      .addConstructor {
        visibility = DescriptorVisibilities.PRIVATE
        isPrimary = true
        origin = Origins.Default
      }
      .apply {
        if (!isExternal) {
          body = generateDefaultConstructorBody()
        }
      }

    // Add create function to companion
    val createFunction =
      companion
        .addFunction {
          name = Symbols.StringNames.CREATE.asName()
          visibility = DescriptorVisibilities.PUBLIC
          modality = Modality.FINAL
          origin = Origins.Default
          returnType = symbols.metroProvider.typeWith(declaration.defaultType)
        }
        .apply {
          setDispatchReceiver(companionReceiver.copyTo(this))
          typeParameters = copyTypeParametersFrom(samFunction)

          val factoryClassId =
            targetType.classIdOrFail.createNestedClassId(Symbols.Names.MetroFactory)
          val factoryParamType = pluginContext.referenceClass(factoryClassId)!!.defaultType
          addValueParameter(Symbols.Names.delegateFactory, factoryParamType)

          // Body will be implemented in implementImplClass
        }

    return ImplCompanionDeclarations(companion, createFunction)
  }

  private fun implementImplClass(
    implClass: IrClass,
    declaration: IrClass,
    samFunction: IrSimpleFunction,
    targetType: IrClass,
    injectConstructor: IrConstructor,
    companionDeclarations: ImplCompanionDeclarations,
  ) {
    // Get the SAM function from the impl class (it's a fake override)
    val implSamFunction =
      implClass.functions.first {
        it.isFakeOverride &&
          it.name == samFunction.name &&
          it.parameters.size == samFunction.parameters.size &&
          it.overriddenSymbols.contains(samFunction.symbol)
      }

    val returnType = implSamFunction.returnType

    // Extract type substitutions from the factory's type args and SAM return type
    val typeSubstitutions = mutableMapOf<IrTypeParameterSymbol, IrType>()
    if (returnType is IrSimpleType && returnType.arguments.isNotEmpty()) {
      // Map constructor type parameters to concrete types
      targetType.typeParameters.zip(returnType.arguments).forEach { (param, arg) ->
        if (arg is IrTypeProjection) {
          typeSubstitutions[param.symbol] = arg.type
        }
      }

      // Also map factory type parameters to the same concrete types
      implClass.typeParameters.zip(returnType.arguments).forEach { (factoryParam, arg) ->
        if (arg is IrTypeProjection) {
          typeSubstitutions[factoryParam.symbol] = arg.type
        }
      }
    }

    val remapper = typeRemapperFor(typeSubstitutions)

    val creatorFunction = samFunction.toAssistedFactoryFunction(implSamFunction, remapper)

    val generatedFactory =
      injectConstructorTransformer.getOrGenerateFactory(
        targetType,
        injectConstructor,
        doNotErrorOnMissing = false,
      ) ?: return

    val constructorParams = injectConstructor.parameters()
    val assistedParameters =
      constructorParams.regularParameters.filter { parameter -> parameter.isAssisted }

    // Apply substitutions when creating assisted parameter keys
    val assistedParameterKeys =
      assistedParameters.map { parameter ->
        val substitutedTypeKey = parameter.typeKey.remapTypes(remapper)
        parameter
          .copy(contextualTypeKey = parameter.contextualTypeKey.withTypeKey(substitutedTypeKey))
          .assistedParameterKey
      }

    val ctor = implClass.primaryConstructor!!
    val delegateFactoryField = assignConstructorParamsToFields(ctor, implClass).values.single()

    implSamFunction.apply {
      finalizeFakeOverride(implClass.thisReceiverOrFail)
      val functionParams =
        regularParameters.zip(creatorFunction.parameterKeys).associate { (valueParam, paramKey) ->
          paramKey to valueParam
        }
      body =
        pluginContext.createIrBuilder(symbol).run {
          // We call the @Inject constructor. Therefore, find for each assisted
          // parameter the function parameter where the keys match.
          val argumentList =
            assistedParameterKeys.map { assistedParameterKey ->
              val param =
                functionParams[assistedParameterKey]
                  ?: reportCompilerBug(
                    "Could not find matching parameter for $assistedParameterKey on constructor for ${implClass.classId}.\n\nAvailable keys are\n${
                        functionParams.keys.joinToString(
                          "\n"
                        )
                      }"
                  )
              irGet(param)
            }

          irExprBodySafe(
            symbol,
            irInvoke(
              dispatchReceiver =
                irGetField(irGet(dispatchReceiverParameter!!), delegateFactoryField),
              callee = generatedFactory.invokeFunctionSymbol,
              args = argumentList,
            ),
          )
        }
    }

    companionDeclarations.createFunction.apply {
      val factoryParam = regularParameters.single()
      // InstanceFactory(Impl(delegateFactory))
      body =
        pluginContext.createIrBuilder(symbol).run {
          irExprBodySafe(
            symbol,
            instanceFactory(
              declaration.typeWith(),
              irInvoke(callee = ctor.symbol, args = listOf(irGet(factoryParam))),
            ),
          )
        }
    }

    implClass.dumpToMetroLog()
  }

  /** Represents a parsed function in an `@AssistedFactory`-annotated interface. */
  private data class AssistedFactoryFunction(
    val simpleName: String,
    val qualifiedName: String,
    val returnType: IrType,
    val originalFunction: IrSimpleFunction,
    val parameterKeys: List<Parameter.AssistedParameterKey>,
  ) {

    companion object {
      context(context: IrMetroContext)
      fun IrSimpleFunction.toAssistedFactoryFunction(
        originalDeclaration: IrSimpleFunction,
        remapper: TypeRemapper? = null,
      ): AssistedFactoryFunction {
        val params = parameters()
        return AssistedFactoryFunction(
          simpleName = originalDeclaration.name.asString(),
          qualifiedName = originalDeclaration.kotlinFqName.asString(),
          returnType = returnType,
          originalFunction = originalDeclaration,
          parameterKeys =
            originalDeclaration.regularParameters.mapIndexed { index, param ->
              val baseTypeKey = params.regularParameters[index].typeKey
              val substitutedTypeKey = remapper?.let { baseTypeKey.remapTypes(it) } ?: baseTypeKey
              param.toAssistedParameterKey(context.symbols, substitutedTypeKey)
            },
        )
      }
    }
  }
}

/** Interface for assisted factory implementations (Metro or Dagger) */
internal sealed interface AssistedFactoryImpl {
  /** Invoke the create method with the given delegate factory provider */
  context(context: IrMetroContext)
  fun IrBuilderWithScope.invokeCreate(delegateFactoryProvider: IrExpression): IrExpression

  /** Metro implementation of AssistedFactoryHandler */
  class Metro(private val createFunction: IrSimpleFunction) : AssistedFactoryImpl {

    context(context: IrMetroContext)
    override fun IrBuilderWithScope.invokeCreate(
      delegateFactoryProvider: IrExpression
    ): IrExpression {
      return irInvoke(
        dispatchReceiver = irGetObject(createFunction.parentAsClass.symbol),
        callee = createFunction.symbol,
        args = listOf(delegateFactoryProvider),
        typeHint = createFunction.returnType,
      )
    }
  }

  /** Dagger implementation of AssistedFactoryHandler */
  class Dagger(daggerImplClass: IrClass) : AssistedFactoryImpl {
    // For Dagger, we need to call the static create method directly
    private val createFunction =
      daggerImplClass.simpleFunctions().first {
        it.isStatic &&
          (it.name == Symbols.Names.create || it.name == Symbols.Names.createFactoryProvider)
      }

    context(context: IrMetroContext)
    override fun IrBuilderWithScope.invokeCreate(
      delegateFactoryProvider: IrExpression
    ): IrExpression {
      return with(context.symbols.daggerSymbols) {
        val targetType = (createFunction.returnType as IrSimpleType).arguments[0].typeOrFail
        transformToMetroProvider(
          irInvoke(
            callee = createFunction.symbol,
            args = listOf(delegateFactoryProvider),
            typeHint = createFunction.returnType,
          ),
          targetType,
        )
      }
    }
  }
}
