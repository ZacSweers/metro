// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.transformers

import dev.zacsweers.metro.compiler.Origins
import dev.zacsweers.metro.compiler.Symbols
import dev.zacsweers.metro.compiler.generatedClass
import dev.zacsweers.metro.compiler.ir.IrContextualTypeKey
import dev.zacsweers.metro.compiler.ir.IrMetroContext
import dev.zacsweers.metro.compiler.ir.assignConstructorParamsToFields
import dev.zacsweers.metro.compiler.ir.createIrBuilder
import dev.zacsweers.metro.compiler.ir.finalizeFakeOverride
import dev.zacsweers.metro.compiler.ir.irExprBodySafe
import dev.zacsweers.metro.compiler.ir.irInvoke
import dev.zacsweers.metro.compiler.ir.isAnnotatedWithAny
import dev.zacsweers.metro.compiler.ir.isExternalParent
import dev.zacsweers.metro.compiler.ir.parameters.Parameter
import dev.zacsweers.metro.compiler.ir.parameters.Parameter.AssistedParameterKey.Companion.toAssistedParameterKey
import dev.zacsweers.metro.compiler.ir.parameters.parameters
import dev.zacsweers.metro.compiler.ir.rawType
import dev.zacsweers.metro.compiler.ir.regularParameters
import dev.zacsweers.metro.compiler.ir.requireSimpleFunction
import dev.zacsweers.metro.compiler.ir.thisReceiverOrFail
import dev.zacsweers.metro.compiler.ir.transformers.AssistedFactoryTransformer.AssistedFactoryFunction.Companion.toAssistedFactoryFunction
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irGetField
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.classId
import org.jetbrains.kotlin.ir.util.classIdOrFail
import org.jetbrains.kotlin.ir.util.companionObject
import org.jetbrains.kotlin.ir.util.functions
import org.jetbrains.kotlin.ir.util.isFakeOverriddenFromAny
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.util.nestedClasses
import org.jetbrains.kotlin.ir.util.primaryConstructor
import org.jetbrains.kotlin.name.ClassId

internal class AssistedFactoryTransformer(
  context: IrMetroContext,
  private val injectConstructorTransformer: InjectConstructorTransformer,
) : IrMetroContext by context {

  private val generatedImpls = mutableMapOf<ClassId, IrClass>()

  fun visitClass(declaration: IrClass) {
    val isAssistedFactory = declaration.isAnnotatedWithAny(symbols.assistedFactoryAnnotations)
    if (isAssistedFactory) {
      getOrGenerateImplClass(declaration)
    }
  }

  internal fun getOrGenerateImplClass(declaration: IrClass): IrClass? {
    // TODO if declaration is external to this compilation, look
    //  up its factory or warn if it doesn't exist
    val classId: ClassId = declaration.classIdOrFail
    generatedImpls[classId]?.let {
      return it
    }

    val isExternal = declaration.isExternalParent

    val implClass =
      declaration.nestedClasses.singleOrNull {
        val isMetroImpl = it.name == Symbols.Names.MetroImpl
        // If not external, double check its origin
        if (isMetroImpl && !isExternal) {
          if (it.origin != Origins.AssistedFactoryImplClassDeclaration) {
            declaration.reportError(
              "Found a Metro assisted factory impl declaration in ${declaration.kotlinFqName} but with an unexpected origin ${it.origin}"
            )
            return null
          }
        }
        isMetroImpl
      }

    if (implClass == null) {
      if (isExternal) {
        if (options.enableDaggerRuntimeInterop) {
          // Look up where dagger would generate one
          val daggerImplClassId = classId.generatedClass("_Impl")
          val daggerImplClass = pluginContext.referenceClass(daggerImplClassId)?.owner
          if (daggerImplClass != null) {
            generatedImpls[classId] = daggerImplClass
            return daggerImplClass
          }
        }
        declaration.reportError(
          "Could not find generated assisted factory impl for '${declaration.kotlinFqName}' in upstream module where it's defined. Run the Metro compiler over that module too."
        )
        return null
      } else {
        error(
          "No expected assisted factory impl class generated for '${declaration.kotlinFqName}'. Report this bug with a repro case at https://github.com/zacsweers/metro/issues/new"
        )
      }
    }

    if (isExternal) {
      generatedImpls[classId] = implClass
      return implClass
    }

    val creatorFunction =
      implClass.functions
        .filter { it.modality == Modality.ABSTRACT }
        .single { it.isFakeOverride && !it.isFakeOverriddenFromAny() }
        .let { function -> function.toAssistedFactoryFunction(this, function) }

    val returnType = creatorFunction.returnType
    val targetType = returnType.rawType()
    val injectConstructor =
      targetType.findInjectableConstructor(onlyUsePrimaryConstructor = false)!!

    val generatedFactory =
      injectConstructorTransformer.getOrGenerateFactory(targetType, injectConstructor)
        ?: return null

    val constructorParams = injectConstructor.parameters(this)
    val assistedParameters =
      constructorParams.regularParameters.filter { parameter -> parameter.isAssisted }
    val assistedParameterKeys =
      assistedParameters.map { parameter -> parameter.assistedParameterKey }

    val ctor = implClass.primaryConstructor!!
    implClass.apply {
      val delegateFactoryField = assignConstructorParamsToFields(ctor, implClass).values.single()

      creatorFunction.originalFunction.apply {
        finalizeFakeOverride(implClass.thisReceiverOrFail)
        val functionParams =
          regularParameters.associateBy { valueParam ->
            val key = IrContextualTypeKey.from(metroContext, valueParam).typeKey
            valueParam.toAssistedParameterKey(symbols, key)
          }
        body =
          pluginContext.createIrBuilder(symbol).run {
            // We call the @Inject constructor. Therefore, find for each assisted
            // parameter the function parameter where the keys match.
            val argumentList =
              assistedParameterKeys.map { assistedParameterKey ->
                val param =
                  functionParams[assistedParameterKey]
                    ?: error(
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
    }

    val companion = implClass.companionObject()!!
    companion.requireSimpleFunction(Symbols.StringNames.CREATE).owner.apply {
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

    generatedImpls[classId] = implClass

    return implClass
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
      fun IrSimpleFunction.toAssistedFactoryFunction(
        context: IrMetroContext,
        originalDeclaration: IrSimpleFunction,
      ): AssistedFactoryFunction {
        val params = parameters(context)
        return AssistedFactoryFunction(
          simpleName = originalDeclaration.name.asString(),
          qualifiedName = originalDeclaration.kotlinFqName.asString(),
          // TODO FIR validate return type is a graph
          returnType = returnType,
          originalFunction = originalDeclaration,
          parameterKeys =
            originalDeclaration.regularParameters.mapIndexed { index, param ->
              param.toAssistedParameterKey(context.symbols, params.regularParameters[index].typeKey)
            },
        )
      }
    }
  }
}
