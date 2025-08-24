// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import dev.zacsweers.metro.compiler.Symbols
import dev.zacsweers.metro.compiler.exitProcessing
import dev.zacsweers.metro.compiler.expectAs
import dev.zacsweers.metro.compiler.graph.WrappedType
import dev.zacsweers.metro.compiler.ir.parameters.Parameter
import dev.zacsweers.metro.compiler.ir.parameters.Parameters
import dev.zacsweers.metro.compiler.ir.parameters.parameters
import dev.zacsweers.metro.compiler.ir.parameters.wrapInProvider
import dev.zacsweers.metro.compiler.ir.transformers.AssistedFactoryTransformer
import dev.zacsweers.metro.compiler.ir.transformers.BindingContainerTransformer
import dev.zacsweers.metro.compiler.ir.transformers.MembersInjectorTransformer
import dev.zacsweers.metro.compiler.reportCompilerBug
import dev.zacsweers.metro.compiler.tracing.Tracer
import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irCallConstructor
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irGetField
import org.jetbrains.kotlin.ir.builders.irGetObject
import org.jetbrains.kotlin.ir.builders.irInt
import org.jetbrains.kotlin.ir.builders.irReturn
import org.jetbrains.kotlin.ir.builders.parent
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.defaultType
import org.jetbrains.kotlin.ir.types.typeOrFail
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.companionObject
import org.jetbrains.kotlin.ir.util.deepCopyWithSymbols
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.isFromJava
import org.jetbrains.kotlin.ir.util.isObject
import org.jetbrains.kotlin.ir.util.isStatic
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.util.primaryConstructor
import org.jetbrains.kotlin.ir.util.remapTypes
import org.jetbrains.kotlin.ir.util.simpleFunctions
import org.jetbrains.kotlin.name.Name

internal class IrGraphExpressionGenerator
private constructor(
  context: IrMetroContext,
  private val node: DependencyGraphNode,
  private val thisReceiver: IrValueParameter,
  private val bindingFieldContext: BindingFieldContext,
  private val bindingGraph: IrBindingGraph,
  private val bindingContainerTransformer: BindingContainerTransformer,
  private val membersInjectorTransformer: MembersInjectorTransformer,
  private val assistedFactoryTransformer: AssistedFactoryTransformer,
  private val graphExtensionGenerator: IrGraphExtensionGenerator,
  private val parentTracer: Tracer,
) : IrMetroContext by context {

  class Factory(
    private val context: IrMetroContext,
    private val node: DependencyGraphNode,
    private val bindingFieldContext: BindingFieldContext,
    private val bindingGraph: IrBindingGraph,
    private val bindingContainerTransformer: BindingContainerTransformer,
    private val membersInjectorTransformer: MembersInjectorTransformer,
    private val assistedFactoryTransformer: AssistedFactoryTransformer,
    private val graphExtensionGenerator: IrGraphExtensionGenerator,
    private val parentTracer: Tracer,
  ) {
    fun create(thisReceiver: IrValueParameter): IrGraphExpressionGenerator {
      return IrGraphExpressionGenerator(
        context = context,
        node = node,
        thisReceiver = thisReceiver,
        bindingFieldContext = bindingFieldContext,
        bindingGraph = bindingGraph,
        bindingContainerTransformer = bindingContainerTransformer,
        membersInjectorTransformer = membersInjectorTransformer,
        assistedFactoryTransformer = assistedFactoryTransformer,
        graphExtensionGenerator = graphExtensionGenerator,
        parentTracer = parentTracer,
      )
    }
  }

  enum class AccessType {
    INSTANCE,
    PROVIDER,
  }

  context(scope: IrBuilderWithScope)
  fun generateBindingCode(
    binding: IrBinding,
    contextualTypeKey: IrContextualTypeKey = binding.contextualTypeKey,
    accessType: AccessType =
      if (contextualTypeKey.requiresProviderInstance) {
        AccessType.PROVIDER
      } else {
        AccessType.INSTANCE
      },
    fieldInitKey: IrTypeKey? = null,
  ): IrExpression =
    with(scope) {
      if (binding is IrBinding.Absent) {
        reportCompilerBug(
          "Absent bindings need to be checked prior to generateBindingCode(). ${binding.typeKey} missing."
        )
      }

      val metroProviderSymbols = symbols.providerSymbolsFor(contextualTypeKey)

      // If we're initializing the field for this key, don't ever try to reach for an existing
      // provider for it.
      // This is important for cases like DelegateFactory and breaking cycles.
      if (fieldInitKey == null || fieldInitKey != binding.typeKey) {
        bindingFieldContext.providerField(binding.typeKey)?.let {
          val providerInstance =
            irGetField(irGet(thisReceiver), it).let {
              with(metroProviderSymbols) { transformMetroProvider(it, contextualTypeKey) }
            }
          return if (accessType == AccessType.INSTANCE) {
            irInvoke(providerInstance, callee = symbols.providerInvoke)
          } else {
            providerInstance
          }
        }
      }

      return when (binding) {
        is IrBinding.ConstructorInjected -> {
          // Example_Factory.create(...)
          val factory = binding.classFactory

          with(factory) {
            invokeCreateExpression { createFunction ->
              val remapper = createFunction.typeRemapperFor(binding.typeKey.type)
              generateBindingArguments(
                targetParams = createFunction.parameters(remapper = remapper),
                function =
                  createFunction.deepCopyWithSymbols(initialParent = createFunction.parent).also {
                    it.parent = createFunction.parent
                    it.remapTypes(remapper)
                  },
                binding = binding,
                fieldInitKey = null,
              )
            }
          }
        }

        is IrBinding.ObjectClass -> {
          instanceFactory(binding.typeKey.type, irGetObject(binding.type.symbol))
        }

        is IrBinding.Alias -> {
          // For binds functions, just use the backing type
          val aliasedBinding = binding.aliasedBinding(bindingGraph, IrBindingStack.empty())
          check(aliasedBinding != binding) { "Aliased binding aliases itself" }
          return generateBindingCode(
            aliasedBinding,
            accessType = accessType,
            fieldInitKey = fieldInitKey,
          )
        }

        is IrBinding.Provided -> {
          val factoryClass =
            bindingContainerTransformer.getOrLookupProviderFactory(binding)?.clazz
              ?: reportCompilerBug(
                "No factory found for Provided binding ${binding.typeKey}. This is likely a bug in the Metro compiler, please report it to the issue tracker."
              )

          // Invoke its factory's create() function
          val creatorClass =
            if (factoryClass.isObject) {
              factoryClass
            } else {
              factoryClass.companionObject()!!
            }
          val createFunction = creatorClass.requireSimpleFunction(Symbols.StringNames.CREATE)
          // Must use the provider's params for IrTypeKey as that has qualifier
          // annotations
          val args =
            generateBindingArguments(
              targetParams = binding.parameters,
              function = createFunction.owner,
              binding = binding,
              fieldInitKey = fieldInitKey,
            )
          irInvoke(
            dispatchReceiver = irGetObject(creatorClass.symbol),
            callee = createFunction,
            args = args,
          )
        }

        is IrBinding.Assisted -> {
          // Example9_Factory_Impl.create(example9Provider);
          val implClass =
            assistedFactoryTransformer.getOrGenerateImplClass(binding.type)
              ?: return stubExpression()

          val dispatchReceiver: IrExpression?
          val createFunction: IrSimpleFunctionSymbol
          val isFromDagger: Boolean
          if (options.enableDaggerRuntimeInterop && implClass.isFromJava()) {
            // Dagger interop
            createFunction =
              implClass
                .simpleFunctions()
                .first {
                  it.isStatic &&
                    (it.name == Symbols.Names.create ||
                      it.name == Symbols.Names.createFactoryProvider)
                }
                .symbol
            dispatchReceiver = null
            isFromDagger = true
          } else {
            val implClassCompanion = implClass.companionObject()!!
            createFunction = implClassCompanion.requireSimpleFunction(Symbols.StringNames.CREATE)
            dispatchReceiver = irGetObject(implClassCompanion.symbol)
            isFromDagger = false
          }

          val targetBinding =
            bindingGraph.requireBinding(binding.target.typeKey, IrBindingStack.empty())
          val delegateFactoryProvider = generateBindingCode(targetBinding, accessType = accessType)
          val invokeCreateExpression =
            irInvoke(
              dispatchReceiver = dispatchReceiver,
              callee = createFunction,
              args = listOf(delegateFactoryProvider),
            )
          if (isFromDagger) {
            with(symbols.daggerSymbols) {
              val targetType =
                (createFunction.owner.returnType as IrSimpleType).arguments[0].typeOrFail
              transformToMetroProvider(invokeCreateExpression, targetType)
            }
          } else {
            invokeCreateExpression
          }
        }

        is IrBinding.Multibinding -> {
          generateMultibindingExpression(binding, contextualTypeKey, fieldInitKey)
        }

        is IrBinding.MembersInjected -> {
          val injectedClass = referenceClass(binding.targetClassId)!!.owner
          val injectedType = injectedClass.defaultType
          val injectorClass = membersInjectorTransformer.getOrGenerateInjector(injectedClass)?.ir

          if (injectorClass == null) {
            // Return a noop
            val noopInjector =
              irInvoke(
                dispatchReceiver = irGetObject(symbols.metroMembersInjectors),
                callee = symbols.metroMembersInjectorsNoOp,
                typeArgs = listOf(injectedType),
              )
            instanceFactory(noopInjector.type, noopInjector).let {
              with(metroProviderSymbols) { transformMetroProvider(it, contextualTypeKey) }
            }
          } else {
            val injectorCreatorClass =
              if (injectorClass.isObject) injectorClass else injectorClass.companionObject()!!
            val createFunction =
              injectorCreatorClass.requireSimpleFunction(Symbols.StringNames.CREATE)
            val args =
              generateBindingArguments(
                targetParams = binding.parameters,
                function = createFunction.owner,
                binding = binding,
                fieldInitKey = fieldInitKey,
              )
            instanceFactory(
                injectedType,
                // InjectableClass_MembersInjector.create(stringValueProvider,
                // exampleComponentProvider)
                irInvoke(
                  dispatchReceiver =
                    if (injectorCreatorClass.isObject) {
                      irGetObject(injectorCreatorClass.symbol)
                    } else {
                      // It's static from java, dagger interop
                      check(createFunction.owner.isStatic)
                      null
                    },
                  callee = createFunction,
                  args = args,
                ),
              )
              .let { with(metroProviderSymbols) { transformMetroProvider(it, contextualTypeKey) } }
          }
        }

        is IrBinding.Absent -> {
          // Should never happen, this should be checked before function/constructor injections.
          reportCompilerBug("Unable to generate code for unexpected Absent binding: $binding")
        }

        is IrBinding.BoundInstance -> {
          if (binding.classReceiverParameter != null) {
            when (accessType) {
              AccessType.INSTANCE -> {
                // Get it directly
                irGet(binding.classReceiverParameter)
              }
              AccessType.PROVIDER -> {
                // We need the provider
                irGetField(
                  irGet(binding.classReceiverParameter),
                  binding.providerFieldAccess!!.field,
                )
              }
            }
          } else {
            // Should never happen, this should get handled in the provider/instance fields logic
            // above.
            reportCompilerBug(
              "Unable to generate code for unexpected BoundInstance binding: $binding"
            )
          }
        }

        is IrBinding.GraphExtension -> {
          // Generate graph extension instance
          val extensionImpl =
            graphExtensionGenerator.getOrBuildGraphExtensionImpl(
              binding.typeKey,
              node.sourceGraph,
              // The reportableDeclaration should be the accessor function
              metroFunctionOf(binding.reportableDeclaration as IrSimpleFunction),
              parentTracer,
            )

          val ctor = extensionImpl.primaryConstructor!!
          val instanceExpression =
            irCallConstructor(ctor.symbol, node.sourceGraph.typeParameters.map { it.defaultType })
              .apply {
                // If this function has parameters, they're factory instance params and need to be
                // passed on
                val functionParams = binding.accessor.regularParameters

                // First param (dispatch receiver) is always the parent graph
                arguments[0] = irGet(thisReceiver)
                for (i in 0 until functionParams.size) {
                  arguments[i + 1] = irGet(functionParams[i])
                }
              }
          when (accessType) {
            AccessType.INSTANCE -> {
              // Already not a provider
              instanceExpression
            }
            AccessType.PROVIDER -> {
              instanceFactory(binding.typeKey.type, instanceExpression)
            }
          }
        }

        is IrBinding.GraphExtensionFactory -> {
          // Get the pre-generated extension implementation that should contain the factory
          val extensionImpl =
            graphExtensionGenerator.getOrBuildGraphExtensionImpl(
              binding.extensionTypeKey,
              node.sourceGraph,
              metroFunctionOf(binding.reportableDeclaration as IrSimpleFunction),
              parentTracer,
            )

          // Get the factory implementation that was generated alongside the extension
          val factoryImpl =
            extensionImpl.generatedGraphExtensionData?.factoryImpl
              ?: reportCompilerBug(
                "Expected factory implementation to be generated for graph extension factory binding"
              )

          val constructor = factoryImpl.primaryConstructor!!
          val parameters = constructor.parameters()
          val factoryInstance =
            irCallConstructor(
                constructor.symbol,
                binding.accessor.typeParameters.map { it.defaultType },
              )
              .apply {
                // Pass the parent graph instance
                arguments[0] =
                  generateBindingCode(
                    bindingGraph.requireBinding(
                      parameters.regularParameters.single().typeKey,
                      IrBindingStack.empty(),
                    ),
                    accessType = AccessType.INSTANCE,
                  )
              }

          when (accessType) {
            AccessType.INSTANCE -> {
              // Factories are not providers, return directly
              factoryInstance
            }
            AccessType.PROVIDER -> {
              // Wrap in an instance factory
              instanceFactory(binding.typeKey.type, factoryInstance)
            }
          }
        }

        is IrBinding.GraphDependency -> {
          val ownerKey = binding.ownerKey
          if (binding.fieldAccess != null) {
            // Just get the field
            irGetField(irGet(binding.fieldAccess.receiverParameter), binding.fieldAccess.field)
          } else if (binding.getter != null) {
            val graphInstanceField =
              bindingFieldContext.instanceField(ownerKey)
                ?: reportCompilerBug(
                  "No matching included type instance found for type $ownerKey while processing ${node.typeKey}. Available instance fields ${bindingFieldContext.availableInstanceKeys}"
                )

            val getterContextKey = IrContextualTypeKey.from(binding.getter)

            val invokeGetter =
              irInvoke(
                dispatchReceiver = irGetField(irGet(thisReceiver), graphInstanceField),
                callee = binding.getter.symbol,
                typeHint = binding.typeKey.type,
              )

            if (getterContextKey.isLazyWrappedInProvider) {
              // TODO FIR this
              diagnosticReporter
                .at(binding.getter)
                .report(MetroIrErrors.METRO_ERROR, "Provider<Lazy<T>> accessors are not supported.")
              exitProcessing()
            } else if (getterContextKey.isWrappedInProvider) {
              // It's already a provider
              invokeGetter
            } else {
              val lambda =
                irLambda(
                  parent = this.parent,
                  receiverParameter = null,
                  emptyList(),
                  binding.typeKey.type,
                  suspend = false,
                ) {
                  val returnExpression =
                    if (getterContextKey.isWrappedInProvider) {
                      irInvoke(invokeGetter, callee = symbols.providerInvoke)
                    } else if (getterContextKey.isWrappedInLazy) {
                      irInvoke(invokeGetter, callee = symbols.lazyGetValue)
                    } else {
                      invokeGetter
                    }
                  +irReturn(returnExpression)
                }
              irInvoke(
                dispatchReceiver = null,
                callee = symbols.metroProviderFunction,
                typeHint = binding.typeKey.type.wrapInProvider(symbols.metroProvider),
                typeArgs = listOf(binding.typeKey.type),
                args = listOf(lambda),
              )
            }
          } else {
            reportCompilerBug("Unknown graph dependency type")
          }
        }
      }
    }

  context(scope: IrBuilderWithScope)
  private fun generateBindingArguments(
    targetParams: Parameters,
    function: IrFunction,
    binding: IrBinding,
    fieldInitKey: IrTypeKey?,
  ): List<IrExpression?> =
    with(scope) {
      val params = function.parameters()
      // TODO only value args are supported atm
      var paramsToMap = buildList {
        if (
          binding is IrBinding.Provided &&
            targetParams.dispatchReceiverParameter?.type?.rawTypeOrNull()?.isObject != true
        ) {
          targetParams.dispatchReceiverParameter?.let(::add)
        }
        addAll(targetParams.regularParameters.filterNot { it.isAssisted })
      }

      // Handle case where function has more parameters than the binding
      // This can happen when parameters are inherited from ancestor classes
      if (
        binding is IrBinding.MembersInjected && function.regularParameters.size > paramsToMap.size
      ) {
        // For MembersInjected, we need to look at the target class and its ancestors
        val nameToParam = mutableMapOf<Name, Parameter>()
        val targetClass = pluginContext.referenceClass(binding.targetClassId)?.owner
        targetClass // Look for inject methods in the target class and its ancestors
          ?.getAllSuperTypes(excludeSelf = false, excludeAny = true)
          ?.forEach { type ->
            val clazz = type.rawType()
            membersInjectorTransformer
              .getOrGenerateInjector(clazz)
              ?.declaredInjectFunctions
              ?.forEach { (_, params) ->
                for (param in params.regularParameters) {
                  nameToParam.putIfAbsent(param.name, param)
                }
              }
          }
        // Construct the list of parameters in order determined by the function
        paramsToMap =
          function.regularParameters.mapNotNull { functionParam -> nameToParam[functionParam.name] }
        // If we still have a mismatch, log a detailed error
        check(function.regularParameters.size == paramsToMap.size) {
          """
          Inconsistent parameter types for type ${binding.typeKey}!
          Input type keys:
            - ${paramsToMap.map { it.typeKey }.joinToString()}
          Binding parameters (${function.kotlinFqName}):
            - ${function.regularParameters.map { IrContextualTypeKey.from(it).typeKey }.joinToString()}
          """
            .trimIndent()
        }
      }

      if (
        binding is IrBinding.Provided &&
          binding.providerFactory.function.correspondingPropertySymbol == null
      ) {
        check(params.regularParameters.size == paramsToMap.size) {
          """
          Inconsistent parameter types for type ${binding.typeKey}!
          Input type keys:
            - ${paramsToMap.map { it.typeKey }.joinToString()}
          Binding parameters (${function.kotlinFqName}):
            - ${function.regularParameters.map { IrContextualTypeKey.from(it).typeKey }.joinToString()}
          """
            .trimIndent()
        }
      }

      return params.regularParameters.mapIndexed { i, param ->
        val contextualTypeKey = paramsToMap[i].contextualTypeKey
        val typeKey = contextualTypeKey.typeKey

        val metroProviderSymbols = symbols.providerSymbolsFor(contextualTypeKey)

        val accessType =
          if (param.contextualTypeKey.requiresProviderInstance) {
            AccessType.PROVIDER
          } else {
            AccessType.INSTANCE
          }

        // TODO consolidate this logic with generateBindingCode
        if (accessType == AccessType.INSTANCE) {
          // IFF the parameter can take a direct instance, try our instance fields
          bindingFieldContext.instanceField(typeKey)?.let { instanceField ->
            return@mapIndexed irGetField(irGet(thisReceiver), instanceField).let {
              with(metroProviderSymbols) { transformMetroProvider(it, contextualTypeKey) }
            }
          }
        }

        val providerInstance =
          bindingFieldContext.providerField(typeKey)?.let { field ->
            // If it's in provider fields, invoke that field
            irGetField(irGet(thisReceiver), field)
          }
            ?: run {
              // Generate binding code for each param
              val paramBinding =
                bindingGraph.requireBinding(contextualTypeKey, IrBindingStack.empty())

              if (paramBinding is IrBinding.Absent) {
                // Null argument expressions get treated as absent in the final call
                return@mapIndexed null
              }

              generateBindingCode(
                paramBinding,
                fieldInitKey = fieldInitKey,
                accessType = accessType,
                contextualTypeKey = param.contextualTypeKey,
              )
            }

        typeAsProviderArgument(
          param.contextualTypeKey,
          providerInstance,
          isAssisted = param.isAssisted,
          isGraphInstance = param.isGraphInstance,
        )
      }
    }

  private fun generateMapKeyLiteral(binding: IrBinding): IrExpression {
    val mapKey =
      when (binding) {
        is IrBinding.Alias -> binding.annotations.mapKeys.first().ir
        is IrBinding.Provided -> binding.annotations.mapKeys.first().ir
        is IrBinding.ConstructorInjected -> binding.annotations.mapKeys.first().ir
        else -> reportCompilerBug("Unsupported multibinding source: $binding")
      }

    val unwrapValue = shouldUnwrapMapKeyValues(mapKey)
    val expression =
      if (!unwrapValue) {
        mapKey
      } else {
        // We can just copy the expression!
        mapKey.arguments[0]!!.deepCopyWithSymbols()
      }

    return expression
  }

  context(scope: IrBuilderWithScope)
  private fun generateMultibindingExpression(
    binding: IrBinding.Multibinding,
    contextualTypeKey: IrContextualTypeKey,
    fieldInitKey: IrTypeKey?,
  ): IrExpression {
    return if (binding.isSet) {
      generateSetMultibindingExpression(binding, contextualTypeKey, fieldInitKey)
    } else {
      // It's a map
      generateMapMultibindingExpression(binding, contextualTypeKey, fieldInitKey)
    }
  }

  context(scope: IrBuilderWithScope)
  private fun generateSetMultibindingExpression(
    binding: IrBinding.Multibinding,
    contextualTypeKey: IrContextualTypeKey,
    fieldInitKey: IrTypeKey?,
  ): IrExpression {
    val elementType = (binding.typeKey.type as IrSimpleType).arguments.single().typeOrFail
    val (collectionProviders, individualProviders) =
      binding.sourceBindings
        .map {
          bindingGraph
            .requireBinding(it, IrBindingStack.empty())
            .expectAs<IrBinding.BindingWithAnnotations>()
        }
        .partition { it.annotations.isElementsIntoSet }
    // If we have any @ElementsIntoSet, we need to use SetFactory
    return if (collectionProviders.isNotEmpty() || contextualTypeKey.requiresProviderInstance) {
      generateSetFactoryExpression(
        elementType,
        collectionProviders,
        individualProviders,
        fieldInitKey,
      )
    } else {
      generateSetBuilderExpression(binding, elementType, fieldInitKey)
    }
  }

  context(scope: IrBuilderWithScope)
  private fun generateSetBuilderExpression(
    binding: IrBinding.Multibinding,
    elementType: IrType,
    fieldInitKey: IrTypeKey?,
  ): IrExpression =
    with(scope) {
      val callee: IrSimpleFunctionSymbol
      val args: List<IrExpression>
      when (val size = binding.sourceBindings.size) {
        0 -> {
          // emptySet()
          callee = symbols.emptySet
          args = emptyList()
        }

        1 -> {
          // setOf(<one>)
          callee = symbols.setOfSingleton
          val provider =
            binding.sourceBindings.first().let {
              bindingGraph.requireBinding(it, IrBindingStack.empty())
            }
          args = listOf(generateMultibindingArgument(provider, fieldInitKey))
        }

        else -> {
          // buildSet(<size>) { ... }
          callee = symbols.buildSetWithCapacity
          args = buildList {
            add(irInt(size))
            add(
              irLambda(
                parent = parent,
                receiverParameter = irBuiltIns.mutableSetClass.typeWith(elementType),
                valueParameters = emptyList(),
                returnType = irBuiltIns.unitType,
                suspend = false,
              ) { function ->
                // This is the mutable set receiver
                val functionReceiver = function.extensionReceiverParameterCompat!!
                binding.sourceBindings
                  .map { bindingGraph.requireBinding(it, IrBindingStack.empty()) }
                  .forEach { provider ->
                    +irInvoke(
                      dispatchReceiver = irGet(functionReceiver),
                      callee = symbols.mutableSetAdd.symbol,
                      args = listOf(generateMultibindingArgument(provider, fieldInitKey)),
                    )
                  }
              }
            )
          }
        }
      }

      return irCall(
          callee = callee,
          type = binding.typeKey.type,
          typeArguments = listOf(elementType),
        )
        .apply {
          for ((i, arg) in args.withIndex()) {
            arguments[i] = arg
          }
        }
    }

  context(scope: IrBuilderWithScope)
  private fun generateSetFactoryExpression(
    elementType: IrType,
    collectionProviders: List<IrBinding>,
    individualProviders: List<IrBinding>,
    fieldInitKey: IrTypeKey?,
  ): IrExpression =
    with(scope) {
      // SetFactory.<String>builder(1, 1)
      //   .addProvider(FileSystemModule_Companion_ProvideString1Factory.create())
      //   .addCollectionProvider(provideString2Provider)
      //   .build()

      // Used to unpack the right provider type
      val valueProviderSymbols = symbols.providerSymbolsFor(elementType)

      // SetFactory.<String>builder(1, 1)
      val builder: IrExpression =
        irInvoke(
          callee = valueProviderSymbols.setFactoryBuilderFunction,
          typeHint = valueProviderSymbols.setFactoryBuilder.typeWith(elementType),
          typeArgs = listOf(elementType),
          args = listOf(irInt(individualProviders.size), irInt(collectionProviders.size)),
        )

      val withProviders =
        individualProviders.fold(builder) { receiver, provider ->
          irInvoke(
            dispatchReceiver = receiver,
            callee = valueProviderSymbols.setFactoryBuilderAddProviderFunction,
            typeHint = builder.type,
            args =
              listOf(
                generateBindingCode(
                  provider,
                  accessType = AccessType.PROVIDER,
                  fieldInitKey = fieldInitKey,
                )
              ),
          )
        }

      // .addProvider(FileSystemModule_Companion_ProvideString1Factory.create())
      val withCollectionProviders =
        collectionProviders.fold(withProviders) { receiver, provider ->
          irInvoke(
            dispatchReceiver = receiver,
            callee = valueProviderSymbols.setFactoryBuilderAddCollectionProviderFunction,
            typeHint = builder.type,
            args =
              listOf(
                generateBindingCode(
                  provider,
                  accessType = AccessType.PROVIDER,
                  fieldInitKey = fieldInitKey,
                )
              ),
          )
        }

      // .build()
      val instance =
        irInvoke(
          dispatchReceiver = withCollectionProviders,
          callee = valueProviderSymbols.setFactoryBuilderBuildFunction,
          typeHint = irBuiltIns.setClass.typeWith(elementType).wrapInProvider(symbols.metroProvider),
        )
      return with(valueProviderSymbols) {
        transformToMetroProvider(instance, irBuiltIns.setClass.typeWith(elementType))
      }
    }

  context(scope: IrBuilderWithScope)
  private fun generateMapMultibindingExpression(
    binding: IrBinding.Multibinding,
    contextualTypeKey: IrContextualTypeKey,
    fieldInitKey: IrTypeKey?,
  ): IrExpression =
    with(scope) {
      // MapFactory.<Integer, Integer>builder(2)
      //   .put(1, FileSystemModule_Companion_ProvideMapInt1Factory.create())
      //   .put(2, provideMapInt2Provider)
      //   .build()
      // MapProviderFactory.<Integer, Integer>builder(2)
      //   .put(1, FileSystemModule_Companion_ProvideMapInt1Factory.create())
      //   .put(2, provideMapInt2Provider)
      //   .build()
      val valueWrappedType = contextualTypeKey.wrappedType.findMapValueType()!!

      val mapTypeArgs = (contextualTypeKey.typeKey.type as IrSimpleType).arguments
      check(mapTypeArgs.size == 2) { "Unexpected map type args: ${mapTypeArgs.joinToString()}" }
      val keyType: IrType = mapTypeArgs[0].typeOrFail
      val rawValueType = mapTypeArgs[1].typeOrFail
      val rawValueTypeMetadata =
        rawValueType.typeOrFail.asContextualTypeKey(null, hasDefault = false)

      // TODO what about Map<String, Provider<Lazy<String>>>?
      //  isDeferrable() but we need to be able to convert back to the middle type
      val useProviderFactory: Boolean = valueWrappedType is WrappedType.Provider

      // Used to unpack the right provider type
      val originalType = contextualTypeKey.toIrType()
      val originalValueType = valueWrappedType.toIrType()
      val originalValueContextKey = originalValueType.asContextualTypeKey(null, hasDefault = false)
      val valueProviderSymbols = symbols.providerSymbolsFor(originalValueType)

      val valueType: IrType = rawValueTypeMetadata.typeKey.type

      val size = binding.sourceBindings.size
      val mapProviderType =
        irBuiltIns.mapClass
          .typeWith(
            keyType,
            if (useProviderFactory) {
              rawValueType.wrapInProvider(symbols.metroProvider)
            } else {
              rawValueType
            },
          )
          .wrapInProvider(symbols.metroProvider)

      if (size == 0) {
        // If it's empty then short-circuit here
        return if (useProviderFactory) {
          // MapProviderFactory.empty()
          val emptyCallee = valueProviderSymbols.mapProviderFactoryEmptyFunction
          if (emptyCallee != null) {
            irInvoke(callee = emptyCallee, typeHint = mapProviderType)
          } else {
            // Call builder().build()
            // build()
            irInvoke(
              callee = valueProviderSymbols.mapProviderFactoryBuilderBuildFunction,
              typeHint = mapProviderType,
              // builder()
              dispatchReceiver =
                irInvoke(
                  callee = valueProviderSymbols.mapProviderFactoryBuilderFunction,
                  typeHint = mapProviderType,
                  args = listOf(irInt(0)),
                ),
            )
          }
        } else {
          // MapFactory.empty()
          irInvoke(
            callee = valueProviderSymbols.mapFactoryEmptyFunction,
            typeHint = mapProviderType,
          )
        }
      }

      val builderFunction =
        if (useProviderFactory) {
          valueProviderSymbols.mapProviderFactoryBuilderFunction
        } else {
          valueProviderSymbols.mapFactoryBuilderFunction
        }
      val builderType =
        if (useProviderFactory) {
          valueProviderSymbols.mapProviderFactoryBuilder
        } else {
          valueProviderSymbols.mapFactoryBuilder
        }

      // MapFactory.<Integer, Integer>builder(2)
      // MapProviderFactory.<Integer, Integer>builder(2)
      val builder: IrExpression =
        irInvoke(
          callee = builderFunction,
          typeArgs = listOf(keyType, valueType),
          typeHint = builderType.typeWith(keyType, valueType),
          args = listOf(irInt(size)),
        )

      val putFunction =
        if (useProviderFactory) {
          valueProviderSymbols.mapProviderFactoryBuilderPutFunction
        } else {
          valueProviderSymbols.mapFactoryBuilderPutFunction
        }
      val putAllFunction =
        if (useProviderFactory) {
          valueProviderSymbols.mapProviderFactoryBuilderPutAllFunction
        } else {
          valueProviderSymbols.mapFactoryBuilderPutAllFunction
        }

      val withProviders =
        binding.sourceBindings
          .map { bindingGraph.requireBinding(it, IrBindingStack.empty()) }
          .fold(builder) { receiver, sourceBinding ->
            val providerTypeMetadata = sourceBinding.contextualTypeKey

            // TODO FIR this should be an error actually
            val isMap = providerTypeMetadata.typeKey.type.rawType().symbol == irBuiltIns.mapClass

            val putter =
              if (isMap) {
                // use putAllFunction
                // .putAll(1, FileSystemModule_Companion_ProvideMapInt1Factory.create())
                // TODO is this only for inheriting in GraphExtensions?
                TODO("putAll isn't yet supported")
              } else {
                // .put(1, FileSystemModule_Companion_ProvideMapInt1Factory.create())
                putFunction
              }
            irInvoke(
              dispatchReceiver = receiver,
              callee = putter,
              typeHint = builder.type,
              args =
                listOf(
                  generateMapKeyLiteral(sourceBinding),
                  generateBindingCode(
                      sourceBinding,
                      accessType = AccessType.PROVIDER,
                      fieldInitKey = fieldInitKey,
                    )
                    .let {
                      with(valueProviderSymbols) {
                        transformMetroProvider(it, originalValueContextKey)
                      }
                    },
                ),
            )
          }

      // .build()
      val buildFunction =
        if (useProviderFactory) {
          valueProviderSymbols.mapProviderFactoryBuilderBuildFunction
        } else {
          valueProviderSymbols.mapFactoryBuilderBuildFunction
        }

      val instance =
        irInvoke(
          dispatchReceiver = withProviders,
          callee = buildFunction,
          typeHint = mapProviderType,
        )
      return with(valueProviderSymbols) { transformToMetroProvider(instance, originalType) }
    }

  context(scope: IrBuilderWithScope)
  private fun generateMultibindingArgument(
    provider: IrBinding,
    fieldInitKey: IrTypeKey?,
  ): IrExpression =
    with(scope) {
      val bindingCode =
        generateBindingCode(provider, accessType = AccessType.PROVIDER, fieldInitKey = fieldInitKey)
      return typeAsProviderArgument(
        contextKey = IrContextualTypeKey.create(provider.typeKey),
        bindingCode = bindingCode,
        isAssisted = false,
        isGraphInstance = false,
      )
    }
}
