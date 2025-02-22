// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.transformers

import dev.zacsweers.metro.compiler.Origins
import dev.zacsweers.metro.compiler.Symbols
import dev.zacsweers.metro.compiler.asName
import dev.zacsweers.metro.compiler.generatedClass
import dev.zacsweers.metro.compiler.ir.IrMetroContext
import dev.zacsweers.metro.compiler.ir.assignConstructorParamsToFields
import dev.zacsweers.metro.compiler.ir.createIrBuilder
import dev.zacsweers.metro.compiler.ir.dispatchReceiverFor
import dev.zacsweers.metro.compiler.ir.finalizeFakeOverride
import dev.zacsweers.metro.compiler.ir.implementsProviderType
import dev.zacsweers.metro.compiler.ir.irExprBodySafe
import dev.zacsweers.metro.compiler.ir.irInvoke
import dev.zacsweers.metro.compiler.ir.irTemporary
import dev.zacsweers.metro.compiler.ir.isExternalParent
import dev.zacsweers.metro.compiler.ir.parameters.ConstructorParameter
import dev.zacsweers.metro.compiler.ir.parameters.Parameter
import dev.zacsweers.metro.compiler.ir.parameters.Parameters
import dev.zacsweers.metro.compiler.ir.parameters.parameters
import dev.zacsweers.metro.compiler.ir.parametersAsProviderArguments
import dev.zacsweers.metro.compiler.ir.requireSimpleFunction
import dev.zacsweers.metro.compiler.ir.thisReceiverOrFail
import dev.zacsweers.metro.compiler.ir.typeAsProviderArgument
import kotlin.collections.component1
import kotlin.collections.component2
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
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
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.symbols.IrConstructorSymbol
import org.jetbrains.kotlin.ir.symbols.IrFunctionSymbol
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.classIdOrFail
import org.jetbrains.kotlin.ir.util.companionObject
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.functions
import org.jetbrains.kotlin.ir.util.getAnnotation
import org.jetbrains.kotlin.ir.util.getAnnotationStringValue
import org.jetbrains.kotlin.ir.util.isFromJava
import org.jetbrains.kotlin.ir.util.isObject
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.util.nestedClasses
import org.jetbrains.kotlin.ir.util.packageFqName
import org.jetbrains.kotlin.ir.util.parentAsClass
import org.jetbrains.kotlin.ir.util.primaryConstructor
import org.jetbrains.kotlin.ir.util.simpleFunctions
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId

internal class InjectConstructorTransformer(
  context: IrMetroContext,
  private val membersInjectorTransformer: MembersInjectorTransformer,
) : IrMetroContext by context {

  private val generatedFactories = mutableMapOf<ClassId, ConstructorInjectedFactory>()

  fun visitClass(declaration: IrClass) {
    val injectableConstructor =
      declaration.findInjectableConstructor(onlyUsePrimaryConstructor = false)
    if (injectableConstructor != null) {
      getOrGenerateFactory(declaration, injectableConstructor)
    }
  }

  fun getOrGenerateFactory(
    declaration: IrClass,
    targetConstructor: IrConstructor,
  ): ConstructorInjectedFactory? {
    val injectedClassId: ClassId = declaration.classIdOrFail
    generatedFactories[injectedClassId]?.let {
      return it
    }

    val isExternal = declaration.isExternalParent

    /*
    Implement a simple Factory class that takes all injected values as providers

    // Simple
    class Example_Factory(private val valueProvider: Provider<String>) : Factory<Example_Factory>

    // Generic
    class Example_Factory<T>(private val valueProvider: Provider<T>) : Factory<Example_Factory<T>>
    */
    val factoryCls =
      declaration.nestedClasses.singleOrNull {
        val isMetroFactory = it.name == Symbols.Names.metroFactory
        // If not external, double check its origin
        if (isMetroFactory && !isExternal) {
          if (it.origin != Origins.InjectConstructorFactoryClassDeclaration) {
            declaration.reportError(
              "Found a Metro factory declaration in ${declaration.kotlinFqName} but with an unexpected origin ${it.origin}"
            )
            return null
          }
        }
        isMetroFactory
      }

    if (factoryCls == null) {
      if (isExternal) {
        if (options.enableDaggerRuntimeInterop) {
          // Look up where dagger would generate one
          val daggerFactoryClassId = injectedClassId.generatedClass("_Factory")
          val daggerFactoryClass = pluginContext.referenceClass(daggerFactoryClassId)?.owner
          if (daggerFactoryClass != null) {
            val wrapper = ConstructorInjectedFactory.DaggerFactory(metroContext, daggerFactoryClass)
            generatedFactories[injectedClassId] = wrapper
            return wrapper
          }
        }
        declaration.reportError(
          "Could not find generated factory for '${declaration.kotlinFqName}' in upstream module where it's defined. Run the Metro compiler over that module too."
        )
        return null
      } else {
        error(
          "No expected factory class generated for '${declaration.kotlinFqName}'. Report this bug with a repro case at https://github.com/zacsweers/metro/issues/new"
        )
      }
    }

    // If it's from another module, we're done!
    // TODO this doesn't work as expected in KMP, where things compiled in common are seen as
    //  external but no factory is found?
    if (isExternal) {
      val wrapper = ConstructorInjectedFactory.MetroFactory(factoryCls)
      generatedFactories[injectedClassId] = wrapper
      return wrapper
    }

    val injectors = membersInjectorTransformer.getOrGenerateAllInjectorsFor(declaration)
    val memberInjectParameters = injectors.flatMap { it.parameters.values.flatten() }

    val constructorParameters = targetConstructor.parameters(metroContext, factoryCls, declaration)
    val allParameters =
      buildList {
          add(constructorParameters)
          addAll(memberInjectParameters)
        }
        .distinct()
    val allValueParameters = allParameters.flatMap { it.valueParameters }
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

    factoryCls.dumpToMetroLog()

    val wrapper = ConstructorInjectedFactory.MetroFactory(factoryCls)
    generatedFactories[injectedClassId] = wrapper
    return wrapper
  }

  private fun implementFactoryInvokeOrGetBody(
    invokeFunction: IrSimpleFunction,
    thisReceiver: IrValueParameter,
    newInstanceFunction: IrSimpleFunction,
    constructorParameters: Parameters<ConstructorParameter>,
    injectors: List<MembersInjectorTransformer.MemberInjectClass>,
    parametersToFields: Map<Parameter, IrField>,
  ) {
    if (invokeFunction.isFakeOverride) {
      invokeFunction.finalizeFakeOverride(thisReceiver)
    }
    invokeFunction.body =
      pluginContext.createIrBuilder(invokeFunction.symbol).irBlockBody {
        val constructorParameterNames =
          constructorParameters.valueParameters
            .filterNot { it.isAssisted }
            .associateBy { it.originalName }

        val functionParamsByName = invokeFunction.valueParameters.associate { it.name to irGet(it) }

        val args =
          constructorParameters.valueParameters.map { targetParam ->
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
                  context = metroContext,
                  contextKey = contextKey,
                  bindingCode = providerInstance,
                  isAssisted = false,
                  isGraphInstance = constructorParam.isGraphInstance,
                )
              }

              in functionParamsByName -> {
                functionParamsByName.getValue(targetParam.originalName)
              }

              else -> error("Unmatched top level injected function param: $targetParam")
            }
          }

        val newInstance =
          irInvoke(
              dispatchReceiver = dispatchReceiverFor(newInstanceFunction),
              callee = newInstanceFunction.symbol,
              args = args,
            )
            .apply {
              if (newInstanceFunction.typeParameters.isNotEmpty()) {
                putTypeArgument(0, invokeFunction.returnType)
              }
            }

        if (injectors.isNotEmpty()) {
          val instance = irTemporary(newInstance)
          for (injector in injectors) {
            for ((function, parameters) in injector.injectFunctions) {
              +irInvoke(
                dispatchReceiver = irGetObject(function.parentAsClass.symbol),
                callee = function.symbol,
                args =
                  buildList {
                    add(irGet(instance))
                    addAll(
                      parametersAsProviderArguments(
                        metroContext,
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

  private fun possiblyImplementInvoke(
    declaration: IrClass,
    constructorParameters: Parameters<ConstructorParameter>,
  ) {
    val injectedFunctionClass =
      declaration.getAnnotation(Symbols.ClassIds.metroInjectedFunctionClass.asSingleFqName())
    if (injectedFunctionClass != null) {
      val callableName = injectedFunctionClass.getAnnotationStringValue()!!.asName()
      val callableId = CallableId(declaration.packageFqName!!, callableName)
      val targetCallable = pluginContext.referenceFunctions(callableId).single()

      // Assign fields
      val constructorParametersToFields =
        assignConstructorParamsToFields(constructorParameters, declaration)

      val invokeFunction =
        declaration.functions.first { it.origin == Origins.TopLevelInjectFunctionClassFunction }

      // TODO
      //  copy default values
      invokeFunction.apply {
        val functionReceiver = dispatchReceiverParameter!!
        body =
          pluginContext.createIrBuilder(symbol).run {
            val constructorParameterNames =
              constructorParameters.valueParameters.associateBy { it.originalName }

            val functionParamsByName =
              invokeFunction.valueParameters.associate { it.name to irGet(it) }

            val args =
              targetCallable.owner.parameters(metroContext).valueParameters.map { targetParam ->
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
                      context = metroContext,
                      contextKey = contextKey,
                      bindingCode = providerInstance,
                      isAssisted = false,
                      isGraphInstance = constructorParam.isGraphInstance,
                    )
                  }

                  in functionParamsByName -> {
                    functionParamsByName.getValue(targetParam.originalName)
                  }

                  else -> error("Unmatched top level injected function param: $targetParam")
                }
              }

            val invokeExpression =
              irInvoke(
                  callee = targetCallable,
                  dispatchReceiver = null,
                  extensionReceiver = null,
                  typeHint = targetCallable.owner.returnType,
                  args = args,
                )
                .apply {
                  // TODO type params
                }

            irExprBodySafe(symbol, invokeExpression)
          }
      }

      declaration.dumpToMetroLog()
    }
  }

  private fun generateCreators(
    factoryCls: IrClass,
    factoryConstructor: IrConstructorSymbol,
    targetConstructor: IrConstructorSymbol,
    constructorParameters: Parameters<ConstructorParameter>,
    allParameters: List<Parameters<out Parameter>>,
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
      context = metroContext,
      parentClass = classToGenerateCreatorsIn,
      targetClass = factoryCls,
      targetConstructor = factoryConstructor,
      parameters = mergedParameters,
      providerFunction = null,
    )

    val newInstanceFunction =
      generateStaticNewInstanceFunction(
        context = metroContext,
        parentClass = classToGenerateCreatorsIn,
        sourceParameters = constructorParameters.valueParameters.map { it.ir },
      ) { function ->
        irCallConstructor(targetConstructor, emptyList()).apply {
          for (index in constructorParameters.allParameters.indices) {
            val parameter = function.valueParameters[index]
            putValueArgument(parameter.index, irGet(parameter))
          }
        }
      }
    return newInstanceFunction
  }

  sealed interface ConstructorInjectedFactory {
    val factoryClass: IrClass
    val invokeFunctionSymbol: IrFunctionSymbol

    fun IrBuilderWithScope.invokeCreateExpression(
      computeArgs: IrBuilderWithScope.(createFunction: IrSimpleFunction) -> List<IrExpression?>
    ): IrExpression

    class MetroFactory(override val factoryClass: IrClass) : ConstructorInjectedFactory {
      override val invokeFunctionSymbol: IrFunctionSymbol
        get() = factoryClass.requireSimpleFunction(Symbols.StringNames.INVOKE)

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
    ) : ConstructorInjectedFactory {
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
              callee = metroContext.symbols.daggerSymbols!!.asMetroProvider,
            )
            .apply { putTypeArgument(0, factoryClass.typeWith()) }
        } else {
          createExpression
        }
      }
    }
  }
}
