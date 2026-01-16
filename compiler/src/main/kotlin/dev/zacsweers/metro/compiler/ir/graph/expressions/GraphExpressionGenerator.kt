// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.graph.expressions

import dev.zacsweers.metro.compiler.expectAsOrNull
import dev.zacsweers.metro.compiler.ir.IrContextualTypeKey
import dev.zacsweers.metro.compiler.ir.IrMetroContext
import dev.zacsweers.metro.compiler.ir.IrTypeKey
import dev.zacsweers.metro.compiler.ir.ParentContext
import dev.zacsweers.metro.compiler.ir.graph.BindingPropertyContext
import dev.zacsweers.metro.compiler.ir.graph.DependencyGraphNode
import dev.zacsweers.metro.compiler.ir.graph.IrBinding
import dev.zacsweers.metro.compiler.ir.graph.IrBindingGraph
import dev.zacsweers.metro.compiler.ir.graph.IrGraphExtensionGenerator
import dev.zacsweers.metro.compiler.ir.graph.generatedGraphExtensionData
import dev.zacsweers.metro.compiler.ir.irGetProperty
import dev.zacsweers.metro.compiler.ir.irInvoke
import dev.zacsweers.metro.compiler.ir.metroFunctionOf
import dev.zacsweers.metro.compiler.ir.parameters.Parameter
import dev.zacsweers.metro.compiler.ir.parameters.Parameters
import dev.zacsweers.metro.compiler.ir.parameters.parameters
import dev.zacsweers.metro.compiler.ir.rawTypeOrNull
import dev.zacsweers.metro.compiler.ir.regularParameters
import dev.zacsweers.metro.compiler.ir.requireSimpleFunction
import dev.zacsweers.metro.compiler.ir.transformers.AssistedFactoryTransformer
import dev.zacsweers.metro.compiler.ir.transformers.BindingContainerTransformer
import dev.zacsweers.metro.compiler.ir.transformers.MembersInjectorTransformer
import dev.zacsweers.metro.compiler.ir.typeAsProviderArgument
import dev.zacsweers.metro.compiler.ir.wrapInProvider
import dev.zacsweers.metro.compiler.memoize
import dev.zacsweers.metro.compiler.reportCompilerBug
import dev.zacsweers.metro.compiler.symbols.Symbols
import dev.zacsweers.metro.compiler.tracing.TraceScope
import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.builders.irCallConstructor
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irGetObject
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.types.defaultType
import org.jetbrains.kotlin.ir.util.allParameters
import org.jetbrains.kotlin.ir.util.companionObject
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.ir.util.isObject
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.util.primaryConstructor
import org.jetbrains.kotlin.ir.util.superClass
import org.jetbrains.kotlin.name.Name

internal class GraphExpressionGenerator
private constructor(
  context: IrMetroContext,
  traceScope: TraceScope,
  private val node: DependencyGraphNode,
  override val thisReceiver: IrValueParameter,
  private val bindingPropertyContext: BindingPropertyContext,
  /** All ancestor graphs' binding property contexts, keyed by graph type key. */
  private val ancestorBindingContexts: Map<IrTypeKey, BindingPropertyContext>,
  override val bindingGraph: IrBindingGraph,
  private val bindingContainerTransformer: BindingContainerTransformer,
  private val membersInjectorTransformer: MembersInjectorTransformer,
  private val assistedFactoryTransformer: AssistedFactoryTransformer,
  private val graphExtensionGenerator: IrGraphExtensionGenerator,
) : BindingExpressionGenerator<IrBinding>(context, traceScope) {

  class Factory(
    private val context: IrMetroContext,
    private val node: DependencyGraphNode,
    private val bindingPropertyContext: BindingPropertyContext,
    /** All ancestor graphs' binding property contexts, keyed by graph type key. */
    private val ancestorBindingContexts: Map<IrTypeKey, BindingPropertyContext>,
    private val bindingGraph: IrBindingGraph,
    private val bindingContainerTransformer: BindingContainerTransformer,
    private val membersInjectorTransformer: MembersInjectorTransformer,
    private val assistedFactoryTransformer: AssistedFactoryTransformer,
    private val graphExtensionGenerator: IrGraphExtensionGenerator,
    private val traceScope: TraceScope,
  ) {
    fun create(thisReceiver: IrValueParameter): GraphExpressionGenerator {
      return GraphExpressionGenerator(
        context = context,
        node = node,
        thisReceiver = thisReceiver,
        bindingPropertyContext = bindingPropertyContext,
        ancestorBindingContexts = ancestorBindingContexts,
        bindingGraph = bindingGraph,
        bindingContainerTransformer = bindingContainerTransformer,
        membersInjectorTransformer = membersInjectorTransformer,
        assistedFactoryTransformer = assistedFactoryTransformer,
        graphExtensionGenerator = graphExtensionGenerator,
        traceScope = traceScope,
      )
    }
  }

  private val wrappedTypeGenerators = listOf(IrOptionalExpressionGenerator).associateBy { it.key }
  private val multibindingExpressionGenerator by memoize { MultibindingExpressionGenerator(this) }

  context(scope: IrBuilderWithScope)
  override fun generateBindingCode(
    binding: IrBinding,
    contextualTypeKey: IrContextualTypeKey,
    accessType: AccessType,
    fieldInitKey: IrTypeKey?,
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

      // If we're initializing the field for this key, don't ever try to reach for an existing
      // provider for it.
      // This is important for cases like DelegateFactory and breaking cycles.
      if (fieldInitKey == null || fieldInitKey != binding.typeKey) {
        bindingPropertyContext.get(contextualTypeKey)?.let { (property, storedKey) ->
          val actual =
            if (storedKey.isWrappedInProvider) AccessType.PROVIDER else AccessType.INSTANCE

          return irGetProperty(irGet(thisReceiver), property)
            .toTargetType(
              actual = actual,
              contextualTypeKey = contextualTypeKey,
              allowPropertyGetter = fieldInitKey == null,
            )
        }
      }

      return when (binding) {
        is IrBinding.ConstructorInjected -> {
          val classFactory = binding.classFactory
          val isAssistedInject = classFactory.isAssistedInject
          // Optimization: Skip factory instantiation when possible
          val canBypassFactory = accessType == AccessType.INSTANCE && binding.canBypassFactory()

          if (canBypassFactory) {
            if (classFactory.supportsDirectInvocation(node.metroGraphOrFail)) {
              // Call constructor directly
              val targetConstructor = classFactory.targetConstructor!!
              irCallConstructor(
                  targetConstructor.symbol,
                  binding.type.typeParameters.map { it.defaultType },
                )
                .apply {
                  val args =
                    generateBindingArguments(
                      targetParams = classFactory.targetFunctionParameters,
                      function = targetConstructor,
                      binding = binding,
                      fieldInitKey = fieldInitKey,
                    )
                  for ((i, arg) in args.withIndex()) {
                    if (arg == null) continue
                    arguments[i] = arg
                  }
                }
                .toTargetType(actual = AccessType.INSTANCE, contextualTypeKey = contextualTypeKey)
            } else {
              // Constructor isn't public - call newInstance() on the factory object instead
              // Example_Factory.newInstance(...)
              classFactory
                .invokeNewInstanceExpression(binding.typeKey, Symbols.Names.newInstance) {
                  newInstanceFunction,
                  parameters ->
                  generateBindingArguments(
                    targetParams = parameters,
                    function = newInstanceFunction,
                    binding = binding,
                    fieldInitKey = null,
                  )
                }
                .toTargetType(actual = AccessType.INSTANCE, contextualTypeKey = contextualTypeKey)
            }
          } else {
            // Example_Factory.create(...)
            classFactory
              .invokeCreateExpression(binding.typeKey) { createFunction, parameters ->
                generateBindingArguments(
                  targetParams = parameters,
                  function = createFunction,
                  binding = binding,
                  fieldInitKey = null,
                )
              }
              .let { factoryInstance ->
                if (isAssistedInject) {
                  return@let factoryInstance
                }

                factoryInstance.toTargetType(
                  actual = AccessType.PROVIDER,
                  contextualTypeKey = contextualTypeKey,
                )
              }
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
            .toTargetType(
              actual = AccessType.INSTANCE,
              contextualTypeKey = contextualTypeKey,
              useInstanceFactory = false,
            )
        }

        is IrBinding.ObjectClass -> {
          irGetObject(binding.type.symbol)
            .toTargetType(actual = AccessType.INSTANCE, contextualTypeKey = contextualTypeKey)
        }

        is IrBinding.Alias -> {
          // TODO cache the aliases (or retrieve from binding property collector?)
          // For binds functions, just use the backing type
          val aliasedBinding = binding.aliasedBinding(bindingGraph)
          check(aliasedBinding != binding) { "Aliased binding aliases itself" }
          generateBindingCode(
            aliasedBinding,
            contextualTypeKey = contextualTypeKey.withIrTypeKey(aliasedBinding.typeKey),
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

          // Optimization: Skip factory instantiation when we don't need a provider instance.
          // This applies when accessType is INSTANCE and the providerFactory supports direct
          // invocation
          val canBypassFactory =
            providerFactory.canBypassFactory &&
              // TODO what if the return type is a Provider?
              accessType == AccessType.INSTANCE

          if (canBypassFactory) {
            val providerFunction = providerFactory.function
            val targetParams = providerFactory.parameters

            // If we need a dispatch receiver but couldn't get one, fall back to factory
            if (providerFactory.supportsDirectInvocation(node.metroGraphOrFail)) {
              // Call the provider function directly
              val realFunction =
                providerFactory.realDeclaration?.expectAsOrNull<IrFunction>() ?: providerFunction
              val args =
                generateBindingArguments(
                  targetParams = targetParams,
                  function = realFunction,
                  binding = binding,
                  fieldInitKey = fieldInitKey,
                )

              irInvoke(callee = realFunction.symbol, args = args, typeHint = binding.typeKey.type)
                .toTargetType(actual = AccessType.INSTANCE, contextualTypeKey = contextualTypeKey)
            } else {
              // Function isn't public - call factory's static newInstance() method instead
              providerFactory
                .invokeNewInstanceExpression(binding.typeKey, providerFactory.newInstanceName) {
                  newInstanceFunction,
                  params ->
                  generateBindingArguments(
                    targetParams = params,
                    function = newInstanceFunction,
                    binding = binding,
                    fieldInitKey = fieldInitKey,
                  )
                }
                .toTargetType(actual = AccessType.INSTANCE, contextualTypeKey = contextualTypeKey)
            }
          } else {
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
              .toTargetType(actual = AccessType.PROVIDER, contextualTypeKey = contextualTypeKey)
          }
        }

        is IrBinding.Assisted -> {
          // Example9_Factory_Impl.create(example9Provider);
          val factoryImpl = assistedFactoryTransformer.getOrGenerateImplClass(binding.type)

          val targetBinding = bindingGraph.requireBinding(binding.target.typeKey)

          // Assisted-inject factories don't implement Provider
          val delegateFactory =
            generateBindingCode(
              targetBinding,
              contextualTypeKey = targetBinding.contextualTypeKey,
              accessType = AccessType.INSTANCE,
              fieldInitKey = fieldInitKey,
            )

          val factoryProvider = with(factoryImpl) { invokeCreate(delegateFactory) }

          factoryProvider.toTargetType(
            actual = AccessType.PROVIDER,
            contextualTypeKey = contextualTypeKey,
          )
        }

        is IrBinding.Multibinding -> {
          multibindingExpressionGenerator.generateBindingCode(
            binding,
            contextualTypeKey,
            accessType,
            fieldInitKey,
          )
        }

        is IrBinding.MembersInjected -> {
          val injectedClass = referenceClass(binding.targetClassId)!!.owner
          val injectedType = injectedClass.defaultType

          // When looking for an injector, try the current class.
          // If the current class doesn't have one but the parent does have injections, traverse up
          // until we hit the first injector that does work and use that
          val injectorClass =
            generateSequence(injectedClass) { clazz ->
                clazz.superClass?.takeIf { it.hasAnnotation(Symbols.ClassIds.HasMemberInjections) }
              }
              .firstNotNullOfOrNull { clazz ->
                membersInjectorTransformer.getOrGenerateInjector(clazz)?.injectorClass
              }

          if (injectorClass == null) {
            // Return a noop
            irInvoke(
                dispatchReceiver = irGetObject(metroSymbols.metroMembersInjectors),
                callee = metroSymbols.metroMembersInjectorsNoOp,
                typeArgs = listOf(injectedType),
              )
              .toTargetType(actual = AccessType.INSTANCE, contextualTypeKey = contextualTypeKey)
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
            irInvoke(callee = createFunction, args = args)
              .toTargetType(actual = AccessType.INSTANCE, contextualTypeKey = contextualTypeKey)
          }
        }

        is IrBinding.Absent -> {
          // Should never happen, this should be checked before function/constructor injections.
          reportCompilerBug("Unable to generate code for unexpected Absent binding: $binding")
        }

        is IrBinding.BoundInstance -> {
          // BoundInstance represents either:
          // 1. Self-binding (token == null): graph provides itself via thisReceiver
          // 2. Parent graph binding (token != null): parent graph type accessed via token's
          // receiver
          //
          // Note: Property access on parent graphs uses GraphDependency, not BoundInstance.
          // BoundInstance with token is always the parent graph type itself.
          val receiver = binding.token?.receiverParameter ?: thisReceiver
          when (accessType) {
            AccessType.INSTANCE -> irGet(receiver)
            AccessType.PROVIDER -> {
              irGet(receiver)
                .toTargetType(actual = AccessType.INSTANCE, contextualTypeKey = contextualTypeKey)
            }
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
            .toTargetType(actual = AccessType.INSTANCE, contextualTypeKey = contextualTypeKey)
        }

        is IrBinding.GraphExtensionFactory -> {
          // Get the pre-generated extension implementation that should contain the factory
          val extensionImpl =
            graphExtensionGenerator.getOrBuildGraphExtensionImpl(
              binding.extensionTypeKey,
              node.sourceGraph,
              metroFunctionOf(binding.reportableDeclaration as IrSimpleFunction),
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
              val graphBinding =
                bindingGraph.requireBinding(parameters.regularParameters.single().typeKey)
              arguments[0] =
                generateBindingCode(
                  graphBinding,
                  graphBinding.contextualTypeKey,
                  accessType = AccessType.INSTANCE,
                )
            }
            .toTargetType(contextualTypeKey = contextualTypeKey, actual = AccessType.INSTANCE)
        }

        is IrBinding.GraphDependency -> {
          val ownerKey = binding.ownerKey
          // When propertyAccessToken is set, resolve it and check if the property returns a
          // Provider or scalar
          // When getter is used, the result is wrapped in a provider function
          val (bindingGetter, actual) =
            if (binding.token != null) {
              // Resolve the token to get the actual property from parent's context
              val propertyAccess = resolveToken(binding.token)
              val isScalarProperty = !propertyAccess.isProviderProperty
              propertyAccess.accessProperty() to
                if (isScalarProperty) {
                  AccessType.INSTANCE
                } else {
                  AccessType.PROVIDER
                }
            } else if (binding.getter != null) {
              val graphInstanceProperty =
                bindingPropertyContext.get(IrContextualTypeKey(ownerKey))?.property
                  ?: reportCompilerBug(
                    "No matching included type instance found for type $ownerKey while processing ${node.typeKey}"
                  )

              val getterContextKey = IrContextualTypeKey.from(binding.getter)

              val invokeGetter =
                irInvoke(
                  dispatchReceiver = irGetProperty(irGet(thisReceiver), graphInstanceProperty),
                  callee = binding.getter.symbol,
                  typeHint = binding.typeKey.type,
                )

              val expr =
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
                }
              // getter case always produces a Provider
              expr to AccessType.PROVIDER
            } else {
              reportCompilerBug("Unknown graph dependency type")
            }
          bindingGetter.toTargetType(
            contextualTypeKey = contextualTypeKey,
            actual = actual,
            allowPropertyGetter = binding.token?.let { !it.contextKey.isWrappedInProvider } ?: false,
          )
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
      // TODO clean all this up
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
        // For MembersInjected, we need to look at the supertype bindings which have
        // correctly remapped parameters. Using declaredInjectFunctions directly would
        // give us unmapped type parameters (e.g., T, R instead of String, Int).
        val nameToParam = mutableMapOf<Name, Parameter>()

        // First add this binding's own parameters
        for (param in binding.parameters.regularParameters) {
          nameToParam.putIfAbsent(param.name, param)
        }

        // Then add parameters from supertype MembersInjector bindings (which are remapped)
        for (supertypeKey in binding.supertypeMembersInjectorKeys) {
          val supertypeBinding = bindingGraph.findBinding(supertypeKey.typeKey)
          if (supertypeBinding is IrBinding.MembersInjected) {
            for (param in supertypeBinding.parameters.regularParameters) {
              nameToParam.putIfAbsent(param.name, param)
            }
          }
        }

        // Construct the list of parameters in order determined by the function
        paramsToMap =
          function.allParameters.mapNotNull { functionParam -> nameToParam[functionParam.name] }

        // If we still have a mismatch, log a detailed error
        check(params.allParameters.size == paramsToMap.size) {
          """
          Inconsistent parameter types for type ${binding.typeKey}!
          Input type keys:
            - ${paramsToMap.map { it.typeKey }.joinToString()}
          Binding parameters (${function.kotlinFqName}):
            - ${params.allParameters.map { it.typeKey }.joinToString()}
          """
            .trimIndent()
        }
      }

      if (
        binding is IrBinding.Provided &&
          binding.providerFactory.function.correspondingPropertySymbol == null
      ) {
        check(params.allParameters.size == paramsToMap.size) {
          """
          Inconsistent parameter types for type ${binding.typeKey}!
          Input type keys:
            - ${paramsToMap.map { it.typeKey }.joinToString()}
          Binding parameters (${function.kotlinFqName}):
            - ${function.allParameters.map { IrContextualTypeKey.from(it).typeKey }.joinToString()}
          """
            .trimIndent()
        }
      }

      return params.allParameters.mapIndexed { i, param ->
        val contextualTypeKey = paramsToMap[i].contextualTypeKey
        val accessType =
          if (param.contextualTypeKey.requiresProviderInstance) {
            AccessType.PROVIDER
          } else {
            AccessType.INSTANCE
          }

        // TODO consolidate this logic with generateBindingCode
        if (accessType == AccessType.INSTANCE) {
          // IFF the parameter can take a direct instance, try our instance fields
          bindingPropertyContext.get(contextualTypeKey)?.let { (property, storedKey) ->
            // Only return early if we got an actual instance property, not a provider fallback
            if (!storedKey.isWrappedInProvider) {
              return@mapIndexed irGetProperty(irGet(thisReceiver), property)
                .toTargetType(actual = AccessType.INSTANCE, contextualTypeKey = contextualTypeKey)
            }
          }
        }

        // When we need a provider (accessType == PROVIDER), look up by the provider-wrapped key
        // to get the provider property (e.g., longInstanceProvider) instead of the scalar property
        // (e.g., longInstance).
        val lookupKey =
          if (accessType == AccessType.PROVIDER) contextualTypeKey.wrapInProvider()
          else contextualTypeKey
        val providerInstance =
          bindingPropertyContext.get(lookupKey)?.let { (property, _) ->
            // If it's in provider fields, invoke that field
            irGetProperty(irGet(thisReceiver), property)
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

  /**
   * Resolves a [ParentContext.Token] to finalized [ParentContext.PropertyAccess] information by
   * looking up the property in the appropriate ancestor's [BindingPropertyContext].
   *
   * The token's [ParentContext.Token.ownerGraphKey] identifies which ancestor graph owns the
   * binding, allowing us to look up the correct context in nested extension chains.
   */
  private fun resolveToken(token: ParentContext.Token): ParentContext.PropertyAccess {
    // Look up the correct ancestor's context using the token's parentKey
    val ancestorContext =
      ancestorBindingContexts[token.ownerGraphKey]
        ?: reportCompilerBug(
          "Cannot resolve property access token - no binding context found for ancestor ${token.ownerGraphKey}"
        )

    val bindingProperty =
      ancestorContext.get(token.contextKey)
        ?: reportCompilerBug(
          "Cannot resolve property access token - property not found for ${token.contextKey} in ${token.ownerGraphKey}"
        )

    // Use the storedKey to determine if the property returns a Provider type,
    // not the token's contextKey. The parent may have upgraded the property to a
    // Provider field (e.g., because the binding is scoped or reused by factories) even if the child
    // originally only needed scalar access.
    return ParentContext.PropertyAccess(
      ownerGraphKey = token.ownerGraphKey,
      property = bindingProperty.property,
      receiverParameter = token.receiverParameter,
      isProviderProperty = bindingProperty.storedKey.isWrappedInProvider,
    )
  }
}
