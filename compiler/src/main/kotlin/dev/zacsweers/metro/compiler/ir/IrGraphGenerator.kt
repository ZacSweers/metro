// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import dev.zacsweers.metro.compiler.METRO_VERSION
import dev.zacsweers.metro.compiler.NameAllocator
import dev.zacsweers.metro.compiler.Origins
import dev.zacsweers.metro.compiler.asName
import dev.zacsweers.metro.compiler.decapitalizeUS
import dev.zacsweers.metro.compiler.expectAs
import dev.zacsweers.metro.compiler.ir.parameters.parameters
import dev.zacsweers.metro.compiler.ir.parameters.wrapInProvider
import dev.zacsweers.metro.compiler.ir.transformers.AssistedFactoryTransformer
import dev.zacsweers.metro.compiler.ir.transformers.BindingContainerTransformer
import dev.zacsweers.metro.compiler.ir.transformers.MembersInjectorTransformer
import dev.zacsweers.metro.compiler.isGeneratedGraph
import dev.zacsweers.metro.compiler.letIf
import dev.zacsweers.metro.compiler.proto.MetroMetadata
import dev.zacsweers.metro.compiler.reportCompilerBug
import dev.zacsweers.metro.compiler.suffixIfNot
import dev.zacsweers.metro.compiler.tracing.Tracer
import dev.zacsweers.metro.compiler.tracing.traceNested
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.DescriptorVisibility
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.builders.declarations.addBackingField
import org.jetbrains.kotlin.ir.builders.declarations.addFunction
import org.jetbrains.kotlin.ir.builders.declarations.addGetter
import org.jetbrains.kotlin.ir.builders.declarations.addProperty
import org.jetbrains.kotlin.ir.builders.declarations.buildProperty
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.builders.irCallConstructor
import org.jetbrains.kotlin.ir.builders.irExprBody
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irGetObject
import org.jetbrains.kotlin.ir.builders.irSetField
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrOverridableDeclaration
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.expressions.IrBody
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.typeOrFail
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.addChild
import org.jetbrains.kotlin.ir.util.classIdOrFail
import org.jetbrains.kotlin.ir.util.constructors
import org.jetbrains.kotlin.ir.util.copyTo
import org.jetbrains.kotlin.ir.util.functions
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.util.primaryConstructor
import org.jetbrains.kotlin.ir.util.propertyIfAccessor
import org.jetbrains.kotlin.ir.util.statements
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.Name

internal typealias PropertyInitializer =
  IrBuilderWithScope.(thisReceiver: IrValueParameter, key: IrTypeKey) -> IrExpression

internal typealias InitStatement =
  IrBuilderWithScope.(thisReceiver: IrValueParameter) -> IrStatement

internal class IrGraphGenerator(
  metroContext: IrMetroContext,
  private val dependencyGraphNodesByClass: (ClassId) -> DependencyGraphNode?,
  private val node: DependencyGraphNode,
  private val graphClass: IrClass,
  private val bindingGraph: IrBindingGraph,
  private val sealResult: IrBindingGraph.BindingGraphResult,
  private val propertyNameAllocator: NameAllocator,
  private val parentTracer: Tracer,
  // TODO move these accesses to irAttributes
  bindingContainerTransformer: BindingContainerTransformer,
  private val membersInjectorTransformer: MembersInjectorTransformer,
  assistedFactoryTransformer: AssistedFactoryTransformer,
  graphExtensionGenerator: IrGraphExtensionGenerator,
) : IrMetroContext by metroContext {

  private var _functionNameAllocatorInitialized = false
  private val _functionNameAllocator = NameAllocator(mode = NameAllocator.Mode.COUNT)
  private val functionNameAllocator: NameAllocator
    get() {
      if (!_functionNameAllocatorInitialized) {
        // pre-allocate existing function names
        for (function in graphClass.functions) {
          _functionNameAllocator.newName(function.name.asString())
        }
        _functionNameAllocatorInitialized = true
      }
      return _functionNameAllocator
    }

  private val bindingPropertyContext = BindingPropertyContext()

  /**
   * Cache for lazily-created properties (e.g., multibinding getters). These are created on-demand
   * and added to the graph at the end to ensure deterministic ordering. Keyed by contextualTypeKey
   * to handle variants like Map<K,V> vs Map<K,Provider<V>>.
   */
  private val lazyProperties = mutableMapOf<IrContextualTypeKey, IrProperty>()

  /**
   * To avoid `MethodTooLargeException`, we split property field initializations up over multiple
   * constructor inits.
   *
   * @see <a href="https://github.com/ZacSweers/metro/issues/645">#645</a>
   */
  private val propertyInitializers = mutableListOf<Pair<IrProperty, PropertyInitializer>>()
  // TODO replace with irAttribute
  private val propertiesToTypeKeys = mutableMapOf<IrProperty, IrTypeKey>()
  private val expressionGeneratorFactory =
    IrGraphExpressionGenerator.Factory(
      context = this,
      node = node,
      bindingPropertyContext = bindingPropertyContext,
      bindingGraph = bindingGraph,
      bindingContainerTransformer = bindingContainerTransformer,
      membersInjectorTransformer = membersInjectorTransformer,
      assistedFactoryTransformer = assistedFactoryTransformer,
      graphExtensionGenerator = graphExtensionGenerator,
      parentTracer = parentTracer,
      getterPropertyFor = ::getOrCreateLazyProperty,
    )

  fun IrProperty.withInit(typeKey: IrTypeKey, init: PropertyInitializer): IrProperty = apply {
    // Only necessary for fields
    if (backingField != null) {
      propertiesToTypeKeys[this] = typeKey
      propertyInitializers += (this to init)
    } else {
      getter!!.apply {
        this.body =
          createIrBuilder(symbol).run { irExprBodySafe(init(dispatchReceiverParameter!!, typeKey)) }
      }
    }
  }

  fun IrProperty.initFinal(body: IrBuilderWithScope.() -> IrExpression): IrProperty = apply {
    backingField?.apply {
      isFinal = true
      initializer = createIrBuilder(symbol).run { irExprBody(body()) }
      return@apply
    }
    getter?.apply { this.body = createIrBuilder(symbol).run { irExprBodySafe(body()) } }
  }

  /**
   * Graph extensions may reserve property names for their linking, so if they've done that we use
   * the precomputed property rather than generate a new one.
   */
  private fun IrClass.getOrCreateBindingProperty(
    key: IrTypeKey,
    name: () -> String,
    type: () -> IrType,
    propertyType: PropertyType,
    visibility: DescriptorVisibility = DescriptorVisibilities.PRIVATE,
  ): IrProperty {
    val property =
      bindingGraph.reservedProperty(key)?.property?.also { addChild(it) }
        ?: addProperty {
            this.name = propertyNameAllocator.newName(name()).asName()
            this.visibility = visibility
          }
          .apply { graphPropertyData = GraphPropertyData(key, type()) }

    return property.ensureInitialized(propertyType, type)
  }

  /**
   * Creates or retrieves a lazily-generated property for the given binding and contextual type key.
   * These properties are cached and added to the graph at the end of generation for deterministic
   * ordering.
   *
   * This is primarily used for multibindings where different accessors may need different variants
   * (e.g., Map<K, V> vs Map<K, Provider<V>>).
   */
  fun getOrCreateLazyProperty(
    binding: IrBinding,
    contextualTypeKey: IrContextualTypeKey,
    bodyGenerator: IrBuilderWithScope.(IrGraphExpressionGenerator) -> IrBody,
  ): IrProperty {
    return lazyProperties.getOrPut(contextualTypeKey) {
      // Create the property but don't add it to the graph yet
      graphClass.factory
        .buildProperty {
          this.name = propertyNameAllocator.newName(binding.nameHint.decapitalizeUS()).asName()
          this.visibility = DescriptorVisibilities.PRIVATE
        }
        .apply {
          parent = graphClass
          graphPropertyData =
            GraphPropertyData(contextualTypeKey.typeKey, contextualTypeKey.toIrType())

          // Add getter with the provided body generator
          addGetter {
              returnType = contextualTypeKey.toIrType()
              visibility = DescriptorVisibilities.PRIVATE
            }
            .apply {
              val getterReceiver = graphClass.thisReceiver!!.copyTo(this)
              setDispatchReceiver(getterReceiver)
              val expressionGenerator = expressionGeneratorFactory.create(getterReceiver)
              this.body = createIrBuilder(symbol).bodyGenerator(expressionGenerator)
            }
        }
    }
  }

  fun generate() =
    with(graphClass) {
      val ctor = primaryConstructor!!

      val constructorStatements = mutableListOf<InitStatement>()

      val thisReceiverParameter = thisReceiverOrFail

      fun addBoundInstanceField(
        typeKey: IrTypeKey,
        name: Name,
        propertyType: PropertyType,
        initializer:
          IrBuilderWithScope.(thisReceiver: IrValueParameter, typeKey: IrTypeKey) -> IrExpression,
      ) {
        // Don't add it if it's not used
        if (typeKey !in sealResult.reachableKeys) return

        bindingPropertyContext.putProviderProperty(
          typeKey,
          getOrCreateBindingProperty(
              typeKey,
              {
                name
                  .asString()
                  .removePrefix("$$")
                  .decapitalizeUS()
                  .suffixIfNot("Instance")
                  .suffixIfNot("Provider")
              },
              { metroSymbols.metroProvider.typeWith(typeKey.type) },
              propertyType,
            )
            .initFinal {
              instanceFactory(typeKey.type, initializer(thisReceiverParameter, typeKey))
            },
        )
      }

      node.creator?.let { creator ->
        for ((i, param) in creator.parameters.regularParameters.withIndex()) {
          val isBindsInstance = param.isBindsInstance

          // TODO if we copy the annotations over in FIR we can skip this creator lookup all
          //  together
          val irParam = ctor.regularParameters[i]

          val isDynamic = irParam.origin == Origins.DynamicContainerParam
          val isBindingContainer = creator.bindingContainersParameterIndices.isSet(i)
          if (isBindsInstance || isBindingContainer || isDynamic) {

            if (!isDynamic && param.typeKey in node.dynamicTypeKeys) {
              // Don't add it if there's a dynamic replacement
              continue
            }
            addBoundInstanceField(param.typeKey, param.name, PropertyType.FIELD) { _, _ ->
              irGet(irParam)
            }
          } else {
            // It's a graph dep. Add all its accessors as available keys and point them at
            // this constructor parameter for provider property initialization
            val graphDep =
              node.includedGraphNodes[param.typeKey]
                ?: reportCompilerBug("Undefined graph node ${param.typeKey}")

            // Don't add it if it's not used
            if (param.typeKey !in sealResult.reachableKeys) continue

            val graphDepProperty =
              addSimpleInstanceProperty(
                propertyNameAllocator.newName(graphDep.sourceGraph.name.asString() + "Instance"),
                param.typeKey,
              ) {
                irGet(irParam)
              }
            // Link both the graph typekey and the (possibly-impl type)
            bindingPropertyContext.putInstanceProperty(param.typeKey, graphDepProperty)
            bindingPropertyContext.putInstanceProperty(graphDep.typeKey, graphDepProperty)

            // Expose the graph as a provider property
            // TODO this isn't always actually needed but different than the instance property above
            //  would be nice if we could determine if this property is unneeded
            val providerWrapperProperty =
              getOrCreateBindingProperty(
                param.typeKey,
                { graphDepProperty.name.asString() + "Provider" },
                { metroSymbols.metroProvider.typeWith(param.typeKey.type) },
                PropertyType.FIELD,
              )

            bindingPropertyContext.putProviderProperty(
              param.typeKey,
              providerWrapperProperty.initFinal {
                instanceFactory(
                  param.typeKey.type,
                  irGetProperty(irGet(thisReceiverParameter), graphDepProperty),
                )
              },
            )
            // Link both the graph typekey and the (possibly-impl type)
            bindingPropertyContext.putProviderProperty(param.typeKey, providerWrapperProperty)
            bindingPropertyContext.putProviderProperty(graphDep.typeKey, providerWrapperProperty)

            if (graphDep.hasExtensions) {
              val depMetroGraph = graphDep.sourceGraph.metroGraphOrFail
              val paramName = depMetroGraph.sourceGraphIfMetroGraph.name
              addBoundInstanceField(param.typeKey, paramName, PropertyType.FIELD) { _, _ ->
                irGet(irParam)
              }
            }
          }
        }
      }

      // Create managed binding containers instance properties if used
      val allBindingContainers = buildSet {
        addAll(node.bindingContainers)
        addAll(node.allExtendedNodes.values.flatMap { it.bindingContainers })
      }
      allBindingContainers
        .sortedBy { it.kotlinFqName.asString() }
        .forEach { clazz ->
          val typeKey = IrTypeKey(clazz)
          if (typeKey !in node.dynamicTypeKeys) {
            // Only add if not replaced with a dynamic instance
            addBoundInstanceField(IrTypeKey(clazz), clazz.name, PropertyType.FIELD) { _, _ ->
              // Can't use primaryConstructor here because it may be a Java dagger Module in interop
              val noArgConstructor = clazz.constructors.first { it.parameters.isEmpty() }
              irCallConstructor(noArgConstructor.symbol, emptyList())
            }
          }
        }

      // Don't add it if it's not used
      if (node.typeKey in sealResult.reachableKeys) {
        val thisGraphProperty =
          addSimpleInstanceProperty(
            propertyNameAllocator.newName("thisGraphInstance"),
            node.typeKey,
          ) {
            irGet(thisReceiverParameter)
          }

        bindingPropertyContext.putInstanceProperty(node.typeKey, thisGraphProperty)

        // Expose the graph as a provider property
        // TODO this isn't always actually needed but different than the instance field above
        //  would be nice if we could determine if this field is unneeded
        val property =
          getOrCreateBindingProperty(
            node.typeKey,
            { "thisGraphInstanceProvider" },
            { metroSymbols.metroProvider.typeWith(node.typeKey.type) },
            PropertyType.FIELD,
          )

        bindingPropertyContext.putProviderProperty(
          node.typeKey,
          property.initFinal {
            instanceFactory(
              node.typeKey.type,
              irGetProperty(irGet(thisReceiverParameter), thisGraphProperty),
            )
          },
        )
      }

      // Collect bindings and their dependencies for provider property ordering
      val initOrder =
        parentTracer.traceNested("Collect bindings") {
          val collectedProperties = BindingPropertyCollector(bindingGraph).collect()
          buildList(collectedProperties.size) {
            for (key in sealResult.sortedKeys) {
              if (key in sealResult.reachableKeys) {
                collectedProperties[key]?.let(::add)
              }
            }
          }
        }

      // For all deferred types, assign them first as factories
      // DelegateFactory properties can be initialized inline since they're just empty factories.
      @Suppress("UNCHECKED_CAST")
      val deferredProperties: Map<IrTypeKey, IrProperty> =
        sealResult.deferredTypes.associateWith { deferredTypeKey ->
          val binding = bindingGraph.requireBinding(deferredTypeKey)
          val property =
            getOrCreateBindingProperty(
                binding.typeKey,
                { binding.nameHint.decapitalizeUS() + "Provider" },
                { deferredTypeKey.type.wrapInProvider(metroSymbols.metroProvider) },
                PropertyType.FIELD,
              )
              .withInit(binding.typeKey) { _, _ ->
                irInvoke(
                  callee = metroSymbols.metroDelegateFactoryConstructor,
                  typeArgs = listOf(deferredTypeKey.type),
                )
              }

          bindingPropertyContext.putProviderProperty(deferredTypeKey, property)
          property
        }

      initOrder
        .asSequence()
        .filterNot { (binding, _) ->
          // Don't generate deferred types here, we'll generate them last
          binding.typeKey in deferredProperties ||
            // Don't generate properties for anything already provided in provider/instance
            // properties (i.e.
            // bound instance types)
            binding.typeKey in bindingPropertyContext ||
            // We don't generate properties for these even though we do track them in dependencies
            // above, it's just for propagating their aliased type in sorting
            binding is IrBinding.Alias ||
            // For implicit outer class receivers we don't need to generate a property for them
            (binding is IrBinding.BoundInstance && binding.classReceiverParameter != null) ||
            // Parent graph bindings don't need duplicated properties
            (binding is IrBinding.GraphDependency && binding.propertyAccess != null)
        }
        .toList()
        .also { propertyBindings ->
          writeDiagnostic("keys-providerProperties-${parentTracer.tag}.txt") {
            propertyBindings.joinToString("\n") { it.binding.typeKey.toString() }
          }
          writeDiagnostic("keys-scopedProviderProperties-${parentTracer.tag}.txt") {
            propertyBindings
              .filter { it.binding.isScoped() }
              .joinToString("\n") { it.binding.typeKey.toString() }
          }
        }
        .forEach { (binding, propertyType) ->
          val key = binding.typeKey
          // Since assisted-inject classes don't implement Factory, we can't just type these
          // as Provider<*> properties
          var isProviderType = true
          val suffix: String
          val irType =
            if (binding is IrBinding.ConstructorInjected && binding.isAssisted) {
              isProviderType = false
              suffix = "Factory"
              binding.classFactory.factoryClass.typeWith() // TODO generic factories?
            } else if (propertyType == PropertyType.GETTER && binding is IrBinding.Multibinding) {
              // Getters don't need to be providers for multibindings
              isProviderType = false
              suffix = ""
              binding.typeKey.type
            } else {
              suffix = "Provider"
              metroSymbols.metroProvider.typeWith(key.type)
            }

          val accessType =
            if (isProviderType) {
              IrGraphExpressionGenerator.AccessType.PROVIDER
            } else {
              IrGraphExpressionGenerator.AccessType.INSTANCE
            }

          // If we've reserved a property for this key here, pull it out and use that
          val property =
            getOrCreateBindingProperty(
              binding.typeKey,
              { binding.nameHint.decapitalizeUS().suffixIfNot(suffix) },
              { irType },
              propertyType,
            )

          property.withInit(key) { thisReceiver, typeKey ->
            expressionGeneratorFactory
              .create(thisReceiver)
              .generateBindingCode(binding, accessType = accessType, fieldInitKey = typeKey)
              .letIf(binding.isScoped() && isProviderType) {
                // If it's scoped, wrap it in double-check
                // DoubleCheck.provider(<provider>)
                it.doubleCheck(this@withInit, metroSymbols, binding.typeKey)
              }
          }

          if (isProviderType) {
            bindingPropertyContext.putProviderProperty(key, property)
          } else {
            bindingPropertyContext.putInstanceProperty(key, property)
          }
        }

      fun addDeferredSetDelegateCalls(collector: MutableList<InitStatement>) {
        // Add statements to our constructor's deferred properties _after_ we've added all provider
        // properties for everything else. This is important in case they reference each other
        for ((deferredTypeKey, field) in deferredProperties) {
          val binding = bindingGraph.requireBinding(deferredTypeKey)
          collector.add { thisReceiver ->
            irInvoke(
              dispatchReceiver = irGetObject(metroSymbols.metroDelegateFactoryCompanion),
              callee = metroSymbols.metroDelegateFactorySetDelegate,
              typeArgs = listOf(deferredTypeKey.type),
              // TODO de-dupe?
              args =
                listOf(
                  irGetProperty(irGet(thisReceiver), field),
                  createIrBuilder(symbol).run {
                    expressionGeneratorFactory
                      .create(thisReceiver)
                      .generateBindingCode(
                        binding,
                        accessType = IrGraphExpressionGenerator.AccessType.PROVIDER,
                        fieldInitKey = deferredTypeKey,
                      )
                      .letIf(binding.isScoped()) {
                        // If it's scoped, wrap it in double-check
                        // DoubleCheck.provider(<provider>)
                        it.doubleCheck(this@run, metroSymbols, binding.typeKey)
                      }
                  },
                ),
            )
          }
        }
      }

      // Use chunked inits if the graph is large enough
      val mustChunkInits =
        options.chunkFieldInits && propertyInitializers.size > options.statementsPerInitFun

      if (mustChunkInits) {
        // Larger graph, split statements
        // Chunk our constructor statements and split across multiple init functions
        val chunks =
          buildList<InitStatement> {
              // Add property initializers and interleave setDelegate calls as dependencies are
              // ready
              for ((property, init) in propertyInitializers) {
                val typeKey = propertiesToTypeKeys.getValue(property)

                // Add this property's initialization
                add { thisReceiver ->
                  irSetField(
                    irGet(thisReceiver),
                    property.backingField!!,
                    init(thisReceiver, typeKey),
                  )
                }
              }

              addDeferredSetDelegateCalls(this)
            }
            .chunked(options.statementsPerInitFun)

        val initFunctionsToCall =
          chunks.map { statementsChunk ->
            val initName = functionNameAllocator.newName("init")
            addFunction(initName, irBuiltIns.unitType, visibility = DescriptorVisibilities.PRIVATE)
              .apply {
                val localReceiver = thisReceiverParameter.copyTo(this)
                setDispatchReceiver(localReceiver)
                buildBlockBody {
                  for (statement in statementsChunk) {
                    +statement(localReceiver)
                  }
                }
              }
          }
        constructorStatements += buildList {
          for (initFunction in initFunctionsToCall) {
            add { dispatchReceiver ->
              irInvoke(dispatchReceiver = irGet(dispatchReceiver), callee = initFunction.symbol)
            }
          }
        }
      } else {
        // Small graph, just do it in the constructor
        // Assign those initializers directly to their properties and mark them as final
        for ((property, init) in propertyInitializers) {
          property.initFinal {
            val typeKey = propertiesToTypeKeys.getValue(property)
            init(thisReceiverParameter, typeKey)
          }
        }
        addDeferredSetDelegateCalls(constructorStatements)
      }

      // Add extra constructor statements
      with(ctor) {
        val originalBody = checkNotNull(body)
        buildBlockBody {
          +originalBody.statements
          for (statement in constructorStatements) {
            +statement(thisReceiverParameter)
          }
        }
      }

      parentTracer.traceNested("Implement overrides") { node.implementOverrides() }

      // Add lazy properties to graph in deterministic order
      if (lazyProperties.isNotEmpty()) {
        lazyProperties.values
          .sortedBy { it.name.asString() }
          .forEach { property -> addChild(property) }
      }

      if (!graphClass.origin.isGeneratedGraph) {
        parentTracer.traceNested("Generate Metro metadata") {
          // Finally, generate metadata
          val graphProto = node.toProto(bindingGraph = bindingGraph)
          val metroMetadata = MetroMetadata(METRO_VERSION, dependency_graph = graphProto)

          writeDiagnostic({
            "graph-metadata-${node.sourceGraph.kotlinFqName.asString().replace(".", "-")}.kt"
          }) {
            metroMetadata.toString()
          }

          // Write the metadata to the metroGraph class, as that's what downstream readers are
          // looking at and is the most complete view
          graphClass.metroMetadata = metroMetadata
          dependencyGraphNodesByClass(node.sourceGraph.classIdOrFail)?.let { it.proto = graphProto }
        }
      }
    }

  // TODO add asProvider support?
  private fun IrClass.addSimpleInstanceProperty(
    name: String,
    typeKey: IrTypeKey,
    initializerExpression: IrBuilderWithScope.() -> IrExpression,
  ): IrProperty =
    addProperty {
        this.name = name.removePrefix("$$").decapitalizeUS().asName()
        this.visibility = DescriptorVisibilities.PRIVATE
      }
      .apply { this.addBackingField { this.type = typeKey.type } }
      .initFinal { initializerExpression() }

  private fun DependencyGraphNode.implementOverrides() {
    // Implement abstract getters for accessors
    for ((contextualTypeKey, function, isOptionalDep) in accessors) {
      val binding = bindingGraph.findBinding(contextualTypeKey.typeKey)

      if (isOptionalDep && binding == null) {
        continue // Just use its default impl
      } else if (binding == null) {
        // Should never happen
        reportCompilerBug("No binding found for $contextualTypeKey")
      }

      val irFunction = function.ir
      irFunction.apply {
        val declarationToFinalize =
          irFunction.propertyIfAccessor.expectAs<IrOverridableDeclaration<*>>()
        if (declarationToFinalize.isFakeOverride) {
          declarationToFinalize.finalizeFakeOverride(graphClass.thisReceiverOrFail)
        }
        body =
          createIrBuilder(symbol).run {
            if (binding is IrBinding.Multibinding) {
              // TODO if we have multiple accessors pointing at the same type, implement
              //  one and make the rest call that one. Not multibinding specific. Maybe
              //  groupBy { typekey }?
            }
            irExprBodySafe(
              typeAsProviderArgument(
                contextualTypeKey,
                expressionGeneratorFactory
                  .create(irFunction.dispatchReceiverParameter!!)
                  .generateBindingCode(binding, contextualTypeKey = contextualTypeKey),
                isAssisted = false,
                isGraphInstance = false,
              )
            )
          }
      }
    }

    // Implement abstract injectors
    for ((contextKey, overriddenFunction) in injectors) {
      val typeKey = contextKey.typeKey
      overriddenFunction.ir.apply {
        finalizeFakeOverride(graphClass.thisReceiverOrFail)
        val targetParam = regularParameters[0]
        val binding = bindingGraph.requireBinding(contextKey) as IrBinding.MembersInjected

        // We don't get a MembersInjector instance/provider from the graph. Instead, we call
        // all the target inject functions directly
        body =
          createIrBuilder(symbol).irBlockBody {
            // TODO reuse, consolidate calling code with how we implement this in
            //  constructor inject code gen
            // val injectors =
            // membersInjectorTransformer.getOrGenerateAllInjectorsFor(declaration)
            // val memberInjectParameters = injectors.flatMap { it.parameters.values.flatten()
            // }

            // Extract the type from MembersInjector<T>
            val wrappedType =
              typeKey.copy(typeKey.type.requireSimpleType(targetParam).arguments[0].typeOrFail)

            for (type in
              pluginContext
                .referenceClass(binding.targetClassId)!!
                .owner
                .getAllSuperTypes(excludeSelf = false, excludeAny = true)) {
              val clazz = type.rawType()
              val generatedInjector =
                membersInjectorTransformer.getOrGenerateInjector(clazz) ?: continue
              for ((function, unmappedParams) in generatedInjector.declaredInjectFunctions) {
                val parameters =
                  if (typeKey.hasTypeArgs) {
                    val remapper = function.typeRemapperFor(wrappedType.type)
                    function.parameters(remapper)
                  } else {
                    unmappedParams
                  }
                // Record for IC
                trackFunctionCall(this@apply, function)
                +irInvoke(
                  callee = function.symbol,
                  args =
                    buildList {
                      add(irGet(targetParam))
                      // Always drop the first parameter when calling inject, as the first is the
                      // instance param
                      for (parameter in parameters.regularParameters.drop(1)) {
                        val paramBinding = bindingGraph.requireBinding(parameter.contextualTypeKey)
                        add(
                          typeAsProviderArgument(
                            parameter.contextualTypeKey,
                            expressionGeneratorFactory
                              .create(overriddenFunction.ir.dispatchReceiverParameter!!)
                              .generateBindingCode(
                                paramBinding,
                                contextualTypeKey = parameter.contextualTypeKey,
                              ),
                            isAssisted = false,
                            isGraphInstance = false,
                          )
                        )
                      }
                    },
                )
              }
            }
          }
      }
    }

    // Implement no-op bodies for Binds providers
    // Note we can't source this from the node.bindsCallables as those are pointed at their original
    // declarations and we need to implement their fake overrides here
    bindsFunctions.forEach { function ->
      val irFunction = function.ir
      irFunction.apply {
        val declarationToFinalize = propertyIfAccessor.expectAs<IrOverridableDeclaration<*>>()
        if (declarationToFinalize.isFakeOverride) {
          declarationToFinalize.finalizeFakeOverride(graphClass.thisReceiverOrFail)
        }
        body = stubExpressionBody()
      }
    }

    // Implement bodies for contributed graphs
    // Sort by keys when generating so they have deterministic ordering
    // TODO make the value types something more strongly typed
    for ((typeKey, functions) in graphExtensions) {
      for (extensionAccessor in functions) {
        val function = extensionAccessor.accessor
        val irFunction = function.ir
        irFunction.apply {
          val declarationToFinalize =
            irFunction.propertyIfAccessor.expectAs<IrOverridableDeclaration<*>>()
          if (declarationToFinalize.isFakeOverride) {
            declarationToFinalize.finalizeFakeOverride(graphClass.thisReceiverOrFail)
          }

          if (extensionAccessor.isFactory) {
            // Handled in regular accessors
          } else {
            // Graph extension creator. Use regular binding code gen
            // Could be a factory SAM function or a direct accessor. SAMs won't have a binding, but
            // we can synthesize one here as needed
            val binding =
              bindingGraph.findBinding(typeKey)
                ?: IrBinding.GraphExtension(
                  typeKey = typeKey,
                  parent = metroGraphOrFail,
                  accessor = irFunction,
                  // Implementing a factory SAM, no scoping or dependencies here,
                  extensionScopes = emptySet(),
                  dependencies = emptyList(),
                )
            val contextKey = IrContextualTypeKey.from(irFunction)
            body =
              createIrBuilder(symbol).run {
                irExprBodySafe(
                  expressionGeneratorFactory
                    .create(irFunction.dispatchReceiverParameter!!)
                    .generateBindingCode(binding = binding, contextualTypeKey = contextKey)
                )
              }
          }
        }
      }
    }
  }
}
