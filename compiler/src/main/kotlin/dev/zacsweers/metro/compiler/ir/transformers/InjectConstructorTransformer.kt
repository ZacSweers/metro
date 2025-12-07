// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.transformers

import dev.zacsweers.metro.compiler.Origins
import dev.zacsweers.metro.compiler.asName
import dev.zacsweers.metro.compiler.fir.MetroDiagnostics
import dev.zacsweers.metro.compiler.generatedClass
import dev.zacsweers.metro.compiler.ir.ClassFactory
import dev.zacsweers.metro.compiler.ir.IrMetroContext
import dev.zacsweers.metro.compiler.ir.assignConstructorParamsToFields
import dev.zacsweers.metro.compiler.ir.contextParameters
import dev.zacsweers.metro.compiler.ir.copyParameterDefaultValues
import dev.zacsweers.metro.compiler.ir.createIrBuilder
import dev.zacsweers.metro.compiler.ir.dispatchReceiverFor
import dev.zacsweers.metro.compiler.ir.finalizeFakeOverride
import dev.zacsweers.metro.compiler.ir.findInjectableConstructor
import dev.zacsweers.metro.compiler.ir.irExprBodySafe
import dev.zacsweers.metro.compiler.ir.irInvoke
import dev.zacsweers.metro.compiler.ir.irTemporary
import dev.zacsweers.metro.compiler.ir.isExternalParent
import dev.zacsweers.metro.compiler.ir.metroAnnotationsOf
import dev.zacsweers.metro.compiler.ir.metroMetadata
import dev.zacsweers.metro.compiler.ir.parameters.Parameter
import dev.zacsweers.metro.compiler.ir.parameters.Parameters
import dev.zacsweers.metro.compiler.ir.parameters.parameters
import dev.zacsweers.metro.compiler.ir.parametersAsProviderArguments
import dev.zacsweers.metro.compiler.ir.regularParameters
import dev.zacsweers.metro.compiler.ir.reportCompat
import dev.zacsweers.metro.compiler.ir.requireSimpleFunction
import dev.zacsweers.metro.compiler.ir.thisReceiverOrFail
import dev.zacsweers.metro.compiler.ir.trackFunctionCall
import dev.zacsweers.metro.compiler.ir.typeAsProviderArgument
import dev.zacsweers.metro.compiler.proto.InjectConstructorFactoryProto
import dev.zacsweers.metro.compiler.proto.MetroMetadata
import dev.zacsweers.metro.compiler.reportCompilerBug
import dev.zacsweers.metro.compiler.symbols.Symbols
import java.util.Optional
import kotlin.jvm.optionals.getOrNull
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.builders.irCallConstructor
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irGetField
import org.jetbrains.kotlin.ir.builders.irGetObject
import org.jetbrains.kotlin.ir.builders.irReturn
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.symbols.IrConstructorSymbol
import org.jetbrains.kotlin.ir.types.defaultType
import org.jetbrains.kotlin.ir.util.callableId
import org.jetbrains.kotlin.ir.util.classIdOrFail
import org.jetbrains.kotlin.ir.util.companionObject
import org.jetbrains.kotlin.ir.util.file
import org.jetbrains.kotlin.ir.util.functions
import org.jetbrains.kotlin.ir.util.getAnnotation
import org.jetbrains.kotlin.ir.util.getAnnotationStringValue
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.util.nestedClasses
import org.jetbrains.kotlin.ir.util.nonDispatchParameters
import org.jetbrains.kotlin.ir.util.packageFqName
import org.jetbrains.kotlin.ir.util.parentAsClass
import org.jetbrains.kotlin.ir.util.primaryConstructor
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId

internal class InjectConstructorTransformer(
  context: IrMetroContext,
  private val membersInjectorTransformer: MembersInjectorTransformer,
) : IrMetroContext by context {

  private val generatedFactories = mutableMapOf<ClassId, Optional<ClassFactory>>()

  fun visitClass(declaration: IrClass) {
    val injectableConstructor =
      declaration.findInjectableConstructor(onlyUsePrimaryConstructor = false)
    if (injectableConstructor != null) {
      getOrGenerateFactory(declaration, injectableConstructor, doNotErrorOnMissing = false)
    }
  }

  fun getOrGenerateFactory(
    declaration: IrClass,
    previouslyFoundConstructor: IrConstructor?,
    doNotErrorOnMissing: Boolean,
  ): ClassFactory? {
    val injectedClassId: ClassId = declaration.classIdOrFail
    generatedFactories[injectedClassId]?.getOrNull()?.let {
      return it
    }

    val isExternal = declaration.isExternalParent

    fun targetConstructor(): IrConstructor? {
      return previouslyFoundConstructor
        ?: declaration.findInjectableConstructor(onlyUsePrimaryConstructor = false)
    }

    if (isExternal) {
      // For external: read class name from metadata and match by name
      val metadata = declaration.metroMetadata?.inject_constructor_factory

      fun reportAndReturn(): ClassFactory? {
        val message = buildString {
          append(
            "Could not find generated factory for '${declaration.kotlinFqName}' in the upstream module where it's defined. "
          )
          append("Run the Metro compiler over that module too")
          if (options.enableDaggerRuntimeInterop) {
            append(" (or Dagger if you're using its interop)")
          }
          appendLine(".")
        }
        reportCompat(declaration, MetroDiagnostics.METRO_ERROR, message)
        return null
      }

      return when {
        metadata != null -> {
          val factoryClassName = metadata.factory_class_name.asName()
          val factoryCls =
            declaration.nestedClasses.singleOrNull { it.name == factoryClassName }
              ?: reportCompilerBug(
                "Expected nested class '$factoryClassName' not found in '${declaration.kotlinFqName}'."
              )
          val parameters =
            factoryCls.requireSimpleFunction(Symbols.StringNames.MIRROR_FUNCTION).owner.parameters()
          // Look up the injectable constructor for direct invocation optimization
          val externalTargetConstructor = targetConstructor()
          val wrapper = ClassFactory.MetroFactory(factoryCls, parameters, externalTargetConstructor)
          // If it's from another module, we're done!
          // TODO this doesn't work as expected in KMP, where things compiled in common are seen
          //  as external but no factory is found?
          generatedFactories[injectedClassId] = Optional.of(wrapper)
          wrapper
        }

        options.enableDaggerRuntimeInterop -> {
          val targetConstructor =
            targetConstructor()
              // Not injectable if we reach here
              // TODO is it an error if we ever hit this?
              ?: return null
          // Look up where dagger would generate one
          val daggerFactoryClassId = injectedClassId.generatedClass("_Factory")
          val daggerFactoryClass = pluginContext.referenceClass(daggerFactoryClassId)?.owner
          if (daggerFactoryClass != null) {
            val wrapper =
              ClassFactory.DaggerFactory(
                metroContext,
                daggerFactoryClass,
                targetConstructor,
                targetConstructor.parameters(),
              )
            generatedFactories[injectedClassId] = Optional.of(wrapper)
            wrapper
          } else {
            reportAndReturn()
          }
        }

        doNotErrorOnMissing -> {
          // Store an empty here because it's absent
          generatedFactories[injectedClassId] = Optional.empty()
          null
        }

        else -> {
          reportAndReturn()
        }
      }
    }

    // For in-compilation: match by FIR-generated origin (metadata not written yet)
    val targetConstructor =
      targetConstructor()
        // Not injectable if we reach here
        ?: return null

    val factoryCls =
      declaration.nestedClasses.singleOrNull {
        it.origin == Origins.InjectConstructorFactoryClassDeclaration
      }
        ?: reportCompilerBug(
          "No expected FIR-generated factory class found for '${declaration.kotlinFqName}'."
        )

    /*
    Implement a simple Factory class that takes all injected values as providers

    // Simple
    class Example_Factory(private val valueProvider: Provider<String>) : Factory<Example_Factory>

    // Generic
    class Example_Factory<T>(private val valueProvider: Provider<T>) : Factory<Example_Factory<T>>
    */

    val injectors = membersInjectorTransformer.getOrGenerateAllInjectorsFor(declaration)
    val memberInjectParameters = injectors.flatMap { it.requiredParametersByClass.values.flatten() }

    val constructorParameters = targetConstructor.parameters()
    val allParameters =
      buildList {
          add(constructorParameters)
          addAll(memberInjectParameters)
        }
        .distinct()
    val allValueParameters = allParameters.flatMap { it.regularParameters }
    val nonAssistedParameters = allValueParameters.filterNot { it.isAssisted }

    val ctor = factoryCls.primaryConstructor!!

    val constructorParametersToFields = assignConstructorParamsToFields(ctor, factoryCls)

    // TODO This is ugly. Can we just source all the params directly from the FIR class now?
    val sourceParametersToFields: Map<Parameter, IrField> =
      constructorParametersToFields.entries.withIndex().associate { (index, pair) ->
        val (_, field) = pair
        val sourceParam = nonAssistedParameters[index]
        sourceParam to field
      }

    val newInstanceFunction =
      generateCreators(
        factoryCls,
        ctor.symbol,
        targetConstructor.symbol,
        constructorParameters,
        allParameters,
      )

    /*
    Normal provider - override + implement the Provider.value property

    // Simple
    override fun invoke(): Example = newInstance(valueProvider())

    // Generic
    override fun invoke(): Example<T> = newInstance(valueProvider())

    // Provider
    override fun invoke(): Example<T> = newInstance(valueProvider)

    // Lazy
    override fun invoke(): Example<T> = newInstance(DoubleCheck.lazy(valueProvider))

    // Provider<Lazy<T>>
    override fun invoke(): Example<T> = newInstance(ProviderOfLazy.create(valueProvider))
    */
    val invoke = factoryCls.requireSimpleFunction(Symbols.StringNames.INVOKE)

    implementFactoryInvokeOrGetBody(
      invoke.owner,
      factoryCls.thisReceiverOrFail,
      newInstanceFunction,
      constructorParameters,
      injectors,
      sourceParametersToFields,
    )

    possiblyImplementInvoke(declaration, constructorParameters)

    // Generate a metadata-visible function that matches the signature of the target constructor
    // This is used in downstream compilations to read the constructor's signature
    val mirrorFunction =
      generateMetadataVisibleMirrorFunction(
        factoryClass = factoryCls,
        target = targetConstructor,
        backingField = null,
        annotations = metroAnnotationsOf(targetConstructor),
      )

    factoryCls.dumpToMetroLog()

    // Write metadata to indicate Metro generated this factory
    cacheFactoryInMetadata(declaration, factoryCls)

    val wrapper =
      ClassFactory.MetroFactory(factoryCls, mirrorFunction.parameters(), targetConstructor)
    generatedFactories[injectedClassId] = Optional.of(wrapper)
    return wrapper
  }

  private fun cacheFactoryInMetadata(targetClass: IrClass, factoryClass: IrClass) {
    if (targetClass.isExternalParent) {
      return
    }
    val factory = InjectConstructorFactoryProto(factory_class_name = factoryClass.name.asString())

    // Store the metadata for this class
    targetClass.metroMetadata = MetroMetadata(inject_constructor_factory = factory)
  }

  private fun implementFactoryInvokeOrGetBody(
    invokeFunction: IrSimpleFunction,
    thisReceiver: IrValueParameter,
    newInstanceFunction: IrSimpleFunction,
    constructorParameters: Parameters,
    injectors: List<MembersInjectorTransformer.MemberInjectClass>,
    parametersToFields: Map<Parameter, IrField>,
  ) {
    if (invokeFunction.isFakeOverride) {
      invokeFunction.finalizeFakeOverride(thisReceiver)
    }
    invokeFunction.body =
      pluginContext.createIrBuilder(invokeFunction.symbol).irBlockBody {
        val constructorParameterNames =
          constructorParameters.regularParameters
            .filterNot { it.isAssisted }
            .associateBy { it.originalName }

        val functionParamsByName =
          invokeFunction.regularParameters.associate { it.name to irGet(it) }

        val args =
          constructorParameters.regularParameters.map { targetParam ->
            when (val parameterName = targetParam.originalName) {
              in constructorParameterNames -> {
                val constructorParam = constructorParameterNames.getValue(parameterName)
                val providerInstance =
                  irGetField(
                    irGet(invokeFunction.dispatchReceiverParameter!!),
                    parametersToFields.getValue(constructorParam),
                  )
                val contextKey = targetParam.contextualTypeKey
                typeAsProviderArgument(
                  contextKey = contextKey,
                  bindingCode = providerInstance,
                  isAssisted = false,
                  isGraphInstance = constructorParam.isGraphInstance,
                )
              }

              in functionParamsByName -> {
                functionParamsByName.getValue(targetParam.originalName)
              }

              else -> reportCompilerBug("Unmatched top level injected function param: $targetParam")
            }
          }

        val typeArgs =
          if (newInstanceFunction.typeParameters.isNotEmpty()) {
            listOf(invokeFunction.returnType)
          } else {
            null
          }
        val newInstance =
          irInvoke(
            dispatchReceiver = dispatchReceiverFor(newInstanceFunction),
            callee = newInstanceFunction.symbol,
            typeArgs = typeArgs,
            args = args,
          )

        if (injectors.isNotEmpty()) {
          val instance = irTemporary(newInstance)
          for (injector in injectors) {
            val injectorClass = injector.injectorClass ?: continue
            val typeArgs = injectorClass.parentAsClass.typeParameters.map { it.defaultType }
            for ((function, parameters) in injector.declaredInjectFunctions) {
              // Record for IC
              trackFunctionCall(invokeFunction, function)
              +irInvoke(
                dispatchReceiver = irGetObject(function.parentAsClass.symbol),
                callee = function.symbol,
                typeArgs = typeArgs,
                args =
                  buildList {
                    add(irGet(instance))
                    addAll(
                      parametersAsProviderArguments(
                        parameters,
                        invokeFunction.dispatchReceiverParameter!!,
                        parametersToFields,
                      )
                    )
                  },
              )
            }
          }

          +irReturn(irGet(instance))
        } else {
          +irReturn(newInstance)
        }
      }
  }

  private fun possiblyImplementInvoke(declaration: IrClass, constructorParameters: Parameters) {
    val injectedFunctionClass =
      declaration.getAnnotation(Symbols.ClassIds.metroInjectedFunctionClass.asSingleFqName())
    if (injectedFunctionClass != null) {
      val callableName = injectedFunctionClass.getAnnotationStringValue()!!.asName()
      val callableId = CallableId(declaration.packageFqName!!, callableName)
      var targetCallable = pluginContext.referenceFunctions(callableId).single()

      // Assign fields
      val constructorParametersToFields =
        assignConstructorParamsToFields(constructorParameters, declaration)

      val invokeFunction =
        declaration.functions.first { it.origin == Origins.TopLevelInjectFunctionClassFunction }

      // If compose compiler has already run, the looked up function may be the _old_ function
      // and we need to update the reference to the newly transformed one
      val hasComposeCompilerRun =
        options.pluginOrderSet?.let { !it }
          ?: (invokeFunction.regularParameters.lastOrNull()?.name?.asString() == $$"$changed")
      if (hasComposeCompilerRun) {
        val originalParent = targetCallable.owner.file
        targetCallable =
          originalParent.declarations
            .filterIsInstance<IrSimpleFunction>()
            .first { it.callableId == callableId }
            .symbol
      }

      invokeFunction.apply {
        val functionReceiver = dispatchReceiverParameter!!
        body =
          pluginContext.createIrBuilder(symbol).run {
            val sourceParameters = targetCallable.owner.parameters()
            if (invokeFunction.origin == Origins.TopLevelInjectFunctionClassFunction) {
              // If this is a top-level function, we need to patch up the parameters
              copyParameterDefaultValues(
                providerFunction = null,
                sourceMetroParameters = sourceParameters,
                sourceParameters =
                  sourceParameters.nonDispatchParameters
                    .filter { it.isAssisted }
                    .map { it.asValueParameter },
                targetParameters = invokeFunction.nonDispatchParameters,
                targetGraphParameter = null,
                wrapInProvider = false,
                isTopLevelFunction = true,
              )
            }

            val constructorParameterNames =
              constructorParameters.regularParameters.associateBy { it.originalName }

            val contextParameterNames =
              invokeFunction.contextParameters.associate { it.name to irGet(it) }

            val functionParamsByName =
              invokeFunction.regularParameters.associate { it.name to irGet(it) }

            val contextArgs =
              sourceParameters.contextParameters.map { targetParam ->
                when (val parameterName = targetParam.originalName) {
                  in constructorParameterNames -> {
                    val constructorParam = constructorParameterNames.getValue(parameterName)
                    val providerInstance =
                      irGetField(
                        irGet(functionReceiver),
                        constructorParametersToFields.getValue(constructorParam),
                      )
                    val contextKey = targetParam.contextualTypeKey
                    typeAsProviderArgument(
                      contextKey = contextKey,
                      bindingCode = providerInstance,
                      isAssisted = false,
                      isGraphInstance = constructorParam.isGraphInstance,
                    )
                  }

                  in functionParamsByName -> {
                    functionParamsByName.getValue(targetParam.originalName)
                  }

                  in contextParameterNames -> {
                    contextParameterNames.getValue(targetParam.originalName)
                  }

                  else -> {
                    error("Unmatched top level injected function param: $targetParam")
                  }
                }
              }

            val args =
              targetCallable.owner.parameters().regularParameters.map { targetParam ->
                when (val parameterName = targetParam.originalName) {
                  in constructorParameterNames -> {
                    val constructorParam = constructorParameterNames.getValue(parameterName)
                    val providerInstance =
                      irGetField(
                        irGet(functionReceiver),
                        constructorParametersToFields.getValue(constructorParam),
                      )
                    val contextKey = targetParam.contextualTypeKey
                    typeAsProviderArgument(
                      contextKey = contextKey,
                      bindingCode = providerInstance,
                      isAssisted = false,
                      isGraphInstance = constructorParam.isGraphInstance,
                    )
                  }

                  in functionParamsByName -> {
                    functionParamsByName.getValue(targetParam.originalName)
                  }

                  else ->
                    reportCompilerBug("Unmatched top level injected function param: $targetParam")
                }
              }

            val invokeExpression =
              irInvoke(
                callee = targetCallable,
                dispatchReceiver = null,
                extensionReceiver = null,
                typeHint = targetCallable.owner.returnType,
                // TODO type params
                contextArgs = contextArgs,
                args = args,
              )

            irExprBodySafe(invokeExpression)
          }
      }

      declaration.dumpToMetroLog()
    }
  }

  private fun generateCreators(
    factoryCls: IrClass,
    factoryConstructor: IrConstructorSymbol,
    targetConstructor: IrConstructorSymbol,
    constructorParameters: Parameters,
    allParameters: List<Parameters>,
  ): IrSimpleFunction {
    // If this is an object, we can generate directly into this object
    val isObject = factoryCls.kind == ClassKind.OBJECT
    val classToGenerateCreatorsIn =
      if (isObject) {
        factoryCls
      } else {
        factoryCls.companionObject()!!
      }

    // TODO
    //  Dagger will de-dupe these by type key to shrink the code. We could do the same but only for
    //  parameters that don't have default values. For those cases, we would need to keep them
    //  as-is. Something for another day.
    val mergedParameters =
      allParameters.reduce { current, next -> current.mergeValueParametersWithUntyped(next) }

    // Generate create()
    generateStaticCreateFunction(
      parentClass = classToGenerateCreatorsIn,
      targetClass = factoryCls,
      targetConstructor = factoryConstructor,
      parameters = mergedParameters,
      providerFunction = null,
    )

    val newInstanceFunction =
      generateStaticNewInstanceFunction(
        parentClass = classToGenerateCreatorsIn,
        sourceMetroParameters = constructorParameters,
        sourceParameters = constructorParameters.regularParameters.map { it.asValueParameter },
      ) { function ->
        irCallConstructor(
            callee = targetConstructor,
            typeArguments = function.typeParameters.map { it.defaultType },
          )
          .apply {
            // The function may have a dispatch receiver so we need to offset
            val functionParameters = function.nonDispatchParameters
            val indexOffset = if (function.dispatchReceiverParameter == null) 0 else 1
            for (index in constructorParameters.allParameters.indices) {
              val parameter = functionParameters[index]
              arguments[parameter.indexInParameters - indexOffset] = irGet(parameter)
            }
          }
      }
    return newInstanceFunction
  }
}
