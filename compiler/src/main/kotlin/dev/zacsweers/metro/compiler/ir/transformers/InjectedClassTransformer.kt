// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.transformers

import dev.zacsweers.metro.compiler.Origins
import dev.zacsweers.metro.compiler.asName
import dev.zacsweers.metro.compiler.fir.MetroDiagnostics
import dev.zacsweers.metro.compiler.generatedClass
import dev.zacsweers.metro.compiler.ir.ClassFactory
import dev.zacsweers.metro.compiler.ir.IrMetroContext
import dev.zacsweers.metro.compiler.ir.IrTypeKey
import dev.zacsweers.metro.compiler.ir.assignConstructorParamsToFields
import dev.zacsweers.metro.compiler.ir.checkMirrorParamMismatches
import dev.zacsweers.metro.compiler.ir.contextParameters
import dev.zacsweers.metro.compiler.ir.copyParameterDefaultValues
import dev.zacsweers.metro.compiler.ir.createAndAddTemporaryVariable
import dev.zacsweers.metro.compiler.ir.createIrBuilder
import dev.zacsweers.metro.compiler.ir.dispatchReceiverFor
import dev.zacsweers.metro.compiler.ir.finalizeFakeOverride
import dev.zacsweers.metro.compiler.ir.findInjectableConstructor
import dev.zacsweers.metro.compiler.ir.irExprBodySafe
import dev.zacsweers.metro.compiler.ir.irInvoke
import dev.zacsweers.metro.compiler.ir.isExternalParent
import dev.zacsweers.metro.compiler.ir.metroAnnotationsOf
import dev.zacsweers.metro.compiler.ir.metroMetadata
import dev.zacsweers.metro.compiler.ir.parameters.Parameter
import dev.zacsweers.metro.compiler.ir.parameters.Parameters
import dev.zacsweers.metro.compiler.ir.parameters.dedupeParameters
import dev.zacsweers.metro.compiler.ir.parameters.parameters
import dev.zacsweers.metro.compiler.ir.parametersAsProviderArguments
import dev.zacsweers.metro.compiler.ir.regularParameters
import dev.zacsweers.metro.compiler.ir.reportCompat
import dev.zacsweers.metro.compiler.ir.requireSimpleFunction
import dev.zacsweers.metro.compiler.ir.thisReceiverOrFail
import dev.zacsweers.metro.compiler.ir.trackFunctionCall
import dev.zacsweers.metro.compiler.ir.typeAsProviderArgument
import dev.zacsweers.metro.compiler.reportCompilerBug
import dev.zacsweers.metro.compiler.symbols.Symbols
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap
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

internal class InjectedClassTransformer(
  context: IrMetroContext,
  private val membersInjectorTransformer: MembersInjectorTransformer,
) : IrMetroContext by context {

  // Thread-safe for concurrent access during parallel graph validation.
  private val generatedFactories = ConcurrentHashMap<ClassId, Optional<ClassFactory>>()

  fun visitClass(declaration: IrClass) {
    val injectableConstructor =
      declaration.findInjectableConstructor(onlyUsePrimaryConstructor = false)
    if (injectableConstructor != null) {
      @Suppress("RETURN_VALUE_NOT_USED")
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
      val metadata = declaration.metroMetadata?.injected_class

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
        metadata != null && metadata.factory_class_name != null -> {
          val factoryClassName = metadata.factory_class_name.asName()
          val factoryCls =
            declaration.nestedClasses.singleOrNull { it.name == factoryClassName }
              ?: reportCompilerBug(
                "Expected nested class '$factoryClassName' not found in '${declaration.kotlinFqName}'."
              )
          val mirrorFunction =
            factoryCls.requireSimpleFunction(Symbols.StringNames.MIRROR_FUNCTION).owner
          val parameters = mirrorFunction.parameters()

          // Look up the injectable constructor for direct invocation optimization
          val externalTargetConstructor = targetConstructor()

          // Validate and optionally patch parameter types due to
          // https://github.com/ZacSweers/metro/issues/1556
          val hadUnpatchedMismatch =
            checkMirrorParamMismatches(
              factoryClass = factoryCls,
              newInstanceFunctionName = Symbols.StringNames.NEW_INSTANCE,
              mirrorFunction = mirrorFunction,
              mirrorParams = { parameters.nonDispatchParameters.filterNot { it.isAssisted } },
              reportingFunction = externalTargetConstructor,
              primaryConstructorParamOffset = 0,
            ) {
              it.parameters().allParameters
            }

          if (hadUnpatchedMismatch) {
            return null
          }

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

    // Deduplicate parameters to match the FIR-generated factory constructor.
    // The FIR side deduplicates by type key, so the factory constructor has fewer
    // parameters when multiple source params share the same type+qualifier.
    val dedupedParameters =
      if (options.deduplicateInjectedParams) nonAssistedParameters.dedupeParameters()
      else nonAssistedParameters

    val ctor = factoryCls.primaryConstructor!!

    val constructorParametersToFields = assignConstructorParamsToFields(ctor, factoryCls)

    // Build a mapping from ALL source parameters to fields, handling the fact that
    // multiple source params with the same type key share the same deduped field.
    val dedupedParamToField: Map<Parameter, IrField> =
      constructorParametersToFields.entries.withIndex().associate { (index, pair) ->
        val (_, field) = pair
        val sourceParam = dedupedParameters[index]
        sourceParam to field
      }
    val typeKeyToField: Map<IrTypeKey, IrField> = buildMap {
      for ((param, field) in dedupedParamToField) {
        // Params with defaults have their own entries (they're never deduped), so they
        // don't belong in the type-key map. Only non-default params share fields by type key.
        if (!param.hasDefault) {
          putIfAbsent(param.typeKey, field)
        }
      }
    }
    val sourceParametersToFields: Map<Parameter, IrField> = buildMap {
      for (param in nonAssistedParameters) {
        if (param.hasDefault) {
          // Params with defaults have their own dedicated fields (looked up by identity)
          dedupedParamToField[param]?.let { put(param, it) }
        } else {
          // Non-default params share fields by type key (deduped)
          typeKeyToField[param.typeKey]?.let { put(param, it) }
        }
      }
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

    val wrapper =
      ClassFactory.MetroFactory(factoryCls, mirrorFunction.parameters(), targetConstructor)

    // Write metadata to indicate Metro generated this factory
    cacheFactoryInMetadata(declaration, wrapper)

    generatedFactories[injectedClassId] = Optional.of(wrapper)
    return wrapper
  }

  private fun cacheFactoryInMetadata(declaration: IrClass, classFactory: ClassFactory) {
    if (classFactory.factoryClass.isExternalParent) {
      return
    }

    val memberInjections = membersInjectorTransformer.getOrGenerateInjector(declaration)

    // Store the metadata for this class
    declaration.writeInjectedClassMetadata(classFactory, memberInjections)
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

        // Use non-deduped constructor params for newInstance args since
        // newInstance preserves the original constructor signature
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
          val instance = createAndAddTemporaryVariable(newInstance)
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

    val mergedParameters =
      allParameters.reduce { current, next -> current.mergeValueParametersWithUntyped(next) }

    // Deduplicate to match the FIR-generated create() function signature
    val dedupedMerged =
      mergedParameters.copy(
        regularParameters =
          if (options.deduplicateInjectedParams)
            mergedParameters.regularParameters.dedupeParameters()
          else mergedParameters.regularParameters
      )

    // Generate create()
    @Suppress("RETURN_VALUE_NOT_USED")
    generateStaticCreateFunction(
      parentClass = classToGenerateCreatorsIn,
      targetClass = factoryCls,
      targetConstructor = factoryConstructor,
      parameters = dedupedMerged,
      providerFunction = null,
    )

    // newInstance() preserves the original constructor signature (no deduplication)
    // so that each parameter gets its own distinct value from the provider.
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
            val functionParameters = function.nonDispatchParameters
            for ((i, param) in constructorParameters.allParameters.withIndex()) {
              arguments[param.asValueParameter.indexInParameters] = irGet(functionParameters[i])
            }
          }
      }
    return newInstanceFunction
  }
}
