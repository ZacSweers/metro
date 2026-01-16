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
import dev.zacsweers.metro.compiler.ir.doubleCheck
import dev.zacsweers.metro.compiler.ir.finalizeFakeOverride
import dev.zacsweers.metro.compiler.ir.graph.expressions.BindingExpressionGenerator
import dev.zacsweers.metro.compiler.ir.graph.expressions.GraphExpressionGenerator
import dev.zacsweers.metro.compiler.ir.graph.sharding.IrGraphShardGenerator
import dev.zacsweers.metro.compiler.ir.graph.sharding.ShardBinding
import dev.zacsweers.metro.compiler.ir.graph.sharding.ShardExpressionContext
import dev.zacsweers.metro.compiler.ir.instanceFactory
import dev.zacsweers.metro.compiler.ir.irExprBodySafe
import dev.zacsweers.metro.compiler.ir.irGetProperty
import dev.zacsweers.metro.compiler.ir.irInvoke
import dev.zacsweers.metro.compiler.ir.metroGraphOrFail
import dev.zacsweers.metro.compiler.ir.metroMetadata
import dev.zacsweers.metro.compiler.ir.parameters.remapTypes
import dev.zacsweers.metro.compiler.ir.rawType
import dev.zacsweers.metro.compiler.ir.rawTypeOrNull
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
import dev.zacsweers.metro.compiler.ir.typeRemapperFor
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
import org.jetbrains.kotlin.ir.builders.irDelegatingConstructorCall
import org.jetbrains.kotlin.ir.builders.irExprBody
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irGetObject
import org.jetbrains.kotlin.ir.builders.irSetField
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrOverridableDeclaration
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.irAttribute
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
  private val bindingContainerTransformer: BindingContainerTransformer,
  private val membersInjectorTransformer: MembersInjectorTransformer,
  private val assistedFactoryTransformer: AssistedFactoryTransformer,
  private val graphExtensionGenerator: IrGraphExtensionGenerator,
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

      // For extension graphs (static nested classes with explicit parent graph parameter),
      // create a property to store the parent graph instance.
      // This is needed so that shards can access parent graph bindings via
      // `this.graph.parentGraphImpl.shard.property`.
      // Extension graphs have a ParentGraphParam-origin parameter as their first constructor
      // parameter.
      val parentGraphParam =
        ctor.regularParameters.getOrNull(0)?.takeIf { it.origin == Origins.ParentGraphParam }
      val parentGraphInstanceProperty: IrProperty? =
        if (parentGraphParam != null) {
          val parentGraphType = parentGraphParam.type
          addProperty {
              name = propertyNameAllocator.newName(parentGraphParam.name)
              visibility = DescriptorVisibilities.PRIVATE
            }
            .apply {
              addBackingFieldCompat {
                  type = parentGraphType
                  visibility = DescriptorVisibilities.PRIVATE
                }
                .apply {
                  initializer = createIrBuilder(symbol).run { irExprBody(irGet(parentGraphParam)) }
                }
            }
            .also {
              // Store on the graph class so child extensions can access it
              graphClass.parentGraphInstanceProperty = it
            }
        } else {
          null
        }

      // Build the ancestor graph properties map for shard expression context.
      // Maps ancestor graph type key -> list of properties to chain through to access it.
      // The key must match DependencyGraphNode.typeKey construction:
      // - For synthetic graphs (extensions, dynamic): uses the impl type key
      // - For non-synthetic graphs: uses the interface type key (via sourceGraphIfMetroGraph)
      val ancestorGraphProperties: Map<IrTypeKey, List<IrProperty>> =
        if (parentGraphInstanceProperty != null) {
          val parentImplType = parentGraphInstanceProperty.backingField!!.type
          val parentImplClass = parentImplType.rawTypeOrNull()

          buildMap {
              if (parentImplClass != null) {
                // Use the same key construction as DependencyGraphNode.typeKey:
                // - Synthetic graphs use the impl
                // - Non-synthetic graphs use sourceGraphIfMetroGraph (the interface)
                val keyClass =
                  if (parentImplClass.origin.isSyntheticGeneratedGraph) {
                    parentImplClass
                  } else {
                    parentImplClass.sourceGraphIfMetroGraph
                  }
                put(IrTypeKey(keyClass.typeWith()), listOf(parentGraphInstanceProperty))
              }

              // For chained extensions, copy parent's ancestor chains with our property prepended.
              // This avoids walking the chain - parent already computed its ancestors.
              parentImplClass?.ancestorGraphPropertiesMap?.let { parentAncestors ->
                for ((ancestorKey, ancestorChain) in parentAncestors) {
                  put(ancestorKey, listOf(parentGraphInstanceProperty) + ancestorChain)
                }
              }
            }
            .also {
              // Store on graph class so child extensions can access it
              graphClass.ancestorGraphPropertiesMap = it
            }
        } else {
          emptyMap()
        }

      val expressionGeneratorFactory =
        GraphExpressionGenerator.Factory(
          context = this@IrGraphGenerator,
          traceScope = this@IrGraphGenerator,
          node = node,
          bindingPropertyContext = bindingPropertyContext,
          ancestorBindingContexts = ancestorBindingContexts,
          ancestorGraphProperties = ancestorGraphProperties,
          bindingGraph = bindingGraph,
          bindingContainerTransformer = bindingContainerTransformer,
          membersInjectorTransformer = membersInjectorTransformer,
          assistedFactoryTransformer = assistedFactoryTransformer,
          graphExtensionGenerator = graphExtensionGenerator,
        )

      // Register the parent graph instance property in the binding context (if present)
      if (parentGraphInstanceProperty != null && parentGraphParam != null) {
        // Register under both the impl type and the interface type, since bindings may
        // reference either (factory methods typically take the interface type).
        val parentImplTypeKey = IrTypeKey(parentGraphParam.type)
        bindingPropertyContext.put(
          IrContextualTypeKey(parentImplTypeKey),
          parentGraphInstanceProperty,
        )

        // Also register under the source graph (interface) type if different
        val parentImplClass = parentGraphParam.type.rawTypeOrNull()
        val parentInterfaceClass = parentImplClass?.sourceGraphIfMetroGraph
        if (parentInterfaceClass != null && parentInterfaceClass != parentImplClass) {
          val parentInterfaceTypeKey = IrTypeKey(parentInterfaceClass)
          bindingPropertyContext.put(
            IrContextualTypeKey(parentInterfaceTypeKey),
            parentGraphInstanceProperty,
          )
        }
      }

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
          // Find matching ctor param by name. Skip parent graph params - they're handled above.
          if (i == 0 && param.ir?.origin == Origins.ParentGraphParam) continue

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

      // Collect bindings that need properties (filtering out bound instances, aliases, etc.)
      // Deferred types (for breaking cycles) are included here and handled specially in shards.
      val collectedBindings =
        initOrder
          .asSequence()
          .filterNot { (binding, _) ->
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

      fun computeBindingMetadata(
        binding: IrBinding,
        propertyType: PropertyKind,
        collectedContextKey: IrContextualTypeKey,
        collectedIsProviderType: Boolean,
      ): BindingMetadata {
        val key = binding.typeKey
        var isProviderType = collectedIsProviderType
        val finalContextKey = collectedContextKey.letIf(isProviderType) { it.wrapInProvider() }
        val suffix: String
        val irType =
          if (binding is IrBinding.ConstructorInjected && binding.isAssisted) {
            isProviderType = false
            suffix = "Factory"
            binding.classFactory.factoryClass.typeWith()
          } else if (propertyType == PropertyKind.GETTER) {
            suffix = if (isProviderType) "Provider" else ""
            finalContextKey.toIrType()
          } else {
            suffix = "Provider"
            metroSymbols.metroProvider.typeWith(key.type)
          }

        return BindingMetadata(
          binding = binding,
          propertyKind = propertyType,
          contextKey = finalContextKey,
          irType = irType,
          nameHint = binding.nameHint.decapitalizeUS().suffixIfNot(suffix).asName(),
          isProviderType = isProviderType,
          isScoped = binding.isScoped(),
        )
      }

      // Convert collected bindings to BindingInfo for shard generator
      val shardBindings =
        collectedBindings.map { collectedProperty ->
          val (binding, propertyType, collectedContextKey, collectedIsProviderType) =
            collectedProperty
          val isDeferred = binding.typeKey in sealResult.deferredTypes
          val metadata =
            computeBindingMetadata(
              binding,
              propertyType,
              collectedContextKey,
              collectedIsProviderType,
            )
          ShardBinding(
            typeKey = binding.typeKey,
            contextKey = metadata.contextKey,
            propertyKind = metadata.propertyKind,
            irType = metadata.irType,
            nameHint = metadata.nameHint,
            isScoped = metadata.isScoped,
            isDeferred = isDeferred,
          )
        }

      // Generate shards (or graph-as-shard) with properties
      val shardResult =
        IrGraphShardGenerator(
            context = metroContext,
            graphClass = graphClass,
            shardBindings = shardBindings,
            plannedGroups = sealResult.shardGroups,
            bindingGraph = bindingGraph,
            propertyNameAllocator = propertyNameAllocator,
          )
          .generateShards(diagnosticTag = tracer.diagnosticTag)

      if (shardResult != null) {
        // Create shard field properties on the main class (only for nested shards)
        // Use internal visibility so shard classes can access each other's fields
        val shardFields =
          if (!shardResult.isGraphAsShard) {
            shardResult.shards.associate { shard ->
              val shardField =
                addProperty {
                    name = propertyNameAllocator.newName("shard${shard.index + 1}").asName()
                    visibility = DescriptorVisibilities.INTERNAL
                  }
                  .apply {
                    addBackingFieldCompat {
                      type = shard.shardClass.typeWith()
                      visibility = DescriptorVisibilities.INTERNAL
                    }
                  }
              shard.index to shardField
            }
          } else {
            emptyMap()
          }

        // Register shard properties in bindingPropertyContext
        shardResult.registerProperties(bindingPropertyContext, shardFields)

        // Process each shard using unified logic (works for both graph-as-shard and nested shards)
        for (shard in shardResult.shards) {
          val targetClass = shard.shardClass
          val targetThisReceiver = targetClass.thisReceiverOrFail

          // Create shard expression context for property access (only for nested shards)
          val shardExprContext =
            if (!shard.isGraphAsShard) {
              ShardExpressionContext(
                graphProperty = shard.graphProperty, // May be null if shard has no cross-shard deps
                shardThisReceiver = targetThisReceiver,
                currentShardIndex = shard.index,
                shardFields = shardFields,
                ancestorGraphProperties = ancestorGraphProperties,
              )
            } else {
              null
            }

          // Collect property initializers for this shard
          val shardPropertyInitializers = mutableListOf<Pair<IrProperty, PropertyInitializer>>()
          val shardPropertiesToTypeKeys = mutableMapOf<IrProperty, IrTypeKey>()
          // Track deferred properties in this shard for setDelegate calls
          val shardDeferredProperties = mutableListOf<Pair<IrTypeKey, IrProperty>>()

          for ((contextKey, propertyInfo) in shard.properties) {
            val binding = bindingGraph.requireBinding(contextKey.typeKey)
            val shardBinding = propertyInfo.shardBinding
            val isProviderType = contextKey.isWrappedInProvider
            val isScoped = shardBinding.isScoped
            val isDeferred = shardBinding.isDeferred

            val accessType =
              if (isProviderType) {
                BindingExpressionGenerator.AccessType.PROVIDER
              } else {
                BindingExpressionGenerator.AccessType.INSTANCE
              }

            val property = propertyInfo.property

            // Handle getter properties directly (no chunking needed)
            // Note: Deferred properties should always be FIELD, not GETTER
            if (property.backingField == null) {
              property.getter!!.apply {
                body =
                  createIrBuilder(symbol).run {
                    val initExpr =
                      expressionGeneratorFactory
                        .create(dispatchReceiverParameter!!, shardContext = shardExprContext)
                        .generateBindingCode(
                          binding = binding,
                          contextualTypeKey = contextKey,
                          accessType = accessType,
                          fieldInitKey = contextKey.typeKey,
                        )
                        .letIf(isScoped && isProviderType) {
                          it.doubleCheck(this@run, metroSymbols, binding.typeKey)
                        }
                    irExprBodySafe(initExpr)
                  }
              }
              continue
            }

            // For field properties, add to initializers list for potential chunking
            shardPropertiesToTypeKeys[property] = contextKey.typeKey

            if (isDeferred) {
              // Deferred properties are initialized with empty DelegateFactory(),
              // then setDelegate is called after all properties in this shard are initialized
              shardDeferredProperties += contextKey.typeKey to property
              val deferredType = contextKey.typeKey.type
              val init: PropertyInitializer = { _, _ ->
                irInvoke(
                  callee = metroSymbols.metroDelegateFactoryConstructor,
                  typeArgs = listOf(deferredType),
                )
              }
              shardPropertyInitializers += property to init
            } else {
              shardPropertyInitializers +=
                property to
                  { thisReceiver: IrValueParameter, fieldInitKey: IrTypeKey ->
                    expressionGeneratorFactory
                      .create(thisReceiver, shardContext = shardExprContext)
                      .generateBindingCode(
                        binding,
                        contextualTypeKey = contextKey,
                        accessType = accessType,
                        fieldInitKey = fieldInitKey,
                      )
                      .letIf(isScoped && isProviderType) {
                        it.doubleCheck(this, metroSymbols, binding.typeKey)
                      }
                  }
            }
          }

          // Helper to generate setDelegate calls for deferred properties in this shard
          fun IrBuilderWithScope.generateDeferredSetDelegateCalls(
            thisReceiver: IrValueParameter
          ): List<IrStatement> = buildList {
            for ((deferredTypeKey, deferredProperty) in shardDeferredProperties) {
              val binding = bindingGraph.requireBinding(deferredTypeKey)
              add(
                irInvoke(
                  dispatchReceiver = irGetObject(metroSymbols.metroDelegateFactoryCompanion),
                  callee = metroSymbols.metroDelegateFactorySetDelegate,
                  typeArgs = listOf(deferredTypeKey.type),
                  args =
                    listOf(
                      irGetProperty(irGet(thisReceiver), deferredProperty),
                      expressionGeneratorFactory
                        .create(thisReceiver, shardContext = shardExprContext)
                        .generateBindingCode(
                          binding,
                          contextualTypeKey = binding.contextualTypeKey.wrapInProvider(),
                          accessType = BindingExpressionGenerator.AccessType.PROVIDER,
                          fieldInitKey = deferredTypeKey,
                        )
                        .letIf(binding.isScoped()) {
                          // If it's scoped, wrap it in double-check
                          it.doubleCheck(
                            this@generateDeferredSetDelegateCalls,
                            metroSymbols,
                            binding.typeKey,
                          )
                        },
                    ),
                )
              )
            }
          }

          // Apply chunking logic to this shard's property initializers
          if (shardPropertyInitializers.isNotEmpty()) {
            val mustChunkInits =
              options.chunkFieldInits &&
                shardPropertyInitializers.size > options.statementsPerInitFun

            // Create name allocator for init functions on this shard
            val shardFunctionNameAllocator =
              if (shard.isGraphAsShard) {
                functionNameAllocator
              } else {
                NameAllocator(mode = NameAllocator.Mode.COUNT)
              }

            if (mustChunkInits) {
              // Chunk property initializers into multiple init functions
              val chunks =
                buildList<InitStatement> {
                    shardPropertyInitializers.forEach { (property, init) ->
                      val typeKey = shardPropertiesToTypeKeys.getValue(property)
                      add { thisReceiver ->
                        irSetField(
                          irGet(thisReceiver),
                          property.backingField!!,
                          init(thisReceiver, typeKey),
                        )
                      }
                    }
                  }
                  .chunked(options.statementsPerInitFun)

              val initFunctionsToCall =
                chunks.map { statementsChunk ->
                  val initName = shardFunctionNameAllocator.newName("init")
                  targetClass
                    .addFunction(
                      initName,
                      irBuiltIns.unitType,
                      visibility = DescriptorVisibilities.PRIVATE,
                    )
                    .apply {
                      val localReceiver = targetThisReceiver.copyTo(this)
                      setDispatchReceiver(localReceiver)
                      buildBlockBody {
                        for (statement in statementsChunk) {
                          +statement(localReceiver)
                        }
                      }
                    }
                }

              if (shard.isGraphAsShard) {
                // For graph-as-shard, add init calls to main constructor
                constructorStatements += buildList {
                  initFunctionsToCall.forEach { initFunction ->
                    add { dispatchReceiver ->
                      irInvoke(
                        dispatchReceiver = irGet(dispatchReceiver),
                        callee = initFunction.symbol,
                      )
                    }
                  }
                }
              } else {
                // For nested shard, add init calls to shard constructor
                val shardConstructor = targetClass.primaryConstructor!!
                shardConstructor.buildBlockBody {
                  +irDelegatingConstructorCall(irBuiltIns.anyClass.owner.primaryConstructor!!)
                  // Initialize graph property field from constructor parameter (if needed)
                  shard.graphProperty?.backingField?.let { graphBackingField ->
                    +irSetField(
                      irGet(targetThisReceiver),
                      graphBackingField,
                      irGet(shard.graphParam!!),
                    )
                  }
                  initFunctionsToCall.forEach { initFunction ->
                    +irInvoke(
                      dispatchReceiver = irGet(targetThisReceiver),
                      callee = initFunction.symbol,
                    )
                  }
                  // Add setDelegate calls for deferred properties in this shard
                  generateDeferredSetDelegateCalls(targetThisReceiver).forEach { +it }
                }
              }
            } else {
              // Small shard, initialize directly in constructor
              if (shard.isGraphAsShard) {
                // For graph-as-shard, use initFinal (field initializer)
                shardPropertyInitializers.forEach { (property, init) ->
                  property.initFinal {
                    val typeKey = shardPropertiesToTypeKeys.getValue(property)
                    init(thisReceiverParameter, typeKey)
                  }
                }
              } else {
                // For nested shard, set fields in constructor body
                val shardConstructor = targetClass.primaryConstructor!!
                shardConstructor.buildBlockBody {
                  +irDelegatingConstructorCall(irBuiltIns.anyClass.owner.primaryConstructor!!)
                  // Initialize graph property field from constructor parameter (if needed)
                  shard.graphProperty?.backingField?.let { graphBackingField ->
                    +irSetField(
                      irGet(targetThisReceiver),
                      graphBackingField,
                      irGet(shard.graphParam!!),
                    )
                  }
                  for ((property, init) in shardPropertyInitializers) {
                    val typeKey = shardPropertiesToTypeKeys.getValue(property)
                    +irSetField(
                      irGet(targetThisReceiver),
                      property.backingField!!,
                      init(targetThisReceiver, typeKey),
                    )
                  }
                  // Add setDelegate calls for deferred properties in this shard
                  generateDeferredSetDelegateCalls(targetThisReceiver).forEach { +it }
                }
              }
            }
          }

          // For graph-as-shard, add deferred setDelegate calls after property inits
          if (shard.isGraphAsShard && shardDeferredProperties.isNotEmpty()) {
            constructorStatements +=
              shardDeferredProperties.map { (deferredTypeKey, deferredProperty) ->
                { thisReceiver: IrValueParameter ->
                  val binding = bindingGraph.requireBinding(deferredTypeKey)
                  irInvoke(
                    dispatchReceiver = irGetObject(metroSymbols.metroDelegateFactoryCompanion),
                    callee = metroSymbols.metroDelegateFactorySetDelegate,
                    typeArgs = listOf(deferredTypeKey.type),
                    args =
                      listOf(
                        irGetProperty(irGet(thisReceiver), deferredProperty),
                        createIrBuilder(symbol).run {
                          expressionGeneratorFactory
                            .create(thisReceiver, shardContext = shardExprContext)
                            .generateBindingCode(
                              binding,
                              contextualTypeKey = binding.contextualTypeKey.wrapInProvider(),
                              accessType = BindingExpressionGenerator.AccessType.PROVIDER,
                              fieldInitKey = deferredTypeKey,
                            )
                            .letIf(binding.isScoped()) {
                              it.doubleCheck(this@run, metroSymbols, binding.typeKey)
                            }
                        },
                      ),
                  )
                }
              }
          }
        }

        // For nested shards, add shard instantiation to main constructor
        if (!shardResult.isGraphAsShard) {
          for (shardInfo in shardResult.shards) {
            val shardField = shardFields[shardInfo.index]!!
            constructorStatements.add { graphThisReceiver ->
              irSetField(
                irGet(graphThisReceiver),
                shardField.backingField!!,
                irCallConstructor(shardInfo.shardClass.primaryConstructor!!.symbol, emptyList())
                  .apply {
                    // Pass graph instance if shard needs it for cross-shard access
                    if (shardInfo.graphParam != null) {
                      arguments[0] = irGet(graphThisReceiver)
                    }
                  },
              )
            }
          }
          // Note: setDelegate calls for deferred properties happen inside each shard's constructor
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

      traceNested("Implement overrides") { node.implementOverrides(expressionGeneratorFactory) }

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

  // Helper to compute binding metadata
  data class BindingMetadata(
    val binding: IrBinding,
    val propertyKind: PropertyKind,
    val contextKey: IrContextualTypeKey,
    val irType: IrType,
    val nameHint: Name,
    val isProviderType: Boolean,
    val isScoped: Boolean,
  )

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

  private fun DependencyGraphNode.implementOverrides(
    expressionGeneratorFactory: GraphExpressionGenerator.Factory
  ) {
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

            for (type in
              pluginContext
                .referenceClass(binding.targetClassId)!!
                .owner
                .allSupertypesSequence(excludeSelf = false, excludeAny = true)) {
              val clazz = type.rawType()
              val generatedInjector =
                membersInjectorTransformer.getOrGenerateInjector(clazz) ?: continue
              for ((function, unmappedParams) in generatedInjector.declaredInjectFunctions) {
                val parameters =
                  if (typeKey.hasTypeArgs) {
                    val remapper = clazz.typeRemapperFor(wrappedType.type)
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

/**
 * Stores the property used to access the parent graph instance in extension graphs (inner classes).
 * This is used by child extensions to build the ancestor property chain for accessing grandparent
 * bindings.
 */
internal var IrClass.parentGraphInstanceProperty: IrProperty? by irAttribute(copyByDefault = false)

/**
 * Stores the pre-computed ancestor graph property chains for extension graphs. Maps ancestor graph
 * type key -> list of properties to chain through to access that ancestor. Child extensions copy
 * this map and prepend their own parentGraphInstanceProperty.
 */
internal var IrClass.ancestorGraphPropertiesMap: Map<IrTypeKey, List<IrProperty>>? by
  irAttribute(copyByDefault = false)
