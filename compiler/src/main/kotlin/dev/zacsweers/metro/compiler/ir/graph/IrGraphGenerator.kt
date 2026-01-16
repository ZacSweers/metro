// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.graph

import dev.zacsweers.metro.compiler.NameAllocator
import dev.zacsweers.metro.compiler.Origins
import dev.zacsweers.metro.compiler.asName
import dev.zacsweers.metro.compiler.decapitalizeUS
import dev.zacsweers.metro.compiler.expectAs
import dev.zacsweers.metro.compiler.ir.IrContextualTypeKey
import dev.zacsweers.metro.compiler.ir.IrMetroContext
import dev.zacsweers.metro.compiler.ir.IrTypeKey
import dev.zacsweers.metro.compiler.ir.allSupertypesSequence
import dev.zacsweers.metro.compiler.ir.buildBlockBody
import dev.zacsweers.metro.compiler.ir.createIrBuilder
import dev.zacsweers.metro.compiler.ir.createMetroMetadata
import dev.zacsweers.metro.compiler.ir.deepRemapperFor
import dev.zacsweers.metro.compiler.ir.doubleCheck
import dev.zacsweers.metro.compiler.ir.finalizeFakeOverride
import dev.zacsweers.metro.compiler.ir.graph.expressions.BindingExpressionGenerator
import dev.zacsweers.metro.compiler.ir.graph.expressions.GraphExpressionGenerator
import dev.zacsweers.metro.compiler.ir.graph.sharding.IrGraphShardGenerator
import dev.zacsweers.metro.compiler.ir.graph.sharding.PropertyBinding
import dev.zacsweers.metro.compiler.ir.instanceFactory
import dev.zacsweers.metro.compiler.ir.irExprBodySafe
import dev.zacsweers.metro.compiler.ir.irGetProperty
import dev.zacsweers.metro.compiler.ir.irInvoke
import dev.zacsweers.metro.compiler.ir.metroGraphOrFail
import dev.zacsweers.metro.compiler.ir.metroMetadata
import dev.zacsweers.metro.compiler.ir.parameters.remapTypes
import dev.zacsweers.metro.compiler.ir.parameters.wrapInProvider
import dev.zacsweers.metro.compiler.ir.rawType
import dev.zacsweers.metro.compiler.ir.regularParameters
import dev.zacsweers.metro.compiler.ir.requireSimpleType
import dev.zacsweers.metro.compiler.ir.setDispatchReceiver
import dev.zacsweers.metro.compiler.ir.sourceGraphIfMetroGraph
import dev.zacsweers.metro.compiler.ir.stripOuterProviderOrLazy
import dev.zacsweers.metro.compiler.ir.stubExpressionBody
import dev.zacsweers.metro.compiler.ir.thisReceiverOrFail
import dev.zacsweers.metro.compiler.ir.toProto
import dev.zacsweers.metro.compiler.ir.trackFunctionCall
import dev.zacsweers.metro.compiler.ir.transformers.AssistedFactoryTransformer
import dev.zacsweers.metro.compiler.ir.transformers.BindingContainerTransformer
import dev.zacsweers.metro.compiler.ir.transformers.MembersInjectorTransformer
import dev.zacsweers.metro.compiler.ir.typeAsProviderArgument
import dev.zacsweers.metro.compiler.ir.typeOrNullableAny
import dev.zacsweers.metro.compiler.ir.wrapInProvider
import dev.zacsweers.metro.compiler.ir.writeDiagnostic
import dev.zacsweers.metro.compiler.isSyntheticGeneratedGraph
import dev.zacsweers.metro.compiler.letIf
import dev.zacsweers.metro.compiler.newName
import dev.zacsweers.metro.compiler.reportCompilerBug
import dev.zacsweers.metro.compiler.suffixIfNot
import dev.zacsweers.metro.compiler.tracing.TraceScope
import dev.zacsweers.metro.compiler.tracing.traceNested
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.DescriptorVisibility
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.builders.declarations.addFunction
import org.jetbrains.kotlin.ir.builders.declarations.addProperty
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
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.typeOrFail
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.classIdOrFail
import org.jetbrains.kotlin.ir.util.constructors
import org.jetbrains.kotlin.ir.util.copyTo
import org.jetbrains.kotlin.ir.util.deepCopyWithSymbols
import org.jetbrains.kotlin.ir.util.functions
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.util.primaryConstructor
import org.jetbrains.kotlin.ir.util.properties
import org.jetbrains.kotlin.ir.util.propertyIfAccessor
import org.jetbrains.kotlin.ir.util.statements
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.platform.konan.isNative

internal typealias PropertyInitializer =
  IrBuilderWithScope.(thisReceiver: IrValueParameter, key: IrTypeKey) -> IrExpression

internal typealias InitStatement =
  IrBuilderWithScope.(thisReceiver: IrValueParameter) -> IrStatement

internal class IrGraphGenerator(
  metroContext: IrMetroContext,
  traceScope: TraceScope,
  private val dependencyGraphNodesByClass: (ClassId) -> DependencyGraphNode?,
  private val node: DependencyGraphNode,
  private val graphClass: IrClass,
  private val bindingGraph: IrBindingGraph,
  private val sealResult: IrBindingGraph.BindingGraphResult,
  // TODO move these accesses to irAttributes
  bindingContainerTransformer: BindingContainerTransformer,
  private val membersInjectorTransformer: MembersInjectorTransformer,
  assistedFactoryTransformer: AssistedFactoryTransformer,
  graphExtensionGenerator: IrGraphExtensionGenerator,
  /** All ancestor graphs' binding property contexts, keyed by graph type key. */
  private val ancestorBindingContexts: Map<IrTypeKey, BindingPropertyContext>,
) : IrMetroContext by metroContext, TraceScope by traceScope {

  private val propertyNameAllocator =
    NameAllocator(mode = NameAllocator.Mode.COUNT).apply {
      // Preallocate any existing property and field names in this graph
      for (property in node.metroGraphOrFail.properties) {
        newName(property.name.asString())
      }
    }

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

  private val bindingPropertyContext = BindingPropertyContext(bindingGraph)

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
    GraphExpressionGenerator.Factory(
      context = this,
      node = node,
      bindingPropertyContext = bindingPropertyContext,
      ancestorBindingContexts = ancestorBindingContexts,
      bindingGraph = bindingGraph,
      bindingContainerTransformer = bindingContainerTransformer,
      membersInjectorTransformer = membersInjectorTransformer,
      assistedFactoryTransformer = assistedFactoryTransformer,
      graphExtensionGenerator = graphExtensionGenerator,
      traceScope = traceScope,
    )

  private val graphMetadataReporter = GraphMetadataReporter(this)

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
  private fun IrClass.createBindingProperty(
    contextKey: IrContextualTypeKey,
    name: Name,
    type: IrType,
    propertyKind: PropertyKind,
    visibility: DescriptorVisibility = DescriptorVisibilities.PRIVATE,
  ): IrProperty {
    val property =
      addProperty {
          this.name = propertyNameAllocator.newName(name)
          this.visibility = visibility
        }
        .apply {
          graphPropertyData = GraphPropertyData(contextKey, type)
          contextKey.typeKey.qualifier?.ir?.let { annotations += it.deepCopyWithSymbols() }
        }

    return property.ensureInitialized(propertyKind, type)
  }

  fun generate(): BindingPropertyContext {
    with(graphClass) {
      val ctor = primaryConstructor!!

      val constructorStatements = mutableListOf<InitStatement>()

      val thisReceiverParameter = thisReceiverOrFail

      fun addBoundInstanceProperty(
        typeKey: IrTypeKey,
        name: Name,
        initializer:
          IrBuilderWithScope.(thisReceiver: IrValueParameter, typeKey: IrTypeKey) -> IrExpression,
      ) {
        // Don't add it if it's not used
        if (typeKey !in sealResult.reachableKeys) return

        val instanceContextKey = IrContextualTypeKey.create(typeKey)
        val instanceProperty =
          createBindingProperty(
              instanceContextKey,
              name.decapitalizeUS().suffixIfNot("Instance"),
              typeKey.type,
              PropertyKind.FIELD,
            )
            .initFinal { initializer(thisReceiverParameter, typeKey) }

        bindingPropertyContext.put(instanceContextKey, instanceProperty)

        val providerType = metroSymbols.metroProvider.typeWith(typeKey.type)
        val providerContextKey =
          IrContextualTypeKey.create(typeKey, isWrappedInProvider = true, rawType = providerType)
        val providerProperty =
          createBindingProperty(
              providerContextKey,
              instanceProperty.name.suffixIfNot("Provider"),
              providerType,
              PropertyKind.FIELD,
            )
            .initFinal {
              instanceFactory(
                typeKey.type,
                irGetProperty(irGet(thisReceiverParameter), instanceProperty),
              )
            }
        bindingPropertyContext.put(providerContextKey, providerProperty)
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
            addBoundInstanceProperty(param.typeKey, param.name) { _, _ -> irGet(irParam) }
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
            bindingPropertyContext.put(IrContextualTypeKey(param.typeKey), graphDepProperty)
            bindingPropertyContext.put(IrContextualTypeKey(graphDep.typeKey), graphDepProperty)

            // Expose the graph dep as a provider property only if it was reserved by a child graph
            val graphDepProviderType = metroSymbols.metroProvider.typeWith(param.typeKey.type)
            val graphDepProviderContextKey =
              IrContextualTypeKey.create(
                param.typeKey,
                isWrappedInProvider = true,
                rawType = graphDepProviderType,
              )
            // Only create the provider property if it was reserved (requested by a child graph)
            if (bindingGraph.isContextKeyReserved(graphDepProviderContextKey)) {
              val providerWrapperProperty =
                createBindingProperty(
                  graphDepProviderContextKey,
                  graphDepProperty.name.suffixIfNot("Provider"),
                  graphDepProviderType,
                  PropertyKind.FIELD,
                )

              // Link both the graph typekey and the (possibly-impl type)
              bindingPropertyContext.put(
                param.contextualTypeKey.stripOuterProviderOrLazy(),
                providerWrapperProperty.initFinal {
                  instanceFactory(
                    param.typeKey.type,
                    irGetProperty(irGet(thisReceiverParameter), graphDepProperty),
                  )
                },
              )
              bindingPropertyContext.put(
                IrContextualTypeKey(graphDep.typeKey),
                providerWrapperProperty,
              )
            }

            if (graphDep.hasExtensions) {
              val depMetroGraph = graphDep.sourceGraph.metroGraphOrFail
              val paramName = depMetroGraph.sourceGraphIfMetroGraph.name
              addBoundInstanceProperty(param.typeKey, paramName) { _, _ -> irGet(irParam) }
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
            addBoundInstanceProperty(IrTypeKey(clazz), clazz.name) { _, _ ->
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

        bindingPropertyContext.put(IrContextualTypeKey(node.typeKey), thisGraphProperty)

        // Expose the graph as a provider property if it's used or reserved
        val thisGraphProviderType = metroSymbols.metroProvider.typeWith(node.typeKey.type)
        val thisGraphProviderContextKey =
          IrContextualTypeKey.create(
            node.typeKey,
            isWrappedInProvider = true,
            rawType = thisGraphProviderType,
          )
        if (bindingGraph.isContextKeyReserved(thisGraphProviderContextKey)) {
          val property =
            createBindingProperty(
              thisGraphProviderContextKey,
              "thisGraphInstanceProvider".asName(),
              thisGraphProviderType,
              PropertyKind.FIELD,
            )

          bindingPropertyContext.put(
            thisGraphProviderContextKey,
            property.initFinal {
              instanceFactory(
                node.typeKey.type,
                irGetProperty(irGet(thisReceiverParameter), thisGraphProperty),
              )
            },
          )
        }
      }

      // Collect bindings and their dependencies for provider property ordering
      val initOrder =
        traceNested("Collect binding properties") {
          // Injector roots are specifically from inject() functions - they don't create
          // MembersInjector instances, so their dependencies are scalar accesses
          val injectorRoots = mutableSetOf<IrContextualTypeKey>()

          // Collect roots (accessors + injectors) for refcount tracking
          val roots = buildList {
            node.accessors.mapTo(this) { it.contextKey }
            for (injector in node.injectors) {
              add(injector.contextKey)
              injectorRoots.add(injector.contextKey)
            }
          }
          val collectedProperties =
            BindingPropertyCollector(
                metroContext,
                graph = bindingGraph,
                sortedKeys = sealResult.sortedKeys,
                roots = roots,
                injectorRoots = injectorRoots,
                extraKeeps = bindingGraph.keeps(),
                deferredTypes = sealResult.deferredTypes,
              )
              .collect()

          val collectedTypeKeys = collectedProperties.entries.groupBy { it.key.typeKey }

          // Build init order: iterate sorted keys and collect any properties for reachable bindings
          // For multibindings (especially maps), there may be multiple contextual variants
          buildList(collectedProperties.size) {
            sealResult.sortedKeys.forEach { key ->
              if (key in sealResult.reachableKeys) {
                collectedTypeKeys[key]?.forEach { (_, prop) -> add(prop) }
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
          val deferredProviderType = deferredTypeKey.type.wrapInProvider(metroSymbols.metroProvider)
          val deferredContextKey =
            IrContextualTypeKey.create(
              binding.typeKey,
              isWrappedInProvider = true,
              rawType = deferredProviderType,
            )
          val property =
            createBindingProperty(
                deferredContextKey,
                (binding.nameHint.decapitalizeUS() + "Provider").asName(),
                deferredProviderType,
                PropertyKind.FIELD,
              )
              .withInit(binding.typeKey) { _, _ ->
                irInvoke(
                  callee = metroSymbols.metroDelegateFactoryConstructor,
                  typeArgs = listOf(deferredTypeKey.type),
                )
              }

          bindingPropertyContext.put(deferredContextKey, property)
          property
        }

      initOrder
        .asSequence()
        .filterNot { (binding, _) ->
          // Don't generate deferred types here, we'll generate them last
          binding.typeKey in deferredProperties ||
            // Don't generate properties for anything already provided in provider/instance
            // properties (i.e. bound instance types)
            binding.contextualTypeKey in bindingPropertyContext ||
            // We don't generate properties for these even though we do track them in dependencies
            // above, it's just for propagating their aliased type in sorting
            binding is IrBinding.Alias ||
            // BoundInstance bindings use receivers (thisReceiver for self, token for parents)
            binding is IrBinding.BoundInstance ||
            // Parent graph bindings don't need duplicated properties
            (binding is IrBinding.GraphDependency && binding.token != null)
        }
        .toList()
        .also { propertyBindings ->
          writeDiagnostic("keys-providerProperties-${tracer.diagnosticTag}.txt") {
            propertyBindings.joinToString("\n") { it.binding.typeKey.toString() }
          }
          writeDiagnostic("keys-scopedProviderProperties-${tracer.diagnosticTag}.txt") {
            propertyBindings
              .filter { it.binding.isScoped() }
              .joinToString("\n") { it.binding.typeKey.toString() }
          }
        }
        .forEach { (binding, propertyType, collectedContextKey, collectedIsProviderType) ->
          val key = binding.typeKey
          // Since assisted-inject classes don't implement Factory, we can't just type these
          // as Provider<*> properties
          var isProviderType = collectedIsProviderType
          val finalContextKey = collectedContextKey.letIf(isProviderType) { it.wrapInProvider() }
          val suffix: String
          val irType =
            if (binding is IrBinding.ConstructorInjected && binding.isAssisted) {
              isProviderType = false
              suffix = "Factory"
              binding.classFactory.factoryClass.typeWith() // TODO generic factories?
            } else if (propertyType == PropertyKind.GETTER) {
              // Getters are scalars unless they need provider access (e.g., multibindings with
              // factory refs)
              suffix = if (isProviderType) "Provider" else ""
              finalContextKey.toIrType()
            } else {
              suffix = "Provider"
              metroSymbols.metroProvider.typeWith(key.type)
            }

          val accessType =
            if (isProviderType) {
              BindingExpressionGenerator.AccessType.PROVIDER
            } else {
              BindingExpressionGenerator.AccessType.INSTANCE
            }

          // If we've reserved a property for this key here, pull it out and use that
          // For multibindings, use the collected contextual key which may include variant info
          val property =
            createBindingProperty(
              finalContextKey,
              binding.nameHint.decapitalizeUS().suffixIfNot(suffix).asName(),
              irType,
              propertyType,
            )

          property.withInit(key) { thisReceiver, typeKey ->
            expressionGeneratorFactory
              .create(thisReceiver)
              .generateBindingCode(
                binding,
                contextualTypeKey = finalContextKey,
                accessType = accessType,
                fieldInitKey = typeKey,
              )
              .letIf(binding.isScoped() && isProviderType) {
                // If it's scoped, wrap it in double-check
                // DoubleCheck.provider(<provider>)
                it.doubleCheck(this@withInit, metroSymbols, binding.typeKey)
              }
          }

          bindingPropertyContext.put(finalContextKey, property)
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
                        contextualTypeKey = binding.contextualTypeKey.wrapInProvider(),
                        accessType = BindingExpressionGenerator.AccessType.PROVIDER,
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

      val propertyBindings =
        propertyInitializers.map { (property, initializer) ->
          PropertyBinding(
            property = property,
            typeKey = propertiesToTypeKeys.getValue(property),
            initializer = initializer,
          )
        }

      // Generate sharded inits if needed or fall back to chunked inits at graph level
      val shardGenerator = IrGraphShardGenerator(metroContext)
      val shardedInitStatements =
        shardGenerator.generateShards(
          graphClass = graphClass,
          propertyBindings = propertyBindings,
          plannedGroups = sealResult.shardGroups,
          bindingGraph = bindingGraph,
          diagnosticTag = tracer.diagnosticTag,
          deferredInit = ::addDeferredSetDelegateCalls,
        )

      if (shardedInitStatements != null) {
        constructorStatements += shardedInitStatements
      } else {
        val mustChunkInits =
          options.chunkFieldInits && propertyInitializers.size > options.statementsPerInitFun

        if (mustChunkInits) {
          // Larger graph, split statements
          // Chunk our constructor statements and split across multiple init functions
          val chunks =
            buildList<InitStatement> {
                // Add property initializers and interleave setDelegate calls as dependencies are
                // ready
                propertyInitializers.forEach { (property, init) ->
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
              addFunction(
                  initName,
                  irBuiltIns.unitType,
                  visibility = DescriptorVisibilities.PRIVATE,
                )
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
            initFunctionsToCall.forEach { initFunction ->
              add { dispatchReceiver ->
                irInvoke(dispatchReceiver = irGet(dispatchReceiver), callee = initFunction.symbol)
              }
            }
          }
        } else {
          // Small graph, just do it in the constructor
          // Assign those initializers directly to their properties and mark them as final
          propertyInitializers.forEach { (property, init) ->
            property.initFinal {
              val typeKey = propertiesToTypeKeys.getValue(property)
              init(thisReceiverParameter, typeKey)
            }
          }
          addDeferredSetDelegateCalls(constructorStatements)
        }
      }

      // Add extra constructor statements
      with(ctor) {
        val originalBody = checkNotNull(body)
        buildBlockBody {
          +originalBody.statements
          constructorStatements.forEach { statement -> +statement(thisReceiverParameter) }
        }
      }

      traceNested("Implement overrides") { node.implementOverrides() }

      if (!graphClass.origin.isSyntheticGeneratedGraph) {
        traceNested("Generate Metro metadata") {
          // Finally, generate metadata
          val graphProto = node.toProto(bindingGraph = bindingGraph)
          graphMetadataReporter.write(node, bindingGraph)
          val metroMetadata = createMetroMetadata(dependency_graph = graphProto)

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
    return bindingPropertyContext
  }

  // TODO add asProvider support?
  private fun IrClass.addSimpleInstanceProperty(
    name: String,
    typeKey: IrTypeKey,
    initializerExpression: IrBuilderWithScope.() -> IrExpression,
  ): IrProperty =
    addProperty {
        this.name = name.decapitalizeUS().asName()
        this.visibility = DescriptorVisibilities.PRIVATE
      }
      .apply { this.addBackingFieldCompat { this.type = typeKey.type } }
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
    injectors.forEach { (contextKey, overriddenFunction) ->
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

            val targetClass = pluginContext.referenceClass(binding.targetClassId)!!.owner

            // Create a single deep remapper from the target class - this handles the entire
            // type hierarchy correctly (e.g., ExampleClass<Int> -> Parent<Int, String> ->
            // GrandParent<String, Int>)
            val remapper =
              if (typeKey.hasTypeArgs) {
                targetClass.deepRemapperFor(wrappedType.type)
              } else {
                null
              }

            for (type in
              targetClass.allSupertypesSequence(excludeSelf = false, excludeAny = true)) {

              val clazz = type.rawType()
              val generatedInjector =
                membersInjectorTransformer.getOrGenerateInjector(clazz) ?: continue
              for ((function, unmappedParams) in generatedInjector.declaredInjectFunctions) {
                val parameters =
                  if (remapper != null) {
                    unmappedParams.remapTypes(remapper)
                  } else {
                    unmappedParams
                  }
                // Record for IC
                trackFunctionCall(this@apply, function)
                +irInvoke(
                  callee = function.symbol,
                  typeArgs =
                    targetParam.type.requireSimpleType(targetParam).arguments.map {
                      it.typeOrNullableAny
                    },
                  args =
                    buildList {
                      add(irGet(targetParam))
                      for (parameter in parameters.regularParameters) {
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

    // Binds stub bodies are implemented in BindsMirrorClassTransformer on the original
    // declarations, so we don't need to implement fake overrides here
    // TODO EXCEPT in native compilations, which appear to complain if you don't implement fake
    //  overrides even if they have a default impl
    //  https://youtrack.jetbrains.com/issue/KT-83666
    if (metroContext.platform.isNative() && bindsFunctions.isNotEmpty()) {
      for (function in bindsFunctions) {
        // Note we can't source this from the node.bindsCallables as those are pointed at their
        // original declarations and we need to implement their fake overrides here
        val irFunction = function.ir
        irFunction.apply {
          val declarationToFinalize = propertyIfAccessor.expectAs<IrOverridableDeclaration<*>>()
          if (declarationToFinalize.isFakeOverride) {
            declarationToFinalize.finalizeFakeOverride(graphClass.thisReceiverOrFail)
          }
          body = stubExpressionBody()
        }
      }
    }

    // Implement bodies for contributed graphs
    // Sort by keys when generating so they have deterministic ordering
    for ((typeKey, functions) in graphExtensions) {
      functions.forEach { extensionAccessor ->
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
                  parentGraphKey = node.typeKey,
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
