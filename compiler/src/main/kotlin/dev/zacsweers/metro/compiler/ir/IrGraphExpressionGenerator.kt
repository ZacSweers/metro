// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import dev.zacsweers.metro.compiler.Symbols
import dev.zacsweers.metro.compiler.expectAs
import dev.zacsweers.metro.compiler.graph.WrappedType
import dev.zacsweers.metro.compiler.ir.parameters.Parameter
import dev.zacsweers.metro.compiler.ir.parameters.Parameters
import dev.zacsweers.metro.compiler.ir.parameters.parameters
import dev.zacsweers.metro.compiler.ir.parameters.wrapInProvider
import dev.zacsweers.metro.compiler.ir.transformers.AssistedFactoryTransformer
import dev.zacsweers.metro.compiler.ir.transformers.BindingContainerTransformer
import dev.zacsweers.metro.compiler.ir.transformers.MembersInjectorTransformer
import dev.zacsweers.metro.compiler.letIf
import dev.zacsweers.metro.compiler.reportCompilerBug
import dev.zacsweers.metro.compiler.tracing.Tracer
import org.jetbrains.kotlin.ir.builders.IrBlockBodyBuilder
import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irCallConstructor
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irGetObject
import org.jetbrains.kotlin.ir.builders.irInt
import org.jetbrains.kotlin.ir.builders.irReturn
import org.jetbrains.kotlin.ir.builders.parent
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.expressions.IrBody
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
import org.jetbrains.kotlin.ir.util.isObject
import org.jetbrains.kotlin.ir.util.isStatic
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.util.primaryConstructor
import org.jetbrains.kotlin.name.Name

internal class IrGraphExpressionGenerator
private constructor(
  context: IrMetroContext,
  private val node: DependencyGraphNode,
  private val thisReceiver: IrValueParameter,
  private val bindingPropertyContext: BindingPropertyContext,
  private val bindingGraph: IrBindingGraph,
  private val bindingContainerTransformer: BindingContainerTransformer,
  private val membersInjectorTransformer: MembersInjectorTransformer,
  private val assistedFactoryTransformer: AssistedFactoryTransformer,
  private val graphExtensionGenerator: IrGraphExtensionGenerator,
  private val parentTracer: Tracer,
  private val getterPropertyFor:
    (
      IrBinding, IrContextualTypeKey, IrBuilderWithScope.(IrGraphExpressionGenerator) -> IrBody,
    ) -> IrProperty,
) : IrMetroContext by context {

  class Factory(
    private val context: IrMetroContext,
    private val node: DependencyGraphNode,
    private val bindingPropertyContext: BindingPropertyContext,
    private val bindingGraph: IrBindingGraph,
    private val bindingContainerTransformer: BindingContainerTransformer,
    private val membersInjectorTransformer: MembersInjectorTransformer,
    private val assistedFactoryTransformer: AssistedFactoryTransformer,
    private val graphExtensionGenerator: IrGraphExtensionGenerator,
    private val parentTracer: Tracer,
    private val getterPropertyFor:
      (
        IrBinding, IrContextualTypeKey, IrBuilderWithScope.(IrGraphExpressionGenerator) -> IrBody,
      ) -> IrProperty,
  ) {
    fun create(thisReceiver: IrValueParameter): IrGraphExpressionGenerator {
      return IrGraphExpressionGenerator(
        context = context,
        node = node,
        thisReceiver = thisReceiver,
        bindingPropertyContext = bindingPropertyContext,
        bindingGraph = bindingGraph,
        bindingContainerTransformer = bindingContainerTransformer,
        membersInjectorTransformer = membersInjectorTransformer,
        assistedFactoryTransformer = assistedFactoryTransformer,
        graphExtensionGenerator = graphExtensionGenerator,
        parentTracer = parentTracer,
        getterPropertyFor = getterPropertyFor,
      )
    }
  }

  enum class AccessType {
    INSTANCE,
    // note: maybe rename this to PROVIDER_LIKE or PROVIDER_OR_FACTORY
    PROVIDER,
  }

  private val wrappedTypeGenerators = listOf(IrOptionalExpressionGenerator).associateBy { it.key }

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

      if (
        accessType != AccessType.INSTANCE &&
          binding is IrBinding.ConstructorInjected &&
          binding.isAssisted
      ) {
        // Should be caught in FIR
        reportCompilerBug("Assisted inject factories should only be accessed as instances")
      }

      val metroProviderSymbols = metroSymbols.providerSymbolsFor(contextualTypeKey)

      // If we're initializing the field for this key, don't ever try to reach for an existing
      // provider for it.
      // This is important for cases like DelegateFactory and breaking cycles.
      if (fieldInitKey == null || fieldInitKey != binding.typeKey) {
        if (bindingPropertyContext.hasKey(binding.typeKey)) {
          bindingPropertyContext.providerProperty(binding.typeKey)?.let {
            val providerInstance =
              irGetProperty(irGet(thisReceiver), it).let {
                with(metroProviderSymbols) { transformMetroProvider(it, contextualTypeKey) }
              }
            return if (accessType == AccessType.INSTANCE) {
              irInvoke(providerInstance, callee = metroSymbols.providerInvoke)
            } else {
              providerInstance
            }
          }
          bindingPropertyContext.instanceProperty(binding.typeKey)?.let {
            val instance = irGetProperty(irGet(thisReceiver), it)
            return if (accessType == AccessType.INSTANCE) {
              instance
            } else {
              with(metroProviderSymbols) {
                transformMetroProvider(
                  instanceFactory(binding.typeKey.type, instance),
                  contextualTypeKey,
                )
              }
            }
          }
          // Should never get here
          reportCompilerBug("Unable to find instance or provider field for ${binding.typeKey}")
        }
      }

      return when (binding) {
        is IrBinding.ConstructorInjected -> {
          // Example_Factory.create(...)
          binding.classFactory
            .invokeCreateExpression(binding.typeKey) { createFunction, parameters ->
              generateBindingArguments(
                targetParams = parameters,
                function = createFunction,
                binding = binding,
                fieldInitKey = null,
              )
            }
            .let { factoryInstance ->
              val isAssistedInject = binding.classFactory.isAssistedInject
              if (isAssistedInject) {
                return@let factoryInstance
              }

              factoryInstance.transformAccessIfNeeded(
                accessType,
                AccessType.PROVIDER,
                binding.typeKey.type,
              )
            }
        }

        is IrBinding.CustomWrapper -> {
          val generator =
            wrappedTypeGenerators[binding.wrapperKey]
              ?: reportCompilerBug("No generator found for wrapper key: ${binding.wrapperKey}")

          val delegateBinding = bindingGraph.findBinding(binding.wrappedContextKey.typeKey)
          val isAbsentInGraph = delegateBinding == null
          val wrappedInstance =
            if (!isAbsentInGraph) {
              generateBindingCode(
                delegateBinding,
                binding.wrappedContextKey,
                accessType = AccessType.INSTANCE,
                fieldInitKey = fieldInitKey,
              )
            } else if (binding.allowsAbsent) {
              null
            } else {
              reportCompilerBug("No delegate binding for wrapped type ${binding.typeKey}!")
            }
          generator
            .generate(binding, wrappedInstance)
            .transformAccessIfNeeded(
              accessType,
              AccessType.INSTANCE,
              binding.typeKey.type,
              useInstanceFactory = false,
            )
        }

        is IrBinding.ObjectClass -> {
          irGetObject(binding.type.symbol)
            .transformAccessIfNeeded(accessType, AccessType.INSTANCE, binding.typeKey.type)
        }

        is IrBinding.Alias -> {
          // For binds functions, just use the backing type
          val aliasedBinding = binding.aliasedBinding(bindingGraph)
          check(aliasedBinding != binding) { "Aliased binding aliases itself" }
          return generateBindingCode(
            aliasedBinding,
            accessType = accessType,
            fieldInitKey = fieldInitKey,
          )
        }

        is IrBinding.Provided -> {
          val providerFactory =
            bindingContainerTransformer.getOrLookupProviderFactory(binding)
              ?: reportCompilerBug(
                "No factory found for Provided binding ${binding.typeKey}. This is likely a bug in the Metro compiler, please report it to the issue tracker."
              )

          // Invoke its factory's create() function
          providerFactory
            .invokeCreateExpression(binding.typeKey) { createFunction, params ->
              generateBindingArguments(
                targetParams = params,
                function = createFunction,
                binding = binding,
                fieldInitKey = fieldInitKey,
              )
            }
            .transformAccessIfNeeded(accessType, AccessType.PROVIDER, binding.typeKey.type)
        }

        is IrBinding.Assisted -> {
          // Example9_Factory_Impl.create(example9Provider);
          val factoryImpl = assistedFactoryTransformer.getOrGenerateImplClass(binding.type)

          val targetBinding = bindingGraph.requireBinding(binding.target.typeKey)

          // Assisted-inject factories don't implement Provider
          val delegateFactory =
            generateBindingCode(
              targetBinding,
              accessType = AccessType.INSTANCE,
              fieldInitKey = fieldInitKey,
            )

          val factoryProvider = with(factoryImpl) { invokeCreate(delegateFactory) }

          factoryProvider.transformAccessIfNeeded(
            accessType,
            AccessType.PROVIDER,
            targetBinding.typeKey.type,
          )
        }

        is IrBinding.Multibinding -> {
          generateMultibindingExpression(binding, contextualTypeKey, accessType, fieldInitKey).let {
            if (accessType == AccessType.INSTANCE) {
              it
            } else {
              with(metroProviderSymbols) { transformMetroProvider(it, contextualTypeKey) }
            }
          }
        }

        is IrBinding.MembersInjected -> {
          val injectedClass = referenceClass(binding.targetClassId)!!.owner
          val injectedType = injectedClass.defaultType
          val injectorClass =
            membersInjectorTransformer.getOrGenerateInjector(injectedClass)?.injectorClass

          if (injectorClass == null) {
            // Return a noop
            val noopInjector =
              irInvoke(
                dispatchReceiver = irGetObject(metroSymbols.metroMembersInjectors),
                callee = metroSymbols.metroMembersInjectorsNoOp,
                typeArgs = listOf(injectedType),
              )
            val noopProvider =
              noopInjector.transformAccessIfNeeded(
                requested = AccessType.PROVIDER,
                actual = AccessType.INSTANCE,
                type = injectedType,
              )
            with(metroProviderSymbols) { transformMetroProvider(noopProvider, contextualTypeKey) }
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

            // InjectableClass_MembersInjector.create(stringValueProvider,
            // exampleComponentProvider)
            val provider =
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
                )
                .transformAccessIfNeeded(
                  requested = AccessType.PROVIDER,
                  actual = AccessType.INSTANCE,
                  type = injectedType,
                )

            with(metroProviderSymbols) { transformMetroProvider(provider, contextualTypeKey) }
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
                irGetProperty(
                  irGet(binding.classReceiverParameter),
                  binding.providerPropertyAccess!!.property,
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

          if (options.enableGraphImplClassAsReturnType) {
            // This is probably not the right spot to change the return type, but the IrClass
            // implementation is not exposed otherwise.
            binding.accessor.returnType = extensionImpl.defaultType
          }

          val ctor = extensionImpl.primaryConstructor!!
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
            .transformAccessIfNeeded(accessType, AccessType.INSTANCE, binding.typeKey.type)
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
          irCallConstructor(
              constructor.symbol,
              binding.accessor.typeParameters.map { it.defaultType },
            )
            .apply {
              // Pass the parent graph instance
              arguments[0] =
                generateBindingCode(
                  bindingGraph.requireBinding(parameters.regularParameters.single().typeKey),
                  accessType = AccessType.INSTANCE,
                )
            }
            .transformAccessIfNeeded(accessType, AccessType.INSTANCE, binding.typeKey.type)
        }

        is IrBinding.GraphDependency -> {
          val ownerKey = binding.ownerKey
          if (binding.propertyAccess != null) {
            // Just get the property
            irGetProperty(
              irGet(binding.propertyAccess.receiverParameter),
              binding.propertyAccess.property,
            )
          } else if (binding.getter != null) {
            val graphInstanceField =
              bindingPropertyContext.instanceProperty(ownerKey)
                ?: reportCompilerBug(
                  "No matching included type instance found for type $ownerKey while processing ${node.typeKey}. Available instance fields ${bindingPropertyContext.availableInstanceKeys}"
                )

            val getterContextKey = IrContextualTypeKey.from(binding.getter)

            val invokeGetter =
              irInvoke(
                dispatchReceiver = irGetProperty(irGet(thisReceiver), graphInstanceField),
                callee = binding.getter.symbol,
                typeHint = binding.typeKey.type,
              )

            if (getterContextKey.isWrappedInProvider) {
              // It's already a provider
              invokeGetter
            } else {
              wrapInProviderFunction(binding.typeKey.type) {
                  if (getterContextKey.isWrappedInProvider) {
                    irInvoke(invokeGetter, callee = metroSymbols.providerInvoke)
                  } else if (getterContextKey.isWrappedInLazy) {
                    irInvoke(invokeGetter, callee = metroSymbols.lazyGetValue)
                  } else {
                    invokeGetter
                  }
                }
                .transformAccessIfNeeded(accessType, AccessType.PROVIDER, binding.typeKey.type)
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
      var paramsToMap = buildList {
        if (
          binding is IrBinding.Provided &&
            targetParams.dispatchReceiverParameter?.type?.rawTypeOrNull()?.isObject != true
        ) {
          targetParams.dispatchReceiverParameter?.let(::add)
        }
        addAll(targetParams.contextParameters.filterNot { it.isAssisted })
        targetParams.extensionReceiverParameter?.let(::add)
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

      return params.nonDispatchParameters.mapIndexed { i, param ->
        val contextualTypeKey = paramsToMap[i].contextualTypeKey
        val typeKey = contextualTypeKey.typeKey

        val metroProviderSymbols = metroSymbols.providerSymbolsFor(contextualTypeKey)

        val accessType =
          if (param.contextualTypeKey.requiresProviderInstance) {
            AccessType.PROVIDER
          } else {
            AccessType.INSTANCE
          }

        // TODO consolidate this logic with generateBindingCode
        if (accessType == AccessType.INSTANCE) {
          // IFF the parameter can take a direct instance, try our instance fields
          bindingPropertyContext.instanceProperty(typeKey)?.let { instanceField ->
            return@mapIndexed irGetProperty(irGet(thisReceiver), instanceField).let {
              with(metroProviderSymbols) { transformMetroProvider(it, contextualTypeKey) }
            }
          }
        }

        val providerInstance =
          bindingPropertyContext.providerProperty(typeKey)?.let { field ->
            // If it's in provider fields, invoke that field
            irGetProperty(irGet(thisReceiver), field)
          }
            ?: run {
              // Generate binding code for each param
              val paramBinding = bindingGraph.requireBinding(contextualTypeKey)

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
    accessType: AccessType,
    fieldInitKey: IrTypeKey?,
  ): IrExpression {
    val transformedContextKey =
      contextualTypeKey.letIf(contextualTypeKey.isWrappedInLazy) {
        // need to change this to a Provider for our generation
        contextualTypeKey.stripLazy().wrapInProvider()
      }
    return if (binding.isSet) {
      generateSetMultibindingExpression(binding, accessType, transformedContextKey, fieldInitKey)
    } else {
      // It's a map
      generateMapMultibindingExpression(binding, transformedContextKey, accessType, fieldInitKey)
    }
  }

  context(scope: IrBuilderWithScope)
  private fun generateSetMultibindingExpression(
    binding: IrBinding.Multibinding,
    accessType: AccessType,
    contextualTypeKey: IrContextualTypeKey,
    fieldInitKey: IrTypeKey?,
  ): IrExpression =
    with(scope) {
      // Use lazy property to cache the multibinding
      val property =
        getterPropertyFor(binding, contextualTypeKey) { expressionGenerator ->
          irExprBodySafe(
            expressionGenerator.buildSetMultibindingExpression(
              binding,
              accessType,
              contextualTypeKey,
              fieldInitKey,
            )
          )
        }

      // Return the property access, which will be the provider
      return irGetProperty(irGet(thisReceiver), property)
    }

  context(scope: IrBuilderWithScope)
  private fun buildSetMultibindingExpression(
    binding: IrBinding.Multibinding,
    accessType: AccessType,
    contextualTypeKey: IrContextualTypeKey,
    fieldInitKey: IrTypeKey?,
  ): IrExpression {
    val elementType = (binding.typeKey.type as IrSimpleType).arguments.single().typeOrFail
    val (collectionProviders, individualProviders) =
      binding.sourceBindings
        .map { bindingGraph.requireBinding(it).expectAs<IrBinding.BindingWithAnnotations>() }
        .partition { it.annotations.isElementsIntoSet }

    val actualAccessType: AccessType
    // If we have any @ElementsIntoSet, we need to use SetFactory
    val instance =
      if (collectionProviders.isNotEmpty() || accessType == AccessType.PROVIDER) {
        actualAccessType = AccessType.PROVIDER
        generateSetFactoryExpression(
          elementType,
          collectionProviders,
          individualProviders,
          fieldInitKey,
        )
      } else {
        actualAccessType = AccessType.INSTANCE
        generateSetBuilderExpression(binding, elementType, fieldInitKey)
      }
    return instance.transformAccessIfNeeded(
      requested = accessType,
      actual = actualAccessType,
      type = contextualTypeKey.toIrType(),
    )
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
          callee = metroSymbols.emptySet
          args = emptyList()
        }

        1 -> {
          // setOf(<one>)
          callee = metroSymbols.setOfSingleton
          val provider = binding.sourceBindings.first().let { bindingGraph.requireBinding(it) }
          args =
            listOf(
              generateMultibindingArgument(
                provider,
                provider.contextualTypeKey,
                fieldInitKey,
                accessType = AccessType.INSTANCE,
              )
            )
        }

        else -> {
          // buildSet(<size>) { ... }
          callee = metroSymbols.buildSetWithCapacity
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
                  .map { bindingGraph.requireBinding(it) }
                  .forEach { binding ->
                    +irInvoke(
                      dispatchReceiver = irGet(functionReceiver),
                      callee = metroSymbols.mutableSetAdd.symbol,
                      args =
                        listOf(
                          generateMultibindingArgument(
                            binding,
                            binding.contextualTypeKey,
                            fieldInitKey,
                            accessType = AccessType.PROVIDER,
                          )
                        ),
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
  private fun generateMapBuilderExpression(
    binding: IrBinding.Multibinding,
    size: Int,
    keyType: IrType,
    valueType: IrType,
    originalValueContextKey: IrContextualTypeKey,
    valueAccessType: AccessType,
    fieldInitKey: IrTypeKey?,
  ): IrExpression =
    with(scope) {
      // buildMap(size) { put(key, value) ... }
      return irCall(
          callee = metroSymbols.buildMapWithCapacity,
          type = irBuiltIns.mapClass.typeWith(keyType, valueType),
          typeArguments = listOf(keyType, valueType),
        )
        .apply {
          arguments[0] = irInt(size)
          arguments[1] =
            irLambda(
              parent = parent,
              receiverParameter = irBuiltIns.mutableMapClass.typeWith(keyType, valueType),
              valueParameters = emptyList(),
              returnType = irBuiltIns.unitType,
              suspend = false,
            ) { function ->
              // This is the mutable map receiver
              val functionReceiver = function.extensionReceiverParameterCompat!!
              binding.sourceBindings
                .map { bindingGraph.requireBinding(it) }
                .forEach { binding ->
                  +irInvoke(
                    dispatchReceiver = irGet(functionReceiver),
                    callee = metroSymbols.mutableMapPut.symbol,
                    args =
                      listOf(
                        generateMapKeyLiteral(binding),
                        generateMultibindingArgument(
                          binding,
                          originalValueContextKey,
                          fieldInitKey,
                          accessType = valueAccessType,
                        ),
                      ),
                  )
                }
            }
        }
    }

  // TODO
  //  test failures
  //  bindingpropertycollector - if a provider is used in multiple multibindings, field instead of
  // lazy getter?
  //  or - geneate property, for instance. If a provider is requested instead, generate that then
  // update the instance access to call that + get()
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
      val valueProviderSymbols = metroSymbols.providerSymbolsFor(elementType)

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
          typeHint =
            irBuiltIns.setClass.typeWith(elementType).wrapInProvider(metroSymbols.metroProvider),
        )
      return with(valueProviderSymbols) {
        transformToMetroProvider(instance, irBuiltIns.setClass.typeWith(elementType))
      }
    }

  context(scope: IrBuilderWithScope)
  private fun generateMapMultibindingExpression(
    binding: IrBinding.Multibinding,
    contextualTypeKey: IrContextualTypeKey,
    accessType: AccessType,
    fieldInitKey: IrTypeKey?,
  ): IrExpression =
    with(scope) {
      // Use lazy property to cache the multibinding and handle different access patterns
      val property =
        getterPropertyFor(binding, contextualTypeKey) { expressionGenerator ->
          irExprBodySafe(
            expressionGenerator.generateMapMultibindingExpressionImpl(
              binding,
              contextualTypeKey,
              accessType,
              fieldInitKey,
            )
          )
        }

      // Return the property access, which will be the provider
      return irGetProperty(irGet(thisReceiver), property)
    }

  context(scope: IrBuilderWithScope)
  private fun generateMapMultibindingExpressionImpl(
    binding: IrBinding.Multibinding,
    contextualTypeKey: IrContextualTypeKey,
    accessType: AccessType,
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
        rawValueType.typeOrFail.asContextualTypeKey(
          null,
          hasDefault = false,
          patchMutableCollections = false,
          declaration = binding.declaration,
        )

      // TODO what about Map<String, Provider<Lazy<String>>>?
      //  isDeferrable() but we need to be able to convert back to the middle type
      val valueIsWrappedInProvider: Boolean = valueWrappedType is WrappedType.Provider

      // Used to unpack the right provider type
      val originalValueType = valueWrappedType.toIrType()
      val originalValueContextKey =
        originalValueType.asContextualTypeKey(
          null,
          hasDefault = false,
          patchMutableCollections = false,
          declaration = binding.declaration,
        )
      val valueProviderSymbols = metroSymbols.providerSymbolsFor(originalValueType)

      val valueType: IrType = rawValueTypeMetadata.typeKey.type

      val size = binding.sourceBindings.size
      val mapProviderType =
        irBuiltIns.mapClass
          .typeWith(
            keyType,
            if (valueIsWrappedInProvider) {
              rawValueType.wrapInProvider(metroSymbols.metroProvider)
            } else {
              rawValueType
            },
          )
          .wrapInProvider(metroSymbols.metroProvider)

      val instance =
        if (size == 0) {
          if (accessType == AccessType.INSTANCE) {
            // Just return emptyMap() for instance access
            return irInvoke(
              callee = metroSymbols.emptyMap,
              typeHint = irBuiltIns.mapClass.typeWith(keyType, rawValueType),
              typeArgs = listOf(keyType, rawValueType),
            )
          } else if (valueIsWrappedInProvider) {
            // MapProviderFactory.empty()
            val emptyCallee = valueProviderSymbols.mapProviderFactoryEmptyFunction
            if (emptyCallee != null) {
              irInvoke(
                callee = emptyCallee,
                typeHint = mapProviderType,
                typeArgs = listOf(keyType, rawValueType),
              )
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
              typeArgs = listOf(keyType, rawValueType),
            )
          }
        } else {
          // Multiple elements
          if (accessType == AccessType.INSTANCE) {
            return generateMapBuilderExpression(
              binding,
              size,
              keyType,
              valueWrappedType.toIrType(),
              originalValueContextKey,
              if (valueIsWrappedInProvider) AccessType.PROVIDER else AccessType.INSTANCE,
              fieldInitKey,
            )
          }

          val builderFunction =
            if (valueIsWrappedInProvider) {
              valueProviderSymbols.mapProviderFactoryBuilderFunction
            } else {
              valueProviderSymbols.mapFactoryBuilderFunction
            }
          val builderType =
            if (valueIsWrappedInProvider) {
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
            if (valueIsWrappedInProvider) {
              valueProviderSymbols.mapProviderFactoryBuilderPutFunction
            } else {
              valueProviderSymbols.mapFactoryBuilderPutFunction
            }
          val putAllFunction =
            if (valueIsWrappedInProvider) {
              valueProviderSymbols.mapProviderFactoryBuilderPutAllFunction
            } else {
              valueProviderSymbols.mapFactoryBuilderPutAllFunction
            }

          val withProviders =
            binding.sourceBindings
              .map { bindingGraph.requireBinding(it) }
              .fold(builder) { receiver, sourceBinding ->
                val providerTypeMetadata = sourceBinding.contextualTypeKey

                val isMap =
                  providerTypeMetadata.typeKey.type.rawType().symbol == irBuiltIns.mapClass

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
                      generateMultibindingArgument(
                        sourceBinding,
                        originalValueContextKey.wrapInProvider(),
                        fieldInitKey,
                        accessType = AccessType.PROVIDER,
                      ),
                    ),
                )
              }

          // .build()
          val buildFunction =
            if (valueIsWrappedInProvider) {
              valueProviderSymbols.mapProviderFactoryBuilderBuildFunction
            } else {
              valueProviderSymbols.mapFactoryBuilderBuildFunction
            }

          irInvoke(
            dispatchReceiver = withProviders,
            callee = buildFunction,
            typeHint = mapProviderType,
          )
        }

      // Always a provider instance in this branch, no need to transform access type
      val providerInstance =
        with(valueProviderSymbols) { transformToMetroProvider(instance, mapProviderType) }

      return providerInstance
    }

  context(scope: IrBuilderWithScope)
  private fun generateMultibindingArgument(
    provider: IrBinding,
    contextKey: IrContextualTypeKey,
    fieldInitKey: IrTypeKey?,
    accessType: AccessType,
  ): IrExpression =
    with(scope) {
      val bindingCode =
        generateBindingCode(provider, accessType = accessType, fieldInitKey = fieldInitKey)

      return typeAsProviderArgument(
        contextKey = contextKey,
        bindingCode = bindingCode,
        isAssisted = false,
        isGraphInstance = false,
      )
    }

  // TODO move transformMetroProvider into this too
  context(scope: IrBuilderWithScope)
  private fun IrExpression.transformAccessIfNeeded(
    requested: AccessType,
    actual: AccessType,
    type: IrType,
    useInstanceFactory: Boolean = true,
  ): IrExpression {
    return when (requested) {
      actual -> this
      AccessType.PROVIDER -> {
        if (useInstanceFactory) {
          // actual is an instance, wrap it
          wrapInInstanceFactory(type)
        } else {
          scope.wrapInProviderFunction(type) { this@transformAccessIfNeeded }
        }
      }
      AccessType.INSTANCE -> {
        // actual is a provider but we want instance
        unwrapProvider(type)
      }
    }
  }

  context(scope: IrBuilderWithScope)
  private fun IrExpression.wrapInInstanceFactory(type: IrType): IrExpression {
    return with(scope) { instanceFactory(type, this@wrapInInstanceFactory) }
  }

  private fun IrBuilderWithScope.wrapInProviderFunction(
    type: IrType,
    returnExpression: IrBlockBodyBuilder.(function: IrSimpleFunction) -> IrExpression,
  ): IrExpression {
    val lambda =
      irLambda(parent = this.parent, receiverParameter = null, emptyList(), type, suspend = false) {
        +irReturn(returnExpression(it))
      }
    return irInvoke(
      dispatchReceiver = null,
      callee = metroSymbols.metroProviderFunction,
      typeHint = type.wrapInProvider(metroSymbols.metroProvider),
      typeArgs = listOf(type),
      args = listOf(lambda),
    )
  }

  context(scope: IrBuilderWithScope)
  private fun IrExpression.unwrapProvider(type: IrType): IrExpression {
    return with(scope) {
      irInvoke(this@unwrapProvider, callee = metroSymbols.providerInvoke, typeHint = type)
    }
  }
}
